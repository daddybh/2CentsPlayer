# Exploration Radio Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current batch-style `AI 心动模式` flow with an in-player exploration radio that re-calls the recommendation interface on each replenish, learns from implicit feedback over time, and only enqueues playable tracks.

**Architecture:** Add a small radio domain layer in `data/` for persistent feedback history, request planning, queue composition, and replenishment retries. Keep the UI change intentionally small by letting `PlayerViewModel` orchestrate playback while delegating radio decisions to new focused classes.

**Tech Stack:** Kotlin, Android ViewModel, SharedPreferences, Jetpack Compose, OkHttp, JUnit4, MockWebServer

---

## Scope Check

This is a single subsystem plan. The new work stays inside the existing Android player and AI recommendation flow, so one implementation plan is appropriate.

## File Map

### Create

- `android/app/src/main/java/com/twocents/player/data/RadioModels.kt`
  Defines exploration-radio domain models shared by history, planner, engine, and UI integration.
- `android/app/src/main/java/com/twocents/player/data/RadioPorts.kt`
  Declares the small interfaces the replenishment engine depends on for candidate generation and track resolution.
- `android/app/src/main/java/com/twocents/player/data/RadioHistoryStore.kt`
  Persists local implicit feedback and exposes bounded snapshots for radio planning.
- `android/app/src/main/java/com/twocents/player/data/RadioRecommendationPlanner.kt`
  Converts favorites, local history, and session state into a structured recommendation request.
- `android/app/src/main/java/com/twocents/player/data/RadioQueueComposer.kt`
  Deduplicates and orders playable radio candidates into a stable "safe -> adjacent -> surprise -> recovery" slice.
- `android/app/src/main/java/com/twocents/player/data/RadioReplenishmentEngine.kt`
  Coordinates planner, AI candidate requests, best-match lookup, playable resolution, retries, and degraded fallback.
- `android/app/src/main/java/com/twocents/player/data/RadioPlaybackFeedback.kt`
  Pure helper for classifying completion and skip events into feedback types.
- `android/app/src/test/java/com/twocents/player/data/RadioHistoryStoreTest.kt`
- `android/app/src/test/java/com/twocents/player/data/RadioRecommendationPlannerTest.kt`
- `android/app/src/test/java/com/twocents/player/data/RadioQueueComposerTest.kt`
- `android/app/src/test/java/com/twocents/player/data/AiRecommendationRepositoryTest.kt`
- `android/app/src/test/java/com/twocents/player/data/RadioReplenishmentEngineTest.kt`
- `android/app/src/test/java/com/twocents/player/data/RadioPlaybackFeedbackTest.kt`

### Modify

- `android/app/src/main/java/com/twocents/player/data/AiModels.kt`
  Add bucket metadata to AI suggestions and keep queue items compatible with the new radio flow.
- `android/app/src/main/java/com/twocents/player/data/AiRecommendationRepository.kt`
  Accept the new radio request context and prompt the model for bucketed radio candidates.
- `android/app/src/main/java/com/twocents/player/data/MusicLibraryRepository.kt`
  Implement `RadioTrackLookup` so the new engine can stay testable.
- `android/app/src/main/java/com/twocents/player/ui/AiUiState.kt`
  Add minimal radio session status fields.
- `android/app/src/main/java/com/twocents/player/ui/PlayerViewModel.kt`
  Replace the thin AI session model with the new radio session flow and persistent feedback writes.
- `android/app/src/main/java/com/twocents/player/ui/PlayerScreen.kt`
  Rename the mode to `探索电台` and surface only a lightweight session-level status.

## Task 1: Add Radio Domain Models And Persistent Local History

**Files:**
- Create: `android/app/src/main/java/com/twocents/player/data/RadioModels.kt`
- Create: `android/app/src/main/java/com/twocents/player/data/RadioHistoryStore.kt`
- Test: `android/app/src/test/java/com/twocents/player/data/RadioHistoryStoreTest.kt`

- [ ] **Step 1: Write the failing tests for bounded history persistence**

```kotlin
package com.twocents.player.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RadioHistoryStoreTest {
    private val prefs = FakeSharedPreferences()
    private val store = RadioHistoryStore(
        preferences = prefs,
        clock = { NOW_MS },
    )

    @Test
    fun loadSnapshot_keepsNewestEventsAndPrunesExpiredOnes() {
        repeat(405) { index ->
            store.recordEvent(
                RadioFeedbackEvent(
                    trackId = "track-$index",
                    artistKey = "artist-${index % 4}",
                    type = if (index % 2 == 0) RadioFeedbackType.POSITIVE else RadioFeedbackType.STRONG_NEGATIVE,
                    timestampMs = NOW_MS - index,
                ),
            )
        }
        store.recordEvent(
            RadioFeedbackEvent(
                trackId = "expired-track",
                artistKey = "expired-artist",
                type = RadioFeedbackType.POSITIVE,
                timestampMs = NOW_MS - RadioHistoryStore.MAX_EVENT_AGE_MS - 1L,
            ),
        )

        val snapshot = store.loadSnapshot()

        assertEquals(400, snapshot.events.size)
        assertFalse(snapshot.recentTrackIds.contains("expired-track"))
        assertEquals("track-0", snapshot.recentTrackIds.first())
    }

    @Test
    fun loadSnapshot_splitsPositiveAndNegativeSignals() {
        store.recordEvent(RadioFeedbackEvent("safe-1", "artist-a", RadioFeedbackType.STRONG_POSITIVE, NOW_MS))
        store.recordEvent(RadioFeedbackEvent("skip-1", "artist-b", RadioFeedbackType.STRONG_NEGATIVE, NOW_MS - 10L))
        store.recordEvent(RadioFeedbackEvent("adjacent-1", "artist-c", RadioFeedbackType.REPLAY_POSITIVE, NOW_MS - 20L))

        val snapshot = store.loadSnapshot()

        assertEquals(setOf("safe-1", "adjacent-1"), snapshot.positiveTrackIds)
        assertEquals(setOf("skip-1"), snapshot.negativeTrackIds)
        assertEquals(setOf("artist-a", "artist-c"), snapshot.positiveArtistKeys)
        assertEquals(setOf("artist-b"), snapshot.negativeArtistKeys)
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, String>()

        override fun getString(key: String?, defValue: String?): String? = values[key] ?: defValue
        override fun edit(): SharedPreferences.Editor = Editor(values)
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private class Editor(
            private val values: MutableMap<String, String>,
        ) : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null && value != null) values[key] = value
                return this
            }
            override fun apply() = Unit
            override fun commit(): Boolean = true
            override fun clear(): SharedPreferences.Editor { values.clear(); return this }
            override fun remove(key: String?): SharedPreferences.Editor { if (key != null) values.remove(key); return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
        }
    }

    private companion object {
        const val NOW_MS = 1_710_000_000_000L
    }
}
```

- [ ] **Step 2: Run the new history tests to verify they fail**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew :app:testDebugUnitTest --tests "com.twocents.player.data.RadioHistoryStoreTest"
```

Expected: FAIL with unresolved references for `RadioHistoryStore`, `RadioFeedbackEvent`, and related radio models.

- [ ] **Step 3: Add the radio models and persistent history store**

```kotlin
// android/app/src/main/java/com/twocents/player/data/RadioModels.kt
package com.twocents.player.data

