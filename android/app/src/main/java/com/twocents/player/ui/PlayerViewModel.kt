package com.twocents.player.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.twocents.player.data.PlaybackState
import com.twocents.player.data.Track

class PlayerViewModel : ViewModel() {

    var playbackState by mutableStateOf(PlaybackState())
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
}
