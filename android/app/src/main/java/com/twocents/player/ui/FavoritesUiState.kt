package com.twocents.player.ui

import com.twocents.player.data.Track

data class FavoritesUiState(
    val isVisible: Boolean = false,
    val tracks: List<Track> = emptyList(),
)
