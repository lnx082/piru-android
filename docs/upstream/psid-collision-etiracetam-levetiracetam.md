> Ready to paste into the repo's **Bug report** template
> (`.github/ISSUE_TEMPLATE/bug_report.md`). Not a *Data correction* — that
> template is for a wrong dose/duration/half-life/interaction/mechanism value
> and requires a citable source; this is an internal identity defect with no
> external source to cite.

---

**Title**

```
Duplicate PSID: Etiracetam and Levetiracetam both resolve to P1-HPHUVLMMVZITSG-0-0-0-G
```

---

## Summary

Two distinct substances in the bundled database — Etiracetam and
Levetiracetam — carry the same `substance_forms.psid`
(`P1-HPHUVLMMVZITSG-0-0-0-G`) and the same `substance_uid`. Levetiracetam is
also the only entry in `isomer-families.json` whose variant was **not** folded
into its parent: 12 of the 13 registered variants are folded, this one is not.
A PSID is meant to identify one form unambiguously, and `substance_uid` is what
inventory buckets doses by, so a logged Levetiracetam and a logged Etiracetam
are currently indistinguishable from each other.

## Environment

Not device-dependent — reproduced by querying the built database directly, so
the meaningful environment is the artifact, not the phone.

- **Piru**: database `content_version 2026-09-26.0`, `schema_version 6`
- **iOS**: n/a — this is a shipped-artifact defect, not a runtime one
- **Device**: n/a — reproduced with `sqlite3` against `piru-substances.sqlite`
- **Depth mode**: n/a

(Found while writing a second consumer of the database for an Android port. The
reproduction below needs nothing from either app.)

## Steps to reproduce

1. Fetch the shipped database (`pipeline/fetch-db.sh`).

2. Look for a PSID that appears more than once:

   ```sql
   SELECT psid, count(*) FROM substance_forms GROUP BY psid HAVING count(*) > 1;
   ```

   → `P1-HPHUVLMMVZITSG-0-0-0-G`, 2 rows.

3. Inspect them:

   ```sql
   SELECT s.id, s.canonical_name, sf.substance_uid, sf.stereo, sf.salt, sf.release, sf.is_default
   FROM substance_forms sf JOIN substances s ON s.id = sf.substance_id
   WHERE sf.psid = 'P1-HPHUVLMMVZITSG-0-0-0-G';
   ```

   | id | canonical_name | substance_uid | stereo | salt | release | is_default |
   |---|---|---|---|---|---|---|
   | 297 | Etiracetam | `HPHUVLMMVZITSG` | `0` | `0` | `0` | 1 |
   | 424 | Levetiracetam | `HPHUVLMMVZITSG` | `0` | `0` | `0` | 1 |

   (Levetiracetam's second form row, `release = 'XR'` → `P1-…-0-0-XR-P`, is
   distinct and not part of this report.)

4. Compare against every other registered stereoisomer variant:

   ```sql
   -- parent/variant pairs come from data/curated/isomer-families.json
   SELECT count(*) FROM substances WHERE canonical_name = 'Escitalopram';
   SELECT count(*) FROM aliases a JOIN substances s ON s.id = a.substance_id
   WHERE s.canonical_name = 'Citalopram' AND lower(a.alias) = 'escitalopram';
   ```

## Expected behavior

Levetiracetam should be handled like the other 12 variants — folded into
Etiracetam as its `S` isomer, surviving as a searchable alias with
isomer-tagged dose rows where it carried its own ladder — or, if it is
deliberately kept standalone, it should carry `stereo = 'S'` so its PSID
differs from its parent's.

## Actual behavior

Every folded variant, and the one that is not:

| parent | variant | isomer | standalone row? | parent alias? |
|---|---|---|---|---|
| Ketamine | Esketamine | S | no | yes |
| Ketamine | Arketamine | R | no | yes |
| MDMA | S-(+)-MDMA | S | no | yes |
| MDMA | R-(-)-MDMA | R | no | yes |
| Methamphetamine | D-methamphetamine | D | no | yes |
| Methamphetamine | L-methamphetamine | L | no | yes |
| Amphetamine | Dextroamphetamine | D | no | yes |
| Methylphenidate | Dexmethylphenidate | D | no | yes |
| Modafinil | Armodafinil | R | no | yes |
| Citalopram | Escitalopram | S | no | yes |
| Zopiclone | Eszopiclone | S | no | yes |
| Milnacipran | Levomilnacipran | L | no | yes |
| **Etiracetam** | **Levetiracetam** | **S** | **yes** | **no** |

12 of 13 are folded. Levetiracetam is the only exception, and because its
`substance_forms` row keeps `stereo = '0'` it collides with its parent's PSID.

Three further things point the same way:

**`isomer-families.json` already declares the relationship** — the same registry
that drives the folds that did happen says the isomer is `S`, while the built
row stores `0`.

**The collision registry has no entry for the pair.**
`inchikey-collisions.json` lists 16 `distinct` groups; Etiracetam/Levetiracetam
is not among them, so the PSID FAMILY assignment stage has nothing telling it to
intervene either.

**The invariant suite does not assert PSID uniqueness.**
`TestBuiltDatabaseInvariants.test_every_substance_form_psid_is_check_valid`
checks that each PSID parses, that its check character verifies, and that the
parsed facets equal the stored `stereo`/`salt`/`release` — all three hold for
both rows. `test_stereoisomers_folded_into_parent` covers four families and this
is not one of them. The PK `(substance_id, stereo, salt, release)` permits the
duplicate, so no constraint fires either.

**Downstream effect.** `matchKey(for:)` in `Data/Services/InventoryService.swift`
is `entry.substanceUID ?? matchKey(for: entry.substance)`, so inventory buckets
doses by `substance_uid`. With the uid shared, a logged Levetiracetam and a
logged Etiracetam are counted as one stock item.

## Session

n/a — no session is involved; this is reproducible from the database alone.

---

## Note before implementing

The candidate fixes mint *different* PSIDs, and a PSID is meant to be derived
once and pinned — so the choice changes identity for anything already logged.
I did not want to pick for you. Happy to open a PR for whichever you choose,
including the uniqueness assertion that would have caught this and an extension
of `test_stereoisomers_folded_into_parent` to cover all of
`isomer-families.json` rather than a hand-listed four.
