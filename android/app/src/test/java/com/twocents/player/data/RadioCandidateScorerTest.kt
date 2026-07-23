package com.twocents.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioCandidateScorerTest {
    private val scorer = RadioCandidateScorer()

    @Test
    fun score_rewardsMultiSeedAndPositiveArtistAndPenalizesNegativeArtist() {
        val request = request(
            positiveArtistKeys = setOf("liked artist"),
            negativeArtistKeys = setOf("tired artist"),
        )
        val result = scorer.score(
            candidates = listOf(
                candidate("multi", "Liked Artist", setOf("seed-1", "seed-2")),
                candidate("single", "Neutral Artist", setOf("seed-1")),
                candidate("negative", "Tired Artist", setOf("seed-1")),
            ),
            request = request,
        )

        assertEquals(listOf("multi", "single", "negative"), result.map { it.recommendation.track.id })
        assertTrue(result[0].score > result[1].score)
        assertTrue(result[1].score > result[2].score)
    }

    @Test
    fun score_filtersNegativeAndAvoidedTracksAndUsesStableTieOrder() {
        val request = request(
            negativeTrackIds = setOf("negative"),
            avoidTrackIds = setOf("queued"),
        )
        val result = scorer.score(
            candidates = listOf(
                candidate("z-track", "Artist Z"),
                candidate("negative", "Artist N"),
                candidate("queued", "Artist Q"),
                candidate("a-track", "Artist A"),
            ),
            request = request,
        )

        assertEquals(listOf("a-track", "z-track"), result.map { it.recommendation.track.id })
    }

    private fun request(
        positiveArtistKeys: Set<String> = emptySet(),
        negativeArtistKeys: Set<String> = emptySet(),
        negativeTrackIds: Set<String> = emptySet(),
        avoidTrackIds: Set<String> = emptySet(),
    ): RadioRecommendationRequest {
        return RadioRecommendationRequest(
            boundaryState = RadioBoundaryState.BALANCED,
            waveTargets = RadioWaveTargets(4, 2, 1),
            rawCandidateLimit = 12,
            favoriteSeeds = listOf(
                track("seed-1", "Seed Artist 1"),
                track("seed-2", "Seed Artist 2"),
                track("seed-3", "Seed Artist 3"),
            ),
            positiveTrackIds = emptySet(),
            negativeTrackIds = negativeTrackIds,
            avoidTrackIds = avoidTrackIds,
            avoidArtistKeys = setOf("recent artist"),
            positiveArtistKeys = positiveArtistKeys,
            negativeArtistKeys = negativeArtistKeys,
        )
    }

    private fun candidate(
        id: String,
        artist: String,
        matchedSeedIds: Set<String> = emptySet(),
    ): RadioResolvedCandidate {
        return RadioResolvedCandidate(
            recommendation = AiRecommendedTrack(track(id, artist), "reason"),
            bucket = RadioCandidateBucket.SAFE,
            matchedSeedIds = matchedSeedIds,
            retrievalSources = setOf("netease-simi"),
        )
    }

    private fun track(id: String, artist: String): Track {
        return Track(
            id = id,
            sourceId = id,
            title = id,
            artist = artist,
        )
    }
}
