> Ready to paste into the repo's **Bug report** template
> (`.github/ISSUE_TEMPLATE/bug_report.md`). Not a *Data correction* — that
> template is for a value that is wrong and wants a citable source; here there
> are two values and no external source can say which is right.

---

**Title**

```
dose_ranges and durations keep duplicate rows: UNIQUE (…) does not hold when salt_form/isomer are NULL
```

---

## Summary

Both `dose_ranges` and `durations` declare a uniqueness constraint that includes
the nullable `salt_form` and `isomer` columns. SQLite treats every NULL in a
unique index as distinct from every other NULL, so the constraint does not
prevent duplicates for the rows where those columns are NULL — which is almost
all of them. The duplicates are real and disagree: 4-Fluorophenylpiperazine
insufflation carries a drug.community ladder of both **20–35 mg and 20–40 mg**.

The consequence is that the resolved value for the affected rows is picked
arbitrarily. Every dose and duration resolver ranks with

```sql
ROW_NUMBER() OVER (PARTITION BY … ORDER BY <source priority>) AS rn
```

and when two rows tie on the priority there is nothing left to order by, so the
winner is whatever the scan happens to reach first. That is not stable across
SQLite builds or query plans.

## Environment

Not device-dependent — reproduced against the built database directly.

- **Piru**: database `content_version 2026-09-26.0`, `schema_version 6`
- **iOS**: n/a — this is a shipped-artifact defect, not a runtime one
- **Device**: n/a — reproduced with `sqlite3` against `piru-substances.sqlite`
- **Depth mode**: n/a

(Found while writing a second consumer of the database for an Android port.)

## Steps to reproduce

1. Fetch the shipped database (`pipeline/fetch-db.sh`).

2. Count groups that the constraint should have prevented:

   ```sql
   -- 39 groups
   SELECT count(*) FROM (
     SELECT 1 FROM dose_ranges
      GROUP BY substance_id, route, source_id, ifnull(salt_form,''), ifnull(isomer,'')
     HAVING count(*) > 1
   );

   -- 73 groups
   SELECT count(*) FROM (
     SELECT 1 FROM durations
      GROUP BY substance_id, route, phase, source_id, ifnull(salt_form,''), ifnull(isomer,'')
     HAVING count(*) > 1
   );
   ```

3. Look at one where the rows disagree:

   ```sql
   SELECT d.id, d.common_lower, d.common_upper FROM dose_ranges d
     JOIN substances s ON s.id = d.substance_id
     JOIN sources src ON src.id = d.source_id
    WHERE s.canonical_name = '4-Fluorophenylpiperazine'
      AND d.route = 'insufflation' AND src.slug = 'drug.community';
   ```

   | id | common_lower | common_upper |
   |---|---|---|
   | 1689 | 20 | 35 |
   | 1692 | 20 | 40 |

4. Confirm the tie reaches the resolvers. Of the 73 tied duration groups, **49
   have the *winning* source itself tied**, so the value a resolver returns for
   them is arbitrary:

   ```sql
   -- For Amphetamine insufflation, tripsit carries two afterglow rows:
   -- 60-1440 (id 1427) and 60-720 (id 1474). Which one a read returns
   -- depends on scan order.
   SELECT du.id, du.min_minutes, du.max_minutes FROM durations du
     JOIN substances s ON s.id = du.substance_id
     JOIN sources src ON src.id = du.source_id
    WHERE s.canonical_name = 'Amphetamine' AND du.route = 'insufflation'
      AND du.phase = 'afterglow' AND src.slug = 'tripsit';
   ```

## Expected behavior

One row per `(substance, route, source, phase, salt_form, isomer)` as the schema
reads like it intends — either because the constraint enforces it, or because
the build does not emit the duplicates in the first place.

## Actual behavior

The duplicates exist, and the resolvers pick among them without a tiebreaker, so
a substance's resolved duration or ladder can differ between two runs of the
same build.

Whether that is user-visible depends on the row: a doubled `total` changes the
drawn curve length, while two identical rows change nothing. Among the examples
above, Amphetamine's afterglow tie is 60–1440 against 60–720 — a midpoint of 750
against 390 minutes, which is the difference between a curve that ends in the
evening and one that ends overnight.

It also means an iOS device and an Android one can legitimately disagree on the
same substance, because they are not running the same SQLite.

## Possible directions

Not implemented — the right fix depends on why the duplicates are there, which I
can't tell from the artifact:

- **A partial unique index that treats NULL as a value**, e.g.
  `UNIQUE (substance_id, route, source_id, ifnull(salt_form,''), ifnull(isomer,''))`
  via a pair of partial indexes, or `salt_form TEXT NOT NULL DEFAULT ''`. Either
  would surface the duplicates as build failures instead of shipping them.
- **A data pass that resolves them**, if the duplicates are ingest artefacts and
  one row is authoritative.
- **A tiebreaker in the resolvers**, which makes the choice reproducible without
  deciding which row is right. This is what the Android port does — appended
  `, id` after the priority ordering, which cannot change which *source* wins,
  only which of several same-source rows is used.

The first two are yours to judge; the third is a mitigation, not a diagnosis.

## Session

n/a — no session is involved; this is reproducible from the database alone.
