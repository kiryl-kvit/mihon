# Video Phase 3: Domain and Data Wiring

## Purpose

This document tracks Phase 3 of video support: the parallel app/domain/data model set that lets video titles, episodes, playback state, history, updates, and category membership be stored and queried under `VIDEO` profiles.

Phase 2 completed extension/runtime separation. Phase 3 is the first persistence-heavy phase. It should create a clean data foundation for later player and UI phases without forcing video concepts into manga storage.

## Phase Goal

Make video titles and episodes storable and queryable under video profiles.

At the end of this phase:

- video titles and episodes have their own tables, models, and repositories
- video category membership works using the existing profile-scoped `categories` table with a new video relation table
- watch/completion state lives on `video_episodes`
- playback resume/progress lives in a dedicated `video_playback_state` table
- video history and updates can be queried without touching manga tables
- backup direction for video data is defined without breaking existing manga/profile backup compatibility

## Locked Decisions

- reuse the existing `categories` table
- add a new `videos_categories` relation table instead of duplicating category definitions
- store watched/completed state on `video_episodes`
- store resume/progress state separately in `video_playback_state`
- keep video data fully parallel from manga tables and repositories
- continue scoping all video data by `profile_id`

## Out Of Scope

This phase does not implement:

- Media3 playback UI
- visible video browse/details screens
- real episode open-routing from the video shell
- downloader integration
- visible video updates/history UI presentation details beyond repository/query support

## Why This Phase Exists

The current app persists manga behavior through a cluster of profile-scoped manga tables and repositories.

Relevant current seams:

- `data/src/main/sqldelight/tachiyomi/data/mangas.sq`
  - title/library state is manga-shaped and source/url keyed
- `data/src/main/sqldelight/tachiyomi/data/chapters.sq`
  - episode/chapter state stores `read`, `bookmark`, and `last_page_read`
- `data/src/main/sqldelight/tachiyomi/data/mangas_categories.sq`
  - category membership is relation-table based and manga-specific
- `data/src/main/sqldelight/tachiyomi/data/history.sq`
  - history is chapter-based and stores `last_read` plus `time_read`
