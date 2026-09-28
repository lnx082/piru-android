package glass.kagerou.piru.engine

import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.model.SubstanceCategory
import java.time.Duration
import java.time.Instant

/*
 * Ported from `Piru/Data/Services/Interactions.swift` (957 lines) in the iOS
 * target, plus the two satellites it is meaningless without:
 *
 *  - `Piru/Data/Services/InteractionRuleCopy.swift` — the localized sentence per
 *    class pair, reproduced here as a Kotlin table ([InteractionRuleCopy]).
 *  - `Piru/Data/SubstanceDB/SubstanceReadModel+PKInteractions.swift` — the
 *    `drug_interactions_pk` row shape ([PKInteractionHit]).
 *
 * The enzyme half of the engine lives beside this in `EnzymeInteractions.kt`,
 * ported from `Piru/Data/Pharmacology/MetabolicModulation.swift`.
 *
 * ## The one structural change, and why
 * Upstream reaches its data through `SubstanceStore.shared` — a singleton — and
 * guards the two memo tables with `OSAllocatedUnfairLock` because Swift Testing
 * runs its suites concurrently. This module is a pure JVM library with no
 * database and no singletons, so the data arrives through [InteractionData] and
 * [`SubstanceCatalog`] and the checker is an ordinary object. That is the same
 * seam the rest of the module already uses, and it is what lets the rules below
 * be pinned in milliseconds against invented substances instead of against a
 * 1,688-row catalog.
 *
 * The consequence to be aware of: **one checker instance is not thread-safe.**
 * Upstream's statics are. A caller that shares one across threads must
 * synchronize; the two caches are ordinary `MutableMap`s.
 */

// MARK: - Interaction Severity

/**
 * How bad a pairing is, in three ordered steps.
 *
 * `CaseIterable` upstream exists so a contrast gate can enumerate the cases
 * rather than be handed a hand-written list. Kotlin's [entries] gives the same
 * property for free.
 */
enum class InteractionSeverity(val raw: Int) {
    CAUTION(0),
    UNSAFE(1),
    DANGEROUS(2),
    ;

    /** The English label. The localized label lives with the app's resources. */
    val label: String
        get() = when (this) {
            CAUTION -> "Caution"
            UNSAFE -> "Unsafe"
            DANGEROUS -> "Dangerous"
        }

    companion object {
        /**
         * Parse the bundled database's lowercase severity name, or null for a
         * value this ladder has no rung for.
         *
         * Null rather than a default: a row whose severity cannot be read is a
         * row the checker must drop, because the alternative is filing it under
         * `caution` and having an unknown word silently mean "mild".
         */
        fun bundled(name: String): InteractionSeverity? = when (name.lowercase()) {
            "dangerous" -> DANGEROUS
            "unsafe" -> UNSAFE
            "caution" -> CAUTION
            else -> null
        }
    }
}

// MARK: - Drug Class (for interaction matching)

/**
 * The pharmacological classes `interaction_rules` is written against.
 *
 * [raw] is the string the bundled database and the curated JSON carry.
 */
enum class DrugClass(val raw: String) {
    OPIOID("opioid"),
    BENZODIAZEPINE("benzodiazepine"),

    /**
     * Barbiturates (phenobarbital, pentobarbital, secobarbital, thiopental, …) and
     * primidone, which is metabolized to phenobarbital. Separate from
     * [BENZODIAZEPINE] because the mechanism differs where it matters most: a
     * benzodiazepine only *modulates* GABA-A and its depression therefore
     * plateaus, while a barbiturate opens the chloride channel directly and keeps
     * going. That missing ceiling is why barbiturate + benzodiazepine is
     * `dangerous` here where benzodiazepine × benzodiazepine is `unsafe`.
     */
    BARBITURATE("barbiturate"),

    STIMULANT("stimulant"),
    PSYCHEDELIC("psychedelic"),
    DISSOCIATIVE("dissociative"),
    EMPATHOGEN("empathogen"),
    CANNABINOID("cannabinoid"),
    GABAPENTINOID("gabapentinoid"),
    ALCOHOL("alcohol"),
    GHB("ghb"),

    /**
     * Dual orexin receptor antagonists (suvorexant, lemborexant, daridorexant —
     * the modern DORA sleep meds). They block OX1R/OX2R rather than enhancing
     * GABA, so they carry additive next-day sedation / psychomotor / fall risk
     * with other CNS depressants but — critically — do NOT add brainstem
     * respiratory depression. Hence only `caution` rules, never the
     * benzo/opioid respiratory-synergy danger tier.
     */
    OREXIN_ANTAGONIST("orexinAntagonist"),

    ANTIHISTAMINE("antihistamine"),
    MAOI("maoi"),
    SSRI("ssri"),
    SNRI("snri"),
    TCA("tca"),

    /**
     * Serotonin-*adding* agents with a genuine additive serotonin-toxicity risk —
     * releasers or reuptake inhibitors that are NOT therapeutic antidepressant
     * SERT blockers (which blunt empathogens). Tramadol, meperidine/pethidine and
     * dextromethorphan: they *stack* serotonergic load rather than competing it
     * away, so they get danger rules, not the antidepressant blunting readout.
     */
    SEROTONERGIC("serotonergic"),

    LITHIUM("lithium"),
    ANTIPSYCHOTIC("antipsychotic"),

    /**
     * Centrally-acting alpha-2 adrenergic agonists (clonidine, guanfacine,
     * tizanidine, dexmedetomidine, lofexidine; the xylazine/medetomidine "tranq"
     * adulterants). Additive sedation/bradycardia/hypotension; the alpha-2
     * component is NOT reversed by naloxone.
     */
    ALPHA2_AGONIST("alpha2Agonist"),

    /**
     * Beta-adrenergic antagonists (propranolol, metoprolol, atenolol, carvedilol,
     * …). The real "unopposed alpha" edge is alpha-2-agonist *withdrawal* while
     * beta-blocked, NOT routine beta-blocker + stimulant.
     */
    BETA_BLOCKER("betaBlocker"),

    /**
     * Vitamins, minerals, amino acids, herbal preparations. Like [OTHER] it
     * appears in **no rule**, so every substance routed here is
     * interaction-invisible. Kept as its own case rather than folded into
     * [OTHER] because the rules it is missing are real and specific (5-HTP with a
     * serotonergic; St John's Wort inducing CYP3A4; grapefruit inhibiting it).
     */
    SUPPLEMENT("supplement"),

    OTHER("other"),
    ;

    companion object {
        /**
         * The classes no rule mentions. Everything routed to one of these returns
         * nothing for every pairing.
         *
         * Pinned by a test rather than derived, so a class cannot silently join
         * the invisible set when the database is rebuilt.
         */
        val unruled: Set<DrugClass> = setOf(OTHER, SUPPLEMENT)

        private val byRaw: Map<String, DrugClass> = entries.associateBy { it.raw }

        /**
         * Parse a class string from the bundled database, or null when this build
         * does not know it.
         *
         * Null is load-bearing: a class the app cannot name is one whose rules it
         * cannot rank, and inventing `other` for it would file a real rule as
         * interaction-invisible.
         */
        fun fromRaw(raw: String): DrugClass? = byRaw[raw]
    }
}

// MARK: - Interaction Mechanism

