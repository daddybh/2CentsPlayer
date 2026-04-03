package com.twocents.player.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.regex.Pattern
import java.util.concurrent.TimeUnit

class KuwoSearchRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
) : MusicSourceRepository {
    override val source: TrackSource = TrackSource.KUWO

    override fun searchTracks(
        keyword: String,
        limit: Int,
        offset: Int,
    ): List<Track> {
        val trimmedKeyword = keyword.trim()
        if (trimmedKeyword.isBlank()) return emptyList()

        val page = if (limit <= 0) 0 else offset / limit
        val url = SEARCH_URL.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("vipver", "1")
            ?.addQueryParameter("client", "kt")
            ?.addQueryParameter("ft", "music")
            ?.addQueryParameter("cluster", "0")
            ?.addQueryParameter("strategy", "2012")
            ?.addQueryParameter("encoding", "utf8")
            ?.addQueryParameter("rformat", "json")
            ?.addQueryParameter("mobi", "1")
            ?.addQueryParameter("issubtitle", "1")
            ?.addQueryParameter("show_copyright_off", "1")
            ?.addQueryParameter("pn", page.toString())
            ?.addQueryParameter("rn", limit.toString())
            ?.addQueryParameter("all", trimmedKeyword)
            ?.build()
            ?: throw IOException("酷我搜索地址构造失败")

        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", WEB_USER_AGENT)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("酷我搜索请求失败: HTTP ${response.code}")
            }

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                throw IOException("酷我搜索返回了空响应")
            }

            val root = JSONObject(body)
            val songs = root.optJSONArray("abslist") ?: return emptyList()

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
        val rawId = track.sourceTrackId()
        if (rawId.isBlank()) return null

        val request = Request.Builder()
            .url("$LYRIC_URL?${KuwoCrypto.buildLyricsParams(rawId, includeLyricX = true)}")
            .addHeader("User-Agent", WEB_USER_AGENT)
            .get()
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("酷我歌词请求失败: HTTP ${response.code}")
            }

            val body = response.body?.bytes() ?: return@use null
            val decoded = KuwoCrypto.decodeLyricResponse(body, includeLyricX = true)
            KuwoCrypto.convertRawLrc(decoded).takeIf { it.isNotBlank() }
        }
    }

    override fun resolvePlayableTracks(tracks: List<Track>): List<Track> {
        if (tracks.isEmpty()) return emptyList()
        return tracks.map { track ->
            resolvePlayableTrack(track) ?: track.withCanonicalIdentity()
        }
    }

    private fun JSONObject.toTrack(): Track {
        val rawSourceId = optString("MUSICRID")
            .ifBlank { optString("musicrid") }
            .removePrefix("MUSIC_")

        return Track(
            id = "${TrackSource.KUWO.storageKey}:$rawSourceId",
            source = TrackSource.KUWO,
            sourceId = rawSourceId,
            title = optString("SONGNAME").ifBlank { optString("NAME") },
            artist = optString("ARTIST").ifBlank { "未知艺术家" },
            album = optString("ALBUM"),
            durationMs = optString("DURATION").toLongOrNull()?.times(1_000L) ?: 0L,
            coverUrl = optString("hts_MVPIC")
                .ifBlank { optString("MVPIC") }
                .let { url ->
                    when {
                        url.startsWith("http://") -> url.replace("http://", "https://")
                        url.startsWith("https://") -> url
                        else -> ""
                    }
                },
        ).withCanonicalIdentity()
    }

    private fun resolvePlayableTrack(track: Track): Track? {
        val rawId = track.sourceTrackId()
        if (rawId.isBlank()) return null

        val query = "user=0&corp=kuwo&source=kwplayer_ar_5.1.0.0_B_jiakong_vh.apk&p2p=1&type=convert_url2&sig=0&format=mp3&rid=$rawId"
        val request = Request.Builder()
            .url("$PLAYBACK_URL?f=kuwo&q=${KuwoCrypto.encryptQuery(query)}")
            .addHeader("User-Agent", MOBILE_USER_AGENT)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null

            val urlMatch = playbackUrlPattern.matcher(body)
            if (!urlMatch.find()) return null

            val resolvedUrl = urlMatch.group(1).orEmpty()
            if (resolvedUrl.isBlank()) return null

            return track.withCanonicalIdentity().copy(
                audioUrl = resolvedUrl,
            )
        }
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

    private companion object {
        const val MATCH_LIMIT = 8
        const val SEARCH_URL = "https://www.kuwo.cn/search/searchMusicBykeyWord"
        const val PLAYBACK_URL = "http://mobi.kuwo.cn/mobi.s"
        const val LYRIC_URL = "http://newlyric.kuwo.cn/newlyric.lrc"
        const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        const val MOBILE_USER_AGENT = "okhttp/3.10.0"
        val playbackUrlPattern = Pattern.compile("""url=(http[^\r\n]+)""")
    }
}
