package com.twocents.player.data

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NeteaseSearchRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: NeteaseSearchRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = NeteaseSearchRepository(
            client = OkHttpClient.Builder()
                .addInterceptor(HostRewriteInterceptor(server.url("/")))
                .build(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun resolvePlayableTracks_prefersOfficialEapiPlaybackUrlBeforeThirdPartyFallback() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.requestUrl?.encodedPath) {
                    "/eapi/song/enhance/player/url/v1" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """
                            {
                              "data": [
                                {
                                  "id": 123,
                                  "url": "http://official.example/audio.mp3",
                                  "time": 187000
                                }
                              ]
                            }
                            """.trimIndent(),
                        )

                    "/163music_play" -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"song_file_url":"http://fallback.example/audio.mp3","lyric":"[00:01.00]line"}""")

                    "/api/netease/music_v1.php" -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"data":{"url":"http://fallback-cgg.example/audio.mp3","duration":"03:07"}}""")

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val resolvedTrack = repository.resolvePlayableTracks(listOf(track(id = "123"))).single()

        assertEquals("https://official.example/audio.mp3", resolvedTrack.audioUrl)
        assertEquals(187000L, resolvedTrack.durationMs)

        val officialRequest = server.takeRequest()
        assertEquals("POST", officialRequest.method)
        assertEquals("/eapi/song/enhance/player/url/v1", officialRequest.requestUrl?.encodedPath)
        assertTrue(officialRequest.body.readUtf8().contains("params="))
    }

    @Test
    fun resolvePlayableTracks_usesThirdPartyFallbackWhenOfficialTrackIsPreviewOnly() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.requestUrl?.encodedPath) {
                    "/eapi/song/enhance/player/url/v1" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """
                            {
                              "data": [
                                {
                                  "id": 123,
                                  "url": "http://official.example/preview.mp3",
                                  "time": 30000,
                                  "freeTrialInfo": {
                                    "start": 0,
                                    "end": 30000
                                  }
                                }
                              ]
                            }
                            """.trimIndent(),
                        )

                    "/163music_play" -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"song_file_url":"http://fallback.example/full.mp3","lyric":"[03:12.50]line"}""")

                    "/api/netease/music_v1.php" -> MockResponse()
                        .setResponseCode(500)

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val resolvedTrack = repository.resolvePlayableTracks(listOf(track(id = "123"))).single()

        assertEquals("https://fallback.example/full.mp3", resolvedTrack.audioUrl)
        assertEquals(192500L, resolvedTrack.durationMs)

        val officialRequest = server.takeRequest()
        val fallbackRequest = server.takeRequest()
        assertEquals("/eapi/song/enhance/player/url/v1", officialRequest.requestUrl?.encodedPath)
        assertEquals("/163music_play", fallbackRequest.requestUrl?.encodedPath)
    }

    @Test
    fun resolvePlayableTracks_usesThirdPartyFallbackWhenOfficialRequestFails() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.requestUrl?.encodedPath) {
                    "/eapi/song/enhance/player/url/v1" -> MockResponse().setResponseCode(500)
                    "/163music_play" -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"song_file_url":"http://fallback.example/recovered.mp3","lyric":"[00:42.00]line"}""")

                    "/api/netease/music_v1.php" -> MockResponse()
                        .setResponseCode(500)

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val resolvedTrack = repository.resolvePlayableTracks(listOf(track(id = "123"))).single()

        assertEquals("https://fallback.example/recovered.mp3", resolvedTrack.audioUrl)
        assertEquals(42000L, resolvedTrack.durationMs)

        val officialRequest = server.takeRequest()
        val fallbackRequest = server.takeRequest()
        assertEquals("/eapi/song/enhance/player/url/v1", officialRequest.requestUrl?.encodedPath)
        assertEquals("/163music_play", fallbackRequest.requestUrl?.encodedPath)
    }

    private fun track(id: String): Track {
        return Track(
            id = id,
            title = "Test Song",
            artist = "Test Artist",
            durationMs = 1L,
        )
    }

    private class HostRewriteInterceptor(
        private val baseUrl: HttpUrl,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val rewrittenUrl = request.url.newBuilder()
                .scheme(baseUrl.scheme)
                .host(baseUrl.host)
                .port(baseUrl.port)
                .build()
            return chain.proceed(
                request.newBuilder()
                    .url(rewrittenUrl)
                    .build(),
            )
        }
    }
}
