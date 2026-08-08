package com.twocents.player.data

class RadioCandidateScorer {
    fun score(
        candidates: List<RadioResolvedCandidate>,
        request: RadioRecommendationRequest,
    ): List<RadioResolvedCandidate> {
        val excludedTrackIds = request.negativeTrackIds + request.avoidTrackIds
        val seedRanks = request.favoriteSeeds
            .mapIndexed { index, track -> track.id to index }
            .toMap()

        return candidates.asSequence()
            .filterNot { it.recommendation.track.id in excludedTrackIds }
            .groupBy { candidate ->
                candidate.stableKey.ifBlank { stableTrackKey(candidate.recommendation.track) }
            }
            .map { (stableKey, duplicates) ->
                val first = duplicates.first()
                val matchedSeedIds = duplicates.flatMapTo(linkedSetOf()) { it.matchedSeedIds }
                val retrievalSources = duplicates.flatMapTo(linkedSetOf()) { it.retrievalSources }
                val sourceRank = duplicates.minOf(RadioResolvedCandidate::sourceRank)
                val track = first.recommendation.track
                val artist = artistKey(track.artist)
                val bestSeedRank = matchedSeedIds.mapNotNull(seedRanks::get).minOrNull()
                val seedScore = when (bestSeedRank) {
                    0 -> 40
                    1 -> 30
                    2 -> 20
                    else -> 0
                }
                val multiSeedScore = (matchedSeedIds.size - 1).coerceAtLeast(0) * 15
                val positiveArtistScore = if (artist in request.positiveArtistKeys) 12 else 0
                val extraSourceScore = (retrievalSources.size - 1).coerceAtLeast(0) * 10
                val metadataScore = if (
                    track.sourceTrackId().isNotBlank() &&
                    track.title.isNotBlank() &&
                    track.artist.isNotBlank()
                ) {
                    5
                } else {
                    0
                }
                val negativeArtistPenalty = if (artist in request.negativeArtistKeys) 25 else 0
                val recentArtistPenalty = if (artist in request.avoidArtistKeys) 20 else 0

                first.copy(
                    matchedSeedIds = matchedSeedIds,
                    retrievalSources = retrievalSources,
                    sourceRank = sourceRank,
                    score = seedScore +
                        multiSeedScore +
                        positiveArtistScore +
                        extraSourceScore +
                        metadataScore -
                        negativeArtistPenalty -
                        recentArtistPenalty,
                    stableKey = stableKey,
                )
            }
            .sortedWith(
                compareByDescending<RadioResolvedCandidate>(RadioResolvedCandidate::score)
                    .thenBy(RadioResolvedCandidate::sourceRank)
                    .thenBy(RadioResolvedCandidate::stableKey),
            )
    }

    private fun stableTrackKey(track: Track): String {
        val title = track.title.lowercase().filter(Char::isLetterOrDigit)
        return "$title::${artistKey(track.artist)}"
    }

    private fun artistKey(artist: String): String {
        return artist
            .split(',', '、', '/', '&')
            .firstOrNull()
            .orEmpty()
            .trim()
            .lowercase()
    }
}
