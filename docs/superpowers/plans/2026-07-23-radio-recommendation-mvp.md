# Exploration Radio Recommendation MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make exploration radio deterministic, usable without AI configuration, responsive to positive and negative feedback, and resilient when candidates are sparse or unplayable.

**Architecture:** Add focused seed-selection and candidate-scoring units, then keep `RadioReplenishmentEngine` as the orchestration boundary. Fast similarity retrieval supplies the first queue; local scoring and queue composition determine ordering; AI is only consulted when configured and local results are insufficient.

**Tech Stack:** Kotlin, Android ViewModel, Media3, Kotlin coroutines, JUnit4

---

## File Map

- Create `android/app/src/main/java/com/twocents/player/data/RadioSeedSelector.kt`: deterministic, artist-diverse seed selection.
- Create `android/app/src/main/java/com/twocents/player/data/RadioCandidateScorer.kt`: local evidence aggregation, filtering, and scoring.
- Create `android/app/src/main/java/com/twocents/player/data/RadioDiagnostics.kt`: injected logging abstraction with no-op default.
- Modify `RadioModels.kt`: candidate evidence and request fields.
- Modify `RadioRecommendationPlanner.kt`: short-term artist avoidance and one-wave expansion.
- Modify `NeteaseSimiRepository.kt`: per-seed candidate quota and deterministic retrieval.
- Modify `RadioQueueComposer.kt`: score-first, graceful constraint relaxation.
- Modify `RadioReplenishmentEngine.kt`: scorer integration, partial-result retention, optional AI fallback.
- Modify `PlayerViewModel.kt`: first-playable candidate fallback and Android diagnostics wiring.
- Modify repository classes currently calling `android.util.Log`: use injected diagnostics.
- Add or update unit tests beside the corresponding domain tests.

### Task 1: Restore a trustworthy test baseline

**Files:**
- Create: `android/app/src/main/java/com/twocents/player/data/RadioDiagnostics.kt`
- Modify: `android/app/src/main/java/com/twocents/player/data/MusicLibraryRepository.kt`
- Modify: `android/app/src/main/java/com/twocents/player/data/NeteaseSearchRepository.kt`
- Modify: `android/app/src/main/java/com/twocents/player/data/LastFmRepository.kt`
- Modify: `android/app/src/main/java/com/twocents/player/data/NeteaseSimiRepository.kt`
- Modify: `android/app/src/main/java/com/twocents/player/data/RadioReplenishmentEngine.kt`

- [ ] Add a test proving the no-op diagnostic logger can be called from JVM tests without Android runtime methods.
- [ ] Run the test and verify it fails because the abstraction does not exist.
- [ ] Add `fun interface RadioDiagnosticLogger { fun debug(message: String) }` and `NoOpRadioDiagnosticLogger`.
- [ ] Replace direct `Log.d` calls in recommendation-path classes with an injected logger whose default is no-op.
- [ ] Run repository and radio tests; verify Android Log failures are gone while behavioral failures remain visible.

### Task 2: Correct planning and deterministic seed selection

**Files:**
- Create: `android/app/src/main/java/com/twocents/player/data/RadioSeedSelector.kt`
- Test: `android/app/src/test/java/com/twocents/player/data/RadioSeedSelectorTest.kt`
- Modify: `android/app/src/main/java/com/twocents/player/data/RadioRecommendationPlanner.kt`
- Test: `android/app/src/test/java/com/twocents/player/data/RadioRecommendationPlannerTest.kt`

- [ ] Add failing tests showing identical inputs return identical, artist-diverse seeds and recent positive tracks outrank ordinary favorites.
- [ ] Add a failing planner test showing only the newest three artist keys are short-term avoided and older positive artists are not excluded.
- [ ] Implement deterministic seed scores: strong positive/replay `+40`, completion `+30`, session favorite `+35`, ordinary favorite order fallback.
- [ ] Select at most three seeds with one seed per normalized primary artist.
- [ ] Update the planner to use selected seeds and `recentArtistKeys.takeLast(3)` only.
- [ ] Make expansion a one-wave signal by deriving it from recent feedback rather than any historical session favorite.
- [ ] Run selector and planner tests to green.

