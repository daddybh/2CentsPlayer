package com.twocents.player.data

interface RadioCandidateSource {
    fun requestRadioCandidates(
        settings: AiServiceConfig,
        request: RadioRecommendationRequest,
    ): List<AiSuggestedTrack>
}

interface RadioTrackLookup {
    fun findBestMatchTrack(
        title: String,
        artist: String = "",
    ): Track?

    fun resolvePlayableTracks(tracks: List<Track>): List<Track>
}
