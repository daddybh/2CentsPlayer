package com.twocents.player.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.twocents.player.data.Track
import com.twocents.player.data.TrackSource
import com.twocents.player.data.sourceTrackId
import com.twocents.player.data.withCanonicalIdentity

private const val EXTRA_DURATION_MS = "duration_ms"
private const val EXTRA_TRACK_SOURCE = "track_source"
private const val EXTRA_TRACK_SOURCE_ID = "track_source_id"

fun Track.toMediaItem(): MediaItem {
    val metadataBuilder = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setExtras(
            Bundle().apply {
                putLong(EXTRA_DURATION_MS, durationMs)
                putString(EXTRA_TRACK_SOURCE, source.storageKey)
                putString(EXTRA_TRACK_SOURCE_ID, sourceTrackId())
            },
        )

    coverUrl
        .takeIf { it.isNotBlank() }
        ?.let { metadataBuilder.setArtworkUri(Uri.parse(it)) }

    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(audioUrl)
        .setMediaMetadata(metadataBuilder.build())
        .build()
}

fun MediaItem.toTrack(): Track {
    val metadata = mediaMetadata
    return Track(
        id = mediaId,
        source = TrackSource.fromStorageKey(metadata.extras?.getString(EXTRA_TRACK_SOURCE)),
        sourceId = metadata.extras?.getString(EXTRA_TRACK_SOURCE_ID).orEmpty(),
        title = metadata.title?.toString().orEmpty(),
        artist = metadata.artist?.toString().orEmpty(),
        album = metadata.albumTitle?.toString().orEmpty(),
        durationMs = metadata.extras?.getLong(EXTRA_DURATION_MS) ?: 0L,
        coverUrl = metadata.artworkUri?.toString().orEmpty(),
        audioUrl = localConfiguration?.uri?.toString().orEmpty(),
    ).withCanonicalIdentity()
}
