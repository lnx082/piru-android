package glass.kagerou.piru.engine

import glass.kagerou.piru.model.ConfidenceTier
import glass.kagerou.piru.model.DoseRange
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.SubstanceCategory
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import org.junit.jupiter.api.Test

/**
 * The interaction engine, exercised against invented substances and a small but
 * *real* slice of the bundled rule table.
 *
 * Ported behaviour from `PiruTests/InteractionCheckerTests.swift`,
 * `InteractionRuleTests.swift` and `InteractionRelevanceGatingTests.swift`. The
 * iOS suites call `SubstanceStore.shared.ensureAllLoaded()` and assert against
 * the shipped 1,688-row catalog; that end-to-end pass belongs with
 * `:core:substance`, over the real database. What this suite can do that a
 * DB-backed one cannot is pin the *rules* — which class wins, when the gate
 * suppresses, how severity and prominence are derived — on inputs whose every
 * number the test chose.
 *
 * Every rule and override below is copied from the real table so a failure here
 * means the engine is wrong rather than the fixture.
 */
class InteractionCheckerTest {

    // MARK: - Fixture

    private val T0: Instant = Instant.ofEpochSecond(1_700_000_000)

    /**
     * The slice of `interaction_rules` these tests need. Severity, classes and
     * note text are verbatim from the shipped 99-row table.
     */
    private val rules: List<ClassInteractionRule> = listOf(
        ClassInteractionRule(
            "benzodiazepine", "opioid", "dangerous",
            "Combined respiratory depression — the leading cause of overdose death.",
        ),
        ClassInteractionRule("empathogen", "maoi", "dangerous", "Risk of fatal serotonin syndrome."),
        ClassInteractionRule("serotonergic", "ssri", "dangerous", "Serotonin syndrome risk."),
        ClassInteractionRule(
            "antipsychotic", "barbiturate", "unsafe",
            "Additive CNS depression — increased sedation and impairment.",
        ),
        ClassInteractionRule(
            "alcohol", "antipsychotic", "unsafe",
            "Additive CNS depression — increased sedation and impairment.",
        ),
        ClassInteractionRule(
            "barbiturate", "opioid", "dangerous",
            "Combined respiratory depression with no ceiling.",
        ),
        ClassInteractionRule(
            "antihistamine", "opioid", "caution",
            "Additive CNS and respiratory depression.",
        ),
        ClassInteractionRule(
            "cannabinoid", "opioid", "caution",
            "Additive CNS depression — may increase sedation and respiratory depression risk.",
        ),
        ClassInteractionRule(
            "opioid", "stimulant", "caution",
            "Stimulants mask overdose signs — when they wear off, respiratory depression can emerge.",
        ),
        ClassInteractionRule(
            "stimulant", "stimulant", "caution",
            "Cardiovascular strain — combined stimulants increase heart rate and blood pressure.",
        ),
    )

    /** The slice of `substance_interaction_classes` these tests need. */
    private val overrides: Map<String, List<DrugClass>> = mapOf(
        "alprazolam" to listOf(DrugClass.BENZODIAZEPINE),
        "tramadol" to listOf(DrugClass.OPIOID, DrugClass.SEROTONERGIC),
        "mdma" to listOf(DrugClass.EMPATHOGEN, DrugClass.STIMULANT),
        "sertraline" to listOf(DrugClass.SSRI),
        "phenelzine" to listOf(DrugClass.MAOI),
        "aripiprazole" to listOf(DrugClass.ANTIPSYCHOTIC),
        "phenobarbital" to listOf(DrugClass.BARBITURATE),
        "alcohol" to listOf(DrugClass.ALCOHOL),
        "diphenhydramine" to listOf(DrugClass.ANTIHISTAMINE),
        "alprazolam supplement" to listOf(DrugClass.SUPPLEMENT),
    )

    /**
     * The whole `category_interaction_classes` mapping, verbatim — all 29 rows.
     *
     * Carried in full rather than trimmed to the categories these tests happen to
     * use, because a trimmed map silently resolves a substance to `.other` and
     * then reports "no interaction" as though that were an answer.
     */
    private val categories: Map<String, DrugClass> = mapOf(
        "Opioid" to DrugClass.OPIOID,
        "Benzodiazepine" to DrugClass.BENZODIAZEPINE,
        "Stimulant" to DrugClass.STIMULANT,
        "Psychedelic" to DrugClass.PSYCHEDELIC,
        "Dissociative" to DrugClass.DISSOCIATIVE,
        "Dysdelic" to DrugClass.DISSOCIATIVE,
        "Empathogen" to DrugClass.EMPATHOGEN,
        "Cannabinoid" to DrugClass.CANNABINOID,
        "GABAergic" to DrugClass.GABAPENTINOID,
        "AMPAkine" to DrugClass.OTHER,
        "Eugeroic" to DrugClass.STIMULANT,
        "Antidepressant" to DrugClass.SSRI,
        "Antipsychotic" to DrugClass.ANTIPSYCHOTIC,
        "Supplement" to DrugClass.SUPPLEMENT,
        "Nootropic" to DrugClass.OTHER,
        "Depressant" to DrugClass.OTHER,
        "OrexinAntagonist" to DrugClass.OREXIN_ANTAGONIST,
        "Analgesic" to DrugClass.OTHER,
        "Antihistamine" to DrugClass.ANTIHISTAMINE,
        "Deliriant" to DrugClass.ANTIHISTAMINE,
        "Cardiovascular" to DrugClass.OTHER,
        "Antimicrobial" to DrugClass.OTHER,
        "Gastrointestinal" to DrugClass.OTHER,
        "Respiratory" to DrugClass.OTHER,
        "Endocrine" to DrugClass.OTHER,
        "Immunological" to DrugClass.OTHER,
        "Peptide" to DrugClass.OTHER,
        "Anticonvulsant" to DrugClass.OTHER,
        "Other" to DrugClass.OTHER,
    )

