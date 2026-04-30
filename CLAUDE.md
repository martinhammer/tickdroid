# Tickdroid — Architecture & Implementation Plan

Companion Android app to the Tickbuddy Nextcloud webapp. See `README.md` for product context and `mobile_instructions.md` for the authoritative API / sync handoff doc (the code wins if it disagrees).

This file captures the current state of the codebase and decisions made along the way. Update it as decisions change.

## Scope decisions (v1)

- **Auth**: pasted Nextcloud app password. Login Flow v2 stubbed but not implemented — seam exists in `AuthRepository.beginLoginFlowV2()`.
- **HTTPS only**: the early "Allow http://" toggle was removed. `ServerUrl.normalize` rejects anything but `https://`.
- **History depth**: full database, infinite scroll backwards in 30-day windows from day 1.
- **Private tracks**: simple show/hide toggle (App settings → Show private tracks). No PIN/biometric gate.
- **Counter UX**: short tap +1, long tap −1 (no-op when value is already 0).
- **Counter conflicts**: silent last-write-wins. The backend has no `/inc` endpoint, so two devices both incrementing on the same day will lose one increment. Acceptable for v1; not surfaced in UI.
- **Sign-out**: wipes the local Room database (`clearAllTables()`) **and** resets `UiPreferences` (`clear()`) so the next user on the same device doesn't inherit cached data, queued writes, or UI settings (theme, density, editable-days, show-private).
- **Editability**: configurable in App settings. Choices: today only / today + previous day / last 7 days / all past days. Future days never editable. Tapping a locked cell shows a Toast.
- **Out of scope for v1** (deferred): track management (add/edit/delete/reorder), Login Flow v2, import/export, widget, daily reminder, real schema migrations.

## Tech stack

| Concern | Choice |
|---|---|
| DI | Hilt + `androidx.hilt:hilt-work` for `@HiltWorker` |
| Async | Kotlin Coroutines + Flow |
| Local DB | Room (currently schema v2; `exportSchema = true` writes JSON to `app/schemas/`. No destructive fallback — every version bump must ship with a real `Migration`) |
| HTTP | Retrofit + OkHttp + kotlinx.serialization |
| Background sync | WorkManager (manifest disables auto-init; Hilt provides config) |
| Secure storage | EncryptedSharedPreferences (`security-crypto` alpha) for credentials |
| App prefs | Plain SharedPreferences via `UiPreferences` |
| Navigation | Navigation-Compose |
| Date/time | `java.time` (`LocalDate`); `android.icu.util.Calendar` for locale-aware weekend; `android.text.format.DateFormat` for the user-set date format |
| Testing | JUnit + Turbine + MockWebServer (no tests written yet — Phase 5) |

`compileSdk = 36`, `minSdk = 31`, `targetSdk = 36`.

## Package layout (single module)

```
com.martinhammer.tickdroid
├── data
│   ├── auth         // AuthRepository, EncryptedCredentialStore, AuthProber, AuthState
│   ├── local        // Room entities, DAOs, database (TickdroidDatabase)
│   ├── network      // NetworkMonitor (ConnectivityManager → Flow<Boolean> isOnline)
│   ├── prefs        // UiPreferences + enums (GridDensity, ThemeMode, EditableDays)
│   ├── remote       // TickbuddyApi, OCS envelope, DTOs, OcsHeaders/BasicAuth interceptors, ServerUrl
│   ├── repository   // TrackRepository, TickRepository, TrackPrefsRepository, mapping
│   └── sync         // SyncManager (pull), PushWorker, SyncScheduler, SyncCoordinator
├── domain           // Track, Tick, TrackType, TrackPrefs, TrackColor
├── ui
│   ├── about        // AboutScreen
│   ├── auth         // AuthScreen, AuthViewModel (with help bottom sheet)
│   ├── common       // EmojiRender (desaturatedEmoji modifier), Dimens (MaxContentWidth, CompactHeightThresholdDp)
│   ├── journal      // JournalScreen, JournalViewModel (with help bottom sheet)
│   ├── settings     // AccountSettingsScreen, AppSettingsScreen, TracksSettingsScreen, TrackDetailScreen, SettingsViewModel, TracksSettingsViewModel, TrackDetailViewModel
│   ├── theme        // Material You / dynamic color
│   ├── RootViewModel, TickdroidApp (NavHost), Routes
└── di               // NetworkModule, DatabaseModule
```

