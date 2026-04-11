# Video Phase 0: Architecture Lock

## Purpose

This document locks the architectural decisions that must be settled before implementation begins.

Phase 0 exists to prevent rework in Phase 1 and beyond. The goal is to remove ambiguity around profile typing, model separation, source contracts, player boundaries, and update/history semantics for video.

This is a decision document first and a task document second.

## Phase 0 Objectives

- Lock the `video` profile type as a first-class product mode.
- Lock full separation between manga and video models.
- Lock the direction of the video source API.
- Lock the built-in playback boundary.
- Lock watch-progress persistence direction.
- Lock the meaning of `Library`, `Updates`, `History`, `Browse`, and `More` inside video profiles.
- Lock the first vertical slice that later phases must deliver.

## Locked Decisions

### 1. Product Separation

- The app will support at least two profile types: `manga` and `video`.
- Existing profiles migrate to `manga`.
- Manga profiles keep existing behavior.
- Video profiles are a separate product mode inside the same app.
- Manga and video content must not mix in the same profile.
- `ProfileType` is immutable after creation.

### 2. Video Profile Shell

Video profiles include these tabs from the start:

- `Library`
- `Updates`
- `History`
- `Browse`
- `More`

The labels may match manga profiles, but the data, actions, view models, and behavior behind them should be video-specific.

### 3. Full Parallel Model Stack

Video is not an alternate rendering mode for manga.

- Do not reuse `SManga`, `SChapter`, or `Page` for video sources.
- Do not represent video titles and episodes internally as `Manga` and `Chapter`.
- Do not overload page-based reader contracts for playback.
- Do not use manga-shaped adapters as the primary internal design.

Video gets its own source models, app models, domain models, repositories, queries, and playback state.

### 4. Player Boundary

- Built-in playback is the required path.
- Built-in playback uses AndroidX Media3 only.
- External player support is a design target, not a ship blocker.
- Playback must be handled by a dedicated player activity/screen.
- `ReaderActivity` must remain manga-only.
- Subtitle support is deferred out of the initial video API and out of v1.

### 5. Scope Constraints

- Streaming only
- One video per episode/chapter target
- No offline downloads
- No local video source
- No DRM support in the first rollout

## Why This Must Be Locked First

The current architecture is heavily manga/page oriented.

Relevant current seams:

- `source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/Source.kt`
  - current source contract is built around `SManga`, `SChapter`, and `getPageList()`
- `source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/SourceFactory.kt`
  - factories currently emit `List<Source>` only
- `app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt`
  - extension loading and compatibility assume `Source`/`SourceFactory`
- `app/src/main/java/mihon/feature/profiles/core/Profile.kt`
  - profiles currently have no type
- `data/src/main/sqldelight/tachiyomi/data/profiles.sq`
  - profile storage currently has no product-type column
- `app/src/main/java/mihon/feature/profiles/core/ProfileDatabase.kt`
  - profile mapping and CRUD currently assume untyped profiles
- `app/src/main/java/mihon/feature/profiles/core/ProfileManager.kt`
  - profile creation and shell defaults currently assume one shared product mode
- `core/common/src/main/kotlin/mihon/core/common/HomeScreenTabs.kt`
  - home-tab defaults are shared globally across profiles
- `data/src/main/java/tachiyomi/data/manga/MangaRepositoryImpl.kt`
  - current library/title data path is manga-only
- `data/src/main/java/tachiyomi/data/updates/UpdatesRepositoryImpl.kt`
  - current updates flow is chapter-oriented
- `data/src/main/java/tachiyomi/data/history/HistoryRepositoryImpl.kt`
  - current history flow is chapter-reading oriented

If these boundaries are not decided up front, Phase 1 risks introducing profile typing that later has to be reworked once the video data model and source model are finalized.

## Architecture Decisions To Lock

## A. Profile Typing

### Decision

Profiles need a strongly typed product mode.

Recommended shape:

- `ProfileType.MANGA`
- `ProfileType.VIDEO`

Recommended storage direction:

- add a `type` column to `profiles`
- keep the Kotlin layer strongly typed with an enum
- migrate all existing rows to `manga`

### Affected code areas

- `data/src/main/sqldelight/tachiyomi/data/profiles.sq`
- `app/src/main/java/mihon/feature/profiles/core/Profile.kt`
- `app/src/main/java/mihon/feature/profiles/core/ProfileDatabase.kt`
- `app/src/main/java/mihon/feature/profiles/core/ProfileManager.kt`
- `app/src/main/java/mihon/feature/profiles/ui/ProfilePickerScreen.kt`
- profile creation and management screens

