package com.twocents.player.data

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AiRecommendationRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: AiRecommendationRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = AiRecommendationRepository(client = OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun requestRadioCandidates_postsWaveTargetsAndParsesBuckets() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "choices": [
                        {
                          "message": {
                            "content": "{\"recommendations\":[{\"title\":\"Song A\",\"artist\":\"Artist A\",\"reason\":\"r1\",\"bucket\":\"adjacent\"},{\"title\":\"Song B\",\"artist\":\"Artist B\",\"reason\":\"r2\",\"bucket\":\"safe\"}]}"
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val settings = AiServiceConfig(
            endpoint = server.url("/v1").toString(),
            model = "gpt-test",
            accessKey = "test-key",
        )
        val request = RadioRecommendationRequest(
            boundaryState = RadioBoundaryState.BALANCED,
            waveTargets = RadioWaveTargets(safeCount = 4, adjacentCount = 2, surpriseCount = 1),
            rawCandidateLimit = 12,
            favoriteSeeds = listOf(
                Track(
                    id = "seed-1",
                    title = "Seed Song",
                    artist = "Seed Artist",
                ),
            ),
            positiveTrackIds = setOf("pos-1"),
            negativeTrackIds = setOf("neg-1"),
            avoidTrackIds = setOf("avoid-1"),
            avoidArtistKeys = setOf("artist-key-1"),
        )

        val result = repository.requestRadioCandidates(settings, request)
        val postedBody = server.takeRequest().body.readUtf8()

        assertTrue(postedBody.contains("safe=4 adjacent=2 surprise=1"))
        assertTrue(postedBody.contains("Avoid track ids"))
        assertEquals(
            listOf(RadioCandidateBucket.ADJACENT, RadioCandidateBucket.SAFE),
            result.map { it.bucket },
        )
    }
}
