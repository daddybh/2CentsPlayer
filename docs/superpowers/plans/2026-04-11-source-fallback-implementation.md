# Source Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add cross-source playback fallback so a track can borrow a playable audio URL from another source when its preferred source cannot resolve one.

**Architecture:** Keep the current `MusicSourceRepository` abstraction and implement fallback inside `MusicLibraryRepository`. Preserve the caller-facing `Track` identity for now, but allow the repository to probe alternate sources for a matching playable track and merge the resolved playback URL back onto the original track.

**Tech Stack:** Kotlin, coroutines, JUnit4, Android local unit tests

---

### Task 1: Add a regression test for cross-source playback fallback

**Files:**
- Modify: `android/app/src/test/java/com/twocents/player/data/MusicLibraryRepositoryTest.kt`

- [ ] Add a failing test covering a track whose primary source cannot resolve `audioUrl` but an alternate source can.
- [ ] Run only `MusicLibraryRepositoryTest` and confirm the new test fails for the expected reason.

### Task 2: Implement fallback in the library repository

**Files:**
- Modify: `android/app/src/main/java/com/twocents/player/data/MusicLibraryRepository.kt`

- [ ] Add repository-level logic that detects unresolved tracks after normal per-source resolution.
- [ ] For each unresolved track, search alternate repositories for the best match and attempt playable resolution.
- [ ] Merge the resolved alternate `audioUrl` and better duration back into the original track without changing its public identity.

### Task 3: Verify behavior and guard against regressions

**Files:**
- Modify: `android/app/src/test/java/com/twocents/player/data/MusicLibraryRepositoryTest.kt`

- [ ] Re-run `MusicLibraryRepositoryTest` and confirm the new fallback test passes alongside existing repository tests.
- [ ] Run a focused Gradle unit test command for the repository package and record the result.
