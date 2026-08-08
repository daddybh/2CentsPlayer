package com.twocents.player.data

data class RadioResolvedCandidate(
    val recommendation: AiRecommendedTrack,
    val bucket: RadioCandidateBucket,
    val matchedSeedIds: Set<String> = emptySet(),
    val retrievalSources: Set<String> = emptySet(),
    val score: Int = 0,
    val stableKey: String = "",
    val sourceRank: Int = Int.MAX_VALUE,
)

class RadioQueueComposer {
    fun compose(
        existingQueue: List<AiRecommendedTrack>,
        candidates: List<RadioResolvedCandidate>,
        boundaryState: RadioBoundaryState,
    ): List<RadioResolvedCandidate> {
        val maximumCount = when (boundaryState) {
            RadioBoundaryState.BALANCED,
            RadioBoundaryState.EXPANDING,
            -> 6
            RadioBoundaryState.RECOVERING -> 4
        }
        val existingTrackIds = existingQueue.map { it.track.id }.toSet()
        val existingStableKeys = existingQueue.map { stableTrackKey(it.track) }.toSet()
        val recentArtists = existingQueue.takeLast(2)
            .map { artistKey(it.track.artist) }
            .filter(String::isNotBlank)
            .toMutableList()
        val remaining = candidates.asSequence()
            .filterNot { it.recommendation.track.id in existingTrackIds }
            .filterNot { stableTrackKey(it.recommendation.track) in existingStableKeys }
            .distinctBy { it.stableKey.ifBlank { stableTrackKey(it.recommendation.track) } }
            .sortedWith(
                compareByDescending<RadioResolvedCandidate>(RadioResolvedCandidate::score)
                    .thenBy(RadioResolvedCandidate::sourceRank)
                    .thenBy { it.stableKey.ifBlank { stableTrackKey(it.recommendation.track) } },
            )
            .toMutableList()
        val selected = mutableListOf<RadioResolvedCandidate>()

        while (remaining.isNotEmpty() && selected.size < maximumCount) {
            val strictIndex = remaining.indexOfFirst { candidate ->
                val artist = artistKey(candidate.recommendation.track.artist)
                val artistAllowed = artist.isBlank() || artist !in recentArtists.takeLast(2)
                val surpriseAllowed = selected.size >= 3 || candidate.bucket != RadioCandidateBucket.SURPRISE
                artistAllowed && surpriseAllowed
            }
            val relaxedSurpriseIndex = if (strictIndex < 0 && selected.size < 3) {
                remaining.indexOfFirst { it.bucket != RadioCandidateBucket.SURPRISE }
            } else {
                -1
            }
            val selectedIndex = when {
                strictIndex >= 0 -> strictIndex
                relaxedSurpriseIndex >= 0 -> relaxedSurpriseIndex
                else -> 0
            }
            val candidate = remaining.removeAt(selectedIndex)
            selected += candidate
            artistKey(candidate.recommendation.track.artist)
                .takeIf(String::isNotBlank)
                ?.let(recentArtists::add)
        }

        return selected
    }

    private fun artistKey(artist: String): String {
        return artist
            .split(',', '、', '/', '&')
            .firstOrNull()
            .orEmpty()
            .trim()
            .lowercase()
    }

    private fun stableTrackKey(track: Track): String {
        val title = track.title.lowercase().filter(Char::isLetterOrDigit)
        return "$title::${artistKey(track.artist)}"
    }
}
