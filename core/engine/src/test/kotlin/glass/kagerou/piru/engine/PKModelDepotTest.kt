package glass.kagerou.piru.engine

import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.math.abs
import org.junit.jupiter.api.Test

/**
 * Ported from `PiruTests/DepotPKTests.swift`'s `DepotPKTests` suite.
 *
 * The evidence file behind these numbers
 * (`Specs/evidence/estradiol-tool/ester-pk-parameters.json`) is the oracle: it
 * states that the estrannaise oil-based cypionate fit peaks at about 113 pg/mL at
 * 3.88 days, and the valerate fit at about 306 pg/mL at 1.90 days, for a 5 mg
 * dose. The peaks below are that claim, checked against this implementation.
 *
 * **`DepotCalibrationTests` is not ported**: it needs `DepotCalibration`, which
 * lives in the Injection Levels tool. Tools are outside the MVP, so the
 * calibration fit — and its eight tests — travel with that screen.
 */
class PKModelDepotTest {

    // Population parameters, from ester_pk/estradiol.json.
    private val cypionate = PKModelDepot.DepotParameters(d = 246.0, k1 = 0.0825, k2 = 3.57, k3 = 0.669)
    private val valerate = PKModelDepot.DepotParameters(d = 478.0, k1 = 0.236, k2 = 4.85, k3 = 1.24)

    /** Milliseconds in a day — not seconds. A 1000x error here is invisible to the peak tests. */
    private val day = 86_400_000L
    private fun epoch(days: Double) = Instant.ofEpochMilli((days * day).toLong())

    // MARK: - Single dose

    @Test
    fun `Concentration at t=0 is zero`() {
        PKModelDepot.depotConcentration(5.0, 0.0, cypionate) shouldBe 0.0
    }

    @Test
    fun `Negative time returns zero`() {
        PKModelDepot.depotConcentration(5.0, -1.0, cypionate) shouldBe 0.0
    }

    @Test
    fun `Cypionate 5 mg peaks near 113 pg per mL at about 3_88 days`() {
        var peak = 0.0
        var peakDay = 0.0
        var d = 0.0
        while (d < 30) {
            val c = PKModelDepot.depotConcentration(5.0, d, cypionate)
            if (c > peak) {
                peak = c
                peakDay = d
            }
            d += 0.01
        }
        (abs(peak - 112.7) < 1.0) shouldBe true
        (abs(peakDay - 3.88) < 0.1) shouldBe true
    }

    @Test
    fun `Valerate 5 mg peaks near 306 pg per mL at about 1_90 days`() {
        var peak = 0.0
        var peakDay = 0.0
        var d = 0.0
        while (d < 20) {
            val c = PKModelDepot.depotConcentration(5.0, d, valerate)
            if (c > peak) {
                peak = c
                peakDay = d
            }
            d += 0.01
        }
        (abs(peak - 305.6) < 1.0) shouldBe true
        (abs(peakDay - 1.90) < 0.1) shouldBe true
    }

    @Test
    fun `Concentration is linear in dose`() {
        val one = PKModelDepot.depotConcentration(5.0, 4.0, cypionate)
        val two = PKModelDepot.depotConcentration(10.0, 4.0, cypionate)
        (abs(two - 2 * one) < 1e-9) shouldBe true
    }

    @Test
    fun `Coincident rate constants do not divide by zero`() {
        val p = PKModelDepot.DepotParameters(d = 200.0, k1 = 0.5, k2 = 0.5, k3 = 0.5)
        val c = PKModelDepot.depotConcentration(5.0, 3.0, p)
        c.isFinite() shouldBe true
        (c >= 0) shouldBe true
    }

    // MARK: - Multi-dose

    @Test
    fun `Superposition equals the sum of single doses`() {
        val now = epoch(0.0)
        val injections = listOf(
            PKModelDepot.Injection(now, 5.0),
            PKModelDepot.Injection(Instant.ofEpochMilli(now.toEpochMilli() + 14 * day), 5.0),
        )
        val at = Instant.ofEpochMilli(now.toEpochMilli() + 20 * day)
        val total = PKModelDepot.depotConcentrationMultiDose(injections, at, cypionate)
        val a = PKModelDepot.depotConcentration(5.0, 20.0, cypionate)
        val b = PKModelDepot.depotConcentration(5.0, 6.0, cypionate)
        (abs(total - (a + b)) < 1e-6) shouldBe true
    }

    @Test
    fun `Future injections do not contribute`() {
        val now = epoch(0.0)
        val injections = listOf(
            PKModelDepot.Injection(Instant.ofEpochMilli(now.toEpochMilli() + 10 * day), 5.0),
        )
        PKModelDepot.depotConcentrationMultiDose(injections, now, cypionate) shouldBe 0.0
    }

