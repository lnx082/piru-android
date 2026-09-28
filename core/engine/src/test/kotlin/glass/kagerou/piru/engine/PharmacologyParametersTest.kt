package glass.kagerou.piru.engine

import glass.kagerou.piru.model.BindingAction
import glass.kagerou.piru.model.ConfidenceTier
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.model.flooredBy
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The Foundation-A pathway, and the derivation layer under it.
 *
 * Ported from `PiruTests/PharmacologyParametersTests.swift`. That suite runs
 * against the bundled catalog — do the flagship Vd, Kᵢ, EC₅₀ and IC₅₀ seeded
 * into the database resolve into usable occupancy inputs? These run against
 * **hand-built** parameters instead, which is what lets the dose-dependence and
 * weight-dependence properties be pinned without a database behind them. The
 * catalog-backed half lands with the resolver that reads it.
 */
class PharmacologyParametersTest {

    private fun engagement(
        target: String = "DAT",
        action: BindingAction = BindingAction.RELEASING_AGENT,
        halfMaxNanomolar: Double = 100.0,
        kind: PharmacologyParameters.HalfMaxKind = PharmacologyParameters.HalfMaxKind.EC50,
        confidence: ConfidenceTier = ConfidenceTier.HIGH,
    ) = PharmacologyParameters.TargetEngagement(
        target = target,
        targetBase = PharmacologyParameters.targetBase(target),
        action = action,
        halfMaxNanomolar = halfMaxNanomolar,
        kind = kind,
        confidence = confidence,
    )

    /** A complete parameter set: caffeine-shaped, so the numbers are recognisable. */
    private fun params(
        molarMass: Double? = 194.19,
        vd: Double? = 0.6,
        f: Double? = 1.0,
        halfLife: Double? = 300.0,
        targets: List<PharmacologyParameters.TargetEngagement> = listOf(
            engagement(target = "Adenosine A2A", action = BindingAction.ANTAGONIST, halfMaxNanomolar = 9_560.0, kind = PharmacologyParameters.HalfMaxKind.KI),
        ),
        doseScale: Double = 1.0,
        fractionUnbound: Double = 1.0,
    ) = PharmacologyParameters(
        molarMassGramsPerMole = molarMass,
        vdLPerKg = vd,
        bioavailabilityFraction = f,
        bioavailabilityConfidence = ConfidenceTier.UNVERIFIED,
        doseScale = doseScale,
        doseScaleConfidence = ConfidenceTier.HIGH,
        halfLifeMinutes = halfLife,
        vdConfidence = ConfidenceTier.HIGH,
        referenceDoseMg = 300.0,
        suppressesSerotoninSynthesis = false,
        targets = targets,
        fractionUnbound = fractionUnbound,
    )

    // MARK: - The pathway is complete only when every input is

    @Test
    fun `A parameter set is computable only with every input present`() {
        params().canComputeOccupancy shouldBe true
        // Each one of the four is load-bearing, and a null in any keeps the
        // prediction closed rather than producing a number from nothing.
        params(vd = null).canComputeOccupancy shouldBe false
        params(molarMass = null).canComputeOccupancy shouldBe false
        params(halfLife = null).canComputeOccupancy shouldBe false
        params(f = null).canComputeOccupancy shouldBe false
        params(targets = emptyList()).canComputeOccupancy shouldBe false
    }

    @Test
    fun `An incomputable set yields no occupancy rather than zero`() {
        // Zero would read as "no engagement", which is a claim. Null is the
        // absence of one.
        params(vd = null).peakPrimaryOccupancy(doseMg = 100.0, weightKg = 70.0).shouldBeNull()
        params(targets = emptyList()).peakPrimaryOccupancy(doseMg = 100.0, weightKg = 70.0).shouldBeNull()
    }

    // MARK: - Occupancy is dose-dependent

