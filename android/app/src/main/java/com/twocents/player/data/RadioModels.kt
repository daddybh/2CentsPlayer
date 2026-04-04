package com.twocents.player.data

enum class RadioFeedbackType {
    STRONG_POSITIVE,
    POSITIVE,
    MILD_NEGATIVE,
    STRONG_NEGATIVE,
    REPLAY_POSITIVE,
}

enum class RadioBoundaryState {
    BALANCED,
    EXPANDING,
    RECOVERING,
}

enum class RadioCandidateBucket {
    SAFE,
    ADJACENT,
    SURPRISE,
}

data class RadioFeedbackEvent(
    val trackId: String,
    val artistKey: String,
    val type: RadioFeedbackType,
    val timestampMs: Long,
)

data class RadioHistorySnapshot(
    val events: List<RadioFeedbackEvent> = emptyList(),
    val positiveTrackIds: Set<String> = emptySet(),
    val negativeTrackIds: Set<String> = emptySet(),
    val positiveArtistKeys: Set<String> = emptySet(),
    val negativeArtistKeys: Set<String> = emptySet(),
    val recentTrackIds: List<String> = emptyList(),
    val recentArtistKeys: List<String> = emptyList(),
)

data class RadioWaveTargets(
    val safeCount: Int,
    val adjacentCount: Int,
    val surpriseCount: Int,
) {
    val totalCount: Int
        get() = safeCount + adjacentCount + surpriseCount
}

data class RadioSessionState(
    val sessionId: Long,
    val queuedRecommendations: List<AiRecommendedTrack> = emptyList(),
    val playedTrackIds: Set<String> = emptySet(),
    val skippedTrackIds: Set<String> = emptySet(),
    val favoritedTrackIds: Set<String> = emptySet(),
    val boundaryState: RadioBoundaryState = RadioBoundaryState.BALANCED,
    val statusLabel: String = "探索中",
    val isLoadingMore: Boolean = false,
    val lastAutoAppendRemainingCount: Int = -1,
    val consecutiveLowYieldCount: Int = 0,
)
