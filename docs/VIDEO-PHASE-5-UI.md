# Video Phase 5: Video Profile Shell and Core UI

## Purpose

This document tracks Phase 5 of video support: replacing the placeholder-backed `VIDEO` profile shell with real browse, library, details, and playback-routing UI, while keeping `Updates` and `History` presentation deferred to Phase 6.

Phase 4 proved the runtime playback vertical slice. Phase 5 makes video profiles usable as a coherent product mode without pulling in the broader history/settings/update adaptation work.

## Phase Goal

Make `VIDEO` profiles usable end to end for browse -> details -> play -> resume flows.

At the end of this phase:

- `VideoBrowseTab` is a real browse surface rather than a placeholder
- `VideoLibraryTab` is a real library surface rather than a placeholder
- video titles have a real details screen and episode list
- opening a video episode routes to `VideoPlayerActivity`
- continue-watching entry points exist in the video shell
- episode rows show watched/completed/progress state
- search inside video browse flows works without mixing manga results
- `VideoUpdatesTab` and `VideoHistoryTab` remain placeholder-backed until Phase 6

## Locked Scope

- Phase 5 includes `Browse`, `Library`, title details, episode routing, continue watching, and episode progress indicators.
- Phase 5 does not include video `Updates` or `History` tab presentation.
- Phase 5 does not include video settings surfaces, notifications, or shortcuts.
- Phase 5 must keep video browse/details/player flows separate from manga browse/details/reader flows.
- Video browse should use a separate video source/extensions surface instead of reusing manga `BrowseTab` directly.
- The built-in Media3 player from Phase 4 remains the required open path.

## Out Of Scope

This phase does not implement:

- video `Updates` tab UI
- video `History` tab UI
- video-specific settings/preferences UI
- notifications or quick actions for video profiles
- downloader, offline playback, or local video support
- multi-source hardening and compatibility sweeps
- production removal of placeholder `VideoUpdatesTab` and `VideoHistoryTab`

## Why This Phase Exists

The current repo state still leaves `VIDEO` profiles structurally incomplete:

- `app/src/main/java/eu/kanade/tachiyomi/ui/video/VideoTabs.kt`
  - all five video tabs are placeholders today
- `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt`
  - `VIDEO` profiles already route to distinct tabs, so replacing placeholder tabs is now a UI concern rather than a shell concern
- `app/src/main/java/eu/kanade/tachiyomi/ui/video/player/VideoPlayerActivity.kt`
  - playback now exists, but the only current entry path is the internal debug launcher
- `app/src/main/java/eu/kanade/presentation/more/settings/screen/debug/VideoPlayerLauncherScreen.kt`
  - confirms stored episodes can open the player, but it is not a product-facing navigation flow

Phase 5 turns the player spike into the first usable video product mode.

## Current Gaps Identified Before Phase 5

### 1. No parallel video source-list interactor stack yet

The manga source list stack exists, but it is manga-only:

- `app/src/main/java/eu/kanade/domain/source/interactor/GetEnabledSources.kt`
- `app/src/main/java/eu/kanade/domain/source/interactor/ToggleSource.kt`
- `app/src/main/java/eu/kanade/domain/source/interactor/ToggleSourcePin.kt`
- `domain/src/main/java/tachiyomi/domain/source/repository/SourceRepository.kt`
- `data/src/main/java/tachiyomi/data/source/SourceRepositoryImpl.kt`

There is no parallel video version of this stack yet.

### 2. No video catalogue paging/browse layer yet

Manga browse currently depends on a manga-specific paging path via `SourceRepository` and `GetRemoteManga`.

Video has:

- `source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/VideoCatalogueSource.kt`

But it does not yet have:

- a video browse paging/service path
- a video paging source
- a video browse screen model

This does not necessarily require a full parallel clone of manga `SourceRepository` on day one. A smaller video-specific browse layer is acceptable if it keeps the browse calls and models video-only.

### 3. No source-to-local browse/details sync bridge yet

Phase 4 only needed `VideoEpisode -> SEpisode` for playback.

Phase 5 still needs a minimal bridge that can:

- map source-side `SVideo` into local `VideoTitle`
- map source-side `SEpisode` into local `VideoEpisode`
- insert/update those rows in the local video repositories

Without this, browse results cannot reliably open a stored details screen or player flow.

### 4. Manga detail/library UI is too manga-shaped to reuse directly

`MangaScreen` and `LibraryScreenModel` are deeply coupled to manga-only concerns such as:

- downloads
- tracking
- merge behavior
- chapter swipe actions
- reader flows

Phase 5 should build smaller parallel video screens instead of parameterizing manga detail/library screens into mixed media surfaces.

## Main Design Decisions

### Parallel source list state

Add video-specific equivalents of the manga source list interactors.

Recommendation:

- add `GetEnabledVideoSources`
- add `ToggleVideoSource`
- add `ToggleVideoSourcePin`

### Parallel source pinning

