# Video Support Plan

## Purpose

This document tracks the high-level plan for adding video support to the app.

The feature will be built as a parallel media stack, not as an extension of the current image reader stack. Existing manga behavior should remain unchanged for normal profiles. Video behavior will live behind a new profile type and a new source type.

This file is the top-level roadmap. Each implementation phase should get its own follow-up markdown file under `docs/` once work starts.

## Confirmed Product Decisions

- Video support is a separate media stack.
- Video is streaming-only for now.
- No offline downloads in the initial rollout.
- No local video support in the initial rollout.
- Built-in playback is the default.
- Built-in playback uses AndroidX Media3.
- External player support is a design target, not a ship blocker.
- The content model is one video per chapter/episode.
- Video content must not mix with manga content in the same UI surfaces.
- Video content will live in a new profile type dedicated to video.
- Existing manga profiles must continue to behave exactly as they do now.
- Video sources will use a new source type dedicated to video.
- Video profiles include `Library`, `Updates`, `History`, `Browse`, and `More` from the start.
- Video uses fully parallel source, app, domain, and data models from the start.
- `ProfileType` is immutable after profile creation.
- Video extensions must be separate packages.
- Extension repositories should use one shared format with a package-level `type` field.
- Video `Updates` has separate screen logic and no filters in v1.
- Subtitles are deferred out of the initial source API and out of v1.
- Video categories should support the same UX as manga profiles.
- Runtime extension models should be typed and not stay centered on manga `Source`.
- The app should keep a shared `ExtensionManager`, but use separate runtime source managers for manga and video.
- Per-profile source visibility should use separate hidden-source preference keys for manga and video.
- Video extension browsing should use separate video screen models, reusing shared rendering components only where useful.

## Current Architecture Constraints

The current app is strongly image-reader oriented.

- `source-api/.../Source.kt` models reading as `getPageList(chapter)`.
- `source-api/.../model/Page.kt` is image-oriented through `imageUrl`, progress, and image-ready states.
- `app/.../ui/reader/model/ReaderPage.kt` wraps pages as `InputStream`-backed image payloads.
- `app/.../ui/reader/loader/ChapterLoader.kt` only branches into image/local/archive/epub/http reader loaders.
- `app/.../data/download/Downloader.kt` is tightly coupled to `HttpSource + Page + image + chapter archive` semantics.
- `app/.../ui/reader/ReaderActivity.kt` and related viewer classes assume page-based image rendering.
- `app/build.gradle.kts` does not currently include a built-in video playback stack.

Because of this, video support should not be added by extending `Page`, `ReaderPage`, or `ReaderActivity` beyond recognition.

## Design Direction

### 1. Separate by profile type

Profiles are already a major boundary in the app. Today, profiles are generic and data separation is mostly achieved through `profile_id` in storage. For video support, profiles should also separate product behavior.

Planned direction:

- Add a profile type field to the profile model and profile table.
- Introduce at least two profile types:
  - `manga`
  - `video`
- Keep the existing default profile as `manga`.
- Restrict normal manga flows to `manga` profiles.
- Expose video-only flows when the active profile type is `video`.

This gives a clean separation for:

- home tabs
- source visibility
- library/history/updates behavior
- settings surfaces
- backup/restore behavior
- future feature gating

### 2. Separate by source type

Video should not reuse the current page/image source contract.

Planned direction:

- Keep existing `Source` and `CatalogueSource` behavior intact for manga.
- Introduce a new source type for video sources.
- Video sources should be browseable/searchable within video profiles, but playback should use a video-specific chapter resolution contract instead of `Page`.
- Keep the current `SourceManager` manga-only and add a parallel `VideoSourceManager`.

At a high level, a video source needs to answer different questions than a manga source:

- what series are available
- what episodes exist
- what stream variants are available for an episode
- what request headers/cookies are required
- what subtitles or alternate streams exist
- whether the stream can be handed to an external player

### 3. Separate by playback stack

Video playback should use a dedicated player flow.

Planned direction:

- Add a dedicated player activity/screen for video.
- Use AndroidX Media3 as the built-in playback stack.
- Keep a small adapter layer that can also hand the resolved stream off to an external player.
- Do not attempt to fold video playback into `ReaderActivity`.

## Proposed Architecture

## Profile Layer

Add profile typing and use it as the top-level feature gate.

Expected responsibilities:

- The active profile type decides which product shell is shown.
- Manga profiles keep current tabs and current data behavior.
- Video profiles get a video-specific shell and data access rules.
- Profile creation UI must ask for the profile type.
- Profile switching must be aware of profile type differences.

Expected core changes:

- profile database schema
- profile model
- profile creation/edit flows
- startup/profile picker behavior
- profile-specific home-tab defaults
- source visibility defaults
- video-profile shell defaults including `Library`, `Updates`, `History`, `Browse`, and `More`

## Source Layer

Introduce a dedicated video source contract rather than stretching the manga one.

Suggested shape:

- `VideoCatalogueSource` or equivalent browse/search contract for video titles
- `VideoSource` or equivalent playback contract for episodes and streams
- `SVideo` for source-level video title rows
- `SEpisode` for source-level episode rows
- `VideoStream` model for a playable stream
- optional `ExternalPlaybackTarget` or external-playable metadata

Current recommendation:

- Keep the video source API fully parallel from the start.
- Do not reuse `SManga`, `SChapter`, or `Page` in the video stack.
- Keep video browse, episode, and stream resolution contracts separate from manga contracts.
- Keep runtime extension models typed so video packages do not carry manga `Source` lists.

Current Phase 0 status:

- initial parallel video source-api scaffolding has been added under `source-api`
- the current proposed contract includes `VideoSource`, `VideoCatalogueSource`, `VideoSourceFactory`, `ConfigurableVideoSource`, `SVideo`, `SEpisode`, `VideosPage`, `VideoStream`, and `VideoRequest`

## Playback Layer

Add a dedicated player stack.

Expected responsibilities:

- resolve episode streams from the source
- select stream quality
- attach cookies/headers/referer when needed
- manage playback state
- resume from saved position
- mark episode watched using a completion threshold
- support external-player handoff when possible

Expected core pieces:

- `PlayerActivity` or equivalent
- `VideoPlayerScreen` and screen model/view model
- playback resolver/use case
- player preference set
- playback progress persistence
- episode open-routing entry point

## App, Domain, and Data Layer

Keep data separated by both profile type and model type.

Planned direction:

- Continue using profile-scoped data where it already exists.
- Add profile type so feature-specific queries and UI behavior can branch early.
- Introduce parallel app/domain/data models for video titles and episodes.
- Prefer separate repositories, queries, and storage paths for video flows.
- Add dedicated playback-progress persistence for video.
- Do not reuse `last_page_read` as the only source of truth for video position.
- Keep video stub-source and source-label fallback paths parallel to manga where needed.

Video should not be represented internally as manga-shaped data with thin adapters around it. Clean separation is preferred, even if it means more up-front work.

Suggested model direction:

- `VideoTitle`
- `VideoEpisode`
- `VideoTitleUpdate`
- `VideoHistoryEntry`
- `VideoPlaybackState`

Suggested playback state model:

- `chapterId`
- `positionMs`
- `durationMs`
- `completed`
- `lastWatchedAt`

Recommended v1 playback rules:

- persist progress every 10 seconds during playback
- persist again on pause/stop
- mark completed at 90% watched

The exact relationship between video completion and any existing generic read-state conventions should be explicit at the video model layer rather than inherited accidentally from manga storage.

## UI Separation Strategy

The goal is not just filtered data. The app should feel like two product modes selected by profile type.

### Manga profiles

- Keep the current navigation shell.
- Keep current library/history/updates/reader behavior.
- Hide video-only features.

### Video profiles

- Show a video-specific shell.
- Replace reader entry points with player entry points.
- Use video-only sources.
- Use video-specific settings where needed.
- Do not surface manga-only concepts that do not apply.

This likely means home tabs will eventually diverge by profile type, even if phase 1 starts with a minimal shell reuse.

Locked direction:

- Manga profiles keep the current shell.
- Video profiles start with `Library`, `Updates`, `History`, `Browse`, and `More`.
- These may share high-level tab names with manga profiles, but their screens, models, actions, and state handling should be video-specific.
- Video browse/extensions should use a separate screen, reusing shared UI components where useful.

## Non-Goals For Initial Rollout

- offline downloads
- local video source
- DRM-protected playback
- casting support
- picture-in-picture unless it comes nearly for free from the chosen player stack
- background audio mode unless it is needed early
- subtitles
- mixing manga and video in the same profile
- retrofitting current manga sources to also support video

## Phase Plan

## Phase 0: Planning and Architecture Lock

Goal: freeze the boundaries before implementation starts.

