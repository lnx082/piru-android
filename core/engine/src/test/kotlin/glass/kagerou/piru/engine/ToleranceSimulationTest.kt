package glass.kagerou.piru.engine

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import org.junit.jupiter.api.Test

/**
 * The replay's pure helpers: cadence, occupancy, memory and the load curve.
 *
 * Two of these carry a trap worth a test of its own — the regularity factor works on
 * raw per-dose onsets rather than calendar days, and the median is the **upper**
 * middle rather than the average of the two.
 */
class ToleranceSimulationTest {

    private val opioid = ReceptorClasses.parametersFor(ReceptorClasses.ReceptorClass.MU_OPIOID)
    private val psychedelic = ReceptorClasses.parametersFor(ReceptorClasses.ReceptorClass.PSYCHEDELIC_5HT2A)

    // MARK: - Schedule regularity

    @Test
    fun `Fewer than three distinct onsets has no cadence to judge`() {
        // One or two doses cannot show a pattern, and penalising them for it would be
        // a claim about regularity that the log does not support.
        ToleranceSimulation.scheduleRegularityFactor(emptyList()) shouldBe 1.0
        ToleranceSimulation.scheduleRegularityFactor(listOf(contributor(onset = 0.0))) shouldBe 1.0
        ToleranceSimulation.scheduleRegularityFactor(
            listOf(contributor(onset = 0.0), contributor(onset = 1_440.0)),
        ) shouldBe 1.0
    }

    @Test
    fun `Perfectly even spacing scores one`() {
        val even = (0 until 5).map { contributor(onset = it * 1_440.0) }
        ToleranceSimulation.scheduleRegularityFactor(even) shouldBe (1.0 plusOrMinus 1e-12)
    }

    @Test
    fun `Erratic spacing costs, and never more than thirty percent`() {
        // The factor is a down-only weight on the adaptive drive: a perfectly regular
        // cadence anticipates more than an erratic one delivering the same total.
        val erratic = listOf(
            contributor(onset = 0.0),
            contributor(onset = 60.0),
            contributor(onset = 5_000.0),
            contributor(onset = 5_030.0),
        )
        val factor = ToleranceSimulation.scheduleRegularityFactor(erratic)
        (factor < 1.0) shouldBe true
        (factor >= 0.7) shouldBe true
    }

    @Test
    fun `Several contributors at one onset count once`() {
        // One dose spawns a contributor per engaged target. Counting them
        // individually would invent a burst of doses that never happened.
        val oneDoseThreeTargets = listOf(
            contributor(onset = 0.0),
            contributor(onset = 0.0),
            contributor(onset = 0.0),
            contributor(onset = 1_440.0),
        )
        // Two distinct onsets — below the threshold, so 1 rather than a penalty.
        ToleranceSimulation.scheduleRegularityFactor(oneDoseThreeTargets) shouldBe 1.0
    }

    @Test
    fun `A spawned metabolite does not count as a dosing event`() {
        // Metabolite onsets sit at parent onset + Tmax. Counting them would halve the
        // apparent cadence of a perfectly regular course and read it as erratic.
        val doses = (0 until 5).map { contributor(onset = it * 1_440.0) }
        val withMetabolites = doses + (0 until 5).map {
            contributor(onset = it * 1_440.0 + 200.0, isMetabolite = true)
        }
        ToleranceSimulation.scheduleRegularityFactor(doses) shouldBe (1.0 plusOrMinus 1e-12)
        ToleranceSimulation.scheduleRegularityFactor(withMetabolites) shouldBe (1.0 plusOrMinus 1e-12)
    }

    // MARK: - Median

    @Test
    fun `The median is the upper middle, not the average of the two`() {
        // Averaging would report an occupancy no dose in the log produced, and this
        // value is displayed as the class's representative peak.
        ToleranceSimulation.median(listOf(1.0, 2.0, 3.0, 4.0)) shouldBe 3.0
        ToleranceSimulation.median(listOf(1.0, 2.0, 3.0)) shouldBe 2.0
        ToleranceSimulation.median(listOf(5.0)) shouldBe 5.0
        ToleranceSimulation.median(emptyList()) shouldBe 0.0
    }

