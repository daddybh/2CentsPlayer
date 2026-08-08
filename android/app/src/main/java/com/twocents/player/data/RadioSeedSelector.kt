package com.twocents.player.data

class RadioSeedSelector(
    private val maximumSeeds: Int = 3,
) {
    fun select(
        favorites: List<Track>,
        history: RadioHistorySnapshot,
        session: RadioSessionState,
    ): List<Track> {
        if (favorites.isEmpty() || maximumSeeds <= 0) return emptyList()

        val latestFeedbackByTrack = history.events
            .associateBy(RadioFeedbackEvent::trackId)

        val rankedSeeds = favorites.asSequence()
            .filterNot { it.id in history.negativeTrackIds || it.id in session.skippedTrackIds }
            .mapIndexed { index, track ->
                val feedbackScore = when (latestFeedbackByTrack[track.id]?.type) {
                    RadioFeedbackType.STRONG_POSITIVE -> 45
                    RadioFeedbackType.REPLAY_POSITIVE -> 40
                    RadioFeedbackType.POSITIVE -> 30
                    RadioFeedbackType.MILD_NEGATIVE,
                    RadioFeedbackType.STRONG_NEGATIVE,
                    null,
                    -> 0
                }
                val sessionScore = if (track.id in session.favoritedTrackIds) 35 else 0
                val negativeArtistScore = if (
                    artistKey(track.artist) in history.negativeArtistKeys
                ) {
                    -1_000
                } else {
                    0
                }
                ScoredSeed(
                    track = track,
                    score = (favorites.size - index).coerceAtLeast(1) +
                        feedbackScore +
                        sessionScore +
                        negativeArtistScore,
                    originalIndex = index,
                )
            }
            .sortedWith(
                compareByDescending<ScoredSeed>(ScoredSeed::score)
                    .thenBy(ScoredSeed::originalIndex)
                    .thenBy { it.track.id },
            )
            .distinctBy { artistKey(it.track.artist).ifBlank { it.track.id } }
            .toList()

        val anchor = rankedSeeds.firstOrNull() ?: return emptyList()
        val explorationSeeds = rankedSeeds.asSequence()
            .filterNot { it.track.id == anchor.track.id }
            .sortedWith(
                compareByDescending<ScoredSeed> { seed ->
                    seed.score - if (seed.track.id in session.usedSeedIds) USED_SEED_PENALTY else 0
                }
                    .thenBy(ScoredSeed::originalIndex)
                    .thenBy { it.track.id },
            )
            .take((maximumSeeds - 1).coerceAtLeast(0))
            .map(ScoredSeed::track)
            .toList()

        return listOf(anchor.track) + explorationSeeds
    }

    private fun artistKey(artist: String): String {
        return artist
            .split(',', '、', '/', '&')
            .firstOrNull()
            .orEmpty()
            .trim()
            .lowercase()
    }

    private data class ScoredSeed(
        val track: Track,
        val score: Int,
        val originalIndex: Int,
    )

    private companion object {
        const val USED_SEED_PENALTY = 80
    }
}
