# Video Phase 4: Built-In Player Vertical Slice

## Purpose

This document tracks Phase 4 of video support: proving the parallel video stack end to end by opening one stored episode from an internal launcher, resolving a real stream through `VideoSource`, and playing it in-app with Media3 while persisting progress, history, and completion state.

Phase 3 completed the storage foundation. Phase 4 is the first runtime slice that must validate stream resolution, header/cookie handling, and persistence before broader video UI work begins.

## Phase Goal

Play one stored video episode from one video source in-app with progress persistence.

At the end of this phase:

- an internal/debug-only entry point can open a stored `VideoEpisode`
- the player resolves a real `VideoStream` through `VideoSourceManager`
- built-in playback uses AndroidX Media3
- request headers, cookies, and referer are honored through the app networking stack
- resume position persists via `VideoPlaybackStateRepository`
- watched duration/history is written via `VideoHistoryRepository`
- episodes are marked completed at 90% watched
- a minimal external-player action can reuse the resolved `VideoRequest`

## Locked Decisions

- Phase 4 uses a debug-only launcher rather than real browse/details UI.
- Built-in playback must land before any external-player work.
- The external-player action, if added in this phase, stays minimal and best-effort.
- Stream selection policy is source order only: first returned stream wins.
- Media3 is the only built-in player stack.
- Phase 4 works against already stored `VideoTitle` and `VideoEpisode` rows.
- Video placeholder tabs remain placeholder-backed in this phase.

## Out Of Scope

This phase does not implement:

- real video browse, details, or episode-list screens
- continue-watching surfaces in the video home tabs
- quality picker UI or video-specific player preferences
- subtitles, casting, picture-in-picture, or background audio
- downloader, offline playback, or local video support
- visible video history, updates, or library UI rollout
- multi-source hardening and edge-case extension compatibility sweeps
- rich external-player metadata contracts

## Why This Phase Exists

The repo now has the core pieces for a real video slice, but they are not connected yet.

Relevant current seams:

- `source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/VideoSource.kt`
  - stream resolution is defined as `getStreamList(episode: SEpisode)`
- `source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/model/VideoRequest.kt`
  - source request metadata is available as `url + headers`
- `app/src/main/java/eu/kanade/tachiyomi/source/AndroidVideoSourceManager.kt`
  - registered video sources now exist at runtime
- `domain/src/main/java/tachiyomi/domain/video/repository/VideoRepository.kt`
- `domain/src/main/java/tachiyomi/domain/video/repository/VideoEpisodeRepository.kt`
- `domain/src/main/java/tachiyomi/domain/video/repository/VideoPlaybackStateRepository.kt`
- `domain/src/main/java/tachiyomi/domain/video/repository/VideoHistoryRepository.kt`
  - Phase 3 storage paths exist, but no real playback path writes into them yet
- `app/build.gradle.kts`
  - the app still has no Media3 dependency
- `app/src/main/AndroidManifest.xml`
  - there is no `VideoPlayerActivity`
- `app/src/main/java/eu/kanade/tachiyomi/ui/video/VideoTabs.kt`
  - video tabs are still placeholders only

Without Phase 4:

- the video data layer remains unproven against real playback
- `VideoRequest.headers` and shared cookie handling remain theoretical
- `video_history` stays disconnected from real watch activity
- later UI work risks being built around unvalidated playback assumptions

## Desired Behavior

### Entry Path

- An internal/debug-only launcher can choose one stored `VideoEpisode` from the active `VIDEO` profile.
- Opening the player passes only stable row IDs such as `videoId` and `episodeId`.
- Missing rows, missing sources, or empty stream lists fail cleanly with a visible error and no fallback into manga flows.

### Stream Resolution

- Load the stored `VideoTitle` and `VideoEpisode`.
- Resolve the source from `VideoSourceManager` using `VideoTitle.source`.
- Map the stored episode into `SEpisode`.
- Call `VideoSource.getStreamList(SEpisode)`.
- Select the first returned `VideoStream`.

Implementation note:

- Because `getStreamList()` is episode-based today, Phase 4 does not need a full source-to-local browse/details bridge.
- A `VideoTitle -> SVideo` mapper can stay deferred unless a concrete player need appears during implementation.

### Built-In Playback

- The app plays the selected `VideoStream` in a dedicated player activity/screen.
- Media3 uses the app's shared `OkHttpClient` and cookie jar.
- `VideoRequest.headers` are forwarded into playback requests.
- The initial implementation must support the returned stream type without adding a user-facing quality selector.

### Persistence

- The player reads `VideoPlaybackState` on open and seeks to `positionMs` when available.
- The player writes playback state every 10 seconds and again on pause/stop.
- Completion is applied at 90% watched through `VideoPlaybackStateRepository.upsertAndSyncEpisodeState()`.

