package com.twocents.player.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioHistoryStoreTest {
    @Test
    fun loadSnapshot_keepsNewestEventsAndPrunesExpiredOnes() {
        val nowMs = 1_000_000_000_000L
        val eventsJson = JSONArray().apply {
            put(
                JSONObject()
                    .put("trackId", "expired-track")
                    .put("artistKey", "expired-artist")
                    .put("type", RadioFeedbackType.POSITIVE.name)
                    .put("timestampMs", nowMs - RadioHistoryStore.MAX_EVENT_AGE_MS - 1L),
            )
            put(
                JSONObject()
                    .put("trackId", "future-track")
                    .put("artistKey", "future-artist")
                    .put("type", RadioFeedbackType.POSITIVE.name)
                    .put("timestampMs", nowMs + 1L),
            )
            for (index in 0 until (RadioHistoryStore.MAX_EVENTS + 5)) {
                put(
                    JSONObject()
                        .put("trackId", "track-$index")
                        .put("artistKey", "artist-$index")
                        .put("type", RadioFeedbackType.POSITIVE.name)
                        .put("timestampMs", nowMs - 10_000L + index),
                )
            }
        }
        val preferences = FakeSharedPreferences(
            mutableMapOf(KEY_EVENTS to eventsJson.toString()),
        )
        val store = RadioHistoryStore(preferences) { nowMs }

        val snapshot = store.loadSnapshot(nowMs)

        assertEquals(RadioHistoryStore.MAX_EVENTS, snapshot.events.size)
        assertEquals("track-5", snapshot.events.first().trackId)
        assertEquals("track-404", snapshot.events.last().trackId)
        assertFalse(snapshot.events.any { it.trackId == "expired-track" })
        assertFalse(snapshot.events.any { it.trackId == "future-track" })
    }

    @Test
    fun loadSnapshot_splitsPositiveAndNegativeSignals() {
        val nowMs = 1_000_000_000_000L
        val eventsJson = JSONArray().apply {
            put(event("t-1", "a-1", RadioFeedbackType.STRONG_POSITIVE, nowMs - 5_000L))
            put(event("t-2", "a-2", RadioFeedbackType.POSITIVE, nowMs - 4_000L))
            put(event("t-3", "a-3", RadioFeedbackType.REPLAY_POSITIVE, nowMs - 3_000L))
            put(event("t-4", "a-4", RadioFeedbackType.MILD_NEGATIVE, nowMs - 2_000L))
            put(event("t-5", "a-5", RadioFeedbackType.STRONG_NEGATIVE, nowMs - 1_000L))
            put(event("t-2", "a-2", RadioFeedbackType.STRONG_NEGATIVE, nowMs - 500L))
        }
        val preferences = FakeSharedPreferences(
            mutableMapOf(KEY_EVENTS to eventsJson.toString()),
        )
        val store = RadioHistoryStore(preferences) { nowMs }

        val snapshot = store.loadSnapshot(nowMs)

        assertEquals(setOf("t-1", "t-2", "t-3"), snapshot.positiveTrackIds)
        assertEquals(setOf("t-2", "t-4", "t-5"), snapshot.negativeTrackIds)
        assertEquals(setOf("a-1", "a-2", "a-3"), snapshot.positiveArtistKeys)
        assertEquals(setOf("a-2", "a-4", "a-5"), snapshot.negativeArtistKeys)
        assertTrue(snapshot.recentTrackIds.contains("t-5"))
        assertTrue(snapshot.recentArtistKeys.contains("a-5"))
    }

    @Test
    fun recordEvent_appendsAndPersistsWithExpiryCleanupAndMaxBound() {
        val nowMs = 1_000_000_000_000L
        val eventsJson = JSONArray().apply {
            put(event("expired-track", "expired-artist", RadioFeedbackType.POSITIVE, nowMs - RadioHistoryStore.MAX_EVENT_AGE_MS - 1L))
            put(event("future-track", "future-artist", RadioFeedbackType.POSITIVE, nowMs + 1L))
            for (index in 0 until RadioHistoryStore.MAX_EVENTS) {
                put(event("track-$index", "artist-$index", RadioFeedbackType.POSITIVE, nowMs - 10_000L + index))
            }
        }
        val preferences = FakeSharedPreferences(
            mutableMapOf(KEY_EVENTS to eventsJson.toString()),
        )
        val store = RadioHistoryStore(preferences) { nowMs }

        store.recordEvent(
            RadioFeedbackEvent(
                trackId = "new-track",
                artistKey = "new-artist",
                type = RadioFeedbackType.REPLAY_POSITIVE,
                timestampMs = nowMs,
            ),
        )

        val persistedRaw = preferences.getString(KEY_EVENTS, null) ?: error("Expected persisted events json")
        val persisted = JSONArray(persistedRaw)
        val persistedIds = buildList(persisted.length()) {
            for (index in 0 until persisted.length()) {
                add(persisted.getJSONObject(index).getString("trackId"))
            }
        }

        assertEquals(RadioHistoryStore.MAX_EVENTS, persisted.length())
        assertEquals("track-1", persistedIds.first())
        assertEquals("new-track", persistedIds.last())
        assertFalse(persistedIds.contains("expired-track"))
        assertFalse(persistedIds.contains("future-track"))

        val snapshot = store.loadSnapshot(nowMs)
        assertEquals(RadioHistoryStore.MAX_EVENTS, snapshot.events.size)
        assertEquals("track-1", snapshot.events.first().trackId)
        assertEquals("new-track", snapshot.events.last().trackId)
    }

    private fun event(
        trackId: String,
        artistKey: String,
        type: RadioFeedbackType,
        timestampMs: Long,
    ): JSONObject {
        return JSONObject()
            .put("trackId", trackId)
            .put("artistKey", artistKey)
            .put("type", type.name)
            .put("timestampMs", timestampMs)
    }

    private class FakeSharedPreferences(
        private val values: MutableMap<String, Any?> = mutableMapOf(),
    ) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String?, defValue: String?): String? {
            return (values[key] as? String) ?: defValue
        }

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            val value = values[key] as? Set<*>
            @Suppress("UNCHECKED_CAST")
            return (value?.filterIsInstance<String>()?.toMutableSet()) ?: defValues
        }

        override fun getInt(key: String?, defValue: Int): Int = (values[key] as? Int) ?: defValue

        override fun getLong(key: String?, defValue: Long): Long = (values[key] as? Long) ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float = (values[key] as? Float) ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (values[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(values)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    private class FakeEditor(
        private val backing: MutableMap<String, Any?>,
    ) : SharedPreferences.Editor {
        private val staged = mutableMapOf<String, Any?>()
        private var shouldClear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            if (key != null) staged[key] = value
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
            if (key != null) staged[key] = values?.toSet()
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            if (key != null) staged[key] = value
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            if (key != null) staged[key] = value
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            if (key != null) staged[key] = value
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            if (key != null) staged[key] = value
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            if (key != null) staged[key] = REMOVED
        }

        override fun clear(): SharedPreferences.Editor = apply {
            shouldClear = true
            staged.clear()
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (shouldClear) backing.clear()
            staged.forEach { (key, value) ->
                if (value === REMOVED) {
                    backing.remove(key)
                } else {
                    backing[key] = value
                }
            }
            staged.clear()
            shouldClear = false
        }

        private companion object {
            val REMOVED = Any()
        }
    }

    private companion object {
        const val KEY_EVENTS = "radio_feedback_events_v1"
    }
}
