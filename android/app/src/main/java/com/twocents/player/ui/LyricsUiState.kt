package com.twocents.player.ui

data class LyricsUiState(
    val isVisible: Boolean = false,
    val trackId: String? = null,
    val isLoading: Boolean = false,
    val credits: List<LyricCredit> = emptyList(),
    val lines: List<LyricLine> = emptyList(),
    val errorMessage: String? = null,
)

data class LyricCredit(
    val role: String,
    val name: String,
)

data class LyricLine(
    val timestampMs: Long,
    val text: String,
)
