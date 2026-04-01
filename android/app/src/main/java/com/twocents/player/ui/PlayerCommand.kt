package com.twocents.player.ui

import com.twocents.player.data.Track

sealed interface PlayerCommand {
    val id: Long

    data class LoadTrack(
        override val id: Long,
        val track: Track,
        val playWhenReady: Boolean,
        val startPositionMs: Long = 0L,
    ) : PlayerCommand

    data class SetPlayWhenReady(
        override val id: Long,
        val playWhenReady: Boolean,
    ) : PlayerCommand

    data class SeekTo(
        override val id: Long,
        val positionMs: Long,
    ) : PlayerCommand
}
