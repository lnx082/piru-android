# `TestBuiltDatabaseInvariants`: what was not ported, and why

Of the 60 tests in `pipeline/build/tests/test_sqlite.py`'s
`TestBuiltDatabaseInvariants`, **52 are ported** to
`src/test/kotlin/.../BuiltDatabaseInvariantsTest.kt` and pass against the real
18 MB database. The 8 below are not ported, each for one of three reasons.

## The boundary

**These specs read only the shipped database.** `:core:substance` is the
consumer side of the build — it opens the published artifact and must be
correct about what is in it. A test that needs `data/curated/**`,
`data/sources/**`, `data/enrichment/**` or `pipeline/**` is a *build*
validation: it can only run on a checkout that has the build inputs, and the
pipeline's own Python suite already runs it there, on the machine where those
inputs exist.

That line is deliberate. If these specs reached into the corpus, the Android
test suite would stop working on a fetch-only checkout — which is exactly the
checkout a fresh clone gets.

## Not ported

### Reads build inputs

| Test | Input it needs |
|---|---|
| `test_every_enrichment_file_parses` | `data/enrichment/**` |
| `test_curated_display_names_resolve` | `data/curated/substances/*.json` (740 files) |
| `test_curated_categories_resolve_and_are_valid_enums` | `data/curated/substances/*.json` |
| `test_popularity_from_wikipedia_snapshot` | `data/sources/wikipedia-popularity.json` |

The two curated-corpus tests are the ones worth revisiting if the boundary is
ever redrawn — they catch a valuable class of rot (a curated override pointing
at a canonical that has since been renamed or merged). They need the corpus,
`_resolve_sid` (already ported) and `is_chemistry_noise` (below).

### Needs pipeline code

| Test | Function it needs |
|---|---|
| `test_no_chemistry_noise_substance_names` | `is_chemistry_noise()` — `pipeline/build/sqlite.py:4516` |
| `test_no_chemnoise_aliases_survive` | `is_chemnoise_alias()` — `pipeline/build/sqlite.py:4348` |

Both read the database, so the boundary above does not exclude them. What
excludes them is that the predicate is **build logic**: a set of regexes that
classify a name as a chemistry artefact. Copying it into Kotlin would put a
second implementation of a build rule inside the app's test suite, and the two
would drift silently the first time upstream tunes a pattern — at which point
the test would be asserting against a stale definition of "noise" while still
reporting green.

If a future checkout needs them, port the regexes *and* record the upstream line
numbers above so the copy can be diffed when they change.

### Needs a library with no equivalent here

| Test | Dependency |
|---|---|
| `test_every_shipped_smiles_parses` | RDKit |

It validates every shipped SMILES by parsing it with a cheminformatics toolkit.
There is no Kotlin equivalent, and a hand-rolled SMILES parser would be a weaker
check wearing the same name. The `molecule_shapes` table is generated offline by
the same toolkit, so the build is where this belongs.

### Also not ported

`test_subfxonex_ontology_ships_whole` asserts the *shipped ontology table* holds
the complete vocabulary the build started from — a property of how the table is
generated, with no runtime consequence for a consumer that only reads it.

## Verification

```bash
./gradlew :core:substance:test
```

Every ported test skips rather than fails when `db/piru-substances.sqlite` has
not been fetched (`db/fetch-db.sh`), matching the upstream suite's `SkipTest`.
A run reporting **0 tests** means the build failed or the database is missing —
check the Gradle exit status before reading the result, since stale XML from a
previous run is otherwise easy to mistake for a fresh one.
