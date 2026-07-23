package com.twocents.player.data

data class RadioRecommendationRequest(
    val boundaryState: RadioBoundaryState,
    val waveTargets: RadioWaveTargets,
    val rawCandidateLimit: Int,
    val favoriteSeeds: List<Track>,
    val positiveTrackIds: Set<String>,
    val negativeTrackIds: Set<String>,
    val avoidTrackIds: Set<String>,
    val avoidArtistKeys: Set<String>,
    val positiveArtistKeys: Set<String> = emptySet(),
    val negativeArtistKeys: Set<String> = emptySet(),
)

class RadioRecommendationPlanner(
    private val seedSelector: RadioSeedSelector = RadioSeedSelector(),
) {
    fun buildRequest(
        favorites: List<Track>,
        history: RadioHistorySnapshot,
        session: RadioSessionState,
    ): RadioRecommendationRequest {
        val recentFeedbackScore = history.events.takeLast(5).fold(0) { score, event ->
            score + when (event.type) {
                RadioFeedbackType.STRONG_NEGATIVE -> -2
                RadioFeedbackType.MILD_NEGATIVE -> -1
                RadioFeedbackType.POSITIVE -> 1
                RadioFeedbackType.STRONG_POSITIVE,
                RadioFeedbackType.REPLAY_POSITIVE,
                -> 2
            }
        }
        val boundaryState = when {
            recentFeedbackScore <= -4 ||
                history.events.takeLast(3).count {
                    it.type == RadioFeedbackType.STRONG_NEGATIVE
                } >= 2 -> RadioBoundaryState.RECOVERING
            history.events.lastOrNull()?.type in setOf(
                RadioFeedbackType.STRONG_POSITIVE,
                RadioFeedbackType.REPLAY_POSITIVE,
            ) -> RadioBoundaryState.EXPANDING
            else -> RadioBoundaryState.BALANCED
        }

        val waveTargets = when (boundaryState) {
            RadioBoundaryState.RECOVERING -> RadioWaveTargets(5, 1, 0)
            RadioBoundaryState.EXPANDING -> RadioWaveTargets(3, 3, 1)
            RadioBoundaryState.BALANCED -> RadioWaveTargets(4, 2, 1)
        }

        return RadioRecommendationRequest(
            boundaryState = boundaryState,
            waveTargets = waveTargets,
            rawCandidateLimit = 12,
            favoriteSeeds = seedSelector.select(favorites, history, session),
            positiveTrackIds = history.positiveTrackIds + session.favoritedTrackIds,
            negativeTrackIds = history.negativeTrackIds + session.skippedTrackIds,
            avoidTrackIds = session.queuedRecommendations.map { it.track.id }.toSet() + session.playedTrackIds,
            avoidArtistKeys = history.recentArtistKeys.take(3).toSet(),
            positiveArtistKeys = history.positiveArtistKeys,
            negativeArtistKeys = history.negativeArtistKeys,
        )
    }
}
