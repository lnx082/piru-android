package glass.kagerou.piru.substance

import glass.kagerou.piru.model.DoseContext
import glass.kagerou.piru.model.DurationOfAction
import glass.kagerou.piru.model.DurationRange
import glass.kagerou.piru.model.RouteOfAdministration
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * Source-priority resolution against the shipped catalog.
 *
 * Ported behaviour from `SubstanceReadModel.swift`. The expectations are read
 * out of the real database — every number here was confirmed with `sqlite3`
 * against `db/piru-substances.sqlite` before it was written down, so a failure
 * means the resolver is wrong, not the assertion.
 */
class SubstanceReaderTest {

    /** The shipped source order, highest priority first, as the app would load it. */
    private fun defaultOrder(db: SubstanceDb): List<String> =
        db.query("SELECT slug FROM sources ORDER BY default_priority, slug")
            .mapNotNull { it.string("slug") }

    private fun substanceId(db: SubstanceDb, name: String): Long =
        db.queryOne("SELECT id FROM substances WHERE canonical_name = ?", listOf(name))
            ?.long("id") ?: error("$name missing from the catalog")

    // MARK: - SQL fragments

    @Test
    fun `priority case maps slugs to their rank`() {
        val sql = SourcePriority(listOf("a", "b", "c")).priorityCaseSQL
        sql shouldBe "CASE src.slug WHEN 'a' THEN 0 WHEN 'b' THEN 1 WHEN 'c' THEN 2 ELSE 999 END"
    }

    @Test
    fun `an empty order ranks everything equally`() {
        // The constant, not an empty CASE — an order that has not loaded must
        // still produce valid SQL, and every row tying is the correct behaviour.
        SourcePriority(emptyList()).priorityCaseSQL shouldBe "999"
    }

    @Test
    fun `the enabled list is a SQL list, and matches nothing when empty`() {
        SourcePriority(listOf("a", "b")).enabledSourceListSQL shouldBe "'a', 'b'"
        // Load-bearing, not cosmetic: the prose resolvers retry without the
        // enabled-source filter, and this literal is what makes the launch
        // window fall through to that retry instead of blanking every overview.
        SourcePriority(emptyList()).enabledSourceListSQL shouldBe "''"
    }

    @Test
    fun `a field override joins the override table and ranks it first`() {
        val rank = SourcePriority(listOf("a", "b")).fieldRankSQL(field = "doses", alias = "d")
        rank.join shouldBe
            "LEFT JOIN source_field_priority sfp\n" +
            "       ON sfp.source_id = d.source_id\n" +
            "      AND sfp.field = 'doses'"
        // The plain priority case stays on as the last term, so a tie falls back
        // to the ordinary order.
        rank.terms shouldBe listOf(
            "COALESCE(sfp.priority, CASE src.slug WHEN 'a' THEN 0 WHEN 'b' THEN 1 ELSE 999 END)",
            "CASE src.slug WHEN 'a' THEN 0 WHEN 'b' THEN 1 ELSE 999 END",
        )
    }

    @Test
    fun `no field means no join, and a row added to the override table cannot move it`() {
        val rank = SourcePriority(listOf("a")).fieldRankSQL(field = null, alias = "t")
        rank.join shouldBe ""
        rank.terms shouldBe listOf("CASE src.slug WHEN 'a' THEN 0 ELSE 999 END")
    }

    @Test
    fun `quote in a slug is escaped rather than closing the literal`() {
        SourcePriority(listOf("a'b")).enabledSourceListSQL shouldBe "'a''b'"
    }

    // MARK: - Dose ladders (fail closed)

    @Test
    fun `the default order resolves Caffeine oral to dosewiki`() {
        openBundledSubstanceDb().use { d ->
            val reader = SubstanceReader(d, defaultOrder(d), ContentLanguage.EN)
            val id = substanceId(d, "Caffeine")
            val ladder = reader.doseLadders(setOf(id))
                .entries.first { it.key.route == "oral" }.value

            // Four sources carry a Caffeine oral ladder: dosewiki 75–250,
            // drug.community 50–200, psychonautwiki 50–150, tripsit 75–75.
            // dosewiki has the best default priority of the four.
            ladder.sourceSlug shouldBe "dosewiki"
            ladder.doses.common shouldBe 75.0..250.0
        }
    }

