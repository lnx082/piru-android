package glass.kagerou.piru.substance

import glass.kagerou.piru.engine.DoseRecord
import glass.kagerou.piru.engine.DrugClass
import glass.kagerou.piru.engine.Enzyme
import glass.kagerou.piru.engine.InteractionPolicy
import glass.kagerou.piru.engine.InteractionRuleCopy
import glass.kagerou.piru.engine.InteractionSeverity
import glass.kagerou.piru.engine.ModulationDirection
import glass.kagerou.piru.engine.ModulationStrength
import glass.kagerou.piru.engine.ModulatorOrigin
import glass.kagerou.piru.model.RouteOfAdministration
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.jupiter.api.Test

/**
 * The six interaction tables, read out of the shipped 18 MB catalog.
 *
 * Every number below was confirmed with `sqlite3` against
 * `db/piru-substances.sqlite` before it was written down, so a failure means the
 * read layer is wrong, not the assertion. The counts are deliberately exact —
 * "99 rules with 43 caution / 28 unsafe / 28 dangerous" is a claim that survives
 * a rebuild noticing it changed, where "not empty" would not.
 *
 * The file's other job is the cross-check the pure engine suite cannot make: that
 * [InteractionRuleCopy]'s 87 sentences are the same 87 pairs the database's
 * curated layer carries, and that each row's own note is that exact sentence.
 * The sentence is copy and the row is data, written in different files by
 * different processes, and this is the only place the two meet.
 */
class InteractionReadsTest {

    private val now: Instant = Instant.ofEpochSecond(1_700_000_000)

    private fun defaultOrder(db: SubstanceDb): List<String> =
        db.query("SELECT slug FROM sources ORDER BY default_priority, slug")
            .mapNotNull { it.string("slug") }

    private fun reader(db: SubstanceDb) = SubstanceReader(db, defaultOrder(db), ContentLanguage.EN)

    private fun openCatalog(db: SubstanceDb) = DbSubstanceCatalog.open(
        db = db,
        order = defaultOrder(db),
        language = ContentLanguage.EN,
    )

    private fun substanceId(db: SubstanceDb, name: String): Long =
        db.queryOne("SELECT id FROM substances WHERE canonical_name = ?", listOf(name))
            ?.long("id") ?: error("$name missing from the catalog")

    /** The rule key for a raw class pair, in the same sorted form the copy table uses. */
    private fun key(classA: String, classB: String): String = listOf(classA, classB).sorted().joinToString("|")

    private fun dose(name: String, amount: Double = 10.0, minutesAgo: Long = 0) = DoseRecord(
        substance = name,
        amount = amount,
        unit = "mg",
        route = RouteOfAdministration.ORAL,
        timestamp = now.minusSeconds(minutesAgo * 60),
    )

    // MARK: - interaction_rules

    @Test
    fun `the class-pair table holds ninety-nine rules in the expected severity split`() {
        openBundledSubstanceDb().use { db ->
            val rules = reader(db).classInteractionRules()
            rules shouldHaveSize 99

            val bySeverity = rules.groupingBy { it.severity }.eachCount()
            bySeverity["caution"] shouldBe 43
            bySeverity["unsafe"] shouldBe 28
            bySeverity["dangerous"] shouldBe 28
            bySeverity.size shouldBe 3
        }
    }

    @Test
    fun `every rule names two known classes and a readable severity`() {
        openBundledSubstanceDb().use { db ->
            val rules = reader(db).classInteractionRules()
            // A row the checker cannot decode is a row it silently drops, so the
            // shipped artifact must have none of them.
            rules.filter { DrugClass.fromRaw(it.classA) == null } shouldBe emptyList()
            rules.filter { DrugClass.fromRaw(it.classB) == null } shouldBe emptyList()
            rules.filter { InteractionSeverity.bundled(it.severity) == null } shouldBe emptyList()
            rules.filter { it.note.isBlank() } shouldBe emptyList()
        }
    }

