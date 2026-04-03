package com.twocents.player.data

/**
 * Represents a music track.
 */
enum class TrackSource(
    val storageKey: String,
    val label: String,
) {
    NETEASE(
        storageKey = "netease",
        label = "网易云",
    ),
    KUWO(
        storageKey = "kuwo",
        label = "酷我",
    ),
    ;

    companion object {
        fun fromStorageKey(rawValue: String?): TrackSource {
            return entries.firstOrNull { it.storageKey == rawValue } ?: NETEASE
        }
    }
}

data class Track(
    val id: String,
    val source: TrackSource = TrackSource.NETEASE,
    val sourceId: String = "",
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

fun Track.sourceTrackId(): String {
    if (sourceId.isNotBlank()) return sourceId
    val prefix = "${source.storageKey}:"
    return if (id.startsWith(prefix)) id.removePrefix(prefix) else id
}

fun Track.withCanonicalIdentity(): Track {
    val rawSourceId = sourceTrackId()
    if (rawSourceId.isBlank()) return this

    val canonicalId = "${source.storageKey}:$rawSourceId"
    return if (id == canonicalId && sourceId == rawSourceId) {
        this
    } else {
        copy(
            id = canonicalId,
            sourceId = rawSourceId,
        )
    }
}