### Task 3: Multi-seed candidate recall

**Files:**
- Modify: `android/app/src/main/java/com/twocents/player/data/AiModels.kt`
- Modify: `android/app/src/main/java/com/twocents/player/data/NeteaseSimiRepository.kt`
- Test: `android/app/src/test/java/com/twocents/player/data/NeteaseSimiRepositoryTest.kt`

- [ ] Add failing tests showing three seeds each contribute candidates and repeated calls preserve seed order.
- [ ] Extend `AiSuggestedTrack` with `matchedSeedIds: Set<String>` and `retrievalSources: Set<String>` using empty defaults for compatibility.
- [ ] Remove `shuffled()` and assign a per-seed quota of `ceil(rawCandidateLimit / usableSeedCount)`.
- [ ] Merge duplicate normalized title/artist candidates by unioning evidence sets rather than dropping later evidence.
- [ ] Run the new repository tests to green.

### Task 4: Local candidate scoring and queue composition

**Files:**
- Create: `android/app/src/main/java/com/twocents/player/data/RadioCandidateScorer.kt`
- Test: `android/app/src/test/java/com/twocents/player/data/RadioCandidateScorerTest.kt`
- Modify: `android/app/src/main/java/com/twocents/player/data/RadioQueueComposer.kt`
- Modify: `android/app/src/test/java/com/twocents/player/data/RadioQueueComposerTest.kt`

- [ ] Add failing scorer tests for multi-seed bonus, positive-artist bonus, negative-artist penalty, hard track exclusion, and stable tie ordering.
- [ ] Extend `RadioResolvedCandidate` with `score` and `stableKey`.
- [ ] Implement the integer scoring table from the approved design.
- [ ] Add failing composer tests showing all-SAFE pools can fill the requested queue, duplicate artists are spaced, and sparse pools relax artist constraints instead of returning empty.
- [ ] Replace the fixed bucket-slot template with score-first selection plus two passes: strict artist spacing, then relaxed fill.
- [ ] Keep surprise candidates out of the first three positions unless no connected candidates remain.
- [ ] Run scorer and composer tests to green.

### Task 5: Resilient replenishment without AI

**Files:**
- Modify: `android/app/src/main/java/com/twocents/player/data/RadioReplenishmentEngine.kt`
- Modify: `android/app/src/test/java/com/twocents/player/data/RadioReplenishmentEngineTest.kt`

- [ ] Add failing tests showing fast-source results start radio with incomplete AI settings and partial local results survive AI failure.
- [ ] Add a failing test showing blank-URL candidates are acceptable as unresolved queue entries but are never reported as already playable.
- [ ] Aggregate and score matched candidates before composition.
- [ ] Only call the AI source when `settings.isComplete`; catch its failure and retain local results.
- [ ] Stop retrying when no additional source is available or an attempt adds no new candidates.
- [ ] Emit one diagnostic summary per replenish with seed, recall, filter, append, and duration counts.
- [ ] Run all radio domain tests to green.

### Task 6: First-playable fallback and full verification

**Files:**
- Modify: `android/app/src/main/java/com/twocents/player/ui/PlayerViewModel.kt`
- Modify: `android/app/src/test/java/com/twocents/player/ui/PlayerViewModelTest.kt`

- [ ] Add a failing ViewModel test where the requested first radio track cannot resolve but the next candidate can.
- [ ] Update preparation so radio playback tries subsequent queue candidates before surfacing an unavailable error.
- [ ] Preserve regular-playlist behavior: manually selected unavailable tracks still report the error instead of silently changing selection.
- [ ] Run `./gradlew testDebugUnitTest` and require zero failures.
- [ ] Run `./gradlew assembleDebug` and require exit code 0.
- [ ] Inspect `git diff --check` and the final diff for unrelated changes.