    @Test
    fun `the table is disjoint coverage, not two opinions about the same pairs`() {
        openBundledSubstanceDb().use { db ->
            // 87 curated adjudications plus 12 TripSit pairs those do not reach.
            // No pair appears twice, in either order.
            val pairs = reader(db).classInteractionRules().map { listOf(it.classA, it.classB).sorted() }
            pairs.toSet() shouldHaveSize 99
        }
    }

    // MARK: - The copy table against the curated rows

    @Test
    fun `every copy sentence is the curated row's own note for that pair`() {
        openBundledSubstanceDb().use { db ->
            val byPair = reader(db).classInteractionRules()
                .associateBy { listOf(it.classA, it.classB).sorted() }

            InteractionRuleCopy.table shouldHaveSize 87
            for ((key, sentence) in InteractionRuleCopy.table) {
                val (classA, classB) = key.split("|")
                val row = byPair[listOf(classA, classB).sorted()]
                    ?: error("copy table has a sentence for $key, but no rule row does")
                row.note shouldBe sentence
            }
        }
    }

    @Test
    fun `the rules the copy table does not cover are exactly the tripsit twelve`() {
        openBundledSubstanceDb().use { db ->
            val uncovered = reader(db).classInteractionRules().filter { row ->
                InteractionRuleCopy.key(
                    DrugClass.fromRaw(row.classA)!!,
                    DrugClass.fromRaw(row.classB)!!,
                ) !in InteractionRuleCopy.table
            }
            uncovered shouldHaveSize 12
            // Named rather than merely counted: these are TripSit's twelve, and
            // they are the pairs Piru has not adjudicated, which is why they have
            // no Piru-voice sentence — the honest default for a verdict nobody
            // here made.
            uncovered.map { key(it.classA, it.classB) }.toSet() shouldBe setOf(
                "alcohol|maoi",
                "alcohol|serotonergic",
                "alcohol|ssri",
                "benzodiazepine|serotonergic",
                "cannabinoid|gabapentinoid",
                "cannabinoid|stimulant",
                "gabapentinoid|ghb",
                "gabapentinoid|serotonergic",
                "gabapentinoid|stimulant",
                "ghb|serotonergic",
                "opioid|serotonergic",
                "serotonergic|stimulant",
            )
        }
    }

    // MARK: - substance_interaction_classes

    @Test
    fun `the override table expands to every spelling of every linked substance`() {
        openBundledSubstanceDb().use { db ->
            // 186 explicit spellings; the alias expansion takes the map to 1,357
            // keys, because a linked row answers under every alias of its
            // substance too.
            val overrides = reader(db).substanceInteractionClasses()
            overrides shouldHaveSize 1357

            // A dual-class substance keeps its curated rank order: 0 is dominant.
            overrides["tramadol"] shouldBe listOf(DrugClass.OPIOID, DrugClass.SEROTONERGIC)
            overrides["mdma"] shouldBe listOf(DrugClass.EMPATHOGEN, DrugClass.STIMULANT)
            overrides["dxm"] shouldBe listOf(DrugClass.DISSOCIATIVE, DrugClass.SEROTONERGIC)
            overrides["quetiapine"] shouldBe listOf(DrugClass.ANTIPSYCHOTIC, DrugClass.ANTIHISTAMINE)
        }
    }

    @Test
    fun `a brand name that the override table never names still resolves`() {
        openBundledSubstanceDb().use { db ->
            val overrides = reader(db).substanceInteractionClasses()
            // "Lexapro" appears nowhere in `substance_interaction_classes`; it is an
            // alias of Citalopram, whose two override rows (citalopram and
            // escitalopram) both link to it — which is why the expanded list
            // carries the class twice. That duplication is upstream's, kept
            // deliberately: the checker takes the worst rule across the list and
            // the list is what a detail screen prints.
            overrides["lexapro"] shouldBe listOf(DrugClass.SSRI, DrugClass.SSRI)
            overrides["citalopram"] shouldBe listOf(DrugClass.SSRI)
            // A name the catalog has no substance for still answers under its own
            // spelling — the xylazine-and-rare-barbiturate case.
            overrides["ghb/gbl"] shouldBe listOf(DrugClass.GHB)
            // A substance with no override at all is simply absent, and falls
            // through to its category.
            overrides.containsKey("alprazolam") shouldBe false
        }
    }