    @Test
    fun `Curve samples the requested number of points across the range`() {
        val start = epoch(0.0)
        val end = Instant.ofEpochMilli(start.toEpochMilli() + 60 * day)
        val curve = PKModelDepot.depotCurve(
            injections = listOf(PKModelDepot.Injection(start, 5.0)),
            from = start,
            to = end,
            parameters = cypionate,
            pointCount = 100,
        )
        curve.size shouldBe 100
        curve.first().date shouldBe start
        (abs((curve.last().date.toEpochMilli() - end.toEpochMilli()).toDouble()) < 1_000) shouldBe true
    }

    // MARK: - Multi-ester summation

    @Test
    fun `A single summed contribution equals its own depot curve`() {
        val start = epoch(0.0)
        val end = Instant.ofEpochMilli(start.toEpochMilli() + 60 * day)
        val injections = listOf(
            PKModelDepot.Injection(start, 5.0),
            PKModelDepot.Injection(Instant.ofEpochMilli(start.toEpochMilli() + 14 * day), 5.0),
        )
        val single = PKModelDepot.depotCurve(injections, start, end, cypionate, pointCount = 50)
        val summed = PKModelDepot.depotCurveSummed(
            contributions = listOf(PKModelDepot.DepotContribution(injections, cypionate)),
            from = start,
            to = end,
            pointCount = 50,
        )
        summed.total.size shouldBe 50
        summed.contributions.size shouldBe 1
        for (i in single.indices) {
            single[i].date shouldBe summed.total[i].date
            (abs(single[i].concentration - summed.total[i].concentration) < 1e-9) shouldBe true
        }
    }

    @Test
    fun `The summed total is the elementwise sum of two esters on a shared grid`() {
        val start = epoch(0.0)
        val end = Instant.ofEpochMilli(start.toEpochMilli() + 60 * day)
        val valerateInj = listOf(PKModelDepot.Injection(start, 4.0))
        val cypionateInj = listOf(
            PKModelDepot.Injection(Instant.ofEpochMilli(start.toEpochMilli() + 21 * day), 3.0),
        )
        val a = PKModelDepot.depotCurve(valerateInj, start, end, valerate, pointCount = 80)
        val b = PKModelDepot.depotCurve(cypionateInj, start, end, cypionate, pointCount = 80)
        val summed = PKModelDepot.depotCurveSummed(
            contributions = listOf(
                PKModelDepot.DepotContribution(valerateInj, valerate),
                PKModelDepot.DepotContribution(cypionateInj, cypionate),
            ),
            from = start,
            to = end,
            pointCount = 80,
        )
        summed.contributions.size shouldBe 2
        for (i in 0 until 80) {
            summed.contributions[0][i].date shouldBe summed.total[i].date
            (abs(summed.contributions[0][i].concentration - a[i].concentration) < 1e-9) shouldBe true
            (abs(summed.contributions[1][i].concentration - b[i].concentration) < 1e-9) shouldBe true
            (abs(summed.total[i].concentration - (a[i].concentration + b[i].concentration)) < 1e-9) shouldBe true
        }
    }

    @Test
    fun `A one-point summed curve returns a single sample per contribution`() {
        val start = epoch(0.0)
        val injections = listOf(
            PKModelDepot.Injection(Instant.ofEpochMilli(start.toEpochMilli() - 5 * day), 5.0),
        )
        val summed = PKModelDepot.depotCurveSummed(
            contributions = listOf(PKModelDepot.DepotContribution(injections, cypionate)),
            from = start,
            to = start,
            pointCount = 1,
        )
        summed.total.size shouldBe 1
        summed.contributions.size shouldBe 1
        (summed.total[0].concentration > 0) shouldBe true
    }

    @Test
    fun `depotConcentrationSummed adds each contribution at an instant`() {
        val start = epoch(0.0)
        val at = Instant.ofEpochMilli(start.toEpochMilli() + 10 * day)
        val valerateInj = listOf(PKModelDepot.Injection(start, 4.0))
        val cypionateInj = listOf(PKModelDepot.Injection(start, 3.0))
        val summed = PKModelDepot.depotConcentrationSummed(
            contributions = listOf(
                PKModelDepot.DepotContribution(valerateInj, valerate),
                PKModelDepot.DepotContribution(cypionateInj, cypionate),
            ),
            at = at,
        )
        val a = PKModelDepot.depotConcentrationMultiDose(valerateInj, at, valerate)
        val b = PKModelDepot.depotConcentrationMultiDose(cypionateInj, at, cypionate)
        (abs(summed - (a + b)) < 1e-9) shouldBe true
    }
}
