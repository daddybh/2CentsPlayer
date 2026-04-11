# Search Page Slide Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current search bottom sheet with a full-screen search page that slides in from right to left and slides out back to the right.

**Architecture:** Keep the app on the current single-activity, single-root Compose structure. Introduce a lightweight search-page visibility state in `PlayerViewModel`, extract the search UI into a dedicated full-screen composable, and swap the `ModalBottomSheet` for an animated page layer inside `PlayerApp`.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidViewModel, coroutines, JUnit4 local unit tests

---

## File Structure

### Files to Modify

- `android/app/src/main/java/com/twocents/player/ui/PlayerViewModel.kt`
  Responsibility: Own search page visibility state, open/close behavior, and close-on-select behavior.

- `android/app/src/main/java/com/twocents/player/ui/SearchUiState.kt`
  Responsibility: Hold search data only. Remove or stop using presentation-only visibility fields if they become redundant.

- `android/app/src/main/java/com/twocents/player/ui/PlayerScreen.kt`
  Responsibility: Host the home page and animated search page layers. Remove the old search `ModalBottomSheet`.

- `android/app/src/test/java/com/twocents/player/ui/PlayerViewModelTest.kt`
  Responsibility: Verify search page visibility transitions and search-result selection behavior.

### Files to Create

- `android/app/src/main/java/com/twocents/player/ui/SearchPage.kt`
  Responsibility: Render the dedicated full-screen search page UI and reuse the existing search content logic without bottom-sheet chrome.

---

### Task 1: Add dedicated search page visibility state in the ViewModel

**Files:**
- Modify: `android/app/src/main/java/com/twocents/player/ui/PlayerViewModel.kt`
- Modify: `android/app/src/test/java/com/twocents/player/ui/PlayerViewModelTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun openSearch_setsSearchPageVisible() {
    val viewModel = PlayerViewModel(FakeApplication())

    viewModel.openSearch()

    assertTrue(viewModel.isSearchPageVisible)
}

@Test
fun closeSearch_hidesSearchPage() {
    val viewModel = PlayerViewModel(FakeApplication())
    viewModel.openSearch()

    viewModel.closeSearch()

    assertFalse(viewModel.isSearchPageVisible)
}

@Test
fun selectTrack_closesSearchPageAfterQueueingPlayback() {
    val viewModel = PlayerViewModel(FakeApplication())
    val track = track(id = "search-track", title = "Search Track")
    viewModel.updateSearchResultsForTest(listOf(track))
    viewModel.openSearch()

    viewModel.selectTrack(track)

    assertFalse(viewModel.isSearchPageVisible)
    assertEquals(track.id, viewModel.playbackState.currentTrack?.id)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew testDebugUnitTest --tests com.twocents.player.ui.PlayerViewModelTest.openSearch_setsSearchPageVisible --tests com.twocents.player.ui.PlayerViewModelTest.closeSearch_hidesSearchPage --tests com.twocents.player.ui.PlayerViewModelTest.selectTrack_closesSearchPageAfterQueueingPlayback
```

Expected:
- FAIL because `isSearchPageVisible` and any helper setup do not exist yet, or because `selectTrack()` does not close the new page state.

- [ ] **Step 3: Write the minimal implementation**

```kotlin
var isSearchPageVisible by mutableStateOf(false)
    private set

fun openSearch() {
    favoritesState = favoritesState.copy(isVisible = false)
    lyricsState = lyricsState.copy(isVisible = false)
    aiSettingsState = aiSettingsState.copy(isVisible = false)
    isSearchPageVisible = true
}

fun closeSearch() {
    isSearchPageVisible = false
}

fun selectTrack(track: Track) {
    val queue = searchState.results.ifEmpty { listOf(track) }.map(::normalizeTrack)
    val selectedIndex = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
    closeSearch()
    prepareTrackForPlayback(
        queue = queue,
        index = selectedIndex,
        playWhenReady = true,
        source = PlaybackSource.REGULAR,
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run the same command from Step 2.

Expected:
- PASS for all targeted `PlayerViewModelTest` cases.

- [ ] **Step 5: Commit**

```bash
cd /Users/daddybh/Code/2CentsPlayer
git add android/app/src/main/java/com/twocents/player/ui/PlayerViewModel.kt android/app/src/test/java/com/twocents/player/ui/PlayerViewModelTest.kt
git commit -m "feat: add search page visibility state"
```

---

### Task 2: Extract the search UI into a full-screen page component

**Files:**
- Create: `android/app/src/main/java/com/twocents/player/ui/SearchPage.kt`
- Modify: `android/app/src/main/java/com/twocents/player/ui/PlayerScreen.kt`

- [ ] **Step 1: Write the failing test**

Because the repository currently has no Compose UI test harness for page transitions, add a small unit-level regression around the intended close behavior instead of introducing a new UI framework in this change:

```kotlin
@Test
fun openSearch_doesNotMutateExistingQueryOrResults() {
    val viewModel = PlayerViewModel(FakeApplication())
    val existing = track(id = "keep", title = "Keep")
    viewModel.updateSearchQuery("jay")
    viewModel.updateSearchResultsForTest(listOf(existing))

    viewModel.openSearch()

    assertEquals("jay", viewModel.searchState.query)
    assertEquals(listOf(existing.id), viewModel.searchState.results.map { it.id })
}
```

- [ ] **Step 2: Run the test to verify it fails if needed**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew testDebugUnitTest --tests com.twocents.player.ui.PlayerViewModelTest.openSearch_doesNotMutateExistingQueryOrResults
```

Expected:
- Either FAIL because the helper setup is missing, or PASS immediately if Task 1 already guarantees the behavior. If it passes immediately, keep it as regression coverage and move on.

- [ ] **Step 3: Write the minimal implementation**