    // MARK: - category_interaction_classes

    @Test
    fun `the category fallback table holds the twenty-nine shipped mappings`() {
        openBundledSubstanceDb().use { db ->
            val categories = reader(db).categoryInteractionClasses()
            categories shouldHaveSize 29
            categories["Opioid"] shouldBe DrugClass.OPIOID
            categories["Benzodiazepine"] shouldBe DrugClass.BENZODIAZEPINE
            // The three that are not name-for-name.
            categories["GABAergic"] shouldBe DrugClass.GABAPENTINOID
            categories["Eugeroic"] shouldBe DrugClass.STIMULANT
            categories["Deliriant"] shouldBe DrugClass.ANTIHISTAMINE
            categories["Dysdelic"] shouldBe DrugClass.DISSOCIATIVE
            categories["Other"] shouldBe DrugClass.OTHER
        }
    }

    // MARK: - drug_interactions_pk

    @Test
    fun `the pharmacokinetic rows for one substance come back source-ranked and name-sorted`() {
        openBundledSubstanceDb().use { db ->
            val rows = reader(db).pkInteractionRows(substanceId(db, "Ketamine"))
            rows shouldHaveSize 4
            // One source, so the tie-break on the counterpart name decides.
            rows.map { it.withSubstance } shouldBe
                listOf("clarithromycin", "rifampin", "ticlopidine", "ticlopidine")
            rows.first().mechanism shouldBe "CYP3A4 inhibition"
            rows.forEach { it.sourceSlug shouldBe "peer-review-primary" }
        }
    }

    @Test
    fun `counterpart names split on the slash, and a class-named row stays unresolved`() {
        openBundledSubstanceDb().use { db ->
            val byName = reader(db).pkInteractionRows(substanceId(db, "Ketamine"))
                .associateBy { it.withSubstance }
            // The table is free text: one drug, a slash-separated pair, or a class.
            byName.getValue("clarithromycin").counterpartNames shouldBe listOf("clarithromycin")

            // A slash-separated row elsewhere in the table splits into both names.
            val clonazepam = reader(db).pkInteractionRows(substanceId(db, "Clonazepam"))
                .first { it.withSubstance == "ketoconazole / itraconazole" }
            clonazepam.counterpartNames shouldBe listOf("ketoconazole", "itraconazole")
        }
    }

    // MARK: - tag_enzyme_interactions

    @Test
    fun `the tag-derived table is materialized with both names resolved`() {
        openBundledSubstanceDb().use { db ->
            val rows = reader(db).tagEnzymeRows()
            rows shouldHaveSize 285
            rows.groupingBy { it.enzyme }.eachCount() shouldBe mapOf(
                "CYP3A4" to 153,
                "CYP2D6" to 120,
                "CYP2C19" to 6,
                "CYP1A2" to 6,
            )
            // The pipeline materializes inhibition edges only; induction comes
            // from the curated modulator table.
            rows.map { it.direction }.toSet() shouldBe setOf("inhibits")
            rows.count { it.victimType == "prodrug" } shouldBe 36
            // Both names are the substances' canonical spellings, resolved
            // through the join rather than carried as ids.
            rows.any {
                it.perpetratorName == "Diphenhydramine" &&
                    it.victimName == "Codeine" &&
                    it.enzyme == "CYP2D6" &&
                    it.victimType == "prodrug" &&
                    it.strength == null
            } shouldBe true
        }
    }

    // MARK: - enzyme_modulators

