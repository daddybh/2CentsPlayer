package com.twocents.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioSeedSelectorTest {
    private val selector = RadioSeedSelector()

    @Test
    fun select_isDeterministicAndUsesDifferentArtists() {
        val favorites = listOf(
            track("fav-a1", "Artist A"),
            track("fav-a2", "artist a / Guest"),
            track("fav-b", "Artist B"),
            track("fav-c", "Artist C"),
        )

        val first = selector.select(favorites, RadioHistorySnapshot(), RadioSessionState(1L))
        val second = selector.select(favorites, RadioHistorySnapshot(), RadioSessionState(1L))

        assertEquals(listOf("fav-a1", "fav-b", "fav-c"), first.map(Track::id))
        assertEquals(first, second)
    }

    @Test
    fun select_prioritizesRecentPositiveOverFavoriteOrder() {
        val favorites = listOf(
            track("ordinary-a", "Artist A"),
            track("ordinary-b", "Artist B"),
            track("positive-c", "Artist C"),
            track("ordinary-d", "Artist D"),
        )
        val positive = RadioFeedbackEvent(
            trackId = "positive-c",
            artistKey = "artist c",
            type = RadioFeedbackType.REPLAY_POSITIVE,
            timestampMs = 30L,
        )

        val result = selector.select(
            favorites = favorites,
            history = RadioHistorySnapshot(
                events = listOf(positive),
                positiveTrackIds = setOf("positive-c"),
            ),
            session = RadioSessionState(2L),
        )

        assertEquals("positive-c", result.first().id)
    }

    @Test
    fun select_keepsAnchorWhileRotatingExplorationSeeds() {
        val favorites = (1..6).map { index ->
            track("fav-$index", "Artist $index")
        }

        val result = selector.select(
            favorites = favorites,
            history = RadioHistorySnapshot(),
            session = RadioSessionState(
                sessionId = 3L,
                usedSeedIds = setOf("fav-1", "fav-2", "fav-3"),
            ),
        )

        assertEquals(listOf("fav-1", "fav-4", "fav-5"), result.map(Track::id))
    }

    @Test
    fun select_demotesArtistsWithRecentNegativeFeedback() {
        val favorites = listOf(
            track("negative-artist", "Shakira"),
            track("fav-b", "Artist B"),
            track("fav-c", "Artist C"),
            track("fav-d", "Artist D"),
        )

        val result = selector.select(
            favorites = favorites,
            history = RadioHistorySnapshot(negativeArtistKeys = setOf("shakira")),
            session = RadioSessionState(sessionId = 4L),
        )

        assertEquals(listOf("fav-b", "fav-c", "fav-d"), result.map(Track::id))
    }

    private fun track(id: String, artist: String): Track {
        return Track(id = id, title = id, artist = artist)
    }
}