/**
 * The pharmacological mechanism category behind a warning — drives the icon
 * shown beside it so a reader can tell respiratory depression apart from
 * serotonin toxicity at a glance.
 *
 * Icons are SF Symbols names upstream; the Android icon set maps them at the
 * presentation layer, so the names are carried through unchanged.
 */
enum class InteractionMechanism(val iconName: String, val filledIconName: String) {
    RESPIRATORY_DEPRESSION("lungs.fill", "lungs.fill"),
    SEROTONIN_TOXICITY("brain.head.profile", "brain.head.profile.fill"),
    CARDIOVASCULAR("heart.fill", "heart.fill"),
    SEIZURE("bolt.fill", "bolt.fill"),
    ENZYME_METABOLIC("arrow.triangle.2.circlepath", "arrow.triangle.2.circlepath"),
    SEDATION("moon.fill", "moon.fill"),
    GENERIC("exclamationmark.triangle", "exclamationmark.triangle.fill"),
}

// MARK: - Interaction Result

/** One severity-ranked finding: what the pair is, how bad, and why. */
data class InteractionResult(
    val severity: InteractionSeverity,
    val substanceA: String,
    val substanceB: String,
    val description: String,

    /**
     * The class pair whose rule produced this warning — the identity a display
     * surface groups on. Empty when the result was made by hand (tests, previews)
     * rather than by a rule firing, and `enzyme:<id>|<enzyme>` for the metabolic
     * layer.
     */
    val ruleKey: String = "",

    /**
     * Temporal effect-curve overlap of the two doses, `[0, 1]`. The peak of the
     * product of both doses' subjective-effect curves over wall-clock time. `1`
     * when no timestamps were available — i.e. "unknown, treat as concurrent".
     */
    val overlapFactor: Double = 1.0,

    /**
     * Combined dose-presence weight, `[0, 1]`. The product of each participant's
     * presence. `1` when the amounts are unknown.
     */
    val doseFactor: Double = 1.0,
) {

    /**
     * Relevance-weighted score used to order warnings and, on warn surfaces, to
     * decide what to hide.
     *
     * With no temporal/dose data both factors are `1`, so this collapses back to
     * the base severity rank and ordering is unchanged.
     */
    val displayScore: Double
        get() = (severity.raw + 1).toDouble() * relevance

    /** `overlapFactor · doseFactor` — how much this pair actually matters here, independent of base severity. */
    val relevance: Double
        get() = overlapFactor * doseFactor

    /** `true` when good data shows the pair is unlikely to matter at these doses/timing. */
    val isLowRelevance: Boolean
        get() = relevance < LOW_RELEVANCE_THRESHOLD

    /**
     * The mechanism in the fewest words that still say what happens.
     *
     * Every rule's description is written the same way: the effect, an em dash,
     * then the elaboration. The mapping below reads the *rule key* rather than
     * the prose, so it survives a copy change.
     */
    val mechanism: InteractionMechanism
        get() {
            if (ruleKey.startsWith("enzyme:")) return InteractionMechanism.ENZYME_METABOLIC
            val key = ruleKey.lowercase()
            if (key.contains("opioid") && (
                    key.contains("benzo") || key.contains("barbiturate") ||
                        key.contains("alcohol") || key.contains("ghb") ||
                        key.contains("gabapentinoid")
                    )
            ) {
                return InteractionMechanism.RESPIRATORY_DEPRESSION
            }
            if (key.contains("maoi") || key.contains("serotonergic") || key.contains("ssri") ||
                key.contains("snri") || key.contains("tca") || key.contains("lithium")
            ) {
                return InteractionMechanism.SEROTONIN_TOXICITY
            }
            if (key.contains("stimulant") && (key.contains("stimulant") || key.contains("beta"))) {
                return InteractionMechanism.CARDIOVASCULAR
            }
            if (key.contains("antihistamine") || key.contains("orexin") ||
                (key.contains("alpha2") && !key.contains("stimulant"))
            ) {
                return InteractionMechanism.SEDATION
            }
            return InteractionMechanism.GENERIC
        }

    /** Everything before the em dash, or the first sentence. */
    val leadClause: String
        get() {
            val dash = description.indexOf(" — ")
            if (dash >= 0) return description.substring(0, dash)
            val stop = description.indexOf('.')
            if (stop >= 0) return description.substring(0, stop)
            return description
        }

    /**
     * How loudly this finding has earned the right to arrive.
     *
     * Severity says how bad a pairing is. Prominence says whether it may
     * interrupt. Two stimulants is a real `caution` — combined cardiovascular
     * strain, worth knowing — but arriving in the same red, at the same size, at
     * the moment someone logs a coffee is how a person learns to dismiss the row
     * that says opioid + benzodiazepine.
     */
    val prominence: InteractionProminence
        get() {
            val base = when (severity) {
                InteractionSeverity.DANGEROUS -> InteractionProminence.BLOCKING
                InteractionSeverity.UNSAFE -> InteractionProminence.NOTABLE
                InteractionSeverity.CAUTION -> InteractionProminence.BACKGROUND
            }
            return if (isLowRelevance) base.demoted else base
        }

    companion object {
        /**
         * Below this relevance (`overlap · dose`) the explorer marks a pair as
         * low-likelihood; warn surfaces suppress earlier, on the confident-low
         * signals in the checker's gate.
         */
        const val LOW_RELEVANCE_THRESHOLD: Double = 0.25
    }
}

/**
 * Where a finding is allowed to appear. Ordered, so a surface can take a floor.
 */
enum class InteractionProminence(val raw: Int) {
    /** True, and low-stakes here. Belongs in the explorer and in a count. */
    BACKGROUND(0),

    /** Worth reading before continuing. */
    NOTABLE(1),

    /** Worth stopping for. */
    BLOCKING(2),
    ;

    /** One step down, flooring at [BACKGROUND] rather than running off the end. */
    val demoted: InteractionProminence
        get() = entries.getOrElse(raw - 1) { BACKGROUND }
}

/** The findings this surface admits, most prominent first is the caller's job — this only filters. */
fun List<InteractionResult>.admitted(floor: InteractionProminence): List<InteractionResult> =
    filter { it.prominence >= floor }

/** How many fall below the floor — the number behind "N more". */
fun List<InteractionResult>.belowFloor(floor: InteractionProminence): Int =
    count { it.prominence < floor }

/**
 * The split between what a review surface shows and what it folds.
 *
 * Folding buys nothing when there is almost nothing to fold: one or two findings
 * fit, whatever they are, and hiding one behind a disclosure that says "1 more"
 * costs a tap and reads as an error. Past that, the quiet ones move out of the
 * way so the loud ones are visible — and when *all* of them are quiet the whole
 * set folds, because then the count is the honest summary.
 */
fun List<InteractionResult>.partitionedForReview(): Pair<List<InteractionResult>, List<InteractionResult>> =
    if (size > 2) admitted(InteractionProminence.NOTABLE) to filter { it.prominence < InteractionProminence.NOTABLE }
    else this to emptyList()

// MARK: - Interaction Policy

/** How aggressively the checker gates warnings, set per surface. */
enum class InteractionPolicy {
    /**
     * Log-time warnings (dose entry, daily-dose batch). Hide a pair when good
     * data — real effect curves or dose amounts — shows it is confidently
     * irrelevant. Everything uncertain is still shown, ranked by relevance.
     */
    WARN,

