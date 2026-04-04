# Exploration Radio Design

Date: 2026-04-04
Project: 2Cents Player Android app
Status: Approved design for implementation planning

## Summary

This design upgrades the current in-player `AI 心动模式` into a true exploration radio mode that stays inside the main player screen, starts playing immediately, and learns primarily from implicit feedback. The experience should feel more exploratory than the current "similar songs" flow, without turning the player into a control panel or a separate discovery product.

The core product direction is:

- Pure client-side implementation. No custom backend profile service.
- In-player mode, not a separate discovery page.
- Continuous radio playback, not a pick-from-cards flow.
- Minimal explanation in UI. The system should feel smarter through playback quality, not through verbose reasoning.
- Every replenishment wave calls the recommendation interface again.
- Only tracks confirmed to be playable may enter the AI queue.

The user-facing label in this spec is `探索电台`. Existing internal names such as `AI` or `HeartMode` can remain during phase-one implementation if that reduces churn, but the product behavior should match this document.

## Context

The current app already has a working AI recommendation flow:

- Favorites act as the primary positive seed.
- Skip behavior is tracked inside the active AI session.
- Recommendations are requested in batches and appended as the queue runs low.
- Candidate songs are matched against local music sources and filtered to playable tracks before playback.

That existing flow is a strong base, but it still behaves more like a refreshed recommendation list than an exploration radio. The next design step is to make the mode feel like a living stream that gradually expands outward and adapts as the user listens.

## Goals

- Increase exploration without breaking continuity.
- Make the mode feel better over time through passive learning from listening behavior.
- Keep the main interaction as a single tap from the player.
- Preserve playback stability. The radio should not surface tracks that cannot be played.
- Reduce the sense of randomness by structuring each wave of recommendations.

## Non-Goals

- No separate discovery tab or browse page in this phase.
- No up-front vibe picker, language picker, or exploration slider.
- No heavy per-track explanation UI.
- No server-side user embedding, profile service, or cloud history sync.
- No attempt to solve fully semantic music understanding in this phase.

## Product Shape

### Entry and Player Experience

`探索电台` remains a mode inside the existing player hero area.

- One tap enables the mode and immediately starts playback.
- The entry does not open a setup wizard or explicit tuning flow.
- The player shows only subtle mode feedback, such as an active state, light animation, or a compact label like `探索中`.
- The player does not show a long "why this song" explanation card.
- The queue should only show a session-level status such as `正在扩圈` or `回到熟悉区` when the boundary state has visibly changed, and it should not explain every song individually.

The experience target is: the user should feel the mode is getting smarter, not feel like they are training it manually.

### Persistent Learning

To satisfy the requirement that the system gets better as the user listens, the app should persist a lightweight local interaction memory across sessions.

This memory should stay on device and record compact implicit feedback summaries, not raw analytics dashboards.

Recommended persisted signals:

- Strong positive: track favorited while in radio mode
- Positive: track played past 70 percent or at least 120 seconds, whichever comes first
- Strong negative: track skipped within the first 30 seconds or first 25 percent
- Mild negative: track skipped after early sampling but before 70 percent
- Repeat positive: the same radio-discovered track is replayed later

The persisted memory should be small, recent, and decay over time. A rolling window such as the most recent 200 to 500 interactions is sufficient for this phase.

## Recommendation Strategy

### Core Model

The radio should not think in terms of "generate the best 10 songs once." Instead, it should operate in replenishment waves that are continuously re-planned as the session evolves.

Each wave targets a balanced exploration shape:

- Safe anchors: songs close to known taste
- Adjacent expansion: songs that move one step outward
- Small surprise: a limited number of songs that broaden the space without breaking the mood
- Recovery: songs that pull the session back toward familiar territory after a surprise

For the initial balanced mode chosen during brainstorming, the default target composition for one wave is:

- 4 safe tracks
- 2 adjacent expansion tracks
- 1 small surprise track

