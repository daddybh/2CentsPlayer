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

    private companion object {
        const val NETEASE_WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
    }
}