    @Test
    fun `the modulator table reads in curated rank order with its matchers attached`() {
        openBundledSubstanceDb().use { db ->
            val modulators = reader(db).enzymeModulatorRows()
            modulators shouldHaveSize 11

            val grapefruit = modulators.first()
            grapefruit.id shouldBe "grapefruit"
            grapefruit.origin shouldBe ModulatorOrigin.CONTEXT
            grapefruit.enzyme shouldBe Enzyme.CYP3A4
            grapefruit.direction shouldBe ModulationDirection.INHIBITS
            grapefruit.strength shouldBe ModulationStrength.MODERATE
            // A pure context flag is never logged as a dose, so it has no matchers.
            grapefruit.matchers.shouldBeEmpty()

            val bupropion = modulators.first { it.id == "bupropion" }
            bupropion.matchers shouldHaveSize 8
            bupropion.matchers shouldContain "wellbutrin"
            bupropion.matchers shouldContain "zyban"
            bupropion.enzyme shouldBe Enzyme.CYP2D6
            bupropion.strength shouldBe ModulationStrength.STRONG

            // The self-edge: MDMA inhibiting the enzyme that clears it.
            modulators.first { it.id == "mdma-cyp2d6" }.origin shouldBe ModulatorOrigin.SELF
        }
    }

    @Test
    fun `a row whose sentence fields are empty is dropped rather than half-rendered`() {
        openBundledSubstanceDb().use { db ->
            // The shipped table has all eleven complete, so the drop rule has
            // nothing to bite on — which is itself the assertion: a rebuild that
            // blanks one of these fields shows up here rather than as a sentence
            // with a missing clause.
            val modulators = reader(db).enzymeModulatorRows()
            modulators.filter { it.displayName.isBlank() || it.userNote.isBlank() }.shouldBeEmpty()
            modulators.filter { it.matchers.isEmpty() }.map { it.id } shouldBe
                listOf("grapefruit", "smoking")
        }
    }

    // MARK: - The engine over the real read layer

    @Test
    fun `the checker resolves classes through the real catalog`() {
        openBundledSubstanceDb().use { db ->
            val checker = openCatalog(db).interactionChecker
            checker.drugClasses("Tramadol") shouldBe listOf(DrugClass.OPIOID, DrugClass.SEROTONERGIC)
            // An alias resolves to its compound's override…
            checker.drugClasses("Xanax") shouldBe listOf(DrugClass.BENZODIAZEPINE)
            // …and a substance with no override falls back to its category.
            checker.drugClasses("Alprazolam") shouldBe listOf(DrugClass.BENZODIAZEPINE)
            // The classes with no rule mention every one of the 99 rows, so the
            // unruled pair really is invisible.
            checker.hasAnyRule(DrugClass.SUPPLEMENT) shouldBe false
            checker.duplicateRuleKeys.shouldBeEmpty()
        }
    }

    @Test
    fun `major clearance enzymes come off the metabolism rows`() {
        openBundledSubstanceDb().use { db ->
            val catalog = openCatalog(db)
            // MDMA's cells name CYP2D6, CYP1A2, CYP2B6, CYP3A4 and COMT; the
            // clause that names the last two is qualified "minor N-demethylation",
            // so it is dropped whole.
            catalog.majorEnzymes("MDMA") shouldBe setOf(Enzyme.CYP2D6, Enzyme.CYP1A2)
            // An unquantified listed pathway counts: codeine's CYP2D6 row carries
            // no clearance percentage, and its quantified one is only 10%.
            catalog.majorEnzymes("Codeine") shouldBe setOf(Enzyme.CYP2D6)
            // 70% CYP3A4 is major; a 5% row beside it is not what decides it.
            catalog.majorEnzymes("Midazolam") shouldBe setOf(Enzyme.CYP3A4)
            // A name the catalog does not carry has no metabolism to read.
            catalog.majorEnzymes("zzzNotARealDrugzzz").shouldBeEmpty()
        }
    }

