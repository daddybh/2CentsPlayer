package com.twocents.player.data

data class AiServiceConfig(
    val endpoint: String = "",
    val model: String = "",
    val accessKey: String = "",
) {
    val isComplete: Boolean
        get() = endpoint.isNotBlank() && model.isNotBlank() && accessKey.isNotBlank()

    fun chatCompletionsUrl(): String {
        val rawEndpoint = endpoint.trim()
        if (rawEndpoint.isBlank()) return ""

        val queryStartIndex = rawEndpoint.indexOf('?')
        val querySuffix = if (queryStartIndex >= 0) {
            rawEndpoint.substring(queryStartIndex)
        } else {
            ""
        }
        val baseEndpoint = if (queryStartIndex >= 0) {
            rawEndpoint.substring(0, queryStartIndex)
        } else {
            rawEndpoint
        }.trimEnd('/')

        val normalizedBase = if (baseEndpoint.endsWith("/chat/completions")) {
            baseEndpoint
        } else {
            "$baseEndpoint/chat/completions"
        }

        return normalizedBase + querySuffix
    }
}

data class AiSuggestedTrack(
    val title: String,
    val artist: String,
    val reason: String = "",
    val bucket: RadioCandidateBucket = RadioCandidateBucket.SAFE,
)

data class AiRecommendedTrack(
    val track: Track,
    val reason: String = "",
)
