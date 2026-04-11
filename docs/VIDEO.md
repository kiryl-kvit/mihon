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
- External player support is optional and secondary.
- The content model is one video per chapter/episode.
- Video content must not mix with manga content in the same UI surfaces.
- Video content will live in a new profile type dedicated to video.
- Existing manga profiles must continue to behave exactly as they do now.
- Video sources will use a new source type dedicated to video.

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
- Use a built-in playback library suitable for HLS/MP4/other common stream types.
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

## Source Layer

Introduce a dedicated video source contract rather than stretching the manga one.

Suggested shape:

- `VideoCatalogueSource` or equivalent browse/search contract for video titles
- `VideoSource` or equivalent playback contract for episodes and streams
- `VideoEpisode` or reuse of `SChapter` for episode rows, depending on how much duplication is acceptable
- `VideoStream` model for a playable stream
- optional `SubtitleTrack` model
- optional `ExternalPlaybackTarget` or external-playable metadata

Open design question:

- Whether video browse models should reuse `SManga` and `SChapter` for library compatibility, or define parallel source models and map them into existing domain models.

Current recommendation:

- Reuse existing domain `Manga` and `Chapter` rows in phase 1 to reduce database churn in library/history/update flows.
- Add content/profile/source typing around them rather than replacing them immediately.
- Keep the video source API separate at the extension boundary.

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

## Data Layer

Keep data separate primarily through profile type, not separate databases.

Planned direction:

- Continue using profile-scoped data where it already exists.
- Add profile type so feature-specific queries and UI behavior can branch early.
- Add dedicated playback-progress persistence for video.
- Do not reuse `last_page_read` as the only source of truth for video position.

Suggested playback state model:

- `chapterId`
- `positionMs`
- `durationMs`
- `completed`
- `lastWatchedAt`

The existing chapter `read` flag can still represent watched/completed status, but granular playback position should be stored separately.

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

## Non-Goals For Initial Rollout

- offline downloads
- local video source
- DRM-protected playback
- casting support
- picture-in-picture unless it comes nearly for free from the chosen player stack
- background audio mode unless it is needed early
- subtitles UI beyond basic support if stream extraction already provides them
- mixing manga and video in the same profile
- retrofitting current manga sources to also support video

## Phase Plan

## Phase 0: Planning and Architecture Lock

Goal: freeze the boundaries before implementation starts.

Deliverables:

- finalized profile type model
- finalized video source contract direction
- finalized playback stack choice
- agreed phase breakdown
- agreed migration and compatibility approach for extensions

Checklist:

- [ ] Add profile type to the data model design
- [ ] Decide whether browse models reuse `SManga` and `SChapter`
- [ ] Define the minimum `VideoStream` model
- [ ] Choose built-in playback library
- [ ] Define the external-player handoff strategy
- [ ] Define playback progress persistence strategy

## Phase 1: Profile Type Foundation

Goal: make the app profile-aware by product type.

Scope:

- add profile type to the schema and models
- keep existing profiles as `manga`
- update profile creation/edit/picker flows
- make startup and navigation react to profile type
- define separate default tabs/settings seed behavior for video profiles

Why this phase exists:

- It creates the top-level separation required to keep manga behavior untouched.
- It reduces later conditional logic by making profile type explicit from the start.

Checklist:

- [ ] Add `type` to profile storage
- [ ] Migrate existing profiles to `manga`
- [ ] Update profile creation UI to choose a type
- [ ] Update profile bundle/view models to expose the type
- [ ] Make home-tab defaults depend on profile type
- [ ] Define source visibility defaults for video profiles
- [ ] Review backup/restore behavior for typed profiles

## Phase 2: Video Source API Foundation

Goal: establish a dedicated extension/source contract for video.

Scope:

- define new video source interfaces and models
- decide which browse/listing contracts are shared with existing source types
- update source manager plumbing to recognize video sources
- update extension compatibility/versioning strategy

Why this phase exists:

- The source API is the foundation for everything else.
- Playback and UI should not be built before stream resolution is modeled properly.

Checklist:

