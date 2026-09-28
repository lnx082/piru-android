# Notice and attribution

## What this is

**Piru for Android** — a Kotlin and Jetpack Compose port of [Piru](https://github.com/kageroumado/piru),
an iOS dose journal and reference app.

It is a **derivative work**, not an independent one, and it is distributed under
the same licence. See [LICENSE](LICENSE).

## The original

The name, the mascot, the design, the data pipeline and the pharmacology model
are not mine:

> **Piru** (ピル) is Japanese for *pill*, hence the smiling capsule. The name and
> mascot come from the app's original author,
> [pharmacykitty](https://github.com/pharmacykitty); [@kageroumado](https://x.com/kageroumado)
> continues it as **rx no. 007** at [kagerou.glass](https://kagerou.glass).

This port exists because they made something worth carrying to another platform.
The tone rules it inherits — no scolding, no streaks to keep, "a dose is not a
confession" — are theirs and are deliberately kept.

## Licence

**GNU General Public License v3.0.** Full text in [LICENSE](LICENSE).

Because this is a derivative work of a GPLv3 program, it must stay under GPLv3.
That is not a restriction worth working around; it is the terms the original was
shared under.

## Bundled data

The substance catalog (`db/piru-substances.sqlite`, ~18 MB) is **fetched, never
committed** — `db/fetch-db.sh` downloads it and verifies it against
`db/manifest.json`'s SHA-256. It is a build artifact of the original project's
pipeline, and this repository carries the pipeline's output, not a re-derivation
of it.

That catalog draws on:

| Source | Licence |
|---|---|
| [substance.wiki](https://substance.wiki) | |
| ↳ its [SubFxOnEx](https://github.com/Di-lemma/SubFxOnEx) ontology | LGPL-2.1 |
| [PsychonautWiki](https://psychonautwiki.org) | CC BY-SA 4.0 |
| [FreeOD Wiki](https://freeodwiki.org) | CC BY-SA 4.0 |
| [dose.wiki](https://dose.wiki) | CC0 1.0 |
| [TripSit](https://tripsit.me) | |
| [DailyMed](https://dailymed.nlm.nih.gov) | |
| [PubChem](https://pubchem.ncbi.nlm.nih.gov) | |
| [Wikidata](https://www.wikidata.org) | CC0 1.0 |
| PiHKAL and TiHKAL (via Erowid) | reference text, Alexander and Ann Shulgin |
| PDSP Ki database | receptor affinity data |
| DEA Orange Book | U.S. controlled-substance schedules |

**Text from PsychonautWiki and FreeOD Wiki is used under CC BY-SA 4.0**, edited
and merged with other sources, and remains available under that licence.

Content outside those licences — quotations from published books, manufacturers'
label text, and figures from reference tables — belongs to its owners and is
shown for reference under their terms. Neither this port nor the original makes
any claim to the correctness of any source.

## Model parameters

The ester pharmacokinetics in the injection-levels and hormone-levels tools are
fitted from [estrannaise.js](https://github.com/WHSAH/estrannaise.js) **(MIT)**,
cross-checked against the primary literature named in each row's provenance note.

## Not medical advice

Piru is a record and a reference. It does not diagnose, it does not recommend a
dose, and its models are estimates. **服用注意 ・ Not medical advice.**