Status: complete

Deliverables:

- finalized profile type model
- finalized video source contract direction
- finalized playback stack choice
- agreed phase breakdown
- agreed migration and compatibility approach for extensions
- agreed parallel app/domain/data model strategy
- agreed extension type metadata strategy
- agreed video browse/extensions screen strategy
- agreed typed runtime extension/source manager split

Checklist:

- [x] Add profile type to the data model design
- [x] Define the full parallel video model set (`SVideo`, `SEpisode`, app/domain/data models)
- [x] Define the minimum `VideoStream` model
- [x] Lock AndroidX Media3 playback integration details
- [x] Define the external-player handoff strategy
- [x] Define playback progress persistence strategy
- [x] Define video `Updates` semantics
- [x] Define extension package and repository type metadata
- [x] Define video browse/extensions screen strategy
- [x] Define typed runtime extension models and manager split

## Phase 1: Profile Type Foundation

Goal: make the app profile-aware by product type.

Status: complete

Scope:

- add profile type to the schema and models
- keep existing profiles as `manga`
- update profile creation/edit/picker flows
- make startup and navigation react to profile type
- define separate default tabs/settings seed behavior for video profiles
- define the initial video-profile shell with `Library`, `Updates`, `History`, `Browse`, and `More`
- keep manga-only settings surfaces out of `video` profiles during this phase

Locked constraints:

- profile type is immutable after creation
- source visibility should use separate hidden-source keys for manga and video

Why this phase exists:

- It creates the top-level separation required to keep manga behavior untouched.
- It reduces later conditional logic by making profile type explicit from the start.

Checklist:

- [x] Add `type` to profile storage
- [x] Migrate existing profiles to `manga`
- [x] Update profile creation UI to choose a type
- [x] Update profile bundle/view models to expose the type
- [x] Make home-tab defaults depend on profile type
- [x] Seed video profiles with `Library`, `Updates`, `History`, `Browse`, and `More`
- [x] Route `video` profiles into placeholder video tabs instead of manga tabs
- [x] Define separate hidden-source keys and video visibility defaults
- [x] Review backup/restore behavior for typed profiles
- [x] Validate startup/auth and fallback behavior for typed profiles
- [x] Hide manga-only settings surfaces from `video` profiles in settings navigation/search

## Phase 2: Video Source API Foundation

Goal: establish a dedicated extension/source contract for video.

Status: complete

Note: initial source-api scaffolding from Phase 0 covered the early interface work. Phase 2 is now complete and established the runtime plumbing: typed extension models, loader branching, `VideoSourceManager`, backend-only video extension registration, and separate video source preference wiring.

Scope:

- define new video source interfaces and models
- keep browse/listing contracts fully parallel from manga source types
- make repo/package metadata type-aware with backward-compatible `manga` defaulting
- split runtime extension models by media type
- update source manager plumbing to recognize video sources
- keep current manga extension UI manga-only while video extension plumbing remains backend-only
- update extension compatibility/versioning strategy

Locked constraints:

- video extensions are separate packages
- subtitles are not part of the first source API revision
- missing repo/package `type` defaults to `manga`
- no visible video extension browsing in this phase

Why this phase exists:

- The source API is the foundation for everything else.
- Playback and UI should not be built before stream resolution is modeled properly.

Checklist:

- [x] Define video source interfaces
- [x] Define stream models
- [x] Decide relationship to `CatalogueSource`
- [x] Parse package/repo `type` with backward-compatible `manga` fallback
- [x] Split runtime extension models by media type
- [x] Update source manager plumbing to keep manga/video registration separate
- [x] Add `VideoSourceManager` and typed source registration
- [x] Update extension loading compatibility policy and loader branching
- [x] Keep current extension UI manga-only while adding backend-only video extension plumbing
- [x] Add video source preference provider and configurable-source wiring
- [x] Write extension author guidance for video sources

## Phase 3: Video Domain and Data Wiring

Goal: make video titles and episodes storable and queryable under video profiles.

Status: complete

Scope:

- establish the parallel app/domain/data path for video titles and episodes
- keep video profile data separated cleanly
- add playback state persistence
- establish video-specific library/history/update data paths

Locked constraints:

- categories should support the same UX as manga profiles
- reuse the existing `categories` table rather than duplicating category definitions
- store watched/completed state on `video_episodes`
- store resume/progress state in a dedicated `video_playback_state` table

Why this phase exists:

- Even with separate profiles, current repositories and views were designed around manga reading assumptions.
- This phase creates the parallel video app/domain/data foundation rather than trying to reuse manga-shaped internals.

Checklist:

- [x] Define app/domain/data models for video titles and episodes
- [x] Add storage/query strategy for video titles and episodes
- [x] Add video title-category relations
- [x] Confirm video stub-source and fallback label strategy is not yet needed for Phase 3 backend/data work
- [x] Add playback-progress persistence
- [x] Add watched/completed update logic
- [x] Define video history query behavior
- [x] Define video updates query behavior
- [x] Defer video library counters and badges to later video UI phases
- [x] Review backup serialization for video data

## Phase 4: Built-In Player Vertical Slice

Goal: play one episode from one video source in-app with progress persistence.

Detailed plan: `docs/VIDEO-PHASE-4-PLAYER.md`

Scope:

- add player dependency
- build player activity/screen
- resolve and play a stream
- persist progress
- resume playback
- mark complete when threshold reached

Why this phase exists:

- This is the first end-to-end proof that the architecture works.
- It should be completed before broader UI rollout.

Checklist:

- [x] Add playback library dependency
- [x] Build video player screen/activity
- [x] Resolve source headers/cookies/referer correctly
- [x] Implement quality selection policy
- [x] Persist and restore playback position
- [x] Mark episode completed at 90% watched
- [x] Add a minimal external-player action

Current note:

- Phase 4 is complete, including a debug-only launcher, built-in Media3 playback, progress/history persistence, and a minimal external-player action.

## Phase 5: Video Profile Shell and Core UI

Goal: make video profiles usable as a coherent product mode.

Scope:

- add/open browse surfaces for video sources
- add title details screen and episode list behavior for video
- replace reader intents with player intents in video profiles
- make continue watching entry points work
- keep `Updates` and `History` tab presentation deferred to Phase 6

Locked constraints:

- use a separate video browse/extensions screen instead of reusing manga `BrowseTab` directly

Detailed plan: `docs/VIDEO-PHASE-5-UI.md`

Why this phase exists:

- A player without surrounding UI is only a technical spike.
- The app needs a complete navigation flow for video profiles.

Checklist:

- [ ] Define video-profile home tabs
- [ ] Add browse flow for video sources
- [ ] Add title details flow for video titles
- [ ] Add episode open routing
- [ ] Add continue watching behavior
- [ ] Add watch progress indicators in episode lists
- [ ] Review search behavior inside video profiles

Current note:

- Phase 5 planning now lives in `docs/VIDEO-PHASE-5-UI.md`.
- This phase intentionally excludes `Updates` and `History` tab presentation, which remain part of Phase 6.

## Phase 6: History, Updates, and Settings for Video

Goal: make video profiles feel complete outside playback.

Scope:

- adapt history semantics to watching
- adapt update semantics to episodes
- wire the `Updates` and `History` tabs to video-specific data
- add video preferences
- review notifications and shortcuts

Locked constraints:

- video `Updates` has no filters in v1

Checklist:

- [ ] Define watched history presentation
- [ ] Define episode update presentation
- [ ] Add video-specific preferences
- [ ] Review notifications for video profiles
- [ ] Review quick actions and shortcuts
- [ ] Review profile-scoped settings defaults for video

## Phase 7: Hardening and Extension Rollout

Goal: stabilize the feature for wider source support.

Scope:

- multiple source validation
- compatibility testing
- edge-case playback handling
- documentation and rollout controls

Checklist:

- [ ] Test with multiple video source patterns
- [ ] Validate tokenized URL handling
- [ ] Validate cookie-based auth flows
- [ ] Validate stream fallback/error UI
- [ ] Document extension compatibility rules
- [ ] Decide whether feature flag can be removed

## Key Technical Decisions

These were finalized in Phase 0 and should guide Phase 1+ work.

### 1. Profile type storage shape

Locked decision:

- use an integer-backed enum-like SQL value with a strongly typed Kotlin enum wrapper

Recommendation:

- use a stable enum-like text or integer value in storage, but keep the Kotlin API strongly typed

### 2. Domain model reuse vs parallel domain models

Locked decision:

- keep app/domain/data models parallel from the start without adding a shared generic media abstraction in the first implementation

Recommendation:

- keep video app/domain/data models parallel from the start
- do not map video titles and episodes onto `Manga` and `Chapter` as the primary internal representation

### 3. Player implementation choice

Locked decision:

- use AndroidX Media3 as the built-in playback stack

Recommendation:

- integrate Media3 with the app's shared `OkHttpClient`, cookies, and request metadata handling

### 4. External-player support contract

Locked direction:

- external-player support should export the richest metadata possible when feasible

Recommendation:

- treat built-in playback as the only required supported path

### 5. Source browsing contract

Locked decision:

- keep browse/search familiar at the UX level, but use a video-specific contract end to end

Recommendation:

- keep browse/search familiar at the UX level, but use a video-specific contract end to end

### 6. Video updates semantics

Locked decision:

- `Updates` represents newly available or newly fetched episodes for library video titles, with video-specific logic and no filters in v1

Recommendation:

- treat `Updates` as newly available or newly fetched episodes in video profiles, with video-specific logic and no filters in v1

### 7. Extension packaging and typing

Locked direction:

- video extensions are separate packages
- extension repositories should expose a package-level `type` field

### 8. Runtime extension/source manager split

Locked direction:

- use a shared `ExtensionManager` for install/update/trust/repository flows
- use typed runtime extension models instead of one manga-shaped source-bearing model
- keep `SourceManager` manga-only
- add a parallel `VideoSourceManager`

### 9. Source visibility preference split

Locked direction:

- keep separate hidden-source preference keys for manga and video per profile

### 10. Video browse/extensions screen

Locked direction:

- video profiles should use a separate browse/extensions screen with separate video screen models, reusing shared UI components where helpful

## Phase 0 Outcome

Phase 0 is complete.

Phase 1 is complete.

It produced:

- locked product/profile separation rules
- locked extension typing and runtime manager split
- locked initial video source API surface
- locked Media3 playback direction
- locked playback progress/completion policy
- locked video library/history/updates semantics
- a compiled initial `source-api` contract for video
- typed `MANGA`/`VIDEO` profiles with immutable profile type
- a placeholder-backed video shell that does not fall back into manga tabs
- split manga/video source visibility preferences
- typed backup/restore handling plus startup/auth/settings-surface validation

Phase 2 is complete and delivered the runtime extension/source separation needed before any real video data or player work.

Phase 3 is complete and delivered the parallel video persistence layer: tables, repositories, category relations, playback state, history/updates queries, and backup/restore payloads, including profile-scoped backup isolation for video child data.

Phase 4 is complete and delivered the first end-to-end runtime slice: stored-episode resolution, Media3 playback, shared OkHttp/header wiring, progress/history persistence, completion sync, a debug-only launcher, and a minimal external-player handoff.

Current note: the next implementation phase is Phase 5.

The next implementation step is `Phase 5: Video Profile Shell and Core UI`.

## Risks

## High Risk

- Source API design churn causing repeated extension compatibility breaks
- Attempting to reuse `ReaderActivity` or `Page` too deeply and accumulating technical debt
- Playback failures caused by missing headers, cookies, referer, or tokenized stream handling
- Underestimating how much current history/update/library logic assumes page-based reading

## Medium Risk

- Video profile shell may initially feel too close to the manga shell if tabs and settings are not separated early
- External-player support may be inconsistent across devices/apps

## Low Risk

- Profile-type migration itself should be manageable because profiles are already a distinct table and existing rows can default to `manga`

## Recommended Implementation Order

The safest execution order is:

0. Architecture lock
1. Profile typing
2. Video source API design
3. Parallel app/domain/data foundation and playback progress persistence
4. Built-in player vertical slice
5. Video profile UI shell
6. History/updates/settings adaptation
7. Hardening and extension rollout

## Tracking

When implementation starts, add phase-specific files such as:

- `docs/VIDEO-PHASE-0-ARCHITECTURE.md`
- `docs/VIDEO-PHASE-1-PROFILES.md`
- `docs/VIDEO-PHASE-2-SOURCE-API.md`
- `docs/VIDEO-PHASE-3-DATA.md`
- `docs/VIDEO-PHASE-4-PLAYER.md`
- `docs/VIDEO-PHASE-5-UI.md`

Each phase file should contain:

- scope
- decisions
- tasks
- blockers
- test plan
- rollout notes

## First Implementation Milestone

The first milestone should be a narrow but real vertical slice:

- create a `video` profile
- install/load one video source type
- browse one video title
- open one episode
- play it in-app
- persist resume position
- mark it complete when finished

If this works cleanly, the rest of the plan can build outward without forcing video concepts into manga-only architecture.
