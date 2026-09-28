# Piru for Android

A Kotlin and Jetpack Compose port of [Piru](https://github.com/kageroumado/piru) —
an iOS dose journal and reference app.

**This is a rewrite, not a conversion.** SwiftUI and SwiftData have no Android
counterparts, so the views and the persistence layer are new. What carries over
unchanged is the substance catalog (an 18 MB read-only SQLite database), the
tone, and the model documentation the original ships alongside its code.

## Status

Mid-port, and honest about it. Working: the journal and its timeline, quick
logging, sessions and notes, the substance library, every tool, the insights
pages, reminders, and Health Connect.

Not in this build, each for a stated reason rather than an oversight:

- **Skins and the skin shop** — the alternate skins are not ported, and there is
  no billing.
- **Live Activity** — no Android equivalent; the app uses the persistent
  notification it does have.
- **Identify** — upstream matches a photographed pill against a catalog. That
  needs camera preview and on-device text/barcode decoding, so the screen says
  what it needs and offers the search that does work.
- **PDF reports** — the summary screens are here; the export is not.
- **Widgets** — Glance widgets are planned, not built.
- **Localization** — upstream carries 3,186 strings in English, Simplified and
  Traditional Chinese. This build is English only until there is a localization
  layer for it to be one of many in, rather than three screens in a Chinese
  language app.

## Building

Requirements: JDK 17, and an Android SDK with platform 37 and build-tools 37.

Gradle finds the SDK through `local.properties` (gitignored, and the file
`android-studio` or the Gradle plugin will write for you):

```properties
sdk.dir=/path/to/Android/sdk
```

Then:

```bash
./gradlew :app:assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug         # onto a connected device or emulator
```

The catalog is not in the repository — it is 18 MB and fetched:

```bash
cd db && ./fetch-db.sh              # verifies against db/manifest.json's sha256
```

### Tests

```bash
./gradlew :core:engine:test :core:substance:test :core:model:test
./gradlew :core:data:testDebugUnitTest :app:testDebugUnitTest
./gradlew :core:data:connectedDebugAndroidTest     # needs a device
```

The `:core:` modules are plain JVM libraries with no Android dependency, on
purpose: it is what lets the pharmacology be tested in milliseconds rather than
on a device. The instrumentation specs run against real SQLite, because several
of the bugs this port has hit were engine differences that a JVM shadow cannot
reproduce.

## Layout

```
core/model      pure JVM   value types — doses, units, colours, the Oklch palette
core/engine     pure JVM   the maths — PK, PD, tolerance, adherence, vitals
core/substance  pure JVM   the read-only SQL over the catalog
core/data       Android    Room, and the user's own store
app             Android    Compose
```

## Licence

**GPLv3.** See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).

Piru's name and mascot are the original author's, and the data is assembled from
sources that carry their own terms — all recorded in [NOTICE.md](NOTICE.md).

**Not medical advice.** Piru is a record and a reference. It does not diagnose,
it does not recommend a dose, and its models are estimates.