enum class RadioFeedbackType {
    STRONG_POSITIVE,
    POSITIVE,
    MILD_NEGATIVE,
    STRONG_NEGATIVE,
    REPLAY_POSITIVE,
}

enum class RadioBoundaryState {
    BALANCED,
    EXPANDING,
    RECOVERING,
}

enum class RadioCandidateBucket {
    SAFE,
    ADJACENT,
    SURPRISE,
}

data class RadioFeedbackEvent(
    val trackId: String,
    val artistKey: String,
    val type: RadioFeedbackType,
    val timestampMs: Long,
)

data class RadioHistorySnapshot(
    val events: List<RadioFeedbackEvent> = emptyList(),
    val positiveTrackIds: Set<String> = emptySet(),
    val negativeTrackIds: Set<String> = emptySet(),
    val positiveArtistKeys: Set<String> = emptySet(),
    val negativeArtistKeys: Set<String> = emptySet(),
    val recentTrackIds: List<String> = emptyList(),
    val recentArtistKeys: List<String> = emptyList(),
)

data class RadioWaveTargets(
    val safeCount: Int,
    val adjacentCount: Int,
    val surpriseCount: Int,
) {
    val totalCount: Int
        get() = safeCount + adjacentCount + surpriseCount
}

data class RadioSessionState(
    val sessionId: Long,
    val queuedRecommendations: List<AiRecommendedTrack> = emptyList(),
    val playedTrackIds: Set<String> = emptySet(),
    val skippedTrackIds: Set<String> = emptySet(),
    val favoritedTrackIds: Set<String> = emptySet(),
    val boundaryState: RadioBoundaryState = RadioBoundaryState.BALANCED,
    val statusLabel: String = "探索中",
    val isLoadingMore: Boolean = false,
    val lastAutoAppendRemainingCount: Int = -1,
    val consecutiveLowYieldCount: Int = 0,
)
```

```kotlin
// android/app/src/main/java/com/twocents/player/data/RadioHistoryStore.kt
package com.twocents.player.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class RadioHistoryStore(
    private val preferences: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun loadSnapshot(nowMs: Long = clock()): RadioHistorySnapshot {
        val validEvents = readEvents()
            .filter { nowMs - it.timestampMs <= MAX_EVENT_AGE_MS }
            .sortedByDescending(RadioFeedbackEvent::timestampMs)
            .take(MAX_EVENTS)

        return RadioHistorySnapshot(
            events = validEvents,
            positiveTrackIds = validEvents.filter { it.type.isPositive() }.mapTo(linkedSetOf(), RadioFeedbackEvent::trackId),
            negativeTrackIds = validEvents.filter { it.type.isNegative() }.mapTo(linkedSetOf(), RadioFeedbackEvent::trackId),
            positiveArtistKeys = validEvents.filter { it.type.isPositive() }.mapTo(linkedSetOf(), RadioFeedbackEvent::artistKey),
            negativeArtistKeys = validEvents.filter { it.type.isNegative() }.mapTo(linkedSetOf(), RadioFeedbackEvent::artistKey),
            recentTrackIds = validEvents.map(RadioFeedbackEvent::trackId).distinct().take(30),
            recentArtistKeys = validEvents.map(RadioFeedbackEvent::artistKey).distinct().take(20),
        )
    }

    fun recordEvent(event: RadioFeedbackEvent) {
        val updatedEvents = (readEvents() + event)
            .sortedByDescending(RadioFeedbackEvent::timestampMs)
            .filter { clock() - it.timestampMs <= MAX_EVENT_AGE_MS }
            .take(MAX_EVENTS)
        writeEvents(updatedEvents)
    }

    private fun readEvents(): List<RadioFeedbackEvent> {
        val rawJson = preferences.getString(KEY_RADIO_HISTORY, null) ?: return emptyList()
        val array = runCatching { JSONArray(rawJson) }.getOrNull() ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    RadioFeedbackEvent(
                        trackId = item.optString("trackId"),
                        artistKey = item.optString("artistKey"),
                        type = runCatching { RadioFeedbackType.valueOf(item.optString("type")) }
                            .getOrDefault(RadioFeedbackType.POSITIVE),
                        timestampMs = item.optLong("timestampMs"),
                    ),
                )
            }
        }.filter { it.trackId.isNotBlank() && it.artistKey.isNotBlank() }
    }

    private fun writeEvents(events: List<RadioFeedbackEvent>) {
        val array = JSONArray()
        events.forEach { event ->
            array.put(
                JSONObject()
                    .put("trackId", event.trackId)
                    .put("artistKey", event.artistKey)
                    .put("type", event.type.name)
                    .put("timestampMs", event.timestampMs),
            )
        }
        preferences.edit().putString(KEY_RADIO_HISTORY, array.toString()).apply()
    }

    private fun RadioFeedbackType.isPositive(): Boolean {
        return this == RadioFeedbackType.STRONG_POSITIVE ||
            this == RadioFeedbackType.POSITIVE ||
            this == RadioFeedbackType.REPLAY_POSITIVE
    }

    private fun RadioFeedbackType.isNegative(): Boolean {
        return this == RadioFeedbackType.MILD_NEGATIVE || this == RadioFeedbackType.STRONG_NEGATIVE
    }

    companion object {
        const val MAX_EVENTS = 400
        const val MAX_EVENT_AGE_MS = 30L * 24L * 60L * 60L * 1000L
        private const val PREFERENCES_NAME = "two_cents_player"
        private const val KEY_RADIO_HISTORY = "radio_history_events"

        fun fromContext(context: Context): RadioHistoryStore {
            return RadioHistoryStore(
                preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            )
        }
    }
}
```

- [ ] **Step 4: Run the history tests to verify they pass**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew :app:testDebugUnitTest --tests "com.twocents.player.data.RadioHistoryStoreTest"
```

Expected: PASS with 2 tests executed.

- [ ] **Step 5: Commit the radio history foundation**

```bash
cd /Users/daddybh/Code/2CentsPlayer
git add android/app/src/main/java/com/twocents/player/data/RadioModels.kt \
        android/app/src/main/java/com/twocents/player/data/RadioHistoryStore.kt \
        android/app/src/test/java/com/twocents/player/data/RadioHistoryStoreTest.kt
git commit -m "feat: add exploration radio history foundation"
```

## Task 2: Add Request Planning And Queue Composition

**Files:**
- Create: `android/app/src/main/java/com/twocents/player/data/RadioRecommendationPlanner.kt`
- Create: `android/app/src/main/java/com/twocents/player/data/RadioQueueComposer.kt`
- Test: `android/app/src/test/java/com/twocents/player/data/RadioRecommendationPlannerTest.kt`
- Test: `android/app/src/test/java/com/twocents/player/data/RadioQueueComposerTest.kt`

- [ ] **Step 1: Write the failing planner and composer tests**

