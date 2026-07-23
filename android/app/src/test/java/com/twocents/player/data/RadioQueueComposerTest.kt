package com.twocents.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioQueueComposerTest {
    @Test
    fun compose_ordersByScoreSpacesArtistsAndKeepsSurpriseOutOfFirstThree() {
        val composer = RadioQueueComposer()
        val candidates = listOf(
            candidate(id = "surprise", artist = "Surprise Artist", bucket = RadioCandidateBucket.SURPRISE, score = 100),
            candidate(id = "shared-1", artist = "Shared Artist,Feat A", bucket = RadioCandidateBucket.SAFE, score = 90),
            candidate(id = "shared-2", artist = "shared artist / feat b", bucket = RadioCandidateBucket.SAFE, score = 85),
            candidate(id = "safe-2", artist = "Safe Artist 2", bucket = RadioCandidateBucket.SAFE, score = 80),
            candidate(id = "safe-3", artist = "Safe Artist 3", bucket = RadioCandidateBucket.SAFE, score = 70),
        )

        val result = composer.compose(
            existingQueue = emptyList(),
            candidates = candidates,
            boundaryState = RadioBoundaryState.BALANCED,
        )

        assertEquals(
            listOf("shared-1", "safe-2", "safe-3", "surprise", "shared-2"),
            result.map { it.recommendation.track.id },
        )
    }

    @Test
    fun compose_fillsFromAllSafeCandidatesAndRelaxesArtistSpacingWhenPoolIsSparse() {
        val composer = RadioQueueComposer()
        val candidates = (1..6).map { index ->
            candidate(
                id = "safe-$index",
                artist = if (index <= 3) "Shared Artist" else "Artist $index",
                bucket = RadioCandidateBucket.SAFE,
                score = 100 - index,
            )
        }

        val result = composer.compose(
            existingQueue = emptyList(),
            candidates = candidates,
            boundaryState = RadioBoundaryState.BALANCED,
        )

        assertEquals(6, result.size)
        assertEquals(candidates.map { it.recommendation.track.id }.toSet(), result.map { it.recommendation.track.id }.toSet())
    }

    private fun candidate(
        id: String,
        artist: String,
        bucket: RadioCandidateBucket,
        audioUrl: String = "https://audio.example/$id.mp3",
        score: Int = 0,
    ): RadioResolvedCandidate {
        return RadioResolvedCandidate(
            recommendation = recommendation(
                id = id,
                artist = artist,
                audioUrl = audioUrl,
            ),
            bucket = bucket,
            score = score,
            stableKey = id,
        )
    }

    private fun recommendation(
        id: String,
        artist: String,
        audioUrl: String = "https://audio.example/$id.mp3",
    ): AiRecommendedTrack {
        return AiRecommendedTrack(
            track = Track(
                id = id,
                title = "Track $id",
                artist = artist,
                audioUrl = audioUrl,
            ),
            reason = "seed",
        )
    }
}