Current `SourcePreferences` already has `disabledVideoSources`, but pinned sources are still shared:

- `app/src/main/java/eu/kanade/domain/source/service/SourcePreferences.kt`

Phase 5 should add parallel pinned video source state rather than reusing manga pinned source preferences.

Reason:

- sharing pin state between manga and video sources is the wrong long-term UX
- it makes video-profile source behavior dependent on manga browsing state

### Video extensions UI is a reuse opportunity

Some extension plumbing is already video-aware:

- `app/src/main/java/eu/kanade/domain/extension/interactor/GetVideoExtensions.kt`
- `app/src/main/java/eu/kanade/domain/extension/interactor/GetVideoExtensionSources.kt`
- `app/src/main/java/eu/kanade/presentation/browse/ExtensionsScreen.kt`

This means a video extensions sub-tab may be relatively cheap to add later in Phase 5, but it should not block the first browse rollout.

### Video browse stays separate from manga browse

Phase 5 should build a separate video browse surface instead of parameterizing `BrowseTab`.

Reason:

- manga browse currently mixes feeds, migration, and manga-only models
- separate video browse keeps the code path parallel and avoids accidental media mixing

The first rollout should stay sources-first:

- start with video sources and source browse
- add a video extensions sub-tab only if reuse stays cheap
- keep feeds and migration out of the initial video browse rollout

### Keep `Updates` and `History` presentation deferred

Although video updates/history backend data exists, tab presentation remains a Phase 6 concern.

Phase 5 should not expand into those tabs.

## Workstreams

## 1. Video Source List Foundation

Create the parallel source-list layer needed for video browse.

### Planned changes

- add video source list interactors parallel to the manga source list stack
- add video pinned-source preference state
- expose enabled video catalogue sources with pin and last-used behavior

### Checklist

- [x] Add `GetEnabledVideoSources`
- [x] Add `ToggleVideoSource`
- [x] Add `ToggleVideoSourcePin`
- [x] Add parallel pinned video source preference state
- [x] Keep manga source preferences unchanged

## 2. Video Browse Paging Layer

Add the source-side paging foundation for video browse results.

### Planned changes

- add a video-specific browse paging/service layer
- support `Popular`, `Latest`, and `Search`
- keep browse results video-only

### Decisions

- do not clone manga `SourceRepository` wholesale unless implementation pressure proves it necessary
- reuse generic source/domain UI pieces where they remain media-neutral

### Checklist

- [x] Add video paging source abstraction
- [x] Add `popular` video browse flow
- [x] Add `latest` video browse flow
- [x] Add source-filtered video search flow
- [x] Keep manga browse stack untouched

## 3. Source-To-Local Video Sync Bridge

Bridge source-side browse results into stored video entities.

### Planned changes

- map `SVideo` -> local `VideoTitle`
- map `SEpisode` -> local `VideoEpisode`
- insert/update local rows for details and playback entry

### Checklist

- [x] Add `SVideo -> VideoTitle` mapping
- [x] Add `SEpisode -> VideoEpisode` mapping
- [x] Upsert browsed video titles into local storage
- [x] Upsert episode lists into local storage
- [x] Ensure details/player routing works from stored ids

## 4. Real Video Browse Tab

Replace the placeholder browse tab with a real video browse surface.

### Planned changes

- add a separate `VideoBrowseTab`
- provide a sources-first video browsing and search entry path
- optionally add a video extensions sub-tab if reuse stays cheap
- keep it independent from manga `BrowseTab`

### Checklist

- [x] Replace placeholder `VideoBrowseTab`
- [x] Add video source list screen/tab content
- [x] Add search entry path within video browse
- [x] Keep browse results scoped to video sources only
- [x] Keep feeds and migration out of the first rollout unless reuse is trivial

## 5. Video Source Browse Screen

Build the video-specific equivalent of the source browse listing screen.

### Planned changes

- add a video browse screen model
- add source-specific `Popular`, `Latest`, and `Search` listing behavior
- surface source filters where supported

### Checklist

- [x] Add video browse screen model
- [x] Add `Popular` listing behavior
- [x] Add `Latest` listing behavior
- [x] Add `Search` + filter behavior
- [x] Open video details screen from browse results

## 6. Video Title Details Screen

Build a smaller, video-specific title details screen.

### Planned changes

- add a real video details screen and screen model
- show metadata, categories, favorite state, and episode list
- support resume/open-next behavior

### Checklist

- [x] Add `VideoScreen` / details screen
- [x] Add `VideoScreenModel`
- [x] Show episode list for the selected title
- [x] Show favorite/category state
- [x] Add resume/open-next CTA when playback state exists

## 7. Episode Routing To Player

Replace debug-only episode open paths with real product routing.

### Checklist

- [x] Open player from details episode taps
- [x] Open player from continue-watching actions
- [x] Remove any reader-style assumptions from video open paths

## 8. Real Video Library Tab

Replace the placeholder library tab with a first real video library surface.