```kotlin
package com.twocents.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioRecommendationPlannerTest {
    private val planner = RadioRecommendationPlanner()

    @Test
    fun buildRequest_usesBalancedWaveByDefault() {
        val request = planner.buildRequest(
            favorites = listOf(track("netease:1", "晴天", "周杰伦")),
            history = RadioHistorySnapshot(),
            session = RadioSessionState(sessionId = 1L),
        )

        assertEquals(RadioBoundaryState.BALANCED, request.boundaryState)
        assertEquals(RadioWaveTargets(safeCount = 4, adjacentCount = 2, surpriseCount = 1), request.waveTargets)
        assertEquals(12, request.rawCandidateLimit)
    }

    @Test
    fun buildRequest_shrinksAfterTwoStrongNegatives() {
        val history = RadioHistorySnapshot(
            events = listOf(
                RadioFeedbackEvent("a", "artist-a", RadioFeedbackType.STRONG_NEGATIVE, 100L),
                RadioFeedbackEvent("b", "artist-b", RadioFeedbackType.STRONG_NEGATIVE, 90L),
            ),
        )

        val request = planner.buildRequest(
            favorites = listOf(track("netease:1", "晴天", "周杰伦")),
            history = history,
            session = RadioSessionState(sessionId = 2L),
        )

        assertEquals(RadioBoundaryState.RECOVERING, request.boundaryState)
        assertEquals(RadioWaveTargets(safeCount = 5, adjacentCount = 1, surpriseCount = 0), request.waveTargets)
    }

    private fun track(id: String, title: String, artist: String) = Track(
        id = id,
        source = TrackSource.NETEASE,
        sourceId = id.substringAfter(':'),
        title = title,
        artist = artist,
    )
}
```

```kotlin
package com.twocents.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioQueueComposerTest {
    private val composer = RadioQueueComposer()

    @Test
    fun compose_filtersDuplicateArtistsAndKeepsSurpriseAwayFromFirstSlot() {
        val existing = listOf(recommendation("netease:1", "晴天", "周杰伦"))
        val candidates = listOf(
            resolvedCandidate("netease:2", "七里香", "周杰伦", RadioCandidateBucket.SAFE),
            resolvedCandidate("netease:3", "最长的电影", "周杰伦", RadioCandidateBucket.ADJACENT),
            resolvedCandidate("kuwo:4", "Space Song", "Beach House", RadioCandidateBucket.SURPRISE),
            resolvedCandidate("kuwo:5", "Midnight City", "M83", RadioCandidateBucket.SAFE),
        )

        val result = composer.compose(
            existingQueue = existing,
            candidates = candidates,
            boundaryState = RadioBoundaryState.BALANCED,
        )

        assertEquals(listOf("kuwo:5", "kuwo:4"), result.map { it.recommendation.track.id })
        assertTrue(result.first().bucket != RadioCandidateBucket.SURPRISE)
    }

    private fun recommendation(id: String, title: String, artist: String) = AiRecommendedTrack(
        track = Track(id = id, source = TrackSource.NETEASE, sourceId = id.substringAfter(':'), title = title, artist = artist),
    )

    private fun resolvedCandidate(id: String, title: String, artist: String, bucket: RadioCandidateBucket) =
        RadioResolvedCandidate(
            recommendation = AiRecommendedTrack(
                track = Track(id = id, source = TrackSource.KUWO, sourceId = id.substringAfter(':'), title = title, artist = artist, audioUrl = "https://example.com/$id.mp3"),
            ),
            bucket = bucket,
        )
}
```

- [ ] **Step 2: Run the new planner and composer tests to verify they fail**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew :app:testDebugUnitTest --tests "com.twocents.player.data.RadioRecommendationPlannerTest" \
                                 --tests "com.twocents.player.data.RadioQueueComposerTest"
```

Expected: FAIL with unresolved references for `RadioRecommendationPlanner`, `RadioQueueComposer`, `RadioRecommendationRequest`, and `RadioResolvedCandidate`.

- [ ] **Step 3: Implement planner and composer**

```kotlin
// android/app/src/main/java/com/twocents/player/data/RadioRecommendationPlanner.kt
package com.twocents.player.data

data class RadioRecommendationRequest(
    val boundaryState: RadioBoundaryState,
    val waveTargets: RadioWaveTargets,
    val rawCandidateLimit: Int,
    val favoriteSeeds: List<Track>,
    val positiveTrackIds: Set<String>,
    val negativeTrackIds: Set<String>,
    val avoidTrackIds: Set<String>,
    val avoidArtistKeys: Set<String>,
)

class RadioRecommendationPlanner {
    fun buildRequest(
        favorites: List<Track>,
        history: RadioHistorySnapshot,
        session: RadioSessionState,
    ): RadioRecommendationRequest {
        val boundaryState = when {
            history.events.take(3).count { it.type == RadioFeedbackType.STRONG_NEGATIVE } >= 2 -> RadioBoundaryState.RECOVERING
            session.favoritedTrackIds.isNotEmpty() -> RadioBoundaryState.EXPANDING
            else -> RadioBoundaryState.BALANCED
        }

        val waveTargets = when (boundaryState) {
            RadioBoundaryState.BALANCED -> RadioWaveTargets(4, 2, 1)
            RadioBoundaryState.EXPANDING -> RadioWaveTargets(3, 3, 1)
            RadioBoundaryState.RECOVERING -> RadioWaveTargets(5, 1, 0)
        }

        return RadioRecommendationRequest(
            boundaryState = boundaryState,
            waveTargets = waveTargets,
            rawCandidateLimit = 12,
            favoriteSeeds = favorites.take(30),
            positiveTrackIds = history.positiveTrackIds + session.favoritedTrackIds,
            negativeTrackIds = history.negativeTrackIds + session.skippedTrackIds,
            avoidTrackIds = session.queuedRecommendations.mapTo(linkedSetOf()) { it.track.id } + session.playedTrackIds,
            avoidArtistKeys = history.recentArtistKeys.toSet(),
        )
    }
}
```

```kotlin
// android/app/src/main/java/com/twocents/player/data/RadioQueueComposer.kt
package com.twocents.player.data

data class RadioResolvedCandidate(
    val recommendation: AiRecommendedTrack,
    val bucket: RadioCandidateBucket,
)

class RadioQueueComposer {
    fun compose(
        existingQueue: List<AiRecommendedTrack>,
        candidates: List<RadioResolvedCandidate>,
        boundaryState: RadioBoundaryState,
    ): List<RadioResolvedCandidate> {
        val seenTrackIds = existingQueue.mapTo(mutableSetOf()) { it.track.id }
        val seenArtistKeys = existingQueue.mapTo(mutableSetOf()) { artistKey(it.track.artist) }
        val orderedBuckets = when (boundaryState) {
            RadioBoundaryState.BALANCED -> listOf(RadioCandidateBucket.SAFE, RadioCandidateBucket.ADJACENT, RadioCandidateBucket.SAFE, RadioCandidateBucket.SURPRISE, RadioCandidateBucket.SAFE, RadioCandidateBucket.ADJACENT)
            RadioBoundaryState.EXPANDING -> listOf(RadioCandidateBucket.SAFE, RadioCandidateBucket.ADJACENT, RadioCandidateBucket.SURPRISE, RadioCandidateBucket.ADJACENT, RadioCandidateBucket.SAFE, RadioCandidateBucket.SAFE)
            RadioBoundaryState.RECOVERING -> listOf(RadioCandidateBucket.SAFE, RadioCandidateBucket.SAFE, RadioCandidateBucket.ADJACENT, RadioCandidateBucket.SAFE)
        }

        val grouped = candidates.groupBy { it.bucket }.mapValues { (_, bucketItems) ->
            bucketItems.filter { candidate ->
                val track = candidate.recommendation.track
                track.audioUrl.isNotBlank() &&
                    seenTrackIds.add(track.id) &&
                    seenArtistKeys.add(artistKey(track.artist))
            }.toMutableList()
        }

        return buildList {
            orderedBuckets.forEach { bucket ->
                val next = grouped[bucket]?.removeFirstOrNull() ?: return@forEach
                add(next)
            }
        }
    }