    @Test
    fun `Occupancy climbs with the dose and passes half-saturation`() {
        // The property the whole absolute-exposure pathway exists for. A
        // normalized-shape model could not express it: every dose would produce
        // the same curve.
        val p = params()
        val low = p.peakPrimaryOccupancy(doseMg = 50.0, weightKg = 70.0)!!
        val high = p.peakPrimaryOccupancy(doseMg = 200.0, weightKg = 70.0)!!
        (low > 0) shouldBe true
        (high > low) shouldBe true
    }

    @Test
    fun `A small dose sits below half-saturation and a large one above it`() {
        // The half-max here is deliberately enormous (9.56 µM) so that ordinary
        // milligram doses land in the sub-saturating regime — which is the
        // regime the Hill step has to get right, since that is where the
        // response is most sensitive to dose.
        val p = params(
            targets = listOf(
                engagement(halfMaxNanomolar = 100.0, kind = PharmacologyParameters.HalfMaxKind.KI),
            ),
        )
        val trace = p.peakPrimaryOccupancy(doseMg = 0.001, weightKg = 70.0)!!
        val saturating = p.peakPrimaryOccupancy(doseMg = 10_000.0, weightKg = 70.0)!!
        (trace < 0.5) shouldBe true
        (saturating > 0.5) shouldBe true
    }

    // MARK: - Weight is the denominator

    @Test
    fun `The same dose yields higher occupancy in a lighter person`() {
        // Body weight is the denominator turning a dose into an exposure — the
        // keystone input of the whole pathway.
        val p = params()
        val light = p.peakPrimaryOccupancy(doseMg = 100.0, weightKg = 50.0)!!
        val heavy = p.peakPrimaryOccupancy(doseMg = 100.0, weightKg = 100.0)!!
        (light > heavy) shouldBe true
    }

    // MARK: - Free drug

    @Test
    fun `The unbound fraction scales occupancy, and can be overridden`() {
        // Occupancy is computed against *free* drug, matching the assay
        // conditions the half-max was measured under. Diazepam is 98%
        // protein-bound, so its free concentration is a fiftieth of the total.
        val p = params(fractionUnbound = 1.0)
        val total = p.peakPrimaryOccupancy(doseMg = 10.0, weightKg = 70.0)!!
        val free = p.peakPrimaryOccupancy(doseMg = 10.0, weightKg = 70.0, unboundFraction = 0.02)!!
        (free < total) shouldBe true
        // And the stored fraction is the default the override replaces.
        params(fractionUnbound = 0.02).peakPrimaryOccupancy(doseMg = 10.0, weightKg = 70.0) shouldBe
            (free plusOrMinus 1e-15)
    }

    // MARK: - Dose scale

    @Test
    fun `A preparation's dose scale multiplies the dose`() {
        // Kratom routes to mitragynine at ~1.5% content, so 5 g of leaf must
        // occupy the target exactly as 75 mg of mitragynine would.
        val direct = params(doseScale = 1.0).peakPrimaryOccupancy(doseMg = 75.0, weightKg = 75.0)!!
        val routed = params(doseScale = 0.015).peakPrimaryOccupancy(doseMg = 5_000.0, weightKg = 75.0)!!
        abs(direct - routed) shouldBe (0.0 plusOrMinus 1e-12)
    }

    // MARK: - Targets

    @Test
    fun `The primary target is the first, and the list is potency-ordered`() {
        // `targets` arrives tightest-first from the resolver, so the occupancy
        // driver is simply the head of the list — no second sort here.
        val tight = engagement(target = "DAT", halfMaxNanomolar = 24.5)
        val loose = engagement(target = "SERT", halfMaxNanomolar = 736.0)
        val p = params(targets = listOf(tight, loose))
        p.primaryTarget shouldBe tight
        p.targets.map { it.halfMaxNanomolar } shouldBe listOf(24.5, 736.0)
    }

    @Test
    fun `An engagement's identity carries its action and its constant kind`() {
        // One target can be both agonised and blocked, by a binding affinity or a
        // functional potency — three axes, and two engagements that differ on any
        // of them are different rows.
        val a = engagement(target = "DAT", action = BindingAction.RELEASING_AGENT, kind = PharmacologyParameters.HalfMaxKind.EC50)
        val b = engagement(target = "DAT", action = BindingAction.REUPTAKE_INHIBITOR, kind = PharmacologyParameters.HalfMaxKind.EC50)
        val c = engagement(target = "DAT", action = BindingAction.RELEASING_AGENT, kind = PharmacologyParameters.HalfMaxKind.KI)
        (a.id != b.id) shouldBe true
        (a.id != c.id) shouldBe true
    }

