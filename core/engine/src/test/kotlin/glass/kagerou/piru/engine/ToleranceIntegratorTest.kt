package glass.kagerou.piru.engine

import glass.kagerou.piru.model.ConfidenceTier
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.ln
import org.junit.jupiter.api.Test

/**
 * The right-shift integrator, and the table that feeds it.
 *
 * `PDModelToleranceTests.swift` exercises `PDModel`'s pure dynamics and is already
 * covered by `PDModelTest`; what is tested here is the **composition** on top of
 * it — the four layers, their gates, and the endpoints that break out of them.
 */
class ToleranceIntegratorTest {

    private val ke = ln(2.0) / 300.0
    private val ka = 4 * ke

    /**
     * A contributor whose peak occupancy is [peakOccupancy].
     *
     * The prefactor is solved from the peak rather than written down, so a test says
     * "a dose that occupies a third of the target" instead of quoting a raw
     * nanomolar constant that happens to produce it.
     */
    private fun contributor(
        onset: Double = 0.0,
        peakOccupancy: Double = 0.3,
        halfMaxNanomolar: Double = 100.0,
        escalation: Double = 0.0,
        suppressesSynthesis: Boolean = false,
        intrinsicEfficacy: Double = 1.0,
        activeMinutes: Double? = null,
    ): ToleranceIntegrator.Contributor {
        val peakRatio = peakOccupancy / (1 - peakOccupancy)
        val peakConcentration = PKModel.cmax(ke, ka)
        val prefactor = peakRatio * halfMaxNanomolar / peakConcentration
        return ToleranceIntegrator.Contributor(
            onset = onset,
            expiry = onset + (activeMinutes
                ?: ToleranceIntegrator.decayWindowMinutes(ke, ka, prefactor, halfMaxNanomolar)),
            ke = ke,
            ka = ka,
            prefactorNanomolar = prefactor,
            halfMaxNanomolar = halfMaxNanomolar,
            confidence = ConfidenceTier.HIGH,
            escalation = escalation,
            suppressesSynthesis = suppressesSynthesis,
            intrinsicEfficacy = intrinsicEfficacy,
        )
    }

    private val params = ReceptorClasses.parametersFor(ReceptorClasses.ReceptorClass.PSYCHEDELIC_5HT2A)

    // MARK: - The table

    @Test
    fun `The shift-kinetics table carries every class's constants`() {
        // Spot-checks with the values read out of the source, one per structural
        // shape, so a transcription slip shows up rather than a silently plausible
        // table.
        fun p(c: ReceptorClasses.ReceptorClass) = ReceptorClasses.parametersFor(c)

        // The one class graded medium, on a controlled-human anchor.
        val psychedelic = p(ReceptorClasses.ReceptorClass.PSYCHEDELIC_5HT2A)
        psychedelic.acuteShiftMax shouldBe 1.2
        psychedelic.tauAcuteMinutes shouldBe 18 * 60.0
        psychedelic.adaptiveShiftMax shouldBe 2.5
        psychedelic.tauAdaptiveMinutes shouldBe 3.5 * 1_440.0
        psychedelic.confidence shouldBe ConfidenceTier.MEDIUM
        psychedelic.classDefaultVdLPerKg shouldBe 4.0

        // The opioid: the deepest entrenched layer in the table.
        val opioid = p(ReceptorClasses.ReceptorClass.MU_OPIOID)
        opioid.adaptiveShiftMax shouldBe 1.8
        opioid.deepShiftMax shouldBe 1.1
        opioid.tauDeepMinutes shouldBe 6 * 30 * 1_440.0

        // The stimulant: the strongest acute pool, and the longest deep constant.
        val stimulant = p(ReceptorClasses.ReceptorClass.CATECHOLAMINE_STIMULANT)
        stimulant.acuteShiftMax shouldBe 0.8
        stimulant.deepShiftMax shouldBe 1.6
        stimulant.tauDeepMinutes shouldBe 9 * 30 * 1_440.0

        // The SERT releaser: the only class with a synthesis pool.
        val sert = p(ReceptorClasses.ReceptorClass.SEROTONERGIC_RELEASER)
        sert.synthesisShiftMax shouldBe 1.0
        sert.tauSynthesisMinutes shouldBe 14 * 1_440.0

        // The generic fallback, at the lowest confidence.
        p(ReceptorClasses.ReceptorClass.UNKNOWN).confidence shouldBe ConfidenceTier.UNVERIFIED
    }

