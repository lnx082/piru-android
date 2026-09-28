package glass.kagerou.piru.engine

import glass.kagerou.piru.model.BindingAction
import glass.kagerou.piru.model.ConfidenceTier
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import org.junit.jupiter.api.Test

/**
 * The replay: how a dose log becomes per-class contributors, and every gate that
 * can drop one on the way.
 *
 * These run on hand-built pharmacology rather than the catalog, which is what lets
 * each gate be exercised alone. The catalog-backed end-to-end run lives in
 * `:core:substance`.
 */
class ToleranceReplayTest {

    private fun engagement(
        target: String,
        action: BindingAction,
        halfMaxNanomolar: Double,
        kind: PharmacologyParameters.HalfMaxKind = PharmacologyParameters.HalfMaxKind.KI,
        confidence: ConfidenceTier = ConfidenceTier.HIGH,
    ) = PharmacologyParameters.TargetEngagement(
        target = target,
        targetBase = PharmacologyParameters.targetBase(target),
        action = action,
        halfMaxNanomolar = halfMaxNanomolar,
        kind = kind,
        confidence = confidence,
    )

    private fun params(
        targets: List<PharmacologyParameters.TargetEngagement> = listOf(
            engagement("MOR", BindingAction.AGONIST, 10.0),
        ),
        vd: Double? = 3.5,
        molarMass: Double? = 285.0,
        bioavailability: Double? = 1.0,
        halfLife: Double? = 200.0,
        tmaxMinutes: Double? = null,
        referenceDoseMg: Double? = 100.0,
        metabolites: List<PharmacologyParameters.MetaboliteContributor> = emptyList(),
        categoryClasses: Set<ReceptorClasses.ReceptorClass> = emptySet(),
        representsClasses: Set<ReceptorClasses.ReceptorClass> = emptySet(),
        suppressesSynthesis: Boolean = false,
        intrinsicEfficacy: Double = 1.0,
        diazepamPerMg: Double? = null,
        opioidMMEPerMg: Double? = null,
    ) = PharmacologyParameters(
        molarMassGramsPerMole = molarMass,
        vdLPerKg = vd,
        bioavailabilityFraction = bioavailability,
        bioavailabilityConfidence = ConfidenceTier.HIGH,
        doseScale = 1.0,
        doseScaleConfidence = ConfidenceTier.HIGH,
        halfLifeMinutes = halfLife,
        vdConfidence = ConfidenceTier.HIGH,
        referenceDoseMg = referenceDoseMg,
        suppressesSerotoninSynthesis = suppressesSynthesis,
        targets = targets,
        tmaxMinutes = tmaxMinutes,
        intrinsicEfficacy = intrinsicEfficacy,
        categoryClasses = categoryClasses,
        representsClasses = representsClasses,
        metabolites = metabolites,
        diazepamPerMg = diazepamPerMg,
        opioidMMEPerMg = opioidMMEPerMg,
    )

    /** An opioid dose of [doseMg] milligrams at minute [at]. */
    private fun dose(doseMg: Double = 10.0, at: Double = 0.0, name: String = "Morphine") =
        ToleranceReplay.SimDose(name, doseMg, at)

    private fun prepare(
        doses: List<ToleranceReplay.SimDose>,
        params: Map<String, PharmacologyParameters>,
        nowMinutes: Double = 1_440.0,
        weightKg: Double = 70.0,
        lookbackDays: Double = ToleranceSimulation.DEFAULT_LOOKBACK_DAYS,
    ) = ToleranceReplay.prepare(doses, params, nowMinutes, weightKg, lookbackDays)

    // MARK: - The window

