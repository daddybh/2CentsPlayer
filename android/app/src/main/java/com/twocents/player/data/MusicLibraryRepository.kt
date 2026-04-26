package com.twocents.player.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class MusicLibraryRepository(
    private val neteaseRepository: MusicSourceRepository = NeteaseSearchRepository(),
    private val kuwoRepository: MusicSourceRepository = KuwoSearchRepository(),
) : RadioTrackLookup {

    private companion object {
        const val TAG = "RadioEngine"
    }

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

    suspend fun resolveTrackMetadata(track: Track): Track = coroutineScope {
        val normalizedTrack = track.withCanonicalIdentity()
        if (!needsMetadataFallback(normalizedTrack)) return@coroutineScope normalizedTrack

        val preferredDeferred = async(Dispatchers.IO) {
            resolveMetadataMatch(repositoryFor(normalizedTrack.source), normalizedTrack)
        }
        val alternateDeferreds = repositoryForAlternateSources(normalizedTrack.source).map { repository ->
            async(Dispatchers.IO) {
                resolveMetadataMatch(repository, normalizedTrack)
            }
        }

        var bestTrack = normalizedTrack
        listOfNotNull(preferredDeferred.await(), *alternateDeferreds.awaitAll().filterNotNull().toTypedArray())
            .forEach { candidate ->
                val mergedTrack = mergeTrackMetadata(
                    originalTrack = normalizedTrack,
                    resolvedTrack = candidate,
                )
                if (metadataCompletenessScore(mergedTrack) > metadataCompletenessScore(bestTrack)) {
                    bestTrack = mergedTrack
                }
            }

        bestTrack
    }

    override suspend fun resolvePlayableTracks(tracks: List<Track>): List<Track> {
        if (tracks.isEmpty()) return emptyList()
        val resolveStart = System.currentTimeMillis()
        val sourceCounts = tracks.groupBy { it.source }.mapValues { it.value.size }
        Log.d(TAG, "resolvePlayable: ${tracks.size} 首, 来源分布=$sourceCounts")

        val normalizedTracks = tracks.map(Track::withCanonicalIdentity)
        val primaryStart = System.currentTimeMillis()
        val resolvedById = coroutineScope {
            val deferredResults = TrackSource.entries.mapNotNull { source ->
                val sourceTracks = normalizedTracks.filter { it.source == source }
                if (sourceTracks.isEmpty()) return@mapNotNull null

                async(Dispatchers.IO) {
                    val srcStart = System.currentTimeMillis()
                    val result = repositoryFor(source)
                        .resolvePlayableTracks(sourceTracks)
                        .map(Track::withCanonicalIdentity)
                    val srcMs = System.currentTimeMillis() - srcStart
                    Log.d(TAG, "  主源 ${source.name}: ${sourceTracks.size} 首 → ${result.count { it.audioUrl.isNotBlank() }} 有URL (${srcMs}ms)")
                    result
                }
            }

            buildMap(normalizedTracks.size) {
                deferredResults.awaitAll().flatten().forEach { resolvedTrack ->
                    put(resolvedTrack.id, resolvedTrack)
                }
            }
        }
        val primaryMs = System.currentTimeMillis() - primaryStart
        val primaryResolved = normalizedTracks.count { resolvedById[it.id]?.audioUrl?.isNotBlank() == true }
        Log.d(TAG, "  主源解析完毕: $primaryResolved/${tracks.size} 有URL (${primaryMs}ms)")

        val fallbackStart = System.currentTimeMillis()
        val fallbackNeeded = normalizedTracks.count { track ->
            val r = resolvedById[track.id]
            r?.audioUrl.isNullOrBlank() && track.audioUrl.isBlank()
        }
        val fallbackResolvedById = coroutineScope {
            normalizedTracks.mapNotNull { track ->
                val resolvedTrack = resolvedById[track.id]
                if (resolvedTrack?.audioUrl.isNullOrBlank() && track.audioUrl.isBlank()) {
                    async(Dispatchers.IO) {
                        resolvePlayableTrackFromAlternateSources(track)
                    }
                } else {
                    null
                }
            }.awaitAll().filterNotNull().associateBy { it.id }
        }
        val fallbackMs = System.currentTimeMillis() - fallbackStart
        Log.d(TAG, "  备源解析: 需要 $fallbackNeeded 首, 成功 ${fallbackResolvedById.size} 首 (${fallbackMs}ms)")

        val totalMs = System.currentTimeMillis() - resolveStart
        Log.d(TAG, "  resolvePlayable 总计: ${totalMs}ms")

        return normalizedTracks.map { track ->
            val resolvedTrack = resolvedById[track.id]
            when {
                !resolvedTrack?.audioUrl.isNullOrBlank() -> resolvedTrack
                !fallbackResolvedById[track.id]?.audioUrl.isNullOrBlank() -> fallbackResolvedById.getValue(track.id)
                else -> resolvedTrack ?: track
            }
        }
    }

    private fun resolvePlayableTrackFromAlternateSources(track: Track): Track? {
        repositoryForAlternateSources(track.source).forEach { repository ->
            val matchedTrack = runCatching {
                repository.findBestMatchTrack(
                    title = track.title,
                    artist = track.artist,
                )
            }.getOrNull() ?: return@forEach

            val resolvedTrack = runCatching {
                repository.resolvePlayableTracks(listOf(matchedTrack))
                    .firstOrNull()
                    ?.withCanonicalIdentity()
            }.getOrNull() ?: return@forEach

            if (resolvedTrack.audioUrl.isBlank()) return@forEach

            return mergePlayableFallback(
                originalTrack = track,
                resolvedTrack = resolvedTrack,
            )
        }

        return null
    }

    private fun resolveMetadataMatch(
        repository: MusicSourceRepository,
        track: Track,
    ): Track? {
        return runCatching {
            repository.findBestMatchTrack(
                title = track.title,
                artist = track.artist,
            )?.withCanonicalIdentity()
        }.getOrNull()
    }

    private fun needsMetadataFallback(track: Track): Boolean {
        return track.coverUrl.isBlank() || track.album.isBlank() || track.durationMs <= 0L
    }

    private fun mergePlayableFallback(
        originalTrack: Track,
        resolvedTrack: Track,
    ): Track {
        return mergeTrackMetadata(
            originalTrack = originalTrack,
            resolvedTrack = resolvedTrack,
        ).copy(
            audioUrl = resolvedTrack.audioUrl,
        )
    }

    private fun mergeTrackMetadata(
        originalTrack: Track,
        resolvedTrack: Track,
    ): Track {
        return originalTrack.withCanonicalIdentity().copy(
            durationMs = resolvedTrack.durationMs.takeIf { it > 0L } ?: originalTrack.durationMs,
            album = originalTrack.album.ifBlank { resolvedTrack.album },
            coverUrl = originalTrack.coverUrl.ifBlank { resolvedTrack.coverUrl },
        )
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