    private fun artistKey(artist: String): String {
        return artist.split(',', '、', '/', '&')
            .firstOrNull()
            .orEmpty()
            .trim()
            .lowercase()
    }
}
```

- [ ] **Step 4: Run the planner and composer tests to verify they pass**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew :app:testDebugUnitTest --tests "com.twocents.player.data.RadioRecommendationPlannerTest" \
                                 --tests "com.twocents.player.data.RadioQueueComposerTest"
```

Expected: PASS with 4 tests executed.

- [ ] **Step 5: Commit planner and composer**

```bash
cd /Users/daddybh/Code/2CentsPlayer
git add android/app/src/main/java/com/twocents/player/data/RadioRecommendationPlanner.kt \
        android/app/src/main/java/com/twocents/player/data/RadioQueueComposer.kt \
        android/app/src/test/java/com/twocents/player/data/RadioRecommendationPlannerTest.kt \
        android/app/src/test/java/com/twocents/player/data/RadioQueueComposerTest.kt
git commit -m "feat: add exploration radio planning and composition"
```

## Task 3: Add Bucketed AI Candidate Requests

**Files:**
- Modify: `android/app/src/main/java/com/twocents/player/data/AiModels.kt`
- Modify: `android/app/src/main/java/com/twocents/player/data/AiRecommendationRepository.kt`
- Test: `android/app/src/test/java/com/twocents/player/data/AiRecommendationRepositoryTest.kt`

- [ ] **Step 1: Write the failing repository test for bucketed radio requests**

```kotlin
package com.twocents.player.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRecommendationRepositoryTest {
    @Test
    fun requestRadioCandidates_postsWaveTargetsAndParsesBuckets() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                {"choices":[{"message":{"content":"{\"recommendations\":[{\"title\":\"Space Song\",\"artist\":\"Beach House\",\"reason\":\"同样的夜行感\",\"bucket\":\"adjacent\"},{\"title\":\"Midnight City\",\"artist\":\"M83\",\"reason\":\"稳住氛围\",\"bucket\":\"safe\"}]}"}}]}
                """.trimIndent(),
            ),
        )

        val repository = AiRecommendationRepository()
        val settings = AiServiceConfig(
            endpoint = server.url("/v1").toString(),
            model = "gpt-test",
            accessKey = "test-key",
        )
        val request = RadioRecommendationRequest(
            boundaryState = RadioBoundaryState.BALANCED,
            waveTargets = RadioWaveTargets(4, 2, 1),
            rawCandidateLimit = 12,
            favoriteSeeds = listOf(Track(id = "netease:1", source = TrackSource.NETEASE, sourceId = "1", title = "晴天", artist = "周杰伦")),
            positiveTrackIds = setOf("netease:1"),
            negativeTrackIds = setOf("kuwo:2"),
            avoidTrackIds = setOf("netease:3"),
            avoidArtistKeys = setOf("周杰伦"),
        )

        val result = repository.requestRadioCandidates(settings, request)
        val postedBody = server.takeRequest().body.readUtf8()

        assertTrue(postedBody.contains("safe=4 adjacent=2 surprise=1"))
        assertTrue(postedBody.contains("Avoid track ids"))
        assertEquals(listOf(RadioCandidateBucket.ADJACENT, RadioCandidateBucket.SAFE), result.map { it.bucket })
    }
}
```

- [ ] **Step 2: Run the repository test to verify it fails**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew :app:testDebugUnitTest --tests "com.twocents.player.data.AiRecommendationRepositoryTest"
```

Expected: FAIL because `AiRecommendationRepository` does not yet expose `requestRadioCandidates`, and `AiSuggestedTrack` has no `bucket`.

- [ ] **Step 3: Add bucket support and the new request method**

```kotlin
// android/app/src/main/java/com/twocents/player/data/AiModels.kt
data class AiSuggestedTrack(
    val title: String,
    val artist: String,
    val reason: String = "",
    val bucket: RadioCandidateBucket = RadioCandidateBucket.SAFE,
)
```

```kotlin
// android/app/src/main/java/com/twocents/player/data/AiRecommendationRepository.kt
class AiRecommendationRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build(),
) : RadioCandidateSource {

    override fun requestRadioCandidates(
        settings: AiServiceConfig,
        request: RadioRecommendationRequest,
    ): List<AiSuggestedTrack> {
        val requestBody = JSONObject()
            .put("model", settings.model.trim())
            .put("stream", false)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", buildRadioSystemPrompt(request)))
                    .put(JSONObject().put("role", "user").put("content", buildRadioUserPrompt(request))),
            )

        val httpRequest = Request.Builder()
            .url(settings.chatCompletionsUrl())
            .addHeader("Authorization", "Bearer ${settings.accessKey.trim()}")
            .post(requestBody.toString().toByteArray(Charsets.UTF_8).toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .build()

        client.newCall(httpRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(extractErrorMessage(body, response.code))
            val root = JSONObject(body)
            val content = extractAssistantContent(root)
            return parseRecommendations(content).take(request.rawCandidateLimit)
        }
    }

    private fun buildRadioSystemPrompt(request: RadioRecommendationRequest): String = """
        你是一个探索电台推荐助手。
        按照 safe=${request.waveTargets.safeCount} adjacent=${request.waveTargets.adjacentCount} surprise=${request.waveTargets.surpriseCount} 组织候选。
        只返回 JSON，不要 Markdown，不要解释。
        JSON 格式：
        {"recommendations":[{"title":"歌曲名","artist":"歌手名","reason":"一句中文理由","bucket":"safe|adjacent|surprise"}]}
    """.trimIndent()

    private fun buildRadioUserPrompt(request: RadioRecommendationRequest): String = """
        Favorite seeds:
        ${request.favoriteSeeds.joinToString("\n") { "- ${it.title} - ${it.artist}" }}

        Positive track ids:
        ${request.positiveTrackIds.joinToString(", ").ifBlank { "none" }}

        Negative track ids:
        ${request.negativeTrackIds.joinToString(", ").ifBlank { "none" }}

        Avoid track ids:
        ${request.avoidTrackIds.joinToString(", ").ifBlank { "none" }}

        Avoid artist keys:
        ${request.avoidArtistKeys.joinToString(", ").ifBlank { "none" }}
    """.trimIndent()

    private fun parseRecommendations(rawContent: String): List<AiSuggestedTrack> {
        val normalizedContent = rawContent
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val payload = sequenceOf(
            normalizedContent,
            extractJsonObject(normalizedContent),
        ).mapNotNull { candidate ->
            candidate?.takeIf { it.isNotBlank() }?.let {
                runCatching { JSONObject(it) }.getOrNull()
            }
        }.firstOrNull() ?: throw IOException("AI 返回的推荐内容不是可解析的 JSON。")

        val items = payload.optJSONArray("recommendations")
            ?: throw IOException("AI 返回缺少 recommendations 字段。")

        val seenKeys = mutableSetOf<String>()
        return buildList(items.length()) {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val title = item.optString("title").trim()
                val artist = item.optString("artist").trim()
                val reason = item.optString("reason").trim()
                if (title.isBlank() || artist.isBlank()) continue

                val dedupeKey = "${title.lowercase()}::${artist.lowercase()}"
                if (!seenKeys.add(dedupeKey)) continue

                add(
                    AiSuggestedTrack(
                        title = title,
                        artist = artist,
                        reason = reason,
                        bucket = when (item.optString("bucket").trim().lowercase()) {
                            "adjacent" -> RadioCandidateBucket.ADJACENT
                            "surprise" -> RadioCandidateBucket.SURPRISE
                            else -> RadioCandidateBucket.SAFE
                        },
                    ),
                )
            }
        }
    }
}
```

- [ ] **Step 4: Run the repository test to verify it passes**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew :app:testDebugUnitTest --tests "com.twocents.player.data.AiRecommendationRepositoryTest"
```

