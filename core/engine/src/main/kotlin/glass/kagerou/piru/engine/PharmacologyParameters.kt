package glass.kagerou.piru.engine

import glass.kagerou.piru.model.BindingAction
import glass.kagerou.piru.model.ConfidenceTier
import glass.kagerou.piru.model.ReceptorTargetKey

/**
 * Resolved pharmacology inputs for the absolute-exposure → receptor-occupancy
 * pipeline.
 *
 * Ported from `Piru/Data/Pharmacology/PharmacologyParameters.swift`.
 *
 * It bundles what the engine needs to turn a *dose* into an *occupancy curve*:
 * the molar mass, the best-graded volume of distribution with bioavailability
 * and half-life, and the engaged targets with their half-saturation constants —
 * each value carrying the confidence tier it was graded at, so the engine can run
 * on verified numbers and the UI can badge how much to trust a prediction.
 *
 * ## The house rule this type exists to enforce
 * A predicted number is never presented as a measured one. Every field a
 * prediction rests on carries its tier, and the tier of a derived value is the
 * **weakest** of its inputs.
 */
data class PharmacologyParameters(
    val molarMassGramsPerMole: Double?,
    val vdLPerKg: Double?,
    /**
     * Oral bioavailability as a fraction in `(0, 1]`.
     *
     * When no F was measured the resolver defaults this to **1.0** rather than
     * leaving it null, and flags [bioavailabilityConfidence] unverified. Absolute
     * oral F is underivable without an IV arm for most recreational drugs, and the
     * stored Vd for such drugs is already an *apparent* Vd (V/F) — so
     * `C = F·dose/((V/F)·wt)` is consistent with `F = 1` because the F cancels,
     * while a separately invented F would double-count it. Defaulting to 1 keeps
     * occupancy computable and errs toward *showing* tolerance, which is the
     * safety-positive direction.
     */
    val bioavailabilityFraction: Double?,
    /** Confidence of [bioavailabilityFraction]: the row's grade when F was measured, unverified when it was defaulted. */
    val bioavailabilityConfidence: ConfidenceTier,
    /**
     * Active-fraction multiplier applied to a logged dose before the exposure
     * math: **mg of active compound per mg of the logged substance**.
     *
     * `1.0` for a pure compound. For a preparation routed to its active
     * constituent — cannabis to THC, mushrooms to psilocybin, kratom to
     * mitragynine — the pharmacology comes from the active compound's row and this
     * scales the logged plant mass to active-compound mass. Every dose to
     * concentration site multiplies by it.
     */
    val doseScale: Double,
    /** Confidence of [doseScale]: high for a pure compound, lower for an estimated preparation content fraction. */
    val doseScaleConfidence: ConfidenceTier,
    val halfLifeMinutes: Double?,
    /** Confidence of [vdLPerKg], unverified when no graded Vd row exists. */
    val vdConfidence: ConfidenceTier,
    /**
     * The substance's **reference "heavy" dose** in mg — the denominator of the
     * dose-relative escalation factor that gates the deep tolerance layer.
     *
     * Expressed in the **logged preparation's** mg, the same units the logged dose
     * is in, so the ratio needs no [doseScale]. Null when the substance has no dose
     * ladder, which keeps the escalation factor at zero and the deep gate closed —
     * the conservative fallback: no deep tolerance without evidence of how much
     * constitutes "heavy".
     */
    val referenceDoseMg: Double?,
    /**
     * Whether this substance suppresses **serotonin synthesis** (TPH), the
     * per-substance field that splits the SERT releaser class onto two recovery
     * clocks.
     *
     * True for the methylenedioxy entactogens, whose metabolites down-regulate the
     * synthesis machinery, so recovery waits weeks. False for the cathinone
     * releasers, which spare synthesis and reset in days on transporter
     * resensitisation alone.
     */
    val suppressesSerotoninSynthesis: Boolean,
    /** Engaged targets carrying a numeric half-max, **tightest first**. */
    val targets: List<TargetEngagement>,
    /**
     * Time-to-peak in **minutes** for the coherent PK row, when the source carries
     * one — used to wire a *real* absorption rate instead of the elimination-derived
     * default, so a fast-onset insufflated stimulant and a slow oral
     * extended-release no longer share an absorption shape.
     */
    val tmaxMinutes: Double? = null,
    /** Confidence of [tmaxMinutes] — the source row's grade when read, unverified when none was. */
    val tmaxConfidence: ConfidenceTier = ConfidenceTier.UNVERIFIED,
    /**
     * **Intrinsic efficacy** relative to a full agonist, in `(0, 1]`, scaling how
     * much *tolerance drive* a unit of occupancy produces.
     *
     * Tolerance is driven by occupancy, but a partial or low-efficacy agonist
     * entrenches less per unit occupancy, so the adaptive and synthesis layers'
     * drive is multiplied by this. `1.0` for a full agonist or when unknown;
     * curated below 1 for the known partials — mitragynine, buprenorphine,
     * tianeptine at μ. It scales the *drive*, not the deep escalation gate.
     */
    val intrinsicEfficacy: Double = 1.0,
    /**
     * Tolerance classes inferred from the substance's **pharmacological category**,
     * independent of binding data. Empty when no category maps to a modeled class.
     */
    val categoryClasses: Set<ReceptorClasses.ReceptorClass> = emptySet(),
    /**
     * Study **species** of the coherent row the Vd and half-life were read from,
     * lowercased, or null when unstated or human.
     *
     * Set after interspecies allometric scaling: a non-human row keeps its
     * species-invariant Vd/kg but has its confidence floored, so this string is the
     * honest provenance behind a low [vdConfidence] — the app can caption "predicted
     * from rat kinetics".
     */
    val pkSpecies: String? = null,
    /**
     * Fraction of total plasma concentration that is **unbound** (free):
     * `fu = 1 − proteinBinding/100`.
     *
     * Multiplied into the molar → nM prefactor so occupancy is computed against
     * *free* drug, matching the assay conditions the Kᵢ or EC₅₀ was measured under.
     * Defaults to `1.0` — no binding correction — when no protein binding is
     * available.
     */
    val fractionUnbound: Double = 1.0,
    /** Active metabolites that may extend the parent's occupancy tail. Empty for substances with no metabolite data. */
    val metabolites: List<MetaboliteContributor> = emptyList(),
    /** Diazepam-mg per 1 mg of this substance, or null. See [MetaboliteContributor] for why a missing factor is a real answer. */
    val diazepamPerMg: Double? = null,
    /** Morphine-mg per 1 mg of this substance, or null. */
    val opioidMMEPerMg: Double? = null,
    /** The tolerance classes this substance is the **class representative** for — the PK-complete stand-in a PK-less member is modeled as. */
    val representsClasses: Set<ReceptorClasses.ReceptorClass> = emptySet(),
) {

    /** The most potent engaged target — the occupancy driver. */
    val primaryTarget: TargetEngagement? get() = targets.firstOrNull()

    /** Whether every input occupancy needs is present. */
    val canComputeOccupancy: Boolean
        get() = vdLPerKg != null && molarMassGramsPerMole != null && halfLifeMinutes != null &&
            bioavailabilityFraction != null && primaryTarget != null

    /**
     * Peak fractional occupancy of the primary target for a single oral dose,
     * evaluated at the modeled time-to-peak.
     *
     * A pure composition of the Foundation-A pathway — dose → molar concentration →
     * Hill occupancy — and the function the dose-dependence gate exercises end to
     * end. Null when the inputs are insufficient.
     *
     * [unboundFraction] defaults to [fractionUnbound]; pass an explicit value to
     * override, which is how a test compares `fu = 1` against `fu = 0.02` without
     * rebuilding the parameter set.
     */
    fun peakPrimaryOccupancy(
        doseMg: Double,
        weightKg: Double,
        unboundFraction: Double? = null,
    ): Double? {
        val vd = vdLPerKg ?: return null
        val molarMass = molarMassGramsPerMole ?: return null
        val halfLife = halfLifeMinutes ?: return null
        val f = bioavailabilityFraction ?: return null
        val target = primaryTarget ?: return null

        val ke = PKModel.keFromHalfLifeMinutes(halfLife)
        val ka = PKModel.defaultKa(ke)
        val peak = PKModel.tmax(ke, ka)
        val molar = PKModel.concentrationMolar(
            dose = doseMg * doseScale,
            bioavailability = f,
            vdPerKg = vd,
            weightKg = weightKg,
            molarMassGramsPerMole = molarMass,
            ke = ke,
            ka = ka,
            minutes = peak,
        )
        // The catalog stores half-maxes in nanomolar, and `concentrationMolar`
        // returns mol/L — so the 1e9 here is not a scaling convenience. Dropping it
        // understates occupancy by a factor of a billion and does so silently, into
        // a plausible-looking small number.
        val freeNanomolar = (unboundFraction ?: fractionUnbound) * molar * 1e9
        return PKModel.occupancy(freeNanomolar, target.halfMaxNanomolar)
    }

    /** Which constant a target's [TargetEngagement.halfMaxNanomolar] is, by mechanism. */
    enum class HalfMaxKind(val wireValue: String) {
        /** Binding affinity (agonist, antagonist, PAM). */
        KI("ki"),

        /** Functional release potency (releaser or substrate — amphetamine, MDMA). */
        EC50("ec50"),

        /** Uptake-inhibition potency (reuptake blocker — methylphenidate, cocaine). */
        IC50("ic50"),

        /**
         * The lower bound of the drug's therapeutic plasma range, standing in for a
         * binding constant.
         *
         * Used for classes whose occupancy is read from a therapeutic range instead,
         * because their Kᵢ sits orders of magnitude below the concentrations that
         * act — so a Kᵢ-driven occupancy would read as saturated for days after one
         * dose. Gabapentinoids are the case: pregabalin binds α2δ at 32 nM but acts
         * at 2–5 µg/mL, roughly 12–30 µM, and its effect follows plasma over ~8–12 h.
         */
        THERAPEUTIC_THRESHOLD("therapeuticThreshold"),
        ;

        companion object {
            fun fromWire(value: String?): HalfMaxKind? = entries.firstOrNull { it.wireValue == value }
        }
    }

    /**
     * One engaged receptor or transporter, and the half-saturation constant that
     * drives its occupancy.
     */
    data class TargetEngagement(
        val target: String,
        /**
         * The receptor this row measures, with spelling and assay qualifiers folded
         * away. Two engagements of one substance sharing a base are two assays of one
         * receptor, and the tolerance engine keeps only the tightest.
         */
        val targetBase: String,
        val action: BindingAction,
        /**
         * Half-saturation constant in **nanomolar** — Kᵢ, EC₅₀ or IC₅₀ per [kind].
         * Compared against the free molar concentration times `1e9` in
         * [PKModel.occupancy].
         */
        val halfMaxNanomolar: Double,
        val kind: HalfMaxKind,
        val confidence: ConfidenceTier,
        /** The dataset slug this value came from. */
        val sourceSlug: String = "",
        /**
         * The specific paper this value was measured in, when cited.
         *
         * This is the identity that lets a consumer take a coherent DAT/NET/SERT
         * triple from **one** assay rather than mixing potencies across labs —
         * only ratios *within* one assay are physically meaningful.
         */
        val citationKey: String? = null,
        /** Study species, when recorded — part of assay identity. */
        val species: String? = null,
    ) {
        /** Identity carries the action and the constant kind: one target can be both agonised and blocked, by a binding or a functional assay. */
        val id: String get() = "$target-${action.wireValue}-${kind.wireValue}"
    }

    /**
     * An active metabolite the parent produces, carried so the engine can extend a
     * dose's occupancy tail past the parent's own elimination.
     *
     * The load-bearing case is diazepam → nordazepam, half-life ≈ 100 h and
     * equipotent at GABA-A: without it, diazepam's integrated tolerance and its
     * withdrawal-onset window are both computed from the parent's ~2-day clock
     * instead of the metabolite's ~5-day one.
     */
    data class MetaboliteContributor(
        val metaboliteName: String,
        /** The metabolite's own elimination half-life in minutes. Required — a contributor is only built when one is known, because the PK shape needs it. */
        val halfLifeMinutes: Double,
        /** Percent of a parent dose that becomes this metabolite. */
        val formationFractionPct: Double? = null,
        /** The metabolite's potency relative to the parent, in percent. */
        val potencyVsParentPct: Double? = null,
        /** What [potencyVsParentPct] measures ("clinical", "receptor_affinity", …). A non-clinical basis floors the folded contributor's confidence. */
        val potencyBasis: String? = null,
        /** "scaled", "divergent" or "unknown". Only a `scaled` metabolite may be folded into the parent's curve. */
        val mechanismVsParent: String,
    ) {
        /** Identity is the name: one parent produces a given metabolite once. */
        val id: String get() = metaboliteName

        /**
         * Whether this metabolite may be **summed into** the parent's tolerance
         * curve.
         *
         * Only a `scaled` metabolite qualifies — same mechanism, different strength,
         * as nordazepam is to diazepam. A `divergent` one (tramadol to M1) has a
         * qualitatively different mechanism that no scalar maps parent occupancy
         * onto, and `unknown` is treated as divergent, which is the conservative
         * reading.
         */
        val canFold: Boolean get() = mechanismVsParent == "scaled"

        /**
         * Concentration prefactor relative to the parent: formation fraction times
         * potency ratio.
         *
         * Each missing factor defaults to 100% — a fully-formed, equipotent
         * metabolite, which is the conservative "counts as much as the parent"
         * reading. Nordazepam is 100% × 100% = 1.0.
         */
        val foldPrefactor: Double
            get() = ((formationFractionPct ?: 100.0) / 100) * ((potencyVsParentPct ?: 100.0) / 100)

        /**
         * Whether the potency ratio is a clinical equivalence, and so safe to trust.
         *
         * A non-clinical basis — a receptor affinity constant standing in for a
         * clinical one — floors the folded contributor's confidence.
         */
        val isClinicalBasis: Boolean get() = potencyBasis == "clinical"
    }

    companion object {
        /** The `substance_flags` values the engine reads. Spelled once, so a resolver and a pipeline row cannot drift apart on a string literal. */
        object Flag {
            /** Metabolites suppress tryptophan hydroxylase, routing the substance onto the weeks-scale synthesis pool. */
            const val SUPPRESSES_SEROTONIN_SYNTHESIS = "suppresses-serotonin-synthesis"

            /** Pressed and sold as MDMA while being a DAT-dominant reuptake blocker. Read by the monoamine profile, not by the tolerance engine. */
            const val MISSOLD_AS_MDMA = "missold-as-mdma"

            /** The effect model was calibrated against human data for this substance, so it may *anchor* a simulation. */
            const val MODEL_CALIBRATED = "model-calibrated"
        }

        /** Fold a target name to its engagement key — the same fold the mechanism summary dedups by. */
        fun targetBase(target: String): String = ReceptorTargetKey.fold(target)
    }
}