    @Test
    fun `A target's base folds away spelling and assay qualifiers`() {
        // Two rows stating one receptor under different names are two assays of
        // it, and the engine keeps only the tightest.
        engagement(target = "DAT (release, [3H]-DA uptake)").targetBase shouldBe "dat"
        engagement(target = "SERT").targetBase shouldBe "sert"
        engagement(target = "NMDA receptor (PCP site)").targetBase shouldBe "nmda"
    }

    // MARK: - Metabolites

    @Test
    fun `Only a scaled metabolite may be folded into the parent`() {
        // Nordazepam is the case that works: same mechanism, different strength.
        // Tramadol to M1 is a qualitatively different mechanism that no scalar
        // maps parent occupancy onto, and `unknown` is treated as divergent —
        // the conservative reading.
        fun contributor(mechanism: String) = PharmacologyParameters.MetaboliteContributor(
            metaboliteName = "Nordazepam",
            halfLifeMinutes = 6_000.0,
            formationFractionPct = 100.0,
            potencyVsParentPct = 100.0,
            potencyBasis = "clinical",
            mechanismVsParent = mechanism,
        )
        contributor("scaled").canFold shouldBe true
        contributor("divergent").canFold shouldBe false
        contributor("unknown").canFold shouldBe false
    }

    @Test
    fun `A missing formation or potency factor reads as fully formed and equipotent`() {
        // The conservative direction: an unmeasured fraction counts as much as
        // the parent rather than as none of it, so a known metabolite is never
        // silently dropped from the tail.
        val bare = PharmacologyParameters.MetaboliteContributor(
            metaboliteName = "Nordazepam",
            halfLifeMinutes = 6_000.0,
            mechanismVsParent = "scaled",
        )
        bare.foldPrefactor shouldBe 1.0
        bare.isClinicalBasis shouldBe false
        // And the factors that are present do multiply.
        bare.copy(formationFractionPct = 50.0, potencyVsParentPct = 50.0).foldPrefactor shouldBe 0.25
        bare.copy(potencyBasis = "clinical").isClinicalBasis shouldBe true
    }

    // MARK: - Category inference

    @Test
    fun `A tolerance class is inferred from the category, with the depressant trap avoided`() {
        fun cls(category: SubstanceCategory) =
            ReceptorClasses.ReceptorClass.toleranceClassFor(category)

        cls(SubstanceCategory.STIMULANT) shouldBe ReceptorClasses.ReceptorClass.CATECHOLAMINE_STIMULANT
        cls(SubstanceCategory.EUGEROIC) shouldBe ReceptorClasses.ReceptorClass.CATECHOLAMINE_STIMULANT
        cls(SubstanceCategory.OPIOID) shouldBe ReceptorClasses.ReceptorClass.MU_OPIOID
        cls(SubstanceCategory.BENZODIAZEPINE) shouldBe ReceptorClasses.ReceptorClass.GABA
        cls(SubstanceCategory.PSYCHEDELIC) shouldBe ReceptorClasses.ReceptorClass.PSYCHEDELIC_5HT2A
        cls(SubstanceCategory.DISSOCIATIVE) shouldBe ReceptorClasses.ReceptorClass.NMDA_ANTAGONIST
        cls(SubstanceCategory.EMPATHOGEN) shouldBe ReceptorClasses.ReceptorClass.SEROTONERGIC_RELEASER
        cls(SubstanceCategory.CANNABINOID) shouldBe ReceptorClasses.ReceptorClass.CANNABINOID_CB1
        cls(SubstanceCategory.GABAPENTINOID) shouldBe ReceptorClasses.ReceptorClass.ALPHA2_DELTA

        // The trap: the catalog pins DEPRESSANT on beta-blockers, α₂-agonists,
        // antihistamines and anxiolytics that are not GABA drugs, so the broad
        // category must not map. The true GABAergic depressants carry their own
        // binding rows and route through the target path instead.
        cls(SubstanceCategory.DEPRESSANT).shouldBeNull()
        cls(SubstanceCategory.ANTIHISTAMINE).shouldBeNull()
        cls(SubstanceCategory.NOOTROPIC).shouldBeNull()
        cls(SubstanceCategory.SUPPLEMENT).shouldBeNull()
    }

