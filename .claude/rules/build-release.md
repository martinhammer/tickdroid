---
paths:
  - "build.gradle.kts"
  - "app/build.gradle.kts"
  - "settings.gradle.kts"
  - "gradle/**"
  - "gradle.properties"
  - "app/proguard-rules.pro"
  - "app/src/main/AndroidManifest.xml"
  - "fastlane/**"
  - "renovate.json"
  - ".github/workflows/**"
---

# Build, release and F-Droid

## Release build

- `isMinifyEnabled = true` for the `release` build type — R8 strips/shrinks/optimizes. `proguard-rules.pro` has three custom rules (the third is below): `-dontwarn com.google.errorprone.annotations.**` (Tink ships errorprone annotation references that aren't on the runtime classpath; benign compile-time-only suppression), and `-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }`. **Do not drop the second one.** Glance creates widget tap callbacks by reflection (`getDeclaredConstructor().newInstance()`); its own consumer rule keeps the classes but, under R8 full mode, not their no-arg constructors, so without it every widget tap silently fails in release builds only (debug is unminified, and the tests run debug). Verified by `dexdump` on the release APK. A third rule, `-keep class * extends androidx.glance.appwidget.GlanceAppWidget`, stops R8 **merging the three widget classes into one**: Glance finds a widget's ids by the GlanceAppWidget's class name, so after the merge every kind's `updateAll` also drew its own UI into the other kinds' widgets (on a real phone, a list widget showing the Single track's "Choose track"). `updateAllWidgets` also no longer uses Glance's `updateAll`; it takes each kind's ids from its manifest receiver, so neither class names nor Glance's on-disk name cache can cross kinds. **Release check:** after `assembleRelease`, `grep -E '^com\.martinhammer\.tickdroid\.widget\.(SingleTrackWidget|TodayListWidget|TodayRowWidget) ' app/build/outputs/mapping/release/mapping.txt` must list all three, each mapped to its own name. Both bugs are invisible in debug builds and in every test, which all run unminified. All other libraries (Retrofit, kotlinx-serialization, Room, Hilt, OkHttp, the rest of Glance) ship sufficient consumer rules.
- `android:allowBackup="false"` in the manifest. Auto Backup and device-to-device transfer are both disabled to keep `EncryptedSharedPreferences` credentials (key bound to the device Keystore) from ending up in a backup that can't be restored. Local state is recoverable by re-syncing from Nextcloud; only per-track color/emoji overrides (`track_prefs`) would be lost on reinstall.
- F-Droid builds from source and — because reproducible builds are enabled (see below) — ships the APK signed with **our** key rather than theirs. Local release APKs need to be signed (e.g. with the debug keystore via `apksigner`) only for sideload testing.

## F-Droid publishing

