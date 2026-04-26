# Tickdroid — Architecture & Implementation Plan

Companion Android app to the Tickbuddy Nextcloud webapp. See `README.md` for product context and `mobile_instructions.md` for the authoritative API / sync handoff doc (the code wins if it disagrees).

This file captures the plan agreed before coding started. Update it as decisions change.

## Scope decisions (v1)

- **Auth**: pasted Nextcloud app password. Login Flow v2 stubbed but not implemented — leave a seam in `AuthRepository`.
- **History depth**: full database, infinite scroll backwards in 30-day windows from day 1.
- **Private tracks**: simple show/hide toggle (matches web). No PIN/biometric gate.
- **Counter UX**: short press increments, long press decrements.
- **Out of scope for v1** (deferred): track management (add/edit/delete/reorder), Login Flow v2, import/export, widget, daily reminder.

## Tech stack

Skeleton already on Compose + Material3, `minSdk 31` (Material You / dynamic color usable directly).

| Concern | Choice |
|---|---|
| DI | Hilt |
| Async | Kotlin Coroutines + Flow |
| Local DB | Room |
| HTTP | Retrofit + OkHttp + kotlinx.serialization |
| Background sync | WorkManager |
| Secure storage | EncryptedSharedPreferences (MasterKey/Keystore) |
| Navigation | Navigation-Compose (type-safe) |
| Date/time | java.time (`LocalDate`) |
| Testing | JUnit + Turbine + MockWebServer |

## Package layout (single module for now)

```
com.martinhammer.tickdroid
├── data
│   ├── local        // Room entities, DAOs, database
│   ├── remote       // Retrofit API, OCS envelope, DTOs, auth interceptor
│   ├── repository   // TrackRepository, TickRepository (single source = Room)
│   └── sync         // SyncManager, WorkManager workers, conflict policy
├── domain           // pure-Kotlin models (Track, Tick, TrackType)
├── ui
│   ├── theme        // Material You / dynamic color
│   ├── journal      // grid screen (today + history)
│   ├── auth         // server URL + login + app password setup
│   └── common       // reusable composables (TickCell, CounterCell, etc.)
└── di               // Hilt modules
```

Repository layer is the single source of truth: UI observes Room via Flow; sync layer is the only thing that touches the network.

## Data layer

**Room schema** mirrors `mobile_instructions.md` §4:

- `tracks(local_id PK, server_id?, name, type, sort_order, private, dirty, deleted, updated_at_local)`
- `ticks(local_id PK, server_id?, track_local_id FK, date TEXT, value INT, dirty, deleted, updated_at_local)` — unique index on `(track_local_id, date)`.

**Network**:

- `OcsEnvelope<T>` unwrapped by a converter or suspend extension.
- `OcsHeadersInterceptor` adds `OCS-APIRequest: true` and `Accept: application/json`.
- `BasicAuthInterceptor` reads creds from encrypted prefs; emits `Flow<AuthState>` so 401 flips the app to re-auth.
- `TickbuddyApi`: `getTracks`, `getTicks(from,to)`, `toggleTick`, `setTick`. Track CRUD wired but unused in v1.

**Repository**:

- `observeTracks(): Flow<List<Track>>`
- `observeTicksInRange(from, to): Flow<Map<Pair<TrackId, LocalDate>, Tick>>`
- `toggleBoolean(trackId, date)` and `setCounter(trackId, date, value)` — write to Room (`dirty=1`) and enqueue sync.

## Sync layer

Implements `mobile_instructions.md` §4:

- **Pull**: `GET /api/tracks` + `GET /api/ticks?from&to` for the visible window. Reconcile by `server_id`, name fallback for un-synced rows. Server-authoritative when local isn't dirty; otherwise push wins.
- **Push order**: track creates → updates → deletes → tick changes.
- **Boolean replay safety**: queue **desired end state**; before push, fetch current server state and only call `/toggle` if it differs.
- **Counter**: idempotent `set`, always safe to replay.
- **Triggers**: `OneTimeWorkRequest` after every local write (`NetworkType.CONNECTED`, exponential backoff), `PeriodicWorkRequest` every 15 min while authenticated, pull-to-refresh in journal.
- **401**: stop worker, clear in-memory auth, route UI to re-auth.

## UI / UX (Material You)

### Auth / onboarding
- Server URL field (validate `https://`, strip trailing slash, allow `http://` only with explicit toggle).
- Login + app password fields with help text on creating an app password in Nextcloud.
- "Test connection" → `GET /api/tracks` validation round-trip.
- Login Flow v2 placeholder seam in `AuthRepository`.

### Journal (main screen)
Modern reinterpretation of Tickmate's grid:

- **Top app bar**: title, sync status indicator (idle/syncing/offline/error), overflow (Settings, Show private, Sign out).
- **Sticky header row**: track icons/names as columns. Horizontal scroll if many tracks; today's column emphasized.
- **Body**: vertical `LazyColumn` of day rows, newest at top, infinite scroll backwards in 30-day chunks. Each row has a day label cell (DOW + date; "today"/"yesterday"; weekend tinted) and one cell per track — filled tonal button (boolean) or counter pill (short press +1, long press -1). Optimistic UI, ripple, haptics.
- **Dynamic color**: ticked = `primary`/`primaryContainer`, untouched = `surfaceContainer`, counters use `tertiaryContainer`.
- **Pull-to-refresh** triggers explicit pull.
- No FAB in v1 (track management is v2).

### Settings
Account info, sign out, "Show private tracks" toggle, theme (system/light/dark), about.

### Material You specifics
- `dynamicLightColorScheme` / `dynamicDarkColorScheme` with fallback palette.
- Edge-to-edge.
- Tabular figures for counter cells.
- Predictive back, large fonts, TalkBack descriptions on tick cells ("Meditate, Saturday April 25, ticked").

## Phased implementation

**Phase 0 — Foundations**
1. Add Hilt, Room, Retrofit/OkHttp/kotlinx.serialization, WorkManager, Navigation, security-crypto to `libs.versions.toml`.
2. `Application` class with Hilt; edge-to-edge in `MainActivity`.
3. Confirm dynamic-color setup in `Theme.kt`.

**Phase 1 — Auth**
4. `EncryptedCredentialStore` + `AuthRepository`.
5. Retrofit + `OcsHeadersInterceptor` + `BasicAuthInterceptor` + OCS envelope unwrapping.
6. `TickbuddyApi.getTracks()` validation round-trip.
7. Auth screen + nav: boot into auth if no creds, otherwise into Journal.

**Phase 2 — Read-only journal**
8. Room entities + DAOs.
9. Repositories exposing Flows from Room.
10. `SyncManager.pull()` for tracks + visible date window; called on app open + pull-to-refresh.
11. Journal screen rendering the grid from Room (read-only). Verify against a real Tickbuddy instance.

**Phase 3 — Writes + offline**
12. Optimistic local writes (`toggle`, `set`) marking rows dirty.
13. `PushWorker` with desired-end-state replay safety for booleans.
14. Periodic + on-write WorkManager triggers; `401` re-auth handling.
15. Conflict reconciliation on pull.

**Phase 4 — Polish**
16. Infinite scroll older history (30-day windows) + range-aware pulls.
17. Sync status indicator + error snackbars.
18. Settings screen.
19. Accessibility + landscape/tablet pass.

**Phase 5 — Testing**
20. Unit tests for repositories, sync conflict matrix, OCS envelope, auth interceptor.
21. MockWebServer integration tests against captured OCS fixtures.
22. Compose UI tests for Journal interactions.
