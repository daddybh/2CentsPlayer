package com.twocents.player.ui

import com.twocents.player.data.Track

data class SearchUiState(
    val isVisible: Boolean = false,
    val query: String = "",
    val activeQuery: String = "",
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasSearched: Boolean = false,
    val errorMessage: String? = null,
    val loadMoreErrorMessage: String? = null,
    val canLoadMore: Boolean = false,
    val nextOffset: Int = 0,
    val nextNeteaseOffset: Int = 0,
    val nextKuwoOffset: Int = 0,
    val results: List<Track> = emptyList(),
)