Submitted for inclusion on 2026-07-23 via merge request [fdroiddata!43688](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/43688), which closes [rfp#3903](https://gitlab.com/fdroid/rfp/-/issues/3903). The recipe lives in F-Droid's own `fdroiddata` repo at `metadata/com.martinhammer.tickdroid.yml`, **not** in this repo. Because it sets `AutoUpdateMode: Version` + `UpdateCheckMode: Tags`, F-Droid picks up new `v*` tags automatically — the recipe shouldn't need further edits for routine releases.

**Reproducible builds are enabled** (`Binaries` + `AllowedAPKSigningKeys` in the recipe), so the F-Droid build is verified byte-identical to our published APK and then signed with our certificate (SHA-256 `a398f829a84c52c9a8bbf2c1357f036b8e822b71ca86f56edebe36bda8ba1c3c`). That keeps one signature across GitHub, F-Droid, and — if it's ever added — Play, so users can move between sources without uninstalling. It is also effectively a one-way door: had we let F-Droid sign with their key, switching later would break updates for existing users.

Two constraints follow, and both are easy to trip over months from now:

- **Never re-enable `dependenciesInfo.includeInApk`.** AGP embeds a Google-key-encrypted dependency-metadata blob in the APK signing block by default. F-Droid's `check apk` scanner rejects it outright (`ERROR Found extra signing block 'Dependency metadata'`) and it is non-reproducible anyway. `includeInBundle = true` is deliberately kept so Play would still get dependency insights — the two flags apply to different artifacts (`assembleRelease` vs `bundleRelease`) and never interact.
- **Every release must stay byte-reproducible and be published at the `Binaries` URL**, i.e. a `tickdroid-<versionName>.apk` asset attached to the `v<versionName>` GitHub release. F-Droid rebuilds from the tag and compares against exactly that file; if it diverges, the release can't ship with our signature. Also keep signing with the same keystore — a different cert breaks `AllowedAPKSigningKeys`.

To sanity-check a release APK before publishing, enumerate its APK Signing Block IDs: expect only `0x7109871a` (v2 signature) and `0x42726577` (verity padding), and **never** `0x504b4453` (dependency metadata). Verifying content reproducibility is a `unzip -v` CRC comparison against F-Droid's from-source build — v2-only signing adds no zip entries, so every entry's CRC must match.

## Toolchain upgrade lessons (July 2026)

The versions below are those of the July upgrade, not the current ones (see
`gradle/libs.versions.toml`); the lessons apply to the next coordinated bump.

- **Toolchain upgrade (Kotlin / KSP / Hilt / AGP / Gradle) — completed 2026-07.** The coordinated AGP 9 wall was climbed in one jump. Final versions: **AGP 9.2.1, Gradle 9.4.1, Kotlin 2.3.20, KSP 2.3.9 (KSP2), Hilt 2.60, Room 2.8.4, kotlinx-serialization 1.11.0, androidx.core 1.19.0, androidx.lifecycle 2.11.0, androidx.hilt 1.4.0, compileSdk 37** (targetSdk stayed 36 — no runtime-behavior opt-in), JDK 17 (unchanged; AGP 9.2 needs 17). CI green (unit tests + lint + debug build). How it fit together, for whoever does the next jump:
  - **The two "walls" were actually one.** AGP 9 refuses to run the KSP1 backend (`KSP1 … no longer compatible with Android Gradle Plugin 9.0.0`), so AGP 9 *forces* KSP2 — and KSP2 was the thing that crashed this project. There was no AGP-9-on-KSP1 intermediate.
  - **The linchpin was Room, not KSP.** The KSP2 `unexpected jvm signature V` crash (google/ksp#2177) is triggered by Room 2.6's generated code for `suspend` DAO methods returning `Unit`; it was fixed in **Room 2.7.0-alpha11+**. Bumping Room to 2.8.4 is what made KSP2 viable. Room 2.8 also needs serialization ≥ 1.9 for its schema `$$serializer` (we're on 1.11.0), and serialization 1.11 needs Kotlin 2.3 — so the whole chain is `AGP 9 → KSP2 → Room 2.7+ → serialization 1.11 → Kotlin 2.3`.
  - **AGP 9 built-in Kotlin.** AGP 9 applies the Kotlin plugin itself, so `org.jetbrains.kotlin.android` was **removed** (from the version catalog `[plugins]` and both build files). We *adopted* built-in Kotlin rather than opting out (`android.builtInKotlin=false` + `android.newDsl=false` would have kept the old plugin, but that path dies in AGP 10). `kotlin.plugin.compose`, `kotlin.plugin.serialization`, and `ksp` stay in the `plugins {}` block; the Kotlin version is now resolved from those plugin versions (KGP floor is 2.2.10 with built-in Kotlin). The `kotlin { compilerOptions { jvmTarget = JVM_11 } }` block is unchanged and still valid. `ksp.useKSP2=false` was removed from `gradle.properties` (KSP 2.3.x is KSP2-only).
  - **Dagger 2.60 needs `error_prone_annotations` on the compile classpath.** Its generated code references `@CanIgnoreReturnValue`, but Dagger no longer brings the annotation transitively — added `compileOnly(libs.errorprone.annotations)` (CLASS retention, stays out of the APK). Distinct from the pre-existing `-dontwarn com.google.errorprone.annotations.**` R8 rule, which is a *runtime/shrink* concern.
  - **Room schema unchanged.** The 2.6 → 2.8 bump re-exported `app/schemas/` byte-identically (still v2) — no migration needed.
  - **`renovate.json` after the jump:** the five version caps (AGP, gradle-wrapper, core, lifecycle, room) are gone. Kotlin/KSP/Hilt are still grouped behind dashboard approval as **"Kotlin / KSP / Hilt (coordinated)"** — the wall is crossed, but the axis stays coupled (KSP locked to Kotlin, AGP built-in Kotlin resolves KGP from plugin versions, Hilt metadata parser, plus the Room↔serialization and Dagger↔errorprone couplings above), so the *next* Kotlin bump should still be done deliberately as one PR.