### Phase 0 output

- exact `ProfileType` values
- migration behavior for existing profiles
- profile type immutability rules

Recommendation:

- keep profile type immutable after creation in the first implementation

## B. Video Source API

### Decision

The video source API is fully parallel from the start.

This means the current `Source` interface should not be the direct base contract for video playback sources, because it bakes in manga/page assumptions.

### Required direction

Define a parallel contract family such as:

- `VideoSource`
- `VideoCatalogueSource`
- `VideoSourceFactory`
- `SVideo`
- `SEpisode`
- `VideoStream`

Exact names can change, but the separation principle is locked.

Current proposed source-api shape in the repo:

- `VideoSource`
  - `suspend fun getVideoDetails(video: SVideo): SVideo`
  - `suspend fun getEpisodeList(video: SVideo): List<SEpisode>`
  - `suspend fun getStreamList(episode: SEpisode): List<VideoStream>`
- `VideoCatalogueSource : VideoSource`
  - `val supportsLatest: Boolean`
  - `suspend fun getPopularVideos(page: Int): VideosPage`
  - `suspend fun getSearchVideos(page: Int, query: String, filters: FilterList): VideosPage`
  - `suspend fun getLatestUpdates(page: Int): VideosPage`
  - `fun getFilterList(): FilterList`
- `VideoSourceFactory`
  - `fun createSources(): List<VideoSource>`
- `ConfigurableVideoSource : VideoSource`
  - `fun getSourcePreferences(): SharedPreferences`
  - `fun setupPreferenceScreen(screen: PreferenceScreen)`

Current proposed model set in the repo:

- `SVideo`
- `SEpisode`
- `VideosPage`
- `VideoStream`
- `VideoRequest`
- `VideoStreamType`

### What the contract must support

- browse/search titles
- fetch title details
- fetch episode list
- resolve playable stream variants for an episode
- return required headers, cookies, referer, or request metadata
- expose enough data for external-player handoff when possible

Initial scope note:

- Subtitle models are intentionally excluded from the first source API revision.
- Subtitles can be introduced later in a dedicated API revision if needed.
- The first API revision uses suspend-only contracts and does not add parallel Rx APIs.
- The first API revision models external playback metadata through `VideoRequest(headers)` rather than a separate handoff object.
- The first API revision mirrors configurable-source support for video via `ConfigurableVideoSource`.

### Affected code areas

- `source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/Source.kt`
- `source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/SourceFactory.kt`
- `app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt`
- source manager registration and discovery

### Phase 0 output

- base interface set
- source model set
- extension factory/loading strategy
- compatibility/versioning strategy for video extensions

Current status:

- initial source interfaces and models have been added under `source-api`
- configurable video-source preference hooks have been added under `source-api`
- they compile successfully in `:source-api:compileDebugKotlinAndroid`

## C. Extension Loading Strategy

### Decision

Video extensions must be discoverable without pretending to be manga `Source`s.

Locked direction:

- Video extensions are separate packages from manga extensions in v1.
- Extension repositories should keep one shared index format with a package-level `type` field.
- Installed packages should also expose type metadata so runtime loading does not depend only on repository state.

### Open implementation directions

1. Broaden extension loading to support a more generic extension entry contract.
2. Keep current manga source loading intact and add a parallel video extension entry path.
3. Introduce a common super-interface for loaded source-like capabilities, then register manga and video separately.

Recommendation:

- preserve the current manga extension path as much as possible
- add a parallel video loading path rather than trying to retrofit `Source` into a shared abstraction too early
- keep one shared install/trust/update flow, but split runtime registration by extension type

Runtime architecture recommendation:

- keep a shared `ExtensionManager` for package discovery, trust, install, update, and repository refresh flows
- expose typed extension flows rather than a single manga-shaped installed/available model set
- keep manga runtime source registration in `SourceManager`
- add a parallel `VideoSourceManager` for registered video sources
- keep stub-source persistence parallel as well, rather than reusing manga `StubSource`
- keep source visibility preferences parallel as well, rather than reusing one shared hidden-source key

### Affected code areas

- `app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt`
- extension models storing loaded sources
- `app/src/main/java/eu/kanade/tachiyomi/extension/ExtensionManager.kt`
- `domain/src/main/java/tachiyomi/domain/source/service/SourceManager.kt`
- source manager registration

### Phase 0 output

- chosen extension entry strategy
- lib versioning policy for video support
- package and repository type metadata strategy
- typed runtime extension/source manager split

### Recommended runtime split

Installed and available extension models should not stay manga-shaped.

