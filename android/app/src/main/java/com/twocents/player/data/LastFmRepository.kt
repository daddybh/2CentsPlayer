package com.twocents.player.data

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class LastFmRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build(),
) : RadioCandidateSource {

    fun getSimilarTracks(
        trackTitle: String,
        artist: String,
        apiKey: String,
        limit: Int = 20,
    ): List<AiSuggestedTrack> {
        if (apiKey.isBlank()) return emptyList()
        if (trackTitle.isBlank()) return emptyList()

        val url = BASE_URL.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("method", "track.getSimilar")
            ?.addQueryParameter("track", trackTitle.trim())
            ?.addQueryParameter("artist", artist.trim())
            ?.addQueryParameter("api_key", apiKey.trim())
            ?.addQueryParameter("limit", limit.toString())
            ?.addQueryParameter("autocorrect", "1")
            ?.addQueryParameter("format", "json")
            ?.build()
            ?: return emptyList()

        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()

            val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
            return parseSimilarTracks(root)
        }
    }

    override fun requestRadioCandidates(
        settings: AiServiceConfig,
        request: RadioRecommendationRequest,
    ): List<AiSuggestedTrack> {
        val apiKey = settings.lastFmApiKey
        if (apiKey.isBlank()) return emptyList()

        val seeds = request.favoriteSeeds
        if (seeds.isEmpty()) return emptyList()

        val avoidTrackKeys = request.avoidTrackIds
        val avoidArtistKeys = request.avoidArtistKeys

        val shuffledSeeds = seeds.shuffled()
        val allSuggestions = mutableListOf<AiSuggestedTrack>()
        val seenKeys = mutableSetOf<String>()
        var seedsQueried = 0
        val totalStart = System.currentTimeMillis()
        Log.d(TAG, "Last.fm requestRadioCandidates: seeds=${seeds.size}, limit=${request.rawCandidateLimit}")

        for (seed in shuffledSeeds) {
            if (allSuggestions.size >= request.rawCandidateLimit) break
            if (seedsQueried >= MAX_SEED_QUERIES) break

            val seedStart = System.currentTimeMillis()
            val similarTracks = runCatching {
                getSimilarTracks(
                    trackTitle = seed.title,
                    artist = seed.artist,
                    apiKey = apiKey,
                    limit = request.rawCandidateLimit,
                )
            }.getOrDefault(emptyList())
            val seedMs = System.currentTimeMillis() - seedStart
            Log.d(TAG, "  seed #$seedsQueried '${seed.title} - ${seed.artist}' → ${similarTracks.size} 条 (${seedMs}ms)")

            seedsQueried++

            for (suggestion in similarTracks) {
                if (allSuggestions.size >= request.rawCandidateLimit) break

                val dedupeKey = "${suggestion.title.lowercase().trim()}::${suggestion.artist.lowercase().trim()}"
                if (!seenKeys.add(dedupeKey)) continue

                val artistKey = suggestion.artist
                    .split(',', '、', '/', '&')
                    .firstOrNull()
                    .orEmpty()
                    .trim()
                    .lowercase()
                if (artistKey in avoidArtistKeys) continue

                allSuggestions.add(suggestion)
            }
        }

        val totalMs = System.currentTimeMillis() - totalStart
        Log.d(TAG, "Last.fm 总计: ${allSuggestions.size} 条候选, ${seedsQueried} 次查询, ${totalMs}ms")
        return allSuggestions
    }

    private fun parseSimilarTracks(root: JSONObject): List<AiSuggestedTrack> {
        val similarTracks = root.optJSONObject("similartracks")
            ?.optJSONArray("track")
            ?: return emptyList()

        val seenKeys = mutableSetOf<String>()
        return buildList(similarTracks.length()) {
            for (index in 0 until similarTracks.length()) {
                val trackJson = similarTracks.optJSONObject(index) ?: continue
                val name = trackJson.optString("name").trim()
                val artistJson = trackJson.optJSONObject("artist")
                val artistName = artistJson?.optString("name").orEmpty().trim()
                val matchScore = trackJson.optDouble("match", 0.0)

                if (name.isBlank() || artistName.isBlank()) continue

                val dedupeKey = "${name.lowercase()}::${artistName.lowercase()}"
                if (!seenKeys.add(dedupeKey)) continue

                val bucket = when {
                    matchScore > 0.5 -> RadioCandidateBucket.SAFE
                    matchScore > 0.2 -> RadioCandidateBucket.ADJACENT
                    else -> RadioCandidateBucket.SURPRISE
                }

                add(
                    AiSuggestedTrack(
                        title = name,
                        artist = artistName,
                        reason = "与你收藏的歌曲风格相似",
                        bucket = bucket,
                    ),
                )
            }
        }
    }

    private companion object {
        private const val TAG = "RadioEngine"
        const val BASE_URL = "https://ws.audioscrobbler.com/2.0/"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        const val MAX_SEED_QUERIES = 3
    }
}
