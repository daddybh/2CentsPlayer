package com.twocents.player.ui

import com.twocents.player.data.AiRecommendedTrack

data class AiSettingsUiState(
    val isVisible: Boolean = false,
    val endpoint: String = "",
    val model: String = "",
    val accessKey: String = "",
) {
    val isConfigured: Boolean
        get() = endpoint.isNotBlank() && model.isNotBlank() && accessKey.isNotBlank()

    fun missingFields(): List<String> {
        return buildList {
            if (endpoint.isBlank()) add("接口地址")
            if (model.isBlank()) add("模型名")
            if (accessKey.isBlank()) add("Access Key")
        }
    }
}

data class AiRecommendationUiState(
    val isActive: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val tracks: List<AiRecommendedTrack> = emptyList(),
    val errorMessage: String? = null,
    val sourceFavoriteCount: Int = 0,
    val suggestionCount: Int = 0,
    val skippedCount: Int = 0,
)
