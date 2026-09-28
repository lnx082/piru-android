> Ready to paste into the repo's **Bug report** template
> (`.github/ISSUE_TEMPLATE/bug_report.md`). Covers two defects in the same
> method; split into two issues if that reads better.

---

**Title**

```
attachAuxiliaryRoutes: the re-fold loses doseContext, and a duration-of-action-only route is never shown
```

---

## Summary

Two defects in the same method, both measured against the shipped catalog.

**1. The re-fold drops `doseContext`.** `attachAuxiliaryRoutes` rebuilds each
route's variants to attach protocol dosing and the release window, and the
`RouteVariant` it constructs does not pass `doseContext` — which defaults to
`.unknown`. Every route that goes through the re-fold therefore loses the regime
its ladder was tagged with. On the shipped catalog that is **20 routes across 18
substances**, and all 20 are on `medical_rx` compounds, which is one of the two
display classes whose card *shows* the regime label. Those cards lose a label
that exists precisely to stop a research-chem figure being read as a prescribed
one.

**2. A route with only a release window never appears.** The three steps of
`attachAuxiliaryRoutes` consult the windows for routes they are already building.
**Nine routes** carry a `durations_of_action` row and nothing else — no ladder,
no acute duration profile, no protocol — so none of the three steps builds them,
and their window never reaches the substance card. They are all long-acting
injectables: the aripiprazole, paliperidone, fluphenazine and risperidone
depots, plus liraglutide, semaglutide and dulaglutide.

## Environment

Not device-dependent — reproduced against the built database directly.

- **Piru**: database `content_version 2026-09-26.0`, `schema_version 6`
- **iOS**: n/a — this is a shipped-artifact defect, not a runtime one
- **Device**: n/a — reproduced with `sqlite3` against `piru-substances.sqlite`
- **Depth mode**: n/a

(Found while writing a second consumer of the database for an Android port.)

## Steps to reproduce

Let `winner` pick the resolved ladder per `(substance, route, salt, isomer)` the
way the resolvers do:

```sql
WITH winner AS (
  SELECT d.substance_id, d.route, d.dose_context,
         ROW_NUMBER() OVER (
           PARTITION BY d.substance_id, d.route, ifnull(d.salt_form,''), ifnull(d.isomer,'')
           ORDER BY (d.dose_context = 'therapeutic') ASC, src.default_priority ASC, d.id) AS rn
    FROM dose_ranges d JOIN sources src ON src.id = d.source_id
)
SELECT s.display_class, count(DISTINCT s.canonical_name) AS substances, count(*) AS routes
  FROM winner w JOIN substances s ON s.id = w.substance_id
 WHERE w.rn = 1
   AND ((w.substance_id, w.route) IN (SELECT p.substance_id, p.route FROM protocol_dosing p)
     OR (w.substance_id, w.route) IN (SELECT a.substance_id, a.route FROM durations_of_action a))
 GROUP BY s.display_class;
```

```sql
-- Winner routes whose context is silently reset, by display class:
--   medical_rx   | 18 substances | 20 routes
--   recreational |  2 substances |  2 routes
```

For the second defect:

```sql
SELECT s.canonical_name, a.route FROM durations_of_action a
  JOIN substances s ON s.id = a.substance_id
 WHERE NOT EXISTS (SELECT 1 FROM dose_ranges d
                    WHERE d.substance_id = a.substance_id AND d.route = a.route)
   AND NOT EXISTS (SELECT 1 FROM durations du
                    WHERE du.substance_id = a.substance_id AND du.route = a.route)
   AND NOT EXISTS (SELECT 1 FROM protocol_dosing p
                    WHERE p.substance_id = a.substance_id AND p.route = a.route);
```

| substance | route |
|---|---|
| Aripiprazole | intramuscular |
| Dulaglutide | subcutaneous |
| Epitalon | insufflation |
| Fluphenazine | intramuscular |
| Liraglutide | subcutaneous |
| Paliperidone | intramuscular |
| Risperidone | intramuscular |
| Semaglutide | oral |
| Semaglutide | subcutaneous |

A concrete instance for the first defect: **CJC-1295** subcutaneous resolves a
recreational ladder (250–500 µg) and carries both a curated protocol
(100 µg once or twice daily) and a 3–14 day release window. Its route's
`doseContext` is `.recreational` before `attachAuxiliaryRoutes` and `.unknown`
after, although only the protocol and the window were meant to be added.

## Expected behavior

1. Attaching a protocol and a release window should not disturb the regime the
   ladder was resolved with. The `RouteVariant` rebuild should carry
   `doseContext` through, as it already carries unit, doses, duration and the
   elemental fraction. Note that the salt-form branch already re-feeds the
   enumeration index as `rank` to preserve ordering, so the intent to be lossless
   is clearly there — the context is the one field that got missed.

2. A route that has a release window and nothing else should still be attached,
   the same way a protocol-only route is. A fourth step over
   `doaByRoute.keys`, or folding the window into the protocol-only pass.

## Actual behavior

Both are as described above. Neither is a crash; both are content that silently
does not reach the screen.

## Session

n/a — no session is involved; this is reproducible from the database alone.

---

## Note before implementing

I did not change either behaviour in the Android port. The context reset is
reproduced exactly, with a test pinning it and a comment naming it as fidelity
rather than oversight — carrying the field through would be a one-line change,
but it would make Android label 20 cards that iOS leaves unlabelled, and that
divergence is not a port's to introduce. The nine unreachable windows are
likewise absent, with a test pinning the count so a fix here is noticed.

Happy to open a PR for either, or both.