    @Test
    fun `the explorer finds the MAOI and empathogen pair in the shipped table`() {
        openBundledSubstanceDb().use { db ->
            val results = openCatalog(db).interactionChecker.checkBatch(
                listOf("MDMA", "Phenelzine"),
                against = emptyList(),
                policy = InteractionPolicy.EXPLORE,
            )
            val dangerous = results.filter { it.severity == InteractionSeverity.DANGEROUS }
            (dangerous.isNotEmpty()) shouldBe true
            // Both orderings of the pair are present as distinct rules, so the
            // worst one is what the reader sees.
            dangerous.map { it.ruleKey }.toSet().contains("empathogen|maoi") shouldBe true
        }
    }

    @Test
    fun `warn mode silences a faded dose that explore mode still shows`() {
        openBundledSubstanceDb().use { db ->
            val checker = openCatalog(db).interactionChecker
            val faded = listOf(dose("Kratom", amount = 5.0, minutesAgo = 12 * 60))

            checker.checkBatch(listOf("Alprazolam"), against = faded, policy = InteractionPolicy.WARN, now = now)
                .shouldBeEmpty()
            checker.checkBatch(listOf("Alprazolam"), against = faded, policy = InteractionPolicy.EXPLORE, now = now)
                .isEmpty() shouldBe false
        }
    }

    @Test
    fun `a residual MAOI still fires against an empathogen twelve hours on`() {
        openBundledSubstanceDb().use { db ->
            val checker = openCatalog(db).interactionChecker
            // A phenelzine dose whose acute curve is long gone: the persistent
            // classes bypass the effect-overlap gate, which is the whole reason
            // the pair is not silenced.
            val results = checker.checkBatch(
                listOf("MDMA"),
                against = listOf(dose("Phenelzine", amount = 15.0, minutesAgo = 24 * 60)),
                policy = InteractionPolicy.WARN,
                now = now,
            )
            results.any { it.severity == InteractionSeverity.DANGEROUS } shouldBe true
        }
    }

    @Test
    fun `the pharmacokinetic read connects the logged counterpart in both directions`() {
        openBundledSubstanceDb().use { db ->
            val checker = openCatalog(db).interactionChecker
            val findings = checker.pharmacokineticInteractions(
                "Ketamine",
                listOf(dose("Clarithromycin"), dose("zzzNothingzzz")),
            )
            findings shouldHaveSize 1
            findings.first().withSubstance shouldBe "clarithromycin"
            findings.first().mechanism shouldBe "CYP3A4 inhibition"

            // Nothing logged that resolves to a drug -> nothing claimed.
            checker.pharmacokineticInteractions("Ketamine", listOf(dose("zzzNothingzzz"))).shouldBeEmpty()
        }
    }

    @Test
    fun `the checker is a single held instance per catalog`() {
        openBundledSubstanceDb().use { db ->
            val subject = openCatalog(db)
            // One checker per catalog, so its rule table is built once rather than
            // rebuilt on every keystroke of the explorer.
            (subject.interactionChecker === subject.interactionChecker) shouldBe true
            subject.classRules() shouldHaveSize 99
            subject.interactionClasses() shouldHaveSize 1357
            subject.categoryInteractionClasses() shouldHaveSize 29
            subject.enzymeModulators() shouldHaveSize 11
            // The tag index is perpetrator -> victim -> row, so its top-level
            // size is the number of perpetrators, not the 285 rows behind them.
            val tagIndex = subject.tagEnzymeInteractions()
            tagIndex shouldHaveSize 9
            // 281, not the table's 285: the index is keyed (perpetrator, victim)
            // while the table's primary key adds `enzyme`, so the four pairs that
            // interact on two enzymes collapse to one row each — Fluoxetine with
            // 25C-NBOMe, 25I-NBOMe, Galantamine and Hydrocodone. Upstream's
            // subscript assignment has the same effect; the checker asks one
            // question per pair, so the last enzyme wins rather than both firing.
            tagIndex.values.sumOf { it.size } shouldBe 281
            tagIndex.keys.sorted() shouldBe listOf(
                "diphenhydramine", "fluoxetine", "kava", "mdma", "modafinil",
                "nefazodone", "paroxetine", "propranolol", "viloxazine",
            )
        }
    }
}
