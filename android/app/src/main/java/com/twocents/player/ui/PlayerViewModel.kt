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
import com.twocents.player.data.NeteaseSearchRepository
import com.twocents.player.data.PlaybackState
import com.twocents.player.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

class PlayerViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private companion object {
        const val AI_PAGE_SIZE = 10
        const val AI_PREFETCH_THRESHOLD = 3
        const val SEARCH_PAGE_SIZE = 20
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

    private data class AiRecommendationResult(
        val suggestionCount: Int,
        val resolvedTracks: List<AiRecommendedTrack>,
    )

    private enum class PlaybackSource {
        REGULAR,
        AI,
    }

    private data class AiPlaybackSession(
        val sessionId: Long,
        val queuedRecommendations: List<AiRecommendedTrack>,
        val skippedTracks: List<Track> = emptyList(),
        val isLoadingMore: Boolean = false,
        val lastAutoAppendRemainingCount: Int = -1,
    )

    private val aiRecommendationRepository = AiRecommendationRepository()
    private val aiSettingsStore = AiSettingsStore(application)
    private val neteaseSearchRepository = NeteaseSearchRepository()
    private val favoritesStore = FavoritesStore(application)
    private val shuffleRandom = Random(System.currentTimeMillis())
    private val lyricsCache = mutableMapOf<String, LyricsContent>()

    private var latestSearchRequestId = 0L
    private var latestPlaybackRequestId = 0L
    private var latestPlayerCommandId = 0L
    private var latestLyricsRequestId = 0L
    private var latestAiSessionId = 0L

    private var activePlaybackSource = PlaybackSource.REGULAR
    private var aiPlaybackSession: AiPlaybackSession? = null

    private val initialAiSettings = aiSettingsStore.loadSettings()
    private val initialFavorites = favoritesStore.loadFavorites().map { it.copy(isFavorite = true) }

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
        val nextIndex = (playbackState.currentIndex + 1) % playlist.size
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
        val prevIndex = if (playbackState.currentIndex > 0) {
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
        val existingFavorites = favoritesState.tracks.filterNot { it.id == normalizedTrack.id }
        val updatedFavorites = if (normalizedTrack.isFavorite) {
            existingFavorites
        } else {
            listOf(normalizedTrack.copy(isFavorite = true, audioUrl = "")) + existingFavorites
        }

        favoritesStore.saveFavorites(updatedFavorites)
        favoritesState = favoritesState.copy(tracks = updatedFavorites)
        syncFavoriteFlags()

        if (updatedFavorites.isEmpty()) {
            clearAiRecommendations(errorMessage = "先收藏几首歌，再生成 AI 推荐。")
        }
    }

