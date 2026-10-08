package glass.kagerou.piru.engine

import glass.kagerou.piru.model.ConfidenceTier

/**
 * One `downstream_signalling` row: what a substance's engagement sets off beyond the receptor it binds.
 *
 * Ported from `SubstanceStore`'s downstream-signalling read. The table is prose rather than numbers — a summary
 * per substance per source — so the model is one row and the section reads as a list of attributed statements.
 *
 * ## Why this is separate from the binding table
 * The binding table says what a substance touches. This says what happens after, which is the part a reader
 * actually wants and the part that binding affinities cannot express: two compounds can share a target and
 * diverge entirely in what the cell does next. Upstream keeps them apart for the same reason.
 */
data class DownstreamSignallingHit(
    val substanceId: Long,
    val summary: String,
    val sourceSlug: String,
    val doi: String? = null,
    val pmid: Int? = null,
)

/**
 * One `off_targets` row: a target the substance hits that is *not* its mechanism.
 *
 * Ported from `SubstanceStore`'s off-target read. The three columns that make it a section rather than a list are
 * [concernLevel], [clinicalConsequence] and the affinity — a target with no affinity is trivia, and one with no
 * concern level is a number without a reason to care.
 *
 * ## The name is the point
 * "Off-target" is what separates a substance's pharmacology from its side-effect profile. A reader looking at an
 * interaction or an unexpected effect wants this list, and it is deliberately not merged into the binding table:
 * merging them would make the mechanism indistinguishable from everything else the compound touches.
 */
data class OffTargetHit(
    val id: Long,
    val target: String,
    /**
     * The affinity, in nanometres, whichever of Kᵢ or IC₅₀ the row carries.
     *
     * Named for both because the table has one column for either — the source reported one, not the other — and a
     * caller that assumed Kᵢ would compare two different measurements as if they were the same one.
     */
    val kiOrIc50Nm: Double? = null,
    val concernLevel: String? = null,
    val clinicalConsequence: String? = null,
    val sourceSlug: String,
    val doi: String? = null,
    val pmid: Int? = null,
)

/**
 * One `pharmacogenetics` row: a gene, and what a phenotype of it does to this substance.
 *
 * Ported from `SubstanceStore`'s pharmacogenetics read. The prose is the payload — [phenotypeEffects] describes
 * what a poor or ultra-rapid metaboliser experiences — and it is deliberately not reduced to a direction flag,
 * because the interesting cases differ by gene rather than by sign.
 */
data class PharmacogeneticHit(
    val id: Long,
    val gene: String,
    val phenotypeEffects: String,
    val sourceSlug: String,
    val doi: String? = null,
    val pmid: Int? = null,
)

/**
 * One `bindings` row joined to its substance, source and citation.
 *
 * Ported from `BindingHit` in `SubstanceReadModel+Pharmacology.swift`.
 */
data class BindingHit(
    val id: Long,
    val substanceName: String,
    val target: String,
    /**
     * The receptor with every spelling and assay qualifier folded away, as the
     * pipeline's `normalize_target` writes it: `α2δ-1 (recombinant human)`,
     * `α2δ-1` and `alpha2delta-1 (CACNA2D1)` all read `alpha-2-delta-1`. This is
     * what two rows must share to be measurements of one receptor.
     *
     * Null on a row the pipeline has not normalized, in which case the assembly
     * falls back to folding [target] itself.
     */
    val targetBase: String? = null,
    val action: String,
    val kiNm: Double? = null,
    val ec50Nm: Double? = null,
    val ic50Nm: Double? = null,
    val species: String? = null,
    val sourceSlug: String = "",
    val doi: String? = null,
    val pmid: Int? = null,
    /** Citation-verification grade for this binding, unverified when ungraded. */
    val confidence: ConfidenceTier = ConfidenceTier.UNVERIFIED,
)

/**
 * One `concentration_effects` row of kind `therapeutic_range`: the TDM reference
 * range whose lower bound is the concentration at which the drug acts.
 *
 * The resolver reads it as the occupancy half-max for the classes flagged
 * [ReceptorClasses.ClassDefaults.occupancyHalfMaxFromTherapeuticRange].
 */
data class TherapeuticRangeHit(
    val id: Long,
    val effect: String,
    val concentrationUnit: String,
    val thresholdValue: Double,
    val peakValue: Double? = null,
    val sourceSlug: String = "",
    val doi: String? = null,
    val pmid: Int? = null,
)

/**
 * One `metabolism` row: an enzyme pathway, the metabolite it produces, and what
 * that metabolite is.
 *
 * Ported from `MetabolismHit` in `SubstanceStore+MetabolismModels.swift`.
 */