    @Test
    fun `Only the classes the literature supports carry a deep layer`() {
        // The deep layer is the one that says "entrenched over months", and it is
        // dark for everything except the opioid and the stimulant — a therapeutic
        // antidepressant user must not accrue it.
        fun deep(c: ReceptorClasses.ReceptorClass) = ReceptorClasses.parametersFor(c).deepShiftMax
        (deep(ReceptorClasses.ReceptorClass.MU_OPIOID) > 0) shouldBe true
        (deep(ReceptorClasses.ReceptorClass.CATECHOLAMINE_STIMULANT) > 0) shouldBe true
        for (other in ReceptorClasses.ReceptorClass.entries) {
            if (other == ReceptorClasses.ReceptorClass.MU_OPIOID ||
                other == ReceptorClasses.ReceptorClass.CATECHOLAMINE_STIMULANT
            ) {
                continue
            }
            deep(other) shouldBe 0.0
        }
    }

    @Test
    fun `Only the SERT releaser class carries a synthesis pool, and only GABA a ladder`() {
        for (c in ReceptorClasses.ReceptorClass.entries) {
            val p = ReceptorClasses.parametersFor(c)
            val expectedSynthesis = if (c == ReceptorClasses.ReceptorClass.SEROTONERGIC_RELEASER) 1.0 else 0.0
            p.synthesisShiftMax shouldBe expectedSynthesis
            val expectedLadder = c == ReceptorClasses.ReceptorClass.GABA
            p.effectEndpoints.isNotEmpty() shouldBe expectedLadder
        }
        // And the GABA ladder's flat rows: anxiolysis, memory and coordination do not
        // tolerize at all, which is the escalation trap rather than a gap.
        val gaba = ReceptorClasses.parametersFor(ReceptorClasses.ReceptorClass.GABA)
        gaba.primaryEffectAxis shouldBe ReceptorClasses.EffectAxis.SEDATION
        val flat = gaba.effectEndpoints.filter { it.adaptiveShiftMax == 0.0 }
            .map { it.axis }.toSet()
        flat shouldBe setOf(
            ReceptorClasses.EffectAxis.ANXIOLYSIS,
            ReceptorClasses.EffectAxis.MEMORY,
            ReceptorClasses.EffectAxis.COORDINATION,
        )
    }

    @Test
    fun `Three classes carry a differential safety endpoint`() {
        fun endpoint(c: ReceptorClasses.ReceptorClass) = ReceptorClasses.parametersFor(c).safetyEndpoint
        endpoint(ReceptorClasses.ReceptorClass.MU_OPIOID)?.kind shouldBe
            ReceptorClasses.SafetyEndpoint.Kind.RESPIRATORY
        endpoint(ReceptorClasses.ReceptorClass.CATECHOLAMINE_STIMULANT)?.kind shouldBe
            ReceptorClasses.SafetyEndpoint.Kind.CARDIOVASCULAR
        endpoint(ReceptorClasses.ReceptorClass.GABA)?.kind shouldBe
            ReceptorClasses.SafetyEndpoint.Kind.COGNITIVE_IMPAIRMENT

        // The opioid endpoint recovers faster than the analgesia it is compared
        // against — that asymmetry *is* the reset-after-break overdose mechanism.
        val opioid = ReceptorClasses.parametersFor(ReceptorClasses.ReceptorClass.MU_OPIOID)
        val endpoint = opioid.safetyEndpoint!!
        (endpoint.adaptiveShiftMax < opioid.adaptiveShiftMax) shouldBe true
        (endpoint.tauAdaptiveMinutes < opioid.tauAdaptiveMinutes) shouldBe true

        // The stimulant endpoint's acute layer is zero: the within-session pressor
        // does not tolerize, so a chronic user redosing still lands on a fresh spike.
        val stimulant = ReceptorClasses.parametersFor(ReceptorClasses.ReceptorClass.CATECHOLAMINE_STIMULANT)
            .safetyEndpoint!!
        stimulant.acuteShiftMax shouldBe 0.0
        (stimulant.adaptiveShiftMax > 0) shouldBe true

        // The GABA endpoint tolerizes not at all: the dose that no longer sedates
        // still impairs memory and coordination at full strength.
        val gaba = ReceptorClasses.parametersFor(ReceptorClasses.ReceptorClass.GABA).safetyEndpoint!!
        gaba.acuteShiftMax shouldBe 0.0
        gaba.adaptiveShiftMax shouldBe 0.0
    }

