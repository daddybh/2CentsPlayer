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

    private val neteaseSearchRepository = NeteaseSearchRepository()
    private val favoritesStore = FavoritesStore(application)
    private val shuffleRandom = Random(System.currentTimeMillis())

    private var latestSearchRequestId = 0L
    private var latestPlaybackRequestId = 0L
    private var latestPlayerCommandId = 0L

    private val initialFavorites = favoritesStore.loadFavorites().map { it.copy(isFavorite = true) }

    var playbackState by mutableStateOf(
        PlaybackState(
            statusMessage = "搜索一首歌，播放器就会开始真正播放。",
        ),
    )
        private set

    var searchState by mutableStateOf(SearchUiState())
        private set

    var favoritesState by mutableStateOf(FavoritesUiState(tracks = initialFavorites))
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
        searchState = searchState.copy(isVisible = true)
    }

    fun closeSearch() {
        searchState = searchState.copy(isVisible = false)
    }

    fun openFavorites() {
        searchState = searchState.copy(isVisible = false)
        favoritesState = favoritesState.copy(isVisible = true)
    }

    fun closeFavorites() {
        favoritesState = favoritesState.copy(isVisible = false)
    }

    fun updateSearchQuery(query: String) {
        searchState = searchState.copy(
            query = query,
            errorMessage = null,
        )
    }

    fun searchTracks() {
        val query = searchState.query.trim()
        if (query.isBlank()) {
            searchState = searchState.copy(
                hasSearched = false,
                errorMessage = null,
                results = emptyList(),
            )
            return
        }

        viewModelScope.launch {
            val requestId = ++latestSearchRequestId
            searchState = searchState.copy(
                isLoading = true,
                hasSearched = true,
                errorMessage = null,
            )

            runCatching {
                withContext(Dispatchers.IO) {
                    neteaseSearchRepository.searchTracks(query)
                }
            }.onSuccess { results ->
                if (requestId != latestSearchRequestId) return@onSuccess
                searchState = searchState.copy(
                    isLoading = false,
                    results = results.map(::normalizeTrack),
                    errorMessage = null,
                )
            }.onFailure {
                if (requestId != latestSearchRequestId) return@onFailure
                searchState = searchState.copy(
                    isLoading = false,
                    results = emptyList(),
                    errorMessage = it.message ?: "搜索失败，请稍后重试",
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

    fun onTrackEnded() {
        if (playbackState.playlist.isEmpty()) {
            playbackState = playbackState.copy(isPlaying = false)
            return
        }
        skipNext()
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

        if (targetTrack.audioUrl.isNotBlank()) {
            playbackState = playbackState.copy(
                currentTrack = targetTrack,
                playlist = normalizedQueue,
                currentIndex = index,
                currentPositionMs = startPositionMs,
                isPlaying = playWhenReady,
                isPreparing = false,
                statusMessage = null,
            )
            emitPlayerCommand(
                PlayerCommand.LoadTrack(
                    id = nextPlayerCommandId(),
                    track = targetTrack,
                    playWhenReady = playWhenReady,
                    startPositionMs = startPositionMs,
                ),
            )
            return
        }

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    neteaseSearchRepository.resolvePlayableTrack(targetTrack)
                }
            }.onSuccess { resolvedTrack ->
                if (requestId != latestPlaybackRequestId) return@onSuccess
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

                val updatedTrack = normalizeTrack(resolvedTrack)
                val updatedQueue = normalizedQueue.toMutableList().also { it[index] = updatedTrack }
                playbackState = playbackState.copy(
                    currentTrack = updatedTrack,
                    playlist = updatedQueue,
                    currentIndex = index,
                    currentPositionMs = startPositionMs,
                    isPlaying = playWhenReady,
                    isPreparing = false,
                    statusMessage = null,
                )
                emitPlayerCommand(
                    PlayerCommand.LoadTrack(
                        id = nextPlayerCommandId(),
                        track = updatedTrack,
                        playWhenReady = playWhenReady,
                        startPositionMs = startPositionMs,
                    ),
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

    private fun emitPlayerCommand(command: PlayerCommand) {
        pendingPlayerCommand = command
    }

    private fun nextPlayerCommandId(): Long {
        latestPlayerCommandId += 1L
        return latestPlayerCommandId
    }
}