    @Test
    fun `An empty or unusable window yields no result rather than empty cards`() {
        // Null is "nothing to replay", not "tolerance is zero" — the caller renders an
        // empty card set either way, but the distinction is what keeps a body weight of
        // zero from reading as a fully rested user.
        prepare(emptyList(), mapOf("Morphine" to params())) shouldBe null
        prepare(listOf(dose()), mapOf("Morphine" to params()), weightKg = 0.0) shouldBe null
        // A dose outside the lookback is not in the log.
        prepare(listOf(dose(at = -400.0 * 1_440.0)), mapOf("Morphine" to params()), nowMinutes = 0.0) shouldBe null
    }

    @Test
    fun `The window spans from the earliest in-window dose to now`() {
        val prepared = prepare(
            listOf(dose(at = -120.0), dose(at = -60.0)),
            mapOf("Morphine" to params()),
            nowMinutes = 0.0,
        )!!
        // Not `lookbackDays × 1440`: the window is the log's own length, which is what
        // keeps a two-dose log from walking a year of empty grid.
        prepared.totalMinutes shouldBe 120.0
        prepared.work.size shouldBe 1
    }

    @Test
    fun `A dose whose substance never resolved contributes nothing`() {
        prepare(listOf(dose(name = "Unobtainium")), mapOf("Morphine" to params())) shouldBe null
    }

    // MARK: - One dose

    @Test
    fun `One dose produces one class carrying its peak, its target and its name`() {
        val prepared = prepare(listOf(dose()), mapOf("Morphine" to params()))!!
        val work = prepared.work.single()
        work.receptorClass shouldBe ReceptorClasses.ReceptorClass.MU_OPIOID
        work.contributors.size shouldBe 1
        work.subTargets shouldBe listOf("MOR")
        work.contributorSubstances shouldBe listOf("Morphine")
        // The representative occupancy is the median of this class's per-engagement
        // single-dose peaks — here, the one.
        val peak = ToleranceSimulation.peakOccupancy(
            work.contributors[0].ke, work.contributors[0].ka,
            work.contributors[0].prefactorNanomolar, work.contributors[0].halfMaxNanomolar,
        )
        (abs(work.representativeOccupancy - peak) < 1e-9) shouldBe true
    }

    @Test
    fun `The prefactor is the closed-form dose-to-nanomolar conversion`() {
        // fu · F · dose · doseScale · 1e9 / (Vd·weight · 1000 · molarMass). Pinned
        // directly because every occupancy downstream is linear in it.
        val prepared = prepare(listOf(dose(doseMg = 10.0)), mapOf("Morphine" to params()))!!
        val contributor = prepared.work.single().contributors.single()
        val expected = 1.0 * (1.0 * 10.0 * 1.0 / (3.5 * 70.0)) / 1_000.0 / 285.0 * 1e9
        contributor.prefactorNanomolar shouldBe (expected plusOrMinus 1e-9)
    }

    @Test
    fun `A Tmax wires a real absorption rate, and its absence does not`() {
        val withoutTmax = prepare(listOf(dose()), mapOf("Morphine" to params()))!!
            .work.single().contributors.single()
        withoutTmax.ka shouldBe (4 * withoutTmax.ke plusOrMinus 1e-12)

        val withTmax = prepare(listOf(dose()), mapOf("Morphine" to params(tmaxMinutes = 90.0)))!!
            .work.single().contributors.single()
        withTmax.ka shouldBe (PKModel.estimateKa(90.0, withTmax.ke) plusOrMinus 1e-12)
        // A 90-minute peak against the default's 133 needs *faster* absorption, so the
        // fitted rate is above the elimination-derived default rather than below it.
        (withTmax.ka > 4 * withTmax.ke) shouldBe true
        (PKModel.tmax(withTmax.ke, withTmax.ka) < PKModel.tmax(withoutTmax.ke, withoutTmax.ka)) shouldBe true
    }

    // MARK: - The gates

