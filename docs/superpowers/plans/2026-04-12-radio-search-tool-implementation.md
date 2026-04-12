# Radio Search Tool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make exploration radio use the app's existing search system as a model-invoked tool, so the model searches first and then chooses from real candidates instead of hallucinating track names.

**Architecture:** Keep `MusicLibraryRepository.searchTracks()` as the single search backend. Add a thin search-tool layer plus a two-stage tool-calling loop inside `AiRecommendationRepository.requestRadioCandidates()`: first ask the model for a `search_tracks` call, then execute search locally, then ask the model to select `candidateId`s from the returned compact candidates.

**Tech Stack:** Kotlin, Android, OkHttp, JSONObject, coroutines, JUnit4 local unit tests

---

## File Structure

### Files to Modify

- `android/app/src/main/java/com/twocents/player/data/AiModels.kt`
  Responsibility: Add tool-call/result models and compact candidate structures.

- `android/app/src/main/java/com/twocents/player/data/AiRecommendationRepository.kt`
  Responsibility: Implement the two-stage LLM + tool flow for exploration radio.

- `android/app/src/main/java/com/twocents/player/data/MusicLibraryRepository.kt`
  Responsibility: Provide a compact, tool-facing search entry if needed, while keeping search as the single source of truth.

- `android/app/src/test/java/com/twocents/player/data/AiRecommendationRepositoryTest.kt`
  Responsibility: Verify tool-call parsing, search execution, candidate handoff, and final selection.

### Files to Create

- `android/app/src/main/java/com/twocents/player/data/AiSearchTool.kt`
  Responsibility: Execute `search_tracks(query, limit)` using `MusicLibraryRepository` and return compact candidates plus a local `candidateId -> Track` map.

---

### Task 1: Add tool-calling models and compact search candidate structures

**Files:**
- Modify: `android/app/src/main/java/com/twocents/player/data/AiModels.kt`
- Test: `android/app/src/test/java/com/twocents/player/data/AiRecommendationRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun parseToolSelection_parsesCandidateIdsAndBuckets() {
    val repository = AiRecommendationRepository()

    val parsed = repository.parseRadioToolSelectionForTest(
        """
        {
          "recommendations": [
            {"candidateId":"cand_1","reason":"理由1","bucket":"safe"},
            {"candidateId":"cand_2","reason":"理由2","bucket":"adjacent"}
          ]
        }
        """.trimIndent()
    )

    assertEquals(listOf("cand_1", "cand_2"), parsed.map { it.candidateId })
    assertEquals(listOf(RadioCandidateBucket.SAFE, RadioCandidateBucket.ADJACENT), parsed.map { it.bucket })
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew testDebugUnitTest --tests "com.twocents.player.data.AiRecommendationRepositoryTest.parseToolSelection_parsesCandidateIdsAndBuckets"
```

Expected:
- FAIL because the tool-selection models and parser helper do not exist yet.

- [ ] **Step 3: Write minimal implementation**

Add compact models in `AiModels.kt`, for example:

```kotlin
data class SearchToolCall(
    val query: String,
    val limit: Int,
)

data class SearchToolCandidate(
    val candidateId: String,
    val title: String,
    val artist: String,
    val album: String,
    val source: String,
    val durationMs: Long,
)

data class ToolSelectedTrack(
    val candidateId: String,
    val reason: String,
    val bucket: RadioCandidateBucket,
)
```

Expose a test-visible parser helper in `AiRecommendationRepository` that can read the second-stage JSON into `ToolSelectedTrack`.

- [ ] **Step 4: Run test to verify it passes**

Run the same command from Step 2.

Expected:
- PASS

---

### Task 2: Add a search tool executor that reuses MusicLibraryRepository.searchTracks

**Files:**
- Create: `android/app/src/main/java/com/twocents/player/data/AiSearchTool.kt`
- Modify: `android/app/src/main/java/com/twocents/player/data/MusicLibraryRepository.kt`
- Test: `android/app/src/test/java/com/twocents/player/data/AiRecommendationRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun searchTool_returnsCompactCandidatesAndTrackMap() = runBlocking {
    val library = FakeMusicLibraryRepository(
        page = MusicSearchPage(
            tracks = listOf(
                track(id = "netease:1", title = "晴天", artist = "周杰伦", album = "叶惠美"),
                track(id = "kuwo:2", title = "七里香", artist = "周杰伦", album = "七里香"),
            ),
            nextNeteaseOffset = 1,
            nextKuwoOffset = 1,
            canLoadMoreNetease = false,
            canLoadMoreKuwo = false,
        ),
    )
    val tool = AiSearchTool(library)

    val result = tool.search(query = "周杰伦", limit = 2)

    assertEquals(listOf("cand_1", "cand_2"), result.candidates.map { it.candidateId })
    assertEquals(listOf("晴天", "七里香"), result.candidates.map { it.title })
    assertEquals("netease:1", result.trackByCandidateId.getValue("cand_1").id)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew testDebugUnitTest --tests "com.twocents.player.data.AiRecommendationRepositoryTest.searchTool_returnsCompactCandidatesAndTrackMap"
```

Expected:
- FAIL because `AiSearchTool` and result mapping do not exist yet.

- [ ] **Step 3: Write minimal implementation**

Implement a thin executor:

```kotlin
class AiSearchTool(
    private val musicLibraryRepository: MusicLibraryRepository,
) {
    suspend fun search(query: String, limit: Int): SearchToolResult {
        val page = musicLibraryRepository.searchTracks(keyword = query, limitPerSource = limit)
        val limitedTracks = page.tracks.take(limit)
        val candidates = limitedTracks.mapIndexed { index, track ->
            SearchToolCandidate(
                candidateId = "cand_${index + 1}",
                title = track.title,
                artist = track.artist,
                album = track.album,
                source = track.source.storageKey,
                durationMs = track.durationMs,
            )
        }
        val trackByCandidateId = candidates.zip(limitedTracks).associate { (candidate, track) ->
            candidate.candidateId to track
        }
        return SearchToolResult(candidates, trackByCandidateId)
    }
}
```

