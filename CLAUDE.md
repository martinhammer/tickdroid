# Tickdroid — Architecture & Implementation Plan

Companion Android app to the Tickbuddy Nextcloud webapp. See `README.md` for product context and `mobile_instructions.md` for the authoritative API / sync handoff doc (the code wins if it disagrees).

This file captures the current state of the codebase and decisions made along the way. Update it as decisions change.

## Scope decisions (v1)

- **Auth**: pasted Nextcloud app password. Login Flow v2 stubbed but not implemented — seam exists in `AuthRepository.beginLoginFlowV2()`.
- **HTTPS only**: the early "Allow http://" toggle was removed. `ServerUrl.normalize` rejects anything but `https://`.
- **TLS trust**: `res/xml/network_security_config.xml` trusts `system` **and** `user` CA stores, so self-hosted servers behind a private/self-signed CA work once the user installs that CA on the device (issue #37). Chain, hostname and expiry are still fully verified — there is deliberately no "ignore TLS errors" switch, and adding one would be a regression (it is what Google's [policy page](https://support.google.com/faqs/answer/6346016) warns against, and the likely route to an F-Droid anti-feature). Two traps: the config **cannot** be narrowed with `<domain-config>` because the server hostname is entered at runtime and the file is a static resource; and `app/src/debug/res/xml/` **overrides** it in debug builds, so the debug copy must stay a superset of the main one or the feature silently vanishes from every build a developer tests on. Per-certificate trust-on-first-use (the model the Nextcloud Android client uses) is the stronger design and remains unbuilt — see "Future considerations".
- **TLS failures are classified, not lumped in with "unreachable"**: `SSLHandshakeException` / `SSLPeerUnverifiedException` are `IOException` subtypes, so without care they land in the generic "could not reach the server, check the URL and your network" branch — advice that points at three things that are all fine. `AuthProber` catches `SSLException` **before** `IOException` at all three probe stages (Kotlin matches catch clauses in order — the ordering is the whole mechanism) and returns `AuthProbeResult.UntrustedCertificate`; `SyncManager.pull` and `PushWorker` do the same for `SyncErrorKind.UntrustedCertificate`. Catch `SSLException`, **not** `CertificateException` — the latter is never the thrown type, only a cause several links down. And classify on exception *type*, never on `message` text: the wording comes from the platform TLS provider (Conscrypt on device, SunJSSE under JVM unit tests) and differs between them, so unmatched detail is passed through verbatim rather than guessed at. Coverage: `AuthProberTlsTest` (real TLS socket, cert from a CA the client doesn't know).
- **History depth**: full database, infinite scroll backwards in 30-day windows from day 1.
- **Private tracks**: simple show/hide toggle (App settings → Show private tracks). No PIN/biometric gate.
- **Counter UX**: short tap +1, long tap −1 (no-op when value is already 0).
- **Counter conflicts**: silent last-write-wins. The backend has no `/inc` endpoint, so two devices both incrementing on the same day will lose one increment. Acceptable for v1; not surfaced in UI.
- **Sign-out**: calls `database.clearAllTables()`, resets `UiPreferences` (`clear()`) and clears `WidgetConfigStore` so the next user on the same device doesn't inherit cached data, queued writes, UI settings (theme, density, journal layout, editable-days, show-private), or widget setups. **Never `close()` the database here.** `TickdroidDatabase` is a Hilt `@Singleton`; nothing recreates it, so closing leaves every consumer (SyncManager, the repositories, the injected DAOs) holding a dead instance for the rest of the process, and signing back in without killing the app crashes on the first query with `SQLException: connection is closed`. An earlier version closed and deleted the DB *file* to avoid `clearAllTables()` running migrations on a stale on-disk schema; that trade was wrong — there is no destructive-migration fallback, so a stale schema crashes at startup long before anyone reaches sign-out. The wipe is gated on a real `SignedIn → SignedOut` transition (`SyncCoordinator` tracks the previous state) and runs under `SyncManager.runExclusive` so it can't race an in-flight pull. Regression coverage: `SyncCoordinatorTest.signOut_thenSignInAgain_leavesDatabaseUsable`.
- **Editability**: configurable in App settings. Choices: today only / today + previous day / last 7 days / all past days. Future days never editable. Tapping a locked cell shows a Toast.
- **Out of scope for v1** (deferred): track management (add/edit/delete/reorder), Login Flow v2, import/export, daily reminder, real schema migrations.

## Tech stack

| Concern | Choice |
|---|---|
| DI | Hilt + `androidx.hilt:hilt-work` for `@HiltWorker` |
| Async | Kotlin Coroutines + Flow |
| Local DB | Room (currently schema v2; `exportSchema = true` writes JSON to `app/schemas/`. No destructive fallback — every version bump must ship with a real `Migration`) |
| HTTP | Retrofit + OkHttp + kotlinx.serialization |
| Background sync | WorkManager (manifest disables auto-init; Hilt provides config) |
| Secure storage | EncryptedSharedPreferences (`security-crypto` alpha) for credentials |
| App prefs | Plain SharedPreferences via `UiPreferences` (widget choices: `WidgetConfigStore`, own file) |
| Home-screen widgets | Jetpack Glance 1.2 (`glance-appwidget` + `glance-material3`; `glance-appwidget-testing` for instrumentation) |
| Navigation | Navigation-Compose |
| Date/time | `java.time` (`LocalDate`); `android.icu.util.Calendar` for locale-aware weekend; `android.text.format.DateFormat` for the user-set date format |
| Testing | JUnit + Turbine + MockWebServer + MockK (JVM unit) · Room/work-testing + Hilt-testing + Compose ui-test-junit4 + espresso-core 3.7.0 + Glance appwidget-testing (instrumentation) |

`compileSdk = 37`, `minSdk = 31`, `targetSdk = 36`.

## Release build

- `isMinifyEnabled = true` for the `release` build type — R8 strips/shrinks/optimizes. `proguard-rules.pro` has two custom rules: `-dontwarn com.google.errorprone.annotations.**` (Tink ships errorprone annotation references that aren't on the runtime classpath; benign compile-time-only suppression), and `-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }`. **Do not drop the second one.** Glance creates widget tap callbacks by reflection (`getDeclaredConstructor().newInstance()`); its own consumer rule keeps the classes but, under R8 full mode, not their no-arg constructors, so without it every widget tap silently fails in release builds only (debug is unminified, and the tests run debug). Verified by `dexdump` on the release APK. All other libraries (Retrofit, kotlinx-serialization, Room, Hilt, OkHttp, the rest of Glance) ship sufficient consumer rules.
- `android:allowBackup="false"` in the manifest. Auto Backup and device-to-device transfer are both disabled to keep `EncryptedSharedPreferences` credentials (key bound to the device Keystore) from ending up in a backup that can't be restored. Local state is recoverable by re-syncing from Nextcloud; only per-track color/emoji overrides (`track_prefs`) would be lost on reinstall.
- F-Droid builds from source and — because reproducible builds are enabled (see below) — ships the APK signed with **our** key rather than theirs. Local release APKs need to be signed (e.g. with the debug keystore via `apksigner`) only for sideload testing.

## F-Droid publishing

Submitted for inclusion on 2026-07-23 via merge request [fdroiddata!43688](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/43688), which closes [rfp#3903](https://gitlab.com/fdroid/rfp/-/issues/3903). The recipe lives in F-Droid's own `fdroiddata` repo at `metadata/com.martinhammer.tickdroid.yml`, **not** in this repo. Because it sets `AutoUpdateMode: Version` + `UpdateCheckMode: Tags`, F-Droid picks up new `v*` tags automatically — the recipe shouldn't need further edits for routine releases.

**Reproducible builds are enabled** (`Binaries` + `AllowedAPKSigningKeys` in the recipe), so the F-Droid build is verified byte-identical to our published APK and then signed with our certificate (SHA-256 `a398f829a84c52c9a8bbf2c1357f036b8e822b71ca86f56edebe36bda8ba1c3c`). That keeps one signature across GitHub, F-Droid, and — if it's ever added — Play, so users can move between sources without uninstalling. It is also effectively a one-way door: had we let F-Droid sign with their key, switching later would break updates for existing users.

Two constraints follow, and both are easy to trip over months from now:

- **Never re-enable `dependenciesInfo.includeInApk`.** AGP embeds a Google-key-encrypted dependency-metadata blob in the APK signing block by default. F-Droid's `check apk` scanner rejects it outright (`ERROR Found extra signing block 'Dependency metadata'`) and it is non-reproducible anyway. `includeInBundle = true` is deliberately kept so Play would still get dependency insights — the two flags apply to different artifacts (`assembleRelease` vs `bundleRelease`) and never interact.
- **Every release must stay byte-reproducible and be published at the `Binaries` URL**, i.e. a `tickdroid-<versionName>.apk` asset attached to the `v<versionName>` GitHub release. F-Droid rebuilds from the tag and compares against exactly that file; if it diverges, the release can't ship with our signature. Also keep signing with the same keystore — a different cert breaks `AllowedAPKSigningKeys`.

To sanity-check a release APK before publishing, enumerate its APK Signing Block IDs: expect only `0x7109871a` (v2 signature) and `0x42726577` (verity padding), and **never** `0x504b4453` (dependency metadata). Verifying content reproducibility is a `unzip -v` CRC comparison against F-Droid's from-source build — v2-only signing adds no zip entries, so every entry's CRC must match.

## Package layout (single module)

```
com.martinhammer.tickdroid
├── data
│   ├── auth         // AuthRepository, EncryptedCredentialStore, AuthProber, AuthState
│   ├── local        // Room entities, DAOs, database (TickdroidDatabase)
│   ├── network      // NetworkMonitor (ConnectivityManager → Flow<Boolean> isOnline)
│   ├── prefs        // UiPreferences + enums (GridDensity, ThemeMode, EditableDays, JournalLayout)
│   ├── remote       // TickbuddyApi, OCS envelope, DTOs, OcsHeaders/BasicAuth interceptors, ServerUrl
│   ├── repository   // TrackRepository, TickRepository, TrackPrefsRepository, mapping
│   ├── sync         // SyncManager (pull), PushWorker, SyncScheduler, SyncCoordinator
│   └── time         // Clock seam (SystemClock production impl, fakeable in tests)
├── domain           // Track, Tick, TrackType, TrackPrefs, TrackColor
├── widget           // Home-screen widgets (Glance): three widget kinds + receivers, WidgetModels (pure), WidgetTickCell (shared cell/controls), WidgetActions, WidgetTickHandler, WidgetRefresher, WidgetRollover, WidgetConfigStore, WidgetConfigActivity/Screen/ViewModel, WidgetEntryPoint
├── ui
│   ├── about        // AboutScreen
│   ├── auth         // AuthScreen, AuthViewModel (with help bottom sheet)
│   ├── common       // EmojiRender (desaturatedEmoji modifier), Dimens (MaxContentWidth, CompactHeightThresholdDp), TrackBadge (shared by Tracks settings and widget setup)
│   ├── journal      // JournalScreen, JournalViewModel (with help bottom sheet), JumpToTodayChip, TracksDownGrid + TracksDownLayout (pure maths), JournalDimens (shared grid constants)
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
  - **The rewrite must carry the origin's subpath**, via `encodedPath(serverUrl.encodedPath.trimEnd('/') + originalUrl.encodedPath)`. `TickbuddyApi`'s paths are absolute (`@GET("/ocs/v2.php/...")`), so they replace the path wholesale; setting only scheme/host/port — as it did until 1.0.6 — dropped the prefix from `https://example.com/nextcloud` on every API call. The failure was quiet and confusing because `AuthProber` builds URLs with `addPathSegments` and so *always* kept the subpath: the connect probe passed and then every real request went to the wrong place, failing in whatever way the host's root happens to answer. Subpaths are explicitly offered to users in `AuthViewModel`'s `NotNextcloud` message. A bare origin's `encodedPath` is `/`, which trims to empty and leaves the path untouched. Covered by three `BasicAuthInterceptorTest` cases (subpath, subpath + query, trailing slash) — the original tests only ever used a bare `host:port`, which is why this survived to release.
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
- **Coroutine scoping (do not regress)**: sync is *application* work, never UI work. `SyncManager.schedulePull` runs pulls on `SyncManager`'s own application-lifetime scope; UI must call that rather than `viewModelScope.launch { pull(...) }` (`PushWorker` still awaits `pull` directly — it has to, to report its `Result`). Room reacts to a cancelled transaction caller by closing the pooled connection while the block is still running on its own executor thread, so the next statement throws `SQLITE_MISUSE` ("connection is closed") on a thread with no cancellation handling — an uncaught fatal, not a `CancellationException`. Belt-and-braces, the reconcile in `pull` and both write paths in `TickRepository` run under `NonCancellable`. Regression coverage: `SyncCancellationTest`.
- **Lifecycle**: `SyncCoordinator` (started from `TickdroidApplication.onCreate`) collects `AuthRepository.state`. On sign-in: schedule periodic + kick a one-shot. On sign-out (only when transitioning from `SignedIn`, never on the initial fresh-install `SignedOut`): cancel both + `clearAllTables()` (under `runExclusive`, and never `close()` — see "Scope decisions") + reset `UiPreferences` + clear `WidgetConfigStore` (widgets then redraw on their own, via `WidgetRefresher`).
- **Cancellation restores the status (do not regress).** `PushWorker.drain` and `SyncManager.pull` set `Pushing` / `Syncing` before going to the network, and their catches only handle HTTP / TLS / IO errors. A `CancellationException` (WorkManager stopping the worker, typically because JobScheduler's network constraint failed when the network lost validation) used to slip past them and leave the status stuck for the rest of the process: the journal's chip vanished (`computeSyncIssue` ignores a push in flight), its spinner could spin forever, and the widgets showed a dead faded ⟳ instead of the offline symbol. Both now catch `CancellationException`, put back the status from before the attempt (an interrupted attempt says nothing new about the server), and rethrow. Coverage: `PushWorkerTest.cancelledMidPush_restoresThePreviousStatus`, `SyncCancellationTest.cancelledPull_restoresThePreviousStatus` (both fail with the stuck state if the catch is removed).
- **Status surfacing**: `SyncManager` exposes `status: StateFlow<SyncStatus>` (pull) and `pushStatus: StateFlow<PushStatus>`, both of which carry a `SyncErrorKind` (`ServerUnreachable` / `UntrustedCertificate` / `ServerError`) when in `Error`. `JournalViewModel` combines those with `NetworkMonitor.isOnline` and `TickRepository.observeHasDirty()` into a `SyncIssue` (`Offline` / `ServerUnreachable` / `UntrustedCertificate` / `ServerError` / `None`, each tagged with `hasUnsavedChanges`). The top bar shows a `CircularProgressIndicator` during pull and a tonal `AssistChip` (`errorContainer` colors, `CloudOff` icon) whose label varies: "Offline" / "Server unreachable" / "Certificate not trusted" / "Sync error", optionally suffixed with ", unsaved changes". Both `when` blocks in `SyncIssue.kt` are exhaustive, so adding a kind is compiler-guided.

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
  - Cell tint: custom `TrackColor.container` if the user assigned one; otherwise `primaryContainer` for ticked cells (uniform across track types — the type is already conveyed by the empty-cell affordance); empty cells use `surfaceContainerHighest`.
  - On-color: from `TrackColor.onContainer` (luminance-aware) or M3 `onXContainer`.
  - Filled boolean cells render `Icons.Filled.Check`. Filled counter cells render the value with `tnum`.
  - Editable empty cells render a faint affordance: `Icons.Filled.Add` (counter) or a bold interpunct `·` (boolean), both at ~50% cell size and 35% alpha.
- **Tap UX**: `combinedClickable` only on editable days (per `EditableDays` policy). Boolean → tap toggles; counter → tap +1, long-press −1 (no-op at 0). Light haptic on each. Tapping a locked cell pops a Toast: *"This day is locked. Select editable days in App settings."*.
- **Pull-to-refresh** triggers `JournalViewModel.refresh()` (which also recomputes `today` for midnight rollover). Empty-state screens (loading / no tracks / all private) are `Modifier.verticalScroll`'d so PTR still works.
- **Infinite scroll**: a derived `nearBottom` flag in `JournalGrid` calls `loadOlder()` which extends `_oldestVisible` by 30 days and pulls just the new chunk.
- **Jump-to-today chip** (`JumpToTodayChip`, both layouts): an `ElevatedAssistChip` ("Today", `CalendarToday` icon) floating at the bottom end of the grid, animated in once the user is away from today — Days down when `listState.firstVisibleItemIndex > 0` (today is item 0), Tracks down when today's column has scrolled off (`firstVisibleItemIndex > 0`). Each grid wraps its content in a `Box` to host it, and pads its scroll content by `ChipClearance` (72dp) so the last row can scroll clear. **Insets**: the chip applies only the horizontal nav-bar/cutout insets; the bottom nav-bar inset is already in the Scaffold padding (there's no `bottomBar`). **Days down also re-expands the top bar on tap**: `animateScrollToItem` is a programmatic scroll, which doesn't go through nested scroll, so a collapsed `LargeTopAppBar` would stay collapsed with the list back at the top. `JournalScreen` passes `onJumpToToday`, which animates `scrollBehavior.state.heightOffset` back to 0 (and resets `contentOffset`). Tracks down's chip scrolls sideways, so it has nothing to undo.
- **Insets: consume what the Scaffold already padded.** M3's default `contentWindowInsets` is `systemBars ∪ displayCutout` (verified in the material3 1.4.0 source), but `Scaffold` does not *consume* them. `JournalScreen` therefore applies `.padding(padding).consumeWindowInsets(padding)` to the `PullToRefreshBox`; without the consume, the grids' own `windowInsetsPadding(navigationBars ∪ displayCutout)` added the camera cutout a second time, leaving a ~47dp empty strip beside the camera in landscape (both layouts). Keep the grids' inset modifiers: after the consume they are no-ops, but they keep the grids correct if hosted elsewhere.
- **Shared pieces**: `JournalEmptyState` (loading / no tracks / all private) and `rememberIsWeekend(day)` are used by both layouts. Grid constants live in `JournalDimens.kt` as `Float` dp (for the JVM-testable maths) with `Dp` twins derived from them, so the two can't drift.

### Journal: "Tracks down" layout
Optional second layout (App settings → Journal layout), in which tracks are rows and days are columns so full track names fit. "Days down" (`JournalGrid`) stays the default and its code path is untouched apart from the shared helpers above; `JournalScreen` branches on `JournalUiState.layout`.

- **Day-by-day scrolling: two snapping `LazyRow`s, one vertical scroll.** The day columns are a `LazyRow` of one-slot items with `rememberSnapFlingBehavior(listState, SnapPosition.Start)`: a fling travels freely, a slow drag settles on the closest column edge, and the leftmost column always ends up flush with the name column. A sticky header row (range label + a second `LazyRow`, `userScrollEnabled = false`) sits above a `verticalScroll` `Column` holding the name column and the day columns. The body drives; the header follows via a `snapshotFlow` on `firstVisibleItemIndex`/`firstVisibleItemScrollOffset`. **Use `requestScrollToItem`, not `scrollToItem`, for the follow**: the body updates its position during measure, the collector can resume mid-layout, and the suspending scroll forces a remeasure, giving `IllegalArgumentException: performMeasureAndLayout called during measure layout` (caught by `TracksDownGridTest`). **Not a `HorizontalPager` with one-slot pages**, though that was the first version (and what an earlier spec preferred): on a slow release the pager chooses its target from the finger's travel in whole pages (`PagerSnapLayoutInfoProvider`, decimal part of `upDownDifference / pageSize`), and with pages this small the touch slop (~20% of a column) makes that disagree with the real scroll. A slow one-column drag snapped *back*, 1.6 columns jumped 2. LazyRow snapping picks the closest edge by actual position. Track rows are not a `LazyColumn` (track counts are small).
- **Uniform row height.** Labels show the emoji plus the full name, wrapping to two lines and then ellipsizing; **at High density names stay on one line** (ellipsized), so rows stay one cell tall. **Words are never split across lines**: Android's line breaker has no such mode, so `splitLabel` (pure, JVM-tested) puts as many whole words on line 1 as fit — measured with a `TextMeasurer` against the width from `BoxWithConstraints` — and the rest on line 2, and each line renders single-line with an ellipsis. A word too wide for a line becomes "Exerc…" rather than "Exercis / e". Semantics carry the full name as one text node. (Hyphenation was tried and dropped — it looked messy in practice.) a row is at least `cellSize + CellGap` tall and grows to fit a wrapped name, with its cells centred in the taller slot. Because label column and pager are separate children, the body row is a `SubcomposeLayout` that measures the labels first (at the label column's real width) and passes the resulting row heights to the pager. A plain `Row` can't do this: it measures the fixed-width pager *before* the weighted label column. Placement uses `placeRelative` so RTL still mirrors. Covered by `TracksDownGridTest.longName_wrapsAndGrowsRow_cellsStayAligned`. Every row gets one height: one cell row (`cellSize + CellGap`) normally, or the tallest label's height if any name wraps, so the grid stays even. The labels are centred in that height, like the cells. Because the name column and day columns are separate children, the body row is a `SubcomposeLayout` that measures the labels first (at the name column's real width) and passes the height to the columns. A plain `Row` can't do this: it measures the fixed-width columns *before* the weighted name column. Placement uses `placeRelative` so RTL still mirrors.
- **Columns anchored on today, newest first.** Column *i* is `today − i` (`dayForColumn`); column 0 is **today, leftmost** (its header reads "Today" in place of the weekday letter), going back in time to the right. Not calendar weeks (avoids the Nextcloud-vs-locale first-day-of-week mismatch). Swiping left goes back in time; Compose mirrors the lists under RTL. The header's range label shows the days in view (`daysInView(firstVisibleItemIndex, N, today)`, read via `derivedStateOf` so it recomposes once per column, not per frame).
- **Slot sizing** (`tracksDownMetrics`): up to `GridSizingMaxWidth`, the track-label column gets a fixed 36% share of it (`NameColumnFraction`, ~148dp on a 411dp Pixel 6a — enough for an emoji plus a typical name on one line), and the rest minus `RightPad` is split into N touching slots with the `TickCell` centred at `slot − CellGap`. N (columns visible) comes from `GridDensity.tracksDownDays` (Low 5 / Medium 6 / High 8 — deliberately not Days down's 5 / 7 / 9: Medium at 6 keeps cells at their Days-down size, ~35dp, on the 6a instead of squeezing 7 in at ~29dp), capped by how many 28dp cells fit and floored at 3. So High shows 7 on the 6a and 6 at 360dp. **Beyond the 480dp cap (landscape, tablets) the extra width becomes extra days**, not a wider label column: slot and label sizes stay what the cap gives (its "portrait-equivalent" size, as in Days down) and N = however many columns fit, e.g. 13 days at Medium on a Pixel 6a in landscape, with cells at ~42dp. So in landscape density sets the cell size, not the day count. (Originally the extra went to the label column, which left a ~600dp mostly empty name column.) Sizing uses the grid's measured width via `BoxWithConstraints`, **not** `Configuration.screenWidthDp`: with targetSdk 35+ the latter includes the display cutout, which in landscape would overflow the day columns. No half-cell peek: columns snap, so none is ever cut off. (The label used to get only what the slots left over, which bottomed out at a 104dp minimum and wrapped almost every name that had an emoji.)
- **History.** The list holds exactly the loaded `DateWindow` (`columnCount`), so it can't scroll onto unpulled days. Mirroring Days down's `nearBottom`: when `firstVisibleItemIndex + N >= count − 7`, `loadOlder()` adds 30 days. The effect is keyed on `nearEnd` **and** `count`, because after a long fling one extension may leave `nearEnd` still true, and an unchanged key alone wouldn't re-run it.
- **Keeping position needs no code.** Column indices don't depend on N, so a rotation or density change keeps the same leftmost day (the saveable `LazyListState` holds the index). This replaced the paged version's anchor logic, which drifted a page towards today on a rotate-there-and-back. Midnight rollover shifts everything one column (column 0 is the new today). Switching layout in settings starts on today.
- **Test hooks**: `testTag("tracksDownGrid")` on the day-column `LazyRow`, `"cell-<localId>-<epochDay>"` on each cell slot, `"label-<localId>"` on each name label, `"rangeLabel"`, `"jumpToToday"` on the chip. Bounds checks in tests must use *unclipped* bounds, and skip NaN: off-screen cells otherwise report bounds clipped to the list's edge.

### Navigation
- **One driver for auth transitions.** `NavHost`'s `startDestination` is captured once via `remember` and must *not* be derived from a changing `authState`: `NavHost` rebuilds its graph whenever `startDestination` changes, and `NavController.setGraph` pops the entire back stack when handed a new graph instance, destroying the current destination's `ViewModelStore`. With both that and `SyncNavToAuthState` reacting to the same state, first sign-in tore down `JournalViewModel` mid-pull and crashed the process (see "Coroutine scoping"). `SyncNavToAuthState` is the sole driver; `startDestination` only picks the entry point. Freezing it is safe because `AuthRepository` resolves real state in its constructor, so a returning user's first composition already reads `SignedIn`.
- **Pop the whole graph, never the start destination.** `SyncNavToAuthState` uses `popUpTo(navController.graph.id) { inclusive = true }`. It must not use `graph.startDestinationId`: now that the start destination is frozen at the process's initial auth state, it no longer tracks where the user is, and `popUpTo` on a destination that isn't on the back stack is a silent no-op. That left the previous screen and its ViewModel alive underneath the new one — so after sign-out → sign-in → sign-out the login screen came back pre-filled with the previous session's server, user and **app password** (revealable via the eye toggle), and `AUTH` sat under `JOURNAL` so Back from the journal landed on the login screen while signed in. Note `remember` is not `rememberSaveable`, so the frozen value re-evaluates after a configuration change — another reason nothing may depend on `startDestinationId` being current.

### Settings
Four top-level entries from the journal overflow menu:

- **Account** (`AccountSettingsScreen`): server URL, username (read-only), Log out (tonal error button).
- **App settings** (`AppSettingsScreen`):
  - Show private tracks (switch).
  - Editable days (segmented: Today / +1 day / 1 week / All).
  - Journal layout (segmented: Days down / Tracks down).
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

## Home-screen widgets

Three Glance widgets in package `widget`, all ticking **today only** (whatever Editable days says) and never showing private tracks (whatever "Show private tracks" says). Spec decisions are in the widgets implementation spec (2026-10-06); this is how they're built.

- **Kinds**: *Single track* (1×1, `SizeMode.Single`: one track's cell, labelled with the shared `ShortLabel` — emoji or two-letter abbreviation, as in the Today row — tap toggles / adds 1; "?" tile with "Choose track" or "Signed out" text when there's no track), *Today list* (`SizeMode.Exact`, resizable to 2×2: header + `LazyColumn` of rows, whole row is the tap target, Full / Short track labels per widget), *Today row* (`SizeMode.Exact`, 4×1 default: Days-down-style columns, tracks that don't fit are left out from the end via pure `rowCapacity`). No widget can subtract from a counter; that's done in the app.
- **One write path.** Every tap is an `ActionCallback` → `WidgetTickHandler.onTap` → `TickRepository.toggleBoolean` / `adjustCounter` — the journal's own transactional, dirty-bit, push-on-write path, so widget taps work offline and sync identically. The tap carries the epoch day the widget was **drawn for**; anything but `clock.today()` is refused (`StaleDay`: redraw + Toast "New day: tap again to tick"), so a late midnight alarm or a time-zone change can never put a tick on the wrong day.
- **Config**: `WidgetConfigStore`, its own SharedPreferences file `tickdroid_widget_prefs`, JSON per `appWidgetId`, tracks referenced by **server id** (local ids don't survive a re-pull). Tracks a list/row's map doesn't mention are shown, so new tracks appear automatically. Not in Room (would need schema v3 + a `Migration` for per-device UI state), not Glance state (can't be cleared in one call on sign-out). Cleared on sign-out, entries removed in `onDeleted`.
- **`WidgetRefresher` is the single redraw hook.** An application-scope `combine` over auth, tracks, prefs, today's ticks, sync status (`computeSyncIssue`, so the widgets' offline symbol and the journal chip always agree) and the store's `revision`, held in a `StateFlow` (equal data — e.g. a pull that rewrote unchanged rows — doesn't redraw) and debounced into `updateAll`. That covers journal taps, widget taps, PushWorker, PTR, emoji/colour edits and the sign-out wipe with no change to the sync layer.
- **Widgets collect `WidgetRefresher.inputs` inside the composition — do not "simplify" this to a snapshot loaded in `provideGlance`.** While a Glance session is alive (up to ~45 s after its last update), `update()` only sends `UpdateGlanceState` and recomposes; it does **not** re-run `provideGlance`. A one-off snapshot would keep drawing the pre-tap state after the very tap that changed it. The collector lives only as long as Glance's session. **The converse matters just as much: `WidgetRefresher`'s `updateAll` on every change is not redundant with that collector.** Most of the time a widget's session has closed, nothing is collecting, and only `update()` starts a new session to redraw it. Both halves are needed.
- **Day changes**: an inexact, non-wakeup `AlarmManager.setAndAllowWhileIdle(RTC, …)` for 00:01 (pure `nextRolloverMillis`, DST-safe) plus `TIME_SET` / `TIMEZONE_CHANGED`, all to `WidgetRolloverReceiver` (exported for the system broadcasts; it only redraws and re-arms). Armed only while a widget is placed. No exact-alarm permission and no battery-exemption request, by decision: the stale-day guard makes a late redraw harmless.
- **Setup runs in its own task.** `WidgetConfigActivity` has `android:taskAffinity=""` (plus `excludeFromRecents`). With the app's default affinity, ✎ → Save finished it back into Tickdroid's task and surfaced the journal instead of the home screen. Its "Open Tickdroid" button therefore starts `MainActivity` with `FLAG_ACTIVITY_NEW_TASK`, so the journal lands in the app's own task.
- **"Add widget" vs "Save" in setup** is not "is a setup stored?": a placed widget can have none (sign-out clears every widget's choices but keeps the widgets), and its first ✎ then read "Add widget". Intents from a placed widget (`configIntent`: ✎, "Choose tracks", "Choose track") carry `EXTRA_FROM_WIDGET`; setup reads "Save" if that is set **or** a setup is stored, so only the launcher placing a new widget gets "Add widget". Covered by `WidgetConfigViewModelTest`.
- **Sync slot** (list header, Today row): faded ⟳ with no action while syncing, else the cloud-off symbol in ⟳'s place while unsent ticks can't go up, else ⟳. The Single track's corner badge uses the same rule (`badge.slot == SyncSlot.Offline`, so it also hides while syncing); all three kinds must agree on when the symbol shows. Both ⟳ and the symbol run `RefreshAction` → `SyncScheduler.schedulePushNow()` (the existing push-then-pull worker), never a pull from the broadcast.
- **Colours**: `GlanceTheme` defaults (live Material You system colours) except the empty cell, which is the journal's `surfaceContainerHighest` read from `dynamicLight/DarkColorScheme` — Glance's colour set has no such role. That one colour is fixed at draw time, so after a wallpaper change it catches up on the next redraw. Emoji are full colour (RemoteViews can't filter text like `desaturatedEmoji`).
- **RemoteViews limits**: containers take at most 10 children (Glance silently drops the rest), so Today row columns go in nested `Row`s of 8. No SemiBold and no `tnum` in RemoteViews text; cells use Medium.
- **Tests**: pure maths/models on the JVM (`WidgetModelsTest`, `WidgetRolloverTest`, `WidgetConfigCodecTest`, `WidgetTickHandlerTest`); Glance node tests per kind (`runGlanceAppWidgetUnitTest`); `WidgetRemoteViewsTest`, which runs every state through Glance's real RemoteViews translation (`GlanceAppWidget.compose`) and inflates it — flip its `DUMP` flag to write PNGs for a visual check. Inflate under an `AppWidgetHostView`: the platform only installs collection adapters (the list's rows) under one, so a plain `FrameLayout` silently renders an empty list. `WidgetTickHandlerIntegrationTest` proves a widget tap leaves a dirty row and schedules one push.

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

**Phase 4 — Polish** ✅
16. ✅ Infinite scroll older history (30-day windows) + range-aware pulls.
17. ✅ Sync status indicator + error chip in top bar.
18. ✅ Settings screens (Account / App / Tracks + per-track editor).
19. ✅ Per-track customization (color from a 10-swatch palette, emoji rendered desaturated).
20. ✅ Editable-days policy + locked-cell Toast.
21. ✅ Theme picker (system/light/dark).
22. ✅ App icon (Nextcloud logo).
23. ✅ Landscape pass (compact-height top bar swap, grid width cap, horizontal display-cutout / nav-bar insets, IME handling, per-control max-width caps).

**Phase 5 — Testing** ✅
24. ✅ Unit tests for repositories, sync conflict matrix, OCS envelope, auth interceptor, TLS error classification (JVM tests in `src/test`).
25. ✅ MockWebServer integration tests against captured OCS fixtures (`src/androidTest/assets/ocs/*.json`).
26. ✅ Compose UI tests for `TickCell` interactions (tap / long-press / locked-cell). Full-screen `JournalScreen` tests deferred — see "Future considerations".

The full suite is 105 JVM unit tests + 95 instrumentation tests (200 total), including the widget tests listed under "Home-screen widgets". Added after the original Phase 5 pass: `AuthProberTlsTest` (untrusted-chain handshake → `UntrustedCertificate`, needs the `okhttp-tls` test dependency for MockWebServer over HTTPS), `SyncCancellationTest` (a cancelled caller can't tear down a Room transaction), `SyncCoordinatorTest.signOut_thenSignInAgain_leavesDatabaseUsable`, and three `BasicAuthInterceptorTest` subpath cases. What is still **not** covered: the `<certificates src="user"/>` trust anchor (no test can populate the device user CA store — verify manually on an emulator with a CA installed) and anything at `NavHost` level, which is where both navigation regressions came from. Run via `./gradlew :app:testDebugUnitTest` and `./gradlew :app:connectedDebugAndroidTest`. **The instrumentation suite needs an emulator with ≥ 4 GB RAM** (`hw.ramSize` in `~/.android/avd/<name>.avd/config.ini`; cold-boot after changing it). A 2 GB Google-APIs AVD gets the test process OOM-killed before any test runs — the symptom is a misleading `Starting 0 tests … Process crashed` with `lowmemorykiller: Kill 'com.martinhammer.tickdroid'` in logcat, not a real test failure.

**Testability seams introduced in Phase 5:**
- `data/time/Clock` — interface + `SystemClock` impl, bound by `di/TimeModule`. Injected into `JournalViewModel` and `PushWorker` so tests can pin "today". `TrackEntity` / `TickEntity` still default `updatedAtLocal = System.currentTimeMillis()`; if a future test needs deterministic timestamps, repos can pass `clock.nowMillis()` explicitly.
- `Credentials.basicAuthHeader` switched from `android.util.Base64` to `java.util.Base64` (byte-equivalent, JVM-testable).
- `SyncScheduler` made `open`, with `WorkManager.getInstance` deferred to a `lazy` so test subclasses can be constructed without WorkManager initialized.
- `SyncCoordinator.stop()` (`internal`) cancels the observer scope so tests can tear down without dangling wipe calls on a closed DB.
- `SyncIssue` + `computeSyncIssue` + `toLabel` extracted from `JournalScreen.kt` to `ui/journal/SyncIssue.kt` for JVM testability.
- `TickCell` composable made `internal` so the Compose test can render it directly.

The shared instrumentation rig is `app/src/androidTest/.../sync/SyncTestRig.kt` — wires in-memory Room + MockWebServer + Retrofit/OkHttp + a real `AuthRepository` pre-seeded as signed-in. Reused by `SyncManagerPullTest`, `PushWorkerTest`, `SyncMutexTest`. JSON fixtures live under `app/src/androidTest/assets/ocs/`.

## Tech debt / known limitations

Tracked from a code audit at the end of Phase 3. Items marked ✅ have been addressed; the rest are open.

1. ✅ Race-free local writes: `TickRepository` wraps read-modify-write in `db.withTransaction`.
2. ✅ Work scheduling: `OneTimeWorkRequest` uses `APPEND_OR_REPLACE` so a tap stream doesn't cancel the running drain.
3. ✅ Pull/push serialization via `SyncManager.mutex`.
4. ✅ **Periodic work also pulls.** `PushWorker.doWork()` runs the drain under the sync mutex, then (only on success) calls `SyncManager.pull(today − 30d, today)`. So the same 15-minute periodic worker now both pushes dirty rows and refreshes recent history; the journal's pull-on-resume / PTR still covers older windows.
5. ✅ Midnight rollover: `JournalViewModel` recomputes `today` on every `refresh()`, and `JournalScreen` calls `refresh()` from `LifecycleEventEffect(ON_RESUME)`.
6. ✅ Push errors visible: `PushStatus` flow + top-bar `SyncIssueChip` (six labels covering offline / server unreachable / server error × dirty-or-not).
7. ✅ **Destructive fallback removed.** `DatabaseModule` no longer calls `fallbackToDestructiveMigration()`, and `@Database(exportSchema = true)` writes per-version JSON to `app/schemas/` (checked into git). Any future schema bump (v2→v3 etc.) must ship with a `Migration` added via `.addMigrations(...)`, plus a `MigrationTestHelper` test replaying the previous schema. Without one, the app will crash at startup rather than silently wipe user data.
8. ✅ **`UiPreferences` reset on sign-out.** `SyncCoordinator` calls `UiPreferences.clear()` and `WidgetConfigStore.clear()` alongside `database.clearAllTables()`, so theme, density, journal layout, editable-days, and show-private all return to defaults, and every widget's choices are forgotten. Still device-wide rather than user-scoped — if multi-account-on-one-device becomes a goal, scope by `(serverUrl, login)`.
9. ✅ **Orphan `TrackPrefs` rows swept on pull.** `SyncManager.reconcileTracks` deletes any `track_prefs` row whose `serverId` is no longer in the server's response, alongside the corresponding track deletion. `track_prefs` is local-only so there's nothing to push.
10. ✅ **Tests landed in Phase 5.** 63 JVM unit tests + 62 instrumentation tests covering: repositories (transactional writes, dirty-bit lifecycle), sync conflict matrix (pull reconciles, push replay-safety, push/pull mutex, sign-out wipe), OCS envelope + headers + basic-auth interceptor + auth prober, the journal `TickCell` tap/long-press UX, and the "Tracks down" layout (pure sizing/paging maths on the JVM, `TracksDownGridTest` for paging and taps). The Compose UI coverage stops at `TickCell` rather than the full `JournalScreen` — see "Future considerations".
11. ✅ **Subpath server URLs fixed.** `BasicAuthInterceptor` now prepends the stored origin's path (see "Network"), with three regression tests. Found while investigating an unrelated crash; it had never been exercised because every existing test used a bare `host:port`.

## Future considerations (not on the critical path)

Items consciously deferred. Revisit when product priorities shift or when external factors (e.g. AndroidX replacement landing stable) force a decision.

- **Tap targets <48dp at high density.** `MinCellSize = 28dp` falls below Material's 48dp guideline at the High density setting. Mitigations: bump `MinCellSize`, or expand the click area beyond the visible cell via `Modifier.minimumInteractiveComponentSize` / a transparent padded hitbox. Acceptable for v1 as a power-user trade-off.
- **Hardcoded strings everywhere.** No `strings.xml`, no localization. Single-locale (English) is fine for v1; revisit if localization becomes a goal.
- **Trust-on-first-use certificate approval.** `<certificates src="user"/>` (see "Scope decisions") grants trust app-wide over the *whole* user CA store — a CA installed years ago for mitmproxy or a corporate MDM can also intercept Tickdroid traffic. The stronger model, used by the Nextcloud Android client, is to show the certificate on handshake failure (subject, issuer, validity, SHA-256) and let the user approve *that* certificate into an app-local keystore. Needs a custom `X509TrustManager` wired into all three OkHttp clients (`NetworkModule`'s singleton plus `AuthProber`'s two, which it builds itself — so a seam is needed), a persistent keystore, approval UI, a retry-after-approval flow inside the suspend probe, and a settings screen to review/revoke. Nextcloud's own dialog has a crash history ([#10871](https://github.com/nextcloud/android/issues/10871), [#11677](https://github.com/nextcloud/android/issues/11677)) — the design is right, the implementation needs care. If it lands, `src="user"` can be dropped or kept as a fallback.
- **Nextcloud Single-Sign-On.** [`nextcloud/Android-SingleSignOn`](https://github.com/nextcloud/Android-SingleSignOn) lets a companion app borrow the account already configured in the official Nextcloud Android client via an AIDL binding, instead of asking the user to paste an app password. Two things make it interesting here: it would supersede the pasted-app-password flow (and retire the `beginLoginFlowV2()` stub), and — because requests are proxied through the Nextcloud app — it inherits that app's TLS trust, including any self-signed certificate the user has already approved there. That is a free fix for [#37](https://github.com/martinhammer/tickdroid/issues/37) for anyone running the Nextcloud app. Costs: a hard dependency on that app being installed (so the app-password path has to stay as a fallback, i.e. two auth paths, not one), the SSO library speaks its own request/response types rather than Retrofit's, so `TickbuddyApi` would need a second implementation behind the interface, and JitPack hosting is a wrinkle for the F-Droid recipe. Roadmap item for further consideration, not scheduled.
- **`security-crypto` is alpha.** AndroidX is replacing it. Monitor and migrate when a stable replacement lands; for now the alpha is the only practical option for `EncryptedSharedPreferences`.
- **Tablet / large-screen pass.** `WindowSizeClass`-driven layouts, two-pane settings, expanded journal density. Out of scope while the product targets phone-only use; revisit if tablet/foldable usage becomes a goal.
- **Compose UI tests for the full `JournalScreen`.** Phase 5 covers `TickCell` interactions in isolation, but not the screen-level concerns (pull-to-refresh, sticky header scroll, infinite scroll, sync-issue chip rendering, overflow menu). Full-screen tests would need a Hilt test harness (`HiltTestActivity` + `@HiltAndroidTest`) and a fake `JournalViewModel`; the boilerplate per assertion is high. **Two regressions have now bitten** — both navigation, both shipped-and-caught-by-hand: the auth-state-driven `startDestination` that destroyed `JournalViewModel` mid-pull, and the `popUpTo(startDestinationId)` that resurfaced a signed-out user's credentials. Neither was detectable below the `NavHost` level, so this has graduated from "opportunistic" to the highest-value missing coverage: a harness that can drive sign-in → sign-out → sign-in and assert on the back stack and on `AuthUiState` being empty.
- **Toast assertions for locked cells.** `TickCellInteractionTest` only verifies that the boolean/counter callbacks don't fire when `editable=false`; it doesn't assert the Toast text. Asserting on `android.widget.Toast` from instrumentation is brittle. If we ever extract a `Toaster` seam (originally planned for Phase 5 but skipped) the assertion becomes trivial.
- **TalkBack `contentDescription` on tick cells.** Custom-drawn cells have no semantics, so a screen reader announces decorative children ("3", "checkmark") with no track / date / state context. Original plan called for `"Meditate, Sat April 25, ticked"`. Documented as a limitation for the initial release; revisit for an accessibility pass.
- **Inert columns in the schema.** `TickEntity.updatedAtLocal` and `TrackEntity.dirty/deleted` are unused (no track CRUD in v1). Not worth a dedicated migration — fold the cleanup into the next migration that's already touching those tables. Until then, any future `PushWorker` change must guard against pushing tracks (the `dirty/deleted` columns shouldn't be interpreted as intent to push).
- **Toolchain upgrade (Kotlin / KSP / Hilt / AGP / Gradle) — completed 2026-07.** The coordinated AGP 9 wall was climbed in one jump. Final versions: **AGP 9.2.1, Gradle 9.4.1, Kotlin 2.3.20, KSP 2.3.9 (KSP2), Hilt 2.60, Room 2.8.4, kotlinx-serialization 1.11.0, androidx.core 1.19.0, androidx.lifecycle 2.11.0, androidx.hilt 1.4.0, compileSdk 37** (targetSdk stayed 36 — no runtime-behavior opt-in), JDK 17 (unchanged; AGP 9.2 needs 17). CI green (unit tests + lint + debug build). How it fit together, for whoever does the next jump:
  - **The two "walls" were actually one.** AGP 9 refuses to run the KSP1 backend (`KSP1 … no longer compatible with Android Gradle Plugin 9.0.0`), so AGP 9 *forces* KSP2 — and KSP2 was the thing that crashed this project. There was no AGP-9-on-KSP1 intermediate.
  - **The linchpin was Room, not KSP.** The KSP2 `unexpected jvm signature V` crash (google/ksp#2177) is triggered by Room 2.6's generated code for `suspend` DAO methods returning `Unit`; it was fixed in **Room 2.7.0-alpha11+**. Bumping Room to 2.8.4 is what made KSP2 viable. Room 2.8 also needs serialization ≥ 1.9 for its schema `$$serializer` (we're on 1.11.0), and serialization 1.11 needs Kotlin 2.3 — so the whole chain is `AGP 9 → KSP2 → Room 2.7+ → serialization 1.11 → Kotlin 2.3`.
  - **AGP 9 built-in Kotlin.** AGP 9 applies the Kotlin plugin itself, so `org.jetbrains.kotlin.android` was **removed** (from the version catalog `[plugins]` and both build files). We *adopted* built-in Kotlin rather than opting out (`android.builtInKotlin=false` + `android.newDsl=false` would have kept the old plugin, but that path dies in AGP 10). `kotlin.plugin.compose`, `kotlin.plugin.serialization`, and `ksp` stay in the `plugins {}` block; the Kotlin version is now resolved from those plugin versions (KGP floor is 2.2.10 with built-in Kotlin). The `kotlin { compilerOptions { jvmTarget = JVM_11 } }` block is unchanged and still valid. `ksp.useKSP2=false` was removed from `gradle.properties` (KSP 2.3.x is KSP2-only).
  - **Dagger 2.60 needs `error_prone_annotations` on the compile classpath.** Its generated code references `@CanIgnoreReturnValue`, but Dagger no longer brings the annotation transitively — added `compileOnly(libs.errorprone.annotations)` (CLASS retention, stays out of the APK). Distinct from the pre-existing `-dontwarn com.google.errorprone.annotations.**` R8 rule, which is a *runtime/shrink* concern.
  - **Room schema unchanged.** The 2.6 → 2.8 bump re-exported `app/schemas/` byte-identically (still v2) — no migration needed.
  - **`renovate.json` after the jump:** the five version caps (AGP, gradle-wrapper, core, lifecycle, room) are gone. Kotlin/KSP/Hilt are still grouped behind dashboard approval as **"Kotlin / KSP / Hilt (coordinated)"** — the wall is crossed, but the axis stays coupled (KSP locked to Kotlin, AGP built-in Kotlin resolves KGP from plugin versions, Hilt metadata parser, plus the Room↔serialization and Dagger↔errorprone couplings above), so the *next* Kotlin bump should still be done deliberately as one PR.
