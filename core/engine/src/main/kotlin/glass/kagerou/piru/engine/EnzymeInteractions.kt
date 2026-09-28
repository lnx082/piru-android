package glass.kagerou.piru.engine

import glass.kagerou.piru.model.ConfidenceTier

/*
 * Ported from `Piru/Data/Pharmacology/MetabolicModulation.swift` in the iOS
 * target — the Layer 2 half of [InteractionChecker].
 *
 * Upstream the file is `@MainActor` and reads `SubstanceStore.shared` directly.
 * Here every entry point takes the data it needs, so the matching is a pure
 * function of rows and stays testable without a catalog behind it — which is
 * what the upstream file says about itself as well; it just cannot follow
 * through while the store is a singleton.
 *
 * Everything below the `Modulator`/`Effect` value types is a direct port. What
 * is deliberately *not* here: `educationalEffects` and
 * `contraceptiveEfficacyCaution`, which serve the substance detail card rather
 * than the checker. Neither is read by this engine.
 */

// MARK: - Enzymes

/**
 * The metabolic enzymes for which modulators are curated.
 *
 * [token] is the string matched (case-insensitively, substring) against a
 * `metabolism.enzyme` cell and stored in `enzyme_modulators.enzyme`. The cells
 * are free text and may name several enzymes ("CYP2C19, CYP3A4", "CYP2D6
 * (major)"), so detection is contains-based. The tokens are mutually
 * non-overlapping — "CYP2C19" never contains "CYP2C9".
 */
enum class Enzyme(val token: String) {
    CYP3A4("CYP3A4"),
    CYP1A2("CYP1A2"),
    CYP2D6("CYP2D6"),
    CYP2C19("CYP2C19"),
    CYP2C9("CYP2C9"),
    CYP2B6("CYP2B6"),
    ;

    /** Human-facing enzyme name for the readout copy. */
    val displayName: String get() = token

    companion object {
        private val byToken: Map<String, Enzyme> = entries.associateBy { it.token }

        /** Parse an `enzyme_modulators.enzyme` cell, or null for an enzyme this build does not model. */
        fun fromToken(token: String): Enzyme? = byToken[token]

        /** Every curated enzyme named by a `metabolism.enzyme` cell (may be empty for generic/other rows). */
        fun all(inDBString: String): Set<Enzyme> {
            val upper = inDBString.uppercase()
            return entries.filterTo(mutableSetOf()) { upper.contains(it.token) }
        }

        /**
         * The enzymes a cell names as a **major** route, dropping those its own
         * prose calls minor.
         *
         * One cell routinely mixes weights — `CYP3A4, CYP1A2 (major); CYP2D6
         * (minor)`, `CYP2D6 (dominant; CYP2C8/CYP2E1/CYP2A6 minor — CYP1A2
         * contribution is negligible)`. Scanning the whole cell with [all]
         * promoted every enzyme in it, so a pathway the source wrote down as
         * *negligible* fanned modulator callouts out as though it were dominant.
         * Splitting on the punctuation those qualifiers scope over, then dropping
         * a whole clause that carries one, is what the cell already means.
         *
         * A clause with no enzyme token contributes nothing, so an unparseable
         * cell reads as silence rather than as a guess.
         */
        fun major(inDBString: String): Set<Enzyme> =
            inDBString.split(';', '—', '–')
                .filter { clause ->
                    val lowered = clause.lowercase()
                    minorQualifiers.none { lowered.contains(it) }
                }
                .fold(mutableSetOf()) { acc, clause -> acc.apply { addAll(all(clause)) } }

        /** Words that demote every enzyme in the clause carrying them. */
        private val minorQualifiers = listOf("minor", "negligible", "trace")
    }
}

// MARK: - Direction & strength

/** Which way a modulator moves the substrate's exposure. */
enum class ModulationDirection(val token: String) {
    /** Enzyme **inhibition** → slower clearance → **higher** levels. */
    INHIBITS("inhibits"),

    /** Enzyme **induction** → faster clearance → **lower** levels. */
    INDUCES("induces"),
    ;

    /** `true` when the modulator raises the substrate's levels. */
    val raisesLevels: Boolean get() = this == INHIBITS

    companion object {
        fun fromToken(token: String): ModulationDirection? = entries.firstOrNull { it.token == token }
    }
}

/**
 * Qualitative magnitude — never a fabricated fold-change. Surfaced as a word,
 * not a number.
 *
 * The constants are declared in ascending order so the natural ordering *is* the
 * strength ordering, matching how the rest of this module treats enum order.
 */
enum class ModulationStrength(val token: String) : Comparable<ModulationStrength> {
    WEAK("weak"),
    MODERATE("moderate"),
    STRONG("strong"),
    ;

