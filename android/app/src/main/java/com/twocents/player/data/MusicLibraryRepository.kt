package com.twocents.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class MusicLibraryRepository(
    private val neteaseRepository: MusicSourceRepository = NeteaseSearchRepository(),
    private val kuwoRepository: MusicSourceRepository = KuwoSearchRepository(),
) : RadioTrackLookup {
    suspend fun searchTracks(
        keyword: String,
        limitPerSource: Int = 20,
        neteaseOffset: Int = 0,
        kuwoOffset: Int = 0,
    ): MusicSearchPage = coroutineScope {
        val neteaseDeferred = async(Dispatchers.IO) {
            neteaseRepository.searchTracks(
                keyword = keyword,
                limit = limitPerSource,
                offset = neteaseOffset,
            ).map(Track::withCanonicalIdentity)
        }
        val kuwoDeferred = async(Dispatchers.IO) {
            kuwoRepository.searchTracks(
                keyword = keyword,
                limit = limitPerSource,
                offset = kuwoOffset,
            ).map(Track::withCanonicalIdentity)
        }

        val neteaseTracks = neteaseDeferred.await()
        val kuwoTracks = kuwoDeferred.await()

        MusicSearchPage(
            tracks = mergeSearchTracks(
                primary = neteaseTracks,
                secondary = kuwoTracks,
            ),
            nextNeteaseOffset = neteaseOffset + neteaseTracks.size,
            nextKuwoOffset = kuwoOffset + kuwoTracks.size,
            canLoadMoreNetease = neteaseTracks.size >= limitPerSource,
            canLoadMoreKuwo = kuwoTracks.size >= limitPerSource,
        )
    }

    override suspend fun findBestMatchTrack(
        title: String,
        artist: String,
    ): Track? = coroutineScope {
        val neteaseDeferred = async(Dispatchers.IO) {
            runCatching { neteaseRepository.findBestMatchTrack(title, artist) }.getOrNull()
        }
        val kuwoDeferred = async(Dispatchers.IO) {
            runCatching { kuwoRepository.findBestMatchTrack(title, artist) }.getOrNull()
        }

        val candidates = listOfNotNull(
            neteaseDeferred.await(),
            kuwoDeferred.await(),
        ).map(Track::withCanonicalIdentity)

        if (candidates.isEmpty()) return@coroutineScope null

        candidates.maxByOrNull { candidate ->
            scoreTrackMatch(
                track = candidate,
                title = title,
                artist = artist,
            )
        }
    }

    fun fetchLyrics(track: Track): String? {
        val normalizedTrack = track.withCanonicalIdentity()
        fetchLyricsFromPreferredSource(normalizedTrack)?.let { return it }

        repositoryForAlternateSources(normalizedTrack.source).forEach { repository ->
            val alternateTrack = repository.findBestMatchTrack(
                title = normalizedTrack.title,
                artist = normalizedTrack.artist,
            ) ?: return@forEach
            repository.fetchLyrics(alternateTrack.withCanonicalIdentity())
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        return null
    }

    override suspend fun resolvePlayableTracks(tracks: List<Track>): List<Track> {
        if (tracks.isEmpty()) return emptyList()

        val normalizedTracks = tracks.map(Track::withCanonicalIdentity)
        val resolvedById = coroutineScope {
            val deferredResults = TrackSource.entries.mapNotNull { source ->
                val sourceTracks = normalizedTracks.filter { it.source == source }
                if (sourceTracks.isEmpty()) return@mapNotNull null

                async(Dispatchers.IO) {
                    repositoryFor(source)
                        .resolvePlayableTracks(sourceTracks)
                        .map(Track::withCanonicalIdentity)
                }
            }

            buildMap(normalizedTracks.size) {
                deferredResults.awaitAll().flatten().forEach { resolvedTrack ->
                    put(resolvedTrack.id, resolvedTrack)
                }
            }
        }

        return normalizedTracks.map { track -> resolvedById[track.id] ?: track }
    }

    private fun fetchLyricsFromPreferredSource(track: Track): String? {
        return repositoryFor(track.source)
            .fetchLyrics(track)
            ?.takeIf { it.isNotBlank() }
    }

    private fun repositoryFor(source: TrackSource): MusicSourceRepository {
        return when (source) {
            TrackSource.NETEASE -> neteaseRepository
            TrackSource.KUWO -> kuwoRepository
        }
    }

    private fun repositoryForAlternateSources(source: TrackSource): List<MusicSourceRepository> {
        return TrackSource.entries
            .filterNot { it == source }
            .map(::repositoryFor)
    }

    private fun mergeSearchTracks(
        primary: List<Track>,
        secondary: List<Track>,
    ): List<Track> {
        val merged = LinkedHashMap<String, Track>()

        (primary + secondary).forEach { track ->
            val dedupeKey = track.searchDedupeKey()
            val existingTrack = merged[dedupeKey]
            if (existingTrack == null || shouldPreferTrack(candidate = track, current = existingTrack)) {
                merged[dedupeKey] = track
            }
        }

        return merged.values.toList()
    }

    private fun shouldPreferTrack(
        candidate: Track,
        current: Track,
    ): Boolean {
        if (candidate.source == TrackSource.NETEASE && current.source != TrackSource.NETEASE) {
            return true
        }
        if (candidate.source != TrackSource.NETEASE && current.source == TrackSource.NETEASE) {
            return false
        }
        return metadataCompletenessScore(candidate) > metadataCompletenessScore(current)
    }

    private fun metadataCompletenessScore(track: Track): Int {
        var score = 0
        if (track.title.isNotBlank()) score += 4
        if (track.artist.isNotBlank()) score += 4
        if (track.album.isNotBlank()) score += 2
        if (track.coverUrl.isNotBlank()) score += 2
        if (track.durationMs > 0) score += 2
        return score
    }

    private fun Track.searchDedupeKey(): String {
        return "${title.normalizedForMatch()}::${artist.primaryArtist().normalizedForMatch()}"
    }

    private fun scoreTrackMatch(
        track: Track,
        title: String,
        artist: String,
    ): Int {
        val expectedTitle = title.normalizedForMatch()
        val expectedArtist = artist.normalizedForMatch()
        val actualTitle = track.title.normalizedForMatch()
        val actualArtist = track.artist.normalizedForMatch()

        var score = 0

        when {
            actualTitle == expectedTitle -> score += 120
            actualTitle.contains(expectedTitle) || expectedTitle.contains(actualTitle) -> score += 75
        }

        if (expectedArtist.isNotBlank()) {
            when {
                actualArtist == expectedArtist -> score += 85
                actualArtist.contains(expectedArtist) || expectedArtist.contains(actualArtist) -> score += 50
            }
        } else {
            score += 5
        }

        if (track.durationMs > 60_000L) score += 10
        if (track.coverUrl.isNotBlank()) score += 5

        return score
    }

    private fun String.primaryArtist(): String {
        return split(',', '、', '/', '&')
            .map(String::trim)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun String.normalizedForMatch(): String {
        return lowercase().filter { it.isLetterOrDigit() }
    }
}