Repository layer is the single source of truth: UI observes Room via Flow; sync layer is the only thing that touches the network.

## Data layer

**Room schema (v2)**:

- `tracks(localId PK, serverId?, name, type, sortOrder, private, dirty, deleted, updatedAtLocal)` — `dirty/deleted/updatedAtLocal` are inert in v1 (no track CRUD on mobile).
- `ticks(localId PK, serverId?, trackLocalId FK, date TEXT, value INT, dirty, deleted, updatedAtLocal)` — unique index on `(trackLocalId, date)`.
- `track_prefs(serverId PK, colorKey?, emoji?)` — local-only UI overrides keyed by `serverId`. Never synced.

**Network**:

- `OcsEnvelope<T>` with a Retrofit-friendly shape that exposes `.ocs.data`.
- `OcsHeadersInterceptor` adds `OCS-APIRequest: true` and `Accept: application/json`.
- `BasicAuthInterceptor` rewrites the host with the user's stored Nextcloud origin and adds the Basic auth header. On `401` it calls `AuthRepository.signOut()`.
- `TickbuddyApi`: `getTracks`, `getTicks(from,to)`, `toggleTick(trackId, date)`, `setTick(trackId, date, value)`. Track CRUD is intentionally not wired in v1.

**Repository**:

- `TrackRepository.observeTracks(): Flow<List<Track>>`
- `TickRepository.observeRange(from, to): Flow<Map<TickKey, Tick>>`
- `TickRepository.toggleBoolean(trackLocalId, date)` and `adjustCounter(trackLocalId, date, delta)` — wrap reads + writes in `db.withTransaction`, mark `dirty=1`, and enqueue a one-shot push. Pending-removal rows persist with `deleted=true` so the worker still sees them.
- `TrackPrefsRepository.observeAll(): Flow<Map<Long, TrackPrefs>>`, plus `setColorKey`/`setEmoji`/`reset`. Auto-deletes the row when both fields end up null.

## Sync layer

Implements `mobile_instructions.md` §4 with a few concrete tweaks captured below.

- **Pull** (`SyncManager.pull(from, to)`): `GET /api/tracks` + `GET /api/ticks?from&to`, then reconcile inside `db.withTransaction`. Server-authoritative when local rows aren't `dirty`. Triggered on `JournalViewModel.refresh()` (which fires on `init` and `Lifecycle.Event.ON_RESUME`) and on pull-to-refresh.
- **Push** (`PushWorker` + `SyncScheduler`):
  - **Counter**: `POST /api/ticks/set value=X` (X=0 deletes server-side). Idempotent.
  - **Boolean**: fetches one-day server state for `(trackId, date)` and only `POST /toggle` if it differs from the desired local state. This is the spec's replay-safety pattern.
  - On `401`: signs out and `Result.failure()` (no retry). On `5xx`/IO: `Result.retry()` with exponential backoff. Worker constraint: `NetworkType.CONNECTED`.
- **Push/pull mutex**: `SyncManager.mutex` is held by `pull()` (around network + reconcile) and by `PushWorker.drain()` via `SyncManager.runExclusive`. Prevents the snapshot-then-stomp race where a concurrent pull would drop a row that a push had just cleared.
- **Triggers**:
  - One-shot `OneTimeWorkRequest` after every local write — `ExistingWorkPolicy.APPEND_OR_REPLACE` so a burst of taps coalesces a follow-up instead of cancelling the running drain.
  - Periodic `PeriodicWorkRequest` every 15 min while signed in.
  - `PushWorker.doWork()` is push-then-pull: drain under the mutex, and on success call `SyncManager.pull(today − 30d, today)` so periodic background work also surfaces changes from other devices.