Expected: PASS with 1 test executed.

- [ ] **Step 5: Commit bucketed radio candidate requests**

```bash
cd /Users/daddybh/Code/2CentsPlayer
git add android/app/src/main/java/com/twocents/player/data/AiModels.kt \
        android/app/src/main/java/com/twocents/player/data/AiRecommendationRepository.kt \
        android/app/src/test/java/com/twocents/player/data/AiRecommendationRepositoryTest.kt
git commit -m "feat: add bucketed exploration radio candidate requests"
```

## Task 4: Add Playable-Only Replenishment With Retries

**Files:**
- Create: `android/app/src/main/java/com/twocents/player/data/RadioPorts.kt`
- Create: `android/app/src/main/java/com/twocents/player/data/RadioReplenishmentEngine.kt`
- Modify: `android/app/src/main/java/com/twocents/player/data/MusicLibraryRepository.kt`
- Test: `android/app/src/test/java/com/twocents/player/data/RadioReplenishmentEngineTest.kt`

- [ ] **Step 1: Write the failing replenishment engine tests**

```kotlin
package com.twocents.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioReplenishmentEngineTest {
    @Test
    fun replenish_retriesUntilMinimumPlayableCountIsReached() {
        val candidateSource = FakeRadioCandidateSource(
            responses = listOf(
                listOf(AiSuggestedTrack("Song A", "Artist A", bucket = RadioCandidateBucket.SAFE)),
                listOf(
                    AiSuggestedTrack("Song B", "Artist B", bucket = RadioCandidateBucket.SAFE),
                    AiSuggestedTrack("Song C", "Artist C", bucket = RadioCandidateBucket.ADJACENT),
                    AiSuggestedTrack("Song D", "Artist D", bucket = RadioCandidateBucket.SURPRISE),
                    AiSuggestedTrack("Song E", "Artist E", bucket = RadioCandidateBucket.SAFE),
                ),
            ),
        )
        val trackLookup = FakeRadioTrackLookup(
            matchedTracks = mapOf(
                "Song A::Artist A" to track("netease:1", "Song A", "Artist A", playable = false),
                "Song B::Artist B" to track("netease:2", "Song B", "Artist B"),
                "Song C::Artist C" to track("netease:3", "Song C", "Artist C"),
                "Song D::Artist D" to track("netease:4", "Song D", "Artist D"),
                "Song E::Artist E" to track("netease:5", "Song E", "Artist E"),
            ),
        )
        val engine = RadioReplenishmentEngine(candidateSource, trackLookup)

        val result = engine.replenish(
            settings = AiServiceConfig(endpoint = "https://example.com/v1", model = "demo", accessKey = "key"),
            favorites = listOf(track("netease:seed", "晴天", "周杰伦")),
            history = RadioHistorySnapshot(),
            session = RadioSessionState(sessionId = 1L),
        )

        assertEquals(2, candidateSource.callCount)
        assertTrue(result.appendedRecommendations.size >= RadioReplenishmentEngine.MIN_SAFE_APPEND)
        assertTrue(result.appendedRecommendations.all { it.track.audioUrl.isNotBlank() })
    }

    @Test
    fun replenish_marksSessionDegradedWhenAllRetriesFail() {
        val engine = RadioReplenishmentEngine(
            candidateSource = FakeRadioCandidateSource(responses = List(3) { emptyList() }),
            trackLookup = FakeRadioTrackLookup(),
        )

        val result = engine.replenish(
            settings = AiServiceConfig(endpoint = "https://example.com/v1", model = "demo", accessKey = "key"),
            favorites = listOf(track("netease:seed", "晴天", "周杰伦")),
            history = RadioHistorySnapshot(),
            session = RadioSessionState(sessionId = 2L),
        )

        assertTrue(result.appendedRecommendations.isEmpty())
        assertEquals(RadioBoundaryState.RECOVERING, result.updatedSession.boundaryState)
        assertEquals("回到熟悉区", result.updatedSession.statusLabel)
    }

    private fun track(id: String, title: String, artist: String, playable: Boolean = true) = Track(
        id = id,
        source = TrackSource.NETEASE,
        sourceId = id.substringAfter(':'),
        title = title,
        artist = artist,
        audioUrl = if (playable) "https://example.com/$id.mp3" else "",
    )

    private class FakeRadioCandidateSource(
        private val responses: List<List<AiSuggestedTrack>>,
    ) : RadioCandidateSource {
        var callCount: Int = 0
            private set

        override fun requestRadioCandidates(
            settings: AiServiceConfig,
            request: RadioRecommendationRequest,
        ): List<AiSuggestedTrack> {
            val response = responses.getOrElse(callCount) { emptyList() }
            callCount += 1
            return response
        }
    }

    private class FakeRadioTrackLookup(
        private val matchedTracks: Map<String, Track> = emptyMap(),
    ) : RadioTrackLookup {
        override fun findBestMatchTrack(title: String, artist: String): Track? {
            return matchedTracks["$title::$artist"]
        }

        override fun resolvePlayableTracks(tracks: List<Track>): List<Track> {
            return tracks.map { track ->
                matchedTracks["${track.title}::${track.artist}"] ?: track
            }
        }
    }
}
```

- [ ] **Step 2: Run the replenishment engine tests to verify they fail**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew :app:testDebugUnitTest --tests "com.twocents.player.data.RadioReplenishmentEngineTest"
```

Expected: FAIL because `RadioReplenishmentEngine`, `RadioCandidateSource`, and `RadioTrackLookup` do not exist yet.

- [ ] **Step 3: Implement the ports and replenishment engine**

```kotlin
// android/app/src/main/java/com/twocents/player/data/RadioPorts.kt
package com.twocents.player.data

interface RadioCandidateSource {
    fun requestRadioCandidates(
        settings: AiServiceConfig,
        request: RadioRecommendationRequest,
    ): List<AiSuggestedTrack>
}

interface RadioTrackLookup {
    fun findBestMatchTrack(title: String, artist: String = ""): Track?
    fun resolvePlayableTracks(tracks: List<Track>): List<Track>
}
```

```kotlin
// android/app/src/main/java/com/twocents/player/data/RadioReplenishmentEngine.kt
package com.twocents.player.data

data class RadioReplenishmentResult(
    val appendedRecommendations: List<AiRecommendedTrack>,
    val suggestionCount: Int,
    val updatedSession: RadioSessionState,
)