    /** 180 minutes end to end — long enough to overlap at t=0, short enough to fade by +12 h. */
    private fun catalog(): SubstanceCatalog = FakeCatalog(
        substances = listOf(
            substance(
                "Alprazolam",
                // The shipped catalog carries Xanax as an alias, and the override
                // table names only the canonical spelling.
                aliases = listOf("Xanax"),
                category = SubstanceCategory.BENZODIAZEPINE,
                routes = listOf(route(doses = DoseRange(heavy = 2.0), duration = acute())),
            ),
            substance(
                "Morphine",
                category = SubstanceCategory.OPIOID,
                routes = listOf(route(doses = DoseRange(heavy = 60.0), duration = acute())),
            ),
            substance(
                "Kratom",
                category = SubstanceCategory.OPIOID,
                routes = listOf(route(doses = DoseRange(heavy = 8.0), duration = acute())),
            ),
            substance(
                "Cannabis",
                category = SubstanceCategory.CANNABINOID,
                routes = listOf(route(doses = DoseRange(heavy = 20.0), duration = acute())),
            ),
            substance(
                "Caffeine",
                category = SubstanceCategory.STIMULANT,
                routes = listOf(route(doses = DoseRange(heavy = 400.0), duration = acute())),
            ),
            // A category the override table does not name at all — the unmapped
            // case, which must resolve to `.other` and therefore to no rule.
            substance("Mystery", category = SubstanceCategory.PEPTIDE),
            substance("Vitamin C", category = SubstanceCategory.SUPPLEMENT),
            // No acute duration and no dose ladder: the half-life branch of
            // `activeEntries` is the only thing that can read it.
            substance(
                "Lithium",
                category = SubstanceCategory.OTHER,
                routes = listOf(route()),
                halfLifeMinutes = 300.0,
            ),
            substance(
                "MDMA",
                aliases = listOf("Ecstasy"),
                routes = listOf(route(doses = DoseRange(heavy = 150.0), duration = acute())),
            ),
            substance("Phenelzine", category = SubstanceCategory.ANTIDEPRESSANT),
            substance("Sertraline", category = SubstanceCategory.ANTIDEPRESSANT),
            substance("Tramadol", category = SubstanceCategory.OPIOID),
            substance("Phenobarbital", category = SubstanceCategory.OTHER),
            substance("Alcohol", category = SubstanceCategory.OTHER, routes = listOf(route())),
            substance("Aripiprazole", category = SubstanceCategory.ANTIPSYCHOTIC),
            substance("Bupropion", category = SubstanceCategory.ANTIDEPRESSANT),
            substance("Codeine", category = SubstanceCategory.OPIOID),
            substance("Diphenhydramine", category = SubstanceCategory.ANTIHISTAMINE),
            substance("Ketamine", category = SubstanceCategory.DISSOCIATIVE),
            substance("Clarithromycin", category = SubstanceCategory.ANTIMICROBIAL),
            substance("Midazolam", category = SubstanceCategory.BENZODIAZEPINE),
        ),
    )

    private fun data(
        modulators: List<EnzymeModulator> = emptyList(),
        majors: Map<String, Set<Enzyme>> = emptyMap(),
        tagIndex: Map<String, Map<String, TagEnzymeInteraction>> = emptyMap(),
        pk: Map<String, List<PKInteractionHit>> = emptyMap(),
        extraRules: List<ClassInteractionRule> = emptyList(),
    ): InteractionData = FakeInteractionData(
        rules = rules + extraRules,
        overrides = overrides,
        categories = categories,
        modulators = modulators,
        majors = majors,
        tagIndex = tagIndex,
        pk = pk,
    )

    private fun checker(
        modulators: List<EnzymeModulator> = emptyList(),
        majors: Map<String, Set<Enzyme>> = emptyMap(),
        tagIndex: Map<String, Map<String, TagEnzymeInteraction>> = emptyMap(),
        pk: Map<String, List<PKInteractionHit>> = emptyMap(),
        extraRules: List<ClassInteractionRule> = emptyList(),
    ) = InteractionChecker(
        data(modulators, majors, tagIndex, pk, extraRules),
        catalog(),
    )

    private fun entry(
        name: String,
        amount: Double = 10.0,
        minutesAgo: Long = 0,
        route: RouteOfAdministration = RouteOfAdministration.ORAL,
    ): DoseRecord = dose(
        substance = name,
        amount = amount,
        route = route,
        timestamp = T0.minusSeconds(minutesAgo * 60),
    )