    @Test
    fun `An off-mechanism target drives no class`() {
        // A MOR *antagonist* is not opioid tolerance: the direction gate is what stops
        // a trazodone's 5-HT2A antagonism from reading as psychedelic tolerance.
        val antagonist = params(targets = listOf(engagement("MOR", BindingAction.ANTAGONIST, 10.0)))
        prepare(listOf(dose()), mapOf("Morphine" to antagonist)) shouldBe null
    }

    @Test
    fun `A target the dose never meaningfully engages is dropped`() {
        // A half-max three orders of magnitude above what the dose reaches: the shift
        // would be far below anything the card reports.
        val weak = params(
            targets = listOf(
                engagement("MOR", BindingAction.AGONIST, 10.0),
                engagement("KOR", BindingAction.AGONIST, 10_000_000.0),
            ),
        )
        val work = prepare(listOf(dose()), mapOf("Morphine" to weak))!!.work.single()
        work.contributors.size shouldBe 1
        work.subTargets shouldBe listOf("MOR")
    }

    @Test
    fun `One receptor engaged through two rows contributes once`() {
        // The catalog states one receptor under several names — "NMDA receptor (PCP
        // site)" beside a bare "NMDA" — and both are the same site.
        val doubled = params(
            targets = listOf(
                engagement("MOR", BindingAction.AGONIST, 10.0),
                engagement("MOR (mu-opioid receptor)", BindingAction.AGONIST, 12.0),
            ),
        )
        val work = prepare(listOf(dose()), mapOf("Morphine" to doubled))!!.work.single()
        work.contributors.size shouldBe 1
        work.subTargets.size shouldBe 1
    }

    // MARK: - The deep gate, through a real log

    @Test
    fun `A therapeutic course never entrenches, and a heavy one does`() {
        // The reference dose is 100 mg, so a 30 mg daily dose escalates at 0.3 and a
        // 200 mg one at 2.0. Only the magnitude differs, which is the point of keying
        // the gate on escalation: at the receptor the two look the same.
        val days = 365
        val light = (0 until days).map { dose(doseMg = 30.0, at = it * 1_440.0) }
        val heavy = (0 until days).map { dose(doseMg = 200.0, at = it * 1_440.0) }
        val now = days * 1_440.0

        val lightCard = ToleranceReplay.simulate(light, mapOf("Morphine" to params()), now, 70.0)
            .getValue(ReceptorClasses.ReceptorClass.MU_OPIOID)
        val heavyCard = ToleranceReplay.simulate(heavy, mapOf("Morphine" to params()), now, 70.0)
            .getValue(ReceptorClasses.ReceptorClass.MU_OPIOID)

        lightCard.sDeep shouldBe 0.0
        (heavyCard.sDeep > 0) shouldBe true
        // Both build the ordinary days-to-weeks shift, and the heavier course builds
        // more of it — occupancy is what the adaptive layer integrates, and a 200 mg
        // dose saturates the target where a 30 mg one does not.
        (lightCard.sAdaptive > 0) shouldBe true
        (heavyCard.sAdaptive > lightCard.sAdaptive) shouldBe true
    }

    // MARK: - Metabolites

    private val nordazepam = PharmacologyParameters.MetaboliteContributor(
        metaboliteName = "Nordazepam",
        halfLifeMinutes = 600.0,
        formationFractionPct = 100.0,
        potencyVsParentPct = 100.0,
        potencyBasis = "clinical",
        mechanismVsParent = "scaled",
    )

    @Test
    fun `A foldable metabolite trails its parent at the parent's own Tmax`() {
        val withMetabolite = params(metabolites = listOf(nordazepam))
        val work = prepare(listOf(dose()), mapOf("Morphine" to withMetabolite))!!.work.single()
        work.contributors.size shouldBe 2

        val parent = work.contributors.first { !it.isMetabolite }
        val metabolite = work.contributors.first { it.isMetabolite }
        val parentTmax = PKModel.tmax(parent.ke, parent.ka)
        metabolite.onset shouldBe (parent.onset + parentTmax plusOrMinus 1e-9)
        // Formation-rate limited: the metabolite appears as the parent is eliminated,
        // so its absorption rate *is* the parent's elimination rate — not its ka.
        metabolite.ka shouldBe (parent.ke plusOrMinus 1e-12)
        metabolite.ke shouldBe (PKModel.keFromHalfLifeMinutes(600.0) plusOrMinus 1e-12)
        // Equipotent and fully formed, so the same prefactor.
        metabolite.prefactorNanomolar shouldBe (parent.prefactorNanomolar plusOrMinus 1e-9)
    }

