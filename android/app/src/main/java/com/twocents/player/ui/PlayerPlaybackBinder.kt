package com.twocents.player.ui
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.twocents.player.playback.PlaybackService
import com.twocents.player.playback.toMediaItem
import com.twocents.player.playback.toTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun BindPlayer(
    viewModel: PlayerViewModel,
) {
    val context = LocalContext.current.applicationContext
    val controllerFuture = remember(context) {
        MediaController.Builder(context, PlaybackService.sessionToken(context)).buildAsync()
    }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    val playbackState = viewModel.playbackState
    val pendingCommand = viewModel.pendingPlayerCommand

    DisposableEffect(controllerFuture) {
        controllerFuture.addListener(
            {
                controller = runCatching { controllerFuture.get() }.getOrNull()
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            controller = null
            MediaController.releaseFuture(controllerFuture)
        }
    }

    DisposableEffect(controller, viewModel) {
        val player = controller ?: return@DisposableEffect onDispose { }

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                viewModel.onPlayerIsPlayingChanged(isPlaying)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                syncPlayerState(player, viewModel)
            }

            override fun onPlayerError(error: PlaybackException) {
                viewModel.onPlayerError(error.message ?: error.errorCodeName)
            }
        }

        player.addListener(listener)
        syncPlayerState(player, viewModel)

        onDispose {
            player.removeListener(listener)
        }
    }

    LaunchedEffect(controller, pendingCommand?.id) {
        val player = controller ?: return@LaunchedEffect

        when (val command = pendingCommand) {
            is PlayerCommand.LoadTrack -> {
                player.setMediaItems(
                    command.queue.map { it.toMediaItem() },
                    command.index,
                    command.startPositionMs,
                )
                player.prepare()
                if (command.playWhenReady) {
                    player.play()
                } else {
                    player.pause()
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

    LaunchedEffect(controller, playbackState.currentTrack?.id, playbackState.isPlaying) {
        val player = controller ?: return@LaunchedEffect

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

private fun syncPlayerState(
    player: Player,
    viewModel: PlayerViewModel,
) {
    val queue = buildList(player.mediaItemCount) {
        for (index in 0 until player.mediaItemCount) {
            add(player.getMediaItemAt(index).toTrack())
        }
    }

    val currentIndex = player.currentMediaItemIndex
        .takeIf { it != C.INDEX_UNSET }
        ?.coerceIn(0, (queue.lastIndex).coerceAtLeast(0))
        ?: 0

    viewModel.onPlayerQueueChanged(
        queue = queue,
        currentIndex = currentIndex,
        positionMs = player.currentPosition,
        durationMs = player.duration,
        isPlaying = player.isPlaying,
    )
}