    /** A one-edge `tag_enzyme_interactions` index, keyed the way the reader keys it. */
    private fun tagIndex(
        perpetrator: String,
        victim: String,
        victimType: String,
        enzyme: String = "CYP2D6",
        direction: String = "inhibits",
        strength: String? = null,
    ): Map<String, Map<String, TagEnzymeInteraction>> = mapOf(
        perpetrator to mapOf(
            victim to TagEnzymeInteraction(
                perpetratorName = perpetrator.replaceFirstChar { it.uppercase() },
                victimName = victim.replaceFirstChar { it.uppercase() },
                enzyme = enzyme,
                direction = direction,
                strength = strength,
                victimType = victimType,
            ),
        ),
    )

    // MARK: - Severity

    @Test
    fun `severity orders caution below unsafe below dangerous`() {
        (InteractionSeverity.CAUTION < InteractionSeverity.UNSAFE) shouldBe true
        (InteractionSeverity.UNSAFE < InteractionSeverity.DANGEROUS) shouldBe true
        (InteractionSeverity.CAUTION < InteractionSeverity.DANGEROUS) shouldBe true
        (InteractionSeverity.DANGEROUS < InteractionSeverity.UNSAFE) shouldBe false
    }

    @Test
    fun `severity labels are the three English words`() {
        InteractionSeverity.CAUTION.label shouldBe "Caution"
        InteractionSeverity.UNSAFE.label shouldBe "Unsafe"
        InteractionSeverity.DANGEROUS.label shouldBe "Dangerous"
    }

    @Test
    fun `bundled severity names parse case-insensitively and an unknown word is null`() {
        InteractionSeverity.bundled("dangerous") shouldBe InteractionSeverity.DANGEROUS
        InteractionSeverity.bundled("UNSAFE") shouldBe InteractionSeverity.UNSAFE
        InteractionSeverity.bundled("Caution") shouldBe InteractionSeverity.CAUTION
        // Null rather than a default: a row filed under `caution` because its
        // severity word did not parse would turn an unread word into "mild".
        InteractionSeverity.bundled("Low Risk & Synergy") shouldBe null
    }

    @Test
    fun `only other and supplement are unruled`() {
        DrugClass.unruled shouldBe setOf(DrugClass.OTHER, DrugClass.SUPPLEMENT)
        DrugClass.fromRaw("alpha2Agonist") shouldBe DrugClass.ALPHA2_AGONIST
        DrugClass.fromRaw("notAClass") shouldBe null
    }

    // MARK: - Prominence and derived values

    @Test
    fun `prominence maps the three severities and demotes one step`() {
        fun result(severity: InteractionSeverity, relevance: Double) = InteractionResult(
            severity = severity,
            substanceA = "a",
            substanceB = "b",
            description = "d",
            overlapFactor = relevance,
        )

        result(InteractionSeverity.DANGEROUS, 1.0).prominence shouldBe InteractionProminence.BLOCKING
        result(InteractionSeverity.UNSAFE, 1.0).prominence shouldBe InteractionProminence.NOTABLE
        result(InteractionSeverity.CAUTION, 1.0).prominence shouldBe InteractionProminence.BACKGROUND

        // Good data showing the pair is unlikely demotes one step…
        result(InteractionSeverity.DANGEROUS, 0.1).prominence shouldBe InteractionProminence.NOTABLE
        result(InteractionSeverity.UNSAFE, 0.1).prominence shouldBe InteractionProminence.BACKGROUND
        // …and floors there rather than running off the ladder.
        result(InteractionSeverity.CAUTION, 0.1).prominence shouldBe InteractionProminence.BACKGROUND
    }

    @Test
    fun `displayScore is severity rank scaled by relevance`() {
        val low = InteractionResult(
            severity = InteractionSeverity.CAUTION,
            substanceA = "a",
            substanceB = "b",
            description = "d",
            overlapFactor = 0.5,
            doseFactor = 0.5,
        )
        low.relevance shouldBe 0.25
        // 0.25 is not *below* the low-relevance threshold, so this stays a
        // full-prominence caution.
        low.isLowRelevance shouldBe false
        low.displayScore shouldBe 0.25

        val quiet = low.copy(overlapFactor = 0.1)
        quiet.isLowRelevance shouldBe true
        quiet.displayScore shouldBe 0.05
    }

    @Test
    fun `mechanism reads the rule key rather than the prose`() {
        fun mechanism(key: String) = InteractionResult(
            severity = InteractionSeverity.CAUTION,
            substanceA = "a",
            substanceB = "b",
            description = "prose that mentions nothing",
            ruleKey = key,
        ).mechanism

        mechanism("benzodiazepine|opioid") shouldBe InteractionMechanism.RESPIRATORY_DEPRESSION
        mechanism("alcohol|opioid") shouldBe InteractionMechanism.RESPIRATORY_DEPRESSION
        mechanism("barbiturate|opioid") shouldBe InteractionMechanism.RESPIRATORY_DEPRESSION
        mechanism("maoi|serotonergic") shouldBe InteractionMechanism.SEROTONIN_TOXICITY
        mechanism("ssri|tca") shouldBe InteractionMechanism.SEROTONIN_TOXICITY
        mechanism("stimulant|stimulant") shouldBe InteractionMechanism.CARDIOVASCULAR
        mechanism("betaBlocker|stimulant") shouldBe InteractionMechanism.CARDIOVASCULAR
        mechanism("antihistamine|opioid") shouldBe InteractionMechanism.SEDATION
        mechanism("orexinAntagonist|alcohol") shouldBe InteractionMechanism.SEDATION
        mechanism("alpha2Agonist|opioid") shouldBe InteractionMechanism.SEDATION
        mechanism("cannabinoid|psychedelic") shouldBe InteractionMechanism.GENERIC
        mechanism("enzyme:bupropion|CYP2D6") shouldBe InteractionMechanism.ENZYME_METABOLIC
    }