    @Test
    fun `A metabolite adds a contributor but claims no target and no credit`() {
        // It is a tail, not a dosing event: it must not move the class's
        // representative occupancy or appear as something the user took.
        val withMetabolite = params(metabolites = listOf(nordazepam))
        val work = prepare(listOf(dose()), mapOf("Morphine" to withMetabolite))!!.work.single()
        work.subTargets.size shouldBe 1
        work.contributorSubstances shouldBe listOf("Morphine")
    }

    @Test
    fun `Only a scaled metabolite folds, and a non-clinical basis floors its badge`() {
        fun work(basis: String?, mechanism: String) = prepare(
            listOf(dose()),
            mapOf(
                "Morphine" to params(
                    metabolites = listOf(
                        nordazepam.copy(potencyBasis = basis, mechanismVsParent = mechanism),
                    ),
                ),
            ),
        )?.work?.single()

        // Divergent and unknown are different drugs, not tails.
        work("clinical", "divergent")?.contributors?.size shouldBe 1
        work("clinical", "unknown")?.contributors?.size shouldBe 1
        // A receptor-affinity ratio is not an equivalence, so it badges low.
        val floored = work("receptor_affinity", "scaled")!!.contributors.first { it.isMetabolite }
        floored.confidence shouldBe ConfidenceTier.LOW
        val clinical = work("clinical", "scaled")!!.contributors.first { it.isMetabolite }
        (clinical.confidence > ConfidenceTier.LOW) shouldBe true
    }

    // MARK: - The missing-PK fallback

    @Test
    fun `A PK-less substance is modelled as its class representative`() {
        // No Vd means no concentration can be computed at all — which is the ordinary
        // case for the research-chemical tail, not an error.
        val pkLess = params(
            vd = null,
            referenceDoseMg = 100.0,
            categoryClasses = setOf(ReceptorClasses.ReceptorClass.MU_OPIOID),
        )
        val representative = params(
            representsClasses = setOf(ReceptorClasses.ReceptorClass.MU_OPIOID),
        )
        val prepared = prepare(
            listOf(dose(doseMg = 50.0, name = "DesignerOpioid")),
            mapOf("DesignerOpioid" to pkLess, "Morphine" to representative),
        )!!
        val work = prepared.work.single()
        work.receptorClass shouldBe ReceptorClasses.ReceptorClass.MU_OPIOID
        // The logged name keeps credit: the chip says what the user took.
        work.contributorSubstances shouldBe listOf("DesignerOpioid")
        // The dose-fraction proxy: the *same fraction of the heavy ceiling*, 50/100,
        // re-expressed in the representative's own milligrams — which happens to be 50
        // here only because the two ceilings are both 100.
        val contributor = work.contributors.single()
        val expectedPrefactor = 1.0 * (1.0 * 50.0 * 1.0 / (3.5 * 70.0)) / 1_000.0 / 285.0 * 1e9
        contributor.prefactorNanomolar shouldBe (expectedPrefactor plusOrMinus 1e-6)
    }

