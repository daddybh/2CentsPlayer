package com.twocents.player.data

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class NeteaseSearchRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
) {

    data class PlaybackDetails(
        val audioUrl: String,
        val durationMs: Long,
    )

    fun searchTracks(
        keyword: String,
        limit: Int = 20,
        offset: Int = 0,
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
            id = opt("id")?.toString().orEmpty(),
            title = optString("name"),
            artist = artists.joinToString(", ").ifBlank { "未知艺术家" },
            album = albumJson?.optString("name").orEmpty(),
            durationMs = optLong("dt"),
            coverUrl = albumJson?.optString("picUrl").orEmpty().replace("http://", "https://"),
        )
    }

    fun resolvePlayableTrack(track: Track): Track {
        if (track.audioUrl.isNotBlank() || track.id.isBlank()) return track
        val playbackDetails = resolvePlaybackDetails(track.id) ?: return track
        return track.copy(
            audioUrl = playbackDetails.audioUrl,
            durationMs = if (track.durationMs > 0) track.durationMs else playbackDetails.durationMs,
        )
    }

    fun resolvePlayableTracks(tracks: List<Track>): List<Track> {
        if (tracks.isEmpty()) return emptyList()

        val trackIds = tracks.map { it.id }.filter { it.isNotBlank() }
        if (trackIds.isEmpty()) return tracks

        val playbackDetailsById = resolvePlaybackDetails(trackIds)
        return tracks.map { track ->
            val playbackDetails = playbackDetailsById[track.id]
            if (track.audioUrl.isNotBlank() || playbackDetails == null) {
                track
            } else {
                track.copy(
                    audioUrl = playbackDetails.audioUrl,
                    durationMs = if (track.durationMs > 0) track.durationMs else playbackDetails.durationMs,
                )
            }
        }
    }

    private fun resolvePlaybackDetails(trackId: String): PlaybackDetails? {
        return resolvePlaybackDetails(listOf(trackId))[trackId]
    }

    private fun resolvePlaybackDetails(trackIds: List<String>): Map<String, PlaybackDetails> {
        if (trackIds.isEmpty()) return emptyMap()

        val joinedIds = trackIds.joinToString(separator = ",")
        val request = Request.Builder()
            .url("https://music.163.com/api/song/enhance/player/url?id=${trackIds.first()}&ids=%5B$joinedIds%5D&br=320000")
            .addHeader("User-Agent", NETEASE_WEB_USER_AGENT)
            .addHeader("Referer", "https://music.163.com/")
            .addHeader("Origin", "https://music.163.com")
            .addHeader("Cookie", NETEASE_WEB_COOKIE)
            .get()
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
                        ),
                    )
                }
            }
        }
    }

    private companion object {
        const val NETEASE_WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        const val NETEASE_WEB_COOKIE = "os=pc; appver=2.7.1.198277;"
    }
}