    @Test
    fun `restricting to one source resolves to that source`() {
        openBundledSubstanceDb().use { d ->
            val reader = SubstanceReader(d, listOf("tripsit"), ContentLanguage.EN)
            val id = substanceId(d, "Caffeine")
            val ladder = reader.doseLadders(setOf(id))
                .entries.first { it.key.route == "oral" }.value
            ladder.sourceSlug shouldBe "tripsit"
            ladder.doses.common shouldBe 75.0..75.0
        }
    }

    @Test
    fun `disabling every source that carries a ladder blanks it`() {
        openBundledSubstanceDb().use { d ->
            // Fail closed. pubchem carries no dose ranges at all, so enabling
            // only it must yield no ladder rather than falling back to a source
            // the user turned off. This is the whole difference from the prose
            // resolvers, and it is deliberate: a ladder drives the curve and the
            // safety displays, a description does not.
            val reader = SubstanceReader(d, listOf("pubchem"), ContentLanguage.EN)
            val id = substanceId(d, "Caffeine")
            reader.doseLadders(setOf(id)) shouldBe emptyMap()
        }
    }

    @Test
    fun `a therapeutic ladder sorts behind a recreational one`() {
        openBundledSubstanceDb().use { d ->
            val reader = SubstanceReader(d, defaultOrder(d), ContentLanguage.EN)
            val id = substanceId(d, "Diphenhydramine")
            val ladder = reader.doseLadders(setOf(id))
                .entries.first { it.key.route == "oral" }.value

            // Diphenhydramine oral has five candidate ladders. On source rank
            // alone the curated *therapeutic* allergy dose (50–100 mg) wins.
            // With `doseContextLast` it does not: a therapeutic figure sitting
            // beside a dose somebody logged reads as a recommendation to take
            // that much, and it is the recreational ladder their number means
            // anything against. Verified both ways with sqlite3.
            ladder.doseContext shouldBe "recreational"
            ladder.sourceSlug shouldBe "dosewiki"
            ladder.doses.common shouldBe 150.0..300.0
        }
    }

    // MARK: - Prose (fail open)

    @Test
    fun `prose still resolves when its only source is disabled`() {
        openBundledSubstanceDb().use { d ->
            // 25E-NBOH has exactly one description, from dosewiki. Disabling
            // dosewiki blanks every other dosewiki-backed field for it, and the
            // description must survive anyway — prose is inert reference content
            // and a blank overview is strictly worse.
            val id = substanceId(d, "25E-NBOH")
            val enabled = SubstanceReader(d, defaultOrder(d), ContentLanguage.EN)
            enabled.textRow("descriptions", "text", id) shouldNotBe null

            val excluded = SubstanceReader(d, listOf("pubchem"), ContentLanguage.EN)
            excluded.textRow("descriptions", "text", id) shouldNotBe null
        }
    }

    @Test
    fun `structured values do not fail open`() {
        openBundledSubstanceDb().use { d ->
            // The mirror of the test above, on the same substance: prose returns,
            // a ladder does not. If these two ever agree, one of them is wrong.
            val id = substanceId(d, "25E-NBOH")
            val excluded = SubstanceReader(d, listOf("pubchem"), ContentLanguage.EN)
            excluded.textRow("descriptions", "text", id) shouldNotBe null
            excluded.doseLadders(setOf(id)) shouldBe emptyMap()
        }
    }

    @Test
    fun `the descriptions field override changes no outcome on the shipped data`() {
        openBundledSubstanceDb().use { d ->
            // Measured, not assumed: across the whole catalog the override moves
            // zero substances to a different source. It grants dosewiki priority
            // 1, and the only sources at or above that are piru-curated (0, which
            // always wins regardless) and peer-review-primary (1, which carries no
            // descriptions at all).
            //
            // Pinned so that a change which makes the override start mattering —
            // a new source, or a curated description added — is noticed rather
            // than silently altering what every substance's overview says.
            val order = defaultOrder(d)
            val plain = SubstanceReader(d, order, ContentLanguage.EN)
            val rank = plain.priority.fieldRankSQL(field = "descriptions", alias = "t")

            fun winner(substanceID: Long, withOverride: Boolean): String? {
                val join = if (withOverride) rank.join else ""
                val ordering =
                    if (withOverride) rank.orderBy
                    else plain.priority.priorityCaseSQL + " ASC"
                return d.queryOne(
                    """
                    SELECT src.slug AS slug FROM descriptions t
                      JOIN sources src ON src.id = t.source_id
                      $join
                     WHERE t.substance_id = ?
                     ORDER BY $ordering LIMIT 1
                    """,
                    listOf(substanceID),
                )?.string("slug")
            }

            val moved = d.query(
                "SELECT DISTINCT substance_id AS sid FROM descriptions",
            ).mapNotNull { it.long("sid") }
                .filter { winner(it, withOverride = true) != winner(it, withOverride = false) }
            moved.shouldBeEmpty()
        }
    }