- **Lifecycle**: `SyncCoordinator` (started from `TickdroidApplication.onCreate`) collects `AuthRepository.state`. On sign-in: schedule periodic + kick a one-shot. On sign-out: cancel both + wipe Room + reset `UiPreferences`.
- **Status surfacing**: `SyncManager` exposes `status: StateFlow<SyncStatus>` (pull) and `pushStatus: StateFlow<PushStatus>`, both of which carry a `SyncErrorKind` (`ServerUnreachable` / `ServerError`) when in `Error`. `JournalViewModel` combines those with `NetworkMonitor.isOnline` and `TickRepository.observeHasDirty()` into a `SyncIssue` (`Offline` / `ServerUnreachable` / `ServerError` / `None`, each tagged with `hasUnsavedChanges`). The top bar shows a `CircularProgressIndicator` during pull and a tonal `AssistChip` (`errorContainer` colors, `CloudOff` icon) whose label varies: "Offline" / "Server unreachable" / "Sync error", optionally suffixed with ", unsaved changes".

## UI / UX (Material You)

### Theme
- `dynamicLightColorScheme` / `dynamicDarkColorScheme` with fallback palette.
- `TickdroidTheme` is wired inside `TickdroidApp` and reads `RootViewModel.themeMode`. Choices: System / Light / Dark.
- Edge-to-edge in `MainActivity`.

### Auth / onboarding (`AuthScreen`)
- Top bar: "Connect with Tickbuddy" + a `HelpOutline` action that opens a `ModalBottomSheet` with a one-paragraph explainer and labeled blurbs for each field.
- Fields: "Nextcloud server URL" (validates `https://`, strips trailing slash), "User", "App password" (with eye toggle).
- Connect button runs `AuthProber.probe`, which round-trips through server-up → 401-style auth check → tickbuddy app installed. The error message reflects which stage failed.

### Journal (main screen)
Modern reinterpretation of Tickmate's grid.

- **Top bar**: collapsing `LargeTopAppBar` with `exitUntilCollapsedScrollBehavior`; swaps to a small `TopAppBar` when `screenHeightDp < CompactHeightThresholdDp` (landscape phones). Actions: `SyncIssueChip` (when relevant) → `SyncIndicator` (spinner during pull) → `?` help icon (opens a placeholder `ModalBottomSheet`) → overflow menu (Account / App settings / Tracks settings / About).
- **Sticky header row**: track headers as columns, horizontally scrollable. Track label is the per-track emoji (rendered desaturated, `titleLarge`) when set, otherwise the 2-letter abbreviation.
- **Body**: vertical `LazyColumn` of day rows, newest at top. Day-label width 92dp, single-line. Subtitle uses `android.text.format.DateFormat.getDateFormat(context)` so it follows the user's Settings → System → Date format. Weekend rows tinted with `surfaceContainerLow`; weekend detection uses `android.icu.util.Calendar.isWeekend` (locale-aware).
- **Cells**:
  - Cell width derived from `GridDensity` (Low=5 / Medium=7 / High=9 visible) with a half-cell peek when there are more tracks than fit, plus a 16dp right inset. Cell size clamped to [28, 64] dp.
  - Cell tint: custom `TrackColor.container` if the user assigned one; otherwise type-based (`primaryContainer` for boolean, `tertiaryContainer` for counter); empty cells use `surfaceContainerHighest`.
  - On-color: from `TrackColor.onContainer` (luminance-aware) or M3 `onXContainer`.
  - Filled boolean cells render `Icons.Filled.Check`. Filled counter cells render the value with `tnum`.
  - Editable empty cells render a faint affordance: `Icons.Filled.Add` (counter) or a bold interpunct `·` (boolean), both at ~50% cell size and 35% alpha.
- **Tap UX**: `combinedClickable` only on editable days (per `EditableDays` policy). Boolean → tap toggles; counter → tap +1, long-press −1 (no-op at 0). Light haptic on each. Tapping a locked cell pops a Toast: *"This day is locked. Select editable days in App settings."*.
- **Pull-to-refresh** triggers `JournalViewModel.refresh()` (which also recomputes `today` for midnight rollover). Empty-state screens (loading / no tracks / all private) are `Modifier.verticalScroll`'d so PTR still works.
- **Infinite scroll**: a derived `nearBottom` flag in `JournalGrid` calls `loadOlder()` which extends `_oldestVisible` by 30 days and pulls just the new chunk.

