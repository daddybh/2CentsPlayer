package com.twocents.player.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.twocents.player.data.NeteaseSearchRepository
import com.twocents.player.data.PlaybackState
import com.twocents.player.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerViewModel(
    private val neteaseSearchRepository: NeteaseSearchRepository = NeteaseSearchRepository(),
) : ViewModel() {

    private var latestSearchRequestId = 0L

    var playbackState by mutableStateOf(PlaybackState())
        private set

    var searchState by mutableStateOf(SearchUiState())
        private set

    // Demo playlist for initial display
    private val demoTracks = listOf(
        Track(
            id = "1",
            title = "Midnight Dreams",
            artist = "Luna Echo",
            album = "Neon Horizons",
            durationMs = 234_000L,
        ),
        Track(
            id = "2",
            title = "Electric Sunrise",
            artist = "Solar Flare",
            album = "Dawn Circuit",
            durationMs = 198_000L,
        ),
        Track(
            id = "3",
            title = "Velvet Shadows",
            artist = "Drift Collective",
            album = "Ambient Pulse",
            durationMs = 267_000L,
        ),
        Track(
            id = "4",
            title = "Crystal Rain",
            artist = "Neon Waves",
            album = "Digital Ocean",
            durationMs = 312_000L,
        ),
    )

    init {
        playbackState = PlaybackState(
            currentTrack = demoTracks.first(),
            playlist = demoTracks,
            currentIndex = 0,
        )
    }

    fun togglePlayPause() {
        playbackState = playbackState.copy(isPlaying = !playbackState.isPlaying)
    }

    fun skipNext() {
        val playlist = playbackState.playlist
        if (playlist.isEmpty()) return
        val nextIndex = (playbackState.currentIndex + 1) % playlist.size
        playbackState = playbackState.copy(
            currentIndex = nextIndex,
            currentTrack = playlist[nextIndex],
            currentPositionMs = 0L,
        )
    }

    fun skipPrevious() {
        val playlist = playbackState.playlist
        if (playlist.isEmpty()) return
        val prevIndex = if (playbackState.currentIndex > 0) {
            playbackState.currentIndex - 1
        } else {
            playlist.size - 1
        }
        playbackState = playbackState.copy(
            currentIndex = prevIndex,
            currentTrack = playlist[prevIndex],
            currentPositionMs = 0L,
        )
    }

    fun toggleFavorite() {
        val current = playbackState.currentTrack ?: return
        val updated = current.copy(isFavorite = !current.isFavorite)
        val updatedPlaylist = playbackState.playlist.toMutableList().also {
            it[playbackState.currentIndex] = updated
        }
        playbackState = playbackState.copy(
            currentTrack = updated,
            playlist = updatedPlaylist,
        )
    }

    fun seekTo(positionMs: Long) {
        playbackState = playbackState.copy(currentPositionMs = positionMs)
    }

    fun openSearch() {
        searchState = searchState.copy(isVisible = true)
    }

    fun closeSearch() {
        searchState = searchState.copy(isVisible = false)
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
                    results = results,
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

        playbackState = playbackState.copy(
            currentTrack = playlist[index],
            currentIndex = index,
            currentPositionMs = 0L,
        )
    }

    fun selectTrack(track: Track) {
        val updatedPlaylist = searchState.results.ifEmpty { listOf(track) }
        val selectedIndex = updatedPlaylist.indexOfFirst { it.id == track.id }.coerceAtLeast(0)

        playbackState = playbackState.copy(
            currentTrack = track,
            playlist = updatedPlaylist,
            currentIndex = selectedIndex,
            currentPositionMs = 0L,
            isPlaying = false,
        )

        closeSearch()
    }
}