    // MARK: - Route assembly

    @Test
    fun `salt variants fold into one route, default first`() {
        openBundledSubstanceDb().use { d ->
            val reader = SubstanceReader(d, defaultOrder(d), ContentLanguage.EN)
            val id = substanceId(d, "Magnesium")
            val oral = reader.routes(setOf(id)).getValue(id).first { it.route.wireValue == "oral" }

            // Three curated salt ladders. `salt_rank` 0/1/2 makes Glycinate the
            // default — a data-driven choice, not an alphabetical one (Citrate
            // would win that).
            val forms = oral.saltForms!!
            forms.map { it.saltForm } shouldBe listOf("Glycinate", "Citrate", "L-Threonate")

            // The element each salt actually delivers, carried per form so the UI
            // can show "= ⟨elemental⟩ mg".
            forms.first { it.saltForm == "Citrate" }.elementalFraction shouldBe 0.16
            forms.first { it.saltForm == "Glycinate" }.elementalFraction shouldBe 0.141

            // The route's headline numbers mirror the default form, so code that
            // knows nothing about salts still gets the right ladder rather than an
            // empty one.
            oral.doses.common shouldBe 200.0..400.0
            oral.saltForms!!.first().doses shouldBe oral.doses
        }
    }

    @Test
    fun `a single-form substance carries no salt list`() {
        openBundledSubstanceDb().use { d ->
            val reader = SubstanceReader(d, defaultOrder(d), ContentLanguage.EN)
            val id = substanceId(d, "Caffeine")
            val routes = reader.routes(setOf(id)).getValue(id)
            routes.isNotEmpty() shouldBe true
            // Null rather than a one-element list: the picker is shown only when
            // there is more than one form, and `saltForms != null` is the signal.
            routes.forEach { it.saltForms shouldBe null }
        }
    }

    @Test
    fun `an isomer family keeps the racemate alongside its enantiomers`() {
        openBundledSubstanceDb().use { d ->
            val reader = SubstanceReader(d, defaultOrder(d), ContentLanguage.EN)
            val id = substanceId(d, "Ketamine")
            val insufflation = reader.routes(setOf(id)).getValue(id)
                .first { it.route.wireValue == "insufflation" }

            // Ketamine insufflation carries a base ladder plus R- and S-tagged
            // ones. An isomer family keeps ALL of them — the racemic parent
            // coexists with its enantiomers — where a salt-only substance would
            // keep just the tagged ones.
            val forms = insufflation.saltForms!!
            forms.map { it.isomer } shouldBe listOf(null, "R", "S")

            // Racemic first is the sensible default, and the route's headline
            // numbers follow it: dosewiki's base ladder, not Esketamine's.
            insufflation.doses.common shouldBe 30.0..75.0
            forms.first { it.isomer == "S" }.doses.common shouldBe 56.0..84.0
        }
    }

    @Test
    fun `durations resolve per phase, not per route`() {
        openBundledSubstanceDb().use { d ->
            val reader = SubstanceReader(d, defaultOrder(d), ContentLanguage.EN)
            val id = substanceId(d, "Amphetamine")
            val profile = reader.durations(setOf(id))
                .entries.first { it.key.route == "insufflation" }.value

            // Every phase is contested by several sources, and the partition
            // includes `phase` so each is ranked on its own. Ranking per route
            // instead would take all five phases from whichever source won once.
            // Values confirmed against the catalog with `sqlite3`, tiebroken by id.
            profile.onset shouldBe DurationRange(1.0, 10.0)
            profile.comeup shouldBe DurationRange(10.0, 30.0)
            profile.peak shouldBe DurationRange(60.0, 180.0)
            profile.offset shouldBe DurationRange(60.0, 120.0)
            profile.afterglow shouldBe DurationRange(120.0, 1440.0)
            profile.total shouldBe DurationRange(180.0, 360.0)
        }
    }

