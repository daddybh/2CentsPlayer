package com.twocents.player.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    private val neteaseSearchRepository = NeteaseSearchRepository()
    private val favoritesStore = FavoritesStore(application)
    private val shuffleRandom = Random(System.currentTimeMillis())
    private val lyricsCache = mutableMapOf<String, LyricsContent>()

    private var latestSearchRequestId = 0L
    private var latestPlaybackRequestId = 0L
    private var latestPlayerCommandId = 0L
    private var latestLyricsRequestId = 0L

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

    var pendingPlayerCommand by mutableStateOf<PlayerCommand?>(null)
        private set

    fun togglePlayPause() {
        val currentTrack = playbackState.currentTrack ?: return
        if (playbackState.isPreparing) return

        if (currentTrack.audioUrl.isBlank()) {
            prepareTrackForPlayback(
                queue = playbackState.playlist.ifEmpty { listOf(currentTrack) },
                index = playbackState.currentIndex.coerceAtLeast(0),
                playWhenReady = true,
                startPositionMs = playbackState.currentPositionMs,
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
        val nextIndex = (playbackState.currentIndex + 1) % playlist.size
        prepareTrackForPlayback(
            queue = playlist,
            index = nextIndex,
            playWhenReady = true,
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
        searchState = searchState.copy(isVisible = true)
    }

    fun closeSearch() {
        searchState = searchState.copy(isVisible = false)
    }

    fun openFavorites() {
        searchState = searchState.copy(isVisible = false)
        lyricsState = lyricsState.copy(isVisible = false)
        favoritesState = favoritesState.copy(isVisible = true)
    }

    fun closeFavorites() {
        favoritesState = favoritesState.copy(isVisible = false)
    }

    fun openLyricsScreen() {
        lyricsState = lyricsState.copy(isVisible = true)
        loadLyricsForCurrentTrack()
    }

    fun closeLyricsScreen() {
        lyricsState = lyricsState.copy(isVisible = false)
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
        prepareTrackForPlayback(
            queue = playlist,
            index = index,
            playWhenReady = true,
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
    ) {
        if (queue.isEmpty() || index !in queue.indices) return

        val normalizedQueue = queue.map(::normalizeTrack)
        val targetTrack = normalizedQueue[index]
        val queueForResolution = normalizedQueue.map { it.copy(audioUrl = "") }
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
                    neteaseSearchRepository.resolvePlayableTracks(queueForResolution)
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
    ) {
        if (queue.isEmpty() || index !in queue.indices) return

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