- [ ] Define video source interfaces
- [ ] Define stream and subtitle models
- [ ] Decide relationship to `CatalogueSource`
- [ ] Update source manager to expose video-capable sources
- [ ] Update extension loading compatibility policy
- [ ] Write extension author guidance for video sources

## Phase 3: Video Domain and Data Wiring

Goal: make video titles and episodes storable and queryable under video profiles.

Scope:

- route video-source browse results into the app domain
- keep video profile data separated cleanly
- add playback state persistence
- ensure history/update/library queries have a video-safe path

Why this phase exists:

- Even with separate profiles, current repositories and views were designed around manga reading assumptions.
- This phase identifies what can be reused and what needs video-specific handling.

Checklist:

- [ ] Decide how video titles map into domain models
- [ ] Add playback-progress persistence
- [ ] Add watched/completed update logic
- [ ] Review history query assumptions
- [ ] Review updates query assumptions
- [ ] Review library counters and badges for video profiles
- [ ] Review backup serialization for video data

## Phase 4: Built-In Player Vertical Slice

Goal: play one episode from one video source in-app with progress persistence.

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

- [ ] Add playback library dependency
- [ ] Build video player screen/activity
- [ ] Resolve source headers/cookies/referer correctly
- [ ] Implement quality selection policy
- [ ] Persist and restore playback position
- [ ] Mark episode completed using threshold
- [ ] Add a minimal external-player action

## Phase 5: Video Profile Shell and Core UI

Goal: make video profiles usable as a coherent product mode.

Scope:

- add/open browse surfaces for video sources
- add title details screen and episode list behavior for video
- replace reader intents with player intents in video profiles
- make continue watching entry points work

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

## Phase 6: History, Updates, and Settings for Video

Goal: make video profiles feel complete outside playback.

Scope:

- adapt history semantics to watching
- adapt update semantics to episodes
- add video preferences
- review notifications and shortcuts

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

## Key Technical Decisions Still Open

These should be resolved before Phase 2 starts.

### 1. Profile type storage shape

Decision needed:

- enum-like string column
- integer enum column

Recommendation:

- use a stable enum-like text or integer value in storage, but keep the Kotlin API strongly typed

### 2. Domain model reuse vs parallel domain models

Decision needed:

- reuse current `Manga` and `Chapter`
- create parallel `VideoTitle` and `VideoEpisode` domain models

Recommendation:

- reuse current domain rows in phase 1 where practical, because profile type already provides top-level separation
- add parallel playback/source contracts rather than duplicating all library/storage code immediately

### 3. Player implementation choice

Decision needed:

- choose the built-in playback stack

Recommendation:

- choose a player with strong Android support for HLS/MP4, custom headers, subtitles, and lifecycle handling

### 4. External-player support contract

Decision needed:

- whether external-player handoff receives only a URL or also exported headers/metadata

Recommendation:

- treat external-player support as best-effort and do not block built-in support on perfect external interoperability

### 5. Source browsing contract

Decision needed:

- whether video sources also implement an existing browse contract or a video-specific one

Recommendation:

- keep browse/search familiar, but avoid inheriting image-reader assumptions into the playback path

## Risks

## High Risk

- Source API design churn causing repeated extension compatibility breaks
- Attempting to reuse `ReaderActivity` or `Page` too deeply and accumulating technical debt
- Playback failures caused by missing headers, cookies, referer, or tokenized stream handling
- Underestimating how much current history/update/library logic assumes page-based reading

## Medium Risk

- Reusing manga domain tables may hide video-specific needs that appear later
- Video profile shell may initially feel too close to the manga shell if tabs and settings are not separated early
- External-player support may be inconsistent across devices/apps

## Low Risk

- Profile-type migration itself should be manageable because profiles are already a distinct table and existing rows can default to `manga`

## Recommended Implementation Order

The safest execution order is:

1. Profile typing
2. Video source API design
3. Playback progress persistence
4. Built-in player vertical slice
5. Video profile UI shell
6. History/updates/settings adaptation
7. Hardening and extension rollout

## Tracking

When implementation starts, add phase-specific files such as:

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