Recommended direction:

- keep a shared top-level extension concept for install/update/trust flows
- add typed runtime models such as:
  - `MangaExtension.Installed`
  - `VideoExtension.Installed`
  - `MangaExtension.Available`
  - `VideoExtension.Available`
- if a small shared base model is useful, limit it to package metadata only
- do not keep a single `Extension.Installed` that always carries `List<Source>`

Why:

- `Extension.Installed` and `Extension.Available` are currently hard-wired to manga `Source`
- `GetExtensionSources` assumes manga `Source` enable/disable behavior
- `Extensions` aggregation models are manga-only today
- keeping the runtime model typed will avoid leaking manga contracts into video package management

Recommended manager shape:

- `ExtensionManager`
  - shared install, update, uninstall, trust, repo refresh, and package metadata flow
- `SourceManager`
  - manga sources only
- `VideoSourceManager`
  - video sources only

Recommended loader behavior:

- loader should inspect extension type metadata before instantiation
- manga packages instantiate `Source` / `SourceFactory` as today
- video packages instantiate `VideoSource` / `VideoSourceFactory`
- registration should happen into the type-appropriate manager only

Visibility preference direction:

- keep separate hidden-source preference sets for manga and video per profile
- do not reuse one shared hidden-source key across both media types
- seed new video profiles against video-source visibility only

Source preference direction:

- keep a separate video source preference provider namespace rather than reusing manga source preference keys implicitly
- mirror `ConfigurableSource` support for video sources in the first API revision

## D. App, Domain, and Data Models

### Decision

Video gets parallel internal models from the start.

Recommended initial model families:

- app/domain:
  - `VideoTitle`
  - `VideoEpisode`
  - `VideoTitleUpdate`
  - `VideoHistoryEntry`
  - `VideoPlaybackState`
- data/storage:
  - video title table(s)
  - video episode table(s)
  - video history table or view path
  - video updates table or view path
  - playback progress table

Exact table/model names can change, but parallel storage is the locked direction.

### Why

- current repositories are manga-only
- current update semantics are chapter-based
- current history semantics are read-session based
- current progress semantics use `last_page_read`

Trying to reuse them would weaken the clean separation already chosen at the profile level.

Categories direction:

- Video profiles should support the same category UX as manga profiles from v1.
- Reuse the existing category concept and profile scoping.
- Use video-specific title-category relations rather than reusing `mangas_categories`.

Runtime repository direction:

- add video repositories and queries parallel to manga library/history/updates repositories
- add a video stub-source repository parallel to the manga stub-source repository if video source fallback labels are needed

### Affected code areas

- manga repositories and query structure as a reference only
- new video repositories and query files
- profile data cleanup logic in `ProfileDatabase.clearProfileData()`
- backup/restore paths for typed profiles

### Phase 0 output

- initial video model list
- initial storage/query plan
- cleanup and backup strategy for video profile data

## E. Playback Contract

### Decision

Playback state is separate from manga reading progress.

Recommended minimal playback state:

- `profileId`
- `episodeId`
- `positionMs`
- `durationMs`
- `completed`
- `lastWatchedAt`

Additional fields to consider:

- `selectedStreamId` or quality hint
- `playbackSpeed`

### Decision points to lock

- completion threshold policy
- resume behavior
- when to persist progress
- whether history is updated continuously, on pause, on completion, or all three

### Recommendation

- persist progress every 10 seconds during playback and on pause/stop
- mark completed at 90% watched
- keep completion and resume state separate
- allow external-player handoff to export the richest metadata possible, while keeping built-in playback as the required supported path

### Affected code areas

- new player view model/screen model
- new playback-state repository
- new history/update integration points

### Phase 0 output

- playback state schema
- completion threshold definition
- resume/writeback rules

## F. Video `Updates` Semantics

### Decision

`Updates` exists in video profiles from the start and must have explicit video semantics.

### Recommended meaning

In video profiles, `Updates` should represent newly available or newly fetched episodes for tracked/favorited video titles.

It should not inherit manga chapter semantics blindly.

Locked direction:

- Video `Updates` gets its own screen logic, repositories, and query path.
- Video `Updates` has no filters in v1.
- Filtering can be introduced later if real use cases appear.

### Questions to settle in Phase 0

- what counts as a new video update
- whether updates are episode-based only or title-plus-episode based
- how sort order should behave

### Current reference seam

- `data/src/main/java/tachiyomi/data/updates/UpdatesRepositoryImpl.kt`
  - current implementation is chapter/update-view based and cannot be reused as-is