### Planned changes

- show favorited videos from the active video profile
- expose title entry and continue-watching entry points
- keep the first version narrower than manga library

### Checklist

- [x] Replace placeholder `VideoLibraryTab`
- [x] Show favorited video titles
- [x] Open details from library entries
- [x] Add continue-watching entry point
- [ ] Keep the implementation video-specific and lighter than manga library

## 9. Continue Watching And Episode Progress Indicators

Use the Phase 3 and 4 playback data to make the UI feel connected.

### Checklist

- [x] Add continue-watching entry path
- [x] Read in-progress state from video playback data on the details screen
- [x] Show completed state in episode lists
- [x] Show partial progress/resume indicators in episode lists

## 10. Search Review

Decide the minimum search shape for Phase 5 without drifting into Phase 6.

### Checklist

- [x] Support video source search in browse flows
- [x] Keep search results scoped to video only in `VIDEO` profiles
- [ ] Defer mixed/global search decisions if they add manga/video coupling

## Implementation Batches

1. Video pinned-source preference split and source-list interactors
2. Sources-first `VideoBrowseTab` shell and source list UI
3. Video browse paging layer
4. Source-to-local video sync bridge
5. Video details screen and episode routing to player
6. Real video library tab, continue watching, and progress indicators

## Test Plan

### Source list

- enabled video sources respect disabled-video-source preferences
- pinned video sources remain independent from manga pinned source state
- last-used video source behavior stays scoped to video browse

### Browse repository

- popular/latest/search listing calls the correct `VideoCatalogueSource` methods
- source filters flow into video search requests correctly
- browse results remain video-only

### Sync bridge

- source video/title data upserts into local video tables
- source episode data upserts into local video episode tables
- repeat sync updates existing rows rather than duplicating them

### Details and routing

- opening a browse result lands on a video details screen
- opening an episode launches `VideoPlayerActivity`
- continue-watching opens the correct stored episode

### Library

- favorited videos appear in `VideoLibraryTab`
- library entries open details
- in-progress playback state is visible through continue/progress UI

## Rollout Notes

- keep `VideoUpdatesTab` and `VideoHistoryTab` placeholder-backed for this phase
- keep the player entry internal to video flows only, not manga flows
- prefer smaller video-specific screens over parameterizing manga UI too early
- avoid broad browse/feed reuse if it forces manga/video model coupling
- a video extensions sub-tab is optional for Phase 5 if it can reuse the existing video-aware extension UI cheaply

## Exit Criteria

Phase 5 is complete when all of the following are true.

- `VideoBrowseTab` is a real video browse surface
- `VideoLibraryTab` is a real video library surface
- video titles have a real details screen and episode list
- browse/details/library episode actions route into `VideoPlayerActivity`
- continue-watching exists in the video shell
- episode rows expose watched/completed/progress state
- search inside video browse flows works for video sources
- `VideoUpdatesTab` and `VideoHistoryTab` remain deferred and untouched by this phase

## Current Status

- Phase 5 planning is now defined in this document
- Batch 1 foundation is complete and verified
- video source-list interactors and separate pinned/last-used video source state are now implemented
- `VideoBrowseTab` now has a real sources-first shell with a verified parallel `GetRemoteVideo` paging path
- browse-result taps now route into a real `VideoScreen`, not a placeholder or debug-only path
- `SyncVideoWithSource` now provides a conservative source-to-local sync bridge for title details and episode upserts
- `VideoScreen` now observes stored playback state per title to drive a primary start/resume action and episode-level progress indicators
- `VideoScreen` currently supports favorite/category state, manual refresh, synced episode listing, playback-aware episode metadata, and direct player routing
- `VideoLibraryTab` is now a real tab backed by favorite video titles and opens `VideoScreen` entries
- `VideoLibraryTab` also exposes a first continue-watching card driven by stored video history, routing directly to `VideoPlayerActivity`
- video library rows now surface first-pass watch-aware state from stored episode/playback data, including unread counts, in-progress markers, and partial-progress bars
- video library rows now also expose a direct start/resume action that opens the computed stored episode in `VideoPlayerActivity`
- video browse now has a parallel video-only global search path, launched from the video sources tab and `VideoBrowseTab` reselect, with results scoped to video catalogue sources only
- the current details/library flow intentionally avoids destructive episode deletion and keeps the first library surface narrower than manga library
- Phase 4 already provides the player endpoint and playback persistence, and details episode taps now use that stored-id path directly
- the current playback-aware details slice is verified by `./gradlew :app:compileDebugKotlin` and focused video unit tests

## Handoff To Phase 6

Once Phase 5 is complete, the next doc should break down Phase 6 into concrete work items covering:

- real `VideoUpdatesTab` presentation using `VideoUpdatesRepository`
- real `VideoHistoryTab` presentation using `VideoHistoryRepository`
- video-specific settings/preferences surfaces
- notification, shortcut, and quick-action review for `VIDEO` profiles
