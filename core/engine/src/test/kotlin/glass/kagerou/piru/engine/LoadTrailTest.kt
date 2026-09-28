package glass.kagerou.piru.engine

import glass.kagerou.piru.model.BindingAction
import glass.kagerou.piru.model.ConfidenceTier
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.jupiter.api.Test

/**
 * The class load trail: a dose log placed on the wall clock, normalised to the
 * user's own recent peak.
 *
 * The arithmetic is [ToleranceSimulation]'s and is tested there. What these cover is
 * what the wrapper adds — which class's contributors are read, where the window
 * sits relative to `now`, and the three ways a trail legitimately comes back
 * empty or flat. The doses are hand-built pharmacology so each case can be
 * exercised alone, exactly as the replay's own suite does.
 */
class LoadTrailTest {

    private val now: Instant = Instant.ofEpochSecond(1_700_000_000)

    /** The axis the app builds [ToleranceReplay.SimDose.timestampMinutes] on: epoch minutes. */
    private val nowMinutes: Double = now.toEpochMilli() / 60_000.0

    private fun engagement(
        target: String,
        action: BindingAction,
        halfMaxNanomolar: Double,
    ) = PharmacologyParameters.TargetEngagement(
        target = target,
        targetBase = PharmacologyParameters.targetBase(target),
        action = action,
        halfMaxNanomolar = halfMaxNanomolar,
        kind = PharmacologyParameters.HalfMaxKind.KI,
        confidence = ConfidenceTier.HIGH,
    )

    /** An oral opioid: 200-minute half-life, one MOR engagement, a 100 mg heavy ceiling. */
    private fun params(
        halfLife: Double = 200.0,
        targets: List<PharmacologyParameters.TargetEngagement> = listOf(
            engagement("MOR", BindingAction.AGONIST, 10.0),
        ),
    ) = PharmacologyParameters(
        molarMassGramsPerMole = 285.0,
        vdLPerKg = 3.5,
        bioavailabilityFraction = 1.0,
        bioavailabilityConfidence = ConfidenceTier.HIGH,
        doseScale = 1.0,
        doseScaleConfidence = ConfidenceTier.HIGH,
        halfLifeMinutes = halfLife,
        vdConfidence = ConfidenceTier.HIGH,
        referenceDoseMg = 100.0,
        suppressesSerotoninSynthesis = false,
        targets = targets,
    )

    private val opioid = mapOf("Morphine" to params())

    /** A dose of [amountMg] logged [minutesAgo] before `now`, on the epoch-minutes axis. */
    private fun dose(minutesAgo: Double, amountMg: Double = 10.0, name: String = "Morphine") =
        ToleranceReplay.SimDose(name, amountMg, nowMinutes - minutesAgo)

    private fun trail(
        doses: List<ToleranceReplay.SimDose>,
        params: Map<String, PharmacologyParameters> = opioid,
        receptorClass: ReceptorClasses.ReceptorClass = ReceptorClasses.ReceptorClass.MU_OPIOID,
        horizonMinutes: Double = 1_440.0,
        stepMinutes: Double = 60.0,
        pastHorizonMinutes: Double = 0.0,
        weightKg: Double = 70.0,
    ) = LoadTrail.loadTrail(
        doses = doses,
        params = params,
        now = now,
        weightKg = weightKg,
        receptorClass = receptorClass,
        horizonMinutes = horizonMinutes,
        stepMinutes = stepMinutes,
        pastHorizonMinutes = pastHorizonMinutes,
    )

    // MARK: - Empty results

    @Test
    fun `A class nothing drives, an empty log and an unusable weight all yield no trail`() {
        // Empty means "no answer", which is drawn differently from a flat line at
        // zero: one is a missing reading, the other is a real one.
        trail(listOf(dose(120.0)), receptorClass = ReceptorClasses.ReceptorClass.GABA).isEmpty() shouldBe true
        trail(emptyList()).isEmpty() shouldBe true
        trail(listOf(dose(120.0)), weightKg = 0.0).isEmpty() shouldBe true
        // A name the pharmacology never resolved has no dose to place.
        trail(listOf(dose(120.0, name = "Unobtainium"))).isEmpty() shouldBe true
    }

