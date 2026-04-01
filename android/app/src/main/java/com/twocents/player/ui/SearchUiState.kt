package com.twocents.player.ui

import com.twocents.player.data.Track

data class SearchUiState(
    val isVisible: Boolean = false,
    val query: String = "",
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val errorMessage: String? = null,
    val results: List<Track> = emptyList(),
)
