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
                    suggestedTrack("second-safe", "Artist 4", RadioCandidateBucket.SAFE),
                    suggestedTrack("second-adjacent", "Artist 5", RadioCandidateBucket.ADJACENT),
                    suggestedTrack("second-surprise", "Artist 6", RadioCandidateBucket.SURPRISE),
                    suggestedTrack("second-safe-2", "Artist 7", RadioCandidateBucket.SAFE),
                ),
            ),
        )
        val trackLookup = FakeRadioTrackLookup(
            matchedTracks = mapOf(
                "first-safe" to track("first-safe", "Artist 1", audioUrl = "https://audio.example/first-safe.mp3"),
                "first-adjacent" to track("first-adjacent", "Artist 2"),
                "first-missing" to track("first-missing", "Artist 3"),
                "second-safe" to track("second-safe", "Artist 4"),
                "second-adjacent" to track("second-adjacent", "Artist 5"),
                "second-surprise" to track("second-surprise", "Artist 6"),
                "second-safe-2" to track("second-safe-2", "Artist 7"),
            ),
            resolvedTracks = mapOf(
                "first-adjacent" to track("first-adjacent", "Artist 2", audioUrl = "https://audio.example/first-adjacent.mp3"),
                "second-safe" to track("second-safe", "Artist 4", audioUrl = "https://audio.example/second-safe.mp3"),
                "second-adjacent" to track("second-adjacent", "Artist 5", audioUrl = "https://audio.example/second-adjacent.mp3"),
                "second-surprise" to track("second-surprise", "Artist 6", audioUrl = "https://audio.example/second-surprise.mp3"),
                "second-safe-2" to track("second-safe-2", "Artist 7", audioUrl = "https://audio.example/second-safe-2.mp3"),
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
        assertTrue(result.appendedRecommendations.size >= 4)
        assertTrue(result.appendedRecommendations.all { it.track.audioUrl.isNotBlank() })
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

        override fun requestRadioCandidates(
            settings: AiServiceConfig,
            request: RadioRecommendationRequest,
        ): List<AiSuggestedTrack> {
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