class RadioReplenishmentEngine(
    private val candidateSource: RadioCandidateSource,
    private val trackLookup: RadioTrackLookup,
    private val planner: RadioRecommendationPlanner = RadioRecommendationPlanner(),
    private val composer: RadioQueueComposer = RadioQueueComposer(),
) {
    fun replenish(
        settings: AiServiceConfig,
        favorites: List<Track>,
        history: RadioHistorySnapshot,
        session: RadioSessionState,
    ): RadioReplenishmentResult {
        var attempts = 0
        var workingSession = session
        var suggestionCount = 0
        var appended: List<RadioResolvedCandidate> = emptyList()

        while (attempts < MAX_ATTEMPTS && appended.size < MIN_SAFE_APPEND) {
            val request = planner.buildRequest(favorites, history, workingSession)
            workingSession = workingSession.copy(
                boundaryState = request.boundaryState,
                statusLabel = when (request.boundaryState) {
                    RadioBoundaryState.BALANCED -> "探索中"
                    RadioBoundaryState.EXPANDING -> "正在扩圈"
                    RadioBoundaryState.RECOVERING -> "回到熟悉区"
                },
            )
            val suggestions = candidateSource.requestRadioCandidates(settings, request)
            suggestionCount += suggestions.size

            val matched = suggestions.mapNotNull { suggestion ->
                trackLookup.findBestMatchTrack(suggestion.title, suggestion.artist)?.let { matchedTrack ->
                    RadioResolvedCandidate(
                        recommendation = AiRecommendedTrack(track = matchedTrack, reason = suggestion.reason),
                        bucket = suggestion.bucket,
                    )
                }
            }

            val resolvedMap = trackLookup.resolvePlayableTracks(
                matched.map { it.recommendation.track.copy(audioUrl = "") },
            ).associateBy { it.id }

            val playable = matched.mapNotNull { candidate ->
                val resolvedTrack = resolvedMap[candidate.recommendation.track.id] ?: return@mapNotNull null
                if (resolvedTrack.audioUrl.isBlank()) return@mapNotNull null
                candidate.copy(recommendation = candidate.recommendation.copy(track = resolvedTrack))
            }

            appended = composer.compose(
                existingQueue = workingSession.queuedRecommendations,
                candidates = playable,
                boundaryState = workingSession.boundaryState,
            )

            if (appended.size >= MIN_SAFE_APPEND) break

            workingSession = workingSession.copy(
                boundaryState = RadioBoundaryState.RECOVERING,
                statusLabel = "回到熟悉区",
                consecutiveLowYieldCount = workingSession.consecutiveLowYieldCount + 1,
            )
            attempts += 1
        }

        val nextQueue = workingSession.queuedRecommendations + appended.map { it.recommendation }
        return RadioReplenishmentResult(
            appendedRecommendations = appended.map { it.recommendation },
            suggestionCount = suggestionCount,
            updatedSession = workingSession.copy(
                queuedRecommendations = nextQueue,
                statusLabel = if (appended.isEmpty() && workingSession.statusLabel == "回到熟悉区") "回到熟悉区" else workingSession.statusLabel,
            ),
        )
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val MIN_SAFE_APPEND = 4
    }
}
```

```kotlin
// android/app/src/main/java/com/twocents/player/data/MusicLibraryRepository.kt
class MusicLibraryRepository(
    private val neteaseRepository: MusicSourceRepository = NeteaseSearchRepository(),
    private val kuwoRepository: MusicSourceRepository = KuwoSearchRepository(),
) : RadioTrackLookup {
    // Keep current implementations; only add the interface to expose them to the engine.
}
```

- [ ] **Step 4: Run the replenishment engine tests to verify they pass**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew :app:testDebugUnitTest --tests "com.twocents.player.data.RadioReplenishmentEngineTest"
```

Expected: PASS with 2 tests executed.

- [ ] **Step 5: Commit playable-only replenishment**

```bash
cd /Users/daddybh/Code/2CentsPlayer
git add android/app/src/main/java/com/twocents/player/data/RadioPorts.kt \
        android/app/src/main/java/com/twocents/player/data/RadioReplenishmentEngine.kt \
        android/app/src/main/java/com/twocents/player/data/MusicLibraryRepository.kt \
        android/app/src/test/java/com/twocents/player/data/RadioReplenishmentEngineTest.kt
git commit -m "feat: add exploration radio replenishment engine"
```

## Task 5: Integrate The Radio Engine Into Playback And Keep UI Minimal

**Files:**
- Create: `android/app/src/main/java/com/twocents/player/data/RadioPlaybackFeedback.kt`
- Modify: `android/app/src/main/java/com/twocents/player/ui/AiUiState.kt`
- Modify: `android/app/src/main/java/com/twocents/player/ui/PlayerViewModel.kt`
- Modify: `android/app/src/main/java/com/twocents/player/ui/PlayerScreen.kt`
- Test: `android/app/src/test/java/com/twocents/player/data/RadioPlaybackFeedbackTest.kt`

- [ ] **Step 1: Write the failing playback feedback tests**

```kotlin
package com.twocents.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioPlaybackFeedbackTest {
    @Test
    fun classifySkip_returnsStrongNegativeForVeryEarlySkip() {
        val result = RadioPlaybackFeedback.classifySkip(
            positionMs = 12_000L,
            durationMs = 180_000L,
        )

        assertEquals(RadioFeedbackType.STRONG_NEGATIVE, result)
    }

    @Test
    fun classifyCompletion_returnsPositiveWhenTrackMostlyPlayed() {
        val result = RadioPlaybackFeedback.classifyCompletion(
            positionMs = 145_000L,
            durationMs = 180_000L,
        )

        assertEquals(RadioFeedbackType.POSITIVE, result)
    }

    @Test
    fun classifyCompletion_returnsNullForShortAbandon() {
        val result = RadioPlaybackFeedback.classifyCompletion(
            positionMs = 25_000L,
            durationMs = 180_000L,
        )

        assertNull(result)
    }
}
```

- [ ] **Step 2: Run the playback feedback tests to verify they fail**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew :app:testDebugUnitTest --tests "com.twocents.player.data.RadioPlaybackFeedbackTest"
```

Expected: FAIL because `RadioPlaybackFeedback` does not exist yet.

- [ ] **Step 3: Add playback feedback helpers and wire the new radio flow into the UI layer**

```kotlin
// android/app/src/main/java/com/twocents/player/data/RadioPlaybackFeedback.kt
package com.twocents.player.data

object RadioPlaybackFeedback {
    fun classifySkip(positionMs: Long, durationMs: Long): RadioFeedbackType {
        val safeDuration = durationMs.coerceAtLeast(1L)
        return if (positionMs <= 30_000L || positionMs * 100L / safeDuration <= 25L) {
            RadioFeedbackType.STRONG_NEGATIVE
        } else {
            RadioFeedbackType.MILD_NEGATIVE
        }
    }