    // MARK: - Empty and idle

    @Test
    fun `No contributors leaves every layer at zero and the shift at one`() {
        val layers = ToleranceIntegrator.integrate(emptyList(), emptyList(), params, 10_000.0, 30.0)
        layers.shiftFactor shouldBe 1.0
        layers.sAcute shouldBe 0.0
        layers.sAdaptive shouldBe 0.0
        layers.chronicExposure shouldBe 0.0
        // The psychedelic class carries no safety endpoint, so there is nothing to
        // report — null, not 1.
        layers.safetyShiftFactor shouldBe null
    }

    @Test
    fun `A zero-length window is a no-op`() {
        ToleranceIntegrator.integrate(listOf(contributor()), emptyList(), params, 0.0, 30.0)
            .shiftFactor shouldBe 1.0
    }

    @Test
    fun `A class without an endpoint reports none rather than one`() {
        // "Measured and has not tolerized" and "there is no endpoint to measure" are
        // different claims, and only one of them is true for an adenosine dose.
        val adenosine = ReceptorClasses.parametersFor(ReceptorClasses.ReceptorClass.ADENOSINE)
        val layers = ToleranceIntegrator.integrate(listOf(contributor()), emptyList(), adenosine, 1_000.0, 30.0)
        layers.safetyShiftFactor shouldBe null
        layers.safetyGap shouldBe null
    }

    // MARK: - The core dynamic

    @Test
    fun `Clustered dosing builds more shift than spaced, and idle relaxes it`() {
        // Ported from the upstream suite: the shape the literature agrees on, and the
        // one claim the whole engine rests on.
        val daily = (0 until 14).map { contributor(onset = it * 1_440.0) }
        val weekly = (0 until 2).map { contributor(onset = it * 7 * 1_440.0) }
        val span = 14 * 1_440.0

        val dailyLayers = ToleranceIntegrator.integrate(daily, emptyList(), params, span, 30.0)
        val weeklyLayers = ToleranceIntegrator.integrate(weekly, emptyList(), params, span, 30.0)

        (dailyLayers.sAdaptive > weeklyLayers.sAdaptive) shouldBe true
        (weeklyLayers.sAdaptive >= 0) shouldBe true
        // The ceiling is a ceiling.
        (dailyLayers.sAdaptive <= params.adaptiveShiftMax + 1e-9) shouldBe true
    }

    @Test
    fun `Idle time relaxes every layer`() {
        val dose = contributor(onset = 0.0, peakOccupancy = 0.9)
        val early = ToleranceIntegrator.integrate(listOf(dose), emptyList(), params, 3 * 1_440.0, 30.0)
        val late = ToleranceIntegrator.integrate(listOf(dose), emptyList(), params, 60 * 1_440.0, 30.0)
        (early.sAdaptive > late.sAdaptive) shouldBe true
        (late.sAdaptive > 0) shouldBe true
        (late.sAdaptive < early.sAdaptive * 0.5) shouldBe true
    }

    @Test
    fun `The layer never exceeds its ceiling however long the exposure`() {
        // A year of continuous full occupancy against the psychedelic class.
        val chronic = (0 until 365).map {
            contributor(onset = it * 1_440.0, peakOccupancy = 0.99, activeMinutes = 1_500.0)
        }
        val layers = ToleranceIntegrator.integrate(chronic, emptyList(), params, 365 * 1_440.0, 30.0)
        (layers.sAcute <= params.acuteShiftMax + 1e-9) shouldBe true
        (layers.sAdaptive <= params.adaptiveShiftMax + 1e-9) shouldBe true
        (layers.shiftFactor <= kotlin.math.exp(params.acuteShiftMax + params.adaptiveShiftMax) + 1e-6) shouldBe true
    }

    // MARK: - The deep gate

    /**
     * A sustained course: overlapping doses every [everyHours] for [days].
     *
     * Overlapping on purpose. One contributor with a long `activeMinutes` is not a
     * sustained course — its concentration still decays on the drug's own half-life,
     * so the average occupancy over the window collapses toward zero. The
     * chronicity accumulator integrates *occupancy*, not the presence of a
     * contributor, and only re-dosing keeps occupancy up.
     */
    private fun course(
        days: Int,
        escalation: Double,
        peakPerDose: Double = 0.8,
        everyHours: Double = 3.0,
    ) = (0 until (days * 24 / everyHours).toInt()).map {
        contributor(
            onset = it * everyHours * 60.0,
            peakOccupancy = peakPerDose,
            escalation = escalation,
        )
    }