    @Test
    fun `The median is order-independent`() {
        ToleranceSimulation.median(listOf(4.0, 1.0, 3.0, 2.0)) shouldBe 3.0
    }

    // MARK: - Occupancy

    @Test
    fun `Peak occupancy is the occupancy the fixture asked for`() {
        val c = contributor(peakOccupancy = 0.3)
        ToleranceSimulation.peakOccupancy(c.ke, c.ka, c.prefactorNanomolar, c.halfMaxNanomolar) shouldBe
            (0.3 plusOrMinus 1e-9)
    }

    @Test
    fun `Peak occupancy guards degenerate input`() {
        // Zero rather than NaN: a contributor with no concentration behind it must
        // drop out of the simulation, not poison every number downstream.
        ToleranceSimulation.peakOccupancy(0.0, TOLERANCE_KA, 1_000.0, 100.0) shouldBe 0.0
        ToleranceSimulation.peakOccupancy(TOLERANCE_KE, 0.0, 1_000.0, 100.0) shouldBe 0.0
        ToleranceSimulation.peakOccupancy(TOLERANCE_KE, TOLERANCE_KA, 0.0, 100.0) shouldBe 0.0
        ToleranceSimulation.peakOccupancy(TOLERANCE_KE, TOLERANCE_KA, 1_000.0, 0.0) shouldBe 0.0
    }

    @Test
    fun `A single contributor's occupancy reduces to the Hill isotherm`() {
        val c = contributor(peakOccupancy = 0.5)
        ToleranceSimulation.combinedOccupancy(listOf(c), PKModel.tmax(c.ke, c.ka)) shouldBe
            (0.5 plusOrMinus 1e-9)
        ToleranceSimulation.combinedOccupancy(emptyList(), 0.0) shouldBe 0.0
    }

    @Test
    fun `Two half-saturated contributors give two thirds, not three quarters`() {
        // Competitive Gaddum summation, not a probabilistic union. The union would
        // over-count co-occupancy at a shared site, and this is the fix that keeps
        // two substances at one receptor honest.
        val tmax = PKModel.tmax(TOLERANCE_KE, TOLERANCE_KA)
        val pair = listOf(contributor(peakOccupancy = 0.5), contributor(peakOccupancy = 0.5))
        ToleranceSimulation.combinedDrive(pair, tmax) shouldBe (2.0 plusOrMinus 1e-9)
        ToleranceSimulation.combinedOccupancy(pair, tmax) shouldBe (2.0 / 3.0 plusOrMinus 1e-9)
    }

    @Test
    fun `A contributor does not act before its onset`() {
        val c = contributor(onset = 100.0)
        ToleranceSimulation.combinedDrive(listOf(c), 50.0) shouldBe 0.0
        (ToleranceSimulation.combinedDrive(listOf(c), 200.0) > 0) shouldBe true
    }

    @Test
    fun `The recent peak finds a spike between grid points`() {
        // The scan steps a grid, so a peak narrower than the step would be stepped
        // over — and this value is the load curve's denominator, so missing it would
        // rescale the whole chart.
        val c = contributor(onset = 0.0, peakOccupancy = 0.9)
        val spike = PKModel.tmax(c.ke, c.ka)
        val peak = ToleranceSimulation.recentPeakDrive(
            contributors = listOf(c),
            endMinutes = 1_000.0,
            windowMinutes = 1_000.0,
            stepMinutes = 500.0,
        )
        // A 500-minute grid straddles the 200-minute peak entirely, so only the exact
        // evaluation at Tmax can find it.
        (abs(spike - 200.0) < 1.0) shouldBe true
        // The return is a *drive* — the unnormalised Σ Cᵢ/Kᵢ — not an occupancy. A
        // peak occupancy of 0.9 is a ratio of 9, and that is what the load curve
        // divides by, so numerator and denominator share the unit.
        peak shouldBe (9.0 plusOrMinus 1e-6)
        (peak / (1 + peak) shouldBe (0.9 plusOrMinus 1e-6))
    }

