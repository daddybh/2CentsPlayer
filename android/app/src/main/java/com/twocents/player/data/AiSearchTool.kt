package com.twocents.player.data

data class SearchToolResult(
    val candidates: List<SearchToolCandidate>,
    val trackByCandidateId: Map<String, Track>,
)

class AiSearchTool(
    private val executeSearch: suspend (query: String, limit: Int) -> MusicSearchPage,
) {
    suspend fun search(
        query: String,
        limit: Int,
    ): SearchToolResult {
        val page = executeSearch(query, limit)
        val limitedTracks = page.tracks.take(limit)
        val candidates = limitedTracks.mapIndexed { index, track ->
            SearchToolCandidate(
                candidateId = "cand_${index + 1}",
                title = track.title,
                artist = track.artist,
                album = track.album,
                source = track.source.storageKey,
                durationMs = track.durationMs,
            )
        }
        val trackByCandidateId = candidates.zip(limitedTracks).associate { (candidate, track) ->
            candidate.candidateId to track
        }
        return SearchToolResult(
            candidates = candidates,
            trackByCandidateId = trackByCandidateId,
        )
    }
}