    // MARK: - Auxiliary routes

    @Test
    fun `protocol dosing and release windows ride along on the dose route`() {
        openBundledSubstanceDb().use { d ->
            val reader = SubstanceReader(d, defaultOrder(d), ContentLanguage.EN)
            val id = substanceId(d, "CJC-1295")
            val subcutaneous = reader.detailRoutes(id)
                .first { it.route.wireValue == "subcutaneous" }

            // Two curated protocol rows tie on source, so the id tiebreaker
            // decides: the 100 mcg "once or twice daily" one, not the 1–2 mg
            // weekly one.
            val protocol = subcutaneous.protocolDosing!!
            protocol.lowAmount shouldBe 100.0
            protocol.highAmount shouldBe 100.0
            protocol.frequency shouldBe "once or twice daily"

            // The route's unit is the *ladder's*, not the protocol's. They
            // disagree here — the curated dose row says µg (U+00B5) while the
            // protocol says the ASCII "mcg" — and `SubstanceRoute` has one unit
            // field, so the ladder's wins. Worth pinning because it means a
            // protocol's unit is only ever seen on a route with no ladder.
            subcutaneous.unit shouldBe "µg"

            // 3 to 14 days, in minutes.
            subcutaneous.durationOfAction shouldBe DurationOfAction(4_320.0, 20_160.0)
        }
    }

    @Test
    fun `a route that exists only as a duration profile is appended`() {
        openBundledSubstanceDb().use { d ->
            val reader = SubstanceReader(d, defaultOrder(d), ContentLanguage.EN)
            val id = substanceId(d, "2C-F")

            // 2C-F carries five duration phases from drug.community and no dose
            // ladder at all, so `routes` never mentions it — the browse path has
            // nothing to show — and only the detail path brings it back.
            reader.routes(setOf(id))[id].orEmpty().shouldBeEmpty()

            val oral = reader.detailRoutes(id).first { it.route.wireValue == "oral" }
            // No ladder: an empty range is the honest answer, not a null.
            oral.doses.hasAnyValue shouldBe false
            oral.unit shouldBe "mg"
            oral.duration!!.onset shouldBe DurationRange(30.0, 90.0)
            oral.duration!!.total shouldBe DurationRange(360.0, 600.0)
        }
    }

    @Test
    fun `a route that exists only as a release window reaches the detail screen`() {
        openBundledSubstanceDb().use { d ->
            // Ten routes on the shipped catalog carry a duration-of-action and nothing
            // else — no ladder and no acute duration profile. They are all long-acting
            // injectables: risperidone, paliperidone, fluphenazine and aripiprazole
            // depots, liraglutide, semaglutide and dulaglutide, plus epitalon.
            //
            // This test used to assert the opposite: that the window was in the catalog
            // and still absent from what a detail screen renders, because
            // `attachAuxiliaryRoutes` consulted the windows only for routes it was already
            // building and no step built one of these. The gap was pinned here and reported
            // upstream; it is fixed on this side now, because the release window is the
            // single most useful thing to say about a depot injection and leaving it out
            // left those ten pages with no route section at all.
            val cases = listOf(
                "Aripiprazole" to "intramuscular",
                "Dulaglutide" to "subcutaneous",
                "Epitalon" to "insufflation",
                "Fluphenazine" to "intramuscular",
                "Liraglutide" to "subcutaneous",
                "Paliperidone" to "intramuscular",
                "Risperidone" to "intramuscular",
                "Semaglutide" to "oral",
                "Semaglutide" to "subcutaneous",
                "Epitalon" to "subcutaneous",
            )
            val reader = SubstanceReader(d, defaultOrder(d), ContentLanguage.EN)
            for ((name, route) in cases) {
                val id = substanceId(d, name)
                val window = reader.durationsOfAction(id)[RouteOfAdministration.from(route)]
                if (window == null) throw AssertionError("no window for $name/$route")

                val shown = reader.detailRoutes(id)
                    .firstOrNull { it.route.wireValue == route }
                    ?: throw AssertionError("$name/$route did not reach the detail path")
                // The window is the thing that made the route worth showing.
                if (shown.durationOfAction != window) {
                    throw AssertionError("$name/$route lost its window")
                }
                // And there is no ladder behind it, which is why it needed step 2.
                if (shown.doses.hasAnyValue) {
                    throw AssertionError("$name/$route unexpectedly carries a ladder")
                }
            }
        }
    }