    @Test
    fun `The fallback pins the surrogate to the one class being modelled`() {
        // Otherwise a Morphine surrogate would import morphine's off-targets into a
        // class the logged substance was never said to engage.
        val pkLess = params(
            vd = null,
            categoryClasses = setOf(ReceptorClasses.ReceptorClass.MU_OPIOID),
        )
        val representative = params(
            targets = listOf(
                engagement("MOR", BindingAction.AGONIST, 10.0),
                engagement("5-HT2A", BindingAction.AGONIST, 10.0),
            ),
            representsClasses = setOf(ReceptorClasses.ReceptorClass.MU_OPIOID),
        )
        val prepared = prepare(
            listOf(dose(name = "DesignerOpioid")),
            mapOf("DesignerOpioid" to pkLess, "Morphine" to representative),
        )!!
        prepared.work.map { it.receptorClass } shouldBe listOf(ReceptorClasses.ReceptorClass.MU_OPIOID)
    }

    @Test
    fun `A validated equivalence is preferred over the dose-fraction proxy`() {
        // An MME factor is a clinical conversion; the proxy is an estimate, and the
        // badge has to say which one it used.
        val pkLess = params(
            vd = null,
            opioidMMEPerMg = 1.5,
            categoryClasses = setOf(ReceptorClasses.ReceptorClass.MU_OPIOID),
        )
        val representative = params(representsClasses = setOf(ReceptorClasses.ReceptorClass.MU_OPIOID))
        val prepared = prepare(
            listOf(dose(doseMg = 10.0, name = "DesignerOpioid")),
            mapOf("DesignerOpioid" to pkLess, "Morphine" to representative),
        )!!
        val contributor = prepared.work.single().contributors.single()
        // 10 mg × 1.5 = 15 mg equivalent, not 10/100 × 100 = 10.
        val expected = 1.0 * (1.0 * 15.0 * 1.0 / (3.5 * 70.0)) / 1_000.0 / 285.0 * 1e9
        contributor.prefactorNanomolar shouldBe (expected plusOrMinus 1e-6)
        contributor.confidence shouldBe ConfidenceTier.LOW
    }

    @Test
    fun `A PK-less substance with no heavy reference is dropped, not guessed at`() {
        // The proxy needs a denominator. Without one there is nothing to stand in, and
        // inventing a dose would be worse than predicting nothing.
        val pkLess = params(vd = null, referenceDoseMg = null)
        prepare(listOf(dose(name = "DesignerOpioid")), mapOf("DesignerOpioid" to pkLess)) shouldBe null
    }

    // MARK: - Representative resolution

    @Test
    fun `Two representatives claiming one class resolve deterministically`() {
        // Upstream first-wins over an unordered dictionary, so its answer can differ
        // between runs. There is no way to match that, so this is a total order — and
        // the point of the test is that it is the *same* answer every time.
        val a = params(representsClasses = setOf(ReceptorClasses.ReceptorClass.MU_OPIOID), vd = 1.0)
        val b = params(representsClasses = setOf(ReceptorClasses.ReceptorClass.MU_OPIOID), vd = 9.0)
        val forward = ToleranceReplay.representativeIndex(mapOf("alpha" to a, "beta" to b))
        val backward = ToleranceReplay.representativeIndex(mapOf("beta" to b, "alpha" to a))
        forward.getValue(ReceptorClasses.ReceptorClass.MU_OPIOID).vdLPerKg shouldBe 1.0
        backward.getValue(ReceptorClasses.ReceptorClass.MU_OPIOID).vdLPerKg shouldBe 1.0
    }

    @Test
    fun `A representative must be able to compute occupancy and carry a reference`() {
        // A surrogate with nothing to scale against is no use as a stand-in.
        val pkLess = params(
            vd = null,
            categoryClasses = setOf(ReceptorClasses.ReceptorClass.MU_OPIOID),
        )
        val unusable = params(representsClasses = setOf(ReceptorClasses.ReceptorClass.MU_OPIOID), vd = null)
        ToleranceReplay.fallbackClasses(pkLess, ToleranceReplay.representativeIndex(mapOf("M" to unusable)))
            .isEmpty() shouldBe true

        val usable = params(representsClasses = setOf(ReceptorClasses.ReceptorClass.MU_OPIOID), vd = 3.5)
        ToleranceReplay.fallbackClasses(pkLess, ToleranceReplay.representativeIndex(mapOf("M" to usable))) shouldBe
            setOf(ReceptorClasses.ReceptorClass.MU_OPIOID)
    }

