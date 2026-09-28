# Porting conventions

For anyone — human or agent — writing Kotlin against the iOS source in
`E:\software\piru\piru`. Read this before adding a screen.

## The shape of the port

```
:core:model     pure JVM   value types (DoseUnit, DoseRange, Oklch, P3Color, Substance…)
:core:engine    pure JVM   the maths (PK, PD, tolerance, adherence, vitals, interactions)
:core:substance pure JVM   the read-only SQL over the 18 MB catalog
:core:data      Android    Room + the user's own store
:app            Android    Compose
```

`:core:engine` having no Android dependency is load-bearing: it is what lets the
curve maths be tested in milliseconds. Nothing platform-specific goes in it.

## Non-negotiables

- **Never apply `org.jetbrains.kotlin.android`.** AGP 9 ships built-in Kotlin
  support and rejects the plugin outright. `kotlin { compilerOptions { … } }` at
  the top level is the working form.
- **`String.format` always takes `Locale.ROOT`.** Kotlin's formatting is
  locale-sensitive and Swift's `String(format:)` is not, so a Turkish device
  would otherwise render `1,5` where the model computed `1.5`.
- **Kotlin `enum` `compareTo` is final.** Declaration order *is* the ordering
  semantics. Never reorder an existing enum's entries.
- **`java.time` everywhere, with a `ZoneId` parameter.** Never read the system
  default zone inside a calculation — the tests need to pin a zone, and the app
  has two day-boundary semantics that must not be confused (`SessionDay` with a
  configurable cut-off hour, and plain `startOfDay`).
- **Tests:** `:core:*` JVM modules use **JUnit 5 + kotest**. Instrumentation in
  `:core:data` uses **JUnit 4** through `androidx.test.ext:junit`, because
  AndroidJUnitRunner is a JUnit 4 runner. The two source sets do not share a
  framework and cannot.

## Build and test

```bash
export ANDROID_HOME='H:\Android\sdk' ANDROID_SDK_ROOT='H:\Android\sdk' GRADLE_USER_HOME='H:\gradle'
./gradlew :core:engine:test          # JVM suites
./gradlew :app:assembleDebug         # the APK
./gradlew :core:data:connectedDebugAndroidTest   # needs the emulator
```

A Gradle file-lock timeout means another build is running. Wait thirty seconds
and retry once.

## Comments

The port's house style, and it is not optional:

- A file starts with KDoc naming **the Swift file it ports** and the line count
  where that helps.
- Comments explain **why**, never what. `// increment the counter` is noise; a
  comment saying why the counter is not clamped is the reason the file is
  readable in six months.
- `## Heading` sections inside a KDoc block for anything with more than one
  non-obvious decision.
- **No emoji.**
- Where a rule was derived from a source, say which source and what the source
  actually measured. "CDC gives 2.4 MME per mcg/hr for transdermal fentanyl — a
  rate, not a mass" is the register.

## Copy rules, inherited from the iOS project's CLAUDE.md

These are product requirements, not style preferences:

- **The words "harm reduction" never appear in consumer-facing copy.**
- Assume the user knows what they are doing. No scolding, no congratulation for
  taking a substance, no streak-shaming.
- Keep **"Not medical advice"** where the iOS original has it, and keep it
  visible.
- Keep the phrasings the original uses deliberately: *"a dose is not a
  confession"*, *"it's not a question of goodness — it's a question of dose"*.
- A model output is labelled as a model output. Predicted is not measured, and
  the copy says so.
- An empty state is information, not an error. Say what would fill it.

## Screens

- A screen takes `navigator: AppNavigator` (when it pushes) and
  `modifier: Modifier = Modifier`.
- List screens are a `LazyColumn` with
  `contentPadding = PaddingValues(bottom = FAB_CLEARANCE)` — the log button
  floats above every tab and would otherwise cover the last row.
- Cards come from `ui/components/PiruCard.kt`. Colours come from
  `PiruTheme.colors` (`accent`, `secondaryLabel`, `tertiaryLabel`,
  `background`, `cardBackground`). There is no other palette.
- **A `@Composable` theme read cannot happen inside `Canvas { }`.** Hoist the
  colour to a `val` above the `Canvas` call.
- Charts are hand-drawn with `Canvas`. There is no Swift Charts equivalent in
  this build and none is being added.
- A screen that is not built yet says so and **names what it needs**. It does
  not render a stub that looks tappable.

## Things that will bite

- **JDBC and Android SQLite disagree about correlated-subquery name resolution.**
  A query that runs in a JVM spec can fail on device with `no such column`. Any
  new SQL in `:core:substance` needs a smoke test in
  `core/data/src/androidTest/.../CatalogOnDeviceTest.kt` as well.
- **The bundled SQLite driver is used deliberately** — the framework's
  `rawQuery` binds parameters as text, which is silently wrong against an
  expression.
- **`BuildConfig` is off by default** since AGP 8 and is enabled in `:app`.
- A `data class` cannot normalise in a `val` primary-constructor parameter. The
  `Oklch` type solves this with a single `of()` factory — copy that pattern
  rather than fighting it.