    @Test
    fun `The deep layer stays dark below the escalation threshold, however sustained`() {
        // A therapeutic daily dose is sustained but not heavy. This is the case the
        // gate exists for: over a year of it, the deep layer must never accrue.
        val opioid = ReceptorClasses.parametersFor(ReceptorClasses.ReceptorClass.MU_OPIOID)
        val layers = ToleranceIntegrator.integrate(
            course(365, escalation = 0.3), emptyList(), opioid, 365 * 1_440.0, 60.0,
        )
        layers.sDeep shouldBe 0.0
        // While the adaptive layer — the ordinary days-to-weeks shift — does build.
        (layers.sAdaptive > 0.5) shouldBe true
    }

    @Test
    fun `The deep layer opens under sustained heavy use`() {
        // Same duration, escalation above the heavy ceiling. Only the magnitude
        // changed, and that is the whole point of keying the gate on escalation
        // rather than occupancy: at the receptor the two are indistinguishable.
        val opioid = ReceptorClasses.parametersFor(ReceptorClasses.ReceptorClass.MU_OPIOID)
        val layers = ToleranceIntegrator.integrate(
            course(365, escalation = 1.5), emptyList(), opioid, 365 * 1_440.0, 60.0,
        )
        (layers.sDeep > 0) shouldBe true
        (layers.sDeep <= opioid.deepShiftMax) shouldBe true
    }

    @Test
    fun `A heavy but unsustained binge does not entrench`() {
        // The other half of the gate: heavy, not sustained. One long weekend must not
        // read the same as a year of it.
        val opioid = ReceptorClasses.parametersFor(ReceptorClasses.ReceptorClass.MU_OPIOID)
        val binge = listOf(
            contributor(onset = 0.0, peakOccupancy = 0.95, escalation = 1.5, activeMinutes = 3 * 1_440.0),
        )
        val layers = ToleranceIntegrator.integrate(binge, emptyList(), opioid, 30 * 1_440.0, 60.0)
        layers.sDeep shouldBe 0.0
    }

    // MARK: - The synthesis gate

    @Test
    fun `Only a synthesis-suppressing contributor drives the slow pool`() {
        // Same class, same dose, one flag apart. This is what splits the SERT
        // releasers onto two recovery clocks: MDMA recovers over weeks, mephedrone in
        // days, and the only difference in the input is this boolean.
        val sert = ReceptorClasses.parametersFor(ReceptorClasses.ReceptorClass.SEROTONERGIC_RELEASER)
        val span = 14 * 1_440.0
        val plain = ToleranceIntegrator.integrate(
            listOf(contributor(onset = 0.0, peakOccupancy = 0.9, suppressesSynthesis = false)),
            emptyList(), sert, span, 30.0,
        )
        val entactogen = ToleranceIntegrator.integrate(
            listOf(contributor(onset = 0.0, peakOccupancy = 0.9, suppressesSynthesis = true)),
            emptyList(), sert, span, 30.0,
        )
        plain.sSynthesis shouldBe 0.0
        (entactogen.sSynthesis > 0) shouldBe true
        // And the slow pool outlasts the fast one: two weeks on, that is most of what
        // is left of the shift.
        (entactogen.sSynthesis > entactogen.sAdaptive) shouldBe true
    }

    @Test
    fun `The synthesis pool is inert for a class with no ceiling for it`() {
        // An adenosine dose flagged as a suppressor still accrues nothing — the
        // per-substance flag gates the drive, the class's ceiling gates the layer.
        val adenosine = ReceptorClasses.parametersFor(ReceptorClasses.ReceptorClass.ADENOSINE)
        val layers = ToleranceIntegrator.integrate(
            listOf(contributor(peakOccupancy = 0.9, suppressesSynthesis = true)),
            emptyList(), adenosine, 14 * 1_440.0, 30.0,
        )
        layers.sSynthesis shouldBe 0.0
    }

    // MARK: - Intrinsic efficacy

