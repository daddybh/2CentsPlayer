package com.twocents.player.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NeteaseSimiRepositoryTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun requestRadioCandidates_collectsDeterministicQuotaFromEachSeedAndMergesEvidence() {
        server.enqueue(simiResponse(song("shared", "Shared"), song("a", "A")))
        server.enqueue(simiResponse(song("shared", "Shared"), song("b", "B")))
        server.enqueue(simiResponse(song("c", "C"), song("d", "D")))
        val repository = NeteaseSimiRepository(endpoint = server.url("/simi").toString())
        val request = requestWithSeeds("seed-1", "seed-2", "seed-3")

        val result = repository.requestRadioCandidates(AiServiceConfig(), request)

        assertEquals(listOf("netease:shared", "netease:a", "netease:b", "netease:c", "netease:d"), result.map { it.resolvedTrack?.id })
        assertEquals(setOf("netease:seed-1", "netease:seed-2"), result.first().matchedSeedIds)
        assertEquals(setOf("netease-simi"), result.first().retrievalSources)
        assertEquals("因为你喜欢「seed-1」", result.first().reason)
        assertEquals(listOf("seed-1", "seed-2", "seed-3"), (0 until 3).map { server.takeRequest().body.readUtf8().substringAfter("songid=").substringBefore('&') })
    }

    @Test
    fun requestRadioCandidates_logsFilteringYield() {
        server.enqueue(simiResponse(song("queued", "Queued"), song("recent", "Recent")))
        val logger = RecordingLogger()
        val repository = NeteaseSimiRepository(
            endpoint = server.url("/simi").toString(),
            logger = logger,
        )
        val request = requestWithSeeds("seed-1").copy(
            avoidTrackIds = setOf("netease:queued"),
            avoidArtistKeys = setOf("artist recent"),
        )

        val result = repository.requestRadioCandidates(AiServiceConfig(), request)

        assertTrue(result.isEmpty())
        assertTrue(
            logger.messages.any {
                "raw=2" in it &&
                    "duplicate_in_queue=1" in it &&
                    "recent_artist=1" in it &&
                    "accepted=0" in it &&
                    "yield=0%" in it
            },
        )
    }

    private fun requestWithSeeds(vararg ids: String): RadioRecommendationRequest {
        return RadioRecommendationRequest(
            boundaryState = RadioBoundaryState.BALANCED,
            waveTargets = RadioWaveTargets(4, 2, 0),
            rawCandidateLimit = 6,
            favoriteSeeds = ids.map { id ->
                Track(
                    id = "netease:$id",
                    source = TrackSource.NETEASE,
                    sourceId = id,
                    title = id,
                    artist = "Artist $id",
                )
            },
            positiveTrackIds = emptySet(),
            negativeTrackIds = emptySet(),
            avoidTrackIds = emptySet(),
            avoidArtistKeys = emptySet(),
        )
    }

    private fun simiResponse(vararg songs: String): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setBody("""{"code":200,"songs":[${songs.joinToString(",")}]}""")
    }

    private fun song(id: String, title: String): String {
        return """{"id":"$id","name":"$title","artists":[{"name":"Artist $title"}],"album":{"name":"Album","picUrl":""},"duration":180000}"""
    }

    private class RecordingLogger : RadioDiagnosticLogger {
        val messages = mutableListOf<String>()

        override fun debug(message: String) {
            messages += message
        }
    }
}