    @Test
    fun `Only the two adrenergic classes are rebound-warning hosts`() {
        // They predict no meaningful tolerance curve and deliberately have no
        // PK-less representative, so a member must not be surfaced as
        // "incomplete tolerance data".
        ReceptorClasses.ReceptorClass.ALPHA2_AGONIST.hostsReboundWarningOnly shouldBe true
        ReceptorClasses.ReceptorClass.BETA_BLOCKER.hostsReboundWarningOnly shouldBe true
        ReceptorClasses.ReceptorClass.GABA.hostsReboundWarningOnly shouldBe false
    }

    @Test
    fun `The occupancy cap applies only where occupancy is a poor effect proxy`() {
        // Release and reuptake classes cap, because their transporters saturate
        // at recreational doses and felt effect tracks flux. The agonists, PAMs
        // and antagonists stay uncapped: capping those would pretend a heavy
        // user's escalated dose is a half-saturated one.
        ReceptorClasses.ReceptorClass.CATECHOLAMINE_STIMULANT.gaugeOccupancyCap shouldBe 0.5
        ReceptorClasses.ReceptorClass.SEROTONERGIC_RELEASER.gaugeOccupancyCap shouldBe 0.5
        ReceptorClasses.ReceptorClass.ALPHA2_DELTA.gaugeOccupancyCap shouldBe 0.5
        ReceptorClasses.ReceptorClass.MU_OPIOID.gaugeOccupancyCap.shouldBeNull()
        ReceptorClasses.ReceptorClass.GABA.gaugeOccupancyCap.shouldBeNull()
        ReceptorClasses.ReceptorClass.PSYCHEDELIC_5HT2A.gaugeOccupancyCap.shouldBeNull()
    }

    // MARK: - Confidence

    @Test
    fun `A confidence tier orders by trust, so minOf is the floor`() {
        // The ordering is deliberately not the declaration order: `HIGH` is the
        // *largest* tier, so `minOf` gives the less trustworthy one — which is
        // what every caller means by flooring a derived value.
        (ConfidenceTier.HIGH > ConfidenceTier.UNVERIFIED) shouldBe true
        ConfidenceTier.HIGH.flooredBy(ConfidenceTier.LOW) shouldBe ConfidenceTier.LOW
        ConfidenceTier.LOW.flooredBy(ConfidenceTier.HIGH) shouldBe ConfidenceTier.LOW
        minOf(ConfidenceTier.MEDIUM, ConfidenceTier.UNVERIFIED) shouldBe ConfidenceTier.UNVERIFIED
    }

    @Test
    fun `A confidence tier parses a pipeline grade string`() {
        ConfidenceTier.fromGrade("HIGH") shouldBe ConfidenceTier.HIGH
        ConfidenceTier.fromGrade(" med ") shouldBe ConfidenceTier.MEDIUM
        ConfidenceTier.fromGrade("medium") shouldBe ConfidenceTier.MEDIUM
        // A missing or explicitly absent grade is not a trustworthy one.
        ConfidenceTier.fromGrade("LOW") shouldBe ConfidenceTier.LOW
        ConfidenceTier.fromGrade("NONE") shouldBe ConfidenceTier.UNVERIFIED
        ConfidenceTier.fromGrade("") shouldBe ConfidenceTier.UNVERIFIED
        ConfidenceTier.fromGrade(null) shouldBe ConfidenceTier.UNVERIFIED
    }

    private fun abs(value: Double): Double = kotlin.math.abs(value)
}
