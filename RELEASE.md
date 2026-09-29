# Releasing Piru for Android

The point of this file is one line that keeps getting skipped: **install the build
on a device and walk the app**. 0.3.0 shipped a crash that passed compilation, R8,
the unit suites and an adversarial review, because none of those run the
application. Every check below exists because something got past the ones above
it.

Run the steps in order. A step that cannot be completed is a release blocker, not
a note.

## 0. Version

`app/build.gradle.kts`:

```kotlin
versionCode = 4           // strictly increasing, every published build
versionName = "0.3.1"     // what the About screen and the export name read
```

The About section reads `BuildConfig`, not a hand-written string, so this is the
only place either number lives. Bump `versionCode` for **every** published APK,
including a re-cut of the same `versionName` — Android refuses to install an equal
code over the top, which turns an otherwise correct update into "app not
installed".

## 1. Build clean, then build both variants

```bash
export ANDROID_HOME='H:\Android\sdk' ANDROID_SDK_ROOT='H:\Android\sdk' GRADLE_USER_HOME='H:\gradle'
cd piru-android

./gradlew clean
./gradlew :app:assembleDebug :app:assembleRelease
```

`assembleRelease` needs `keystore.properties` at the repo root (gitignored; the
file's own comment in `app/build.gradle.kts` says how to make the key). Without
it the release APK is produced **unsigned and uninstallable** — the failure is a
long way from its cause, so check that the keystore file is present before
blaming the build.

Release is `isMinifyEnabled = true` with `app/proguard-rules.pro`. That is the
half of this build that no test covers: R8 rewrites reflection and serialization
paths, and Room, kotlinx.serialization and Compose are all reached that way.

## 2. Tests

```bash
./gradlew :core:engine:test :core:substance:test :core:model:test
./gradlew :core:data:testDebugUnitTest :app:testDebugUnitTest
./gradlew :core:data:connectedDebugAndroidTest     # needs a device
```

A `TEST-*.xml` with `tests="0"` is not a pass. Neither is a `TEST-*.xml` written
by an emulator run that was never collected — check the timestamps.

## 3. Walk the release build on a device

**This is the step that is not optional.** It is also the step that needs a
decision made *before* it can run: the emulator usually carries a debug-signed
install, and installing a release APK over it requires `adb uninstall`, which
erases the store. Decide first which of these you are doing:

- **Authorised wipe** of a scratch emulator, or
- **a second AVD** kept for release checks, so the dev device's seeded journal
  survives.

```bash
adb install -r -d app/build/outputs/apk/release/app-release.apk
adb shell am start -n glass.kagerou.piru/.MainActivity
```

Then, on the device, in this order:

1. **Launch.** The journal renders; nothing is blank.
2. **Log a dose** through quick log and see it appear in the journal.
3. **The four cursor screens** — journal graph, session detail, tolerance tool,
   body load — drag the rule horizontally on each. The readout updates, and no
   label is drawn over another one.
4. **A day with duration-less substances** (no curve, so the graph draws marker
   lanes). The lane names sit under the clock axis, not on it.
5. **Insights and tools** open and draw. These are the screens with the most
   `Canvas` code and the least test coverage.
6. **Export** a Piru file. **Import** it back. The count is "nothing new" the
   second time.
7. **Settings** opens each subsection: substance colours, health data,
   notifications, data and backup, About.
8. **Health data** states its availability honestly on this device (connected,
   partly connected, or not installed).
9. **Onboarding** on a fresh install: every step advances, and the import step
   imports a real export.

Debug-only shortcuts are off in release (the screenshot tour, any debug menu), so
a step that worked in debug and not here is usually a missing ProGuard rule rather
than a product bug — but confirm which before shipping.

## 4. Crash log, before anything is tagged

```bash
adb logcat -d -b crash
```

Read it, do not grep it for the package name. A crash in a system component or a
provider still means this build crashed. `adb logcat -d -b crash` on a device that
has not run the app since boot is empty, which is not evidence of anything — run
steps 3 through 9 first, then read.

## 5. Package

```bash
cp app/build/outputs/apk/release/app-release.apk \
   dist/piru-<versionName>-zh.apk
```

`dist/` keeps every shipped APK. The `zh` suffix is the locale set this build
carries (English + Simplified Chinese); when the catalogue grows, the suffix is
what says which languages a given file has.

Write `dist/release-notes-<versionName>.md` before tagging, from the diff — see
`release-notes-0.3.1.md` for the shape: what changed, what is known missing, and
anything a user has to do (a migration, a re-import).

## 6. Tag, then publish

```bash
git tag v<versionName>          # v0.3.1, the same string as versionName
git push origin v<versionName>
```

Tag **after** the device walk and **before** the GitHub release, so the tag and
the artifact are the same build. Publish the APK from `dist/` as a GitHub
**pre-release**, with the release notes as the body.

## What this checklist does not cover yet

Named rather than implied, because a checklist nobody trusts is worse than none:

- **No automated device smoke run.** Every step in §3 is done by hand. The
  intended replacement is a screen-by-screen smoke pass (launch → one dose → the
  four cursor screens → export → import → settings) driven by
  `adb` + `uiautomator dump` + `input tap` + `screencap`, or Compose screenshot
  tests (Roborazzi/Paparazzi) which run on the JVM and can go in CI.
- **No CI.** There is no `.github/` in this repository, so §1, §2 and the style
  tools are all local steps a person has to remember. The iOS side pins
  swiftformat/swiftlint/ruff in CI; the equivalent here is ktlint/detekt, which
  are not wired up.
- **No screenshot or multi-language matrix.** The iOS pipeline walks every screen
  in every language and skin (`pipeline/screenshots.py`). Nothing here does.
- **Skins are not checked**, because there is one.