    /**
     * The Tools ▸ Interactions explorer. Never hide a matched rule — surface
     * every possible interaction unconditionally, marking low-relevance ones via
     * [InteractionResult.isLowRelevance] instead of suppressing them.
     */
    EXPLORE,
}

// MARK: - Interaction Rule

/**
 * One resolved class-pair rule.
 *
 * Internal rather than public because a rule is only ever *found* — every public
 * surface takes an [InteractionResult], which has already collapsed class pairs
 * into a sentence and a severity.
 */
internal data class InteractionRule(
    val classA: DrugClass,
    val classB: DrugClass,
    val severity: InteractionSeverity,
    /** Piru's own sentence for the pair where it has one, the database row's English note where it does not. */
    val description: String,
) {

    /**
     * The class pair this rule is written against, order-independent. This is the
     * rule's identity: two pairs firing the same rule share a cause, which two
     * pairs merely sharing boilerplate prose do not.
     */
    val key: String get() = InteractionRuleCopy.key(classA, classB)

    /**
     * A hard pharmacological edge whose danger **outlasts the subjective effect
     * curve** — irreversible MAO inhibition (lethal days after the felt effects
     * fade), lithium, or a chronic serotonergic at steady state. These are *not*
     * gated on effect-curve overlap and are never suppressed on dose.
     */
    val isPersistent: Boolean
        get() = classA in InteractionChecker.PERSISTENT_CLASSES || classB in InteractionChecker.PERSISTENT_CLASSES
}

// MARK: - Interaction rule copy

/**
 * The sentence Piru shows for a class-pair rule, in the reader's language.
 *
 * The rule itself — which two [DrugClass] values interact, and how badly — is
 * data and lives in the bundled database's `interaction_rules`. The sentence is
 * copy: written in the app's voice, and shipped in every language the app ships
 * in, which a database row cannot be. Keeping it here is what stops a data
 * rebuild from putting an untranslated sentence in front of a reader.
 *
 * A pair with no entry renders the row's own English note instead. That is not a
 * gap to fill mechanically — it is how TripSit's rules have always been shown,
 * and it is the honest default for a verdict Piru has not adjudicated itself.
 *
 * Every sentence is written the same way: the effect, an em dash, then the
 * elaboration. Compact surfaces show only the part before the dash
 * ([InteractionResult.leadClause]), so that half has to stand alone.
 *
 * Upstream the sentences are `LocalizedStringResource`s resolved by the app's
 * string catalog. This port carries the English source strings; the Android
 * resource layer resolves them from here when it lands.
 */
object InteractionRuleCopy {

    /** The class pair this copy is written against, order-independent. */
    fun key(classA: DrugClass, classB: DrugClass): String =
        listOf(classA.raw, classB.raw).sorted().joinToString("|")

    /** Piru's own sentence for a pair, or null when it has none. */
    fun note(classA: DrugClass, classB: DrugClass): String? = table[key(classA, classB)]