- `data/src/main/java/tachiyomi/data/updates/UpdatesRepositoryImpl.kt`
  - updates are derived from chapter-oriented queries and relations
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/models/BackupManga.kt`
  - backup is currently manga/title/chapter/history shaped

Without Phase 3, later player or UI work would either:

- store video state in manga tables with thin adapters
- overload `last_page_read` into a video progress surrogate
- or build temporary in-memory flows that must be replaced later

## Desired Behavior

### Video titles

- persist independently from manga titles
- remain profile-scoped
- keep source/url uniqueness within a profile
- support category membership through the existing categories table
- support favorite/library semantics for video profiles

### Video episodes

- persist independently from chapters
- belong to one video title
- store watched/completed state directly
- support fetch/update ordering fields
- support bookmark-like semantics only if later phases require it; not required to model in this phase unless needed for parity

### Playback state

- persist current resume position separately from episode completion state
- allow resume after process death or app relaunch
- record `positionMs`, `durationMs`, `completed`, and `lastWatchedAt`
- avoid overloading episode rows with transient playback session details

### History and updates

- history should derive from episode watch activity, not manga chapter history queries
- updates should derive from video episode fetch/update data, not manga updates views
- both must stay profile-scoped and video-only

## Workstreams

## 1. Video Schema Foundation

Add the minimum persistent table set for video titles and episodes.

### Planned changes

- add `videos` table
- add `video_episodes` table
- add indexes for profile/source/url uniqueness and title/episode lookups
- add migration file(s) for new tables

### Suggested tables

#### `videos`

Suggested fields:

- `_id`
- `profile_id`
- `source`
- `url`
- `title`
- `display_name`
- `description`
- `genre`
- `thumbnail_url`
- `favorite`
- `initialized`
- `date_added`
- `last_update`
- `next_update`
- `last_modified_at`
- `favorite_modified_at`
- `version`
- `notes`

#### `video_episodes`

Suggested fields:

- `_id`
- `profile_id`
- `video_id`
- `url`
- `name`
- `episode_number`
- `source_order`
- `date_fetch`
- `date_upload`
- `watched`
- `completed`
- `last_modified_at`
- `version`

### Files

- new SQLDelight `.sq` files under `data/src/main/sqldelight/tachiyomi/data/`
- new migration file(s)

### Decisions

- `videos` and `video_episodes` must not reuse `mangas` or `chapters`
- profile scoping mirrors manga tables
- watched/completed state belongs on `video_episodes`

### Checklist

- [x] Add `videos` table
- [x] Add `video_episodes` table
- [x] Add profile/source/url uniqueness for video titles
- [x] Add profile/video/url uniqueness for episodes
- [x] Add migration(s)

## 2. Category Relations For Video Titles

Reuse the existing `categories` table and add a video-specific membership table.

### Planned changes

- add `videos_categories`
- keep `categories` shared and profile-scoped
- add relation queries for video titles by category and category membership updates

### Files

- new `videos_categories.sq`
- category and video repository layers

### Decisions

- categories themselves are not duplicated for video
- only membership relations are parallelized
- phase 3 should support the same high-level category UX boundary later without changing category identity rules

### Checklist

- [x] Add `videos_categories` relation table
- [x] Add relation queries for video category membership
- [x] Add repository support for setting video categories

## 3. Video Playback State Persistence

Persist resume/progress state separately from watched/completed episode state.

### Planned changes

- add `video_playback_state`
- upsert by `video_episode_id`
- persist `position_ms`, `duration_ms`, `completed`, `last_watched_at`
- keep resume position separate from `video_episodes.completed`

### Suggested table

#### `video_playback_state`

Suggested fields:

- `_id`
- `profile_id`
- `episode_id`
- `position_ms`
- `duration_ms`
- `completed`
- `last_watched_at`

### Decisions

- `completed` may appear in both the playback-state table and episode row, but the episode row is the stable library/history/update-facing completion flag
- playback-state `completed` is allowed as a convenience field if it simplifies persistence; the repository should define one source of truth clearly
- do not store video progress in `video_episodes` only

### Checklist

- [x] Add `video_playback_state` table
- [x] Add upsert/query APIs for episode playback state
- [x] Define repository rule for syncing playback completion into `video_episodes.completed`

## 4. Domain And App Model Set

Add the parallel video models needed by repositories and later UI phases.

### Planned changes

- define video title model
- define video episode model
- define video update relation model
- define video history relation model
- define video playback state model
- add update/partial-update models as needed

### Suggested domain model set

- `VideoTitle`
- `VideoTitleUpdate`
- `VideoEpisode`
- `VideoEpisodeUpdate`
- `VideoHistoryEntry`
- `VideoHistoryWithRelations`
- `VideoUpdatesWithRelations`
- `VideoPlaybackState`

### Files

- new `domain/src/main/java/tachiyomi/domain/video/...`
- app-side presentation or support models only if required by repository mapping

### Decisions

- avoid a shared generic media abstraction in this phase
- mirror manga patterns only where it keeps query/repository behavior consistent

### Checklist

- [x] Define domain models for titles, episodes, history, updates, and playback state
- [x] Define update models for partial persistence operations
- [x] Add mapper layer from SQL rows to domain models

## 5. Repository Layer

Add parallel repositories for video storage and queries.

### Planned changes

- add title repository
- add episode repository
- add history repository
- add updates repository
- add playback-state repository

### Suggested repository set

- `VideoRepository`
- `VideoEpisodeRepository`
- `VideoHistoryRepository`
- `VideoUpdatesRepository`
- `VideoPlaybackStateRepository`

### Files

- new repository interfaces under `domain`
- new implementations under `data`
- wiring in `DomainModule`

### Decisions

- repositories should stay profile-aware through `ActiveProfileProvider`
- do not extend manga repositories with video conditionals

### Checklist

- [x] Add repository interfaces
- [x] Add repository implementations
- [x] Wire repositories in DI/domain module

## 6. History And Updates Query Semantics

Define the minimum data semantics that later Phase 5/6 UI can rely on.

### Planned changes

- define what makes an episode appear in history
- define what makes an episode appear in updates
- define query relation models and ordering

### Recommended initial rules

- history is driven by `video_playback_state.last_watched_at` or a dedicated `video_history` row updated from playback
- updates are driven by `video_episodes.date_fetch` or episode availability sync timestamps for favorite video titles
- use a dedicated `video_history` table if it keeps aggregation simpler and closer to current manga history semantics

### Decision to lock during implementation

- whether history should be fully derived from playback-state rows or backed by a dedicated `video_history` table

Recommendation:

- use a dedicated `video_history` table in Phase 3 for parity with current query patterns and easier later UI filtering

### Checklist

- [x] Lock video history storage strategy
- [x] Add video history query behavior
- [x] Add video updates query behavior

## 7. Backup Direction

Define the backup approach for video data without implementing a full rollout if that becomes too large for this phase.

### Planned changes

- define whether video data extends the current backup root or uses parallel payload sections
- preserve backward compatibility for existing backup files
- avoid unsafe protobuf field-number churn

### Decisions

- keep existing manga/profile backup payloads readable
- any new video backup payloads must use new proto fields only
- if full implementation is too large for Phase 3, at minimum lock and document the protobuf direction

### Checklist

- [x] Define video backup payload direction
- [x] Review compatibility strategy for old backups
- [x] Implement or explicitly defer full video backup serialization

## Implementation Batches

1. Schema and model foundation
2. Title/episode repositories
3. Category relations and playback state
4. History and updates queries
5. Backup direction and tests

## Test Plan

### Schema

- migration creates all new video tables cleanly
- uniqueness constraints hold per profile

### Repository CRUD

- insert/get/update video titles
- insert/get/update video episodes
- category assignment updates relation rows correctly

### Playback state

- playback-state upsert replaces prior position for the same episode
- playback completion sync behavior is deterministic

### History and updates

- watched episodes appear in video history only
- fetched/new episodes for favorited video titles appear in video updates only

### Backup

- existing manga/profile backups still decode
- new video payload fields, if added in this phase, decode with backward compatibility
- profile-scoped video backups read child data from the requested profile rather than implicitly from the active profile

## Exit Criteria

Phase 3 is complete when all of the following are true.

- video titles and episodes persist in parallel tables
- video category membership works through `videos_categories`
- playback progress persists through `video_playback_state`
- video history and updates repositories exist and return video-only data
- the data layer is ready for a real player vertical slice in Phase 4

## Current Verification

- `./gradlew :app:compileDebugKotlin` passes
- `./gradlew :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.data.backup.create.creators.VideoBackupCreatorTest" --tests "eu.kanade.tachiyomi.data.backup.models.VideoBackupProtoCompatibilityTest" --tests "mihon.feature.profiles.core.ProfileScopedBackupProtoTest" --tests "tachiyomi.domain.video.model.VideoModelsTest" --tests "tachiyomi.data.video.VideoPlaybackStateMapperTest" --tests "eu.kanade.domain.extension.interactor.GetVideoExtensionsTest" --tests "mihon.feature.profiles.core.ProfileVideoSourcePreferenceProviderTest"` passes
- Phase 3 Batch 1 added SQLDelight tables for `videos`, `video_episodes`, `videos_categories`, and `video_playback_state`
- first repository set now exists for video titles, episodes, and playback state
- video category membership queries now flow through the shared `CategoryRepository`
- playback-state upserts can now sync watched/completed state into `video_episodes`
- a first `VideoHistoryRepository` now exists on top of `video_history`
- a first `VideoUpdatesRepository` now exists on top of favorited video titles plus episode fetch timestamps, with no v1 filters beyond watched status
- backup now includes parallel video payloads in both root and profile-scoped backup structures, with restore wiring for titles, episodes, categories, history, and playback state
- profile-scoped video backup creation now reads episodes, history, and playback state by the requested `profile_id` instead of assuming the active profile
- legacy backup bytes still decode correctly when new video backup fields are absent

## Handoff To Phase 4

Phase 4 planning now lives in `docs/VIDEO-PHASE-4-PLAYER.md`.

It covers concrete work items for:

- Media3 integration with the resolved video request/stream model
- player screen/activity state handling
- playback progress save/resume integration with `VideoPlaybackStateRepository`
- completion threshold updates into `video_episodes.completed`