    fun classifyCompletion(positionMs: Long, durationMs: Long): RadioFeedbackType? {
        val safeDuration = durationMs.coerceAtLeast(1L)
        return if (positionMs >= 120_000L || positionMs * 100L / safeDuration >= 70L) {
            RadioFeedbackType.POSITIVE
        } else {
            null
        }
    }
}
```

```kotlin
// android/app/src/main/java/com/twocents/player/ui/AiUiState.kt
data class AiRecommendationUiState(
    val isActive: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val tracks: List<AiRecommendedTrack> = emptyList(),
    val errorMessage: String? = null,
    val sourceFavoriteCount: Int = 0,
    val suggestionCount: Int = 0,
    val skippedCount: Int = 0,
    val statusLabel: String? = null,
    val isDegraded: Boolean = false,
)
```

```kotlin
// android/app/src/main/java/com/twocents/player/ui/PlayerViewModel.kt
private val radioHistoryStore = RadioHistoryStore.fromContext(application)
private val radioPlanner = RadioRecommendationPlanner()
private val radioQueueComposer = RadioQueueComposer()
private val radioEngine = RadioReplenishmentEngine(
    candidateSource = aiRecommendationRepository,
    trackLookup = musicLibraryRepository,
    planner = radioPlanner,
    composer = radioQueueComposer,
)

private var radioSession: RadioSessionState? = null
private var latestCompletedRadioTrackId: String? = null

fun toggleHeartMode() {
    if (activePlaybackSource == PlaybackSource.AI) {
        applyPlaybackSource(PlaybackSource.REGULAR)
    } else {
        startExplorationRadio()
    }
}

private fun startExplorationRadio() {
    val config = buildAiServiceConfig()
    if (!config.isComplete) {
        aiRecommendationState = aiRecommendationState.copy(errorMessage = "先在设置里填好 AI 接口、模型和 Access Key。")
        openAiSettings()
        return
    }
    val favorites = favoritesState.tracks.map(::normalizeTrack)
    if (favorites.isEmpty()) {
        clearAiRecommendations(errorMessage = "先收藏几首歌，再生成 AI 推荐。")
        return
    }

    viewModelScope.launch {
        aiRecommendationState = aiRecommendationState.copy(isLoading = true, errorMessage = null, statusLabel = "探索中")
        val result = withContext(Dispatchers.IO) {
            radioEngine.replenish(
                settings = config,
                favorites = favorites,
                history = radioHistoryStore.loadSnapshot(),
                session = RadioSessionState(sessionId = nextAiSessionId(), statusLabel = "探索中"),
            )
        }
        radioSession = result.updatedSession
        val queue = result.updatedSession.queuedRecommendations
        aiRecommendationState = aiRecommendationState.copy(
            isLoading = false,
            isActive = queue.isNotEmpty(),
            tracks = queue,
            suggestionCount = result.suggestionCount,
            statusLabel = result.updatedSession.statusLabel,
            isDegraded = queue.isEmpty(),
            errorMessage = if (queue.isEmpty()) "这一轮没有拿到可播放歌曲。" else null,
        )
        if (queue.isNotEmpty()) {
            playResolvedAiRecommendations(queue)
        }
    }
}

private fun recordCurrentAiTrackSkipped() {
    if (activePlaybackSource != PlaybackSource.AI) return
    val currentTrack = playbackState.currentTrack ?: return
    val session = radioSession ?: return
    val feedbackType = RadioPlaybackFeedback.classifySkip(
        positionMs = playbackState.currentPositionMs,
        durationMs = currentTrack.durationMs,
    )
    radioHistoryStore.recordEvent(
        RadioFeedbackEvent(
            trackId = currentTrack.id,
            artistKey = currentTrack.artist.lowercase(),
            type = feedbackType,
            timestampMs = System.currentTimeMillis(),
        ),
    )
    radioSession = session.copy(skippedTrackIds = session.skippedTrackIds + currentTrack.id)
}

fun toggleFavorite(track: Track) {
    val normalizedTrack = normalizeTrack(track)
    val existingFavorites = favoritesState.tracks.filterNot { it.id == normalizedTrack.id }
    val updatedFavorites = if (normalizedTrack.isFavorite) {
        existingFavorites
    } else {
        listOf(normalizedTrack.copy(isFavorite = true, audioUrl = "")) + existingFavorites
    }

    favoritesStore.saveFavorites(updatedFavorites)
    favoritesState = favoritesState.copy(tracks = updatedFavorites)
    syncFavoriteFlags()

    if (activePlaybackSource == PlaybackSource.AI && !normalizedTrack.isFavorite) {
        radioHistoryStore.recordEvent(
            RadioFeedbackEvent(
                trackId = normalizedTrack.id,
                artistKey = normalizedTrack.artist.lowercase(),
                type = RadioFeedbackType.STRONG_POSITIVE,
                timestampMs = System.currentTimeMillis(),
            ),
        )
        radioSession = radioSession?.copy(
            favoritedTrackIds = radioSession?.favoritedTrackIds.orEmpty() + normalizedTrack.id,
        )
    }

    if (updatedFavorites.isEmpty()) {
        clearAiRecommendations(errorMessage = "先收藏几首歌，再生成 AI 推荐。")
    }
}

private fun recordRadioReplay(track: Track) {
    if (activePlaybackSource != PlaybackSource.AI) return
    if (radioSession?.playedTrackIds?.contains(track.id) != true) return
    radioHistoryStore.recordEvent(
        RadioFeedbackEvent(
            trackId = track.id,
            artistKey = track.artist.lowercase(),
            type = RadioFeedbackType.REPLAY_POSITIVE,
            timestampMs = System.currentTimeMillis(),
        ),
    )
}

private fun recordRadioCompletionIfNeeded(previousTrack: Track?) {
    if (activePlaybackSource != PlaybackSource.AI) return
    val track = previousTrack ?: return
    if (track.id == latestCompletedRadioTrackId) return
    val feedbackType = RadioPlaybackFeedback.classifyCompletion(
        positionMs = playbackState.currentPositionMs,
        durationMs = track.durationMs,
    ) ?: return
    radioHistoryStore.recordEvent(
        RadioFeedbackEvent(
            trackId = track.id,
            artistKey = track.artist.lowercase(),
            type = feedbackType,
            timestampMs = System.currentTimeMillis(),
        ),
    )
    latestCompletedRadioTrackId = track.id
    radioSession = radioSession?.copy(
        playedTrackIds = radioSession?.playedTrackIds.orEmpty() + track.id,
    )
}

fun onPlayerQueueChanged(
    queue: List<Track>,
    currentIndex: Int,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
) {
    val previousTrack = playbackState.currentTrack
    recordRadioCompletionIfNeeded(previousTrack)

    if (queue.isEmpty()) {
        playbackState = playbackState.copy(
            isPlaying = false,
            isPreparing = false,
            currentPositionMs = positionMs.coerceAtLeast(0L),
        )
        if (activePlaybackSource == PlaybackSource.AI) {
            applyPlaybackSource(PlaybackSource.REGULAR)
            aiRecommendationState = aiRecommendationState.copy(
                statusLabel = null,
                isDegraded = true,
                errorMessage = "探索电台已结束。",
            )
        }
        return
    }

    val normalizedQueue = queue.map(::normalizeTrack)
    val safeIndex = currentIndex.coerceIn(0, normalizedQueue.lastIndex)
    val currentTrack = normalizedQueue[safeIndex]
    val updatedTrack = currentTrack.copy(
        durationMs = durationMs.takeIf { it > 0 } ?: currentTrack.durationMs,
    )
    val updatedQueue = normalizedQueue.toMutableList().also { playlist ->
        playlist[safeIndex] = updatedTrack
    }

    if (activePlaybackSource == PlaybackSource.AI) {
        recordRadioReplay(updatedTrack)
    }

    playbackState = playbackState.copy(
        currentTrack = updatedTrack,
        playlist = updatedQueue,
        currentIndex = safeIndex,
        currentPositionMs = positionMs.coerceAtLeast(0L),
        isPlaying = isPlaying,
        isPreparing = false,
        statusMessage = null,
    )

    if (activePlaybackSource == PlaybackSource.AI) {
        maybeAutoQueueMoreAiRecommendations()
    }
}