### Settings
Four top-level entries from the journal overflow menu:

- **Account** (`AccountSettingsScreen`): server URL, username (read-only), Log out (tonal error button).
- **App settings** (`AppSettingsScreen`):
  - Show private tracks (switch).
  - Editable days (segmented: Today / +1 day / 1 week / All).
  - Grid density (segmented: Low / Medium / High).
  - Theme (segmented: System / Light / Dark).
- **Tracks settings** (`TracksSettingsScreen`): list of all tracks (visible + private) with a 40dp circle badge (custom color or type color; emoji desaturated or abbreviation) and the track name. Tapping opens `TrackDetailScreen`:
  - 56dp preview badge + description ("Counter" or "Yes/No" · "Private").
  - Color picker: horizontal `LazyRow` of M3-styled swatches plus a "Default" chip.
  - Icon: `OutlinedTextField` capped to one grapheme cluster (`BreakIterator`). Empty clears the override.
  - "Reset to defaults" outlined button.
  - Edits write through immediately. Disabled if the track has no `serverId`.
- **About** (`AboutScreen`, package `ui.about`): app name, version (resolved at runtime via `PackageManager.getPackageInfo`), one-line description, copyright, GPL-3.0 link to `LICENCE` on GitHub, and a "View source on GitHub" outlined button (`Intent.ACTION_VIEW`).

### Accessibility
- `minSdk 31` → predictive back works out of the box.
- TalkBack content descriptions on tick cells **not yet implemented** — see "Future considerations". Documented as a limitation for the initial release.
- Tap targets shrink to ~28dp at high density, below the 48dp Material guideline — see "Future considerations".

## App icon
- Adaptive icon only (no legacy density mipmaps). Foreground vector renders the Nextcloud logo (white circle + checkmark) inside the 72dp safe zone of the 108dp canvas; background is solid `#0082C9`. Foreground also serves as the Android 13+ themed-icon monochrome layer.

## Phased implementation

**Phase 0 — Foundations** ✅
1. Hilt, Room, Retrofit/OkHttp/kotlinx.serialization, WorkManager, Navigation, security-crypto in `libs.versions.toml`.
2. `Application` class with Hilt + edge-to-edge in `MainActivity`.
3. Dynamic color set up in `Theme.kt`.

**Phase 1 — Auth** ✅
4. `EncryptedCredentialStore` + `AuthRepository` + `AuthState`.
5. Retrofit + `OcsHeadersInterceptor` + `BasicAuthInterceptor` + OCS envelope unwrapping.
6. `AuthProber` round-trips for server-up + auth + Tickbuddy-installed.
7. Auth screen + nav: boots into auth if no creds, otherwise into Journal.

**Phase 2 — Read-only journal** ✅
8. Room entities + DAOs.
9. Repositories exposing Flows.
10. `SyncManager.pull()`.
11. Journal screen rendering the grid (read-only).

**Phase 3 — Writes + offline** ✅
12. `TickRepository.toggleBoolean` / `adjustCounter` (transactional, dirty-bit, push-on-write).
13. `PushWorker` with desired-end-state replay safety for booleans, idempotent set for counters; pull/push share `SyncManager.mutex`.
14. Periodic + on-write WorkManager triggers; `401` re-auth; sign-out wipes Room.
15. Conflict reconciliation on pull (server overwrites unless local is dirty).

**Phase 4 — Polish** (in progress)
16. ✅ Infinite scroll older history (30-day windows) + range-aware pulls.
17. ✅ Sync status indicator + error chip in top bar.
18. ✅ Settings screens (Account / App / Tracks + per-track editor).
19. ✅ Per-track customization (color from a 10-swatch palette, emoji rendered desaturated).
20. ✅ Editable-days policy + locked-cell Toast.
21. ✅ Theme picker (system/light/dark).
22. ✅ App icon (Nextcloud logo).
23. ✅ Landscape pass (compact-height top bar swap, grid width cap, horizontal display-cutout / nav-bar insets, IME handling, per-control max-width caps).