    @Test
    fun `A rebound-only class is never stand-in modelled`() {
        // The adrenergic classes exist to host a discontinuation warning, not to
        // predict a tolerance curve, so by design they have no representative. A
        // PK-less clonidine must therefore not be surfaced as "incomplete data" —
        // there is no tolerance to predict, complete PK or not.
        val pkLess = params(
            vd = null,
            categoryClasses = setOf(ReceptorClasses.ReceptorClass.ALPHA2_AGONIST),
        )
        ReceptorClasses.ReceptorClass.ALPHA2_AGONIST.hostsReboundWarningOnly shouldBe true
        // With no representative for it anywhere, nothing stands in.
        ToleranceReplay.fallbackClasses(pkLess, emptyMap()).isEmpty() shouldBe true

        // And the diagnostic agrees: no target mechanism (no targets at all) and a
        // category set that is not empty, but no fallback -> the user is told.
        ToleranceReplay.incompleteData(
            listOf(dose(name = "Clonidine")),
            mapOf("Clonidine" to pkLess),
            emptyMap(),
        ) shouldBe setOf("Clonidine")
    }

    // MARK: - The card

    @Test
    fun `The card's confidence is the weakest link, floored by the class kinetics`() {
        val weakTarget = params(
            targets = listOf(
                engagement("MOR", BindingAction.AGONIST, 10.0, confidence = ConfidenceTier.LOW),
            ),
        )
        val card = ToleranceReplay.simulate(
            listOf(dose()), mapOf("Morphine" to weakTarget), 1_440.0, 70.0,
        ).getValue(ReceptorClasses.ReceptorClass.MU_OPIOID)
        // The opioid class's own kinetics are graded low, so the floor is low.
        card.confidence shouldBe ConfidenceTier.LOW
    }

    @Test
    fun `A class with no safety endpoint reports none`() {
        val psychedelic = params(
            targets = listOf(
                engagement("5-HT2A", BindingAction.AGONIST, 10.0),
            ),
        )
        val card = ToleranceReplay.simulate(
            listOf(dose(name = "LSD")), mapOf("LSD" to psychedelic), 1_440.0, 70.0,
        ).getValue(ReceptorClasses.ReceptorClass.PSYCHEDELIC_5HT2A)
        card.safetyShiftFactor shouldBe null
        card.safetyEndpointKind shouldBe null
        card.safetyGap shouldBe null
    }

    @Test
    fun `A class with an endpoint reports its gap`() {
        val card = ToleranceReplay.simulate(
            listOf(dose()), mapOf("Morphine" to params()), 1_440.0, 70.0,
        ).getValue(ReceptorClasses.ReceptorClass.MU_OPIOID)
        // The endpoint moves too — its adaptive layer is a 10-day constant and the
        // window is a day — but far less than the analgesia, which is the gap.
        (card.safetyShiftFactor!! > 1) shouldBe true
        (card.safetyShiftFactor!! < card.shiftFactor) shouldBe true
        card.safetyEndpointKind shouldBe ReceptorClasses.SafetyEndpoint.Kind.RESPIRATORY
        (card.safetyGap!! > 1) shouldBe true
        (card.responseFraction < 1) shouldBe true
    }

    @Test
    fun `A sodium-channel-only log drives nothing`() {
        val inert = params(targets = listOf(engagement("Nav1.7", BindingAction.CHANNEL_BLOCKER, 10.0)))
        ToleranceReplay.simulate(listOf(dose()), mapOf("Lidocaine" to inert), 1_440.0, 70.0).isEmpty() shouldBe true
    }
}
