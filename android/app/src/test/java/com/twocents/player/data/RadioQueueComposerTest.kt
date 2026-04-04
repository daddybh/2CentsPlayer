package com.twocents.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RadioQueueComposerTest {
    @Test
    fun compose_filtersDuplicateArtistsAndKeepsSurpriseAwayFromFirstSlot() {
        val composer = RadioQueueComposer()
        val existingQueue = listOf(
            recommendation(id = "existing-track", artist = "Queue Artist"),
        )
        val candidates = listOf(
            candidate(id = "blank-url", artist = "Blank Artist", bucket = RadioCandidateBucket.SAFE, audioUrl = ""),
            candidate(id = "existing-track", artist = "Queue Artist", bucket = RadioCandidateBucket.SAFE),
            candidate(id = "safe-1", artist = "Shared Artist,Feat A", bucket = RadioCandidateBucket.SAFE),
            candidate(id = "adjacent-1", artist = "Adjacent Artist", bucket = RadioCandidateBucket.ADJACENT),
            candidate(id = "safe-dup-artist", artist = "shared artist / feat b", bucket = RadioCandidateBucket.SAFE),
            candidate(id = "surprise-1", artist = "Surprise Artist", bucket = RadioCandidateBucket.SURPRISE),
            candidate(id = "safe-2", artist = "Safe Artist 2", bucket = RadioCandidateBucket.SAFE),
            candidate(id = "adjacent-2", artist = "Adjacent Artist 2", bucket = RadioCandidateBucket.ADJACENT),
        )

        val result = composer.compose(
            existingQueue = existingQueue,
            candidates = candidates,
            boundaryState = RadioBoundaryState.BALANCED,
        )

        assertNotEquals(RadioCandidateBucket.SURPRISE, result.first().bucket)
        assertEquals(
            listOf("safe-1", "adjacent-1", "safe-2", "surprise-1", "adjacent-2"),
            result.map { it.recommendation.track.id },
        )
    }

    private fun candidate(
        id: String,
        artist: String,
        bucket: RadioCandidateBucket,
        audioUrl: String = "https://audio.example/$id.mp3",
    ): RadioResolvedCandidate {
        return RadioResolvedCandidate(
            recommendation = recommendation(
                id = id,
                artist = artist,
                audioUrl = audioUrl,
            ),
            bucket = bucket,
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
