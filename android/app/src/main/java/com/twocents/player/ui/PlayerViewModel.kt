package com.twocents.player.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.twocents.player.data.AiRecommendationRepository
import com.twocents.player.data.AiRecommendedTrack
import com.twocents.player.data.AiServiceConfig
import com.twocents.player.data.AiSettingsStore
import com.twocents.player.data.FavoritesStore
import com.twocents.player.data.MusicLibraryRepository
import com.twocents.player.data.PlaybackState
import com.twocents.player.data.RadioFeedbackEvent
import com.twocents.player.data.RadioFeedbackType
import com.twocents.player.data.RadioHistoryStore
import com.twocents.player.data.RadioRecommendationRequest
import com.twocents.player.data.RadioReplenishmentEngine
import com.twocents.player.data.RadioSessionState
import com.twocents.player.data.RadioWaveTargets
import com.twocents.player.data.Track
import com.twocents.player.data.classifyCompletion
import com.twocents.player.data.classifySkip
import com.twocents.player.data.withCanonicalIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

class PlayerViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private companion object {
        const val AI_PREFETCH_THRESHOLD = 3
        const val RADIO_REPLAY_SEEK_THRESHOLD_MS = 5_000L
        const val RADIO_LOADING_LABEL = "正在准备电台中"
        const val RADIO_ENDED_LABEL = "探索电台已结束"
        const val RADIO_DEGRADED_LABEL = "暂时没有更多歌曲"
        const val RADIO_START_MIN_APPEND = 1
        const val RADIO_START_RAW_CANDIDATE_LIMIT = 4
        const val SEARCH_PAGE_SIZE = 20
        val RADIO_START_WAVE_TARGETS = RadioWaveTargets(
            safeCount = 1,
            adjacentCount = 1,
            surpriseCount = 0,
        )
        val LYRIC_TIMESTAMP_REGEX = Regex("""\[(\d{2}):(\d{2})(?:\.(\d{1,3}))?]""")
        val LYRIC_CREDIT_ROLES = setOf(
            "作词",
            "作曲",
            "编曲",
            "制作人",
            "和声编写",
            "和声",
            "吉他",
            "贝斯",
            "鼓",
            "录音工程",
            "录音助理",
            "混音",
            "母带",
            "配唱制作人",
            "人声编辑",
            "监制",
        )
    }

    private data class LyricsContent(
        val credits: List<LyricCredit>,
        val lines: List<LyricLine>,
    )

    private enum class PlaybackSource {
        REGULAR,
        AI,
    }

    private val aiRecommendationRepository = AiRecommendationRepository()
    private val aiSettingsStore = AiSettingsStore(application)
    private val musicLibraryRepository = MusicLibraryRepository()
    private val favoritesStore = FavoritesStore(application)
    private val radioHistoryStore = RadioHistoryStore.fromContext(application)
    private val radioEngine = RadioReplenishmentEngine(
        candidateSource = aiRecommendationRepository,
        trackLookup = musicLibraryRepository,
    )
    private val shuffleRandom = Random(System.currentTimeMillis())
    private val lyricsCache = mutableMapOf<String, LyricsContent>()

    private var latestSearchRequestId = 0L
    private var latestPlaybackRequestId = 0L
    private var latestPlayerCommandId = 0L
    private var latestLyricsRequestId = 0L
    private var latestAiSessionId = 0L

    private var activePlaybackSource = PlaybackSource.REGULAR
    private var radioSession: RadioSessionState? = null
    private var latestCompletedRadioTrackId: String? = null
    private var suppressedRadioCompletionTrackId: String? = null

    private val initialAiSettings = aiSettingsStore.loadSettings()
    private val initialFavorites = favoritesStore.loadFavorites().map { track ->
        track.withCanonicalIdentity().copy(isFavorite = true)
    }

    var playbackState by mutableStateOf(
        PlaybackState(),
    )
        private set

    var searchState by mutableStateOf(SearchUiState())
        private set

    var favoritesState by mutableStateOf(FavoritesUiState(tracks = initialFavorites))
        private set

    var lyricsState by mutableStateOf(LyricsUiState())
        private set

    var aiSettingsState by mutableStateOf(
        AiSettingsUiState(
            endpoint = initialAiSettings.endpoint,
            model = initialAiSettings.model,
            accessKey = initialAiSettings.accessKey,
        ),
    )
        private set

    var aiRecommendationState by mutableStateOf(AiRecommendationUiState())
        private set

    var pendingPlayerCommand by mutableStateOf<PlayerCommand?>(null)
        private set

    init {
        if (initialAiSettings.isComplete && initialFavorites.isNotEmpty()) {
            refreshAiRecommendations()
        }
    }

    fun togglePlayPause() {
        val currentTrack = playbackState.currentTrack ?: return
        if (playbackState.isPreparing) return

        if (currentTrack.audioUrl.isBlank()) {
            prepareTrackForPlayback(
                queue = playbackState.playlist.ifEmpty { listOf(currentTrack) },
                index = playbackState.currentIndex.coerceAtLeast(0),
                playWhenReady = true,
                startPositionMs = playbackState.currentPositionMs,
                source = activePlaybackSource,
            )
            return
        }

        val shouldPlay = !playbackState.isPlaying
        playbackState = playbackState.copy(
            isPlaying = shouldPlay,
            statusMessage = null,
        )
        emitPlayerCommand(PlayerCommand.SetPlayWhenReady(nextPlayerCommandId(), shouldPlay))
    }

    fun skipNext() {
        val playlist = playbackState.playlist
        if (playlist.isEmpty()) return
        recordCurrentAiTrackSkipped()
        if (activePlaybackSource == PlaybackSource.AI && playbackState.currentIndex >= playlist.lastIndex) {
            maybeAutoQueueMoreAiRecommendations(
                force = true,
                advanceToFirstNewTrack = true,
            )
            return
        }
        val nextIndex = if (activePlaybackSource == PlaybackSource.AI) {
            (playbackState.currentIndex + 1).coerceAtMost(playlist.lastIndex)
        } else {
            (playbackState.currentIndex + 1) % playlist.size
        }
        prepareTrackForPlayback(
            queue = playlist,
            index = nextIndex,
            playWhenReady = true,
            source = activePlaybackSource,
        )
    }

    fun skipPrevious() {
        val playlist = playbackState.playlist
        if (playlist.isEmpty()) return
        val prevIndex = if (activePlaybackSource == PlaybackSource.AI) {
            (playbackState.currentIndex - 1).coerceAtLeast(0)
        } else if (playbackState.currentIndex > 0) {
            playbackState.currentIndex - 1
        } else {
            playlist.lastIndex
        }
        prepareTrackForPlayback(
            queue = playlist,
            index = prevIndex,
            playWhenReady = true,
            source = activePlaybackSource,
        )
    }

    fun toggleFavorite() {
        playbackState.currentTrack?.let(::toggleFavorite)
    }

    fun toggleFavorite(track: Track) {
        val normalizedTrack = normalizeTrack(track)
        val wasFavorite = normalizedTrack.isFavorite
        val existingFavorites = favoritesState.tracks.filterNot { it.id == normalizedTrack.id }
        val updatedFavorites = if (normalizedTrack.isFavorite) {
            existingFavorites
        } else {
            listOf(normalizedTrack.copy(isFavorite = true, audioUrl = "")) + existingFavorites
        }

        favoritesStore.saveFavorites(updatedFavorites)
        favoritesState = favoritesState.copy(tracks = updatedFavorites)
        syncFavoriteFlags()
        if (!wasFavorite) {
            recordAiTrackFavorited(normalizedTrack)
        }

        if (updatedFavorites.isEmpty()) {
            clearAiRecommendations(errorMessage = "先收藏几首歌，再生成 AI 推荐。")
        }
    }

    fun seekTo(positionMs: Long) {
        if (playbackState.currentTrack == null) return
        maybeRecordAiReplay(positionMs)
        playbackState = playbackState.copy(currentPositionMs = positionMs)
        emitPlayerCommand(
            PlayerCommand.SeekTo(
                id = nextPlayerCommandId(),
                positionMs = positionMs,
            ),
        )
    }

    fun openSearch() {
        favoritesState = favoritesState.copy(isVisible = false)
        lyricsState = lyricsState.copy(isVisible = false)
        aiSettingsState = aiSettingsState.copy(isVisible = false)
        searchState = searchState.copy(isVisible = true)
    }

    fun closeSearch() {
        searchState = searchState.copy(isVisible = false)
    }

    fun openFavorites() {
        searchState = searchState.copy(isVisible = false)
        lyricsState = lyricsState.copy(isVisible = false)
        aiSettingsState = aiSettingsState.copy(isVisible = false)
        favoritesState = favoritesState.copy(isVisible = true)
    }

    fun closeFavorites() {
        favoritesState = favoritesState.copy(isVisible = false)
    }

    fun openLyricsScreen() {
        aiSettingsState = aiSettingsState.copy(isVisible = false)
        lyricsState = lyricsState.copy(isVisible = true)
        loadLyricsForCurrentTrack()
    }

    fun closeLyricsScreen() {
        lyricsState = lyricsState.copy(isVisible = false)
    }

    fun openAiSettings() {
        searchState = searchState.copy(isVisible = false)
        favoritesState = favoritesState.copy(isVisible = false)
        lyricsState = lyricsState.copy(isVisible = false)
        loadStoredAiSettings(isVisible = true)
    }

    fun closeAiSettings() {
        loadStoredAiSettings()
    }

    fun updateAiEndpoint(endpoint: String) {
        aiSettingsState = aiSettingsState.copy(endpoint = endpoint)
    }

    fun updateAiModel(model: String) {
        aiSettingsState = aiSettingsState.copy(model = model)
    }

    fun updateAiAccessKey(accessKey: String) {
        aiSettingsState = aiSettingsState.copy(accessKey = accessKey)
    }

    fun saveAiSettings() {
        val config = buildAiServiceConfig()
        aiSettingsStore.saveSettings(config)
        aiSettingsState = AiSettingsUiState(
            endpoint = config.endpoint,
            model = config.model,
            accessKey = config.accessKey,
        )

        if (config.isComplete) {
            refreshAiRecommendations()
        } else {
            clearAiRecommendations()
        }
    }

    fun toggleHeartMode() {
        if (activePlaybackSource == PlaybackSource.AI) {
            unwindRadioPlaybackToRegularQueue(
                track = playbackState.currentTrack,
                playWhenReady = playbackState.isPlaying,
                startPositionMs = playbackState.currentPositionMs,
            )
        } else {
            playAiRecommendations()
        }
    }

    fun playAiRecommendations() {
        if (aiRecommendationState.isLoading) return
        refreshAiRecommendations(playAfterRefresh = true)
    }

    fun refreshAiRecommendations(playAfterRefresh: Boolean = false) {
        if (activePlaybackSource == PlaybackSource.AI && radioSession != null) {
            queueMoreAiRecommendations(force = true)
            return
        }

        if (aiRecommendationState.isLoading) return

        val config = buildAiServiceConfig()
        if (!config.isComplete) {
            aiRecommendationState = aiRecommendationState.copy(
                isLoading = false,
                errorMessage = "先在设置里填好 AI 接口、模型和 Access Key。",
            )
            if (playAfterRefresh) {
                openAiSettings()
            }
            return
        }

        val favorites = favoritesState.tracks.map(::normalizeTrack)
        if (favorites.isEmpty()) {
            clearAiRecommendations(errorMessage = "先收藏几首歌，再生成 AI 推荐。")
            return
        }

        viewModelScope.launch {
            aiRecommendationState = aiRecommendationState.copy(
                isLoading = true,
                isLoadingMore = false,
                errorMessage = null,
                statusLabel = RADIO_LOADING_LABEL,
                isDegraded = false,
            )

            runCatching {
                withContext(Dispatchers.IO) {
                    radioEngine.replenish(
                        settings = config,
                        favorites = favorites,
                        history = radioHistoryStore.loadSnapshot(),
                        session = RadioSessionState(sessionId = nextAiSessionId()),
                        minimumRequiredAppend = if (playAfterRefresh) {
                            RADIO_START_MIN_APPEND
                        } else {
                            RadioReplenishmentEngine.MIN_SAFE_APPEND
                        },
                        requestTransform = if (playAfterRefresh) {
                            { request: RadioRecommendationRequest ->
                                request.copy(
                                    waveTargets = RADIO_START_WAVE_TARGETS,
                                    rawCandidateLimit = RADIO_START_RAW_CANDIDATE_LIMIT,
                                )
                            }
                        } else {
                            { request: RadioRecommendationRequest -> request }
                        },
                    )
                }
            }.onSuccess { result ->
                val normalizedTracks = result.updatedSession.queuedRecommendations.map { recommendation ->
                    recommendation.copy(track = normalizeTrack(recommendation.track))
                }
                val normalizedSession = result.updatedSession.copy(
                    queuedRecommendations = normalizedTracks,
                )
                radioSession = normalizedSession
                latestCompletedRadioTrackId = null
                suppressedRadioCompletionTrackId = null

                aiRecommendationState = aiRecommendationState.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    tracks = normalizedTracks,
                    errorMessage = if (normalizedTracks.isEmpty()) {
                        "探索电台暂时没准备好。"
                    } else {
                        null
                    },
                    sourceFavoriteCount = favorites.size,
                    suggestionCount = result.suggestionCount,
                    skippedCount = normalizedSession.skippedTrackIds.size,
                    statusLabel = if (normalizedTracks.isEmpty()) {
                        RADIO_DEGRADED_LABEL
                    } else {
                        normalizedSession.statusLabel
                    },
                    isDegraded = normalizedTracks.isEmpty(),
                )

                if (playAfterRefresh && normalizedTracks.isNotEmpty()) {
                    playResolvedAiRecommendations(normalizedTracks)
                    if (normalizedTracks.size < RadioReplenishmentEngine.MIN_SAFE_APPEND) {
                        queueMoreAiRecommendations(force = true)
                    }
                } else if (playAfterRefresh && normalizedTracks.isEmpty()) {
                    stopRadioPlayback(
                        statusLabel = RADIO_DEGRADED_LABEL,
                        isDegraded = true,
                    )
                }
            }.onFailure { error ->
                radioSession = null
                aiRecommendationState = aiRecommendationState.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    statusLabel = null,
                    isDegraded = false,
                    errorMessage = error.message ?: "探索电台启动失败，请稍后重试。",
                )
            }
        }
    }

    fun updateSearchQuery(query: String) {
        val trimmedQuery = query.trim()
        val isEditingNewQuery = trimmedQuery != searchState.activeQuery
        if (isEditingNewQuery) {
            latestSearchRequestId += 1L
        }

        searchState = searchState.copy(
            query = query,
            isLoading = if (isEditingNewQuery) false else searchState.isLoading,
            isLoadingMore = false,
            errorMessage = null,
            loadMoreErrorMessage = null,
        )
    }

    fun searchTracks() {
        val query = searchState.query.trim()
        if (query.isBlank()) {
            searchState = searchState.copy(
                activeQuery = "",
                isLoading = false,
                isLoadingMore = false,
                hasSearched = false,
                errorMessage = null,
                loadMoreErrorMessage = null,
                canLoadMore = false,
                nextOffset = 0,
                nextNeteaseOffset = 0,
                nextKuwoOffset = 0,
                results = emptyList(),
            )
            return
        }

        viewModelScope.launch {
            val requestId = ++latestSearchRequestId
            searchState = searchState.copy(
                activeQuery = query,
                isLoading = true,
                isLoadingMore = false,
                hasSearched = true,
                errorMessage = null,
                loadMoreErrorMessage = null,
                canLoadMore = false,
                nextOffset = 0,
                nextNeteaseOffset = 0,
                nextKuwoOffset = 0,
                results = emptyList(),
            )

            runCatching {
                withContext(Dispatchers.IO) {
                    musicLibraryRepository.searchTracks(
                        keyword = query,
                        limitPerSource = SEARCH_PAGE_SIZE,
                        neteaseOffset = 0,
                        kuwoOffset = 0,
                    )
                }
            }.onSuccess { searchPage ->
                if (requestId != latestSearchRequestId) return@onSuccess
                val normalizedResults = searchPage.tracks.map(::normalizeTrack)
                searchState = searchState.copy(
                    isLoading = false,
                    activeQuery = query,
                    results = normalizedResults,
                    errorMessage = null,
                    loadMoreErrorMessage = null,
                    canLoadMore = searchPage.canLoadMore,
                    nextOffset = normalizedResults.size,
                    nextNeteaseOffset = searchPage.nextNeteaseOffset,
                    nextKuwoOffset = searchPage.nextKuwoOffset,
                )
            }.onFailure {
                if (requestId != latestSearchRequestId) return@onFailure
                searchState = searchState.copy(
                    activeQuery = query,
                    isLoading = false,
                    isLoadingMore = false,
                    results = emptyList(),
                    errorMessage = it.message ?: "搜索失败，请稍后重试",
                    loadMoreErrorMessage = null,
                    canLoadMore = false,
                    nextOffset = 0,
                    nextNeteaseOffset = 0,
                    nextKuwoOffset = 0,
                )
            }
        }
    }

    fun loadMoreSearchTracks() {
        val activeQuery = searchState.activeQuery.trim()
        if (activeQuery.isBlank()) return
        if (searchState.query.trim() != activeQuery) return
        if (searchState.isLoading || searchState.isLoadingMore || !searchState.canLoadMore) return

        val neteaseOffset = searchState.nextNeteaseOffset
        val kuwoOffset = searchState.nextKuwoOffset

        viewModelScope.launch {
            val requestId = ++latestSearchRequestId
            searchState = searchState.copy(
                isLoadingMore = true,
                loadMoreErrorMessage = null,
            )

            runCatching {
                withContext(Dispatchers.IO) {
                    musicLibraryRepository.searchTracks(
                        keyword = activeQuery,
                        limitPerSource = SEARCH_PAGE_SIZE,
                        neteaseOffset = neteaseOffset,
                        kuwoOffset = kuwoOffset,
                    )
                }
            }.onSuccess { searchPage ->
                if (requestId != latestSearchRequestId) return@onSuccess
                val mergedResults = mergeSearchResults(
                    existing = searchState.results,
                    incoming = searchPage.tracks.map(::normalizeTrack),
                )
                searchState = searchState.copy(
                    isLoadingMore = false,
                    results = mergedResults,
                    loadMoreErrorMessage = null,
                    canLoadMore = searchPage.canLoadMore,
                    nextOffset = mergedResults.size,
                    nextNeteaseOffset = searchPage.nextNeteaseOffset,
                    nextKuwoOffset = searchPage.nextKuwoOffset,
                )
            }.onFailure {
                if (requestId != latestSearchRequestId) return@onFailure
                searchState = searchState.copy(
                    isLoadingMore = false,
                    loadMoreErrorMessage = it.message ?: "加载更多失败，请重试",
                )
            }
        }
    }

    fun selectPlaylistTrack(index: Int) {
        val playlist = playbackState.playlist
        if (index !in playlist.indices) return
        if (index != playbackState.currentIndex) {
            recordCurrentAiTrackSkipped()
        }
        prepareTrackForPlayback(
            queue = playlist,
            index = index,
            playWhenReady = true,
            source = activePlaybackSource,
        )
    }

    fun selectTrack(track: Track) {
        val queue = searchState.results.ifEmpty { listOf(track) }.map(::normalizeTrack)
        val selectedIndex = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        closeSearch()
        prepareTrackForPlayback(
            queue = queue,
            index = selectedIndex,
            playWhenReady = true,
            source = PlaybackSource.REGULAR,
        )
    }

    fun selectFavoriteTrack(track: Track) {
        val queue = favoritesState.tracks.map(::normalizeTrack)
        val selectedIndex = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        closeFavorites()
        prepareTrackForPlayback(
            queue = queue,
            index = selectedIndex,
            playWhenReady = true,
            source = PlaybackSource.REGULAR,
        )
    }

    fun playFavoriteTracks(shuffle: Boolean) {
        val favorites = favoritesState.tracks.map(::normalizeTrack)
        if (favorites.isEmpty()) return

        val queue = if (shuffle) {
            favorites.shuffled(shuffleRandom)
        } else {
            favorites
        }

        closeFavorites()
        prepareTrackForPlayback(
            queue = queue,
            index = 0,
            playWhenReady = true,
            source = PlaybackSource.REGULAR,
        )
    }

    private fun playResolvedAiRecommendations(recommendations: List<AiRecommendedTrack>) {
        val normalizedRecommendations = recommendations.map { recommendation ->
            recommendation.copy(track = normalizeTrack(recommendation.track))
        }
        val queue = normalizedRecommendations.map { it.track }
        if (queue.isEmpty()) return

        val session = (radioSession ?: RadioSessionState(sessionId = nextAiSessionId())).copy(
            queuedRecommendations = normalizedRecommendations,
            isLoadingMore = false,
            lastAutoAppendRemainingCount = -1,
        )
        radioSession = session
        latestCompletedRadioTrackId = null
        suppressedRadioCompletionTrackId = null
        activePlaybackSource = PlaybackSource.AI
        aiRecommendationState = aiRecommendationState.copy(
            tracks = normalizedRecommendations,
            isActive = true,
            isLoading = false,
            isLoadingMore = false,
            skippedCount = session.skippedTrackIds.size,
            statusLabel = session.statusLabel,
            isDegraded = false,
        )

        prepareTrackForPlayback(
            queue = queue,
            index = 0,
            playWhenReady = true,
            source = PlaybackSource.AI,
        )
    }

    fun onPlayerCommandHandled(commandId: Long) {
        if (pendingPlayerCommand?.id == commandId) {
            pendingPlayerCommand = null
        }
    }

    fun onPlayerIsPlayingChanged(isPlaying: Boolean) {
        if (playbackState.currentTrack == null) return
        if (playbackState.isPreparing && !isPlaying) return
        playbackState = playbackState.copy(isPlaying = isPlaying)
    }

    fun onPlayerProgress(positionMs: Long, durationMs: Long) {
        val currentTrack = playbackState.currentTrack ?: return
        val normalizedDuration = durationMs.takeIf { it > 0 } ?: currentTrack.durationMs
        val updatedTrack = if (normalizedDuration != currentTrack.durationMs) {
            currentTrack.copy(durationMs = normalizedDuration)
        } else {
            currentTrack
        }

        val updatedPlaylist = playbackState.playlist.toMutableList().also { playlist ->
            val currentIndex = playbackState.currentIndex
            if (currentIndex in playlist.indices) {
                playlist[currentIndex] = updatedTrack
            }
        }

        playbackState = playbackState.copy(
            currentTrack = updatedTrack,
            playlist = updatedPlaylist,
            currentPositionMs = positionMs.coerceAtLeast(0L),
        )
    }

    fun onPlayerQueueChanged(
        queue: List<Track>,
        currentIndex: Int,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
    ) {
        val previousTrack = playbackState.currentTrack
        val previousPositionMs = playbackState.currentPositionMs
        val previousDurationMs = previousTrack?.durationMs ?: 0L

        if (queue.isEmpty()) {
            if (previousTrack != null) {
                recordAiCompletionIfEligible(
                    track = previousTrack,
                    positionMs = previousPositionMs,
                    durationMs = previousDurationMs,
                )
            }
            playbackState = playbackState.copy(
                isPlaying = false,
                isPreparing = false,
                currentPositionMs = positionMs.coerceAtLeast(0L),
            )
            if (activePlaybackSource == PlaybackSource.AI) {
                unwindRadioPlaybackToRegularQueue(
                    track = previousTrack,
                    playWhenReady = false,
                    startPositionMs = previousPositionMs,
                    statusLabel = radioQueueEndedLabel(),
                    isDegraded = aiRecommendationState.isDegraded,
                )
            }
            return
        }

        val normalizedQueue = queue.map(::normalizeTrack)
        val safeIndex = currentIndex.coerceIn(0, normalizedQueue.lastIndex)
        val currentTrack = normalizedQueue[safeIndex]
        val updatedTrack = currentTrack.copy(
            durationMs = durationMs.takeIf { it > 0 } ?: currentTrack.durationMs,
        )
        val updatedQueue = normalizedQueue.toMutableList().also { playlist ->
            playlist[safeIndex] = updatedTrack
        }
        if (previousTrack != null && previousTrack.id != updatedTrack.id) {
            recordAiCompletionIfEligible(
                track = previousTrack,
                positionMs = previousPositionMs,
                durationMs = previousDurationMs,
            )
        }

        playbackState = playbackState.copy(
            currentTrack = updatedTrack,
            playlist = updatedQueue,
            currentIndex = safeIndex,
            currentPositionMs = positionMs.coerceAtLeast(0L),
            isPlaying = isPlaying,
            isPreparing = false,
            statusMessage = null,
        )

        if (lyricsState.isVisible && lyricsState.trackId != updatedTrack.id) {
            loadLyricsForTrack(updatedTrack)
        }

        maybeAutoQueueMoreAiRecommendations()
        maybeEndAiPlaybackIfQueueDrained(
            currentTrack = updatedTrack,
            currentIndex = safeIndex,
            queue = updatedQueue,
            isPlaying = isPlaying,
        )
    }

    fun onPlayerError(message: String?) {
        val currentIndex = playbackState.currentIndex
        val clearedCurrentTrack = playbackState.currentTrack?.copy(audioUrl = "")
        val clearedPlaylist = playbackState.playlist.toMutableList().also { playlist ->
            if (currentIndex in playlist.indices) {
                playlist[currentIndex] = playlist[currentIndex].copy(audioUrl = "")
            }
        }
        playbackState = playbackState.copy(
            currentTrack = clearedCurrentTrack,
            playlist = clearedPlaylist,
            isPlaying = false,
            isPreparing = false,
            statusMessage = message ?: "播放失败，请换一首歌试试。",
        )
    }

    private fun prepareTrackForPlayback(
        queue: List<Track>,
        index: Int,
        playWhenReady: Boolean,
        startPositionMs: Long = 0L,
        source: PlaybackSource,
    ) {
        if (queue.isEmpty() || index !in queue.indices) return

        applyPlaybackSource(source)

        val normalizedQueue = queue.map(::normalizeTrack)
        val targetTrack = normalizedQueue[index]
        if (targetTrack.audioUrl.isNotBlank()) {
            commitPlayableQueue(
                queue = normalizedQueue,
                index = index,
                playWhenReady = playWhenReady,
                startPositionMs = startPositionMs,
                source = source,
            )
            return
        }

        val targetTrackForResolution = targetTrack.takeIf { track ->
            track.id.isNotBlank() && track.audioUrl.isBlank()
        }
        val remainingQueueForResolution = normalizedQueue.filterIndexed { queueIndex, track ->
            queueIndex != index &&
                track.id.isNotBlank() &&
                track.audioUrl.isBlank()
        }
        val requestId = ++latestPlaybackRequestId

        playbackState = playbackState.copy(
            currentTrack = targetTrack,
            playlist = normalizedQueue,
            currentIndex = index,
            currentPositionMs = startPositionMs,
            isPlaying = false,
            isPreparing = true,
            statusMessage = "正在准备播放 ${targetTrack.title}...",
        )

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    targetTrackForResolution
                        ?.let { track -> musicLibraryRepository.resolvePlayableTracks(listOf(track)).firstOrNull() }
                        ?: targetTrack
                }
            }.onSuccess { resolvedTargetTrack ->
                if (requestId != latestPlaybackRequestId) return@onSuccess
                val resolvedTrack = resolvedTargetTrack ?: targetTrack
                if (resolvedTrack.audioUrl.isBlank()) {
                    playbackState = playbackState.copy(
                        currentTrack = normalizeTrack(targetTrack),
                        playlist = normalizedQueue,
                        currentIndex = index,
                        currentPositionMs = 0L,
                        isPlaying = false,
                        isPreparing = false,
                        statusMessage = "这首歌当前没有可用音频地址，换一首试试。",
                    )
                    return@onSuccess
                }

                val queueWithResolvedTarget = normalizedQueue.toMutableList().also { playlist ->
                    playlist[index] = normalizeTrack(
                        targetTrack.copy(
                            audioUrl = resolvedTrack.audioUrl,
                            durationMs = resolvedTrack.durationMs.takeIf { it > 0L } ?: targetTrack.durationMs,
                        ),
                    )
                }

                commitPlayableQueue(
                    queue = queueWithResolvedTarget,
                    index = index,
                    playWhenReady = playWhenReady,
                    startPositionMs = startPositionMs,
                    source = source,
                )

                if (remainingQueueForResolution.isEmpty()) return@onSuccess

                viewModelScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            musicLibraryRepository.resolvePlayableTracks(remainingQueueForResolution)
                        }
                    }.onSuccess { resolvedTracks ->
                        if (requestId != latestPlaybackRequestId) return@onSuccess
                        val currentPlaybackTrack = playbackState.currentTrack ?: return@onSuccess
                        if (currentPlaybackTrack.id != queueWithResolvedTarget[index].id) return@onSuccess

                        val resolvedTrackMap = resolvedTracks.associateBy { track -> track.id }
                        val mergedQueue = playbackState.playlist.map { track ->
                            val resolvedQueueTrack = resolvedTrackMap[track.id]
                            if (resolvedQueueTrack?.audioUrl.isNullOrBlank()) {
                                track
                            } else {
                                track.copy(
                                    audioUrl = resolvedQueueTrack?.audioUrl.orEmpty(),
                                    durationMs = resolvedQueueTrack?.durationMs
                                        ?.takeIf { durationMs -> durationMs > 0L }
                                        ?: track.durationMs,
                                )
                            }
                        }
                        commitPlayableQueue(
                            queue = mergedQueue.map(::normalizeTrack),
                            index = playbackState.currentIndex.coerceIn(0, mergedQueue.lastIndex),
                            playWhenReady = playbackState.isPlaying,
                            startPositionMs = playbackState.currentPositionMs,
                            source = source,
                        )
                    }
                }
            }.onFailure {
                if (requestId != latestPlaybackRequestId) return@onFailure
                playbackState = playbackState.copy(
                    currentTrack = normalizeTrack(targetTrack),
                    playlist = normalizedQueue,
                    currentIndex = index,
                    currentPositionMs = 0L,
                    isPlaying = false,
                    isPreparing = false,
                    statusMessage = it.message ?: "播放准备失败，请稍后重试。",
                )
            }
        }
    }

    private fun commitPlayableQueue(
        queue: List<Track>,
        index: Int,
        playWhenReady: Boolean,
        startPositionMs: Long,
        source: PlaybackSource,
    ) {
        if (queue.isEmpty() || index !in queue.indices) return

        applyPlaybackSource(source)

        val currentTrack = queue[index]
        playbackState = playbackState.copy(
            currentTrack = currentTrack,
            playlist = queue,
            currentIndex = index,
            currentPositionMs = startPositionMs,
            isPlaying = playWhenReady,
            isPreparing = false,
            statusMessage = null,
        )
        if (lyricsState.isVisible) {
            loadLyricsForTrack(currentTrack)
        }
        emitPlayerCommand(
            PlayerCommand.LoadTrack(
                id = nextPlayerCommandId(),
                queue = queue,
                index = index,
                playWhenReady = playWhenReady,
                startPositionMs = startPositionMs,
            ),
        )
    }

    private fun queueMoreAiRecommendations(
        force: Boolean,
        advanceToFirstNewTrack: Boolean = false,
    ) {
        val session = radioSession ?: return
        if (session.isLoadingMore) return

        val remainingCount = (playbackState.playlist.size - playbackState.currentIndex).coerceAtLeast(0)
        if (!force) {
            if (remainingCount > AI_PREFETCH_THRESHOLD) return
            if (session.lastAutoAppendRemainingCount == remainingCount) return
        }

        val config = buildAiServiceConfig()
        if (!config.isComplete) return

        val favorites = favoritesState.tracks.map(::normalizeTrack)
        if (favorites.isEmpty()) return

        val sessionId = session.sessionId
        val nextSessionState = session.copy(
            isLoadingMore = true,
            lastAutoAppendRemainingCount = if (force) session.lastAutoAppendRemainingCount else remainingCount,
        )
        radioSession = nextSessionState
        aiRecommendationState = aiRecommendationState.copy(
            isLoadingMore = true,
            errorMessage = null,
            skippedCount = nextSessionState.skippedTrackIds.size,
        )

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    radioEngine.replenish(
                        settings = config,
                        favorites = favorites,
                        history = radioHistoryStore.loadSnapshot(),
                        session = nextSessionState.copy(isLoadingMore = false),
                    )
                }
            }.onSuccess { result ->
                val activeSession = radioSession
                if (
                    activeSession == null ||
                    activeSession.sessionId != sessionId ||
                    activePlaybackSource != PlaybackSource.AI
                ) {
                    return@onSuccess
                }

                val normalizedTracks = result.updatedSession.queuedRecommendations.map { recommendation ->
                    recommendation.copy(track = normalizeTrack(recommendation.track))
                }
                val appendedTrackIds = result.appendedRecommendations.map { recommendation ->
                    normalizeTrack(recommendation.track).id
                }.toSet()
                val appendedTracks = normalizedTracks.filter { recommendation ->
                    recommendation.track.id in appendedTrackIds
                }

                val updatedSession = result.updatedSession.copy(
                    queuedRecommendations = normalizedTracks,
                    isLoadingMore = false,
                    lastAutoAppendRemainingCount = nextSessionState.lastAutoAppendRemainingCount,
                )
                radioSession = updatedSession
                aiRecommendationState = aiRecommendationState.copy(
                    isLoadingMore = false,
                    tracks = updatedSession.queuedRecommendations,
                    errorMessage = null,
                    suggestionCount = aiRecommendationState.suggestionCount + result.suggestionCount,
                    skippedCount = updatedSession.skippedTrackIds.size,
                    statusLabel = if (appendedTracks.isEmpty()) {
                        RADIO_DEGRADED_LABEL
                    } else {
                        updatedSession.statusLabel
                    },
                    isDegraded = appendedTracks.isEmpty(),
                )

                if (appendedTracks.isEmpty()) {
                    if (advanceToFirstNewTrack) {
                        stopRadioPlaybackWithPausedFallbackQueue(
                            statusLabel = RADIO_DEGRADED_LABEL,
                            isDegraded = true,
                        )
                        return@onSuccess
                    }
                    maybeEndAiPlaybackIfQueueDrained(
                        currentTrack = playbackState.currentTrack ?: return@onSuccess,
                        currentIndex = playbackState.currentIndex,
                        queue = playbackState.playlist,
                        isPlaying = playbackState.isPlaying,
                    )
                    return@onSuccess
                }

                val updatedQueue = playbackState.playlist + appendedTracks.map { it.track }
                val targetIndex = if (advanceToFirstNewTrack) {
                    val firstAppendedTrackId = appendedTracks.firstOrNull()?.track?.id.orEmpty()
                    updatedQueue.indexOfFirst { track -> track.id == firstAppendedTrackId }
                        .takeIf { it >= 0 }
                        ?: playbackState.currentIndex
                } else {
                    playbackState.currentIndex.coerceIn(0, updatedQueue.lastIndex)
                }
                commitPlayableQueue(
                    queue = updatedQueue,
                    index = targetIndex,
                    playWhenReady = if (advanceToFirstNewTrack) true else playbackState.isPlaying,
                    startPositionMs = if (advanceToFirstNewTrack) 0L else playbackState.currentPositionMs,
                    source = PlaybackSource.AI,
                )
            }.onFailure { error ->
                val activeSession = radioSession
                if (activeSession?.sessionId != sessionId) return@onFailure

                radioSession = activeSession.copy(isLoadingMore = false)
                aiRecommendationState = aiRecommendationState.copy(
                    isLoadingMore = false,
                    statusLabel = RADIO_DEGRADED_LABEL,
                    isDegraded = true,
                    errorMessage = error.message ?: "探索电台续播失败，请稍后重试。",
                    skippedCount = activeSession.skippedTrackIds.size,
                )
            }
        }
    }

    private fun maybeAutoQueueMoreAiRecommendations(
        force: Boolean = false,
        advanceToFirstNewTrack: Boolean = false,
    ) {
        if (activePlaybackSource != PlaybackSource.AI) return
        queueMoreAiRecommendations(
            force = force,
            advanceToFirstNewTrack = advanceToFirstNewTrack,
        )
    }

    private fun recordCurrentAiTrackSkipped() {
        if (activePlaybackSource != PlaybackSource.AI) return

        val currentTrack = playbackState.currentTrack ?: return
        val session = radioSession ?: return
        if (currentTrack.id.isBlank()) return
        if (currentTrack.id in session.skippedTrackIds) return

        recordAiFeedback(
            track = currentTrack,
            type = classifySkip(
                positionMs = playbackState.currentPositionMs,
                durationMs = currentTrack.durationMs,
            ),
        )
        val updatedSession = session.copy(
            skippedTrackIds = session.skippedTrackIds + currentTrack.id,
        )
        radioSession = updatedSession
        aiRecommendationState = aiRecommendationState.copy(
            skippedCount = updatedSession.skippedTrackIds.size,
        )
        suppressedRadioCompletionTrackId = currentTrack.id
        latestCompletedRadioTrackId = latestCompletedRadioTrackId?.takeUnless { it == currentTrack.id }
    }

    private fun recordAiTrackFavorited(track: Track) {
        if (activePlaybackSource != PlaybackSource.AI) return

        val normalizedTrack = normalizeTrack(track)
        val session = radioSession ?: return
        if (!isAiTrackInCurrentSession(normalizedTrack.id)) return
        if (normalizedTrack.id in session.favoritedTrackIds) return

        recordAiFeedback(normalizedTrack, RadioFeedbackType.STRONG_POSITIVE)
        val updatedSession = session.copy(
            favoritedTrackIds = session.favoritedTrackIds + normalizedTrack.id,
            statusLabel = "正在扩圈",
        )
        radioSession = updatedSession
        aiRecommendationState = aiRecommendationState.copy(
            statusLabel = updatedSession.statusLabel,
        )
    }

    private fun recordAiCompletionIfEligible(
        track: Track,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (activePlaybackSource != PlaybackSource.AI) return

        val normalizedTrack = normalizeTrack(track)
        val session = radioSession ?: return
        if (normalizedTrack.id.isBlank()) return
        if (latestCompletedRadioTrackId == normalizedTrack.id) return
        if (suppressedRadioCompletionTrackId == normalizedTrack.id) {
            suppressedRadioCompletionTrackId = null
            return
        }

        val feedbackType = classifyCompletion(
            positionMs = positionMs,
            durationMs = durationMs,
        ) ?: return

        recordAiFeedback(normalizedTrack, feedbackType)
        latestCompletedRadioTrackId = normalizedTrack.id
        radioSession = session.copy(
            playedTrackIds = session.playedTrackIds + normalizedTrack.id,
        )
    }

    private fun maybeRecordAiReplay(targetPositionMs: Long) {
        if (activePlaybackSource != PlaybackSource.AI) return

        val currentTrack = playbackState.currentTrack ?: return
        val normalizedTrack = normalizeTrack(currentTrack)
        if (normalizedTrack.id.isBlank()) return
        if (latestCompletedRadioTrackId != normalizedTrack.id) return
        if (targetPositionMs > RADIO_REPLAY_SEEK_THRESHOLD_MS) return
        if (targetPositionMs >= playbackState.currentPositionMs) return

        recordAiFeedback(normalizedTrack, RadioFeedbackType.REPLAY_POSITIVE)
        latestCompletedRadioTrackId = null
    }

    private fun maybeEndAiPlaybackIfQueueDrained(
        currentTrack: Track,
        currentIndex: Int,
        queue: List<Track>,
        isPlaying: Boolean,
    ) {
        if (activePlaybackSource != PlaybackSource.AI) return
        if (queue.isEmpty()) return
        if (currentIndex != queue.lastIndex) return
        if (isPlaying) return
        if (radioSession?.isLoadingMore == true) return
        if (currentTrack.durationMs <= 0L) return
        if (playbackState.currentPositionMs < currentTrack.durationMs - 2_000L) return

        recordAiCompletionIfEligible(
            track = currentTrack,
            positionMs = playbackState.currentPositionMs,
            durationMs = currentTrack.durationMs,
        )
        if (latestCompletedRadioTrackId != currentTrack.id) return

        unwindRadioPlaybackToRegularQueue(
            track = currentTrack,
            playWhenReady = false,
            startPositionMs = playbackState.currentPositionMs,
            statusLabel = radioQueueEndedLabel(),
            isDegraded = aiRecommendationState.isDegraded,
        )
    }

    private fun recordAiFeedback(
        track: Track,
        type: RadioFeedbackType,
    ) {
        if (activePlaybackSource != PlaybackSource.AI) return

        val normalizedTrack = normalizeTrack(track)
        val artistKey = normalizedTrack.artist.radioArtistKey()
        if (normalizedTrack.id.isBlank() || artistKey.isBlank()) return

        radioHistoryStore.recordEvent(
            RadioFeedbackEvent(
                trackId = normalizedTrack.id,
                artistKey = artistKey,
                type = type,
                timestampMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun isAiTrackInCurrentSession(trackId: String): Boolean {
        if (trackId.isBlank()) return false
        return radioSession?.queuedRecommendations?.any { recommendation ->
            recommendation.track.id == trackId
        } == true
    }

    private fun stopRadioPlayback(
        statusLabel: String? = null,
        isDegraded: Boolean = false,
    ) {
        clearRadioPlaybackState(
            statusLabel = statusLabel,
            isDegraded = isDegraded,
        )
    }

    private fun unwindRadioPlaybackToRegularQueue(
        track: Track?,
        playWhenReady: Boolean,
        startPositionMs: Long,
        statusLabel: String? = null,
        isDegraded: Boolean = false,
    ) {
        track?.let(::normalizeTrack)?.let { currentTrack ->
            prepareTrackForPlayback(
                queue = listOf(currentTrack),
                index = 0,
                playWhenReady = playWhenReady,
                startPositionMs = startPositionMs.coerceAtLeast(0L),
                source = PlaybackSource.REGULAR,
            )
        }

        clearRadioPlaybackState(
            statusLabel = statusLabel,
            isDegraded = isDegraded,
        )
    }

    private fun clearRadioPlaybackState(
        statusLabel: String? = null,
        isDegraded: Boolean = false,
    ) {
        activePlaybackSource = PlaybackSource.REGULAR
        radioSession = null
        latestCompletedRadioTrackId = null
        suppressedRadioCompletionTrackId = null
        aiRecommendationState = aiRecommendationState.copy(
            isActive = false,
            isLoading = false,
            isLoadingMore = false,
            tracks = emptyList(),
            skippedCount = 0,
            errorMessage = null,
            statusLabel = statusLabel,
            isDegraded = isDegraded,
        )
    }

    private fun stopRadioPlaybackWithPausedFallbackQueue(
        statusLabel: String? = null,
        isDegraded: Boolean = false,
    ) {
        unwindRadioPlaybackToRegularQueue(
            track = playbackState.currentTrack,
            playWhenReady = false,
            startPositionMs = playbackState.currentPositionMs,
            statusLabel = statusLabel,
            isDegraded = isDegraded,
        )
    }

    private fun radioQueueEndedLabel(): String {
        return if (aiRecommendationState.isDegraded) {
            RADIO_DEGRADED_LABEL
        } else {
            RADIO_ENDED_LABEL
        }
    }

    private fun applyPlaybackSource(source: PlaybackSource) {
        if (source == PlaybackSource.AI) {
            activePlaybackSource = PlaybackSource.AI
            aiRecommendationState = aiRecommendationState.copy(
                isActive = true,
                isLoadingMore = radioSession?.isLoadingMore == true,
                skippedCount = radioSession?.skippedTrackIds?.size ?: aiRecommendationState.skippedCount,
                statusLabel = radioSession?.statusLabel ?: aiRecommendationState.statusLabel,
            )
            return
        }

        activePlaybackSource = PlaybackSource.REGULAR
        clearRadioPlaybackState()
    }

    private fun nextAiSessionId(): Long {
        latestAiSessionId += 1L
        return latestAiSessionId
    }

    private fun normalizeTrack(track: Track): Track {
        val canonicalTrack = track.withCanonicalIdentity()
        val knownTrack = findKnownTrack(canonicalTrack.id)
        val isFavorite = favoritesState.tracks.any { it.id == canonicalTrack.id }

        return canonicalTrack.copy(
            title = canonicalTrack.title.ifBlank { knownTrack?.title.orEmpty() },
            artist = canonicalTrack.artist.ifBlank { knownTrack?.artist.orEmpty() },
            album = canonicalTrack.album.ifBlank { knownTrack?.album.orEmpty() },
            durationMs = canonicalTrack.durationMs.takeIf { it > 0 } ?: knownTrack?.durationMs ?: 0L,
            coverUrl = canonicalTrack.coverUrl.ifBlank { knownTrack?.coverUrl.orEmpty() },
            audioUrl = canonicalTrack.audioUrl.ifBlank { knownTrack?.audioUrl.orEmpty() },
            isFavorite = isFavorite,
        )
    }

    private fun findKnownTrack(trackId: String): Track? {
        if (trackId.isBlank()) return null

        val currentTrack = playbackState.currentTrack
        if (currentTrack?.id == trackId) return currentTrack

        return sequenceOf(
            playbackState.playlist.asSequence(),
            searchState.results.asSequence(),
            favoritesState.tracks.asSequence(),
            aiRecommendationState.tracks.asSequence().map { it.track },
        ).flatten().firstOrNull { it.id == trackId }
    }

    private fun syncFavoriteFlags() {
        searchState = searchState.copy(
            results = searchState.results.map(::normalizeTrack),
        )

        playbackState = playbackState.copy(
            currentTrack = playbackState.currentTrack?.let(::normalizeTrack),
            playlist = playbackState.playlist.map(::normalizeTrack),
        )

        favoritesState = favoritesState.copy(
            tracks = favoritesState.tracks.map(::normalizeTrack),
        )

        aiRecommendationState = aiRecommendationState.copy(
            tracks = aiRecommendationState.tracks.map { recommendation ->
                recommendation.copy(track = normalizeTrack(recommendation.track))
            },
        )

        radioSession = radioSession?.copy(
            queuedRecommendations = radioSession
                ?.queuedRecommendations
                .orEmpty()
                .map { recommendation ->
                    recommendation.copy(track = normalizeTrack(recommendation.track))
                },
        )
    }

    private fun loadStoredAiSettings(isVisible: Boolean = false) {
        val config = aiSettingsStore.loadSettings()
        aiSettingsState = AiSettingsUiState(
            isVisible = isVisible,
            endpoint = config.endpoint,
            model = config.model,
            accessKey = config.accessKey,
        )
    }

    private fun buildAiServiceConfig(): AiServiceConfig {
        return AiServiceConfig(
            endpoint = aiSettingsState.endpoint.trim(),
            model = aiSettingsState.model.trim(),
            accessKey = aiSettingsState.accessKey.trim(),
        )
    }

    private fun clearAiRecommendations(errorMessage: String? = null) {
        aiRecommendationState = AiRecommendationUiState(
            isActive = activePlaybackSource == PlaybackSource.AI,
            errorMessage = errorMessage,
            statusLabel = aiRecommendationState.statusLabel,
            isDegraded = aiRecommendationState.isDegraded,
        )
    }

    private fun mergeSearchResults(
        existing: List<Track>,
        incoming: List<Track>,
    ): List<Track> {
        if (incoming.isEmpty()) return existing

        val seenKeys = existing.map(::searchDedupKey).toMutableSet()
        return buildList(existing.size + incoming.size) {
            addAll(existing)
            incoming.forEach { track ->
                val dedupKey = searchDedupKey(track)
                if (dedupKey.isBlank() || seenKeys.add(dedupKey)) {
                    add(track)
                }
            }
        }
    }

    private fun searchDedupKey(track: Track): String {
        val normalizedTitle = track.title.lowercase().filter { it.isLetterOrDigit() }
        if (normalizedTitle.isBlank()) return track.id

        val normalizedArtist = track.artist
            .split(',', '、', '/', '&')
            .firstOrNull()
            .orEmpty()
            .lowercase()
            .filter { it.isLetterOrDigit() }

        return "$normalizedTitle::$normalizedArtist"
    }

    private fun String.radioArtistKey(): String {
        return split(',', '、', '/', '&')
            .firstOrNull()
            .orEmpty()
            .trim()
            .lowercase()
    }

    private fun loadLyricsForCurrentTrack(force: Boolean = false) {
        val currentTrack = playbackState.currentTrack
        if (currentTrack == null) {
            lyricsState = lyricsState.copy(
                isVisible = true,
                trackId = null,
                isLoading = false,
                credits = emptyList(),
                lines = emptyList(),
                errorMessage = "先选择一首歌再看歌词。",
            )
            return
        }

        loadLyricsForTrack(currentTrack, force)
    }

    private fun loadLyricsForTrack(
        track: Track,
        force: Boolean = false,
    ) {
        val normalizedTrack = track.withCanonicalIdentity()
        if (normalizedTrack.id.isBlank()) {
            lyricsState = lyricsState.copy(
                isVisible = true,
                trackId = null,
                isLoading = false,
                credits = emptyList(),
                lines = emptyList(),
                errorMessage = "当前歌曲没有歌词信息。",
            )
            return
        }

        if (!force) {
            lyricsCache[normalizedTrack.id]?.let { cachedLyrics ->
                lyricsState = lyricsState.copy(
                    isVisible = true,
                    trackId = normalizedTrack.id,
                    isLoading = false,
                    credits = cachedLyrics.credits,
                    lines = cachedLyrics.lines,
                    errorMessage = if (cachedLyrics.lines.isEmpty()) "暂无歌词" else null,
                )
                return
            }
        }

        val requestId = ++latestLyricsRequestId
        lyricsState = lyricsState.copy(
            isVisible = true,
            trackId = normalizedTrack.id,
            isLoading = true,
            credits = emptyList(),
            lines = emptyList(),
            errorMessage = null,
        )

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    musicLibraryRepository.fetchLyrics(normalizedTrack)
                }
            }.onSuccess { rawLyrics ->
                if (requestId != latestLyricsRequestId) return@onSuccess
                val content = parseLyricsContent(rawLyrics.orEmpty())
                lyricsCache[normalizedTrack.id] = content
                lyricsState = lyricsState.copy(
                    isVisible = true,
                    trackId = normalizedTrack.id,
                    isLoading = false,
                    credits = content.credits,
                    lines = content.lines,
                    errorMessage = if (content.lines.isEmpty()) "暂无歌词" else null,
                )
            }.onFailure {
                if (requestId != latestLyricsRequestId) return@onFailure
                lyricsState = lyricsState.copy(
                    isVisible = true,
                    trackId = normalizedTrack.id,
                    isLoading = false,
                    credits = emptyList(),
                    lines = emptyList(),
                    errorMessage = it.message ?: "歌词加载失败，请稍后重试。",
                )
            }
        }
    }

    private fun parseLyricsContent(rawLyrics: String): LyricsContent {
        if (rawLyrics.isBlank()) return LyricsContent(emptyList(), emptyList())

        val parsedLines = rawLyrics
            .lineSequence()
            .flatMap { rawLine ->
                val text = rawLine.replace(LYRIC_TIMESTAMP_REGEX, "").trim()
                if (text.isBlank()) return@flatMap emptySequence()

                val timestamps = LYRIC_TIMESTAMP_REGEX.findAll(rawLine).toList()
                if (timestamps.isEmpty()) return@flatMap emptySequence()

                timestamps.asSequence().map { match ->
                    LyricLine(
                        timestampMs = parseTimestampMs(match),
                        text = text,
                    )
                }
            }
            .sortedBy { it.timestampMs }
            .toList()

        if (parsedLines.isEmpty()) return LyricsContent(emptyList(), emptyList())

        val credits = mutableListOf<LyricCredit>()
        val lyricLines = mutableListOf<LyricLine>()
        var collectingCredits = true

        parsedLines.forEach { line ->
            val credit = line.toLyricCredit()
            if (collectingCredits && credit != null) {
                credits += credit
            } else {
                collectingCredits = false
                lyricLines += line
            }
        }

        val fallbackLines = if (lyricLines.isEmpty()) {
            parsedLines.filter { it.toLyricCredit() == null }
        } else {
            lyricLines
        }

        return LyricsContent(
            credits = credits.take(4),
            lines = fallbackLines,
        )
    }

    private fun parseTimestampMs(match: MatchResult): Long {
        val minutes = match.groupValues.getOrNull(1)?.toLongOrNull() ?: 0L
        val seconds = match.groupValues.getOrNull(2)?.toLongOrNull() ?: 0L
        val rawFraction = match.groupValues.getOrNull(3).orEmpty()
        val fractionMs = when (rawFraction.length) {
            1 -> rawFraction.toLongOrNull()?.times(100L) ?: 0L
            2 -> rawFraction.toLongOrNull()?.times(10L) ?: 0L
            else -> rawFraction.take(3).toLongOrNull() ?: 0L
        }
        return (minutes * 60_000L) + (seconds * 1_000L) + fractionMs
    }

    private fun LyricLine.toLyricCredit(): LyricCredit? {
        val separatorIndex = text.indexOfAny(charArrayOf(':', '：'))
        if (separatorIndex <= 0 || separatorIndex >= text.lastIndex) return null

        val role = text.substring(0, separatorIndex).trim().replace(" ", "")
        val name = text.substring(separatorIndex + 1).trim()
        if (role !in LYRIC_CREDIT_ROLES || name.isBlank()) return null

        return LyricCredit(
            role = role,
            name = name,
        )
    }

    private fun emitPlayerCommand(command: PlayerCommand) {
        pendingPlayerCommand = command
    }

    private fun nextPlayerCommandId(): Long {
        latestPlayerCommandId += 1L
        return latestPlayerCommandId
    }
}
