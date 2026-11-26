# CLAUDE.md — Tickdroid

Companion Android app to the Tickbuddy Nextcloud app. See `README.md` for product
context and `mobile_instructions.md` for the authoritative API / sync handoff doc
(the code wins if it disagrees). Tickbuddy owns the API: contract changes start
there. Released on GitHub and submitted to F-Droid, with reproducible builds signed
with our key.

Detail loads only when needed:

| Where | What | Loads |
|---|---|---|
| `.claude/rules/decisions.md` | The v1 scope decisions in full | any app source |
| `.claude/rules/data.md` | Room schema (v6), network layer, repositories | `data/local`, `data/remote`, `data/repository`, `data/auth`, `di`, `app/schemas` |
| `.claude/rules/sync.md` | Pull, push, server features, triggers, mutex, coroutine scoping, track changes | `data/sync`, `data/network`, `data/repository`, `data/time` |
| `.claude/rules/ui.md` | Theme, auth screen, navigation, accessibility, app icon | `ui/**`, `MainActivity.kt`, `res/**` |
| `.claude/rules/{journal,today,settings}.md` | One screen area each | `ui/journal`, `ui/today`, `ui/settings` + `ui/about` |
| `.claude/rules/values.md` | Number and Choice in the UI | `ui/entry`, `NumberValue.kt`, value cells and settings |
| `.claude/rules/widgets.md` | The three Glance widgets | `widget/**`, `res/xml` |
| `.claude/rules/build-release.md` | Release build, R8 rules, F-Droid, toolchain upgrade lessons | Gradle files, `proguard-rules.pro`, manifest, `fastlane`, `renovate.json`, workflows |
| `.claude/rules/testing.md` | Test suites, testability seams, the instrumentation rig | `app/src/test`, `app/src/androidTest` |
| `docs/backlog.md` | Future considerations, open work | read when planning |
| Android skills (Google's, installed with the Android CLI) | Glance widgets, R8, edge-to-edge, Compose performance, ANR, intent and permission security, testing, the Android CLI | by topic |

## Invariants

Each is explained in the rule named; read it before changing what it covers.
- **HTTPS only, and never an "ignore TLS errors" switch.** User CAs are trusted
  app-wide; the debug network-security config must stay a superset of the main
  one. TLS failures are classified by exception type, catching `SSLException`
  before `IOException` (`decisions.md`).
- **Sign-out never `close()`s the database**: `TickdroidDatabase` is a Hilt
  singleton (`decisions.md`).
- **Room has no destructive fallback**: every schema version bump ships a real
  `Migration` and a migration test (`data.md`).
- **Track management is online only**; colour and icon stay local (`decisions.md`).
- **An unknown track type is read-only, never a crash.** The release that first
  learns a new type deletes the older release's local ticks for it and keeps the
  track locked until its first successful sync (`decisions.md`).
- **Number and Choice: "has an entry" is "has a row", never `value > 0`**; Numbers
  are whole thousandths, and all decimal maths is in `domain/NumberValue.kt`
  (`decisions.md`, `values.md`).
- **Streaks come from the server** (`stats`); there is no phone-side streak rule.
- **Server capability flags gate every optional feature** (`ServerFeatures`); an
  older Tickbuddy must keep working (`sync.md`).
- **Sync rules marked "do not regress"** (re-read then settle, coroutine scoping,
  cancellation restores the status) are in `sync.md`.
- **Widgets collect `WidgetRefresher.inputs` inside the composition**, never a
  snapshot loaded in `provideGlance` (`widgets.md`).
- **Release builds:** never drop the two Glance keep rules in
  `proguard-rules.pro`; never re-enable `dependenciesInfo.includeInApk`; every
  release stays byte-reproducible and signed with the same keystore
  (`build-release.md`). Release builds and signing are Martin's.

## Commands

```sh
./gradlew :app:testDebugUnitTest   # JVM unit tests (CI)
./gradlew :app:lintDebug           # Android lint (CI)
./gradlew :app:assembleDebug       # debug build (CI)
./gradlew :app:connectedDebugAndroidTest   # instrumentation tests, needs a device or emulator
```

When the Android CLI (`android`) is installed, its `docs` command searches the
official Android documentation (`android docs -h`): prefer it over memory for
platform behaviour.

## Tech stack

| Concern | Choice |
|---|---|
| DI | Hilt + `androidx.hilt:hilt-work` for `@HiltWorker` |
| Async | Kotlin Coroutines + Flow |
| Local DB | Room (currently schema v6; `exportSchema = true` writes JSON to `app/schemas/`. No destructive fallback — every version bump must ship with a real `Migration`) |
| HTTP | Retrofit + OkHttp + kotlinx.serialization |
| Background sync | WorkManager (manifest disables auto-init; Hilt provides config) |
| Secure storage | EncryptedSharedPreferences (`security-crypto` 1.1.0, whose APIs AndroidX has deprecated; see `docs/backlog.md`) for credentials |
| App prefs | Plain SharedPreferences via `UiPreferences` (widget choices: `WidgetConfigStore`, own file) |
| Home-screen widgets | Jetpack Glance 1.2 (`glance-appwidget` + `glance-material3`; `glance-appwidget-testing` for instrumentation) |
| Navigation | Navigation-Compose |
| Drag-to-reorder | `sh.calvin.reorderable:reorderable` (Apache-2.0, pure Compose; Compose Multiplatform, so on Android its `org.jetbrains.compose.*` deps resolve to the BOM's androidx Compose) |
| Emoji picker | `androidx.emoji2:emoji2-emojipicker` (a View, hosted in `AndroidView`; brings appcompat, RecyclerView and Guava, and raises `emoji2` to 1.7.0) |
| Date/time | `java.time` (`LocalDate`); `android.icu.util.Calendar` for locale-aware weekend; `android.text.format.DateFormat` for the user-set date format |
| Testing | JUnit + Turbine + MockWebServer + MockK (JVM unit) · Room/work-testing + Hilt-testing + Compose ui-test-junit4 + espresso-core 3.7.0 + Glance appwidget-testing (instrumentation) |

`compileSdk = 37`, `minSdk = 31`, `targetSdk = 36`.

## Package layout (single module)

```
com.martinhammer.tickdroid
├── data
│   ├── auth         // AuthRepository, EncryptedCredentialStore, AuthProber, AuthState
│   ├── local        // Room entities, DAOs, database (TickdroidDatabase)
│   ├── network      // NetworkMonitor (default network → Flow<Boolean> isOnline), DefaultNetworkTracker (pure, callback args → state)
│   ├── prefs        // UiPreferences + enums (GridDensity, ThemeMode, EditableDays, JournalLayout)
│   ├── remote       // TickbuddyApi, OCS envelope, OcsError (reason from a failed reply), DTOs, OcsHeaders/BasicAuth interceptors, ServerUrl
│   ├── repository   // TrackRepository, TickRepository, NoteRepository, TrackRunRepository, TrackPrefsRepository, mapping
│   ├── sync         // SyncManager (pull, changeTracks), ServerFeatures (capability flags), TrackEditor (track management → TrackChange), PushWorker, SyncScheduler, SyncCoordinator
│   └── time         // Clock seam (SystemClock production impl, fakeable in tests)
├── domain           // Track, Tick, TrackType, TrackPrefs (+ CellLabel), TrackColor, TrackRun, TrackNames (255-code-point name rules), NoteText (note cleaning, 500-code-point cap, planNotePush), NumberValue (Number thousandths, parsing, formatting, shortLabel)
├── widget           // Home-screen widgets (Glance): three widget kinds + receivers, WidgetModels (pure), WidgetTickCell (shared cell/controls), WidgetActions, WidgetTickHandler, WidgetRefresher, WidgetRollover, WidgetConfigStore, WidgetConfigActivity/Screen/ViewModel, WidgetEntryPoint
├── ui
│   ├── about        // AboutScreen
│   ├── auth         // AuthScreen, AuthViewModel (with help bottom sheet)
│   ├── entry        // ValueEntryLayer: the Number card and the Choice sheet (Journal, Today, widget window), EntryTarget
│   ├── common       // ValueLabels (T20–T24 texts), CloseWithKeyboard, Dimens (MaxContentWidth, CompactHeightThresholdDp), TrackBadge (shared by Tracks settings and widget setup), HelpSheet ("How to use Tickdroid" for the Journal and Today)
│   ├── today        // TodayScreen (pager), TodayViewModel, TodayRow (squares, streak, note line), NoteCard, TodayText (pure: dayKind, runText, squareWindow, levelFor; plus ICU titles)
│   ├── journal      // JournalScreen, JournalViewModel (with help bottom sheet), TickCell (shared with Today), JumpToTodayChip, TracksDownGrid + TracksDownLayout (pure maths), JournalDimens (shared grid constants)
│   ├── settings     // SettingsScreen (Account, App), TracksSettingsScreen (+ NewTrackSheet), TrackDetailScreen (+ TrackDialogs, TrackIconRow, EmojiPickerSheet), TracksHelpSheet, SettingsRows (ToggleRow, SectionLabel), OfflineGuard, ScrollableRowFade, pure TrackOrder / IconChoices / ArchivedTracks, and the ViewModels
│   ├── theme        // Material You / dynamic color
│   ├── RootViewModel, TickdroidApp (NavHost), Routes, MainNavigationBar
└── di               // NetworkModule, DatabaseModule
```

Repository layer is the single source of truth: UI observes Room via Flow; sync layer is the only thing that touches the network.
