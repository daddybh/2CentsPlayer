package com.twocents.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioPlaybackFeedbackTest {
    @Test
    fun classifySkip_returnsStrongNegativeForVeryEarlySkip() {
        assertEquals(
            RadioFeedbackType.STRONG_NEGATIVE,
            classifySkip(positionMs = 25_000L, durationMs = 240_000L),
        )
    }

    @Test
    fun classifyCompletion_returnsPositiveWhenTrackMostlyPlayed() {
        assertEquals(
            RadioFeedbackType.POSITIVE,
            classifyCompletion(positionMs = 150_000L, durationMs = 180_000L),
        )
    }

    @Test
    fun classifyCompletion_returnsNullForShortAbandon() {
        assertNull(
            classifyCompletion(positionMs = 40_000L, durationMs = 180_000L),
        )
    }
}