    @Test
    fun `leadClause takes the em dash half, else the first sentence, else everything`() {
        fun lead(description: String) = InteractionResult(
            severity = InteractionSeverity.CAUTION,
            substanceA = "a",
            substanceB = "b",
            description = description,
        ).leadClause

        lead("Combined respiratory depression — the leading cause of overdose death.") shouldBe
            "Combined respiratory depression"
        // 23 of the 87 curated sentences have no em dash, so the first-sentence
        // fallback is load-bearing rather than defensive.
        lead(
            "Life-threatening respiratory depression. A barbiturate opens the GABA-A channel " +
                "directly rather than modulating it.",
        ) shouldBe "Life-threatening respiratory depression"
        // A sentence with no full stop inside it keeps its whole text.
        lead("Risk of serotonin syndrome and hypertensive crisis.") shouldBe
            "Risk of serotonin syndrome and hypertensive crisis"
        lead("No punctuation at all") shouldBe "No punctuation at all"
    }

    @Test
    fun `partitionedForReview folds only past two findings`() {
        fun result(prominenceSeverity: InteractionSeverity, relevance: Double = 1.0) = InteractionResult(
            severity = prominenceSeverity,
            substanceA = "a",
            substanceB = "b",
            description = "d",
            overlapFactor = relevance,
        )

        val two = listOf(result(InteractionSeverity.CAUTION), result(InteractionSeverity.CAUTION))
        val (shownTwo, foldedTwo) = two.partitionedForReview()
        shownTwo shouldHaveSize 2
        foldedTwo.shouldBeEmpty()

        val three = two + result(InteractionSeverity.DANGEROUS)
        val (shownThree, foldedThree) = three.partitionedForReview()
        shownThree shouldHaveSize 1
        foldedThree shouldHaveSize 2
        three.belowFloor(InteractionProminence.NOTABLE) shouldBe 2
        three.admitted(InteractionProminence.NOTABLE) shouldHaveSize 1
    }

    // MARK: - Pair keys and rule lookup

    @Test
    fun `pair key is order-independent and lowercased`() {
        val engine = checker()
        engine.pairKey("Alprazolam", "Morphine") shouldBe "alprazolam|morphine"
        engine.pairKey("Morphine", "ALPRAZOLAM") shouldBe "alprazolam|morphine"
    }

    @Test
    fun `worstRule takes the highest severity across every class combination`() {
        val engine = checker()
        val worst = engine.worstRule(
            listOf(DrugClass.OPIOID),
            listOf(DrugClass.BENZODIAZEPINE, DrugClass.STIMULANT),
        )
        worst shouldNotBe null
        worst!!.severity shouldBe InteractionSeverity.DANGEROUS
        worst.key shouldBe "benzodiazepine|opioid"

        // No rule between two classes that never meet.
        engine.worstRule(listOf(DrugClass.CANNABINOID), listOf(DrugClass.SSRI)) shouldBe null
    }

    @Test
    fun `merge keeps the highest display score per pair`() {
        val engine = checker()
        val byPair = mutableMapOf<String, InteractionResult>()
        val dangerous = InteractionResult(
            InteractionSeverity.DANGEROUS, "Kratom", "Alprazolam", "dangerous",
        )
        val caution = InteractionResult(
            InteractionSeverity.CAUTION, "Alprazolam", "Kratom", "caution",
        )

        engine.merge(dangerous, byPair)
        engine.merge(caution, byPair)
        byPair.entries.size shouldBe 1
        byPair.values.first().severity shouldBe InteractionSeverity.DANGEROUS
        // Re-merging the same values must not flip the winner either.
        engine.merge(caution, byPair)
        byPair.values.first().severity shouldBe InteractionSeverity.DANGEROUS
    }

    @Test
    fun `hasAnyRule is false for the unruled classes`() {
        val engine = checker()
        engine.hasAnyRule(DrugClass.OPIOID) shouldBe true
        engine.hasAnyRule(DrugClass.SUPPLEMENT) shouldBe false
        engine.hasAnyRule(DrugClass.OTHER) shouldBe false
    }

    @Test
    fun `a pair written twice back-to-front is reported as a duplicate key`() {
        checker().duplicateRuleKeys.shouldBeEmpty()

        val duplicated = checker(
            extraRules = listOf(ClassInteractionRule("opioid", "benzodiazepine", "caution", "again")),
        )
        // `UNIQUE (class_a, class_b)` is on the ordered tuple, so the same pair
        // written the other way round satisfies it and then decides by row order.
        duplicated.duplicateRuleKeys shouldBe listOf("benzodiazepine|opioid")
    }