    /** The pairs as `(classA, classB, sentence)`, so each is written as enum cases the compiler checks. */
    private val ENTRIES: List<Triple<DrugClass, DrugClass, String>> = listOf(
        Triple(DrugClass.OPIOID, DrugClass.BENZODIAZEPINE, "Combined respiratory depression — the leading cause of overdose death."),
        Triple(DrugClass.OPIOID, DrugClass.GHB, "Severe respiratory depression — both substances suppress breathing."),
        Triple(DrugClass.OPIOID, DrugClass.ALCOHOL, "Respiratory depression and CNS shutdown — potentially fatal combination."),
        Triple(DrugClass.MAOI, DrugClass.EMPATHOGEN, "Risk of fatal serotonin syndrome — MAOIs block the enzyme that clears the serotonin an empathogen releases."),
        Triple(DrugClass.MAOI, DrugClass.SSRI, "Serotonin syndrome — potentially fatal. The labels require a washout of weeks between them."),
        Triple(DrugClass.MAOI, DrugClass.SNRI, "Serotonin syndrome — potentially fatal. The labels require a washout of weeks between them."),
        Triple(DrugClass.MAOI, DrugClass.TCA, "Risk of serotonin syndrome and hypertensive crisis."),
        Triple(DrugClass.MAOI, DrugClass.STIMULANT, "Hypertensive crisis — potentially fatal spike in blood pressure."),
        Triple(DrugClass.MAOI, DrugClass.OPIOID, "Risk of serotonin syndrome, especially with meperidine/pethidine, tramadol, and tapentadol."),
        Triple(DrugClass.LITHIUM, DrugClass.PSYCHEDELIC, "Of 62 reports of this combination, 47% described a seizure and 39% involved medical attention — against none of 34 reports for lamotrigine. Self-reported, so the rate is not a measured one, but no other pairing shows a signal like it."),
        Triple(DrugClass.GHB, DrugClass.ALCOHOL, "Respiratory depression and loss of consciousness — very narrow safety margin."),
        Triple(DrugClass.GHB, DrugClass.BENZODIAZEPINE, "Severe respiratory depression — both are GABAergic depressants."),
        Triple(DrugClass.BENZODIAZEPINE, DrugClass.ALCOHOL, "Life-threatening respiratory depression — this combination is a leading cause of overdose death."),
        Triple(DrugClass.OPIOID, DrugClass.GABAPENTINOID, "Enhanced respiratory depression — gabapentinoids increase opioid overdose risk."),
        Triple(DrugClass.OPIOID, DrugClass.OPIOID, "Stacking opioids is unpredictable — respiratory depression risk compounds."),
        Triple(DrugClass.OPIOID, DrugClass.ANTIHISTAMINE, "Additive CNS and respiratory depression — antihistamines potentiate opioid sedation."),
        Triple(DrugClass.OPIOID, DrugClass.STIMULANT, "Stimulants mask overdose signs — when they wear off, respiratory depression can emerge."),
        Triple(DrugClass.BENZODIAZEPINE, DrugClass.GABAPENTINOID, "Excessive sedation and respiratory depression risk."),
        Triple(DrugClass.BENZODIAZEPINE, DrugClass.ANTIHISTAMINE, "Compounded CNS depression — excessive sedation and impaired breathing."),
        Triple(DrugClass.SSRI, DrugClass.EMPATHOGEN, "SSRIs usually blunt MDMA — it may feel much weaker, and the documented harm is taking more to compensate (overheating, heart strain). Case reports don't show serotonin syndrome from this pair alone; MAOIs are the documented danger."),
        Triple(DrugClass.SNRI, DrugClass.EMPATHOGEN, "SNRIs usually blunt MDMA — it may feel weaker, and the documented harm is taking more to compensate (overheating, heart strain). Case reports don't show serotonin syndrome from this pair alone; MAOIs are the documented danger."),
        Triple(DrugClass.TCA, DrugClass.EMPATHOGEN, "TCAs usually blunt MDMA rather than boosting it, which leads people to take more; the bigger concern is added strain on heart rate and blood pressure."),
        Triple(DrugClass.DISSOCIATIVE, DrugClass.ALCOHOL, "Risk of respiratory depression, aspiration, and loss of consciousness."),
        Triple(DrugClass.DISSOCIATIVE, DrugClass.BENZODIAZEPINE, "Severe respiratory depression and loss of consciousness."),
        Triple(DrugClass.BENZODIAZEPINE, DrugClass.BENZODIAZEPINE, "Stacking benzodiazepines dramatically increases sedation and respiratory depression risk."),
        Triple(DrugClass.SSRI, DrugClass.SNRI, "Overlapping serotonin reuptake inhibition — increased serotonin syndrome risk."),
        Triple(DrugClass.SSRI, DrugClass.TCA, "SSRIs inhibit TCA metabolism — risk of TCA toxicity and serotonin syndrome."),
        Triple(DrugClass.GABAPENTINOID, DrugClass.ALCOHOL, "Enhanced CNS depression — risk of respiratory depression and death."),
        Triple(DrugClass.SEROTONERGIC, DrugClass.EMPATHOGEN, "Serotonin syndrome risk — these drugs add serotonin on top of an empathogen's surge. Some (tramadol, meperidine) can also trigger seizures."),
        Triple(DrugClass.SEROTONERGIC, DrugClass.MAOI, "Serotonin syndrome — potentially fatal. MAOIs block the enzyme that clears serotonin."),
        Triple(DrugClass.SEROTONERGIC, DrugClass.SEROTONERGIC, "Serotonin syndrome risk — two serotonin-raising drugs stacked together."),
        Triple(DrugClass.SEROTONERGIC, DrugClass.SSRI, "Serotonin syndrome risk — a serotonin-raising drug stacked with an SSRI."),
        Triple(DrugClass.SEROTONERGIC, DrugClass.SNRI, "Serotonin syndrome risk — a serotonin-raising drug stacked with an SNRI."),
        Triple(DrugClass.SEROTONERGIC, DrugClass.TCA, "Serotonin syndrome risk — a serotonin-raising drug stacked with a tricyclic antidepressant."),
        Triple(DrugClass.SEROTONERGIC, DrugClass.LITHIUM, "A large serotonin load on top of lithium's own. The lithium label names tramadol and fentanyl in this group; serotonin syndrome can start within hours."),
        Triple(DrugClass.ALPHA2_AGONIST, DrugClass.OPIOID, "Heavy sedation with a dangerously slow heart rate and breathing. Naloxone reverses the opioid but NOT the alpha-2 part — give rescue breaths and call for help even after naloxone."),
        Triple(DrugClass.ALPHA2_AGONIST, DrugClass.ALCOHOL, "Adds up sedation and lowers blood pressure further — stronger drowsiness and dizziness."),
        Triple(DrugClass.ALPHA2_AGONIST, DrugClass.BENZODIAZEPINE, "Compounded sedation and low blood pressure — stronger drowsiness and dizziness."),
        Triple(DrugClass.ALPHA2_AGONIST, DrugClass.GABAPENTINOID, "Additive sedation and low blood pressure — increased drowsiness and dizziness."),
        Triple(DrugClass.ALPHA2_AGONIST, DrugClass.TCA, "Tricyclics can cancel out clonidine-type blood-pressure lowering, so blood pressure may rise — a medical issue more than an overdose risk."),
        Triple(DrugClass.BETA_BLOCKER, DrugClass.ALPHA2_AGONIST, "Stopping the clonidine-type drug suddenly while on a beta-blocker can spike blood pressure to dangerous levels."),
        Triple(DrugClass.BETA_BLOCKER, DrugClass.STIMULANT, "The old “never mix” warning rests on little — large reviews did not find the harm it predicts. Both still strain the heart."),
        Triple(DrugClass.BETA_BLOCKER, DrugClass.ALCOHOL, "Both can lower blood pressure and add to dizziness — you may feel faint, especially standing up."),
        Triple(DrugClass.OREXIN_ANTAGONIST, DrugClass.OPIOID, "Added drowsiness and next-day grogginess, with more fall and coordination risk. The labels warn about combining with other CNS depressants, opioids included."),
        Triple(DrugClass.OREXIN_ANTAGONIST, DrugClass.ALCOHOL, "Alcohol stacks psychomotor and memory impairment on top of the sleep med (and raises lemborexant's blood levels) — expect worse next-day grogginess and unsteadiness. The labels advise against drinking with these."),
        Triple(DrugClass.OREXIN_ANTAGONIST, DrugClass.BENZODIAZEPINE, "Two sleep-promoting drugs stacked — additive next-day sedation, fall risk and impaired coordination. The labels warn about combining with other CNS depressants."),
        Triple(DrugClass.OREXIN_ANTAGONIST, DrugClass.GABAPENTINOID, "Additive sedation and next-day grogginess — more drowsiness, dizziness, and fall risk."),
        Triple(DrugClass.OREXIN_ANTAGONIST, DrugClass.GHB, "Compounded sedation — stronger, deeper drowsiness. GHB suppresses breathing on its own, and added sedation makes that harder to notice."),
        Triple(DrugClass.OREXIN_ANTAGONIST, DrugClass.ANTIHISTAMINE, "Both cause drowsiness — additive next-day sedation and grogginess."),
        Triple(DrugClass.DISSOCIATIVE, DrugClass.OPIOID, "Respiratory depression risk — dissociatives can mask overdose signs."),
        Triple(DrugClass.OPIOID, DrugClass.ANTIPSYCHOTIC, "Additive CNS and respiratory depression."),
        Triple(DrugClass.LITHIUM, DrugClass.SSRI, "Both raise serotonin, so serotonin syndrome is possible — agitation, tremor, sweating, a racing heart — and most likely in the first weeks. This pairing is prescribed and monitored on purpose; SSRIs do not raise lithium levels."),
        Triple(DrugClass.LITHIUM, DrugClass.SNRI, "Both raise serotonin, so serotonin syndrome is possible — agitation, tremor, sweating, a racing heart — and most likely in the first weeks. This pairing is prescribed and monitored on purpose; SNRIs do not raise lithium levels."),
        Triple(DrugClass.MAOI, DrugClass.DISSOCIATIVE, "Serotonin syndrome risk — especially with DXM and other serotonergic dissociatives."),
        Triple(DrugClass.BARBITURATE, DrugClass.OPIOID, "Combined respiratory depression with no ceiling — barbiturates deepen an opioid's suppression of breathing until it stops."),
        Triple(DrugClass.BARBITURATE, DrugClass.BENZODIAZEPINE, "Life-threatening respiratory depression. A barbiturate opens the GABA-A channel directly rather than modulating it, so this stacks past the point where benzodiazepines alone level off."),
        Triple(DrugClass.BARBITURATE, DrugClass.ALCOHOL, "Life-threatening respiratory depression and loss of consciousness — the classic fatal combination."),
        Triple(DrugClass.BARBITURATE, DrugClass.GHB, "Severe respiratory depression — two direct-acting depressants with no shared ceiling."),
        Triple(DrugClass.BARBITURATE, DrugClass.BARBITURATE, "Doses add with no plateau, and the gap between a sedating dose and a fatal one is narrow to begin with."),
        Triple(DrugClass.BARBITURATE, DrugClass.GABAPENTINOID, "Additive sedation and respiratory depression."),
        Triple(DrugClass.BARBITURATE, DrugClass.ANTIHISTAMINE, "Heavy additive sedation — deep drowsiness and impaired breathing."),
        Triple(DrugClass.BARBITURATE, DrugClass.DISSOCIATIVE, "Additive CNS and respiratory depression, with a raised risk of vomiting while unresponsive."),
        Triple(DrugClass.BARBITURATE, DrugClass.ALPHA2_AGONIST, "Additive sedation, low blood pressure, and slow heart rate."),
        Triple(DrugClass.BARBITURATE, DrugClass.OREXIN_ANTAGONIST, "Additive sedation and next-day impairment."),
        Triple(DrugClass.BARBITURATE, DrugClass.CANNABINOID, "Additive sedation, dizziness, and slowed reaction time."),
        Triple(DrugClass.BARBITURATE, DrugClass.ANTIPSYCHOTIC, "Additive CNS depression — increased sedation and impairment."),
        Triple(DrugClass.STIMULANT, DrugClass.STIMULANT, "Cardiovascular strain — combined stimulants increase heart rate and blood pressure."),
        Triple(DrugClass.STIMULANT, DrugClass.PSYCHEDELIC, "Increased anxiety and vasoconstriction — stimulants can intensify difficult trips."),
        Triple(DrugClass.CANNABINOID, DrugClass.PSYCHEDELIC, "Unpredictable intensification — cannabis can trigger anxiety or thought loops."),
        Triple(DrugClass.SSRI, DrugClass.PSYCHEDELIC, "SSRIs typically reduce psychedelic effects but may increase risk with some compounds."),
        Triple(DrugClass.SSRI, DrugClass.SSRI, "Serotonin accumulation risk — combining serotonergic agents increases toxicity chance."),
        Triple(DrugClass.STIMULANT, DrugClass.DISSOCIATIVE, "Increased heart rate and blood pressure — cardiovascular strain."),
        Triple(DrugClass.ALCOHOL, DrugClass.STIMULANT, "Stimulants mask alcohol impairment — risk of overconsumption."),
        Triple(DrugClass.ALCOHOL, DrugClass.ANTIHISTAMINE, "Compounded drowsiness and impaired coordination."),
        Triple(DrugClass.ALCOHOL, DrugClass.ANTIPSYCHOTIC, "Additive CNS depression — increased sedation and impairment."),
        Triple(DrugClass.GABAPENTINOID, DrugClass.GABAPENTINOID, "Stacking gabapentinoids compounds sedation and respiratory depression risk."),
        Triple(DrugClass.DISSOCIATIVE, DrugClass.DISSOCIATIVE, "Compounded dissociation — disorientation and loss of motor control."),
        Triple(DrugClass.EMPATHOGEN, DrugClass.EMPATHOGEN, "Serotonin depletion and neurotoxicity risk — allow adequate recovery between uses."),
        Triple(DrugClass.LITHIUM, DrugClass.EMPATHOGEN, "MDMA releases serotonin in bulk and lithium adds to it, so serotonin syndrome is the main risk. Seizures are reported for lithium with classic psychedelics; MDMA has not been looked at the same way."),
        Triple(DrugClass.LITHIUM, DrugClass.MAOI, "MAOIs block the enzyme that clears serotonin, so the load builds instead of levelling off. Serotonin syndrome is the risk; MAOIs do not raise lithium levels."),
        Triple(DrugClass.STIMULANT, DrugClass.SSRI, "Some combinations increase serotonin or seizure risk — monitor for symptoms."),
        Triple(DrugClass.STIMULANT, DrugClass.SNRI, "Cardiovascular strain and serotonin risk — watch your heart rate and blood pressure."),
        Triple(DrugClass.ANTIPSYCHOTIC, DrugClass.ANTIPSYCHOTIC, "Combined QTc prolongation risk — monitor cardiac rhythm."),
        Triple(DrugClass.GABAPENTINOID, DrugClass.ANTIHISTAMINE, "Additive CNS depression — increased sedation and impaired coordination."),
        Triple(DrugClass.CANNABINOID, DrugClass.BENZODIAZEPINE, "Additive sedation — may increase drowsiness and impaired coordination."),
        Triple(DrugClass.CANNABINOID, DrugClass.OPIOID, "Additive CNS depression — may increase sedation and respiratory depression risk."),
        Triple(DrugClass.CANNABINOID, DrugClass.ALCOHOL, "Additive impairment — increased dizziness, drowsiness, and slowed reaction time."),
    )