### History

- Phase 4 becomes the first real writer to `video_history`.
- History updates must use watched-duration deltas, not absolute player position, because the current repository accumulates `time_watched`.
- `last_watched` should reflect real playback activity.

### Stored Data Assumption

- The debug launcher works against existing stored `VideoTitle` and `VideoEpisode` rows only.
- If the active video profile has no stored rows, the launcher should show an empty state instead of trying to fetch new data.
- Phase 4 does not add production browse or ingestion UX.

### Minimal External Action

- Once built-in playback is working, the player may expose a simple external-player action.
- It should reuse the resolved `VideoRequest`.
- Failure to hand off externally must not block the built-in player path.

## Workstreams

## 1. Media3 Dependencies And Manifest Wiring

### Planned changes

- add Media3 dependencies to the version catalog
- wire the dependencies into `app/build.gradle.kts`
- register a dedicated `VideoPlayerActivity`

### Files

- `gradle/libs.versions.toml`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`

### Decisions

- use only the Media3 artifacts needed for app playback
- use `media3-datasource-okhttp` so playback can share the app networking stack

### Checklist

- [x] Add Media3 version catalog entries
- [x] Add Media3 app dependencies
- [x] Register `VideoPlayerActivity`

## 2. Stored Episode To Playback Resolver

Add the minimal bridge from stored video rows to a playable source request.

### Planned changes

- add a mapper from `VideoEpisode` to `SEpisode`
- add a small resolver/use case that loads the stored video row, episode row, and runtime source
- resolve the first playable `VideoStream`

### Files

- new video player mapper/resolver files under `app` or `domain`
- existing `VideoRepository`
- existing `VideoEpisodeRepository`
- existing `VideoSourceManager`

### Decisions

- keep this bridge minimal and one-way
- do not build browse/detail refresh logic here
- treat missing source or empty stream lists as explicit error states

### Checklist

- [x] Add `VideoEpisode -> SEpisode` mapping
- [x] Add resolver for `VideoTitle`, `VideoEpisode`, and `VideoSource`
- [x] Select the first returned stream
- [x] Surface source-missing and no-stream errors clearly

## 3. Player Activity And UI State

Build the dedicated player entry point.

### Planned changes

- add `VideoPlayerActivity`
- add player UI state for loading, ready, and error
- keep activity setup parallel to `ReaderActivity` and `WebViewActivity`, not mixed into reader code

### Files

- new `app/src/main/java/eu/kanade/tachiyomi/ui/video/player/...`

### Decisions

- player lives in its own activity/screen
- phase uses a narrow intent contract based on stable DB ids
- no routing from normal placeholder tabs yet

### Checklist

- [x] Add `VideoPlayerActivity.newIntent(context, videoId, episodeId)`
- [x] Initialize from DB ids and finish cleanly on invalid input
- [x] Add loading, error, and ready states
- [x] Keep `ReaderActivity` manga-only

## 4. Media3 Request And Datasource Integration

Make playback honor the resolved request metadata.

### Planned changes

- create Media3 player setup from the selected `VideoStream`
- build a datasource backed by the app `NetworkHelper`
- map `VideoRequest.headers` into the playback request layer

### Files

- new player adapter/helper code
- `core/common/src/main/kotlin/eu/kanade/tachiyomi/network/NetworkHelper.kt`

### Decisions

- custom headers, cookies, and referer must come from the resolved `VideoRequest`
- the built-in player should share the app cookie jar instead of creating a parallel network stack
- source order decides which stream is used

### Checklist

- [x] Create Media3 datasource integration backed by shared OkHttp
- [x] Forward `VideoRequest.headers`
- [x] Create `MediaItem` from the selected stream
- [x] Handle unsupported or empty stream results with explicit player errors

## 5. Playback State And Completion Writeback

Persist resume state from real playback.

### Planned changes

- load saved playback state on init
- seek to saved position
- persist position, duration, and completion during playback and on lifecycle boundaries

### Files

- player activity/view-model code
- existing `VideoPlaybackStateRepository`

### Decisions

- `VideoPlaybackStateRepository.upsertAndSyncEpisodeState()` remains the single write path for playback state plus episode completion sync
- completion threshold is 90%
- incomplete episodes should still keep their resume position

### Checklist

- [x] Load saved playback state on open
- [x] Save progress every 10 seconds
- [x] Save again on pause and stop
- [x] Mark completed at 90% watched
- [x] Keep partial progress when below threshold

## 6. History Writeback From Real Playback

Connect the Phase 3 history store to actual playback sessions.

### Planned changes

- write `VideoHistoryUpdate` during periodic saves and on pause/stop
- track watched-duration deltas so `time_watched` is not double-counted

### Files

- player activity/view-model code
- existing `VideoHistoryRepository`

### Decisions

- Phase 4 should not leave `video_history` disconnected from real player activity
- `sessionWatchedDuration` must represent new watched time since the previous write, not absolute playback position
- one history row per episode remains the current behavior

### Checklist

- [x] Track watched-duration deltas separately from absolute position
- [x] Upsert history during playback save points
- [x] Update `last_watched` from real playback events
- [x] Keep writes compatible with current `video_history` accumulation semantics

## 7. Debug-Only Launcher

Create the narrow internal entry path for the first vertical slice.

### Planned changes

- add an internal/debug-only launcher surface
- let it choose from stored `VideoTitle` and `VideoEpisode` rows in the active profile
- open `VideoPlayerActivity`

### Files

- debug or advanced settings surface
- new player package

### Decisions

- no production browse/details UX in this phase
- launcher may remain behind internal/debug build gating or internal settings surfaces
- empty stored data should show a clear empty state instead of trying to fetch new data

### Checklist

- [x] Add internal launcher entry
- [x] List or select stored video and episode rows
- [x] Open player with stable ids
- [x] Handle empty-state cleanly

## 8. Minimal External-Player Action

Add a small handoff path after the built-in player is reliable.

### Planned changes

- expose a basic external-player action from the player UI
- reuse the resolved `VideoRequest`
- keep it best-effort

### Files

- player UI/activity
- system intent helpers if needed

### Decisions

- built-in playback remains the required supported path
- external-player failure is non-blocking
- do not expand this into a richer external contract yet

### Checklist

- [x] Add a simple external-player action
- [x] Reuse the resolved stream request where feasible
- [x] Keep external handoff non-blocking

## Implementation Batches

1. Dependencies, manifest, and intent contract
2. Stored-episode resolver and Media3 datasource adapter
3. Player activity and built-in playback
4. Progress persistence, history writeback, and completion threshold
5. Debug launcher and minimal external-player action

## Test Plan

### Resolver

- load stored video and episode by id
- return clear error when source is missing
- return clear error when stream list is empty
- select the first returned stream

### Request integration

- forward custom headers from `VideoRequest`
- confirm the shared cookie jar is used by playback requests

### Playback state

- resume seeks to saved `positionMs`
- periodic saves update `video_playback_state`
- pause and stop save the latest position
- 90% watched marks the episode completed

### History

- periodic saves upsert history rows
- watched-duration deltas accumulate correctly
- repeated saves do not treat absolute position as fresh watched time

### Debug launcher

- empty launcher state when no stored video rows exist
- selecting a stored episode opens the player with the correct ids

### Focused automated verification

- `ResolveVideoStreamTest` covers source lookup, first-stream selection, and error states
- `VideoPlaybackSessionTest` covers watched-duration deltas and 90% completion threshold behavior
- `VideoPlayerViewModelTest` covers resume-state loading and playback/history persistence writes

### Manual validation

- open one stored episode from the internal launcher
- play, pause, background, and reopen
- confirm resume position works
- confirm completion sync happens after threshold
- confirm history updates appear in repository-backed data
- confirm built-in playback still works if external-player handoff is ignored

## Rollout Notes

- keep the player entry internal/debug-only for this phase
- do not route placeholder video tabs to the player yet
- do not let external-player polish block the built-in vertical slice

## Exit Criteria

Phase 4 is complete when all of the following are true.

- Media3 is integrated into the app
- a dedicated `VideoPlayerActivity` can open one stored episode from an internal launcher
- the player resolves and plays a real stream through `VideoSourceManager`
- request headers and shared cookies are honored for playback
- resume position persists through `VideoPlaybackStateRepository`
- real playback writes `video_history`
- 90% watched marks the episode completed and syncs to `video_episodes`
- a minimal external-player action is available from the player UI
- the slice remains isolated from production video browse/details UI

## Current Status

- Phase 4 is complete
- built-in playback, stream resolution, progress persistence, history writeback, debug-only launcher, and a minimal external-player action are implemented
- remaining work, if any, belongs to later hardening or UI phases rather than the core Phase 4 vertical slice
- Phase 2 and Phase 3 prerequisites remain the underlying base: `VideoSourceManager`, `VideoRequest`, `VideoStream`, playback-state persistence, and video history storage

## Handoff To Phase 5

Phase 5 planning now lives in `docs/VIDEO-PHASE-5-UI.md`.

It covers concrete work items for:

- routing real video browse and details flows into the player
- replacing placeholder tabs with real video surfaces
- continue-watching entry points
- episode-list progress indicators

`VideoUpdatesTab` and `VideoHistoryTab` presentation remain deferred to Phase 6.
