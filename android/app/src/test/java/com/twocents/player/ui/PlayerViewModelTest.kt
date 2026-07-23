package com.twocents.player.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.twocents.player.data.AiRecommendedTrack
import com.twocents.player.data.AiSuggestedTrack
import com.twocents.player.data.MusicSearchPage
import com.twocents.player.data.MusicSourceRepository
import com.twocents.player.data.MusicLibraryRepository
import com.twocents.player.data.RadioCandidateSource
import com.twocents.player.data.RadioFeedbackType
import com.twocents.player.data.RadioHistoryStore
import com.twocents.player.data.RadioReplenishmentEngine
import com.twocents.player.data.RadioSessionState
import com.twocents.player.data.RadioSessionStore
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

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
        val sessionStore = RadioSessionStore(application)
        sessionStore.saveRecommendations(
            queue.map { AiRecommendedTrack(track = it, reason = "test") },
        )

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
        assertEquals(
            listOf(nextTrack.id),
            sessionStore.loadRecommendations().map { it.track.id },
        )
    }

    @Test
    fun prepareTrackForPlayback_resolvesCurrentTrackBeforeRemainingQueue() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
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
            unresolvedTrack(id = "start").copy(
                album = "Album start",
                coverUrl = "https://cover.example/start.jpg",
            ),
            unresolvedTrack(id = "next-1").copy(
                album = "Album next-1",
                coverUrl = "https://cover.example/next-1.jpg",
            ),
            unresolvedTrack(id = "next-2").copy(
                album = "Album next-2",
                coverUrl = "https://cover.example/next-2.jpg",
            ),
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
    }

    @Test
    fun prepareTrackForPlayback_radioFallsForwardWhenRequestedTrackIsUnavailable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val application = FakeApplication()
        val viewModel = PlayerViewModel(application)
        val neteaseSource = RecordingMusicSourceRepository(
            source = TrackSource.NETEASE,
            unplayableTrackIds = setOf("netease:blocked"),
        )
        viewModel.setPrivateField(
            "musicLibraryRepository",
            MusicLibraryRepository(
                neteaseRepository = neteaseSource,
                kuwoRepository = RecordingMusicSourceRepository(source = TrackSource.KUWO),
            ),
        )
        val queue = listOf(
            unresolvedTrack("blocked").copy(
                album = "Blocked Album",
                coverUrl = "https://cover.example/blocked.jpg",
            ),
            unresolvedTrack("playable-next").copy(
                album = "Next Album",
                coverUrl = "https://cover.example/playable-next.jpg",
            ),
        )

        viewModel.invokePrepareTrackForPlayback(
            queue = queue,
            index = 0,
            playWhenReady = true,
            startPositionMs = 0L,
            sourceName = "AI",
        )
        advanceUntilIdle()
        var attempts = 0
        while (
            (
                viewModel.playbackState.currentTrack?.id != "netease:playable-next" ||
                    viewModel.playbackState.currentTrack?.audioUrl.isNullOrBlank()
            ) &&
            attempts < 50
        ) {
            advanceUntilIdle()
            Thread.sleep(20L)
            attempts += 1
        }

        assertEquals("netease:playable-next", viewModel.playbackState.currentTrack?.id)
        assertTrue(viewModel.playbackState.currentTrack?.audioUrl?.isNotBlank() == true)
        val command = viewModel.pendingPlayerCommand as PlayerCommand.LoadTrack
        assertEquals(1, command.index)
    }

    @Test
    fun queueMoreRadioRecommendations_resolvesFirstAppendedTrackBeforeAdvancing() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val application = FakeApplication()
        val viewModel = PlayerViewModel(application)
        val neteaseSource = RecordingMusicSourceRepository(source = TrackSource.NETEASE)
        val library = MusicLibraryRepository(
            neteaseRepository = neteaseSource,
            kuwoRepository = RecordingMusicSourceRepository(source = TrackSource.KUWO),
        )
        val appendedTrack = unresolvedTrack("appended").copy(
            album = "Appended Album",
            coverUrl = "https://cover.example/appended.jpg",
        )
        val fastSource = object : RadioCandidateSource {
            override fun requestRadioCandidates(
                settings: com.twocents.player.data.AiServiceConfig,
                request: com.twocents.player.data.RadioRecommendationRequest,
            ): List<AiSuggestedTrack> {
                return listOf(
                    AiSuggestedTrack(
                        title = appendedTrack.title,
                        artist = appendedTrack.artist,
                        resolvedTrack = appendedTrack,
                    ),
                )
            }
        }
        viewModel.setPrivateField("musicLibraryRepository", library)
        viewModel.setPrivateField(
            "radioEngine",
            RadioReplenishmentEngine(
                candidateSource = object : RadioCandidateSource {
                    override fun requestRadioCandidates(
                        settings: com.twocents.player.data.AiServiceConfig,
                        request: com.twocents.player.data.RadioRecommendationRequest,
                    ): List<AiSuggestedTrack> = emptyList()
                },
                fastCandidateSource = fastSource,
                trackLookup = library,
            ),
        )
        val current = track("current", "Current")
        viewModel.onPlayerQueueChanged(
            queue = listOf(current),
            currentIndex = 0,
            positionMs = 30_000L,
            durationMs = current.durationMs,
            isPlaying = true,
        )
        viewModel.setPrivateFavoritesState(FavoritesUiState(tracks = listOf(track("seed", "Seed"))))
        viewModel.setPrivateField("radioSession", radioSession(listOf(current)))
        viewModel.applyPlaybackSource("AI")

        viewModel.invokeQueueMoreRecommendations(force = true, advanceToFirstNewTrack = true)
        advanceUntilIdle()
        var attempts = 0
        while (
            (
                viewModel.playbackState.currentTrack?.id != "netease:appended" ||
                    viewModel.playbackState.currentTrack?.audioUrl.isNullOrBlank()
            ) &&
            attempts < 50
        ) {
            advanceUntilIdle()
            Thread.sleep(20L)
            attempts += 1
        }

        assertEquals("netease:appended", viewModel.playbackState.currentTrack?.id)
        assertTrue(viewModel.playbackState.currentTrack?.audioUrl?.isNotBlank() == true)
    }

    @Test
    fun prepareTrackForPlayback_enrichesCurrentTrackArtworkFromAlternateSourceMetadata() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val application = FakeApplication()
        val viewModel = PlayerViewModel(application)
        val targetTrack = Track(
            id = "netease:art",
            source = TrackSource.NETEASE,
            sourceId = "art",
            title = "Title art",
            artist = "Artist art",
            durationMs = 180_000L,
            audioUrl = "https://audio.example/art.mp3",
        )
        val kuwoMatch = Track(
            id = "kuwo:art-alt",
            source = TrackSource.KUWO,
            sourceId = "art-alt",
            title = "Title art",
            artist = "Artist art",
            album = "Artwork Album",
            coverUrl = "https://cover.example/art.jpg",
        )
        viewModel.setPrivateField(
            "musicLibraryRepository",
            MusicLibraryRepository(
                neteaseRepository = RecordingMusicSourceRepository(source = TrackSource.NETEASE),
                kuwoRepository = RecordingMusicSourceRepository(
                    source = TrackSource.KUWO,
                    bestMatch = kuwoMatch,
                ),
            ),
        )

        viewModel.invokePrepareTrackForPlayback(
            queue = listOf(targetTrack),
            index = 0,
            playWhenReady = true,
            startPositionMs = 0L,
            sourceName = "REGULAR",
        )
        advanceUntilIdle()
        var attempts = 0
        while (viewModel.playbackState.currentTrack?.coverUrl.isNullOrBlank() && attempts < 50) {
            advanceUntilIdle()
            Thread.sleep(20L)
            attempts += 1
        }

        assertEquals("https://cover.example/art.jpg", viewModel.playbackState.currentTrack?.coverUrl)
        assertEquals("Artwork Album", viewModel.playbackState.currentTrack?.album)
        assertEquals("https://cover.example/art.jpg", viewModel.playbackState.playlist.single().coverUrl)
    }

    @Test
    fun openSearch_setsSearchPageVisible() {
        val application = FakeApplication()
        val viewModel = PlayerViewModel(application)

        assertFalse(viewModel.isSearchPageVisible)

        viewModel.openSearch()

        assertTrue(viewModel.isSearchPageVisible)
    }

    @Test
    fun openSearch_keepsExistingSearchQueryAndResults() {
        val application = FakeApplication()
        val viewModel = PlayerViewModel(application)
        val existingResults = listOf(
            track(id = "existing-1", title = "Existing 1"),
            track(id = "existing-2", title = "Existing 2"),
        )
        viewModel.setPrivateSearchState(
            viewModel.searchState.copy(
                query = "existing query",
                activeQuery = "existing query",
                results = existingResults,
                hasSearched = true,
            ),
        )
        val existingState = viewModel.searchState

        viewModel.openSearch()

        assertEquals(existingState, viewModel.searchState)
        assertTrue(viewModel.isSearchPageVisible)
    }

    @Test
    fun closeSearch_hidesSearchPage() {
        val application = FakeApplication()
        val viewModel = PlayerViewModel(application)

        viewModel.openSearch()
        assertTrue(viewModel.isSearchPageVisible)

        viewModel.closeSearch()

        assertFalse(viewModel.isSearchPageVisible)
    }

    @Test
    fun selectTrack_closesSearchPageAfterQueueingPlayback() {
        val application = FakeApplication()
        val viewModel = PlayerViewModel(application)
        val track = track(id = "search-track", title = "Search Track")

        viewModel.openSearch()
        assertTrue(viewModel.isSearchPageVisible)

        viewModel.selectTrack(track)

        assertFalse(viewModel.isSearchPageVisible)
        assertEquals(track.id, viewModel.playbackState.currentTrack?.id)
    }

    @Test
    fun selectTrack_keepsSearchDataButReturnsToHomeLayer() {
        val application = FakeApplication()
        val viewModel = PlayerViewModel(application)
        val results = listOf(
            track(id = "search-1", title = "Search 1"),
            track(id = "search-2", title = "Search 2"),
        )
        viewModel.setPrivateSearchState(
            viewModel.searchState.copy(
                query = "search query",
                activeQuery = "search query",
                results = results,
                hasSearched = true,
            ),
        )
        viewModel.openSearch()

        viewModel.selectTrack(results.first())

        assertFalse(viewModel.isSearchPageVisible)
        assertEquals("search query", viewModel.searchState.query)
        assertEquals("search query", viewModel.searchState.activeQuery)
        assertEquals(results.map { it.id }, viewModel.searchState.results.map { it.id })
        assertEquals(results.first().id, viewModel.playbackState.currentTrack?.id)
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

    private fun PlayerViewModel.setPrivateSearchState(state: SearchUiState) {
        val method = PlayerViewModel::class.java.getDeclaredMethod("setSearchState", SearchUiState::class.java)
        method.isAccessible = true
        method.invoke(this, state)
    }

    private fun PlayerViewModel.setPrivateFavoritesState(state: FavoritesUiState) {
        val method = PlayerViewModel::class.java.getDeclaredMethod("setFavoritesState", FavoritesUiState::class.java)
        method.isAccessible = true
        method.invoke(this, state)
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

    private fun PlayerViewModel.invokeQueueMoreRecommendations(
        force: Boolean,
        advanceToFirstNewTrack: Boolean,
    ) {
        val method = PlayerViewModel::class.java.getDeclaredMethod(
            "queueMoreAiRecommendations",
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(this, force, advanceToFirstNewTrack)
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
        private val bestMatch: Track? = null,
        private val searchResults: List<Track> = emptyList(),
        private val unplayableTrackIds: Set<String> = emptySet(),
    ) : MusicSourceRepository {
        val resolveCalls = mutableListOf<List<String>>()

        override fun searchTracks(
            keyword: String,
            limit: Int,
            offset: Int,
        ): List<Track> = searchResults.drop(offset).take(limit)

        override fun findBestMatchTrack(
            title: String,
            artist: String,
        ): Track? = bestMatch

        override fun fetchLyrics(track: Track): String? = null

        override fun resolvePlayableTracks(tracks: List<Track>): List<Track> {
            resolveCalls += tracks.map { it.id }
            return tracks.map { track ->
                if (track.id in unplayableTrackIds) {
                    track.copy(audioUrl = "")
                } else {
                    track.copy(audioUrl = "https://audio.example/${track.sourceTrackId()}.mp3")
                }
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