    /** Keyed by [key]. A pair written twice keeps the first — as upstream's `uniquingKeysWith`. */
    val table: Map<String, String> = ENTRIES.associate { (a, b, text) -> key(a, b) to text }

}

// MARK: - Bundled rows

/**
 * One `interaction_rules` row, as the read layer hands it over.
 *
 * Severity and the two classes stay strings here: they are decoded by
 * [InteractionChecker], which is the only code that knows what to do with a word
 * it does not recognise (drop the row).
 */
data class ClassInteractionRule(
    val classA: String,
    val classB: String,
    val severity: String,
    val note: String,
)

/**
 * One `drug_interactions_pk` row — a named counterpart, the enzyme mechanism, and
 * the measured effect on exposure.
 *
 * These are **not** severity-bearing warnings and must never be rendered as one.
 * The table has no severity column because its sources do not assign one: a row
 * says "clarithromycin raises oral ketamine AUC ~2.6×", which is a measurement,
 * and turning a measurement into caution/unsafe/dangerous would be inventing the
 * part a reader most relies on. Severity comes from the class rules or from the
 * curated layer, never here.
 *
 * Ported from `PKInteractionHit` in `SubstanceReadModel+PKInteractions.swift`.
 */
data class PKInteractionHit(
    val id: Long,
    /**
     * The counterpart, **verbatim from the source**. Often a single drug
     * ("clarithromycin"), often a slash-separated set
     * ("ketoconazole / itraconazole"), and often a class
     * ("CYP3A4 inhibitors (azoles, macrolides)"). See [counterpartNames].
     */
    val withSubstance: String,
    /** The enzyme mechanism — "CYP3A4 inhibition", "CYP2C19 induction". */
    val mechanism: String? = null,
    /** Inhibition constant in µM, when the source measured one. */
    val kiMicromolar: Double? = null,
    /** What it does to exposure, in the source's own terms. */
    val labeledEffect: String? = null,
    val sourceSlug: String = "",
    val doi: String? = null,
    val pmid: Int? = null,
) {

    /**
     * [withSubstance] split into its individual names.
     *
     * Two thirds of the table names a *class* rather than a drug, so this is a
     * best-effort split for matching, not a promise that each piece is a
     * substance. Anything that fails to resolve is simply not matched — the row
     * still displays in full on the substance's own page.
     */
    val counterpartNames: List<String>
        get() = withSubstance.split("/").map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * Everything the checker reads from outside itself.
 *
 * Upstream this is `SubstanceStore.shared` plus `SubstanceLibrary`; here it is a
 * port, implemented over the real read layer in `:core:substance`. Name
 * resolution — canonical spelling, category, duration, half-life — is **not**
 * here: it comes from the [SubstanceCatalog] the checker already holds, so the
 * two halves cannot disagree about what a name means.
 */
interface InteractionData {

    /** Every `interaction_rules` row. */
    fun classRules(): List<ClassInteractionRule>

    /**
     * `substance_interaction_classes`, keyed by **lowercased** name, already
     * expanded across each linked substance's aliases. An explicit row beats an
     * alias expansion.
     */
    fun interactionClasses(): Map<String, List<DrugClass>>

    /** `category_interaction_classes`, keyed by [SubstanceCategory.wireValue]. */
    fun categoryInteractionClasses(): Map<String, DrugClass>

    /** Every `enzyme_modulators` row, in curated rank order. */
    fun enzymeModulators(): List<EnzymeModulator>

    /**
     * The enzymes carrying a *major* share of a substance's clearance, from its
     * `metabolism` rows. Empty for a name the catalog does not carry.
     */
    fun majorEnzymes(substanceName: String): Set<Enzyme>

    /**
     * `tag_enzyme_interactions`, indexed `perpetratorCanonical -> victimCanonical
     * -> row`. Both keys are lowercased canonical names.
     */
    fun tagEnzymeInteractions(): Map<String, Map<String, TagEnzymeInteraction>>

    /** `drug_interactions_pk` rows for one substance name or alias, enabled sources only. */
    fun pharmacokineticInteractions(substanceName: String): List<PKInteractionHit>
}

// MARK: - Interaction Checker

/**
 * The drug-interaction engine. Resolves substance pairs into severity-ranked
 * warnings using hand-curated pharmacological class rules and returns the
 * worst-case match per pair.
 *
 * ## Source layers
 *
 * Every rule comes from the bundled database's `interaction_rules`: Piru's own
 * adjudicated verdicts, and TripSit's combination matrix for the pairs those do
 * not cover. Which pair interacts and how badly is data; the *sentence* shown for
 * a pair Piru has adjudicated is copy and lives in [InteractionRuleCopy].
 *
 * ## Severity ordering
 *
 * [InteractionSeverity] orders dangerous > unsafe > caution. Batch
 * deduplication keeps the highest-scoring result for each substance pair so a
 * reader always sees the worst-case warning.
 *
 * ## Caveats
 *
 * [DrugClass.OTHER] participates in **zero** rules. Any substance whose category
 * resolves to it is interaction-invisible: it returns nothing for every pairing.
 * The fix is a row in `substance_interaction_classes` lifting the substance into
 * a real class — that is how barbiturates and methylene blue were recovered.
 *
 * @param data the read layer, [InteractionData].
 * @param catalog name resolution — canonical name, category, duration, half-life.
 */
class InteractionChecker(
    private val data: InteractionData,
    private val catalog: SubstanceCatalog,
) {

    // MARK: - Public API

    /**
     * Check a prospective substance against a list of active dose entries.
     *
     * On [InteractionPolicy.WARN] this reads the same effect curves the timeline
     * draws: a pair only warns where the two doses' effects genuinely co-occur
     * and both are at a meaningful dose. A pair the data confidently shows to be
     * irrelevant — non-concurrent, or with a clearly sub-threshold participant —
     * is dropped, not reddened. Hard pharmacological edges (MAOI, lithium,
     * chronic serotonergics) bypass the effect-overlap gate.
     *
     * On [InteractionPolicy.EXPLORE] nothing is hidden — every matched rule is
     * returned, ranked by relevance.
     *
     * [now] is a parameter rather than a clock read so the prospective dose's
     * curve — and therefore the whole gate — is reproducible in a test.
     */
    fun check(
        substanceName: String,
        against: List<DoseRecord>,
        policy: InteractionPolicy = InteractionPolicy.WARN,
        now: Instant = Instant.now(),
    ): List<InteractionResult> {
        val newClasses = drugClasses(substanceName)
        if (newClasses.isEmpty()) return emptyList()

        // The prospective dose's effect track (it starts "now"). Only built for
        // temporal gating on warn surfaces; null when the substance has no curve
        // data, in which case the temporal gate degrades to "unknown" and the
        // pair is shown.
        val prospective = if (policy == InteractionPolicy.WARN) prospectiveTrack(substanceName, now) else null

        val byPair = mutableMapOf<String, InteractionResult>()
        for (entry in against) {
            if (entry.substance.lowercase() == substanceName.lowercase()) continue

            val rule = worstRule(newClasses, drugClasses(entry.substance)) ?: continue

            var overlapFactor = 1.0
            var doseFactor = 1.0
            if (policy == InteractionPolicy.WARN) {
                val gate = gatePair(prospective, track(entry), rule.isPersistent)
                if (gate.suppress) continue
                overlapFactor = gate.overlapFactor
                doseFactor = gate.doseFactor
            }

            merge(
                InteractionResult(
                    severity = rule.severity,
                    substanceA = substanceName,
                    substanceB = entry.substance,
                    description = rule.description,
                    ruleKey = rule.key,
                    overlapFactor = overlapFactor,
                    doseFactor = doseFactor,
                ),
                byPair,
            )
        }

        addEnzymeResults(listOf(substanceName) + against.map { it.substance }, byPair)

        return byPair.values.sortedByDescending { it.displayScore }
    }

    /**
     * Check all substances in a batch against each other and against active
     * entries. Returns a **unified** ranked list: drug-specific enzyme
     * interactions at the top, generic class-pair rules demoted when a more
     * specific interaction exists for the same pair.
     */
    fun checkBatch(
        substances: List<String>,
        against: List<DoseRecord>,
        policy: InteractionPolicy = InteractionPolicy.WARN,
        now: Instant = Instant.now(),
    ): List<InteractionResult> {
        val byPair = mutableMapOf<String, InteractionResult>()

        // Each substance (logged "now") against the already-active entries.
        if (against.isNotEmpty()) {
            for (substance in substances) {
                for (result in check(substance, against, policy, now)) merge(result, byPair)
            }
        }

        // Within the batch the substances are co-administered, so they are
        // concurrent by construction (overlap = 1) and carry no amounts here
        // (dose = 1) — the rule fires at its base severity.
        for (i in substances.indices) {
            for (j in i + 1 until substances.size) {
                val rule = worstRule(drugClasses(substances[i]), drugClasses(substances[j])) ?: continue
                merge(
                    InteractionResult(
                        severity = rule.severity,
                        substanceA = substances[i],
                        substanceB = substances[j],
                        description = rule.description,
                        ruleKey = rule.key,
                    ),
                    byPair,
                )
            }
        }

        addEnzymeResults(substances + against.map { it.substance }, byPair)

        return byPair.values.sortedByDescending { it.displayScore }
    }

    /**
     * The `drug_interactions_pk` rows that name something in [against].
     *
     * Read in both directions: a row lives on one substance's record and names
     * the other in free text, and which side got the row is an artifact of which
     * paper was read, not of which drug is affected. Ketamine's record carries
     * "clarithromycin"; clarithromycin's record carries nothing.
     *
     * About a third of the table names a real drug this way; the rest names a
     * *class* ("CYP3A4 inhibitors", "MAOIs") and matches nothing here by design —
     * an unresolved name is skipped rather than string-matched, because "SSRIs"
     * substring-matching a logged SSRI would be the checker inferring a claim the
     * row does not make.
     */
    fun pharmacokineticInteractions(
        substanceName: String,
        against: List<DoseRecord>,
    ): List<PKInteractionHit> {
        val logged = LinkedHashMap<String, String>()
        for (entry in against) {
            val canonical = canonicalOrNull(entry.substance) ?: continue
            logged.putIfAbsent(canonical, entry.substance)
        }
        if (logged.isEmpty()) return emptyList()
        val subject = canonicalOrNull(substanceName) ?: return emptyList()

        val findings = mutableListOf<PKInteractionHit>()
        val seen = mutableSetOf<Long>()

        fun collect(owner: String, candidates: Map<String, String>) {
            for (hit in data.pharmacokineticInteractions(owner)) {
                if (hit.id in seen) continue
                for (name in hit.counterpartNames) {
                    val resolved = canonicalOrNull(name) ?: continue
                    if (!candidates.containsKey(resolved)) continue
                    seen += hit.id
                    findings += hit
                    break
                }
            }
        }

        // The prospective substance's own rows, against everything logged…
        collect(substanceName, logged)
        // …then each logged substance's rows, against the prospective one.
        for (entry in against) {
            if (entry.substance.lowercase() == substanceName.lowercase()) continue
            collect(entry.substance, mapOf(subject to substanceName))
        }
        return findings
    }

    /**
     * Whether any rule mentions [drugClass]. A class no rule mentions makes every
     * substance routed to it interaction-invisible.
     */
    fun hasAnyRule(drugClass: DrugClass): Boolean =
        ruleLookup().values.any { it.classA == drugClass || it.classB == drugClass }

    /**
     * Class pairs the database declares more than once.
     *
     * `UNIQUE (class_a, class_b)` is on the **ordered** tuple, so the same pair
     * written back-to-front by two ingesters satisfies it and then silently
     * decides by row order here — harmless while both say the same thing,
     * invisible when they stop.
     */
    val duplicateRuleKeys: List<String>
        get() {
            val seen = mutableSetOf<String>()
            val duplicates = mutableListOf<String>()
            for (bundled in data.classRules()) {
                val classA = DrugClass.fromRaw(bundled.classA) ?: continue
                val classB = DrugClass.fromRaw(bundled.classB) ?: continue
                val key = InteractionRuleCopy.key(classA, classB)
                if (!seen.add(key)) duplicates += key
            }
            return duplicates
        }

    // MARK: - Drug class mapping

    /** Memoized drug-class lookups, keyed by the lowercased typed name. */
    private val drugClassCache = mutableMapOf<String, List<DrugClass>>()

    /**
     * Drop every memoized class. Call whenever the custom-substance overlay
     * changes, since a relabel or custom entry can change what a name resolves to.
     */
    fun invalidateClassCache() {
        drugClassCache.clear()
    }

    /**
     * Get drug classes for a substance name. Falls back to a catalog lookup
     * (canonical name or alias) when no override names it; the result is
     * memoised. **Misses are deliberately NOT memoised**: a transient lookup
     * failure must not permanently poison a substance's interactions.
     */
    fun drugClasses(name: String): List<DrugClass> {
        val lower = name.lowercase()
        val overrides = data.interactionClasses()

        overrides[lower]?.let { return it }
        drugClassCache[lower]?.let { return it }

        val substance = catalog.lookup(name) ?: return emptyList()
        // Re-check under the canonical name. The overrides already cover every
        // catalog alias, but a lookup also resolves a user's own relabel —
        // someone who renames Alprazolam to "my anxiety pill" would otherwise
        // fall through to the category.
        val resolved = overrides[substance.name.lowercase()]
            ?: listOf(categoryToDrugClass(substance.category))
        drugClassCache[lower] = resolved
        return resolved
    }

    /**
     * The interaction class a substance's category falls back to.
     *
     * [DrugClass.OTHER] is the default for a category
     * `category_interaction_classes` does not name, and it participates in no
     * rule — so an unmapped category makes every substance under it
     * interaction-invisible. That default is in code because it is what
     * "unmapped" *means*, not a judgment someone made.
     */
    private fun categoryToDrugClass(category: SubstanceCategory): DrugClass =
        data.categoryInteractionClasses()[category.wireValue] ?: DrugClass.OTHER

    // MARK: - Interaction rules

    private var ruleLookupCache: Map<String, InteractionRule>? = null

    /**
     * Every `interaction_rules` row, keyed by its sorted class pair.
     *
     * An empty result is **not** memoised. Every rule the app has comes from one
     * read, so caching a failed one would leave the process with no interaction
     * warnings at all — and nothing on screen would say so.
     */
    private fun ruleLookup(): Map<String, InteractionRule> {
        ruleLookupCache?.let { return it }
        val dict = mutableMapOf<String, InteractionRule>()
        for (bundled in data.classRules()) {
            val classA = DrugClass.fromRaw(bundled.classA) ?: continue
            val classB = DrugClass.fromRaw(bundled.classB) ?: continue
            val severity = InteractionSeverity.bundled(bundled.severity) ?: continue
            dict[InteractionRuleCopy.key(classA, classB)] = InteractionRule(
                classA = classA,
                classB = classB,
                severity = severity,
                description = InteractionRuleCopy.note(classA, classB) ?: bundled.note,
            )
        }
        if (dict.isNotEmpty()) ruleLookupCache = dict
        return dict
    }

    private fun findRule(a: DrugClass, b: DrugClass): InteractionRule? = ruleLookup()[InteractionRuleCopy.key(a, b)]

    /**
     * The highest-severity rule across every class-pair combination of two
     * substances, or null if none interact.
     */
    internal fun worstRule(classesA: List<DrugClass>, classesB: List<DrugClass>): InteractionRule? {
        var best: InteractionRule? = null
        for (a in classesA) {
            for (b in classesB) {
                val rule = findRule(a, b) ?: continue
                if (best == null || rule.severity > best.severity) best = rule
            }
        }
        return best
    }

    /** Keep the highest-scoring result per substance pair. */
    internal fun merge(result: InteractionResult, into: MutableMap<String, InteractionResult>) {
        val key = pairKey(result.substanceA, result.substanceB)
        val existing = into[key]
        if (existing != null && existing.displayScore >= result.displayScore) return
        into[key] = result
    }

    internal fun pairKey(a: String, b: String): String =
        listOf(a.lowercase(), b.lowercase()).sorted().joinToString("|")

    /**
     * Layer 2 — metabolic modulation, merged into [byPair].
     *
     * An enzyme interaction is strictly more specific than a class rule, so it
     * **replaces** a class rule of equal or lower severity for the same pair —
     * but **never a `dangerous` one**: the enzyme result is capped at `unsafe`,
     * and MDMA + tramadol's serotonergic pairing must not arrive downgraded
     * because the pair also shares CYP2D6.
     */
    private fun addEnzymeResults(names: List<String>, byPair: MutableMap<String, InteractionResult>) {
        if (names.isEmpty()) return
        val effects = MetabolicModulation.checkerEffects(
            among = names,
            modulators = data.enzymeModulators(),
            tagIndex = data.tagEnzymeInteractions(),
            majorEnzymesFor = data::majorEnzymes,
            canonical = ::canonical,
        )
        for (effect in effects) {
            val key = pairKey(effect.modulatorName, effect.substrate)
            val classRule = byPair[key]
            if (classRule != null && classRule.severity < InteractionSeverity.DANGEROUS) byPair.remove(key)
            byPair["enzyme:$key"] = InteractionResult(
                severity = InteractionSeverity.UNSAFE,
                substanceA = effect.modulatorName,
                substanceB = effect.substrate,
                description = effect.userNote,
                ruleKey = "enzyme:${effect.modulatorID}|${effect.enzyme.token}",
            )
        }
    }

    /**
     * A name resolved to the catalog's canonical spelling, lowercased — or null
     * when the catalog does not carry it, which is the signal to skip rather than
     * to fall back to raw string comparison.
     */
    private fun canonicalOrNull(name: String): String? = catalog.lookup(name)?.name?.lowercase()

    /** The same, with the lowercased input standing in when the catalog has no entry. */
    private fun canonical(name: String): String = canonicalOrNull(name) ?: name.lowercase()

    // MARK: - Relevance gating

    /**
     * One dose's subjective-effect track on the wall clock: when it was taken and
     * the state whose [TimelineCurveModel.effectShape] gives its strength over
     * time.
     */
    private class EffectTrack(
        val state: ActiveSubstanceState,
        val start: Instant,
        /** `false` when the amount is unknown (a prospective / name-only dose). */
        val amountKnown: Boolean,
    ) {
        val magnitude: Double get() = state.doseMagnitude
        val effectEndMinutes: Double get() = maxOf(state.offsetEndMinutes, state.totalMinutes)
    }

    private class GateResult(val overlapFactor: Double, val doseFactor: Double, val suppress: Boolean)

    /**
     * Grade a single pair: its temporal overlap, combined dose presence, and
     * whether good data makes it confidently irrelevant. `persistent` rules skip
     * both the effect-overlap and dose suppression.
     */
    private fun gatePair(prospective: EffectTrack?, active: EffectTrack?, persistent: Boolean): GateResult {
        val presenceProspective = prospective?.let { if (it.amountKnown) presence(it.magnitude) else 1.0 } ?: 1.0
        val presenceActive = active?.let { if (it.amountKnown) presence(it.magnitude) else 1.0 } ?: 1.0
        val doseFactor = presenceProspective * presenceActive

        fun confidentlyTrivial(track: EffectTrack?): Boolean =
            track != null && track.amountKnown && track.magnitude <= TRIVIAL_MAGNITUDE

        val doseConfidentLow = !persistent && (confidentlyTrivial(active) || confidentlyTrivial(prospective))

        var overlapFactor = 1.0
        var overlapConfidentLow = false
        if (!persistent && prospective != null && active != null) {
            overlapFactor = effectOverlap(prospective, active)
            overlapConfidentLow = overlapFactor < OVERLAP_SUPPRESS_THRESHOLD
        }

        return GateResult(
            overlapFactor = overlapFactor,
            doseFactor = doseFactor,
            suppress = overlapConfidentLow || doseConfidentLow,
        )
    }

    /**
     * Peak of the product of two doses' effect curves over the window where they
     * could co-occur. `0` when the curves never overlap in wall-clock time.
     */
    private fun effectOverlap(a: EffectTrack, b: EffectTrack): Double {
        val laterStart = maxOf(a.start, b.start)
        val endA = a.start.plusSeconds((a.effectEndMinutes * 60).toLong())
        val endB = b.start.plusSeconds((b.effectEndMinutes * 60).toLong())
        val windowEnd = minOf(endA, endB)
        if (windowEnd <= laterStart) return 0.0

        val steps = 64
        val span = Duration.between(laterStart, windowEnd).toMillis() / 1000.0
        var peak = 0.0
        for (i in 0..steps) {
            val t = laterStart.plusSeconds(((span * i / steps)).toLong())
            val minutesFromA = Duration.between(a.start, t).toMillis() / 60_000.0
            val minutesFromB = Duration.between(b.start, t).toMillis() / 60_000.0
            val shapeA = TimelineCurveModel.effectShape(minutesFromA, a.state)
            val shapeB = TimelineCurveModel.effectShape(minutesFromB, b.state)
            peak = maxOf(peak, shapeA * shapeB)
        }
        return peak
    }

    /**
     * Dose-presence weight in `[0, 1]`: ≈1 at a meaningful dose, →0 for a clearly
     * sub-threshold one. Smoothstep over `[PRESENCE_LOW, PRESENCE_FULL]`.
     */
    private fun presence(magnitude: Double): Double {
        val t = minOf(1.0, maxOf(0.0, (magnitude - PRESENCE_LOW) / (PRESENCE_FULL - PRESENCE_LOW)))
        return t * t * (3 - 2 * t)
    }

    /** Build the effect track for an already-logged entry (amount known). */
    private fun track(entry: DoseRecord): EffectTrack? {
        val state = ActiveSubstanceState.from(entry, P3Color.NEUTRAL, catalog) ?: return null
        return EffectTrack(state = state, start = entry.timestamp, amountKnown = true)
    }

    /**
     * Build the effect track for a prospective dose from its name alone — it
     * starts "now" on its default route, and its amount is treated as unknown
     * (only the *shape* of the curve, which is dose-independent, is used).
     */
    private fun prospectiveTrack(substanceName: String, now: Instant): EffectTrack? {
        val substance = catalog.lookup(substanceName) ?: return null
        val entry = DoseRecord(
            substance = substanceName,
            amount = 1.0,
            unit = "mg",
            route = substance.defaultRoute,
            timestamp = now,
        )
        val state = ActiveSubstanceState.from(entry, P3Color.NEUTRAL, catalog) ?: return null
        return EffectTrack(state = state, start = now, amountKnown = false)
    }

    // MARK: - Active entry detection

    /**
     * Filter entries to those still pharmacologically active at [now].
     *
     * An unknown substance is assumed active for 24 h rather than dropped: the
     * safety-relevant names the catalog lacks (xylazine, the rarer barbiturates)
     * are exactly the ones a person types by hand.
     */
    fun activeEntries(from: List<DoseRecord>, now: Instant = Instant.now()): List<DoseRecord> =
        from.filter { entry ->
            val elapsedSeconds = Duration.between(entry.timestamp, now).toMillis() / 1000.0
            val substance = catalog.lookup(entry.substance)
                ?: return@filter elapsedSeconds < DAY_SECONDS

            val duration = substance.resolveDuration(entry.route, entry.saltForm, entry.isomer)
            if (duration != null) {
                return@filter elapsedSeconds < duration.estimatedTotalMinutes * 60
            }
            val halfLife = substance.halfLifeMinutes
            if (halfLife != null) {
                // 5 half-lives ≈ 97% eliminated.
                return@filter elapsedSeconds < halfLife * 5 * 60
            }
            // No duration or half-life data — assume active for 24h.
            elapsedSeconds < DAY_SECONDS
        }

    companion object {
        /**
         * Drug classes whose interactions outlast the subjective effect curve, so
         * they bypass the effect-overlap gate and are never suppressed on dose.
         */
        val PERSISTENT_CLASSES: Set<DrugClass> = setOf(
            DrugClass.MAOI,
            DrugClass.LITHIUM,
            DrugClass.SSRI,
            DrugClass.SNRI,
            DrugClass.TCA,
        )

        /** Effect-overlap peak below which two doses are treated as not concurrent. */
        private const val OVERLAP_SUPPRESS_THRESHOLD: Double = 0.05

        /**
         * Dose magnitude at or below which a participant is treated as
         * confidently sub-threshold — its presence in the pair is negligible, so
         * the *interaction* (not the drug itself) is irrelevant.
         *
         * This equals [ActiveSubstanceCalculator.MINIMUM_INTENSITY], the floor
         * `computeDoseMagnitude` clamps to. A substance with no dose data
         * resolves to [ActiveSubstanceCalculator.UNKNOWN_INTENSITY] (0.60)
         * instead, so it is never mistaken for trivial — exactly the "only
         * suppress when we have good data" requirement.
         */
        private val TRIVIAL_MAGNITUDE: Double = ActiveSubstanceCalculator.MINIMUM_INTENSITY

        /** Magnitude window over which dose presence ramps `0 → 1`. */
        private const val PRESENCE_LOW: Double = 0.04
        private const val PRESENCE_FULL: Double = 0.25

        private const val DAY_SECONDS: Double = 86_400.0
    }
}
