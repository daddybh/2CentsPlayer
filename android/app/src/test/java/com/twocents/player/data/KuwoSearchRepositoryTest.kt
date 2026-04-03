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
import org.junit.Before
import org.junit.Test

class KuwoSearchRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: KuwoSearchRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = KuwoSearchRepository(
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
    fun resolvePlayableTracks_preservesOfficialHttpStreamUrl() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.requestUrl?.encodedPath) {
                    "/mobi.s" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """
                            format=mp3
                            bitrate=320
                            url=http://kuwo.example/audio.mp3
                            rid=228908
                            """.trimIndent(),
                        )

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val resolvedTrack = repository.resolvePlayableTracks(
            listOf(
                Track(
                    id = "kuwo:228908",
                    source = TrackSource.KUWO,
                    sourceId = "228908",
                    title = "晴天",
                    artist = "周杰伦",
                ),
            ),
        ).single()

        assertEquals("http://kuwo.example/audio.mp3", resolvedTrack.audioUrl)
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