    @Test
    fun `A degenerate sampling request yields no trail rather than a bad one`() {
        val doses = listOf(dose(120.0))
        trail(doses, stepMinutes = 0.0).isEmpty() shouldBe true
        trail(doses, horizonMinutes = -1.0).isEmpty() shouldBe true
        trail(doses, pastHorizonMinutes = -1.0).isEmpty() shouldBe true
    }

    // MARK: - The window

    @Test
    fun `Without a past window the trail runs from now to now plus the horizon`() {
        // The forward-only reading: `now` is the first sample, which is what places
        // the user on their own clearance curve.
        val points = trail(listOf(dose(120.0)), horizonMinutes = 1_440.0, stepMinutes = 60.0)
        points.size shouldBe 25
        points.first().date shouldBe now
        points.last().date shouldBe now.plusSeconds(1_440 * 60)
        for (i in 1 until points.size) {
            // The step is in minutes, so a 60-minute step is 3 600 seconds of clock.
            points[i].date shouldBe points[i - 1].date.plusSeconds(60 * 60)
        }
    }

    @Test
    fun `A past window extends the trail backwards over the doses still loading the receptor`() {
        val minutesAgo = 600.0
        val points = trail(
            listOf(dose(minutesAgo)),
            horizonMinutes = 0.0,
            stepMinutes = 60.0,
            pastHorizonMinutes = minutesAgo,
        )
        // From the dose itself (at the window's start) to now, inclusive.
        points.size shouldBe 11
        points.first().date shouldBe now.minusSeconds((minutesAgo * 60).toLong())
        points.last().date shouldBe now
        // Nothing is bound at the instant a dose is swallowed, so the curve starts at
        // zero, rises through the dose's own time-to-peak and is falling by now.
        points.first().load shouldBe 0.0
        (points.maxOf { it.load } > 0.9) shouldBe true
        (points.last().load < points.maxOf { it.load }) shouldBe true
    }

    // MARK: - Normalisation

    @Test
    fun `The trail is a fraction of the user's own peak and clears as the drug leaves`() {
        // Six half-lives on, the same dose reads near zero — which absolute occupancy
        // cannot do for a tight-Kᵢ target, where it pins near 1 and never comes down.
        val minutesAgo = 1_200.0
        val points = trail(
            listOf(dose(minutesAgo)),
            horizonMinutes = 0.0,
            stepMinutes = 60.0,
            pastHorizonMinutes = minutesAgo,
        )
        points.isNotEmpty() shouldBe true
        (points.all { it.load in 0.0..1.0 }) shouldBe true
        // The denominator is the exact peak, so no grid sample quite reaches 1; it is
        // the *fall* that matters, not the maximum.
        (points.maxOf { it.load } > 0.9) shouldBe true
        (points.last().load < 0.05) shouldBe true
    }

    @Test
    fun `Nothing inside the reference window gives a flat zero trail, not an empty one`() {
        // The class is driven — the dose is inside the year-long lookback — but the
        // last three weeks hold nothing, so the reference peak is zero and every
        // sample is zero. This is a reading, and the card draws it as one.
        val points = trail(
            listOf(dose(minutesAgo = 200 * 1_440.0)),
            horizonMinutes = 1_440.0,
            stepMinutes = 60.0,
        )
        points.isNotEmpty() shouldBe true
        points.size shouldBe 25
        (points.all { it.load == 0.0 }) shouldBe true
    }

    @Test
    fun `The sample count follows the step, not the span`() {
        val points = trail(listOf(dose(120.0)), horizonMinutes = 1_440.0, stepMinutes = 180.0)
        // 1 440 / 180 = 8 steps, both ends inclusive.
        points.size shouldBe 9
        points.last().date shouldBe now.plusSeconds(1_440 * 60)
        points[1].date shouldBe points[0].date.plusSeconds(180 * 60)
    }

    @Test
    fun `The reading is about timing rather than size`() {
        // The prefactor is a factor of both the drive and the reference peak, so it
        // cancels: a 5 mg dose and a 500 mg dose of the same substance leave the same
        // trail. That is the point of normalising — the curve answers "how much of
        // what this person was on is left", not "how much did they take".
        val small = trail(listOf(dose(120.0, amountMg = 5.0)), horizonMinutes = 0.0)
        val large = trail(listOf(dose(120.0, amountMg = 500.0)), horizonMinutes = 0.0)
        small.size shouldBe 1
        large.size shouldBe 1
        small.first().load shouldBe (large.first().load plusOrMinus 1e-9)
    }
}
