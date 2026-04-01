package com.twocents.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun BindPlayer(
    viewModel: PlayerViewModel,
) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
        }
    }
    val playbackState = viewModel.playbackState
    val pendingCommand = viewModel.pendingPlayerCommand

    DisposableEffect(player, viewModel) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                viewModel.onPlayerIsPlayingChanged(isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        viewModel.onPlayerProgress(
                            positionMs = player.currentPosition,
                            durationMs = player.duration,
                        )
                    }

                    Player.STATE_ENDED -> {
                        viewModel.onTrackEnded()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                viewModel.onPlayerError(error.localizedMessage ?: error.errorCodeName)
            }
        }

        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(pendingCommand?.id) {
        when (val command = pendingCommand) {
            is PlayerCommand.LoadTrack -> {
                val mediaItem = MediaItem.Builder()
                    .setUri(command.track.audioUrl)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(command.track.title)
                            .setArtist(command.track.artist)
                            .setAlbumTitle(command.track.album)
                            .setArtworkUri(command.track.coverUrl.takeIf { it.isNotBlank() }?.toUri())
                            .build(),
                    )
                    .build()

                player.setMediaItem(mediaItem, command.startPositionMs)
                player.prepare()
                player.playWhenReady = command.playWhenReady
                if (command.playWhenReady) {
                    player.play()
                }
                viewModel.onPlayerCommandHandled(command.id)
            }

            is PlayerCommand.SetPlayWhenReady -> {
                player.playWhenReady = command.playWhenReady
                if (command.playWhenReady) {
                    player.play()
                } else {
                    player.pause()
                }
                viewModel.onPlayerCommandHandled(command.id)
            }

            is PlayerCommand.SeekTo -> {
                player.seekTo(command.positionMs)
                viewModel.onPlayerCommandHandled(command.id)
            }

            null -> Unit
        }
    }

    LaunchedEffect(player, playbackState.currentTrack?.id, playbackState.isPlaying) {
        while (isActive) {
            if (playbackState.currentTrack != null) {
                viewModel.onPlayerProgress(
                    positionMs = player.currentPosition,
                    durationMs = player.duration,
                )
            }
            delay(if (playbackState.isPlaying) 500L else 1000L)
        }
    }
}
