# Localizing the UI

The app ships English and **Simplified Chinese**. Upstream ships 3,212 strings in
English, `zh-Hans`, `zh-Hant` and Spanish; this port takes the first two.

## The pipeline

1. **Extract.** Every user-facing literal moves into `app/src/main/res/values/strings_<area>.xml`.
   One file per area, so two people can work at once without touching the same file.
2. **Generate the Chinese.** `python tools/zh_from_xcstrings.py <english> <chinese>`
   looks each English value up in upstream's `Piru/Localizable.xcstrings` and writes
   the `zh-Hans` beside it. Most of the copy came across unchanged, so most of the
   translation already exists.
3. **Translate what is left.** Anything the script reports as `MISSING` is copy this
   port wrote itself — hub blurbs, empty states, the honest degradation notes. Those
   need writing, not looking up.

## The rule that matters: a sentence is one string

This is the whole reason extraction is not a `sed`.

**Kotlin:**

```kotlin
// WRONG — three resources a translator has to reassemble, and Chinese word
// order does not match English, so they cannot.
Text(
    "Piru scales every dose model by this — how fast a dose is " +
        "cleared, and how concentrated it is while it is there."
)
```

```xml
<!-- RIGHT — one resource, one sentence, one translation -->
<string name="body_weight_explanation">Piru scales every dose model by this — how fast a dose is cleared, and how concentrated it is while it is there.</string>
```

```kotlin
Text(stringResource(R.string.body_weight_explanation))
```

A literal that is genuinely two sentences still belongs in one resource. A
`+` between two halves of one sentence is the thing to remove; a `+` between two
independent rows is usually two resources.

## Format specifiers are positional

Upstream's Swift is `%@` and `%lld`. Android needs `%1$s` and `%1$d` — **with the
index**, because Chinese reorders arguments and an unindexed `%s` silently fills
the wrong slot:

```xml
<string name="med_reminder_body">Time to log %1$s — %2$s.</string>
```

## Two things that will not compile

- **`stringResource` is a `@Composable` read.** It cannot go inside `Canvas { }`,
  `drawBehind { }`, or any non-composable lambda. Hoist it to a `val` above, or use
  `LocalContext.current.getString(R.string.…)` where there is no composition.
- **`text = "literal"` in a `Text` is fine; `label = { Text("…") }` needs the same
  treatment.** Parameter defaults that are strings (`String = "Oral"`) have to
  become nullable and resolve at the call site.

## What not to translate

- **Substance names, aliases, brand names, salt and ester names.** They are data,
  they come from the catalog, and the catalog has its own `localized_names` table.
- **Route names that are identifiers** (`oral`, `insufflation`) — those are wire
  values. The *labels* shown for them are translated.
- **`Not medical advice.`** — keep it in English in the Chinese build too. It is
  the sentence that has to be legible to someone who cannot read the rest.
- Code, log messages, and anything in a `require`/`error` message.

## The register

Match upstream's `zh-Hans`, which is already in the catalogue and readable in
`Piru/Localizable.xcstrings`. Two things to hold on to:

- Direct, not clinical. The Chinese upstream uses 你可能/你的, not 患者/用户.
- No scolding and no congratulating. The tone rules apply in every language.

## Doing it

```bash
python tools/zh_from_xcstrings.py \
    app/src/main/res/values/strings_journal.xml \
    app/src/main/res/values-zh/strings_journal.xml
```

Entries it could not find are written with `translatable="false"`, which is
Android's way of saying "fall back to English". That is a marker, not a finished
state: replace each with a real translation and drop the attribute.

**It overwrites the target file**, and it cannot tell your translation from its
own. Re-running it on a file that has been hand-edited will silently revert those
edits. This has already happened once: a severity field labelled `Note` came back
as 备注 from an unrelated upstream `Note`, was corrected by hand, and a
regeneration put it back. The fix is procedural, not technical — **run the tool
once, then edit**, and treat a later re-run as a fresh review rather than a
refresh.