    @Test
    fun `a route that exists only as a clinical protocol is appended`() {
        openBundledSubstanceDb().use { d ->
            val reader = SubstanceReader(d, defaultOrder(d), ContentLanguage.EN)
            val id = substanceId(d, "Adipotide")

            reader.routes(setOf(id))[id].orEmpty().shouldBeEmpty()

            val route = reader.detailRoutes(id)
                .first { it.route.wireValue == "subcutaneous" }
            route.doses.hasAnyValue shouldBe false
            route.duration shouldBe null
            // A research-only compound: no amounts at all, just the frequency.
            route.protocolDosing!!.frequency shouldBe "research only"
        }
    }

    @Test
    fun `the re-fold drops the dose context, matching upstream`() {
        openBundledSubstanceDb().use { d ->
            val reader = SubstanceReader(d, defaultOrder(d), ContentLanguage.EN)
            val id = substanceId(d, "CJC-1295")

            // Before: the ladder is labelled as recreational.
            val before = reader.routes(setOf(id)).getValue(id)
                .first { it.route.wireValue == "subcutaneous" }
            before.doseContext shouldBe DoseContext.RECREATIONAL

            // After: it is not. Attaching the protocol and the release window
            // rebuilds the variant without naming a context, and the field
            // defaults to unknown. This is upstream's code path exactly, and the
            // pinned consequence is that an `medical_rx` compound — the class whose
            // card *shows* the regime label — loses it for having a protocol.
            val after = reader.detailRoutes(id)
                .first { it.route.wireValue == "subcutaneous" }
            after.doseContext shouldBe DoseContext.UNKNOWN

            // Everything else survives the re-fold.
            after.doses.common shouldBe before.doses.common
            after.saltForms shouldBe before.saltForms
        }
    }

    @Test
    fun `a route with no protocol or window is left untouched`() {
        openBundledSubstanceDb().use { d ->
            val reader = SubstanceReader(d, defaultOrder(d), ContentLanguage.EN)
            val id = substanceId(d, "Diphenhydramine")

            // The counterpart to the test above: no protocol, no release window,
            // so the route never goes through the re-fold and keeps its context.
            val before = reader.routes(setOf(id)).getValue(id)
            val after = reader.detailRoutes(id)
            before.map { it.route to it.doseContext } shouldBe
                after.map { it.route to it.doseContext }
            after.first { it.route.wireValue == "oral" }.doseContext shouldBe DoseContext.RECREATIONAL
        }
    }

    // MARK: - Ties

    @Test
    fun `tied rows are broken deterministically rather than by scan order`() {
        openBundledSubstanceDb().use { d ->
            // Both tables declare UNIQUE (substance_id, route, source_id,
            // salt_form, isomer), and it does not hold: SQLite treats each NULL in
            // a unique index as distinct, and salt_form/isomer are NULL almost
            // everywhere. 4-Fluorophenylpiperazine insufflation has two
            // drug.community ladders, 20-35 and 20-40 mg.
            //
            // Without a tiebreaker the window function picks by scan order, which
            // is unspecified — and which this port has already changed once by
            // moving to the bundled SQLite driver. `ORDER BY …, id` makes the
            // choice reproducible without affecting which source wins.
            val rows = d.query(
                """
                SELECT d.common_upper AS hi, d.id AS id FROM dose_ranges d
                  JOIN substances s ON s.id = d.substance_id
                  JOIN sources src ON src.id = d.source_id
                 WHERE s.canonical_name = '4-Fluorophenylpiperazine'
                   AND d.route = 'insufflation' AND src.slug = 'drug.community'
                 ORDER BY d.id
                """,
            )
            rows.size shouldBe 2
            val lowestId = rows.first().long("id")
            // The two rows differ, which is what makes the tie worth breaking:
            // 35 against 40 mg at the top of the common band.
            rows.map { it.double("hi") } shouldBe listOf(35.0, 40.0)

            val reader = SubstanceReader(d, defaultOrder(d), ContentLanguage.EN)
            val id = substanceId(d, "4-Fluorophenylpiperazine")
            val ladder = reader.doseLadders(setOf(id))
                .entries.first { it.key.route == "insufflation" }.value

            // The lowest id wins — stable across runs and across SQLite builds.
            ladder.doses.common shouldBe 20.0..35.0
            ladder.sourceSlug shouldBe "drug.community"
            lowestId shouldBe 1689L

            // Repeated resolves agree, which is the property that actually
            // matters: a flaky duration is one nobody can pin in a test.
            repeat(5) {
                reader.doseLadders(setOf(id))
                    .entries.first { it.key.route == "insufflation" }.value.doses.common shouldBe
                    20.0..35.0
            }
        }
    }

