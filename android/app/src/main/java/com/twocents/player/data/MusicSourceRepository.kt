package com.twocents.player.data

interface MusicSourceRepository {
    val source: TrackSource

    fun searchTracks(
        keyword: String,
        limit: Int = 20,
        offset: Int = 0,
    ): List<Track>

    fun findBestMatchTrack(
        title: String,
        artist: String = "",
    ): Track?

    fun fetchLyrics(track: Track): String?

    fun resolvePlayableTracks(tracks: List<Track>): List<Track>
}

data class MusicSearchPage(
    val tracks: List<Track>,
    val nextNeteaseOffset: Int,
    val nextKuwoOffset: Int,
    val canLoadMoreNetease: Boolean,
    val canLoadMoreKuwo: Boolean,
) {
    val canLoadMore: Boolean
        get() = canLoadMoreNetease || canLoadMoreKuwo
}
