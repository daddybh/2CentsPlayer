package com.twocents.player.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioReplenishmentEngineTest {
    @Test
    fun replenish_acceptsPartialResultAfterSingleAiAttempt() {
        val candidateSource = FakeRadioCandidateSource(
            responses = listOf(
                listOf(
                    suggestedTrack("first-safe", "Artist 1", RadioCandidateBucket.SAFE),
                    suggestedTrack("first-adjacent", "Artist 2", RadioCandidateBucket.ADJACENT),
                    suggestedTrack("first-missing", "Artist 3", RadioCandidateBucket.SAFE),
                ),
                listOf(
                    suggestedTrack("first-safe", "Artist 1", RadioCandidateBucket.SAFE),
                    suggestedTrack("second-same-artist", "Artist 2", RadioCandidateBucket.SAFE),
                    suggestedTrack("second-safe", "Artist 4", RadioCandidateBucket.SAFE),
                    suggestedTrack("second-adjacent", "Artist 5", RadioCandidateBucket.ADJACENT),
                ),
            ),
        )
        val trackLookup = FakeRadioTrackLookup(
            matchedTracks = mapOf(
                "first-safe" to track("first-safe", "Artist 1", audioUrl = "https://audio.example/first-safe.mp3"),
                "first-adjacent" to track("first-adjacent", "Artist 2"),
                "first-missing" to track("first-missing", "Artist 3"),
                "second-same-artist" to track("second-same-artist", "Artist 2"),
                "second-safe" to track("second-safe", "Artist 4"),
                "second-adjacent" to track("second-adjacent", "Artist 5"),
            ),
            resolvedTracks = mapOf(
                "first-adjacent" to track("first-adjacent", "Artist 2", audioUrl = "https://audio.example/first-adjacent.mp3"),
                "second-same-artist" to track("second-same-artist", "Artist 2", audioUrl = "https://audio.example/second-same-artist.mp3"),
                "second-safe" to track("second-safe", "Artist 4", audioUrl = "https://audio.example/second-safe.mp3"),
                "second-adjacent" to track("second-adjacent", "Artist 5", audioUrl = "https://audio.example/second-adjacent.mp3"),
            ),
        )
        val engine = RadioReplenishmentEngine(
            candidateSource = candidateSource,
            trackLookup = trackLookup,
        )

        val result = runBlocking {
            engine.replenish(
                settings = AiServiceConfig(endpoint = "https://api.example", model = "test-model", accessKey = "secret"),
                favorites = listOf(track("favorite-1", "Favorite Artist", audioUrl = "https://audio.example/favorite-1.mp3")),
                history = RadioHistorySnapshot(),
                session = RadioSessionState(sessionId = 7L),
            )
        }

        assertEquals(1, candidateSource.callCount)
        assertEquals(3, result.appendedRecommendations.size)
        assertEquals(
            listOf("first-adjacent", "first-missing", "first-safe"),
            result.appendedRecommendations.map { it.track.id },
        )
        assertEquals(RadioBoundaryState.BALANCED, candidateSource.requests[0].boundaryState)
        assertEquals(RadioWaveTargets(4, 2, 1), candidateSource.requests[0].waveTargets)
    }

    @Test
    fun replenish_marksSessionDegradedWhenAllRetriesFail() {
        val candidateSource = FakeRadioCandidateSource(
            responses = listOf(
                listOf(suggestedTrack("missing-1", "Artist 1", RadioCandidateBucket.SAFE)),
                listOf(suggestedTrack("missing-2", "Artist 2", RadioCandidateBucket.ADJACENT)),
                listOf(suggestedTrack("missing-3", "Artist 3", RadioCandidateBucket.SURPRISE)),
            ),
        )
        val trackLookup = FakeRadioTrackLookup(matchedTracks = emptyMap())
        val engine = RadioReplenishmentEngine(
            candidateSource = candidateSource,
            trackLookup = trackLookup,
        )

        val result = runBlocking {
            engine.replenish(
                settings = AiServiceConfig(endpoint = "https://api.example", model = "test-model", accessKey = "secret"),
                favorites = listOf(track("favorite-1", "Favorite Artist", audioUrl = "https://audio.example/favorite-1.mp3")),
                history = RadioHistorySnapshot(),
                session = RadioSessionState(sessionId = 11L),
            )
        }

        assertTrue(result.appendedRecommendations.isEmpty())
        assertEquals(1, candidateSource.callCount)
        assertEquals(RadioBoundaryState.RECOVERING, result.updatedSession.boundaryState)
        assertEquals("回到熟悉区", result.updatedSession.statusLabel)
    }

    @Test
    fun replenish_filtersLocallyBlockedTracksAfterMatching() {
        val candidateSource = FakeRadioCandidateSource(
            responses = listOf(
                listOf(
                    suggestedTrack("negative-1", "Blocked Negative Artist", RadioCandidateBucket.SAFE),
                    suggestedTrack("played-1", "Blocked Played Artist", RadioCandidateBucket.ADJACENT),
                    suggestedTrack("artist-blocked-1", "Blocked Artist / Guest", RadioCandidateBucket.SAFE),
                    suggestedTrack("allowed-1", "Allowed Artist 1", RadioCandidateBucket.SAFE),
                    suggestedTrack("allowed-2", "Allowed Artist 2", RadioCandidateBucket.ADJACENT),
                    suggestedTrack("allowed-3", "Allowed Artist 3", RadioCandidateBucket.SAFE),
                    suggestedTrack("allowed-4", "Allowed Artist 4", RadioCandidateBucket.SURPRISE),
                ),
            ),
        )
        val trackLookup = FakeRadioTrackLookup(
            matchedTracks = mapOf(
                "negative-1" to track("negative-1", "Blocked Negative Artist", audioUrl = "https://audio.example/negative-1.mp3"),
                "played-1" to track("played-1", "Blocked Played Artist", audioUrl = "https://audio.example/played-1.mp3"),
                "artist-blocked-1" to track("artist-blocked-1", "Blocked Artist / Guest", audioUrl = "https://audio.example/artist-blocked-1.mp3"),
                "allowed-1" to track("allowed-1", "Allowed Artist 1", audioUrl = "https://audio.example/allowed-1.mp3"),
                "allowed-2" to track("allowed-2", "Allowed Artist 2", audioUrl = "https://audio.example/allowed-2.mp3"),
                "allowed-3" to track("allowed-3", "Allowed Artist 3", audioUrl = "https://audio.example/allowed-3.mp3"),
                "allowed-4" to track("allowed-4", "Allowed Artist 4", audioUrl = "https://audio.example/allowed-4.mp3"),
            ),
        )
        val engine = RadioReplenishmentEngine(
            candidateSource = candidateSource,
            trackLookup = trackLookup,
        )

        val result = runBlocking {
            engine.replenish(
                settings = AiServiceConfig(endpoint = "https://api.example", model = "test-model", accessKey = "secret"),
                favorites = listOf(track("favorite-1", "Favorite Artist", audioUrl = "https://audio.example/favorite-1.mp3")),
                history = RadioHistorySnapshot(
                    negativeTrackIds = setOf("negative-1"),
                    recentArtistKeys = listOf("blocked artist"),
                ),
                session = RadioSessionState(
                    sessionId = 13L,
                    playedTrackIds = setOf("played-1"),
                ),
                minimumRequiredAppend = 5,
            )
        }

        assertEquals(1, candidateSource.callCount)
        assertEquals(5, result.appendedRecommendations.size)
        assertTrue("artist-blocked-1" in result.appendedRecommendations.map { it.track.id })
    }

    @Test
    fun replenish_allowsFastStartWithSmallerInitialWave() {
        val candidateSource = FakeRadioCandidateSource(
            responses = listOf(
                listOf(
                    suggestedTrack("fast-start", "Fast Artist", RadioCandidateBucket.SAFE),
                ),
            ),
        )
        val trackLookup = FakeRadioTrackLookup(
            matchedTracks = mapOf(
                "fast-start" to track(
                    id = "fast-start",
                    artist = "Fast Artist",
                    audioUrl = "https://audio.example/fast-start.mp3",
                ),
            ),
        )
        val engine = RadioReplenishmentEngine(
            candidateSource = candidateSource,
            trackLookup = trackLookup,
        )

        val result = runBlocking {
            engine.replenish(
                settings = AiServiceConfig(endpoint = "https://api.example", model = "test-model", accessKey = "secret"),
                favorites = listOf(track("favorite-1", "Favorite Artist", audioUrl = "https://audio.example/favorite-1.mp3")),
                history = RadioHistorySnapshot(),
                session = RadioSessionState(sessionId = 21L),
                minimumRequiredAppend = 1,
                requestTransform = { request ->
                    request.copy(
                        waveTargets = RadioWaveTargets(1, 1, 0),
                        rawCandidateLimit = 4,
                    )
                },
            )
        }

        assertEquals(1, candidateSource.callCount)
        assertEquals(1, result.appendedRecommendations.size)
        assertEquals(RadioWaveTargets(1, 1, 0), candidateSource.requests.single().waveTargets)
        assertEquals(4, candidateSource.requests.single().rawCandidateLimit)
    }

    @Test
    fun replenish_fastStartStopsAfterFirstPlayableCandidate() {
        val candidateSource = FakeRadioCandidateSource(
            responses = listOf(
                listOf(
                    suggestedTrack("first-playable", "Artist 1", RadioCandidateBucket.SAFE),
                    suggestedTrack("second-should-not-match", "Artist 2", RadioCandidateBucket.ADJACENT),
                    suggestedTrack("third-should-not-match", "Artist 3", RadioCandidateBucket.SURPRISE),
                ),
            ),
        )
        val trackLookup = FakeRadioTrackLookup(
            matchedTracks = mapOf(
                "first-playable" to track(
                    id = "first-playable",
                    artist = "Artist 1",
                    audioUrl = "https://audio.example/first-playable.mp3",
                ),
                "second-should-not-match" to track(
                    id = "second-should-not-match",
                    artist = "Artist 2",
                    audioUrl = "https://audio.example/second.mp3",
                ),
                "third-should-not-match" to track(
                    id = "third-should-not-match",
                    artist = "Artist 3",
                    audioUrl = "https://audio.example/third.mp3",
                ),
            ),
        )
        val engine = RadioReplenishmentEngine(
            candidateSource = candidateSource,
            trackLookup = trackLookup,
        )

        val result = runBlocking {
            engine.replenish(
                settings = AiServiceConfig(endpoint = "https://api.example", model = "test-model", accessKey = "secret"),
                favorites = listOf(track("favorite-1", "Favorite Artist", audioUrl = "https://audio.example/favorite-1.mp3")),
                history = RadioHistorySnapshot(),
                session = RadioSessionState(sessionId = 31L),
                minimumRequiredAppend = 1,
                requestTransform = { request ->
                    request.copy(
                        waveTargets = RadioWaveTargets(1, 1, 0),
                        rawCandidateLimit = 4,
                    )
                },
            )
        }

        assertEquals(1, result.appendedRecommendations.size)
        assertEquals(listOf("first-playable"), result.appendedRecommendations.map { it.track.id })
        assertEquals(listOf("first-playable"), trackLookup.matchedTitles)
        assertTrue(trackLookup.resolveRequests.isEmpty())
    }

    @Test
    fun replenish_usesPreselectedTrackWithoutLookup() {
        val candidateSource = FakeRadioCandidateSource(
            responses = listOf(
                listOf(
                    AiSuggestedTrack(
                        title = "晴天",
                        artist = "周杰伦",
                        reason = "命中偏好",
                        bucket = RadioCandidateBucket.SAFE,
                        resolvedTrack = track(
                            id = "kuwo:228908",
                            artist = "周杰伦",
                            audioUrl = "https://audio.example/qingtian.mp3",
                        ),
                    ),
                ),
            ),
        )
        val trackLookup = FakeRadioTrackLookup(
            matchedTracks = emptyMap(),
            resolvedTracks = emptyMap(),
        )
        val engine = RadioReplenishmentEngine(
            candidateSource = candidateSource,
            trackLookup = trackLookup,
        )

        val result = runBlocking {
            engine.replenish(
                settings = AiServiceConfig(endpoint = "https://api.example", model = "test-model", accessKey = "secret"),
                favorites = listOf(track("favorite-1", "Favorite Artist", audioUrl = "https://audio.example/favorite-1.mp3")),
                history = RadioHistorySnapshot(),
                session = RadioSessionState(sessionId = 41L),
                minimumRequiredAppend = 1,
            )
        }

        assertEquals(1, result.appendedRecommendations.size)
        assertEquals("kuwo:228908", result.appendedRecommendations.first().track.id)
        assertTrue(result.appendedRecommendations.first().track.audioUrl.isNotBlank())
        assertTrue(trackLookup.matchedTitles.isEmpty())
        assertTrue(trackLookup.resolveRequests.isEmpty())
    }

    @Test
    fun replenish_fastStartBatchesPlayableResolutionForPreselectedCandidates() {
        val candidateSource = FakeRadioCandidateSource(
            responses = listOf(
                listOf(
                    AiSuggestedTrack(
                        title = "first-unplayable",
                        artist = "Artist 1",
                        reason = "reason-1",
                        bucket = RadioCandidateBucket.SAFE,
                        resolvedTrack = track(
                            id = "first-unplayable",
                            artist = "Artist 1",
                        ),
                    ),
                    AiSuggestedTrack(
                        title = "second-playable",
                        artist = "Artist 2",
                        reason = "reason-2",
                        bucket = RadioCandidateBucket.ADJACENT,
                        resolvedTrack = track(
                            id = "second-playable",
                            artist = "Artist 2",
                        ),
                    ),
                ),
            ),
        )
        val trackLookup = FakeRadioTrackLookup(
            matchedTracks = emptyMap(),
            resolvedTracks = mapOf(
                "second-playable" to track(
                    id = "second-playable",
                    artist = "Artist 2",
                    audioUrl = "https://audio.example/second-playable.mp3",
                ),
            ),
        )
        val engine = RadioReplenishmentEngine(
            candidateSource = candidateSource,
            trackLookup = trackLookup,
        )

        val result = runBlocking {
            engine.replenish(
                settings = AiServiceConfig(endpoint = "https://api.example", model = "test-model", accessKey = "secret"),
                favorites = listOf(track("favorite-1", "Favorite Artist", audioUrl = "https://audio.example/favorite-1.mp3")),
                history = RadioHistorySnapshot(),
                session = RadioSessionState(sessionId = 51L),
                minimumRequiredAppend = 1,
            )
        }

        assertEquals(listOf("first-unplayable"), result.appendedRecommendations.map { it.track.id })
        assertTrue(trackLookup.resolveRequests.isEmpty())
        assertTrue(trackLookup.matchedTitles.isEmpty())
    }

    @Test
    fun replenish_fastSourceStartsWithoutAiConfiguration() {
        val fastSource = FakeRadioCandidateSource(
            responses = listOf(
                listOf(
                    AiSuggestedTrack(
                        title = "local",
                        artist = "Local Artist",
                        resolvedTrack = track("local", "Local Artist"),
                        matchedSeedIds = setOf("favorite-1"),
                        retrievalSources = setOf("netease-simi"),
                    ),
                ),
            ),
        )
        val aiSource = ThrowingCandidateSource()
        val engine = RadioReplenishmentEngine(
            candidateSource = aiSource,
            fastCandidateSource = fastSource,
            trackLookup = FakeRadioTrackLookup(emptyMap()),
        )

        val result = runBlocking {
            engine.replenish(
                settings = AiServiceConfig(),
                favorites = listOf(track("favorite-1", "Favorite Artist")),
                history = RadioHistorySnapshot(),
                session = RadioSessionState(61L),
                minimumRequiredAppend = 1,
            )
        }

        assertEquals(listOf("local"), result.appendedRecommendations.map { it.track.id })
        assertEquals(0, aiSource.callCount)
    }

    @Test
    fun replenish_rotatesFastSeedsWhenFirstWaveIsLocallyBlocked() {
        val blockedTrack = track("blocked", "Blocked Artist")
        val allowedTrack = track("allowed", "Allowed Artist")
        val fastSource = FakeRadioCandidateSource(
            responses = listOf(
                listOf(
                    AiSuggestedTrack(
                        title = blockedTrack.title,
                        artist = blockedTrack.artist,
                        resolvedTrack = blockedTrack,
                    ),
                ),
                listOf(
                    AiSuggestedTrack(
                        title = allowedTrack.title,
                        artist = allowedTrack.artist,
                        resolvedTrack = allowedTrack,
                    ),
                ),
            ),
        )
        val aiSource = ThrowingCandidateSource()
        val favorites = (1..6).map { index ->
            track("favorite-$index", "Favorite Artist $index")
        }
        val engine = RadioReplenishmentEngine(
            candidateSource = aiSource,
            fastCandidateSource = fastSource,
            trackLookup = FakeRadioTrackLookup(emptyMap()),
        )

        val result = runBlocking {
            engine.replenish(
                settings = AiServiceConfig(endpoint = "https://api.example", model = "m", accessKey = "k"),
                favorites = favorites,
                history = RadioHistorySnapshot(negativeTrackIds = setOf(blockedTrack.id)),
                session = RadioSessionState(611L),
                minimumRequiredAppend = 1,
            )
        }

        assertEquals(listOf(allowedTrack.id), result.appendedRecommendations.map { it.track.id })
        assertEquals(2, fastSource.callCount)
        assertEquals(0, aiSource.callCount)
        assertEquals(
            listOf("favorite-1", "favorite-2", "favorite-3"),
            fastSource.requests[0].favoriteSeeds.map(Track::id),
        )
        assertEquals(
            listOf("favorite-4", "favorite-5", "favorite-6"),
            fastSource.requests[1].favoriteSeeds.map(Track::id),
        )
    }

    @Test
    fun replenish_keepsPartialFastResultsWhenAiFallbackFails() {
        val fastSource = FakeRadioCandidateSource(
            responses = listOf(
                listOf(
                    AiSuggestedTrack(
                        title = "local-1",
                        artist = "Local Artist 1",
                        resolvedTrack = track("local-1", "Local Artist 1"),
                    ),
                    AiSuggestedTrack(
                        title = "local-2",
                        artist = "Local Artist 2",
                        resolvedTrack = track("local-2", "Local Artist 2"),
                    ),
                ),
            ),
        )
        val aiSource = ThrowingCandidateSource()
        val engine = RadioReplenishmentEngine(
            candidateSource = aiSource,
            fastCandidateSource = fastSource,
            trackLookup = FakeRadioTrackLookup(emptyMap()),
        )

        val result = runBlocking {
            engine.replenish(
                settings = AiServiceConfig(endpoint = "https://api.example", model = "m", accessKey = "k"),
                favorites = listOf(track("favorite-1", "Favorite Artist")),
                history = RadioHistorySnapshot(),
                session = RadioSessionState(62L),
            )
        }

        assertEquals(listOf("local-1", "local-2"), result.appendedRecommendations.map { it.track.id })
        assertEquals(1, aiSource.callCount)
    }

    @Test
    fun replenish_doesNotRepeatAiImmediatelyWhenFastAndAiSourcesBothYieldNothing() {
        val aiSource = ThrowingCandidateSource()
        val engine = RadioReplenishmentEngine(
            candidateSource = aiSource,
            fastCandidateSource = FakeRadioCandidateSource(responses = listOf(emptyList())),
            trackLookup = FakeRadioTrackLookup(emptyMap()),
        )

        runBlocking {
            engine.replenish(
                settings = AiServiceConfig(endpoint = "https://api.example", model = "m", accessKey = "k"),
                favorites = listOf(track("favorite-1", "Favorite Artist")),
                history = RadioHistorySnapshot(),
                session = RadioSessionState(63L),
            )
        }

        assertEquals(1, aiSource.callCount)
    }

    @Test
    fun replenish_rotatesSeedsAcrossRequestsInTheSameSession() {
        val fastSource = FakeRadioCandidateSource(
            responses = listOf(
                listOf(
                    AiSuggestedTrack(
                        title = "local-1",
                        artist = "Local Artist 1",
                        resolvedTrack = track("local-1", "Local Artist 1"),
                    ),
                ),
                listOf(
                    AiSuggestedTrack(
                        title = "local-2",
                        artist = "Local Artist 2",
                        resolvedTrack = track("local-2", "Local Artist 2"),
                    ),
                ),
            ),
        )
        val favorites = (1..4).map { index ->
            track("favorite-$index", "Favorite Artist $index")
        }
        val engine = RadioReplenishmentEngine(
            candidateSource = ThrowingCandidateSource(),
            fastCandidateSource = fastSource,
            trackLookup = FakeRadioTrackLookup(emptyMap()),
        )

        val firstResult = runBlocking {
            engine.replenish(
                settings = AiServiceConfig(),
                favorites = favorites,
                history = RadioHistorySnapshot(),
                session = RadioSessionState(64L),
                minimumRequiredAppend = 1,
            )
        }
        runBlocking {
            engine.replenish(
                settings = AiServiceConfig(),
                favorites = favorites,
                history = RadioHistorySnapshot(),
                session = firstResult.updatedSession,
                minimumRequiredAppend = 1,
            )
        }

        assertEquals(
            listOf("favorite-1", "favorite-2", "favorite-3"),
            fastSource.requests[0].favoriteSeeds.map(Track::id),
        )
        assertEquals(
            listOf("favorite-4", "favorite-1", "favorite-2"),
            fastSource.requests[1].favoriteSeeds.map(Track::id),
        )
    }

    private fun suggestedTrack(
        title: String,
        artist: String,
        bucket: RadioCandidateBucket,
    ): AiSuggestedTrack {
        return AiSuggestedTrack(
            title = title,
            artist = artist,
            reason = "reason-$title",
            bucket = bucket,
        )
    }

    private fun track(
        id: String,
        artist: String,
        audioUrl: String = "",
    ): Track {
        return Track(
            id = id,
            title = id,
            artist = artist,
            audioUrl = audioUrl,
        )
    }

    private class FakeRadioCandidateSource(
        private val responses: List<List<AiSuggestedTrack>>,
    ) : RadioCandidateSource {
        var callCount: Int = 0
            private set
        val requests = mutableListOf<RadioRecommendationRequest>()

        override fun requestRadioCandidates(
            settings: AiServiceConfig,
            request: RadioRecommendationRequest,
        ): List<AiSuggestedTrack> {
            requests += request
            val response = responses.getOrElse(callCount) { emptyList() }
            callCount += 1
            return response
        }
    }

    private class ThrowingCandidateSource : RadioCandidateSource {
        var callCount = 0

        override fun requestRadioCandidates(
            settings: AiServiceConfig,
            request: RadioRecommendationRequest,
        ): List<AiSuggestedTrack> {
            callCount += 1
            error("AI unavailable")
        }
    }

    private class FakeRadioTrackLookup(
        private val matchedTracks: Map<String, Track>,
        private val resolvedTracks: Map<String, Track> = emptyMap(),
    ) : RadioTrackLookup {
        val matchedTitles = mutableListOf<String>()
        val resolveRequests = mutableListOf<List<String>>()

        override suspend fun findBestMatchTrack(
            title: String,
            artist: String,
        ): Track? {
            matchedTitles += title
            return matchedTracks[title]
        }

        override suspend fun resolvePlayableTracks(tracks: List<Track>): List<Track> {
            resolveRequests += tracks.map { it.id }
            return tracks.mapNotNull { track -> resolvedTracks[track.id] }
        }
    }
}