    @Test
    fun `the duplicate rows the unique index fails to prevent are still present`() {
        openBundledSubstanceDb().use { d ->
            // Pinned so that a fix upstream — an index that handles NULLs, or a
            // data pass that removes them — is noticed here rather than silently
            // changing which ladder these substances resolve to.
            //
            // The two tables need different grouping keys: `durations` carries a
            // `phase` column that is part of its uniqueness, `dose_ranges` does
            // not. Grouping durations without it counts every phase of a source as
            // one tie.
            fun tiedGroups(sql: String): Int = d.query(sql).first().long("n")!!.toInt()

            tiedGroups(
                """
                SELECT count(*) AS n FROM (
                  SELECT 1 FROM dose_ranges
                   GROUP BY substance_id, route, source_id, ifnull(salt_form, ''), ifnull(isomer, '')
                  HAVING count(*) > 1
                )
                """,
            ) shouldBe 39

            tiedGroups(
                """
                SELECT count(*) AS n FROM (
                  SELECT 1 FROM durations
                   GROUP BY substance_id, route, phase, source_id, ifnull(salt_form, ''), ifnull(isomer, '')
                  HAVING count(*) > 1
                )
                """,
            ) shouldBe 73
        }
    }

    // MARK: - Language

    @Test
    fun `Chinese floats the matching language, English excludes it`() {
        ContentLanguage.ZH_HANS.clauses("t.language") shouldBe
            ContentLanguage.Clauses("", "(t.language = 'zh-Hans') DESC, (t.language LIKE 'zh%') DESC, ")
        ContentLanguage.ZH_HANT.clauses("t.language") shouldBe
            ContentLanguage.Clauses("", "(t.language = 'zh-Hant') DESC, (t.language LIKE 'zh%') DESC, ")
        // English excludes raw Chinese outright rather than ranking it last: a
        // Chinese blob is worse than a blank the bundled English template fills.
        ContentLanguage.EN.clauses("t.language") shouldBe
            ContentLanguage.Clauses(" AND t.language IN ('en', 'und') ", "")
    }

    @Test
    fun `Spanish resolves substance text as English`() {
        // The catalog carries no Spanish prose, so Spanish is a UI language
        // whose text resolves as English. The gate is `isChinese`, never
        // "not English" — which is exactly the distinction this pins.
        ContentLanguage.ES.isChinese shouldBe false
        ContentLanguage.ES.clauses("t.language") shouldBe ContentLanguage.EN.clauses("t.language")
    }

    @Test
    fun `localization identifiers map to a content language`() {
        ContentLanguage.fromLocalization("zh-Hans") shouldBe ContentLanguage.ZH_HANS
        ContentLanguage.fromLocalization("zh-HK") shouldBe ContentLanguage.ZH_HANT
        ContentLanguage.fromLocalization("zh-TW") shouldBe ContentLanguage.ZH_HANT
        ContentLanguage.fromLocalization("zh") shouldBe ContentLanguage.ZH_HANS
        ContentLanguage.fromLocalization("es-419") shouldBe ContentLanguage.ES
        ContentLanguage.fromLocalization("Base") shouldBe ContentLanguage.EN
        ContentLanguage.fromLocalization("fr") shouldBe ContentLanguage.EN
    }
}