If needed for tests, make `MusicLibraryRepository` open for injection or use constructor injection in `AiRecommendationRepository`.

- [ ] **Step 4: Run test to verify it passes**

Run the same command from Step 2.

Expected:
- PASS

---

### Task 3: Change exploration radio to a two-stage tool-calling loop

**Files:**
- Modify: `android/app/src/main/java/com/twocents/player/data/AiRecommendationRepository.kt`
- Modify: `android/app/src/main/java/com/twocents/player/data/AiModels.kt`
- Modify: `android/app/src/main/java/com/twocents/player/data/AiSearchTool.kt`
- Test: `android/app/src/test/java/com/twocents/player/data/AiRecommendationRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun requestRadioCandidates_callsSearchToolThenReturnsChosenTracks() {
    val responses = listOf(
        llmResponse("""{"tool":"search_tracks","arguments":{"query":"周杰伦 经典","limit":4}}"""),
        llmResponse(
            """
            {
              "recommendations": [
                {"candidateId":"cand_1","reason":"命中偏好","bucket":"safe"},
                {"candidateId":"cand_2","reason":"相邻扩展","bucket":"adjacent"}
              ]
            }
            """.trimIndent()
        ),
    )
    val repository = FakeAiRecommendationRepository(responses = responses, searchToolResult = toolResult(...))

    val result = repository.requestRadioCandidates(config, request)

    assertEquals(listOf("cand_1", "cand_2"), result.map { it.candidateId })
    assertEquals(2, repository.networkCallCount)
    assertEquals("周杰伦 经典", repository.toolCalls.single().query)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew testDebugUnitTest --tests "com.twocents.player.data.AiRecommendationRepositoryTest.requestRadioCandidates_callsSearchToolThenReturnsChosenTracks"
```

Expected:
- FAIL because radio candidate generation is still one-shot and does not perform the tool loop.

- [ ] **Step 3: Write minimal implementation**

Inside `requestRadioCandidates()`:

1. First request asks the model for a single tool call.
2. Parse the tool call.
3. Execute `AiSearchTool.search(query, limit)`.
4. Second request sends the compact candidates back to the model.
5. Parse selected `candidateId`s.
6. Map `candidateId` back to real `Track`.
7. Return `AiSuggestedTrack`-compatible selected results or directly build the next-stage structures required by the current radio flow.

Keep constraints:
- At most one tool call
- Compact candidate payload
- If tool call is invalid, fail fast with a clear error

- [ ] **Step 4: Run test to verify it passes**

Run the same command from Step 2.

Expected:
- PASS

---

### Task 4: Wire candidateId results back into the existing radio pipeline

**Files:**
- Modify: `android/app/src/main/java/com/twocents/player/data/AiRecommendationRepository.kt`
- Modify: `android/app/src/test/java/com/twocents/player/data/RadioReplenishmentEngineTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun replenish_acceptsToolSelectedCandidatesAndStillBuildsPlayableQueue() = runBlocking {
    val candidateSource = FakeToolCallingCandidateSource(
        selectedTracks = listOf(
            suggestedTrack("cand_1", "命中", RadioCandidateBucket.SAFE),
        ),
    )
    val trackLookup = FakeRadioTrackLookup(
        matchedTracks = emptyMap(),
        resolvedTracks = mapOf("netease:1" to track("netease:1", "周杰伦", audioUrl = "https://audio.example/1.mp3")),
    )
    val engine = RadioReplenishmentEngine(candidateSource, trackLookup)

    val result = engine.replenish(config, favorites, history, session, minimumRequiredAppend = 1)

    assertEquals(1, result.appendedRecommendations.size)
    assertTrue(result.appendedRecommendations.first().track.audioUrl.isNotBlank())
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew testDebugUnitTest --tests "com.twocents.player.data.RadioReplenishmentEngineTest.replenish_acceptsToolSelectedCandidatesAndStillBuildsPlayableQueue"
```

Expected:
- FAIL if the new repository output shape does not yet align with the radio pipeline.

- [ ] **Step 3: Write minimal implementation**

Keep `RadioCandidateSource` contract compatible. If needed, have `AiRecommendationRepository` translate selected real tracks back into the existing `AiSuggestedTrack` or equivalent expected radio candidate form before handing off to `RadioReplenishmentEngine`.

- [ ] **Step 4: Run tests to verify it passes**

Run the same command from Step 2.

Expected:
- PASS

---

### Task 5: Verify the complete feature

**Files:**
- Verify only

- [ ] **Step 1: Run focused data-layer tests**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew testDebugUnitTest --tests "com.twocents.player.data.AiRecommendationRepositoryTest"
./gradlew testDebugUnitTest --tests "com.twocents.player.data.RadioReplenishmentEngineTest"
./gradlew testDebugUnitTest --tests "com.twocents.player.data.MusicLibraryRepositoryTest"
```

Expected:
- All pass

- [ ] **Step 2: Run the full local unit test suite**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew testDebugUnitTest
```

Expected:
- BUILD SUCCESSFUL

- [ ] **Step 3: Install to device and manually verify exploration radio**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew installDebug
adb shell monkey -p com.twocents.player -c android.intent.category.LAUNCHER 1
```

Manual checks:
- Tapping exploration radio starts the AI flow
- Model issues one search tool call
- Search returns compact candidates
- Model chooses candidateIds
- Radio gets at least one playable track faster than the old free-text path
