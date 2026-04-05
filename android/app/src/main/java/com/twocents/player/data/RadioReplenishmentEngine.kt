package com.twocents.player.data

data class RadioReplenishmentResult(
    val appendedRecommendations: List<AiRecommendedTrack>,
    val suggestionCount: Int,
    val updatedSession: RadioSessionState,
)

class RadioReplenishmentEngine(
    private val candidateSource: RadioCandidateSource,
    private val trackLookup: RadioTrackLookup,
    private val planner: RadioRecommendationPlanner = RadioRecommendationPlanner(),
    private val composer: RadioQueueComposer = RadioQueueComposer(),
) {
    fun replenish(
        settings: AiServiceConfig,
        favorites: List<Track>,
        history: RadioHistorySnapshot,
        session: RadioSessionState,
        minimumRequiredAppend: Int = MIN_SAFE_APPEND,
        requestTransform: (RadioRecommendationRequest) -> RadioRecommendationRequest = { it },
    ): RadioReplenishmentResult {
        var attempts = 0
        var workingSession = session
        var suggestionCount = 0
        var appended = emptyList<RadioResolvedCandidate>()
        var forceRecoveringRetry = false

        while (attempts < MAX_ATTEMPTS && appended.size < minimumRequiredAppend) {
            val planningSession = workingSession.copy(
                queuedRecommendations = workingSession.queuedRecommendations + appended.map { it.recommendation },
            )
            val plannedRequest = planner.buildRequest(favorites, history, planningSession)
            val request = if (forceRecoveringRetry) {
                plannedRequest.copy(
                    boundaryState = RadioBoundaryState.RECOVERING,
                    waveTargets = RadioWaveTargets(5, 1, 0),
                )
            } else {
                requestTransform(plannedRequest)
            }
            workingSession = workingSession.copy(
                boundaryState = request.boundaryState,
                statusLabel = request.boundaryState.statusLabel(),
            )

            val suggestions = candidateSource.requestRadioCandidates(settings, request)
            suggestionCount += suggestions.size

            val matchedCandidates = suggestions.mapNotNull { suggestion ->
                val matchedTrack = trackLookup.findBestMatchTrack(
                    title = suggestion.title,
                    artist = suggestion.artist,
                ) ?: return@mapNotNull null

                RadioResolvedCandidate(
                    recommendation = AiRecommendedTrack(
                        track = matchedTrack,
                        reason = suggestion.reason,
                    ),
                    bucket = suggestion.bucket,
                )
            }.filterNot { candidate ->
                candidate.recommendation.track.isLocallyExcluded(request)
            }

            val unresolvedTracks = matchedCandidates
                .map { it.recommendation.track }
                .filter { it.audioUrl.isBlank() }
            val resolvedPlayableById = trackLookup.resolvePlayableTracks(unresolvedTracks)
                .associateBy { it.id }

            val playable = matchedCandidates.mapNotNull { candidate ->
                val finalTrack = resolvedPlayableById[candidate.recommendation.track.id]
                    ?: candidate.recommendation.track
                if (finalTrack.audioUrl.isBlank()) {
                    null
                } else {
                    candidate.copy(
                        recommendation = candidate.recommendation.copy(track = finalTrack),
                    )
                }
            }

            val newlyAppended = composer.compose(
                existingQueue = workingSession.queuedRecommendations + appended.map { it.recommendation },
                candidates = playable,
                boundaryState = workingSession.boundaryState,
            )
            appended = appended + newlyAppended

            if (appended.size >= minimumRequiredAppend) {
                break
            }

            workingSession = workingSession.copy(
                boundaryState = RadioBoundaryState.RECOVERING,
                statusLabel = RadioBoundaryState.RECOVERING.statusLabel(),
                consecutiveLowYieldCount = workingSession.consecutiveLowYieldCount + 1,
            )
            forceRecoveringRetry = true
            attempts += 1
        }

        val nextQueue = workingSession.queuedRecommendations + appended.map { it.recommendation }

        return RadioReplenishmentResult(
            appendedRecommendations = appended.map { it.recommendation },
            suggestionCount = suggestionCount,
            updatedSession = workingSession.copy(
                queuedRecommendations = nextQueue,
                statusLabel = if (
                    appended.isEmpty() &&
                    workingSession.statusLabel == RadioBoundaryState.RECOVERING.statusLabel()
                ) {
                    RadioBoundaryState.RECOVERING.statusLabel()
                } else {
                    workingSession.statusLabel
                },
            ),
        )
    }

    private fun RadioBoundaryState.statusLabel(): String {
        return when (this) {
            RadioBoundaryState.BALANCED -> "探索中"
            RadioBoundaryState.EXPANDING -> "正在扩圈"
            RadioBoundaryState.RECOVERING -> "回到熟悉区"
        }
    }

    private fun Track.isLocallyExcluded(request: RadioRecommendationRequest): Boolean {
        return id in request.negativeTrackIds ||
            id in request.avoidTrackIds ||
            radioArtistKey() in request.avoidArtistKeys
    }

    private fun Track.radioArtistKey(): String {
        return artist
            .split(',', '、', '/', '&')
            .firstOrNull()
            .orEmpty()
            .trim()
            .lowercase()
    }

    companion object {
        const val MAX_ATTEMPTS: Int = 3
        const val MIN_SAFE_APPEND: Int = 4
    }
}
