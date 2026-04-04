package com.twocents.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioRecommendationPlannerTest {
    @Test
    fun buildRequest_usesBalancedWaveByDefault() {
        val planner = RadioRecommendationPlanner()
        val favorites = (1..35).map { index ->
            track(
                id = "fav-$index",
                artist = "Favorite Artist $index",
            )
        }
        val history = RadioHistorySnapshot(
            events = listOf(
                feedbackEvent("older-positive", "artist-a", RadioFeedbackType.POSITIVE, 10L),
                feedbackEvent("older-negative", "artist-b", RadioFeedbackType.MILD_NEGATIVE, 20L),
            ),
            positiveTrackIds = setOf("older-positive"),
            negativeTrackIds = setOf("older-negative"),
            recentArtistKeys = listOf("artist-a", "artist-b", "artist-a"),
        )
        val session = RadioSessionState(
            sessionId = 101L,
            queuedRecommendations = listOf(
                recommendation(track(id = "queue-1", artist = "Queue Artist")),
            ),
            playedTrackIds = setOf("played-1"),
            skippedTrackIds = setOf("skipped-1"),
            favoritedTrackIds = emptySet(),
        )

        val result = planner.buildRequest(
            favorites = favorites,
            history = history,
            session = session,
        )

        assertEquals(RadioBoundaryState.BALANCED, result.boundaryState)
        assertEquals(RadioWaveTargets(4, 2, 1), result.waveTargets)
        assertEquals(12, result.rawCandidateLimit)
        assertEquals(30, result.favoriteSeeds.size)
        assertEquals(favorites.take(30), result.favoriteSeeds)
        assertEquals(setOf("older-positive"), result.positiveTrackIds)
        assertEquals(setOf("older-negative", "skipped-1"), result.negativeTrackIds)
        assertEquals(setOf("queue-1", "played-1"), result.avoidTrackIds)
        assertEquals(setOf("artist-a", "artist-b"), result.avoidArtistKeys)
    }

    @Test
    fun buildRequest_shrinksAfterTwoStrongNegatives() {
        val planner = RadioRecommendationPlanner()
        val history = RadioHistorySnapshot(
            events = listOf(
                feedbackEvent("old-1", "artist-old-1", RadioFeedbackType.POSITIVE, 100L),
                feedbackEvent("recent-1", "artist-recent-1", RadioFeedbackType.STRONG_NEGATIVE, 200L),
                feedbackEvent("recent-2", "artist-recent-2", RadioFeedbackType.POSITIVE, 300L),
                feedbackEvent("recent-3", "artist-recent-3", RadioFeedbackType.STRONG_NEGATIVE, 400L),
            ),
            positiveTrackIds = setOf("history-positive"),
            negativeTrackIds = setOf("history-negative"),
            recentArtistKeys = listOf("artist-recent-1", "artist-recent-2"),
        )
        val session = RadioSessionState(
            sessionId = 202L,
            skippedTrackIds = setOf("session-skipped"),
            favoritedTrackIds = setOf("session-favorited"),
        )

        val result = planner.buildRequest(
            favorites = emptyList(),
            history = history,
            session = session,
        )

        assertEquals(RadioBoundaryState.RECOVERING, result.boundaryState)
        assertEquals(RadioWaveTargets(5, 1, 0), result.waveTargets)
        assertEquals(setOf("history-positive", "session-favorited"), result.positiveTrackIds)
        assertEquals(setOf("history-negative", "session-skipped"), result.negativeTrackIds)
    }

    private fun feedbackEvent(
        trackId: String,
        artistKey: String,
        type: RadioFeedbackType,
        timestampMs: Long,
    ): RadioFeedbackEvent {
        return RadioFeedbackEvent(
            trackId = trackId,
            artistKey = artistKey,
            type = type,
            timestampMs = timestampMs,
        )
    }

    private fun recommendation(track: Track): AiRecommendedTrack {
        return AiRecommendedTrack(
            track = track,
            reason = "seed",
        )
    }

    private fun track(
        id: String,
        artist: String,
        audioUrl: String = "https://audio.example/$id.mp3",
    ): Track {
        return Track(
            id = id,
            title = "Title $id",
            artist = artist,
            audioUrl = audioUrl,
        )
    }
}