    // MARK: - Drug class resolution

    @Test
    fun `an override wins and is case-insensitive`() {
        val engine = checker()
        engine.drugClasses("Tramadol") shouldBe listOf(DrugClass.OPIOID, DrugClass.SEROTONERGIC)
        engine.drugClasses("tramadol") shouldBe listOf(DrugClass.OPIOID, DrugClass.SEROTONERGIC)
        engine.drugClasses("TRAMADOL") shouldBe listOf(DrugClass.OPIOID, DrugClass.SEROTONERGIC)
    }

    @Test
    fun `an alias resolves through the catalog to the canonical override`() {
        // Xanax is not in the override table; Alprazolam is. The catalog resolves
        // the alias, and the override is re-checked under the canonical name.
        checker().drugClasses("Xanax") shouldBe listOf(DrugClass.BENZODIAZEPINE)
    }

    @Test
    fun `a substance the override table omits falls back to its category`() {
        checker().drugClasses("Caffeine") shouldBe listOf(DrugClass.STIMULANT)
    }

    @Test
    fun `a category the fallback table does not name becomes other`() {
        // `.other` participates in no rule, so this substance is
        // interaction-invisible — which is what "unmapped" means.
        checker().drugClasses("Mystery") shouldBe listOf(DrugClass.OTHER)
    }

    @Test
    fun `an unknown substance resolves to nothing, repeatedly`() {
        val engine = checker()
        engine.drugClasses("zzzNotARealDrugzzz").shouldBeEmpty()
        // A miss is not memoized, so a later lookup is not poisoned by it — and
        // two misses in a row are stable.
        engine.drugClasses("zzzNotARealDrugzzz").shouldBeEmpty()
    }

    @Test
    fun `invalidating the class cache keeps the same answers`() {
        val engine = checker()
        engine.drugClasses("Tramadol") shouldBe listOf(DrugClass.OPIOID, DrugClass.SEROTONERGIC)
        engine.invalidateClassCache()
        engine.drugClasses("Tramadol") shouldBe listOf(DrugClass.OPIOID, DrugClass.SEROTONERGIC)
    }

    // MARK: - check()

    @Test
    fun `a dangerous opioid plus benzo is found and ranks first`() {
        val results = checker().check("Alprazolam", against = listOf(entry("Morphine")), now = T0)
        results shouldHaveSize 1
        results.first().severity shouldBe InteractionSeverity.DANGEROUS
        results.first().ruleKey shouldBe "benzodiazepine|opioid"
        results.first().prominence shouldBe InteractionProminence.BLOCKING
        // The copy table's own sentence, not the row's note.
        results.first().description shouldBe
            "Combined respiratory depression — the leading cause of overdose death."
    }

    @Test
    fun `the check is symmetric`() {
        val engine = checker()
        val ab = engine.check("Alprazolam", against = listOf(entry("Morphine")), now = T0)
        val ba = engine.check("Morphine", against = listOf(entry("Alprazolam")), now = T0)
        ab.first().severity shouldBe ba.first().severity
    }

    @Test
    fun `a dose does not interact with itself`() {
        checker().check("Morphine", against = listOf(entry("Morphine")), now = T0).shouldBeEmpty()
    }

    @Test
    fun `two unruled substances produce nothing`() {
        checker().check("Vitamin C", against = listOf(entry("Mystery")), now = T0).shouldBeEmpty()
    }

    @Test
    fun `an unknown prospective substance produces nothing`() {
        checker().check("zzzNothingzzz", against = listOf(entry("Morphine")), now = T0).shouldBeEmpty()
    }

    @Test
    fun `a serotonergic opioid still raises a serotonin rule an ordinary opioid does not`() {
        val engine = checker()
        // Tramadol rides `.serotonergic` on top of `.opioid`, so the
        // `.serotonergic + .ssri` rule fires where plain morphine's does not.
        engine.check("Tramadol", against = listOf(entry("Sertraline")), now = T0)
            .any { it.severity == InteractionSeverity.DANGEROUS } shouldBe true
        engine.check("Morphine", against = listOf(entry("Sertraline")), now = T0).shouldBeEmpty()
    }

    // MARK: - checkBatch()

    @Test
    fun `the explorer returns the batch pair at its base severity`() {
        val results = checker().checkBatch(
            listOf("Morphine", "Alprazolam"),
            against = emptyList(),
            policy = InteractionPolicy.EXPLORE,
        )
        val pair = results.first { it.severity == InteractionSeverity.DANGEROUS }
        // Co-administered by construction, so both factors are 1 and the score
        // is the bare severity rank.
        pair.overlapFactor shouldBe 1.0
        pair.doseFactor shouldBe 1.0
        pair.displayScore shouldBe 3.0
    }