    @Test
    fun `The recent peak guards a non-positive step`() {
        // The scan loop advances by the step, so a zero step would never terminate.
        ToleranceSimulation.recentPeakDrive(listOf(contributor()), 1_000.0, 1_000.0, 0.0) shouldBe 0.0
        ToleranceSimulation.recentPeakDrive(emptyList(), 1_000.0, 1_000.0, 60.0) shouldBe 0.0
    }

    // MARK: - Memory

    @Test
    fun `Memory is the slowest engaged layer`() {
        // The opioid's deep layer is measured in months, so it credits a dose as a
        // driver far longer than the psychedelic's adaptive one.
        ToleranceSimulation.toleranceMemoryTauMinutes(opioid) shouldBe opioid.tauDeepMinutes
        ToleranceSimulation.toleranceMemoryTauMinutes(psychedelic) shouldBe psychedelic.tauAdaptiveMinutes

        // A class with no slow layer falls back to its acute constant, which is
        // always a candidate even where the acute ceiling is zero.
        val adenosine = ReceptorClasses.parametersFor(ReceptorClasses.ReceptorClass.ADENOSINE)
        ToleranceSimulation.toleranceMemoryTauMinutes(adenosine) shouldBe adenosine.tauAdaptiveMinutes
    }

    @Test
    fun `A substance whose influence has faded stops being listed as a driver`() {
        val onsets = mapOf("recent" to 0.0, "ancient" to -100_000.0)
        val names = listOf("recent", "ancient")
        val kept = ToleranceSimulation.relevantContributors(names, onsets, psychedelic, totalMinutes = 1_000.0)
        kept shouldBe listOf("recent")
    }

    @Test
    fun `A substance at or after now is always kept`() {
        val onsets = mapOf("now" to 1_000.0, "future" to 2_000.0)
        ToleranceSimulation.relevantContributors(
            listOf("now", "future"), onsets, psychedelic, totalMinutes = 1_000.0,
        ) shouldBe listOf("now", "future")
    }

    @Test
    fun `A name with no recorded onset is kept rather than dropped`() {
        // Dropping it would silently under-report who is driving the class, which is
        // the one thing the chip row exists to say.
        ToleranceSimulation.relevantContributors(
            listOf("unknown"), emptyMap(), psychedelic, totalMinutes = 1_000.0,
        ) shouldBe listOf("unknown")
    }

    // MARK: - The load curve

    @Test
    fun `The load curve normalises to the user's own peak and clears as the drug leaves`() {
        // Saturation-immune: occupancy would pin near 1 for a tight-Kᵢ target and
        // never come down, so the curve would never show clearance.
        val c = contributor(onset = 0.0, peakOccupancy = 0.9)
        val trail = ToleranceSimulation.loadTrail(
            contributors = listOf(c),
            totalMinutes = 3_000.0,
            horizonMinutes = 0.0,
            stepMinutes = 60.0,
            pastHorizonMinutes = 3_000.0,
        )
        (trail.isNotEmpty()) shouldBe true
        // The denominator is the *exact* peak, so no grid sample reaches 1 — the
        // maximum sampled load sits just under it, which is correct rather than a
        // rounding artefact.
        val peakLoad = trail.maxOf { it.load }
        (peakLoad <= 1.0) shouldBe true
        (peakLoad > 0.9) shouldBe true
        // And the tail is nearly clear, which is the whole point of normalising to the
        // user's own peak rather than showing raw occupancy.
        (trail.last().load < 0.05) shouldBe true
        // Samples are evenly spaced and end at "now".
        trail.last().minutes shouldBe 3_000.0
    }

    @Test
    fun `The load curve guards its window and returns nothing rather than zeros`() {
        // An empty list means the inputs were degenerate — not that the load is zero,
        // which would draw a flat line at the baseline and read as a real answer.
        val c = listOf(contributor())
        ToleranceSimulation.loadTrail(c, 1_000.0, 0.0, 0.0).isEmpty() shouldBe true
        ToleranceSimulation.loadTrail(c, 1_000.0, -1.0, 60.0).isEmpty() shouldBe true
        ToleranceSimulation.loadTrail(c, 1_000.0, 0.0, 60.0, pastHorizonMinutes = -1.0).isEmpty() shouldBe true
    }
}