data class MetabolismHit(
    val id: Long,
    val enzyme: String,
    /**
     * The **enzyme's** share of the parent's clearance — not how much metabolite
     * appears. See [formationFractionPct].
     */
    val fractionOfClearancePct: Double? = null,
    val metaboliteName: String? = null,
    /**
     * The metabolite's own substance name, when the catalog carries it as one.
     *
     * Non-null means the row can link to a real detail screen and that the
     * metabolite's own sourced half-life is available, instead of the scalar
     * columns here.
     */
    val metaboliteSubstanceName: String? = null,
    val metaboliteActive: Boolean? = null,
    val metabolitePotencyVsParentPct: Double? = null,
    /**
     * What [metabolitePotencyVsParentPct] measures.
     *
     * Never assume clinical potency: the column has also carried receptor-affinity
     * ratios — tramadol to M1 reads 20000% from a "~200× MOR affinity" source.
     * Anything that multiplies by the potency must branch on this.
     */
    val metabolitePotencyBasis: MetabolitePotencyBasis? = null,
    /**
     * The receptor or transporter the potency ratio was measured at ("MOR",
     * "NET").
     *
     * A basis alone does not make two ratios comparable: tramadol to M1 is 20000%
     * at MOR, quetiapine to norquetiapine 10000% at NET.
     */
    val metabolitePotencyTarget: String? = null,
    /**
     * Whether the metabolite is the same drug at a different strength. Outranks
     * the potency number — only a [MetaboliteMechanism.SCALED] mechanism with a
     * [MetabolitePotencyBasis.CLINICAL] basis may scale the parent's effect.
     */
    val metaboliteMechanismVsParent: MetaboliteMechanism = MetaboliteMechanism.UNKNOWN,
    /** The metabolite's own elimination half-life in minutes — the field a two-compartment parent-to-metabolite model needs. */
    val metaboliteHalfLifeMinutes: Double? = null,
    /** Percent of an administered parent dose that becomes this metabolite. */
    val formationFractionPct: Double? = null,
    /** The route this row applies to, when route-specific. Null is route-agnostic. */
    val route: String? = null,
    val sourceSlug: String = "",
    val doi: String? = null,
    val pmid: Int? = null,
)

/** What a metabolite's potency ratio measures. */
enum class MetabolitePotencyBasis(val wireValue: String) {
    CLINICAL("clinical"),
    RECEPTOR_AFFINITY("receptor_affinity"),
    IN_VITRO("in_vitro"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String?): MetabolitePotencyBasis? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Whether a metabolite is pharmacologically the parent at a different strength,
 * or a different drug the parent happens to produce.
 *
 * [UNKNOWN] is the default rather than a null on purpose: "not yet classified"
 * and "known not to be a scaled copy" must not be indistinguishable to a caller,
 * and neither may scale the parent's effect.
 */
enum class MetaboliteMechanism(val wireValue: String) {
    /**
     * Same mechanism, different strength — nordazepam to diazepam. A potency
     * ratio converts cleanly. Prodrugs count: the metabolite *is* the drug
     * (psilocybin to psilocin).
     */
    SCALED("scaled"),

    /** A different drug the parent produces — tramadol to M1. Never fold into the parent's curve. */
    DIVERGENT("divergent"),

    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String?): MetaboliteMechanism = entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

/**
 * A substance's `pk_reference` pointer: borrow another substance's PK where this
 * one has none.
 *
 * The derivation layer's answer to a compound nobody has run a PK study on —
 * 2-MMC borrows mephedrone's kinetics rather than shipping occupancy-uncomputable.
 */
data class PKReference(
    val name: String,
    /** The fields the pointer licenses borrowing, lowercased ("vd", "bioavailability", "tmax", "half_life"). */
    val fields: Set<String>,
    val confidence: ConfidenceTier,
)

/** Whether an opioid's morphine-milligram-equivalent factor can be applied at all. */
enum class OpioidConvertibility(val wireValue: String) {
    /** A pure full µ-agonist with a stable oral MME factor. */
    LINEAR("linear"),

    /** Nonlinear, dose-dependent potency (methadone) — never auto-convert. */
    NONLINEAR("nonlinear"),

    /** Dosed in mcg/hr rather than mg (transdermal fentanyl) — a separate unit space. */
    TRANSDERMAL("transdermal"),

    /** A partial agonist with a ceiling (buprenorphine) — MME does not apply. */
    EXCLUDED("excluded"),
    ;

    companion object {
        fun fromWire(value: String?): OpioidConvertibility? = entries.firstOrNull { it.wireValue == value }
    }
}

/** One `opioid_mme` row. */
data class OpioidMmeRow(val mmePerMg: Double, val convertibility: OpioidConvertibility)

/**
 * Concentration-unit conversion for the therapeutic-range read.
 *
 * Ported from `DoseEquivalent`'s `milligramsPerLitre` and `isConvertible`.
 */
object DoseEquivalent {

    /**
     * A concentration in `unit` as milligrams per litre, or null for a unit this
     * build cannot convert.
     *
     * ## The two mu signs again
     * The unit string carries a suffix on some rows — `"ng/mL psilocin"` — so the
     * match is by prefix. And `µg/mL` is written with **either** MICRO SIGN
     * (U+00B5) or GREEK SMALL LETTER MU (U+03BC) depending on which upstream typed
     * it, exactly as in the dose-unit table. Folding the Greek spelling onto the
     * Latin one first is what keeps a microgram per millilitre from failing to
     * convert and silently dropping a substance's therapeutic floor.
     */
    fun milligramsPerLitre(value: Double, unit: String): Double? {
        val normalized = unit.lowercase().replace('μ', 'µ')
        return when {
            normalized.startsWith("pg/ml") -> value * 1e-6
            normalized.startsWith("ng/ml") -> value * 1e-3
            normalized.startsWith("µg/ml") || normalized.startsWith("ug/ml") -> value
            normalized.startsWith("mg/l") -> value
            else -> null
        }
    }

    /**
     * Effect descriptions the conversion refuses.
     *
     * A **fatal or post-mortem** concentration must never be turned into a dose:
     * the number would read as the dose that kills, which is not a thing this app
     * prints, and post-mortem redistribution makes it wrong anyway. A
     * **whole-blood** concentration is not a plasma concentration — the
     * blood-to-plasma ratio is drug-specific and not recorded here.
     */
    fun isConvertible(effect: String): Boolean {
        val text = effect.lowercase()
        val refusals = listOf(
            "fatal", "lethal", "post-mortem", "postmortem", "antemortem", "whole blood", "overdose death",
        )
        return refusals.none { text.contains(it) }
    }
}
