package glass.kagerou.piru.engine

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.exp
import kotlin.math.ln
import org.junit.jupiter.api.Test

/**
 * The repeated-dose plateau, and every guard around it.
 *
 * Ported from `PiruTests/SteadyStateModelTests.swift`, plus the boundaries the
 * Swift suite leaves to the shared PK model: the `ka ≈ ke` singular branch
 * (reached here through the public `compute`, since the sum is what makes it
 * observable) and the dose-count cap that keeps a degenerate schedule from
 * becoming a multi-second integration.
 *
 * The half-life/interval pairs are built the way the view builds them —
 * `ke = ln2/t½`, `ka = 4·ke` — so the numbers below are the numbers a user sees.
 */
class SteadyStateModelTest {

    private fun make(
        halfLifeMinutes: Double,
        intervalMinutes: Double,
        dose: Double = 20.0,
        ka: Double? = null,
    ): SteadyStateModel.Result? {
        val ke = PKModel.keFromHalfLifeMinutes(halfLifeMinutes)
        return SteadyStateModel.compute(
            dose = dose,
            halfLifeMinutes = halfLifeMinutes,
            intervalMinutes = intervalMinutes,
            ke = ke,
            ka = ka ?: PKModel.defaultKa(ke),
        )
    }

    // MARK: - Guards

    @Test
    fun `Non-positive inputs return nil rather than a plateau of zero`() {
        // Null is "cannot answer". A zero plateau would be a claim about a schedule
        // the model was never given.
        make(halfLifeMinutes = 0.0, intervalMinutes = 1_440.0) shouldBe null
        make(halfLifeMinutes = 1_440.0, intervalMinutes = 0.0) shouldBe null
        make(halfLifeMinutes = 1_440.0, intervalMinutes = 1_440.0, dose = 0.0) shouldBe null
        // The elimination constant is passed in, so it is guarded too: a ke of zero
        // would make every superposed dose permanent.
        SteadyStateModel.compute(
            dose = 20.0, halfLifeMinutes = 1_440.0, intervalMinutes = 1_440.0, ke = 0.0, ka = 0.1,
        ) shouldBe null
    }

    // MARK: - Accumulation

    @Test
    fun `Interval exactly equal to the half-life doubles the load`() {
        // ke·τ = ln2 ⟹ R = 1/(1 − e^(−ln2)) = 1/(1 − 0.5) = 2. Exact, and the one
        // case where the ratio can be written down without a square root.
        val r = make(halfLifeMinutes = 1_440.0, intervalMinutes = 1_440.0)!!
        r.accumulationRatio shouldBe (2.0 plusOrMinus 1e-6)
    }

    @Test
    fun `Dosing far apart barely accumulates`() {
        // τ = 10·t½: each dose is all but gone before the next arrives.
        (make(halfLifeMinutes = 120.0, intervalMinutes = 1_200.0)!!.accumulationRatio < 1.01) shouldBe true
    }

    @Test
    fun `Frequent dosing of a long half-life accumulates a lot`() {
        // Fluoxetine-like: t½ 96 h, once daily.
        (make(halfLifeMinutes = 96 * 60.0, intervalMinutes = 24 * 60.0)!!.accumulationRatio > 5) shouldBe true
    }

    // MARK: - Time to steady state

    @Test
    fun `Time to steady state is a multiple of the half-life alone`() {
        val a = make(halfLifeMinutes = 600.0, intervalMinutes = 360.0)!!
        val b = make(halfLifeMinutes = 600.0, intervalMinutes = 1_440.0, dose = 500.0)!!
        // Independent of both the interval and the dose: the approach is 1 − 2^(−t/t½).
        (a.time95 - b.time95) shouldBe (0.0 plusOrMinus 1e-6)
        a.time95 shouldBe (4.3219 * 600.0 plusOrMinus 1e-2)
        (a.time90 < a.time95 && a.time95 < a.time97) shouldBe true
    }

    // MARK: - The plateau

    @Test
    fun `Peak exceeds trough, both are positive, and the mean sits between them`() {
        val r = make(halfLifeMinutes = 1_440.0, intervalMinutes = 720.0)!!
        (r.peakAmount > r.troughAmount) shouldBe true
        (r.troughAmount > 0) shouldBe true
        (r.averageAmount > r.troughAmount && r.averageAmount < r.peakAmount) shouldBe true
        // The derived numbers are pinned against the sampled ones, not re-derived:
        // the swing is defined as the ratio to the interval mean.
        r.averageAmount shouldBe ((r.peakAmount + r.troughAmount) / 2 plusOrMinus 1e-12)
        r.fluctuationPercent shouldBe (
            (r.peakAmount - r.troughAmount) / r.averageAmount * 100 plusOrMinus 1e-9
            )
    }

    @Test
    fun `The plateau trough is always finite, never the sentinel it starts from`() {
        // The scan starts its minimum at +∞ because it has no first sample to seed
        // with. That sentinel must never leave the function: an infinite trough would
        // flow into the mean and reach the screen as a number.
        for (interval in listOf(1.0, 60.0, 1_440.0, 100_000.0)) {
            val r = make(halfLifeMinutes = 1_440.0, intervalMinutes = interval)!!
            (r.troughAmount.isFinite() && r.peakAmount.isFinite()) shouldBe true
            (r.averageAmount.isFinite() && r.fluctuationPercent.isFinite()) shouldBe true
        }
    }