Two classes of entry are announced as `CHECK` for that reason:

- **single-word matches** — exact, and still a guess. One word is the unit of
  language most likely to mean something else on another screen.
- **case-folded matches** — nearly always the same sentence, but the net caught
  an unrelated one when it was wider, so it now needs at least three words.

## It is a labour-saver, not an authority

The lookup is exact-string, so it will happily hand you upstream's translation of
a word that means something else here. Three real ones from this port:

| English | Upstream's sense | This port's sense | Upstream's Chinese |
| --- | --- | --- | --- |
| `Level` | steady, not rising | a number field | 平稳 |
| `Flat` | unremarkable | a flat lab trend | 平淡 |
| `Peak` | the peak window | a peak amount | 高峰期 |

All three are wrong here and all three look plausible. A key is a *string*, and
the same string is a different sentence in a different screen — so **read what it
found before accepting it**. The same applies to a string whose sense depends on
where it sits: `Common` is a dose tier upstream and a substance family here.

The tool cannot check this and no amount of pattern matching would. It saves the
lookup, not the judgement.

## What it will refuse to emit

Two guards, both there because the failure is a runtime crash rather than a
build error:

- **A surviving Swift specifier.** Upstream's translations are often already
  positional — `%2$@ 中的 %1$@` is a translator having moved the arguments — and
  those numbers are the mapping. The tool converts the *type* and keeps the
  *number*, because renumbering by order of appearance silently puts the wrong
  value in the wrong slot. If any `%@` or `%lld` would survive, the entry comes
  back MISSING instead.
- **Escaping.** aapt2 decodes entities *first* and then rejects a bare `'`, so
  `&#39;` does not work and only `\'` does. Both directions are handled.

## Dates need two things, and getting one right is worse than neither

A date is localized by the **pattern** (field order) and by the **locale** (month
and weekday names). Fixing one without the other produces a translation that
looks wrong rather than one that reads as English.

**The pattern belongs in a resource.** `ofPattern("EEEE, d MMMM")` renders
`Monday, 28 September` — correct English, and in Chinese `星期一, 28 九月`, which
is not how anyone writes a date. The order is fixed by the pattern, so no
translation of a surrounding string can fix it. Extract the pattern, and give the
Chinese file `M月d日 EEEE`.

**The locale is not `Locale.getDefault()`.** This was wrong here for a while, and
it is the subtler half. `Locale.getDefault()` is the *device's* locale, and this
app ships two languages. A German phone gets **English screens** — `values/` is
the fallback — but `Locale.getDefault()` hands the formatter German, so
`28. September` lands inside an otherwise English screen. `Locale.ROOT` is worse
in the other direction: it pins month names to English on a Chinese device.

The right locale is **the one the app's own resources resolved to** — whatever
language the strings came out in is the language the dates come out in. That is
`appLocale()` in `ui/labels/`, read from the resolved configuration, and it is
what every date site passes to `ofPattern`.

`ofLocalizedDate(...)` sidesteps the pattern half but not the locale half: it
still takes the device locale by default.

`"HH:mm"` sites are left as literals — a 24-hour clock reads the same in both
languages, and both places that matter here are 24-hour.

## The two vocabularies that cannot be resources

`RouteOfAdministration`, `SubstanceCategory`, `InteractionSeverity` — and their
siblings `ReceptorClass`, `Combination.Severity`, `BindingAction`,
`BindingAffinity` — live in `:core:`, which is plain JVM with no Android
dependency on purpose. A resource id is an Android concept, so those types carry
the **English** label and their KDocs say the localized one lives with the app's
resources. That place is **`ui/labels/CoreLabels.kt`**, and it is the only thing
that should read those labels. A screen reaching for `route.displayName` shows
English to a Chinese reader.

## Verifying

```bash
./gradlew :app:compileDebugKotlin
```

Then change the device language and look at the screen. A missing translation
falls back to English silently, so `grep -c 'translatable="false"'` on your
Chinese file is the check that catches what a build cannot.
