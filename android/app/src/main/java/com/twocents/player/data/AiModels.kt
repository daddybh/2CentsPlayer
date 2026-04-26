package com.twocents.player.data

data class AiServiceConfig(
    val endpoint: String = "",
    val model: String = "",
    val accessKey: String = "",
    val lastFmApiKey: String = "",
) {
    val isComplete: Boolean
        get() = endpoint.isNotBlank() && model.isNotBlank() && accessKey.isNotBlank()

    val hasLastFm: Boolean
        get() = lastFmApiKey.isNotBlank()

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
    val resolvedTrack: Track? = null,
)

data class AiRecommendedTrack(
    val track: Track,
    val reason: String = "",
)

data class SearchToolCall(
    val query: String,
    val limit: Int,
)

data class SearchToolCandidate(
    val candidateId: String,
    val title: String,
    val artist: String,
    val album: String,
    val source: String,
    val durationMs: Long,
)

data class ToolSelectedTrack(
    val candidateId: String,
    val reason: String,
    val bucket: RadioCandidateBucket,
)
