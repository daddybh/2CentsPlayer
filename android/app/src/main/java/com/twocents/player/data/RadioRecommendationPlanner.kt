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
)

class RadioRecommendationPlanner {
    fun buildRequest(
        favorites: List<Track>,
        history: RadioHistorySnapshot,
        session: RadioSessionState,
    ): RadioRecommendationRequest {
        val boundaryState = when {
            history.events.takeLast(3).count { it.type == RadioFeedbackType.STRONG_NEGATIVE } >= 2 -> RadioBoundaryState.RECOVERING
            session.favoritedTrackIds.isNotEmpty() -> RadioBoundaryState.EXPANDING
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
            favoriteSeeds = favorites.take(30),
            positiveTrackIds = history.positiveTrackIds + session.favoritedTrackIds,
            negativeTrackIds = history.negativeTrackIds + session.skippedTrackIds,
            avoidTrackIds = session.queuedRecommendations.map { it.track.id }.toSet() + session.playedTrackIds,
            avoidArtistKeys = history.recentArtistKeys.toSet(),
        )
    }
}