    @Test
    fun `two rules sharing boilerplate prose stay distinguishable by rule key`() {
        // `alcohol + antipsychotic` and `barbiturate + antipsychotic` are written
        // against the same sentence. A surface that groups on prose folds them
        // into one finding and asserts a cause they do not share.
        val results = checker().checkBatch(
            listOf("Phenobarbital", "Alcohol", "Aripiprazole"),
            against = emptyList(),
            policy = InteractionPolicy.EXPLORE,
        )
        val shared = results.filter { it.description.contains("Additive CNS depression") }
        shared shouldHaveSize 2
        shared.map { it.ruleKey }.toSet() shouldHaveSize 2
        shared.all { it.ruleKey.isNotEmpty() } shouldBe true
    }

    @Test
    fun `batch results are ordered by display score`() {
        val results = checker().checkBatch(
            listOf("Morphine", "Alprazolam", "Cannabis"),
            against = emptyList(),
            policy = InteractionPolicy.EXPLORE,
        )
        (results.size >= 2) shouldBe true
        results.zipWithNext().forEach { (first, second) ->
            (first.displayScore >= second.displayScore) shouldBe true
        }
        results.first().severity shouldBe InteractionSeverity.DANGEROUS
    }

    // MARK: - Relevance gating

    @Test
    fun `a faded morning dose does not red-flag tonight's benzo`() {
        // Both curves run 180 minutes; a dose 12 hours ago ended nine hours
        // before the prospective one starts, so they never co-occur.
        val engine = checker()
        engine.check("Alprazolam", against = listOf(entry("Kratom", amount = 5.0, minutesAgo = 12 * 60)), now = T0)
            .shouldBeEmpty()
    }

    @Test
    fun `a concurrent pair still warns`() {
        val results = checker().check(
            "Alprazolam",
            against = listOf(entry("Kratom", amount = 5.0, minutesAgo = 0)),
            now = T0,
        )
        results.any { it.severity == InteractionSeverity.DANGEROUS } shouldBe true
        // Two identical curves starting together overlap at their full height.
        results.first { it.severity == InteractionSeverity.DANGEROUS }.overlapFactor shouldBe
            (1.0 plusOrMinus 0.001)
    }

    @Test
    fun `a clearly sub-threshold active dose is suppressed`() {
        // 0.0001 mg alprazolam against a 2 mg heavy reference is nothing: the
        // *interaction* is irrelevant even though both are present now.
        val engine = checker()
        engine.check("Kratom", against = listOf(entry("Alprazolam", amount = 0.0001)), now = T0)
            .shouldBeEmpty()
        // The same pair at a real dose does warn.
        engine.check("Kratom", against = listOf(entry("Alprazolam", amount = 1.8)), now = T0)
            .any { it.severity == InteractionSeverity.DANGEROUS } shouldBe true
    }

    @Test
    fun `a persistent edge outlasts its effect curve`() {
        // Irreversible MAO inhibition is lethal days after the felt effects fade,
        // so the temporal gate must not silence it.
        val results = checker().check("MDMA", against = listOf(entry("Phenelzine", minutesAgo = 24 * 60)), now = T0)
        results.any { it.severity == InteractionSeverity.DANGEROUS } shouldBe true
    }

    @Test
    fun `the explorer never suppresses what warn mode hides`() {
        val engine = checker()
        val faded = listOf(entry("Kratom", amount = 5.0, minutesAgo = 12 * 60))
        engine.checkBatch(listOf("Alprazolam"), against = faded, policy = InteractionPolicy.WARN).shouldBeEmpty()
        engine.checkBatch(listOf("Alprazolam"), against = faded, policy = InteractionPolicy.EXPLORE).isEmpty() shouldBe
            false
    }

    @Test
    fun `warn surfaces rank by relevance, not by raw severity`() {
        val results = checker().check(
            "Morphine",
            against = listOf(
                entry("Alprazolam", amount = 1.8, minutesAgo = 0),
                entry("Cannabis", amount = 20.0, minutesAgo = 0),
            ),
            now = T0,
        )
        results.zipWithNext().forEach { (first, second) ->
            (first.displayScore >= second.displayScore) shouldBe true
        }
        results.first().severity shouldBe InteractionSeverity.DANGEROUS
    }

    // MARK: - Layer 2: enzyme interactions

    private val bupropion = EnzymeModulator(
        id = "bupropion",
        origin = ModulatorOrigin.SUBSTANCE,
        enzyme = Enzyme.CYP2D6,
        direction = ModulationDirection.INHIBITS,
        strength = ModulationStrength.STRONG,
        confidence = ConfidenceTier.HIGH,
        matchers = listOf("bupropion", "wellbutrin"),
        displayName = "Bupropion",
        userNote = "Bupropion strongly inhibits CYP2D6, raising the levels of drugs cleared by it.",
    )

    @Test
    fun `a curated modulator fires against a substrate it clears`() {
        val results = checker(modulators = listOf(bupropion), majors = mapOf("codeine" to setOf(Enzyme.CYP2D6)))
            .checkBatch(listOf("Bupropion", "Codeine"), against = emptyList(), policy = InteractionPolicy.EXPLORE)

        val enzyme = results.firstOrNull { it.ruleKey.startsWith("enzyme:") }
        enzyme shouldNotBe null
        // The enzyme layer is capped at `unsafe`: the table assigns no severity
        // and the app must not manufacture one.
        enzyme!!.severity shouldBe InteractionSeverity.UNSAFE
        enzyme.ruleKey shouldBe "enzyme:bupropion|CYP2D6"
        enzyme.description shouldBe bupropion.userNote
        enzyme.mechanism shouldBe InteractionMechanism.ENZYME_METABOLIC
    }