    fun seekTo(positionMs: Long) {
        if (playbackState.currentTrack == null) return
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
            applyPlaybackSource(PlaybackSource.REGULAR)
        } else {
            playAiRecommendations()
        }
    }

    fun playAiRecommendations() {
        if (aiRecommendationState.isLoading) return

        val recommendations = aiRecommendationState.tracks
        if (recommendations.isNotEmpty()) {
            playResolvedAiRecommendations(recommendations)
            return
        }

        val config = buildAiServiceConfig()
        if (!config.isComplete) {
            aiRecommendationState = aiRecommendationState.copy(
                errorMessage = "先在设置里填好 AI 接口、模型和 Access Key。",
            )
            openAiSettings()
            return
        }

        if (favoritesState.tracks.isEmpty()) {
            clearAiRecommendations(errorMessage = "先收藏几首歌，再生成 AI 推荐。")
            return
        }

        refreshAiRecommendations(playAfterRefresh = true)
    }

    fun refreshAiRecommendations(playAfterRefresh: Boolean = false) {
        if (activePlaybackSource == PlaybackSource.AI && aiPlaybackSession != null) {
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
                errorMessage = null,
            )

            runCatching {
                withContext(Dispatchers.IO) {
                    generateAiRecommendations(
                        config = config,
                        favorites = favorites,
                    )
                }
            }.onSuccess { result ->
                val normalizedTracks = result.resolvedTracks.map { recommendation ->
                    recommendation.copy(track = normalizeTrack(recommendation.track))
                }

                aiRecommendationState = aiRecommendationState.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    tracks = normalizedTracks,
                    errorMessage = if (normalizedTracks.isEmpty()) {
                        "AI 已返回推荐，但还没匹配到可播放歌曲。"
                    } else {
                        null
                    },
                    sourceFavoriteCount = favorites.size,
                    suggestionCount = result.suggestionCount,
                    skippedCount = 0,
                )

                if (playAfterRefresh && normalizedTracks.isNotEmpty()) {
                    playResolvedAiRecommendations(normalizedTracks)
                }
            }.onFailure { error ->
                aiRecommendationState = aiRecommendationState.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    errorMessage = error.message ?: "AI 推荐生成失败，请稍后重试。",
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
                results = emptyList(),
            )

            runCatching {
                withContext(Dispatchers.IO) {
                    neteaseSearchRepository.searchTracks(
                        keyword = query,
                        limit = SEARCH_PAGE_SIZE,
                        offset = 0,
                    )
                }
            }.onSuccess { results ->
                if (requestId != latestSearchRequestId) return@onSuccess
                val normalizedResults = results.map(::normalizeTrack)
                searchState = searchState.copy(
                    isLoading = false,
                    activeQuery = query,
                    results = normalizedResults,
                    errorMessage = null,
                    loadMoreErrorMessage = null,
                    canLoadMore = results.size >= SEARCH_PAGE_SIZE,
                    nextOffset = results.size,
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
                )
            }
        }
    }

    fun loadMoreSearchTracks() {
        val activeQuery = searchState.activeQuery.trim()
        if (activeQuery.isBlank()) return
        if (searchState.query.trim() != activeQuery) return
        if (searchState.isLoading || searchState.isLoadingMore || !searchState.canLoadMore) return

        val offset = searchState.nextOffset

        viewModelScope.launch {
            val requestId = ++latestSearchRequestId
            searchState = searchState.copy(
                isLoadingMore = true,
                loadMoreErrorMessage = null,
            )

            runCatching {
                withContext(Dispatchers.IO) {
                    neteaseSearchRepository.searchTracks(
                        keyword = activeQuery,
                        limit = SEARCH_PAGE_SIZE,
                        offset = offset,
                    )
                }
            }.onSuccess { results ->
                if (requestId != latestSearchRequestId) return@onSuccess
                val mergedResults = mergeSearchResults(
                    existing = searchState.results,
                    incoming = results.map(::normalizeTrack),
                )
                searchState = searchState.copy(
                    isLoadingMore = false,
                    results = mergedResults,
                    loadMoreErrorMessage = null,
                    canLoadMore = results.size >= SEARCH_PAGE_SIZE,
                    nextOffset = offset + results.size,
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
        val shuffledRecommendations = recommendations.shuffled(shuffleRandom)
        val queue = shuffledRecommendations.map { normalizeTrack(it.track) }
        if (queue.isEmpty()) return

        val session = AiPlaybackSession(
            sessionId = nextAiSessionId(),
            queuedRecommendations = shuffledRecommendations,
        )
        aiPlaybackSession = session
        activePlaybackSource = PlaybackSource.AI
        aiRecommendationState = aiRecommendationState.copy(
            tracks = shuffledRecommendations,
            isLoadingMore = false,
            skippedCount = 0,
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
        if (queue.isEmpty()) {
            playbackState = playbackState.copy(
                isPlaying = false,
                isPreparing = false,
                currentPositionMs = positionMs.coerceAtLeast(0L),
            )
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

        val queueForResolution = normalizedQueue.filter { track ->
            track.id.isNotBlank() && track.audioUrl.isBlank()
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
                    val resolvedTracks = neteaseSearchRepository.resolvePlayableTracks(queueForResolution)
                    val resolvedTrackMap = resolvedTracks.associateBy { track -> track.id }
                    normalizedQueue.map { track ->
                        val resolvedTrack = resolvedTrackMap[track.id]
                        if (resolvedTrack?.audioUrl.isNullOrBlank()) {
                            track
                        } else {
                            track.copy(
                                audioUrl = resolvedTrack?.audioUrl.orEmpty(),
                                durationMs = resolvedTrack?.durationMs
                                    ?.takeIf { durationMs -> durationMs > 0L }
                                    ?: track.durationMs,
                            )
                        }
                    }
                }
            }.onSuccess { resolvedQueue ->
                if (requestId != latestPlaybackRequestId) return@onSuccess
                val resolvedTrack = resolvedQueue.getOrNull(index) ?: targetTrack
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

                commitPlayableQueue(
                    queue = resolvedQueue.map(::normalizeTrack),
                    index = index,
                    playWhenReady = playWhenReady,
                    startPositionMs = startPositionMs,
                    source = source,
                )
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

    private fun generateAiRecommendations(
        config: AiServiceConfig,
        favorites: List<Track>,
        skippedTracks: List<Track> = emptyList(),
        avoidTracks: List<Track> = emptyList(),
    ): AiRecommendationResult {
        val suggestions = aiRecommendationRepository.requestRecommendations(
            settings = config,
            favorites = favorites,
            skippedTracks = skippedTracks,
            avoidTracks = avoidTracks,
            limit = AI_PAGE_SIZE,
        )
        val resolvedTracks = suggestions.mapNotNull { suggestion ->
            neteaseSearchRepository.findBestMatchTrack(
                title = suggestion.title,
                artist = suggestion.artist,
            )?.let { matchedTrack ->
                AiRecommendedTrack(
                    track = matchedTrack,
                    reason = suggestion.reason,
                )
            }
        }.distinctBy { it.track.id }

        return AiRecommendationResult(
            suggestionCount = suggestions.size,
            resolvedTracks = resolvedTracks,
        )
    }

    private fun queueMoreAiRecommendations(force: Boolean) {
        val session = aiPlaybackSession ?: return
        if (session.isLoadingMore) return

        val remainingCount = playbackState.playlist.size - playbackState.currentIndex
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
        aiPlaybackSession = nextSessionState
        aiRecommendationState = aiRecommendationState.copy(
            isLoadingMore = true,
            errorMessage = null,
            skippedCount = nextSessionState.skippedTracks.size,
        )

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val result = generateAiRecommendations(
                        config = config,
                        favorites = favorites,
                        skippedTracks = session.skippedTracks,
                        avoidTracks = session.queuedRecommendations.map { it.track },
                    )
                    result.copy(
                        resolvedTracks = resolvePlayableAiRecommendations(result.resolvedTracks),
                    )
                }
            }.onSuccess { result ->
                val activeSession = aiPlaybackSession
                if (
                    activeSession == null ||
                    activeSession.sessionId != sessionId ||
                    activePlaybackSource != PlaybackSource.AI
                ) {
                    return@onSuccess
                }

                val existingIds = activeSession.queuedRecommendations.map { it.track.id }.toSet()
                val appendedTracks = result.resolvedTracks
                    .map { recommendation ->
                        recommendation.copy(track = normalizeTrack(recommendation.track))
                    }
                    .filter { recommendation ->
                        recommendation.track.id.isNotBlank() &&
                            recommendation.track.audioUrl.isNotBlank() &&
                            recommendation.track.id !in existingIds
                    }
                    .shuffled(shuffleRandom)

                val updatedSession = activeSession.copy(
                    queuedRecommendations = activeSession.queuedRecommendations + appendedTracks,
                    isLoadingMore = false,
                )
                aiPlaybackSession = updatedSession
                aiRecommendationState = aiRecommendationState.copy(
                    isLoadingMore = false,
                    tracks = updatedSession.queuedRecommendations,
                    errorMessage = if (force && appendedTracks.isEmpty()) {
                        "这一轮没有拿到新的可播放推荐，稍后再试试。"
                    } else {
                        null
                    },
                    suggestionCount = aiRecommendationState.suggestionCount + result.suggestionCount,
                    skippedCount = updatedSession.skippedTracks.size,
                )

                if (appendedTracks.isEmpty()) return@onSuccess

                val updatedQueue = playbackState.playlist + appendedTracks.map { normalizeTrack(it.track) }
                val safeIndex = playbackState.currentIndex.coerceIn(0, updatedQueue.lastIndex)
                commitPlayableQueue(
                    queue = updatedQueue,
                    index = safeIndex,
                    playWhenReady = playbackState.isPlaying,
                    startPositionMs = playbackState.currentPositionMs,
                    source = PlaybackSource.AI,
                )
            }.onFailure { error ->
                val activeSession = aiPlaybackSession
                if (activeSession?.sessionId != sessionId) return@onFailure

                aiPlaybackSession = activeSession.copy(isLoadingMore = false)
                aiRecommendationState = aiRecommendationState.copy(
                    isLoadingMore = false,
                    errorMessage = error.message ?: "追加 AI 推荐失败，请稍后重试。",
                    skippedCount = activeSession.skippedTracks.size,
                )
            }
        }
    }

    private fun resolvePlayableAiRecommendations(
        recommendations: List<AiRecommendedTrack>,
    ): List<AiRecommendedTrack> {
        if (recommendations.isEmpty()) return emptyList()

        val resolvedTracks = neteaseSearchRepository.resolvePlayableTracks(
            recommendations.map { recommendation ->
                recommendation.track.copy(audioUrl = "")
            },
        ).associateBy { track -> track.id }

        return recommendations.mapNotNull { recommendation ->
            val resolvedTrack = resolvedTracks[recommendation.track.id] ?: return@mapNotNull null
            if (resolvedTrack.audioUrl.isBlank()) return@mapNotNull null

            recommendation.copy(track = resolvedTrack)
        }
    }

    private fun maybeAutoQueueMoreAiRecommendations() {
        if (activePlaybackSource != PlaybackSource.AI) return
        queueMoreAiRecommendations(force = false)
    }

    private fun recordCurrentAiTrackSkipped() {
        if (activePlaybackSource != PlaybackSource.AI) return

        val currentTrack = playbackState.currentTrack ?: return
        val session = aiPlaybackSession ?: return
        if (currentTrack.id.isBlank()) return
        if (session.skippedTracks.any { it.id == currentTrack.id }) return

        val updatedSkippedTracks = session.skippedTracks + currentTrack.copy(audioUrl = "")
        aiPlaybackSession = session.copy(skippedTracks = updatedSkippedTracks)
        aiRecommendationState = aiRecommendationState.copy(
            skippedCount = updatedSkippedTracks.size,
        )
    }

    private fun applyPlaybackSource(source: PlaybackSource) {
        if (source == PlaybackSource.AI) {
            activePlaybackSource = PlaybackSource.AI
            aiRecommendationState = aiRecommendationState.copy(
                isActive = true,
                isLoadingMore = aiPlaybackSession?.isLoadingMore == true,
                skippedCount = aiPlaybackSession?.skippedTracks?.size ?: aiRecommendationState.skippedCount,
            )
            return
        }

        activePlaybackSource = PlaybackSource.REGULAR
        aiPlaybackSession = null
        aiRecommendationState = aiRecommendationState.copy(
            isActive = false,
            isLoadingMore = false,
            skippedCount = 0,
        )
    }

    private fun nextAiSessionId(): Long {
        latestAiSessionId += 1L
        return latestAiSessionId
    }

    private fun normalizeTrack(track: Track): Track {
        val knownTrack = findKnownTrack(track.id)
        val isFavorite = favoritesState.tracks.any { it.id == track.id }

        return track.copy(
            title = track.title.ifBlank { knownTrack?.title.orEmpty() },
            artist = track.artist.ifBlank { knownTrack?.artist.orEmpty() },
            album = track.album.ifBlank { knownTrack?.album.orEmpty() },
            durationMs = track.durationMs.takeIf { it > 0 } ?: knownTrack?.durationMs ?: 0L,
            coverUrl = track.coverUrl.ifBlank { knownTrack?.coverUrl.orEmpty() },
            audioUrl = track.audioUrl.ifBlank { knownTrack?.audioUrl.orEmpty() },
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

        aiPlaybackSession = aiPlaybackSession?.copy(
            queuedRecommendations = aiPlaybackSession
                ?.queuedRecommendations
                .orEmpty()
                .map { recommendation ->
                    recommendation.copy(track = normalizeTrack(recommendation.track))
                },
            skippedTracks = aiPlaybackSession
                ?.skippedTracks
                .orEmpty()
                .map(::normalizeTrack),
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
        )
    }

    private fun mergeSearchResults(
        existing: List<Track>,
        incoming: List<Track>,
    ): List<Track> {
        if (incoming.isEmpty()) return existing

        val seenIds = existing.mapNotNull { track ->
            track.id.takeIf { it.isNotBlank() }
        }.toMutableSet()
        return buildList(existing.size + incoming.size) {
            addAll(existing)
            incoming.forEach { track ->
                if (track.id.isBlank() || seenIds.add(track.id)) {
                    add(track)
                }
            }
        }
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
        if (track.id.isBlank()) {
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
            lyricsCache[track.id]?.let { cachedLyrics ->
                lyricsState = lyricsState.copy(
                    isVisible = true,
                    trackId = track.id,
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
            trackId = track.id,
            isLoading = true,
            credits = emptyList(),
            lines = emptyList(),
            errorMessage = null,
        )

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    neteaseSearchRepository.fetchLyrics(track.id)
                }
            }.onSuccess { rawLyrics ->
                if (requestId != latestLyricsRequestId) return@onSuccess
                val content = parseLyricsContent(rawLyrics.orEmpty())
                lyricsCache[track.id] = content
                lyricsState = lyricsState.copy(
                    isVisible = true,
                    trackId = track.id,
                    isLoading = false,
                    credits = content.credits,
                    lines = content.lines,
                    errorMessage = if (content.lines.isEmpty()) "暂无歌词" else null,
                )
            }.onFailure {
                if (requestId != latestLyricsRequestId) return@onFailure
                lyricsState = lyricsState.copy(
                    isVisible = true,
                    trackId = track.id,
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
