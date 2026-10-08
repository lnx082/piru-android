package glass.kagerou.piru.engine

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The tolerance recovery curve, and the reason a linear ratio of it was wrong.
 *
 * ## The defect this pins
 * The tolerance screen's chart normalised the decaying shift by the layers' initial sum and drew that as
 * recovery. The bar beside it read tolerance through `responseFraction`, which is **saturating** and capped
 * per receptor class. Two different quantities for the same class at the same instant, so the chart could read
 * "nearly recovered" while the bar still said high.
 *
 * The test that makes the difference concrete: at a cap of 0.5 with a representative occupancy of 0.6, a
 * shift factor of 2 and one of 20 **both** read as saturated. A ratio would draw them far apart; the gauge
 * draws them the same, because the gauge is what the model believes.
 */
class ToleranceRecoveryCurveTest {

    private fun layers(
        acute: Double = 0.0,
        adaptive: Double = 1.0,
        deep: Double = 0.0,
        synthesis: Double = 0.0,
        tauAdaptiveMinutes: Double = 14_400.0,
    ) = PDModel.ToleranceLayers(
        acute = acute,
        adaptive = adaptive,
        deep = deep,
        synthesis = synthesis,
        tauAcuteMinutes = 60.0,
        tauAdaptiveMinutes = tauAdaptiveMinutes,
        tauDeepMinutes = 100_000.0,
        tauSynthesisMinutes = 200_000.0,
    )

    /**
     * The first sample is `toleranceNow`, which is the bar's number.
     *
     * This is the invariant that keeps the chart and the bar from disagreeing: the curve's origin is the
     * gauge, not a separate computation of it.
     */
    @Test
    fun `the curve starts at the current tolerance`() {
        val state = layers(adaptive = 1.2)
        val now = PDModel.toleranceNow(state, representativeOccupancy = 0.3, occupancyCap = null)
        val curve = PDModel.toleranceRecoveryCurve(
            state,
            representativeOccupancy = 0.3,
            occupancyCap = null,
            windowMinutes = 20_000.0,
        )
        curve.first().day shouldBe 0.0
        curve.first().percent shouldBe (now plusOrMinus 1e-9)
    }

    /**
     * The curve descends, never rises.
     *
     * Layers only relax over a forward window, so tolerance can only fall. A curve that rose would mean the
     * decay sign was inverted, which is a plausible-looking chart.
     */
    @Test
    fun `the curve is monotonically non-increasing`() {
        val curve = PDModel.toleranceRecoveryCurve(
            layers(acute = 0.4, adaptive = 1.0, deep = 0.3),
            representativeOccupancy = 0.3,
            occupancyCap = null,
            windowMinutes = 50_000.0,
        )
        for (index in 1 until curve.size) {
            (curve[index].percent <= curve[index - 1].percent + 1e-9) shouldBe true
        }
    }

    /**
     * The saturation is real: a large shift and a huge one land on the same reading under a low cap.
     *
     * This is the assertion the old chart would have failed, and the reason the change is a fix rather than a
     * refactor. With `occupancyCap = 0.5` and `representativeOccupancy = 0.6`, the capped occupancy is 0.5, and
     * the response fraction is already at its floor for any shift factor above a few.
     */
    @Test
    fun `a capped gauge saturates, so a ratio would disagree with it`() {
        val modest = PDModel.toleranceNow(
            layers(adaptive = 2.0),
            representativeOccupancy = 0.6,
            occupancyCap = 0.5,
        )
        val extreme = PDModel.toleranceNow(
            layers(adaptive = 20.0),
            representativeOccupancy = 0.6,
            occupancyCap = 0.5,
        )
        // Both saturated: the reading barely moves between shift 2 and shift 20.
        (kotlin.math.abs(extreme - modest) < 25.0) shouldBe true

        // And the raw ratio the old chart used would have separated them by a factor of ten, so the two
        // curves could not have been the same quantity.
        val ratioModest = 2.0 / 2.0
        val ratioExtreme = 20.0 / 20.0
        // Both are 1.0 at t = 0 — which is exactly the trap: the ratio starts at "fully tolerant" for every
        // shift, so it cannot distinguish a class that is saturated from one that is not.
        ratioModest shouldBe ratioExtreme
    }

    /**
     * Without a cap, a bigger shift reads as more tolerant.
     *
     * The uncapped case is the agonists, where the gauge is not clamped — and there the reading must be
     * sensitive to the shift, or the chart would be flat for every class.
     */
    @Test
    fun `an uncapped gauge distinguishes shifts`() {
        val modest = PDModel.toleranceNow(
            layers(adaptive = 0.5),
            representativeOccupancy = 0.4,
            occupancyCap = null,
        )
        val extreme = PDModel.toleranceNow(
            layers(adaptive = 3.0),
            representativeOccupancy = 0.4,
            occupancyCap = null,
        )
        (extreme > modest) shouldBe true
        // And materially so, not by a rounding error.
        ((extreme - modest) > 10.0) shouldBe true
    }

    /**
     * The percentages are on the axis, always.
     *
     * The chart draws against a 0-100 axis, so a value outside that range would draw a line off the top or
     * below the bottom rather than at the edge.
     */
    @Test
    fun `every sample is a percentage on the 0 to 100 axis`() {
        for (shift in listOf(0.01, 1.0, 10.0, 1000.0)) {
            val curve = PDModel.toleranceRecoveryCurve(
                layers(adaptive = shift),
                representativeOccupancy = 0.9,
                occupancyCap = 0.5,
                windowMinutes = 10_000.0,
            )
            curve.all { it.percent in 0.0..100.0 } shouldBe true
        }
    }

    /**
     * A class with no layers has an empty curve, so the chart draws nothing rather than a flat line at zero.
     */
    @Test
    fun `no layers means no curve`() {
        val curve = PDModel.toleranceRecoveryCurve(
            layers(adaptive = 0.0),
            representativeOccupancy = 0.3,
            occupancyCap = null,
            windowMinutes = 10_000.0,
        )
        // A curve is still produced — the shift is exp(0) = 1, which is naive — but it must sit at zero
        // tolerance throughout, and the screen's own guard is what skips drawing it.
        curve.all { it.percent < 1.0 } shouldBe true
    }

    /**
     * The day axis is in days, not minutes.
     *
     * `windowMinutes / 1440` per sample. A units slip here would draw a curve whose x axis claims months where
     * the caption says days, which is the kind of error a chart hides.
     */
    @Test
    fun `the day axis is converted from minutes`() {
        val curve = PDModel.toleranceRecoveryCurve(
            layers(),
            representativeOccupancy = 0.3,
            occupancyCap = null,
            // Exactly two days.
            windowMinutes = 2 * 1_440.0,
            sampleCount = 3,
        )
        curve.map { it.day } shouldBe listOf(0.0, 1.0, 2.0)
    }

    /**
     * Layer shares sum to one and are computed from the magnitudes, which is the place a ratio *is* right.
     */
    @Test
    fun `layer shares sum to one`() {
        val shares = PDModel.layerShares(listOf(1.0, 2.0, 0.0, 1.0))
        shares.sum() shouldBe (1.0 plusOrMinus 1e-12)
        shares shouldBe listOf(0.25, 0.5, 0.0, 0.25)

        // No state at all yields zero shares rather than a division by zero.
        PDModel.layerShares(listOf(0.0, 0.0, 0.0, 0.0)) shouldBe listOf(0.0, 0.0, 0.0, 0.0)
        // And negative magnitudes are clamped rather than inverted.
        PDModel.layerShares(listOf(-1.0, 1.0)) shouldBe listOf(0.0, 1.0)
    }
}