If the planner cannot find a usable surprise track, it should backfill with an adjacent track. If it still cannot fill the wave, it should backfill with a safe track. The mode should prefer continuity over forced novelty.

### Recommendation Inputs

Every replenishment request should combine:

- Favorite tracks
- Positive local listening memory
- Negative local listening memory
- Tracks already queued in the current radio session
- Tracks already played in the current session
- Artist, language, and era fatigue from recent history

This prevents the radio from repeatedly recommending the same artist cluster or repeatedly retrying directions the user already rejected.

### Recommendation Output Contract

Every replenishment wave must call the recommendation interface again. The system should not rely on a single long-lived static list.

The recommendation interface should return a candidate pool, not a guaranteed final queue. The app then applies local filtering and composition before playback.

Rules:

- A candidate is not queueable until the app has resolved it to a playable track with a valid `audioUrl`.
- Duplicates against the current queue, played history, and recent session memory are removed locally.
- Repeated artist clustering should be actively limited.
- If several fresh tracks are skipped quickly in a row, the next wave automatically narrows the exploration boundary.
- If a fresh track is completed or favorited, the next wave is allowed to continue exploring near that new anchor.

### Exploration Boundary

The app should maintain a dynamic exploration boundary inside each session.

Start state:

- Balanced exploration
- One small surprise slot per wave
- Preference for continuity of mood over abrupt genre jumps

Boundary adjustments:

- Two strong negatives in a short window shrink the next wave toward safe and adjacent tracks
- One strong positive on a fresh track allows the next wave to preserve or slightly widen exploration
- Multiple safe-track completions without positive reactions do not widen the boundary by themselves

This keeps the system exploratory, but not stubborn.

## System Architecture

### 1. RadioSession

Owns in-memory session state for the active radio run.

Responsibilities:

- Current playable queue
- Played tracks in this session
- Skipped tracks in this session
- Session-local positive events
- Recent artist and track fatigue
- Current exploration boundary
- Session mode metadata for the UI

This replaces the idea of a passive appended list with an explicit evolving session model.

### 2. RadioHistoryStore

Owns lightweight local persistence of implicit feedback across sessions.

Responsibilities:

- Store recent positive and negative events
- Keep only a bounded recent window
- Decay or prune stale interactions
- Expose compact aggregates to the planner

This store is necessary so the product can improve with listening over time while staying fully client-side.

### 3. RecommendationPlanner

Turns session state plus local history into each recommendation request.

Responsibilities:

- Build the request context for the recommendation interface
- Specify desired wave composition
- Encode avoid lists and recent rejects
- Adjust exploration boundary based on recent implicit feedback

The planner should request a candidate pool larger than the final queue target so filtering does not starve the session. In phase one, one replenishment should ask for 12 raw candidates to produce 6 to 7 playable results.

### 4. PlayableResolver

Guarantees the queue contains only playable tracks.

Responsibilities:

- Match candidate songs to the best track in local source repositories
- Resolve final playback URLs
- Filter out any item with no usable `audioUrl`
- Retry with additional recommendation calls if the playable yield is too low

This component enforces the user's hard requirement that every enqueued track must be playable.

### 5. QueueComposer

Builds the actual order of the next radio slice.

Responsibilities:

- Deduplicate by track and artist
- Apply fatigue rules
- Arrange tracks in a listening-friendly order
- Preserve the wave shape of safe, adjacent, surprise, then recovery

The queue composer is where the experience stops feeling like random shuffle and starts feeling like a curated radio stream.

## Data Flow

### Radio Start

1. User taps the in-player `探索电台` control.
2. App creates a new `RadioSession`.
3. Planner reads favorites plus persisted local listening memory.
4. Planner requests the first candidate pool from the recommendation interface.
5. Playable resolver matches and resolves candidates.
6. Queue composer builds the opening playable slice.
7. Playback begins immediately.

### Replenishment

