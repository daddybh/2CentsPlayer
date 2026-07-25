package com.twocents.player.data

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NeteaseSimiRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val endpoint: String = SIMI_SONG_URL,
    private val logger: RadioDiagnosticLogger = NoOpRadioDiagnosticLogger,
) : RadioCandidateSource {

    fun getSimiSongs(trackId: String, limit: Int = 20): List<AiSuggestedTrack> {
        if (trackId.isBlank()) return emptyList()

        val requestBody = FormBody.Builder()
            .add("songid", trackId)
            .add("offset", "0")
            .add("limit", limit.toString())
            .build()

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Referer", "https://music.163.com/")
            .addHeader("Origin", "https://music.163.com")
            .addHeader("Cookie", COOKIE)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()

            val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
            if (root.optInt("code") != 200) return emptyList()

            val songs = root.optJSONArray("songs") ?: return emptyList()

            return buildList(songs.length().coerceAtMost(limit)) {
                for (index in 0 until songs.length()) {
                    if (size >= limit) break
                    val song = songs.optJSONObject(index) ?: continue

                    val songId = song.opt("id")?.toString().orEmpty()
                    val name = song.optString("name").trim()
                    if (songId.isBlank() || name.isBlank()) continue

                    val artists = buildList {
                        val artistsJson = song.optJSONArray("artists")
                            ?: song.optJSONArray("ar")
                        if (artistsJson != null) {
                            for (i in 0 until artistsJson.length()) {
                                val artistName = artistsJson.optJSONObject(i)
                                    ?.optString("name").orEmpty().trim()
                                if (artistName.isNotBlank()) add(artistName)
                            }
                        }
                    }
                    val artistStr = artists.joinToString(", ").ifBlank { "Unknown" }

                    val albumJson = song.optJSONObject("album")
                        ?: song.optJSONObject("al")
                    val albumName = albumJson?.optString("name").orEmpty()
                    val coverUrl = albumJson?.optString("picUrl").orEmpty()
                        .replace("http://", "https://")

                    val durationMs = song.optLong("duration", 0L)
                        .takeIf { it > 0 }
                        ?: song.optLong("dt", 0L)

                    val resolvedTrack = Track(
                        id = "${TrackSource.NETEASE.storageKey}:$songId",
                        source = TrackSource.NETEASE,
                        sourceId = songId,
                        title = name,
                        artist = artistStr,
                        album = albumName,
                        durationMs = durationMs,
                        coverUrl = coverUrl,
                    ).withCanonicalIdentity()

                    add(
                        AiSuggestedTrack(
                            title = name,
                            artist = artistStr,
                            reason = "Similar to your favorites",
                            bucket = RadioCandidateBucket.SAFE,
                            resolvedTrack = resolvedTrack,
                        ),
                    )
                }
            }
        }
    }

    override fun requestRadioCandidates(
        settings: AiServiceConfig,
        request: RadioRecommendationRequest,
    ): List<AiSuggestedTrack> {
        val seeds = request.favoriteSeeds.mapNotNull { seed ->
            seed.neteaseTrackId()?.let { trackId -> seed to trackId }
        }.take(MAX_SEED_QUERIES)
        if (seeds.isEmpty()) return emptyList()

        val avoidTrackIds = request.avoidTrackIds
        val avoidArtistKeys = request.avoidArtistKeys

        val suggestionsByKey = linkedMapOf<String, AiSuggestedTrack>()
        var seedsQueried = 0
        var rawCount = 0
        var duplicateInQueueCount = 0
        var recentArtistCount = 0
        var mergedDuplicateCount = 0
        val perSeedLimit = ((request.rawCandidateLimit + seeds.size - 1) / seeds.size)
            .coerceAtLeast(1)
        val totalStart = System.currentTimeMillis()
        logger.debug("Netease simi: seeds=${seeds.size}, limit=${request.rawCandidateLimit}")

        for ((seed, neteaseTrackId) in seeds) {
            val seedStart = System.currentTimeMillis()
            val similarTracks = runCatching {
                getSimiSongs(
                    trackId = neteaseTrackId,
                    limit = perSeedLimit,
                )
            }.getOrDefault(emptyList())
            val seedMs = System.currentTimeMillis() - seedStart
            seedsQueried++
            rawCount += similarTracks.size
            val acceptedBeforeSeed = suggestionsByKey.size
            var seedDuplicateInQueueCount = 0
            var seedRecentArtistCount = 0
            var seedMergedDuplicateCount = 0

            for (suggestion in similarTracks) {
                val dedupeKey = "${suggestion.title.lowercase().trim()}::${suggestion.artist.lowercase().trim()}"

                val trackId = suggestion.resolvedTrack?.id.orEmpty()
                if (trackId in avoidTrackIds) {
                    duplicateInQueueCount++
                    seedDuplicateInQueueCount++
                    continue
                }

                val artistKey = suggestion.artist
                    .split(',', '/', '&')
                    .firstOrNull()
                    .orEmpty()
                    .trim()
                    .lowercase()
                if (artistKey in avoidArtistKeys) {
                    recentArtistCount++
                    seedRecentArtistCount++
                    continue
                }

                val enriched = suggestion.copy(
                    reason = "因为你喜欢「${seed.title}」",
                    matchedSeedIds = suggestion.matchedSeedIds + seed.id,
                    retrievalSources = suggestion.retrievalSources + SOURCE_KEY,
                )
                val existing = suggestionsByKey[dedupeKey]
                suggestionsByKey[dedupeKey] = if (existing == null) {
                    enriched
                } else {
                    mergedDuplicateCount++
                    seedMergedDuplicateCount++
                    existing.copy(
                        matchedSeedIds = existing.matchedSeedIds + enriched.matchedSeedIds,
                        retrievalSources = existing.retrievalSources + enriched.retrievalSources,
                    )
                }
            }

            logger.debug(
                "  seed #${seedsQueried - 1} '${seed.title} - ${seed.artist}' " +
                    "(id=$neteaseTrackId): raw=${similarTracks.size}, " +
                    "new=${suggestionsByKey.size - acceptedBeforeSeed}, " +
                    "duplicate_in_queue=$seedDuplicateInQueueCount, " +
                    "recent_artist=$seedRecentArtistCount, " +
                    "merged_duplicate=$seedMergedDuplicateCount (${seedMs}ms)",
            )
        }

        val totalMs = System.currentTimeMillis() - totalStart
        val allSuggestions = suggestionsByKey.values.take(request.rawCandidateLimit)
        val yieldPercent = if (rawCount == 0) 0 else allSuggestions.size * 100 / rawCount
        logger.debug(
            "Netease simi total: raw=$rawCount, duplicate_in_queue=$duplicateInQueueCount, " +
                "recent_artist=$recentArtistCount, merged_duplicate=$mergedDuplicateCount, " +
                "accepted=${allSuggestions.size}, yield=$yieldPercent%, " +
                "queries=$seedsQueried, ${totalMs}ms",
        )
        return allSuggestions
    }

    private fun Track.neteaseTrackId(): String? {
        return when {
            source == TrackSource.NETEASE && sourceId.isNotBlank() -> sourceId
            id.startsWith("${TrackSource.NETEASE.storageKey}:") ->
                id.removePrefix("${TrackSource.NETEASE.storageKey}:").takeIf(String::isNotBlank)
            else -> null
        }
    }

    private companion object {
        const val SIMI_SONG_URL = "https://music.163.com/api/v1/discovery/simiSong"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        const val COOKIE = "os=pc; appver=2.7.1.198277;"
        const val MAX_SEED_QUERIES = 3
        const val SOURCE_KEY = "netease-simi"
    }
}