private fun playResolvedAiRecommendations(recommendations: List<AiRecommendedTrack>) {
    val queue = recommendations.map { normalizeTrack(it.track) }
    if (queue.isEmpty()) return

    radioSession = radioSession?.copy(queuedRecommendations = recommendations)
    activePlaybackSource = PlaybackSource.AI
    aiRecommendationState = aiRecommendationState.copy(
        isActive = true,
        tracks = recommendations,
        isLoadingMore = false,
        statusLabel = radioSession?.statusLabel,
        isDegraded = false,
    )

    prepareTrackForPlayback(
        queue = queue,
        index = 0,
        playWhenReady = true,
        source = PlaybackSource.AI,
    )
}

private fun maybeAutoQueueMoreAiRecommendations() {
    val session = radioSession ?: return
    if (session.isLoadingMore) return

    val remainingCount = playbackState.playlist.size - playbackState.currentIndex
    if (remainingCount > 3 || session.lastAutoAppendRemainingCount == remainingCount) return

    val config = buildAiServiceConfig()
    val favorites = favoritesState.tracks.map(::normalizeTrack)
    if (!config.isComplete || favorites.isEmpty()) return

    viewModelScope.launch {
        radioSession = session.copy(isLoadingMore = true, lastAutoAppendRemainingCount = remainingCount)
        aiRecommendationState = aiRecommendationState.copy(isLoadingMore = true, errorMessage = null)

        val result = withContext(Dispatchers.IO) {
            radioEngine.replenish(
                settings = config,
                favorites = favorites,
                history = radioHistoryStore.loadSnapshot(),
                session = radioSession ?: session,
            )
        }

        radioSession = result.updatedSession.copy(isLoadingMore = false)
        aiRecommendationState = aiRecommendationState.copy(
            isLoadingMore = false,
            tracks = result.updatedSession.queuedRecommendations,
            suggestionCount = aiRecommendationState.suggestionCount + result.suggestionCount,
            statusLabel = result.updatedSession.statusLabel,
            isDegraded = result.appendedRecommendations.isEmpty(),
            errorMessage = if (result.appendedRecommendations.isEmpty()) "这一轮没有拿到新的可播放歌曲。" else null,
        )

        if (result.appendedRecommendations.isNotEmpty()) {
            val updatedQueue = playbackState.playlist + result.appendedRecommendations.map { normalizeTrack(it.track) }
            commitPlayableQueue(
                queue = updatedQueue,
                index = playbackState.currentIndex.coerceIn(0, updatedQueue.lastIndex),
                playWhenReady = playbackState.isPlaying,
                startPositionMs = playbackState.currentPositionMs,
                source = PlaybackSource.AI,
            )
        }
    }
}

private fun applyPlaybackSource(source: PlaybackSource) {
    if (source == PlaybackSource.AI) {
        activePlaybackSource = PlaybackSource.AI
        aiRecommendationState = aiRecommendationState.copy(
            isActive = true,
            isLoadingMore = radioSession?.isLoadingMore == true,
            statusLabel = radioSession?.statusLabel,
        )
        return
    }

    activePlaybackSource = PlaybackSource.REGULAR
    radioSession = null
    aiRecommendationState = aiRecommendationState.copy(
        isActive = false,
        isLoadingMore = false,
        statusLabel = null,
    )
}
```

```kotlin
// android/app/src/main/java/com/twocents/player/ui/PlayerScreen.kt
HeartModeToggle(
    isActive = isHeartModeActive,
    isLoading = isHeartModeLoading,
    statusLabel = aiRecommendationState.statusLabel,
    onToggle = { onToggleHeartMode() },
)

@Composable
private fun HeartModeToggle(
    isActive: Boolean,
    isLoading: Boolean,
    statusLabel: String?,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !isLoading) { onToggle(!isActive) },
        shape = RoundedCornerShape(22.dp),
        color = if (isActive) Color.White.copy(alpha = 0.05f) else Color.Transparent,
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (isActive) 0.15f else 0.08f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "探索电台",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isActive) TextPrimary else TextSecondary,
                )
                when {
                    isLoading -> Text("正在准备电台中", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
                    isActive && !statusLabel.isNullOrBlank() -> Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
                }
            }
            Switch(
                checked = isActive,
                onCheckedChange = onToggle,
                enabled = !isLoading,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MidnightBackground,
                    checkedTrackColor = AccentMint,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = SurfaceSecondary,
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        }
    }
}
```

- [ ] **Step 4: Run the feedback tests and the full unit test suite**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew :app:testDebugUnitTest --tests "com.twocents.player.data.RadioPlaybackFeedbackTest"
./gradlew :app:testDebugUnitTest
```

Expected:

- First command: PASS with 3 tests executed.
- Second command: PASS for the existing repository tests plus the new radio tests.

- [ ] **Step 5: Build a debug APK and smoke-test the radio flow manually**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew :app:assembleDebug
```

Manual checks:

- Save valid AI settings in the sheet, then tap `探索电台`.
- Confirm the player starts immediately instead of waiting for a full page of suggestions.
- Skip a track within 30 seconds, let the queue replenish, and confirm the UI label can fall back to `回到熟悉区`.
- Favorite a fresh radio track, let the queue replenish, and confirm playback continues without unplayable tracks.
- Leave only 2 to 3 tracks in queue and confirm replenish happens before the queue empties.

Expected: BUILD SUCCESSFUL, and the radio never appends a track with a blank `audioUrl`.

- [ ] **Step 6: Commit the integrated exploration radio flow**

```bash
cd /Users/daddybh/Code/2CentsPlayer
git add android/app/src/main/java/com/twocents/player/data/RadioPlaybackFeedback.kt \
        android/app/src/main/java/com/twocents/player/ui/AiUiState.kt \
        android/app/src/main/java/com/twocents/player/ui/PlayerViewModel.kt \
        android/app/src/main/java/com/twocents/player/ui/PlayerScreen.kt \
        android/app/src/test/java/com/twocents/player/data/RadioPlaybackFeedbackTest.kt
git commit -m "feat: turn heart mode into exploration radio"
```

## Self-Review

### Spec Coverage

- Local persistent learning: Task 1
- Balanced exploration boundary and wave composition: Task 2
- Re-call the interface on every replenish: Tasks 3 and 4
- Playable-only queue with retries and fallback: Task 4
- Minimal UI explanation and in-player mode: Task 5
- Completion, skip, and favorite feedback loop: Task 5

No uncovered spec requirement remains.

### Placeholder Scan

- No `TODO`, `TBD`, or "implement later" placeholders remain.
- Every task includes exact file paths, concrete code, and exact commands.

### Type Consistency

The plan consistently uses:

- `RadioHistoryStore`
- `RadioRecommendationRequest`
- `RadioResolvedCandidate`
- `RadioReplenishmentEngine`
- `RadioSessionState`
- `RadioPlaybackFeedback`

Keep those names unchanged while implementing to avoid cross-task drift.