    @Test
    fun `A partial agonist entrenches less per unit occupancy`() {
        // Mitragynine at μ is the case: the receptor is occupied, but a partial
        // agonist drives less adaptation.
        val opioid = ReceptorClasses.parametersFor(ReceptorClasses.ReceptorClass.MU_OPIOID)
        val span = 14 * 1_440.0
        val full = ToleranceIntegrator.integrate(
            listOf(contributor(peakOccupancy = 0.9, intrinsicEfficacy = 1.0)),
            emptyList(), opioid, span, 30.0,
        )
        val partial = ToleranceIntegrator.integrate(
            listOf(contributor(peakOccupancy = 0.9, intrinsicEfficacy = 0.3)),
            emptyList(), opioid, span, 30.0,
        )
        (partial.sAdaptive < full.sAdaptive) shouldBe true
        // But the acute layer is not efficacy-scaled — it is occupancy alone.
        partial.sAcute shouldBe (full.sAcute plusOrMinus 1e-12)
    }

    // MARK: - Endpoints

    @Test
    fun `The opioid safety endpoint recovers faster than the analgesia it protects against`() {
        // A heavy course, then a break. The analgesic adaptive layer recovers on its
        // ~20-day constant while the respiratory endpoint recovers on its ~10-day one
        // — so the *ratio* between them widens during abstinence. That widening is
        // the reset-after-break hazard: breathing protection is gone while the user
        // still expects their old dose to feel the same.
        //
        // The ratio is the thing to measure, not the difference of two total shift
        // factors: the acute layer also contributes during the course and decays
        // fastest of all (τ = 4 h), which would swamp the comparison.
        val opioid = ReceptorClasses.parametersFor(ReceptorClasses.ReceptorClass.MU_OPIOID)
        val doses = course(days = 60, escalation = 0.0, peakPerDose = 0.6, everyHours = 4.0)
        val courseEnd = 60 * 1_440.0

        val onCourse = ToleranceIntegrator.integrate(doses, emptyList(), opioid, courseEnd, 60.0)
        // Both layers build, and the analgesia has outrun the protection — the gap
        // the card reports.
        (onCourse.sAdaptive > 0) shouldBe true
        (onCourse.sAdaptiveSafety > 0) shouldBe true
        (onCourse.safetyGap!! > 1) shouldBe true

        val oneDayOut = ToleranceIntegrator.integrate(doses, emptyList(), opioid, courseEnd + 1_440.0, 60.0)
        val tenDaysOut = ToleranceIntegrator.integrate(doses, emptyList(), opioid, courseEnd + 10 * 1_440.0, 60.0)
        val earlyRatio = oneDayOut.sAdaptiveSafety / oneDayOut.sAdaptive
        val lateRatio = tenDaysOut.sAdaptiveSafety / tenDaysOut.sAdaptive
        (lateRatio < earlyRatio) shouldBe true
    }

    @Test
    fun `The stimulant pressor does not tolerize within a session`() {
        // Acute 0 on the endpoint against 0.8 on the primary: after one session the
        // high has faded but the cardiovascular spike has not moved at all.
        val stimulant = ReceptorClasses.parametersFor(ReceptorClasses.ReceptorClass.CATECHOLAMINE_STIMULANT)
        val layers = ToleranceIntegrator.integrate(
            listOf(contributor(peakOccupancy = 0.9, activeMinutes = 6 * 60.0)),
            emptyList(), stimulant, 12 * 60.0, 10.0,
        )
        (layers.sAcute > 0) shouldBe true
        layers.sAcuteSafety shouldBe 0.0
        // The within-session spike is untouched — but the endpoint is not frozen: its
        // adaptive layer still moves on its own 12-day constant, which over a single
        // session is barely anything. The safety story is the *gap*, not a shift of 1.
        (layers.safetyShiftFactor!! > 1) shouldBe true
        (layers.safetyShiftFactor!! < layers.shiftFactor) shouldBe true
    }

    // MARK: - The effect ladder

