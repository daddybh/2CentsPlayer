package com.twocents.player.data

/**
 * Represents a music track.
 */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val coverUrl: String = "",
    val audioUrl: String = "",
    val isFavorite: Boolean = false,
)

/**
 * Playback state for the player UI.
 */
data class PlaybackState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val playlist: List<Track> = emptyList(),
    val currentIndex: Int = 0,
    val isPreparing: Boolean = false,
    val statusMessage: String? = null,
)