    @Test
    fun `Peak holds at least one fresh dose`() {
        // Right after a dose the body carries the trough plus a full new dose, so the
        // steady-state peak is never below a single dose.
        val dose = 20.0
        val r = make(halfLifeMinutes = 1_440.0, intervalMinutes = 1_440.0, dose = dose)!!
        (r.peakAmount >= dose * 0.98) shouldBe true
    }

    @Test
    fun `Shorter interval swings less`() {
        val wide = make(halfLifeMinutes = 1_440.0, intervalMinutes = 1_440.0)!!
        val tight = make(halfLifeMinutes = 1_440.0, intervalMinutes = 360.0)!!
        (tight.fluctuationPercent < wide.fluctuationPercent) shouldBe true
    }

    // MARK: - The curve

    @Test
    fun `The curve climbs into the plateau band`() {
        val r = make(halfLifeMinutes = 96 * 60.0, intervalMinutes = 24 * 60.0)!!
        r.curve.size shouldBe SteadyStateModel.CURVE_SAMPLE_COUNT + 1
        val first = r.curve.first()
        val last = r.curve.last()
        first.minutes shouldBe 0.0
        last.minutes shouldBe (r.totalMinutes plusOrMinus 1e-9)
        // The first sample is one dose in the body, the last is somewhere inside the
        // plateau — which is the whole shape the chart draws.
        (first.amount < r.troughAmount) shouldBe true
        (last.amount >= r.troughAmount * 0.98) shouldBe true
        (last.amount <= r.peakAmount * 1.02) shouldBe true
    }

    // MARK: - The ka ≈ ke limit

    @Test
    fun `Near-equal rate constants take the limit branch and stay finite`() {
        // `fractionRemainingInBody` is 0/0 as ka → ke, and the model answers with
        // L'Hôpital's limit (1 + ke·t)·e^(−ke·t). Summing it is the only way to see
        // the branch from outside the PK model, so the expectation below is written
        // out here rather than borrowed from it.
        val halfLife = 300.0
        val ke = ln(2.0) / halfLife
        val ka = ke + 1e-12
        val dose = 100.0
        val interval = 600.0
        val r = make(halfLifeMinutes = halfLife, intervalMinutes = interval, dose = dose, ka = ka)!!

        (r.curve.all { it.amount.isFinite() }) shouldBe true
        (r.peakAmount.isFinite() && r.troughAmount.isFinite()) shouldBe true

        // totalMinutes = max(5·t½·1.2, 6·τ, τ+1) = 3 600, so seven doses are superposed
        // and the curve steps 6 minutes at a time.
        r.totalMinutes shouldBe 3_600.0
        r.curve.size shouldBe 601
        val limit = { t: Double -> dose * (1 + ke * t) * exp(-ke * t) }
        val at600 = (0 until 7).map { 600.0 - it * interval }.filter { it >= 0 }.sumOf(limit)
        r.curve[100].minutes shouldBe 600.0
        r.curve[100].amount shouldBe (at600 plusOrMinus 1e-9)

        // At t = 0 only the oldest dose has elapsed, so the first sample is exactly
        // one dose — a second, independent check that the limit was taken rather
        // than a raw difference quotient that would have gone to NaN.
        r.curve.first().amount shouldBe (dose * 1.0 plusOrMinus 1e-9)
    }

    @Test
    fun `The accumulation ratio ignores the absorption rate`() {
        // It is a function of ke and the interval alone, which is what lets the view
        // quote one number for a schedule without knowing how the dose is absorbed —
        // and what makes the plateau's *height* the only thing ka moves.
        val fast = make(halfLifeMinutes = 300.0, intervalMinutes = 600.0, ka = 100.0)!!
        val slow = make(halfLifeMinutes = 300.0, intervalMinutes = 600.0)!!
        fast.accumulationRatio shouldBe (slow.accumulationRatio plusOrMinus 1e-12)
        // What ka does move is the shape, and a slow one keeps more of the previous
        // dose unabsorbed at the moment the next one lands.
        (fast.curve.last().amount != slow.curve.last().amount) shouldBe true
        (fast.curve.all { it.amount.isFinite() } && slow.curve.all { it.amount.isFinite() }) shouldBe true
    }

    // MARK: - The work cap

    @Test
    fun `The dose cap bounds the work on a degenerate schedule`() {
        // A one-minute interval against a long half-life asks for millions of
        // superposed doses. The cap turns that into 5 000 and the window keeps the
        // grid at 841 points, so this returns at all.
        val r = make(halfLifeMinutes = 100_000.0, intervalMinutes = 1.0)!!
        r.totalMinutes shouldBe (5.0 * 100_000.0 * 1.2 plusOrMinus 1e-6)
        (r.peakAmount > 0) shouldBe true
        (r.curve.all { it.amount.isFinite() }) shouldBe true
    }
}
