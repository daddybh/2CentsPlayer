package com.twocents.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioReplenishmentEngineTest {
    @Test
    fun replenish_retriesUntilMinimumPlayableCountIsReached() {
        val candidateSource = FakeRadioCandidateSource(
            responses = listOf(
                listOf(
                    suggestedTrack("first-safe", "Artist 1", RadioCandidateBucket.SAFE),
                    suggestedTrack("first-adjacent", "Artist 2", RadioCandidateBucket.ADJACENT),
                    suggestedTrack("first-missing", "Artist 3", RadioCandidateBucket.SAFE),
                ),
                listOf(
                    suggestedTrack("first-safe", "Artist 1", RadioCandidateBucket.SAFE),
                    suggestedTrack("second-same-artist", "Artist 2", RadioCandidateBucket.SAFE),
                    suggestedTrack("second-safe", "Artist 4", RadioCandidateBucket.SAFE),
                    suggestedTrack("second-adjacent", "Artist 5", RadioCandidateBucket.ADJACENT),
                ),
            ),
        )
        val trackLookup = FakeRadioTrackLookup(
            matchedTracks = mapOf(
                "first-safe" to track("first-safe", "Artist 1", audioUrl = "https://audio.example/first-safe.mp3"),
                "first-adjacent" to track("first-adjacent", "Artist 2"),
                "first-missing" to track("first-missing", "Artist 3"),
                "second-same-artist" to track("second-same-artist", "Artist 2"),
                "second-safe" to track("second-safe", "Artist 4"),
                "second-adjacent" to track("second-adjacent", "Artist 5"),
            ),
            resolvedTracks = mapOf(
                "first-adjacent" to track("first-adjacent", "Artist 2", audioUrl = "https://audio.example/first-adjacent.mp3"),
                "second-same-artist" to track("second-same-artist", "Artist 2", audioUrl = "https://audio.example/second-same-artist.mp3"),
                "second-safe" to track("second-safe", "Artist 4", audioUrl = "https://audio.example/second-safe.mp3"),
                "second-adjacent" to track("second-adjacent", "Artist 5", audioUrl = "https://audio.example/second-adjacent.mp3"),
            ),
        )
        val engine = RadioReplenishmentEngine(
            candidateSource = candidateSource,
            trackLookup = trackLookup,
        )

        val result = engine.replenish(
            settings = AiServiceConfig(endpoint = "https://api.example", model = "test-model", accessKey = "secret"),
            favorites = listOf(track("favorite-1", "Favorite Artist", audioUrl = "https://audio.example/favorite-1.mp3")),
            history = RadioHistorySnapshot(),
            session = RadioSessionState(sessionId = 7L),
        )

        assertEquals(2, candidateSource.callCount)
        assertEquals(4, result.appendedRecommendations.size)
        assertEquals(
            listOf("first-safe", "first-adjacent", "second-safe", "second-adjacent"),
            result.appendedRecommendations.map { it.track.id },
        )
        assertTrue(result.appendedRecommendations.all { it.track.audioUrl.isNotBlank() })
        assertEquals(RadioBoundaryState.BALANCED, candidateSource.requests[0].boundaryState)
        assertEquals(RadioWaveTargets(4, 2, 1), candidateSource.requests[0].waveTargets)
        assertEquals(RadioBoundaryState.RECOVERING, candidateSource.requests[1].boundaryState)
        assertEquals(RadioWaveTargets(5, 1, 0), candidateSource.requests[1].waveTargets)
    }

    @Test
    fun replenish_marksSessionDegradedWhenAllRetriesFail() {
        val candidateSource = FakeRadioCandidateSource(
            responses = listOf(
                listOf(suggestedTrack("missing-1", "Artist 1", RadioCandidateBucket.SAFE)),
                listOf(suggestedTrack("missing-2", "Artist 2", RadioCandidateBucket.ADJACENT)),
                listOf(suggestedTrack("missing-3", "Artist 3", RadioCandidateBucket.SURPRISE)),
            ),
        )
        val trackLookup = FakeRadioTrackLookup(
            matchedTracks = mapOf(
                "missing-1" to track("missing-1", "Artist 1"),
                "missing-2" to track("missing-2", "Artist 2"),
                "missing-3" to track("missing-3", "Artist 3"),
            ),
        )
        val engine = RadioReplenishmentEngine(
            candidateSource = candidateSource,
            trackLookup = trackLookup,
        )

        val result = engine.replenish(
            settings = AiServiceConfig(endpoint = "https://api.example", model = "test-model", accessKey = "secret"),
            favorites = listOf(track("favorite-1", "Favorite Artist", audioUrl = "https://audio.example/favorite-1.mp3")),
            history = RadioHistorySnapshot(),
            session = RadioSessionState(sessionId = 11L),
        )

        assertTrue(result.appendedRecommendations.isEmpty())
        assertEquals(3, candidateSource.callCount)
        assertEquals(RadioBoundaryState.RECOVERING, result.updatedSession.boundaryState)
        assertEquals("回到熟悉区", result.updatedSession.statusLabel)
    }

    private fun suggestedTrack(
        title: String,
        artist: String,
        bucket: RadioCandidateBucket,
    ): AiSuggestedTrack {
        return AiSuggestedTrack(
            title = title,
            artist = artist,
            reason = "reason-$title",
            bucket = bucket,
        )
    }

    private fun track(
        id: String,
        artist: String,
        audioUrl: String = "",
    ): Track {
        return Track(
            id = id,
            title = id,
            artist = artist,
            audioUrl = audioUrl,
        )
    }

    private class FakeRadioCandidateSource(
        private val responses: List<List<AiSuggestedTrack>>,
    ) : RadioCandidateSource {
        var callCount: Int = 0
            private set
        val requests = mutableListOf<RadioRecommendationRequest>()

        override fun requestRadioCandidates(
            settings: AiServiceConfig,
            request: RadioRecommendationRequest,
        ): List<AiSuggestedTrack> {
            requests += request
            val response = responses.getOrElse(callCount) { emptyList() }
            callCount += 1
            return response
        }
    }

    private class FakeRadioTrackLookup(
        private val matchedTracks: Map<String, Track>,
        private val resolvedTracks: Map<String, Track> = emptyMap(),
    ) : RadioTrackLookup {
        override fun findBestMatchTrack(
            title: String,
            artist: String,
        ): Track? = matchedTracks[title]

        override fun resolvePlayableTracks(tracks: List<Track>): List<Track> {
            return tracks.mapNotNull { track -> resolvedTracks[track.id] }
        }
    }
}