**Phase 5 — Testing** ☐
24. Unit tests for repositories, sync conflict matrix, OCS envelope, auth interceptor.
25. MockWebServer integration tests against captured OCS fixtures.
26. Compose UI tests for Journal interactions.

## Tech debt / known limitations

Tracked from a code audit at the end of Phase 3. Items marked ✅ have been addressed; the rest are open.

1. ✅ Race-free local writes: `TickRepository` wraps read-modify-write in `db.withTransaction`.
2. ✅ Work scheduling: `OneTimeWorkRequest` uses `APPEND_OR_REPLACE` so a tap stream doesn't cancel the running drain.
3. ✅ Pull/push serialization via `SyncManager.mutex`.
4. ✅ **Periodic work also pulls.** `PushWorker.doWork()` runs the drain under the sync mutex, then (only on success) calls `SyncManager.pull(today − 30d, today)`. So the same 15-minute periodic worker now both pushes dirty rows and refreshes recent history; the journal's pull-on-resume / PTR still covers older windows.
5. ✅ Midnight rollover: `JournalViewModel` recomputes `today` on every `refresh()`, and `JournalScreen` calls `refresh()` from `LifecycleEventEffect(ON_RESUME)`.
6. ✅ Push errors visible: `PushStatus` flow + top-bar `SyncIssueChip` (six labels covering offline / server unreachable / server error × dirty-or-not).
7. ✅ **Destructive fallback removed.** `DatabaseModule` no longer calls `fallbackToDestructiveMigration()`, and `@Database(exportSchema = true)` writes per-version JSON to `app/schemas/` (checked into git). Any future schema bump (v2→v3 etc.) must ship with a `Migration` added via `.addMigrations(...)`, plus a `MigrationTestHelper` test replaying the previous schema. Without one, the app will crash at startup rather than silently wipe user data.
8. ✅ **`UiPreferences` reset on sign-out.** `SyncCoordinator` calls `UiPreferences.clear()` alongside `database.clearAllTables()`, so theme, density, editable-days, and show-private all return to defaults. Still device-wide rather than user-scoped — if multi-account-on-one-device becomes a goal, scope by `(serverUrl, login)`.
9. ✅ **Orphan `TrackPrefs` rows swept on pull.** `SyncManager.reconcileTracks` deletes any `track_prefs` row whose `serverId` is no longer in the server's response, alongside the corresponding track deletion. `track_prefs` is local-only so there's nothing to push.
10. ☐ **No tests.** Phase 5. The sync-conflict matrix is the highest-leverage starting point.

## Future considerations (not on the critical path)

Items consciously deferred. Revisit when product priorities shift or when external factors (e.g. AndroidX replacement landing stable) force a decision.

- **Tap targets <48dp at high density.** `MinCellSize = 28dp` falls below Material's 48dp guideline at the High density setting. Mitigations: bump `MinCellSize`, or expand the click area beyond the visible cell via `Modifier.minimumInteractiveComponentSize` / a transparent padded hitbox. Acceptable for v1 as a power-user trade-off.
- **Hardcoded strings everywhere.** No `strings.xml`, no localization. Single-locale (English) is fine for v1; revisit if localization becomes a goal.
- **`security-crypto` is alpha.** AndroidX is replacing it. Monitor and migrate when a stable replacement lands; for now the alpha is the only practical option for `EncryptedSharedPreferences`.
- **Tablet / large-screen pass.** `WindowSizeClass`-driven layouts, two-pane settings, expanded journal density. Out of scope while the product targets phone-only use; revisit if tablet/foldable usage becomes a goal.
- **TalkBack `contentDescription` on tick cells.** Custom-drawn cells have no semantics, so a screen reader announces decorative children ("3", "checkmark") with no track / date / state context. Original plan called for `"Meditate, Sat April 25, ticked"`. Documented as a limitation for the initial release; revisit for an accessibility pass.
- **Inert columns in the schema.** `TickEntity.updatedAtLocal` and `TrackEntity.dirty/deleted` are unused (no track CRUD in v1). Not worth a dedicated migration — fold the cleanup into the next migration that's already touching those tables. Until then, any future `PushWorker` change must guard against pushing tracks (the `dirty/deleted` columns shouldn't be interpreted as intent to push).