1. Remaining queue count drops to the replenish threshold.
2. App reads the latest session signals:
   - completed tracks
   - skips
   - favorites
   - recent artists and tracks
3. Planner calls the recommendation interface again with updated context.
4. Resolver filters to playable tracks only.
5. Composer appends a new wave in listening order.

The initial replenish threshold should be `<= 3` remaining tracks, matching the current product direction and leaving enough time to recover from low-yield waves.

### Low-Yield Handling

If one interface call does not produce enough playable tracks:

1. Retry with another interface call in the same replenish cycle.
2. Narrow the exploration boundary slightly for the retry.
3. Continue until the app reaches the minimum safe append size or the per-cycle retry limit.

Recommended phase-one limits:

- Minimum safe append size: 4 playable tracks
- Target append size: 6 to 7 playable tracks
- Retry limit per replenish cycle: 3 interface calls

These values keep playback resilient without turning a single replenish into an unbounded loop.

## Error Handling and Fallbacks

### Recommendation Failure

If the recommendation interface fails:

- Keep playing the remaining queue
- Mark the current replenish attempt as failed
- Retry only at the next normal replenish opportunity or a user-triggered refresh
- Show a compact non-blocking status message instead of trapping the UI in loading

### Low Playable Yield

If recommendations return but too few are playable:

- Retry within the current replenish cycle
- Shrink exploration for the retry
- Prefer safe backfill over forced novelty

### Queue Exhaustion

If the queue is about to empty and replenishment still fails:

- Finish any remaining playable tracks
- Exit radio mode gracefully when the queue ends
- Return control to normal playback state rather than leaving the player stuck in a loading session

### Configuration Gating

If AI settings are incomplete:

- Radio mode should not start
- The app should open the existing settings sheet
- The UX should stay consistent with the current setup path

## UI States

The UI should stay restrained.

Required visible states:

- Radio off
- Radio starting
- Radio active
- Radio replenishing
- Radio degraded but still playing
- Radio ended or exited

Lightweight label policy:

- Default label: `探索中`
- Use `正在扩圈` only when the boundary has widened after recent positive signals
- Use `回到熟悉区` only when the system has narrowed exploration after recent negative signals

Not in scope for phase one:

- Per-track reason cards
- Explicit user tuning chips
- Session dashboards

## Testing Strategy

### Unit and Domain Tests

Cover the planner, resolver, and composer independently.

Key cases:

- Strong negative events shrink the next wave
- Strong positive events on fresh tracks allow continued expansion
- Duplicate tracks and artists are removed
- Fatigue rules prevent short-window repetition
- Surprise slots backfill safely when novelty candidates fail

### Playback Safety Tests

Key cases:

- Unplayable candidates never enter the queue
- Low-yield waves trigger retry behavior
- Retry limits stop correctly
- Queue remains stable while replenishment is in flight
- Session exits cleanly if replenish ultimately fails

### Integration Tests

Key cases:

- Radio starts from favorites and local history
- Replenishment happens at the threshold
- Skip, completion, favorite, and replay events update session state correctly
- Persistent local history is written and read correctly
- Existing non-radio playback remains unaffected when radio mode is off

## Implementation Notes for Planning

The current codebase already contains useful foundations in:

- `PlayerViewModel`
- `AiRecommendationRepository`
- `MusicLibraryRepository`
- the current AI playback session state

The implementation plan should evolve those foundations rather than replacing the whole flow at once.

The highest-value structural changes are likely:

- Introduce explicit radio session state instead of a thin appended queue model
- Add a local history store for implicit feedback
- Separate planning, playable resolution, and queue composition responsibilities
- Keep the UI change intentionally small

## Final Decisions

- Product shape: in-player mode
- Experience style: exploration radio
- Novelty level: balanced
- Feedback style: implicit first
- Runtime model: every replenish wave re-calls the interface
- Playback safety: playable-only queue
- UI philosophy: minimal explanation, stronger felt intelligence