    @Test
    fun `The GABA ladder splits sedation from the effects that do not tolerize`() {
        val gaba = ReceptorClasses.parametersFor(ReceptorClasses.ReceptorClass.GABA)
        val doses = (0 until 30).map {
            contributor(onset = it * 1_440.0, peakOccupancy = 0.9, activeMinutes = 1_500.0)
        }
        val layers = ToleranceIntegrator.integrate(doses, emptyList(), gaba, 30 * 1_440.0, 60.0)

        // Sedation is the primary layer, so it reads the class shift.
        (layers.shiftFactor > 1.5) shouldBe true
        // The flat rows stay exactly 1: no tolerance at all.
        layers.effectShifts[ReceptorClasses.EffectAxis.ANXIOLYSIS] shouldBe 1.0
        layers.effectShifts[ReceptorClasses.EffectAxis.MEMORY] shouldBe 1.0
        layers.effectShifts[ReceptorClasses.EffectAxis.COORDINATION] shouldBe 1.0
        // And the two that do tolerate have moved.
        (layers.effectShifts.getValue(ReceptorClasses.EffectAxis.MYORELAXATION) > 1) shouldBe true
    }

    @Test
    fun `A ladder effect with a zero ceiling never accrues however long the course`() {
        val gaba = ReceptorClasses.parametersFor(ReceptorClasses.ReceptorClass.GABA)
        val doses = (0 until 180).map {
            contributor(onset = it * 1_440.0, peakOccupancy = 0.9, activeMinutes = 1_500.0)
        }
        val layers = ToleranceIntegrator.integrate(doses, emptyList(), gaba, 180 * 1_440.0, 120.0)
        layers.effectShifts[ReceptorClasses.EffectAxis.ANXIOLYSIS] shouldBe 1.0
        // The per-effect response fraction is null for an effect the class does not
        // model, rather than a made-up 1.
        layers.responseFraction(
            axis = ReceptorClasses.EffectAxis.HYPNOTIC,
            params = gaba,
            receptorClass = ReceptorClasses.ReceptorClass.GABA,
            representativeOccupancy = 0.5,
        ) shouldBe null
    }

    @Test
    fun `The gauge reads the shift and the representative occupancy together`() {
        val opioid = ReceptorClasses.parametersFor(ReceptorClasses.ReceptorClass.MU_OPIOID)
        val doses = (0 until 90).map {
            contributor(onset = it * 1_440.0, peakOccupancy = 0.5, activeMinutes = 1_500.0)
        }
        val layers = ToleranceIntegrator.integrate(doses, emptyList(), opioid, 90 * 1_440.0, 60.0)
        val shifted = layers.responseFraction(ReceptorClasses.ReceptorClass.MU_OPIOID, 0.5)
        val naive = ToleranceLayersShim.naive()
        (shifted < naive) shouldBe true
        (shifted > 0) shouldBe true
        // The opioid gauge is uncapped: capping would throw away the escalation a
        // heavy user's dose represents.
        ReceptorClasses.ReceptorClass.MU_OPIOID.gaugeOccupancyCap shouldBe null
    }

    // MARK: - The active window

    @Test
    fun `The decay window covers at least the absorption peak`() {
        val window = ToleranceIntegrator.decayWindowMinutes(ke, ka, prefactorNanomolar = 1_000.0, halfMaxNanomolar = 100.0)
        (window >= PKModel.tmax(ke, ka) * 1.5) shouldBe true
    }

    @Test
    fun `The decay window guards its inputs and grows with the peak`() {
        ToleranceIntegrator.decayWindowMinutes(0.0, ka, 1_000.0, 100.0) shouldBe 0.0
        ToleranceIntegrator.decayWindowMinutes(ke, 0.0, 1_000.0, 100.0) shouldBe 0.0
        ToleranceIntegrator.decayWindowMinutes(ke, ka, 0.0, 100.0) shouldBe 0.0
        ToleranceIntegrator.decayWindowMinutes(ke, ka, 1_000.0, 0.0) shouldBe 0.0
        val small = ToleranceIntegrator.decayWindowMinutes(ke, ka, 1_000.0, 100.0)
        val large = ToleranceIntegrator.decayWindowMinutes(ke, ka, 10_000.0, 100.0)
        (large > small) shouldBe true
    }

    @Test
    fun `A contributor is never pruned before it has acted`() {
        // The window is what the integrator uses to decide a contributor is spent. If
        // it could come out shorter than the peak, a dose would be dropped mid-rise
        // and its tolerance silently lost.
        val c = contributor(peakOccupancy = 0.9)
        (c.expiry > c.onset + PKModel.tmax(ke, ka)) shouldBe true
    }

    /** The un-shifted gauge, for comparison: `S = 1` reads full response at any occupancy. */
    private object ToleranceLayersShim {
        fun naive(): Double = PDModel.responseFraction(shiftFactor = 1.0, representativeOccupancy = 0.5)
    }
}