    @Test
    fun `a modulator present only under an alias still fires`() {
        val results = checker(
            modulators = listOf(bupropion),
            majors = mapOf("codeine" to setOf(Enzyme.CYP2D6)),
        ).checkBatch(listOf("Wellbutrin", "Codeine"), against = emptyList(), policy = InteractionPolicy.EXPLORE)
        // "Wellbutrin" is a matcher, so the modulator is present; the display name
        // is the modulator's own label rather than the typed spelling.
        results.any { it.ruleKey == "enzyme:bupropion|CYP2D6" } shouldBe true
    }

    @Test
    fun `tag-derived effects are added when no class rule covers the pair`() {
        val engine = checker(tagIndex = tagIndex("diphenhydramine", "codeine", victimType = "substrate"))

        // The pair's classes have no rule between them in this fixture, so the
        // enzyme effect is simply added.
        val added = engine.checkBatch(listOf("Diphenhydramine", "Codeine"), emptyList(), InteractionPolicy.EXPLORE)
        added shouldHaveSize 1
        val enzyme = added.first()
        enzyme.ruleKey shouldBe "enzyme:diphenhydramine|CYP2D6"
        // strength is null on this row, so the template leaves the adverb out.
        enzyme.description shouldBe
            "Diphenhydramine inhibits CYP2D6, raising the levels of drugs cleared by it."
    }

    @Test
    fun `an enzyme effect replaces a class rule it outranks`() {
        // `antihistamine + opioid` is a real `caution` row, and Diphenhydramine +
        // Codeine carry those classes. The enzyme effect is strictly more
        // specific, so it takes the pair and the generic row goes.
        val engine = checker(tagIndex = tagIndex("diphenhydramine", "codeine", victimType = "substrate"))

        val results = engine.checkBatch(listOf("Diphenhydramine", "Codeine"), emptyList(), InteractionPolicy.EXPLORE)
        results.none { it.ruleKey == "antihistamine|opioid" } shouldBe true
        results.count { it.ruleKey == "enzyme:diphenhydramine|CYP2D6" } shouldBe 1
    }

    @Test
    fun `an enzyme effect never downgrades a dangerous class rule`() {
        // The enzyme result is capped at `unsafe`, so replacing a `dangerous` row
        // with it would downgrade the worst thing the pair shows.
        val engine = checker(
            tagIndex = tagIndex("phenobarbital", "morphine", victimType = "substrate", enzyme = "CYP3A4"),
        )

        val results = engine.checkBatch(listOf("Phenobarbital", "Morphine"), emptyList(), InteractionPolicy.EXPLORE)
        val dangerous = results.firstOrNull { it.ruleKey == "barbiturate|opioid" }
        dangerous shouldNotBe null
        dangerous!!.severity shouldBe InteractionSeverity.DANGEROUS
        // …and the enzyme effect is still reported beside it, under its own key.
        results.any { it.ruleKey == "enzyme:phenobarbital|CYP3A4" } shouldBe true
    }

    @Test
    fun `a prodrug victim gets the activation-blocking sentence`() {
        val engine = checker(
            tagIndex = mapOf(
                "diphenhydramine" to mapOf(
                    "codeine" to TagEnzymeInteraction(
                        perpetratorName = "Diphenhydramine",
                        victimName = "Codeine",
                        enzyme = "CYP2D6",
                        direction = "inhibits",
                        strength = "strong",
                        victimType = "prodrug",
                    ),
                ),
            ),
        )
        val results = engine.checkBatch(listOf("Diphenhydramine", "Codeine"), emptyList(), InteractionPolicy.EXPLORE)
        val enzyme = results.first { it.ruleKey.startsWith("enzyme:") }
        enzyme.description shouldBe
            "Diphenhydramine strongly inhibits CYP2D6, " +
            "blocking the activation pathway for prodrugs that depend on it."
    }

    @Test
    fun `an induction reads as lowering levels`() {
        val engine = checker(
            tagIndex = mapOf(
                "bupropion" to mapOf(
                    "codeine" to TagEnzymeInteraction(
                        perpetratorName = "Bupropion",
                        victimName = "Codeine",
                        enzyme = "CYP3A4",
                        direction = "induces",
                        strength = "moderate",
                        victimType = "substrate",
                    ),
                ),
            ),
        )
        val results = engine.checkBatch(listOf("Bupropion", "Codeine"), emptyList(), InteractionPolicy.EXPLORE)
        results.first { it.ruleKey.startsWith("enzyme:") }.description shouldBe
            "Bupropion moderately induces CYP3A4, lowering the levels of drugs cleared by it."
    }

    // MARK: - Pharmacokinetic interactions

    private val ketamineByClarithromycin = PKInteractionHit(
        id = 7L,
        withSubstance = "clarithromycin / itraconazole",
        mechanism = "CYP3A4 inhibition",
        kiMicromolar = 1.5,
        labeledEffect = "AUC increased ~2.6x",
        sourceSlug = "dailymed",
    )

