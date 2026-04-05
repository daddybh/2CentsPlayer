package com.twocents.player.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.twocents.player.data.AiRecommendedTrack
import com.twocents.player.data.MusicSearchPage
import com.twocents.player.data.MusicSourceRepository
import com.twocents.player.data.MusicLibraryRepository
import com.twocents.player.data.RadioFeedbackType
import com.twocents.player.data.RadioHistoryStore
import com.twocents.player.data.RadioSessionState
import com.twocents.player.data.Track
import com.twocents.player.data.TrackSource
import com.twocents.player.data.sourceTrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    @Test
    fun toggleHeartMode_whenRadioActive_unwindsToSingleTrackRegularQueue() {
        val application = FakeApplication()
        val viewModel = PlayerViewModel(application)
        val queue = listOf(
            track(id = "radio-1", title = "Radio 1"),
            track(id = "radio-2", title = "Radio 2"),
        )

        viewModel.onPlayerQueueChanged(
            queue = queue,
            currentIndex = 1,
            positionMs = 42_000L,
            durationMs = queue[1].durationMs,
            isPlaying = true,
        )
        viewModel.setPrivateField("radioSession", radioSession(queue))
        viewModel.applyPlaybackSource("AI")

        viewModel.toggleHeartMode()

        val command = viewModel.pendingPlayerCommand as PlayerCommand.LoadTrack
        assertEquals(1, command.queue.size)
        assertEquals(queue[1].id, command.queue.single().id)
        assertEquals(0, command.index)
        assertTrue(command.playWhenReady)
        assertEquals(42_000L, command.startPositionMs)
        assertFalse(viewModel.aiRecommendationState.isActive)
        assertNull(viewModel.getPrivateField("radioSession"))
        assertEquals("REGULAR", viewModel.getPlaybackSource())
        assertEquals(listOf(queue[1].id), viewModel.playbackState.playlist.map { it.id })
    }

    @Test
    fun skippedRadioTrack_isNotRecordedAsPositiveOnFollowingQueueTransition() {
        val application = FakeApplication()
        val viewModel = PlayerViewModel(application)
        val skippedTrack = track(id = "radio-skip", title = "Radio Skip", durationMs = 200_000L)
        val nextTrack = track(id = "radio-next", title = "Radio Next", durationMs = 200_000L)
        val queue = listOf(skippedTrack, nextTrack)

        viewModel.onPlayerQueueChanged(
            queue = queue,
            currentIndex = 0,
            positionMs = 190_000L,
            durationMs = skippedTrack.durationMs,
            isPlaying = true,
        )
        viewModel.setPrivateField("radioSession", radioSession(queue))
        viewModel.applyPlaybackSource("AI")

        viewModel.invokePrivate("recordCurrentAiTrackSkipped")
        viewModel.onPlayerQueueChanged(
            queue = queue,
            currentIndex = 1,
            positionMs = 0L,
            durationMs = nextTrack.durationMs,
            isPlaying = true,
        )

        val history = RadioHistoryStore(
            application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        ).loadSnapshot(System.currentTimeMillis())
        val eventsForSkippedTrack = history.events.filter { it.trackId == skippedTrack.id }

        assertEquals(1, eventsForSkippedTrack.size)
        assertEquals(RadioFeedbackType.MILD_NEGATIVE, eventsForSkippedTrack.single().type)
        assertTrue(history.negativeTrackIds.contains(skippedTrack.id))
        assertFalse(history.positiveTrackIds.contains(skippedTrack.id))
    }

    @Test
    fun prepareTrackForPlayback_resolvesCurrentTrackBeforeRemainingQueue() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val application = FakeApplication()
            val viewModel = PlayerViewModel(application)
            val neteaseSource = RecordingMusicSourceRepository(source = TrackSource.NETEASE)
            viewModel.setPrivateField(
                "musicLibraryRepository",
                MusicLibraryRepository(
                    neteaseRepository = neteaseSource,
                    kuwoRepository = RecordingMusicSourceRepository(source = TrackSource.KUWO),
                ),
            )
            val queue = listOf(
                unresolvedTrack(id = "start"),
                unresolvedTrack(id = "next-1"),
                unresolvedTrack(id = "next-2"),
            )

            viewModel.invokePrepareTrackForPlayback(
                queue = queue,
                index = 0,
                playWhenReady = true,
                startPositionMs = 0L,
                sourceName = "REGULAR",
            )
            advanceUntilIdle()
            var attempts = 0
            while (neteaseSource.resolveCalls.size < 2 && attempts < 50) {
                advanceUntilIdle()
                Thread.sleep(20L)
                attempts += 1
            }

            assertEquals(
                listOf(
                    listOf("netease:start"),
                    listOf("netease:next-1", "netease:next-2"),
                ),
                neteaseSource.resolveCalls,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun radioSession(queue: List<Track>): RadioSessionState {
        return RadioSessionState(
            sessionId = 1L,
            queuedRecommendations = queue.map { AiRecommendedTrack(it, "reason-${it.id}") },
        )
    }

    private fun track(
        id: String,
        title: String,
        durationMs: Long = 180_000L,
    ): Track {
        return Track(
            id = "netease:$id",
            source = TrackSource.NETEASE,
            sourceId = id,
            title = title,
            artist = "Artist $id",
            durationMs = durationMs,
            audioUrl = "https://audio.example/$id.mp3",
        )
    }

    private fun unresolvedTrack(id: String): Track {
        return Track(
            id = "netease:$id",
            source = TrackSource.NETEASE,
            sourceId = id,
            title = "Title $id",
            artist = "Artist $id",
            durationMs = 180_000L,
            audioUrl = "",
        )
    }

    private fun PlayerViewModel.applyPlaybackSource(name: String) {
        val enumValue = playbackSourceEnum(name)
        val method = PlayerViewModel::class.java.getDeclaredMethod("applyPlaybackSource", enumValue.javaClass)
        method.isAccessible = true
        method.invoke(this, enumValue)
    }

    private fun PlayerViewModel.playbackSourceEnum(name: String): Any {
        val field = PlayerViewModel::class.java.getDeclaredField("activePlaybackSource")
        field.isAccessible = true
        return field.type.enumConstants
            ?.first { constant -> (constant as Enum<*>).name == name }
            ?: error("Missing playback source enum $name")
    }

    private fun PlayerViewModel.getPlaybackSource(): String {
        val field = PlayerViewModel::class.java.getDeclaredField("activePlaybackSource")
        field.isAccessible = true
        return (field.get(this) as Enum<*>).name
    }

    private fun PlayerViewModel.setPrivateField(
        name: String,
        value: Any?,
    ) {
        val field = PlayerViewModel::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(this, value)
    }

    private fun PlayerViewModel.getPrivateField(name: String): Any? {
        val field = PlayerViewModel::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(this)
    }

    private fun PlayerViewModel.invokePrivate(name: String) {
        val method = PlayerViewModel::class.java.getDeclaredMethod(name)
        method.isAccessible = true
        method.invoke(this)
    }

    private fun PlayerViewModel.invokePrepareTrackForPlayback(
        queue: List<Track>,
        index: Int,
        playWhenReady: Boolean,
        startPositionMs: Long,
        sourceName: String,
    ) {
        val enumValue = playbackSourceEnum(sourceName)
        val method = PlayerViewModel::class.java.getDeclaredMethod(
            "prepareTrackForPlayback",
            List::class.java,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            enumValue.javaClass,
        )
        method.isAccessible = true
        method.invoke(this, queue, index, playWhenReady, startPositionMs, enumValue)
    }

    private class FakeApplication : Application() {
        private val preferencesByName = mutableMapOf<String, SharedPreferences>()

        override fun getSharedPreferences(
            name: String?,
            mode: Int,
        ): SharedPreferences {
            val safeName = name ?: error("SharedPreferences name required")
            return preferencesByName.getOrPut(safeName) { FakeSharedPreferences() }
        }
    }

    private class RecordingMusicSourceRepository(
        override val source: TrackSource,
    ) : MusicSourceRepository {
        val resolveCalls = mutableListOf<List<String>>()

        override fun searchTracks(
            keyword: String,
            limit: Int,
            offset: Int,
        ): List<Track> = emptyList()

        override fun findBestMatchTrack(
            title: String,
            artist: String,
        ): Track? = null

        override fun fetchLyrics(track: Track): String? = null

        override fun resolvePlayableTracks(tracks: List<Track>): List<Track> {
            resolveCalls += tracks.map { it.id }
            return tracks.map { track ->
                track.copy(audioUrl = "https://audio.example/${track.sourceTrackId()}.mp3")
            }
        }
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
        const val PREFERENCES_NAME = "two_cents_player"
    }
}
