package com.twocents.player.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class RadioHistoryStore(
    private val preferences: SharedPreferences,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun loadSnapshot(nowMs: Long = clock()): RadioHistorySnapshot {
        val storedEvents = readEvents()
        val retainedEvents = pruneAndBound(storedEvents, nowMs)
        if (retainedEvents.size != storedEvents.size) {
            persistEvents(retainedEvents)
        }

        val effectiveEvents = retainedEvents.filter { event ->
            event.isEffectiveAt(nowMs)
        }
        val positiveTrackIds = linkedSetOf<String>()
        val negativeTrackIds = linkedSetOf<String>()
        val positiveArtistKeys = linkedSetOf<String>()
        val negativeArtistKeys = linkedSetOf<String>()

        retainedEvents.associateBy(RadioFeedbackEvent::trackId).values.forEach { event ->
            if (!event.isEffectiveAt(nowMs)) return@forEach
            when (event.type) {
                RadioFeedbackType.STRONG_POSITIVE,
                RadioFeedbackType.POSITIVE,
                RadioFeedbackType.REPLAY_POSITIVE,
                -> {
                    positiveTrackIds += event.trackId
                }

                RadioFeedbackType.MILD_NEGATIVE,
                RadioFeedbackType.STRONG_NEGATIVE,
                -> {
                    negativeTrackIds += event.trackId
                }
            }
        }

        retainedEvents.associateBy(RadioFeedbackEvent::artistKey).values.forEach { event ->
            if (!event.isEffectiveAt(nowMs)) return@forEach
            when (event.type) {
                RadioFeedbackType.STRONG_POSITIVE,
                RadioFeedbackType.POSITIVE,
                RadioFeedbackType.REPLAY_POSITIVE,
                -> positiveArtistKeys += event.artistKey

                RadioFeedbackType.MILD_NEGATIVE,
                RadioFeedbackType.STRONG_NEGATIVE,
                -> negativeArtistKeys += event.artistKey
            }
        }

        return RadioHistorySnapshot(
            events = effectiveEvents,
            positiveTrackIds = positiveTrackIds,
            negativeTrackIds = negativeTrackIds,
            positiveArtistKeys = positiveArtistKeys,
            negativeArtistKeys = negativeArtistKeys,
            recentTrackIds = effectiveEvents.asReversed().map { it.trackId }.distinct(),
            recentArtistKeys = effectiveEvents.asReversed().map { it.artistKey }.distinct(),
        )
    }

    fun recordEvent(event: RadioFeedbackEvent) {
        val retainedEvents = pruneAndBound(readEvents() + event, clock())
        persistEvents(retainedEvents)
    }

    fun removeLatestNegativeEvent(trackId: String): Boolean {
        if (trackId.isBlank()) return false

        val events = readEvents().toMutableList()
        val eventIndex = events.indexOfLast { event ->
            event.trackId == trackId &&
                event.type in setOf(
                    RadioFeedbackType.MILD_NEGATIVE,
                    RadioFeedbackType.STRONG_NEGATIVE,
                )
        }
        if (eventIndex < 0) return false

        events.removeAt(eventIndex)
        persistEvents(pruneAndBound(events, clock()))
        return true
    }

    fun loadRecentSeedIds(): List<String> {
        val raw = preferences.getString(KEY_RECENT_SEED_IDS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                array.optString(index)
                    .trim()
                    .takeIf(String::isNotBlank)
                    ?.let(::add)
            }
        }.distinct().takeLast(MAX_RECENT_SEEDS)
    }

    fun recordSeedExposure(seedIds: Collection<String>) {
        val normalizedSeedIds = seedIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        if (normalizedSeedIds.isEmpty()) return

        val exposedSet = normalizedSeedIds.toSet()
        val updatedSeedIds = (
            loadRecentSeedIds().filterNot { it in exposedSet } + normalizedSeedIds
        ).takeLast(MAX_RECENT_SEEDS)
        val array = JSONArray()
        updatedSeedIds.forEach(array::put)
        preferences.edit().putString(KEY_RECENT_SEED_IDS, array.toString()).apply()
    }

    private fun readEvents(): List<RadioFeedbackEvent> {
        val raw = preferences.getString(KEY_EVENTS, null) ?: return emptyList()
        val jsonArray = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList(jsonArray.length()) {
            for (index in 0 until jsonArray.length()) {
                val item = jsonArray.optJSONObject(index) ?: continue
                val trackId = item.optString(KEY_TRACK_ID).orEmpty()
                val artistKey = item.optString(KEY_ARTIST_KEY).orEmpty()
                val type = item.optString(KEY_TYPE).orEmpty()
                    .let { runCatching { RadioFeedbackType.valueOf(it) }.getOrNull() }
                    ?: continue
                if (trackId.isBlank() || artistKey.isBlank()) continue
                add(
                    RadioFeedbackEvent(
                        trackId = trackId,
                        artistKey = artistKey,
                        type = type,
                        timestampMs = item.optLong(KEY_TIMESTAMP_MS),
                    ),
                )
            }
        }
    }

    private fun pruneAndBound(
        events: List<RadioFeedbackEvent>,
        nowMs: Long,
    ): List<RadioFeedbackEvent> {
        val cutoffMs = nowMs - MAX_EVENT_AGE_MS
        return events.asSequence()
            .filter { it.timestampMs in cutoffMs..nowMs }
            .sortedBy { it.timestampMs }
            .toList()
            .takeLast(MAX_EVENTS)
    }

    private fun persistEvents(events: List<RadioFeedbackEvent>) {
        val array = JSONArray()
        events.forEach { event ->
            array.put(
                JSONObject()
                    .put(KEY_TRACK_ID, event.trackId)
                    .put(KEY_ARTIST_KEY, event.artistKey)
                    .put(KEY_TYPE, event.type.name)
                    .put(KEY_TIMESTAMP_MS, event.timestampMs),
            )
        }
        preferences.edit().putString(KEY_EVENTS, array.toString()).apply()
    }

    private fun RadioFeedbackEvent.isEffectiveAt(nowMs: Long): Boolean {
        return type != RadioFeedbackType.MILD_NEGATIVE ||
            timestampMs >= nowMs - MILD_NEGATIVE_EFFECTIVE_AGE_MS
    }

    companion object {
        const val MAX_EVENTS = 400
        const val MAX_EVENT_AGE_MS = 30L * 24L * 60L * 60L * 1000L
        const val MILD_NEGATIVE_EFFECTIVE_AGE_MS = 7L * 24L * 60L * 60L * 1000L
        const val MAX_RECENT_SEEDS = 9

        private const val PREFERENCES_NAME = "two_cents_player"
        private const val KEY_EVENTS = "radio_feedback_events_v1"
        private const val KEY_RECENT_SEED_IDS = "radio_recent_seed_ids_v1"
        private const val KEY_TRACK_ID = "trackId"
        private const val KEY_ARTIST_KEY = "artistKey"
        private const val KEY_TYPE = "type"
        private const val KEY_TIMESTAMP_MS = "timestampMs"

        fun fromContext(context: Context): RadioHistoryStore {
            val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            return RadioHistoryStore(preferences)
        }
    }
}