### Phase 0 output

- a written definition of video update semantics
- the minimum data required to support the tab

## G. Video `History` Semantics

### Decision

History for video profiles should be watch-oriented, not page-read oriented.

### Recommended meaning

- recently watched episodes
- last watched timestamp
- optional watch duration/session information
- resume affordance from the last known position

Locked direction:

- Opening from video history should route to the player, not `ReaderActivity`.
- Resume position should come from video playback state, not `last_page_read`.

### Current reference seam

- `data/src/main/java/tachiyomi/data/history/HistoryRepositoryImpl.kt`
  - current history flow is chapter-reading based and should be treated as a reference, not a reusable implementation

### Phase 0 output

- written semantics for video history rows
- relation between history and playback state

## H. Video Library Semantics

### Decision

Video profiles need a library model that is title-based but watch-aware.

### Questions to settle in Phase 0

- what badges/counters appear on library items
- how categories behave for video titles

Recommended v1 row state:

- `unwatchedCount`
- `hasInProgress`
- `latestEpisodeAt`

Category direction:

- keep the existing category UX and profile scoping
- use video title-category relations parallel to `mangas_categories`

### Current reference seam

- `data/src/main/java/tachiyomi/data/manga/MangaRepositoryImpl.kt`
  - useful as a structural reference, but not as the target implementation model

### Phase 0 output

- a written library-state definition for video titles

## First Vertical Slice To Lock

This vertical slice is the target all later phases should build toward.

1. Create a `video` profile.
2. Install or load one video source.
3. Browse one video title.
4. Open its details screen.
5. Open one episode.
6. Resolve a playable stream.
7. Play it inside the app.
8. Persist resume position.
9. Mark the episode completed after the threshold is crossed.
10. Show the result in video `Library`, `History`, and `Updates` as defined.

## Finalized Decisions

The remaining Phase 0 architecture decisions have now been resolved.

### 1. Profile type storage encoding

Locked decision:

- use an integer-backed enum-like value in SQL with a strongly typed Kotlin enum wrapper

Recommendation:

- use `ProfileType` in Kotlin and store it as a stable integer value through SQLDelight adapters
- keep existing profiles as `MANGA` during migration

### 2. Extension entry strategy

Locked decision:

- use a parallel video factory/interface path with minimal disruption to existing manga extension loading

Recommendation:

- parallel video factory path with minimal disruption to existing manga extension loading

Locked constraints:

- video extensions are separate packages
- shared repo format should expose package type metadata
- installed packages should expose type metadata too

Locked runtime direction:

- shared `ExtensionManager`
- typed extension flows/models
- `SourceManager` for manga
- `VideoSourceManager` for video

### 3. Built-in player stack

Locked decision:

- use AndroidX Media3 for built-in playback

Required capability checklist:

- HLS and direct media support
- custom headers and referer
- lifecycle-safe playback
- quality selection hooks

Recommended stack:

- `androidx.media3:media3-exoplayer`
- `androidx.media3:media3-ui`
- `androidx.media3:media3-datasource-okhttp`

Integration direction:

- host `PlayerView` via `AndroidView`
- reuse the app `OkHttpClient` and cookie jar through the Media3 OkHttp datasource bridge
- consume `VideoRequest.url` and `VideoRequest.headers` as the input for Media3 datasource setup

### 4. Subtitle support depth for v1

Locked decision:

- subtitles are deferred out of the initial source API and out of v1

Recommendation:

- revisit subtitles in a later API revision instead of adding unused surface area now

### 5. External player handoff payload

Locked decision:

- use `VideoRequest(url, headers)` as the source-level playback and handoff payload

Recommendation:

- export the richest metadata possible when feasible, but treat built-in playback as the only required supported path

Current proposed payload direction:

- use `VideoRequest(url, headers)` as the source-level handoff payload
- map that request into built-in playback directly
- reuse the same request metadata for external player handoff where supported by the target app/device

### 6. Profile type mutability

Locked decision:

- `ProfileType` is immutable after creation in the first implementation

Recommendation:

- users should create a new profile instead of converting an existing one between manga and video

### 7. Video browse/extensions screen strategy

Locked decision:

- video profiles should use a separate browse/extensions screen
- shared UI components can be reused, but the screen model and data wiring should be video-specific

Recommendation:

- do not reuse `BrowseTab` as-is, because it currently bundles manga feeds, manga sources, extensions, and migration flows
- do not reuse the manga extension screen model directly; keep separate video extension screen models and lists

### 8. Typed extension/runtime model split

Locked decision:

- runtime extension models should be typed and should not stay centered on manga `Source`

