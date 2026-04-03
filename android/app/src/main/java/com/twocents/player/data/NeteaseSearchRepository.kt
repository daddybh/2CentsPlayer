package com.twocents.player.data

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max

class NeteaseSearchRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
) : MusicSourceRepository {
    override val source: TrackSource = TrackSource.NETEASE

    data class PlaybackDetails(
        val audioUrl: String,
        val durationMs: Long,
        val isPreviewOnly: Boolean,
    )

    override fun searchTracks(
        keyword: String,
        limit: Int,
        offset: Int,
    ): List<Track> {
        val trimmedKeyword = keyword.trim()
        if (trimmedKeyword.isBlank()) return emptyList()

        val requestBody = FormBody.Builder()
            .add("s", trimmedKeyword)
            .add("type", "1")
            .add("limit", limit.toString())
            .add("offset", offset.toString())
            .build()

        val request = Request.Builder()
            .url("https://music.163.com/api/cloudsearch/pc")
            .addHeader("User-Agent", NETEASE_WEB_USER_AGENT)
            .addHeader("Referer", "https://music.163.com/")
            .addHeader("Origin", "https://music.163.com")
            .addHeader("Cookie", NETEASE_WEB_COOKIE)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("网易云搜索请求失败: HTTP ${response.code}")
            }

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                throw IOException("网易云搜索返回了空响应")
            }

            val root = JSONObject(body)
            if (root.optInt("code") != 200) {
                throw IOException("网易云搜索返回异常状态: ${root.optInt("code")}")
            }

            val result = root.optJSONObject("result") ?: return emptyList()
            val songs = result.optJSONArray("songs") ?: return emptyList()

            return buildList(songs.length()) {
                for (index in 0 until songs.length()) {
                    val song = songs.optJSONObject(index) ?: continue
                    add(song.toTrack())
                }
            }
        }
    }

    override fun findBestMatchTrack(
        title: String,
        artist: String,
    ): Track? {
        val trimmedTitle = title.trim()
        val trimmedArtist = artist.trim()
        if (trimmedTitle.isBlank()) return null

        val query = listOf(trimmedTitle, trimmedArtist)
            .filter { it.isNotBlank() }
            .joinToString(separator = " ")

        val candidates = buildList {
            addAll(searchTracks(query, limit = MATCH_LIMIT))
            if (trimmedArtist.isNotBlank()) {
                addAll(searchTracks(trimmedTitle, limit = MATCH_LIMIT))
            }
        }.distinctBy { it.id }

        if (candidates.isEmpty()) return null

        return candidates
            .map { candidate ->
                candidate to scoreTrackMatch(
                    track = candidate,
                    title = trimmedTitle,
                    artist = trimmedArtist,
                )
            }
            .maxByOrNull { it.second }
            ?.takeIf { it.second > 0 }
            ?.first
    }

    override fun fetchLyrics(track: Track): String? {
        return fetchLyrics(track.sourceTrackId())
    }

    fun fetchLyrics(trackId: String): String? {
        if (trackId.isBlank()) return null

        val request = Request.Builder()
            .url("https://music.163.com/api/song/lyric?id=$trackId&lv=1&kv=1&tv=-1")
            .addHeader("User-Agent", NETEASE_WEB_USER_AGENT)
            .addHeader("Referer", "https://music.163.com/")
            .addHeader("Origin", "https://music.163.com")
            .addHeader("Cookie", NETEASE_WEB_COOKIE)
            .get()
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("网易云歌词请求失败: HTTP ${response.code}")
            }

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@use null

            val root = runCatching { JSONObject(body) }.getOrNull() ?: return@use null
            root.optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
        }
    }

    private fun JSONObject.toTrack(): Track {
        val artistsJson = optJSONArray("ar")
        val artists = buildList {
            if (artistsJson != null) {
                for (index in 0 until artistsJson.length()) {
                    val artist = artistsJson.optJSONObject(index)?.optString("name").orEmpty()
                    if (artist.isNotBlank()) add(artist)
                }
            }
        }

        val albumJson = optJSONObject("al")
        return Track(
            id = "${TrackSource.NETEASE.storageKey}:${opt("id")?.toString().orEmpty()}",
            source = TrackSource.NETEASE,
            sourceId = opt("id")?.toString().orEmpty(),
            title = optString("name"),
            artist = artists.joinToString(", ").ifBlank { "未知艺术家" },
            album = albumJson?.optString("name").orEmpty(),
            durationMs = optLong("dt"),
            coverUrl = albumJson?.optString("picUrl").orEmpty().replace("http://", "https://"),
        ).withCanonicalIdentity()
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

    private fun String.normalizedForMatch(): String {
        return lowercase().filter { it.isLetterOrDigit() }
    }

    fun resolvePlayableTrack(track: Track): Track {
        if (track.sourceTrackId().isBlank()) return track.withCanonicalIdentity()
        val playbackDetails = resolvePlaybackDetails(track.sourceTrackId()) ?: return track.withCanonicalIdentity()
        return track.withCanonicalIdentity().copy(
            audioUrl = playbackDetails.audioUrl,
            durationMs = playbackDetails.durationMs.takeIf { it > 0 } ?: track.durationMs,
        )
    }

    override fun resolvePlayableTracks(tracks: List<Track>): List<Track> {
        if (tracks.isEmpty()) return emptyList()

        val trackIds = tracks.map { it.sourceTrackId() }.filter { it.isNotBlank() }
        if (trackIds.isEmpty()) return tracks

        val playbackDetailsById = resolvePlaybackDetails(trackIds)
        return tracks.map { track ->
            val playbackDetails = playbackDetailsById[track.sourceTrackId()]
            if (playbackDetails == null) {
                track.withCanonicalIdentity()
            } else {
                track.withCanonicalIdentity().copy(
                    audioUrl = playbackDetails.audioUrl,
                    durationMs = playbackDetails.durationMs.takeIf { it > 0 } ?: track.durationMs,
                )
            }
        }
    }

    private fun resolvePlaybackDetails(trackId: String): PlaybackDetails? {
        return resolvePlaybackDetails(listOf(trackId))[trackId]
    }

    private fun resolvePlaybackDetails(trackIds: List<String>): Map<String, PlaybackDetails> {
        if (trackIds.isEmpty()) return emptyMap()

        val officialPlaybackDetails = runCatching {
            resolveOfficialPlaybackDetails(trackIds)
        }.getOrDefault(emptyMap())
        val fallbackTrackIds = trackIds.filter { trackId ->
            val details = officialPlaybackDetails[trackId]
            details == null || details.isPreviewOnly
        }
        if (fallbackTrackIds.isEmpty()) return officialPlaybackDetails

        val fallbackPlaybackDetails = resolveThirdPartyPlaybackDetails(fallbackTrackIds)
        return buildMap(trackIds.size) {
            trackIds.forEach { trackId ->
                val preferredDetails = fallbackPlaybackDetails[trackId] ?: officialPlaybackDetails[trackId]
                if (preferredDetails != null) {
                    put(trackId, preferredDetails)
                }
            }
        }
    }

    private fun resolveOfficialPlaybackDetails(trackIds: List<String>): Map<String, PlaybackDetails> {
        if (trackIds.isEmpty()) return emptyMap()

        val payloadJson = JSONObject().apply {
            put(
                "ids",
                JSONArray().apply {
                    trackIds.forEach { trackId ->
                        put(trackId.toLongOrNull() ?: trackId)
                    }
                },
            )
            put("level", OFFICIAL_PLAYBACK_LEVEL)
            put("encodeType", "flac")
            put("header", buildOfficialPlaybackHeader())
        }.toString()

        val requestBody = FormBody.Builder()
            .add("params", encryptEapiParams(OFFICIAL_PLAYBACK_URL, payloadJson))
            .build()
        val request = Request.Builder()
            .url(OFFICIAL_PLAYBACK_URL)
            .addHeader("User-Agent", NETEASE_WEB_USER_AGENT)
            .addHeader("Referer", "https://music.163.com/")
            .addHeader("Origin", "https://music.163.com")
            .addHeader("Cookie", NETEASE_EAPI_COOKIE)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("网易云播放地址请求失败: HTTP ${response.code}")
            }

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return emptyMap()

            val root = JSONObject(body)
            val data = root.optJSONArray("data") ?: return emptyMap()

            return buildMap(data.length()) {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val resolvedTrackId = item.opt("id")?.toString().orEmpty()
                    val resolvedUrl = item.optString("url").orEmpty()
                    if (resolvedTrackId.isBlank() || resolvedUrl.isBlank()) continue

                    put(
                        resolvedTrackId,
                        PlaybackDetails(
                            audioUrl = resolvedUrl.replace("http://", "https://"),
                            durationMs = item.optLong("time"),
                            isPreviewOnly = item.optJSONObject("freeTrialInfo") != null,
                        ),
                    )
                }
            }
        }
    }

    private fun buildOfficialPlaybackHeader(): String {
        return JSONObject().apply {
            put("os", "pc")
            put("appver", "2.7.1.198277")
            put("osver", "")
            put("deviceId", NETEASE_EAPI_DEVICE_ID)
            put("requestId", System.currentTimeMillis().toString())
        }.toString()
    }

    private fun encryptEapiParams(
        url: String,
        payloadJson: String,
    ): String {
        val urlPath = url.substringAfter("://").substringAfter('/').substringBefore('?')
            .let { "/$it" }
            .replace("/eapi/", "/api/")
        val digest = md5Hex("nobody${urlPath}use${payloadJson}md5forencrypt")
        val params = "$urlPath-36cd479b6b5-$payloadJson-36cd479b6b5-$digest"

        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(EAPI_AES_KEY.toByteArray(Charsets.UTF_8), "AES"),
        )
        return cipher.doFinal(params.toByteArray(Charsets.UTF_8)).toHexString()
    }

    private fun md5Hex(text: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .toHexString()
    }

    private fun ByteArray.toHexString(): String {
        return joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun resolveThirdPartyPlaybackDetails(trackIds: List<String>): Map<String, PlaybackDetails> {
        return buildMap(trackIds.size) {
            trackIds.forEach { trackId ->
                resolveThirdPartyPlaybackDetails(trackId)?.let { put(trackId, it) }
            }
        }
    }

    private fun resolveThirdPartyPlaybackDetails(trackId: String): PlaybackDetails? {
        return resolveCunyuPlaybackDetails(trackId) ?: resolveCggPlaybackDetails(trackId)
    }

    private fun resolveCunyuPlaybackDetails(trackId: String): PlaybackDetails? {
        return runCatching {
            val request = Request.Builder()
                .url("https://www.cunyuapi.top/163music_play?id=$trackId&quality=standard")
                .addHeader("User-Agent", NETEASE_WEB_USER_AGENT)
                .addHeader("Referer", "https://music.163.com/")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null

                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@use null

                val root = runCatching { JSONObject(body) }.getOrNull()
                if (root == null) return@use null

                val resolvedUrl = root.optString("song_file_url").orEmpty()
                if (resolvedUrl.isBlank()) return@use null

                PlaybackDetails(
                    audioUrl = resolvedUrl.replace("http://", "https://"),
                    durationMs = extractDurationMsFromLyric(root.optString("lyric")),
                    isPreviewOnly = false,
                )
            }
        }.getOrNull()
    }

    private fun resolveCggPlaybackDetails(trackId: String): PlaybackDetails? {
        return runCatching {
            val request = Request.Builder()
                .url("https://api-v2.cenguigui.cn/api/netease/music_v1.php?id=$trackId&type=json&level=standard")
                .addHeader("User-Agent", NETEASE_WEB_USER_AGENT)
                .addHeader("Referer", "https://music.163.com/")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null

                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@use null

                val root = runCatching { JSONObject(body) }.getOrNull()
                if (root == null) return@use null

                val data = root.optJSONObject("data")
                if (data == null) return@use null

                val resolvedUrl = data.optString("url").orEmpty()
                if (resolvedUrl.isBlank()) return@use null

                PlaybackDetails(
                    audioUrl = resolvedUrl.replace("http://", "https://"),
                    durationMs = parseClockDurationMs(data.optString("duration")),
                    isPreviewOnly = false,
                )
            }
        }.getOrNull()
    }

    private fun extractDurationMsFromLyric(lyric: String): Long {
        if (lyric.isBlank()) return 0L

        var maxDurationMs = 0L
        TIMESTAMP_REGEX.findAll(lyric).forEach { match ->
            val minutes = match.groupValues.getOrNull(1)?.toLongOrNull() ?: return@forEach
            val seconds = match.groupValues.getOrNull(2)?.toLongOrNull() ?: return@forEach
            val fractionRaw = match.groupValues.getOrNull(3).orEmpty()
            val milliseconds = when (fractionRaw.length) {
                0 -> 0L
                1 -> fractionRaw.toLongOrNull()?.times(100L) ?: 0L
                2 -> fractionRaw.toLongOrNull()?.times(10L) ?: 0L
                else -> fractionRaw.take(3).toLongOrNull() ?: 0L
            }
            val timestampMs = minutes * 60_000L + seconds * 1_000L + milliseconds
            maxDurationMs = max(maxDurationMs, timestampMs)
        }

        return maxDurationMs
    }

    private fun parseClockDurationMs(durationText: String): Long {
        if (durationText.isBlank()) return 0L

        val parts = durationText.split(':')
            .mapNotNull { it.trim().toLongOrNull() }
        if (parts.isEmpty()) return 0L

        return when (parts.size) {
            3 -> parts[0] * 3_600_000L + parts[1] * 60_000L + parts[2] * 1_000L
            2 -> parts[0] * 60_000L + parts[1] * 1_000L
            else -> parts[0] * 1_000L
        }
    }

    private companion object {
        const val MATCH_LIMIT = 8
        const val OFFICIAL_PLAYBACK_LEVEL = "standard"
        const val OFFICIAL_PLAYBACK_URL = "https://interface3.music.163.com/eapi/song/enhance/player/url/v1"
        const val NETEASE_EAPI_DEVICE_ID = "pyncm!"
        const val NETEASE_WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        const val NETEASE_WEB_COOKIE = "os=pc; appver=2.7.1.198277;"
        const val NETEASE_EAPI_COOKIE = "os=pc; appver=2.7.1.198277; osver=; deviceId=$NETEASE_EAPI_DEVICE_ID;"
        const val EAPI_AES_KEY = "e82ckenh8dichen8"
        val TIMESTAMP_REGEX = Regex("""\[(\d+):(\d{2})(?:\.(\d{1,3}))?]""")
    }
}