    companion object {
        fun fromToken(token: String): ModulationStrength? = entries.firstOrNull { it.token == token }
    }
}

/** Where the modulation comes from — drives where and when it is surfaced. */
enum class ModulatorOrigin(val token: String) {
    /** A *logged* co-active drug (ritonavir, carbamazepine, …). */
    SUBSTANCE("substance"),

    /** A non-dose lifestyle flag — grapefruit (per-dose) or smoking (profile). */
    CONTEXT("context"),

    /** The substance modulating the enzyme that clears *itself* (MDMA ⊣ CYP2D6). */
    SELF("self"),
    ;

    companion object {
        fun fromToken(token: String): ModulatorOrigin? = entries.firstOrNull { it.token == token }
    }
}

// MARK: - Modulator

/**
 * One source of metabolic modulation: a logged drug, a lifestyle context, or a
 * substance's effect on its own clearing enzyme. Built from an
 * `enzyme_modulators` row.
 *
 * Display text is DB-driven — adding a new modulator is a pipeline change, not a
 * Kotlin change.
 *
 * The read layer skips any row whose origin, enzyme, direction or strength does
 * not decode, and any row missing `display_name` or `user_note`: the readout IS
 * a sentence, and a rule with no sentence has nothing to show.
 */
data class EnzymeModulator(
    val id: String,
    val origin: ModulatorOrigin,
    val enzyme: Enzyme,
    val direction: ModulationDirection,
    val strength: ModulationStrength,
    val confidence: ConfidenceTier,
    /**
     * Lowercased names/aliases identifying the modulating (or self) substance.
     * Empty for a pure context flag that is never logged as a dose (grapefruit,
     * smoking).
     */
    val matchers: List<String>,
    val displayName: String,
    val userNote: String,
)

// MARK: - Effect

/**
 * One predicted metabolic-modulation effect on a substrate. Carries direction,
 * qualitative strength and a confidence tier — never a fabricated fold-change.
 */
data class EnzymeEffect(
    val modulatorID: String,
    val origin: ModulatorOrigin,
    /** The affected substance (display name as supplied). */
    val substrate: String,
    val enzyme: Enzyme,
    val direction: ModulationDirection,
    val modulatorName: String,
    val userNote: String,
) {

    /** `true` when levels go up (inhibition). */
    val raisesLevels: Boolean get() = direction.raisesLevels
}

/**
 * One row from the pipeline-materialized `tag_enzyme_interactions` table: every
 * pair where a tagged CYP inhibitor meets a tagged CYP substrate or prodrug.
 */
data class TagEnzymeInteraction(
    val perpetratorName: String,
    val victimName: String,
    val enzyme: String,
    val direction: String,
    val strength: String?,
    val victimType: String,
)

// MARK: - Metabolic modulation

/**
 * The **metabolic-modulation graph** — curated edges where something (a
 * co-active drug, a lifestyle context, or the substance itself) changes how fast
 * another drug is *cleared*, by inhibiting or inducing the enzyme that clears it.
 *
 * ## Readout-only
 * This is a **readout layer**, not a change to the PK/occupancy math. A CYP3A4
 * inhibitor onboard does **not** raise the substrate's concentration curve
 * everywhere; instead the app surfaces the *direction and qualitative strength*
 * of the effect. No fabricated fold-change number is ever shown.
 */
object MetabolicModulation {

    /**
     * A quantified clearance share at or above this percent — or any
     * *unquantified* listed pathway — is treated as a **major** route.
     *
     * Unquantified rows are listed by the curators precisely because they matter,
     * so they count; quantified minor pathways below the threshold are ignored to
     * avoid noise.
     */
    const val MAJOR_CLEARANCE_THRESHOLD_PCT: Double = 15.0

    /**
     * The enzymes carrying a *major* share of a substance's clearance, from its
     * `metabolism` rows.
     *
     * Two gates, because a row can say "minor" in either of two places: the
     * quantified share, and the prose of the cell itself. Neither subsumes the
     * other — most cells carrying a qualifier have no fraction at all, which is
     * why the unquantified default has to be generous.
     */
    fun majorEnzymes(metabolism: List<MetabolismHit>): Set<Enzyme> {
        val result = mutableSetOf<Enzyme>()
        for (hit in metabolism) {
            if ((hit.fractionOfClearancePct ?: 100.0) < MAJOR_CLEARANCE_THRESHOLD_PCT) continue
            result += Enzyme.major(hit.enzyme)
        }
        return result
    }

