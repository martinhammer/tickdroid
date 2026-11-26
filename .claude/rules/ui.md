---
paths:
  - "app/src/main/java/com/martinhammer/tickdroid/ui/**"
  - "app/src/main/java/com/martinhammer/tickdroid/MainActivity.kt"
  - "app/src/main/res/**"
---

# UI: theme, auth, navigation, accessibility, icon

## Theme
- `dynamicLightColorScheme` / `dynamicDarkColorScheme` with fallback palette.
- `TickdroidTheme` is wired inside `TickdroidApp` and reads `RootViewModel.themeMode`. Choices: System / Light / Dark.
- Edge-to-edge in `MainActivity`.

## Auth / onboarding (`AuthScreen`)
- Top bar: "Connect with Tickbuddy" + a `HelpOutline` action that opens a `ModalBottomSheet` with a one-paragraph explainer and labeled blurbs for each field.
- Fields: "Nextcloud server URL" (validates `https://`, strips trailing slash), "User", "App password" (with eye toggle).
- Connect button runs `AuthProber.probe`, which round-trips through server-up → 401-style auth check → tickbuddy app installed. The error message reflects which stage failed.

## Navigation
- **Today is the start destination when signed in** (`Routes.TODAY`, still frozen with `remember`), and `SyncNavToAuthState`'s signed-in target, so TODAY is always the root of the back stack while signed in. **The bar navigates with `openTab(route)`**: `popUpTo(Routes.TODAY) { saveState = true }`, `launchSingleTop`, `restoreState` — the standard bottom-nav pattern, which keeps the Journal's layout, scroll and loaded days. **Pop by route, never `graph.startDestinationId`** (frozen at AUTH after a sign-in, so popping it would be a no-op — the same trap as below). The Journal's Back is a `BackHandler` calling `openTab(TODAY)` (a plain pop would discard its saved state). Bar Today and day labels write `openFromJournal` then `openDay` (epoch day) into TODAY's own entry's `savedStateHandle` (the `JUST_ARCHIVED` pattern), and `TodayScreen` applies and clears them; with `fromJournal`, a `BackHandler` returns to the Journal. Sign-out also `clearBackStack(Routes.JOURNAL)` so the next account doesn't restore the last one's Journal.
- **`MainActivity` is `adjustResize`** (manifest, `windowSoftInputMode`; do not remove). With edge-to-edge that means the window is neither panned nor resized and the keyboard reaches Compose only as `WindowInsets.ime`. Left unset, Android picked `adjust=pan` (seen in `dumpsys window`): it panned the whole window up while the note card lifted itself above the keyboard with its `ime` padding, so the card opened at the top of the screen, half off it, until the first key press made Android drop the pan (Samsung S23, release build, 29 Oct).
- **`MainActivity` is `singleTop`** (manifest). Widgets open the app with Glance's `actionStartActivity<MainActivity>()` and setup's "Open Tickdroid" with `FLAG_ACTIVITY_NEW_TASK`; without a launch mode each such tap stacked a second `MainActivity` (Back first showed the old copy). With a single activity in its task, a new start now reaches the existing one (`onNewIntent`, nothing to handle), screen intact. Verify with `adb shell dumpsys activity activities | grep -i tickdroid` (exactly one `MainActivity`).
- **One driver for auth transitions.** `NavHost`'s `startDestination` is captured once via `remember` and must *not* be derived from a changing `authState`: `NavHost` rebuilds its graph whenever `startDestination` changes, and `NavController.setGraph` pops the entire back stack when handed a new graph instance, destroying the current destination's `ViewModelStore`. With both that and `SyncNavToAuthState` reacting to the same state, first sign-in tore down `JournalViewModel` mid-pull and crashed the process (see "Coroutine scoping" in `.claude/rules/sync.md`). `SyncNavToAuthState` is the sole driver; `startDestination` only picks the entry point. Freezing it is safe because `AuthRepository` resolves real state in its constructor, so a returning user's first composition already reads `SignedIn`.
- **Pop the whole graph, never the start destination.** `SyncNavToAuthState` uses `popUpTo(navController.graph.id) { inclusive = true }`. It must not use `graph.startDestinationId`: now that the start destination is frozen at the process's initial auth state, it no longer tracks where the user is, and `popUpTo` on a destination that isn't on the back stack is a silent no-op. That left the previous screen and its ViewModel alive underneath the new one — so after sign-out → sign-in → sign-out the login screen came back pre-filled with the previous session's server, user and **app password** (revealable via the eye toggle), and `AUTH` sat under `JOURNAL` so Back from the journal landed on the login screen while signed in. Note `remember` is not `rememberSaveable`, so the frozen value re-evaluates after a configuration change — another reason nothing may depend on `startDestinationId` being current.

## Accessibility
- `minSdk 31` → predictive back works out of the box.
- TalkBack content descriptions on tick cells **not yet implemented** — see `docs/backlog.md`. Documented as a limitation for the initial release.
- Tap targets shrink to ~28dp at high density, below the 48dp Material guideline — see `docs/backlog.md`.

## App icon
- Adaptive icon only (no legacy density mipmaps). Foreground vector renders the Nextcloud logo (white circle + checkmark) inside the 72dp safe zone of the 108dp canvas; background is solid `#0082C9`. Foreground also serves as the Android 13+ themed-icon monochrome layer.