Create a dedicated page composable that reuses the existing search content but removes bottom-sheet assumptions:

```kotlin
@Composable
fun SearchPage(
    state: SearchUiState,
    currentTrackId: String?,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onSelectTrack: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
) {
    BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize()) {
        SearchPageHeader(onBack = onBack)
        SearchPageContent(
            state = state,
            currentTrackId = currentTrackId,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            onLoadMore = onLoadMore,
            onSelectTrack = onSelectTrack,
            onToggleFavorite = onToggleFavorite,
        )
    }
}
```

Notes:
- Move the old `SearchSheet` body out of `PlayerScreen.kt`.
- Rename internal helpers as needed so they make sense in a page context.
- Keep the current result cards, empty states, and pagination footer logic.

- [ ] **Step 4: Run targeted tests**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew testDebugUnitTest --tests com.twocents.player.ui.PlayerViewModelTest
```

Expected:
- PASS. No behavior regressions from the extraction.

- [ ] **Step 5: Commit**

```bash
cd /Users/daddybh/Code/2CentsPlayer
git add android/app/src/main/java/com/twocents/player/ui/SearchPage.kt android/app/src/main/java/com/twocents/player/ui/PlayerScreen.kt android/app/src/test/java/com/twocents/player/ui/PlayerViewModelTest.kt
git commit -m "refactor: extract full-screen search page"
```

---

### Task 3: Replace the search bottom sheet with a right-to-left animated page

**Files:**
- Modify: `android/app/src/main/java/com/twocents/player/ui/PlayerScreen.kt`

- [ ] **Step 1: Write the failing test**

Use an existing ViewModel-driven regression to confirm selection still closes the page and starts playback after the UI host swap:

```kotlin
@Test
fun selectTrack_keepsSearchDataButReturnsToHomeLayer() {
    val viewModel = PlayerViewModel(FakeApplication())
    val track = track(id = "slide", title = "Slide")
    viewModel.updateSearchResultsForTest(listOf(track))
    viewModel.openSearch()

    viewModel.selectTrack(track)

    assertFalse(viewModel.isSearchPageVisible)
    assertEquals(listOf(track.id), viewModel.searchState.results.map { it.id })
}
```

- [ ] **Step 2: Run the test to verify it fails if behavior is not yet covered**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew testDebugUnitTest --tests com.twocents.player.ui.PlayerViewModelTest.selectTrack_keepsSearchDataButReturnsToHomeLayer
```

Expected:
- FAIL if the helper does not exist yet, otherwise PASS as added regression coverage.

- [ ] **Step 3: Write the minimal implementation**

Swap the old sheet block for an animated page layer:

```kotlin
Box(modifier = Modifier.fillMaxSize()) {
    HomePageContent(...)

    AnimatedVisibility(
        visible = viewModel.isSearchPageVisible,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
    ) {
        SearchPage(
            state = searchState,
            currentTrackId = currentTrack?.id,
            onBack = viewModel::closeSearch,
            onQueryChange = viewModel::updateSearchQuery,
            onSearch = viewModel::searchTracks,
            onLoadMore = viewModel::loadMoreSearchTracks,
            onSelectTrack = viewModel::selectTrack,
            onToggleFavorite = viewModel::toggleFavorite,
        )
    }
}
```

Also:
- Remove the old `ModalBottomSheet` search host.
- Keep favorites and AI settings sheets unchanged.
- Ensure the search page visually fills the whole screen and does not inherit bottom-sheet drag affordances.

- [ ] **Step 4: Run tests to verify the swap did not break behavior**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew testDebugUnitTest --tests com.twocents.player.ui.PlayerViewModelTest
```

Expected:
- PASS for the full `PlayerViewModelTest` class.

- [ ] **Step 5: Commit**

```bash
cd /Users/daddybh/Code/2CentsPlayer
git add android/app/src/main/java/com/twocents/player/ui/PlayerScreen.kt android/app/src/main/java/com/twocents/player/ui/SearchPage.kt android/app/src/test/java/com/twocents/player/ui/PlayerViewModelTest.kt
git commit -m "feat: show search as sliding page"
```

---

### Task 4: Verify the complete feature and install it on device

**Files:**
- Verify only

- [ ] **Step 1: Run focused unit tests**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew testDebugUnitTest --tests com.twocents.player.ui.PlayerViewModelTest
./gradlew testDebugUnitTest --tests com.twocents.player.data.MusicLibraryRepositoryTest
```

Expected:
- PASS for both suites.

- [ ] **Step 2: Run the complete local unit test suite**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew testDebugUnitTest
```

Expected:
- BUILD SUCCESSFUL

- [ ] **Step 3: Install on the connected Android device**

Run:

```bash
cd /Users/daddybh/Code/2CentsPlayer/android
./gradlew installDebug
adb shell monkey -p com.twocents.player -c android.intent.category.LAUNCHER 1
```

Expected:
- APK installs successfully
- App launches on the device

- [ ] **Step 4: Manual verification checklist**

Check on device:

- Tapping search opens a full-screen page
- The page enters from right to left
- Back button exits to the home page
- Selecting a result starts playback and returns home
- Existing search results remain available when reopening search during the same session

- [ ] **Step 5: Commit**

```bash
cd /Users/daddybh/Code/2CentsPlayer
git add android/app/src/main/java/com/twocents/player/ui/PlayerViewModel.kt android/app/src/main/java/com/twocents/player/ui/PlayerScreen.kt android/app/src/main/java/com/twocents/player/ui/SearchPage.kt android/app/src/main/java/com/twocents/player/ui/SearchUiState.kt android/app/src/test/java/com/twocents/player/ui/PlayerViewModelTest.kt
git commit -m "feat: convert search sheet to sliding page"
```