    /**
     * **Interaction-checker** effects among a set of hypothetically co-present
     * substances.
     *
     * Two layers, merged and deduplicated:
     *
     * 1. **Curated modulators** (`enzyme_modulators`): each pair where one
     *    selected substance is a curated modulator of an enzyme that clears
     *    another. Rich per-modulator notes.
     * 2. **Tag-derived** (`tag_enzyme_interactions`): every pair where one
     *    selected substance carries a CYP inhibitor/inducer tag and the other a
     *    CYP substrate/prodrug tag on the same enzyme. Broader coverage,
     *    template-generated notes.
     *
     * When both layers fire on the same pair, the curated entry wins — it has the
     * richer copy, and it claimed the pair's dedup key first.
     *
     * Context flags and self-edges are excluded: the checker reasons about
     * substance combinations only.
     *
     * @param majorEnzymesFor a substance's major clearance enzymes, by name.
     * @param canonical a name resolved to the catalog's canonical spelling,
     *   lowercased; the input unchanged when the catalog has no entry.
     */
    fun checkerEffects(
        among: List<String>,
        modulators: List<EnzymeModulator>,
        tagIndex: Map<String, Map<String, TagEnzymeInteraction>>,
        majorEnzymesFor: (String) -> Set<Enzyme>,
        canonical: (String) -> String,
    ): List<EnzymeEffect> {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<EnzymeEffect>()

        // Layer 1: curated modulators (richer copy, wins on overlap).
        for (substrate in among) {
            val enzymes = majorEnzymesFor(substrate)
            if (enzymes.isEmpty()) continue
            val substrateLower = substrate.lowercase()
            for (m in modulators) {
                if (m.origin != ModulatorOrigin.SUBSTANCE || m.enzyme !in enzymes) continue
                val modulatorPresent = among.any { other ->
                    other.lowercase() != substrateLower && m.matchers.contains(canonical(other))
                }
                if (!modulatorPresent) continue
                results += makeEffect(m, substrate)
                seen += "${m.id}|${substrate.lowercase()}"
            }
        }

        // Layer 2: tag-derived enzyme interactions (broader coverage).
        val substanceLower = among.map { it.lowercase() }.toSet()
        val canonicalForDisplay = mutableMapOf<String, String>()
        for (name in among) canonicalForDisplay.putIfAbsent(name.lowercase(), name)

        for (substance in among) {
            val key = canonical(substance)
            val victims = tagIndex[key] ?: continue
            for (otherLower in substanceLower) {
                if (otherLower == key) continue
                val otherCanonical = canonical(canonicalForDisplay[otherLower] ?: otherLower)
                val hit = victims[otherCanonical] ?: continue
                val dedup = "$key|$otherCanonical"
                if (!seen.add(dedup)) continue
                // The dedup key is consumed before the enzyme is parsed, exactly as
                // upstream: an unrecognised enzyme is dropped, not left to be
                // re-offered by a later iteration of the same pair.
                val enzyme = Enzyme.fromToken(hit.enzyme) ?: continue
                val direction = if (hit.direction == "induces") {
                    ModulationDirection.INDUCES
                } else {
                    ModulationDirection.INHIBITS
                }
                val perpetratorDisplay = canonicalForDisplay[key] ?: hit.perpetratorName
                val victimDisplay = canonicalForDisplay[otherLower] ?: hit.victimName
                results += EnzymeEffect(
                    modulatorID = key,
                    origin = ModulatorOrigin.SUBSTANCE,
                    substrate = victimDisplay,
                    enzyme = enzyme,
                    direction = direction,
                    modulatorName = perpetratorDisplay,
                    userNote = templateNote(
                        perpetrator = perpetratorDisplay,
                        enzyme = enzyme,
                        direction = direction,
                        strength = hit.strength,
                        victimType = hit.victimType,
                    ),
                )
            }
        }

        return results
    }

    /** Generate a user-facing note for a tag-derived enzyme interaction. */
    fun templateNote(
        perpetrator: String,
        enzyme: Enzyme,
        direction: ModulationDirection,
        strength: String?,
        victimType: String,
    ): String {
        // "strong" → "strongly ", "moderate" → "moderately ", absent → "".
        val strengthWord = strength?.let { "${it}ly " } ?: ""
        val verb = if (direction == ModulationDirection.INHIBITS) "inhibits" else "induces"
        val consequence = when {
            victimType == "prodrug" && direction == ModulationDirection.INHIBITS ->
                "blocking the activation pathway for prodrugs that depend on it"
            direction == ModulationDirection.INHIBITS -> "raising the levels of drugs cleared by it"
            else -> "lowering the levels of drugs cleared by it"
        }
        return "$perpetrator $strengthWord$verb ${enzyme.displayName}, $consequence."
    }

    private fun makeEffect(m: EnzymeModulator, substrate: String): EnzymeEffect = EnzymeEffect(
        modulatorID = m.id,
        origin = m.origin,
        substrate = substrate,
        enzyme = m.enzyme,
        direction = m.direction,
        modulatorName = m.displayName,
        userNote = m.userNote,
    )
}
