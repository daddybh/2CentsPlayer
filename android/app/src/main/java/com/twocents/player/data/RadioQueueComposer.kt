package com.twocents.player.data

data class RadioResolvedCandidate(
    val recommendation: AiRecommendedTrack,
    val bucket: RadioCandidateBucket,
)

class RadioQueueComposer {
    fun compose(
        existingQueue: List<AiRecommendedTrack>,
        candidates: List<RadioResolvedCandidate>,
        boundaryState: RadioBoundaryState,
    ): List<RadioResolvedCandidate> {
        val existingTrackIds = existingQueue.map { it.track.id }.toSet()
        val usedArtistKeys = existingQueue
            .map { artistKey(it.track.artist) }
            .filter { it.isNotBlank() }
            .toMutableSet()

        val remaining = candidates.filter { candidate ->
            candidate.recommendation.track.audioUrl.isNotBlank() &&
                candidate.recommendation.track.id !in existingTrackIds
        }.toMutableList()

        val orderedBuckets = when (boundaryState) {
            RadioBoundaryState.BALANCED -> listOf(
                RadioCandidateBucket.SAFE,
                RadioCandidateBucket.ADJACENT,
                RadioCandidateBucket.SAFE,
                RadioCandidateBucket.SURPRISE,
                RadioCandidateBucket.SAFE,
                RadioCandidateBucket.ADJACENT,
            )

            RadioBoundaryState.EXPANDING -> listOf(
                RadioCandidateBucket.SAFE,
                RadioCandidateBucket.ADJACENT,
                RadioCandidateBucket.SURPRISE,
                RadioCandidateBucket.ADJACENT,
                RadioCandidateBucket.SAFE,
                RadioCandidateBucket.SAFE,
            )

            RadioBoundaryState.RECOVERING -> listOf(
                RadioCandidateBucket.SAFE,
                RadioCandidateBucket.SAFE,
                RadioCandidateBucket.ADJACENT,
                RadioCandidateBucket.SAFE,
            )
        }

        val resolved = mutableListOf<RadioResolvedCandidate>()
        orderedBuckets.forEach { bucket ->
            val selectedIndex = remaining.indexOfFirst { candidate ->
                candidate.bucket == bucket &&
                    artistKey(candidate.recommendation.track.artist).let { key ->
                        key.isBlank() || key !in usedArtistKeys
                    }
            }
            if (selectedIndex < 0) return@forEach

            val selected = remaining.removeAt(selectedIndex)
            val key = artistKey(selected.recommendation.track.artist)
            if (key.isNotBlank()) {
                usedArtistKeys += key
            }
            resolved += selected
        }

        return resolved
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