Recommendation:

- keep only package metadata shared at the base layer
- split installed/available/runtime source-bearing models by media type

### 9. Source manager split

Locked decision:

- manga and video should use separate runtime source managers

Recommendation:

- keep the current `SourceManager` manga-only
- add a parallel `VideoSourceManager` rather than broadening the current interface into a mixed media manager

### 10. Source visibility preference split

Locked decision:

- per-profile source visibility should use separate hidden-source preference sets for manga and video

Recommendation:

- keep separate keys and separate initialization behavior to avoid collisions and manga-specific assumptions in video profiles

### 11. Parallel app/domain/data model direction

Locked decision:

- video app/domain/data stays fully parallel from the start

Recommendation:

- keep source-side models (`SVideo`, `SEpisode`) separate from app/domain/data models (`VideoTitle`, `VideoEpisode`, etc.)
- do not introduce a shared `MediaTitle` / `MediaEpisode` abstraction in the first implementation

### 12. Video history semantics

Locked decision:

- history rows represent recently watched episodes with resume-oriented behavior

Definition:

- one row represents the latest watch state for an episode/title context
- history sorting is by `lastWatchedAt` descending
- opening a history row launches the video player
- resume position comes from `VideoPlaybackState`, not page-read state

### 13. Video updates semantics

Locked decision:

- updates represent newly available or newly fetched episodes for library video titles

Definition:

- updates are episode-based rows associated with tracked or favorited video titles
- the v1 tab has no filters
- sort order is newest episode update signal first
- opening an update row launches the video player for the episode

### 14. Video library semantics

Locked decision:

- library rows are title-based and watch-aware

Definition:

- rows represent `VideoTitle`
- v1 row state includes `unwatchedCount`, `hasInProgress`, and `latestEpisodeAt`
- categories use the same UX as manga, but video title-category relations are stored separately

### 15. Playback progress and completion rules

Locked decision:

- playback progress is stored separately from manga reading progress

Definition:

- save progress every 10 seconds during playback
- save again on pause and stop
- mark complete at 90% watched
- completion and resume state are separate concerns

### 16. First vertical slice

Locked decision:

- the first implementation milestone defined in this document is the required Phase 0 vertical-slice target

## Risks

## High Risk

- over-designing a shared abstraction between manga and video too early
- trying to keep `Source` as the common entry point and leaking page-based assumptions into video
- unclear `Updates` semantics causing repeated data-model churn
- unclear extension entry strategy causing loader and manager rework
- leaving runtime extension models manga-shaped and forcing type branching everywhere
- under-specifying playback progress and completion rules

## Medium Risk

- video shell feeling too similar to manga shell despite separate profile typing
- external-player behavior varying heavily across apps and devices

## Low Risk

- adding profile typing itself should be manageable because profiles are already stored separately and existing rows can default to `manga`

## Exit Criteria

Phase 0 is complete when all of the following are true.

- `ProfileType` is chosen and documented.
- The video source model set is chosen and documented.
- The extension loading strategy for video is chosen and documented.
- The typed extension/runtime model split is chosen and documented.
- The parallel app/domain/data direction is chosen and documented.
- Playback progress schema and completion rules are chosen and documented.
- `Library`, `Updates`, and `History` semantics for video are documented.
- Video extension package and repository typing are documented.
- The initial `source-api` video contract compiles and is documented.
- The first vertical slice is locked and unambiguous.
- No Phase 1 task depends on unresolved architecture decisions.

Current status:

- all Phase 0 architecture decisions are now locked in this document
- the initial `source-api` video contract exists in the repo and compiles
- the next step is Phase 1 execution, not more Phase 0 architecture work

## Implementation Handoff To Phase 1

Once Phase 0 is complete, the next doc should break down Phase 1 into concrete work items covering:

- profile schema migration
- profile model updates
- profile creation UI
- profile switching behavior
- per-type home-tab defaults
- source visibility seeding by profile type
- typed-profile backup/restore handling

## Tracking Checklist

- [x] Lock `ProfileType` shape and storage
- [x] Lock video extension entry/loading strategy
- [x] Lock typed extension/runtime model split
- [x] Lock video source interface/model set
- [x] Lock source manager split
- [x] Lock parallel app/domain/data model direction
- [x] Lock playback progress model and completion rules
- [x] Lock video `Updates` semantics
- [x] Lock video `History` semantics
- [x] Lock video `Library` semantics
- [x] Lock video extension type metadata strategy
- [x] Lock video browse/extensions screen strategy
- [x] Lock first vertical slice