    private val ketamineByClass = PKInteractionHit(
        id = 8L,
        withSubstance = "CYP3A4 inhibitors (azoles, macrolides)",
        mechanism = "CYP3A4 inhibition",
        sourceSlug = "dailymed",
    )

    @Test
    fun `a counterpart name is split on the slash and trimmed`() {
        ketamineByClarithromycin.counterpartNames shouldBe listOf("clarithromycin", "itraconazole")
    }

    @Test
    fun `a class-named counterpart matches nothing, while a real drug connects both directions`() {
        val engine = checker(
            pk = mapOf(
                "ketamine" to listOf(ketamineByClarithromycin, ketamineByClass),
                "midazolam" to listOf(
                    PKInteractionHit(
                        id = 9L,
                        withSubstance = "ketamine",
                        mechanism = "CYP3A4 inhibition",
                        sourceSlug = "peer-review-primary",
                    ),
                ),
            ),
        )

        // Ketamine's own row names clarithromycin, which the catalog carries.
        val direct = engine.pharmacokineticInteractions("Ketamine", listOf(entry("Clarithromycin")))
        direct.map { it.id } shouldBe listOf(7L)

        // …and midazolam's row names ketamine, so the read is symmetric.
        val reverse = engine.pharmacokineticInteractions("Ketamine", listOf(entry("Midazolam")))
        reverse.map { it.id } shouldBe listOf(9L)

        // With nothing logged that resolves, there is nothing to match.
        engine.pharmacokineticInteractions("Ketamine", emptyList()).shouldBeEmpty()
        // A subject the catalog does not carry cannot be matched either.
        engine.pharmacokineticInteractions("zzzNotARealdrugzzz", listOf(entry("Clarithromycin"))).shouldBeEmpty()
    }

    @Test
    fun `an unresolved counterpart is skipped rather than substring matched`() {
        // "CYP3A4 inhibitors (azoles, macrolides)" names a class, not a drug. The
        // row still renders on the substance's own page; it must not silently
        // connect to a logged azole.
        val engine = checker(pk = mapOf("ketamine" to listOf(ketamineByClass)))
        engine.pharmacokineticInteractions("Ketamine", listOf(entry("Clarithromycin"))).shouldBeEmpty()
    }

    // MARK: - Active entry detection

    @Test
    fun `an entry inside its duration is active and a week-old one is not`() {
        val engine = checker()
        engine.activeEntries(listOf(entry("Caffeine", minutesAgo = 1)), now = T0) shouldHaveSize 1
        engine.activeEntries(listOf(entry("Caffeine", minutesAgo = 7 * 24 * 60)), now = T0).shouldBeEmpty()
        engine.activeEntries(emptyList(), now = T0).shouldBeEmpty()

        val active = engine.activeEntries(
            listOf(entry("Caffeine", minutesAgo = 1), entry("Caffeine", minutesAgo = 7 * 24 * 60)),
            now = T0,
        )
        active shouldHaveSize 1
    }

    @Test
    fun `a substance with no duration falls back to five half-lives`() {
        val engine = checker()
        // 300-minute half-life → a 1500-minute window.
        engine.activeEntries(listOf(entry("Lithium", minutesAgo = 20 * 60)), now = T0) shouldHaveSize 1
        engine.activeEntries(listOf(entry("Lithium", minutesAgo = 30 * 60)), now = T0).shouldBeEmpty()
    }

    @Test
    fun `an unknown substance is assumed active for a day`() {
        val engine = checker()
        engine.activeEntries(listOf(entry("zzzNothingzzz", minutesAgo = 60)), now = T0) shouldHaveSize 1
        engine.activeEntries(listOf(entry("zzzNothingzzz", minutesAgo = 2 * 24 * 60)), now = T0).shouldBeEmpty()
    }
}

/**
 * [InteractionData] over maps.
 *
 * The checker reads seven things; a test supplies only the two or three it is
 * about, and the rest default to empty — which is itself the honest answer for a
 * table the test did not populate, not a stub.
 */
private class FakeInteractionData(
    private val rules: List<ClassInteractionRule> = emptyList(),
    private val overrides: Map<String, List<DrugClass>> = emptyMap(),
    private val categories: Map<String, DrugClass> = emptyMap(),
    private val modulators: List<EnzymeModulator> = emptyList(),
    private val majors: Map<String, Set<Enzyme>> = emptyMap(),
    private val tagIndex: Map<String, Map<String, TagEnzymeInteraction>> = emptyMap(),
    private val pk: Map<String, List<PKInteractionHit>> = emptyMap(),
) : InteractionData {

    override fun classRules(): List<ClassInteractionRule> = rules

    override fun interactionClasses(): Map<String, List<DrugClass>> = overrides

    override fun categoryInteractionClasses(): Map<String, DrugClass> = categories

    override fun enzymeModulators(): List<EnzymeModulator> = modulators

    override fun majorEnzymes(substanceName: String): Set<Enzyme> =
        majors[substanceName.lowercase()].orEmpty()

    override fun tagEnzymeInteractions(): Map<String, Map<String, TagEnzymeInteraction>> = tagIndex

    override fun pharmacokineticInteractions(substanceName: String): List<PKInteractionHit> =
        pk[substanceName.lowercase()].orEmpty()
}
