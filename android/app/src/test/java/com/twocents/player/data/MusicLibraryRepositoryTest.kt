package com.twocents.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicLibraryRepositoryTest {
    @Test
    fun searchTracks_mergesSourcesAndDedupesSameSong() {
        val netease = FakeMusicSourceRepository(
            source = TrackSource.NETEASE,
            searchResults = listOf(
                track(source = TrackSource.NETEASE, sourceId = "1", title = "晴天", artist = "周杰伦"),
                track(source = TrackSource.NETEASE, sourceId = "2", title = "七里香", artist = "周杰伦"),
            ),
        )
        val kuwo = FakeMusicSourceRepository(
            source = TrackSource.KUWO,
            searchResults = listOf(
                track(source = TrackSource.KUWO, sourceId = "9", title = "晴天", artist = "周杰伦"),
                track(source = TrackSource.KUWO, sourceId = "10", title = "搁浅", artist = "周杰伦"),
            ),
        )
        val repository = MusicLibraryRepository(
            neteaseRepository = netease,
            kuwoRepository = kuwo,
        )

        val result = repository.searchTracks(
            keyword = "周杰伦",
            limitPerSource = 20,
            neteaseOffset = 0,
            kuwoOffset = 0,
        )

        assertEquals(
            listOf("netease:1", "netease:2", "kuwo:10"),
            result.tracks.map { it.id },
        )
    }

    @Test
    fun resolvePlayableTracks_routesBySourceAndPreservesOriginalOrder() {
        val netease = FakeMusicSourceRepository(
            source = TrackSource.NETEASE,
            resolveMap = mapOf("1" to "https://netease.example/1.mp3"),
        )
        val kuwo = FakeMusicSourceRepository(
            source = TrackSource.KUWO,
            resolveMap = mapOf("2" to "https://kuwo.example/2.mp3"),
        )
        val repository = MusicLibraryRepository(
            neteaseRepository = netease,
            kuwoRepository = kuwo,
        )

        val result = repository.resolvePlayableTracks(
            listOf(
                track(source = TrackSource.NETEASE, sourceId = "1"),
                track(source = TrackSource.KUWO, sourceId = "2"),
            ),
        )

        assertEquals(
            listOf("https://netease.example/1.mp3", "https://kuwo.example/2.mp3"),
            result.map { it.audioUrl },
        )
    }

    @Test
    fun fetchLyrics_fallsBackToAlternateSourceWhenPrimarySourceReturnsBlank() {
        val neteaseTrack = track(source = TrackSource.NETEASE, sourceId = "1", title = "晴天", artist = "周杰伦")
        val kuwoMatch = track(source = TrackSource.KUWO, sourceId = "9", title = "晴天", artist = "周杰伦")
        val netease = FakeMusicSourceRepository(
            source = TrackSource.NETEASE,
            lyricsById = mapOf("1" to null),
        )
        val kuwo = FakeMusicSourceRepository(
            source = TrackSource.KUWO,
            lyricsById = mapOf("9" to "[00:01.00]从前从前"),
            bestMatch = kuwoMatch,
        )
        val repository = MusicLibraryRepository(
            neteaseRepository = netease,
            kuwoRepository = kuwo,
        )

        val lyrics = repository.fetchLyrics(neteaseTrack)

        assertEquals("[00:01.00]从前从前", lyrics)
    }

    @Test
    fun fetchLyrics_returnsNullWhenAllSourcesMiss() {
        val neteaseTrack = track(source = TrackSource.NETEASE, sourceId = "1", title = "晴天", artist = "周杰伦")
        val repository = MusicLibraryRepository(
            neteaseRepository = FakeMusicSourceRepository(source = TrackSource.NETEASE),
            kuwoRepository = FakeMusicSourceRepository(source = TrackSource.KUWO),
        )

        val lyrics = repository.fetchLyrics(neteaseTrack)

        assertNull(lyrics)
    }

    private fun track(
        source: TrackSource,
        sourceId: String,
        title: String = "歌曲",
        artist: String = "歌手",
    ): Track {
        return Track(
            id = "${source.storageKey}:$sourceId",
            source = source,
            sourceId = sourceId,
            title = title,
            artist = artist,
        )
    }

    private class FakeMusicSourceRepository(
        override val source: TrackSource,
        private val searchResults: List<Track> = emptyList(),
        private val resolveMap: Map<String, String> = emptyMap(),
        private val lyricsById: Map<String, String?> = emptyMap(),
        private val bestMatch: Track? = null,
    ) : MusicSourceRepository {
        override fun searchTracks(
            keyword: String,
            limit: Int,
            offset: Int,
        ): List<Track> = searchResults

        override fun findBestMatchTrack(
            title: String,
            artist: String,
        ): Track? = bestMatch

        override fun fetchLyrics(track: Track): String? = lyricsById[track.sourceTrackId()]

        override fun resolvePlayableTracks(tracks: List<Track>): List<Track> {
            return tracks.map { track ->
                val audioUrl = resolveMap[track.sourceTrackId()].orEmpty()
                if (audioUrl.isBlank()) {
                    track
                } else {
                    track.copy(audioUrl = audioUrl)
                }
            }
        }
    }
}
