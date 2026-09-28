package glass.kagerou.piru.engine

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import org.junit.jupiter.api.Test

/**
 * Ported from `PiruTests/PKModelTests.swift`.
 *
 * The assertions are unchanged. They are the main evidence that this
 * transcription is correct: the numbers here were derived against the Swift
 * implementation, and both are plain IEEE-754 `Double` arithmetic, so agreement
 * is expected to the last bit rather than to a tolerance.
 */
class PKModelTest {

    // MARK: - ke

    @Test
    fun `ke from caffeine half-life (300 min)`() {
        val ke = PKModel.keFromHalfLifeMinutes(300.0)
        // ke = ln(2) / 300
        (abs(ke - ln(2.0) / 300) < 1e-10) shouldBe true
    }

    @Test
    fun `ke from zero half-life returns zero`() {
        PKModel.keFromHalfLifeMinutes(0.0) shouldBe 0.0
    }

    @Test
    fun `ke from negative half-life returns zero`() {
        PKModel.keFromHalfLifeMinutes(-100.0) shouldBe 0.0
    }

    // MARK: - defaultKa

    @Test
    fun `Default ka is 4x ke`() {
        val ke = PKModel.keFromHalfLifeMinutes(300.0)
        PKModel.defaultKa(ke) shouldBe 4 * ke
    }

    // MARK: - concentration

    @Test
    fun `Concentration at t=0 is zero`() {
        val ke = PKModel.keFromHalfLifeMinutes(300.0)
        val ka = PKModel.defaultKa(ke)
        PKModel.concentration(0.0, ke, ka) shouldBe 0.0
    }

    @Test
    fun `Concentration rises then falls`() {
        val ke = PKModel.keFromHalfLifeMinutes(300.0)
        val ka = PKModel.defaultKa(ke)
        val early = PKModel.concentration(30.0, ke, ka)
        val peak = PKModel.concentration(PKModel.tmax(ke, ka), ke, ka)
        val late = PKModel.concentration(1_000.0, ke, ka)
        (early > 0) shouldBe true
        (peak > early) shouldBe true
        (late < peak) shouldBe true
        (late > 0) shouldBe true
    }

    @Test
    fun `Concentration with negative time returns zero`() {
        val ke = PKModel.keFromHalfLifeMinutes(300.0)
        PKModel.concentration(-10.0, ke, PKModel.defaultKa(ke)) shouldBe 0.0
    }

    @Test
    fun `Concentration with zero ke returns zero`() {
        PKModel.concentration(60.0, 0.0, 0.01) shouldBe 0.0
    }

    @Test
    fun `Concentration with zero ka returns zero`() {
        PKModel.concentration(60.0, 0.01, 0.0) shouldBe 0.0
    }

    @Test
    fun `Concentration handles the ka approximately ke singularity`() {
        val ke = 0.005
        val ka = ke + 1e-12
        val c = PKModel.concentration(100.0, ke, ka)
        (c > 0) shouldBe true
        (c.isFinite()) shouldBe true
    }

    @Test
    fun `Concentration is always non-negative`() {
        val ke = PKModel.keFromHalfLifeMinutes(60.0)
        val ka = PKModel.defaultKa(ke)
        var t = 0.0
        while (t <= 5_000) {
            (PKModel.concentration(t, ke, ka) >= 0) shouldBe true
            t += 50
        }
    }

    // MARK: - tmax

    @Test
    fun `Tmax is positive for valid parameters`() {
        val ke = PKModel.keFromHalfLifeMinutes(300.0)
        (PKModel.tmax(ke, PKModel.defaultKa(ke)) > 0) shouldBe true
    }

    @Test
    fun `Tmax returns zero when ka is at or below ke`() {
        PKModel.tmax(0.01, 0.005) shouldBe 0.0
        PKModel.tmax(0.01, 0.01) shouldBe 0.0
    }

    @Test
    fun `Tmax returns zero for invalid parameters`() {
        PKModel.tmax(0.0, 0.01) shouldBe 0.0
        PKModel.tmax(-1.0, 0.01) shouldBe 0.0
        PKModel.tmax(0.01, 0.0) shouldBe 0.0
    }

    @Test
    fun `Faster absorption means earlier peak`() {
        val ke = PKModel.keFromHalfLifeMinutes(300.0)
        (PKModel.tmax(ke, 10 * ke) < PKModel.tmax(ke, 3 * ke)) shouldBe true
    }

    // MARK: - cmax

    @Test
    fun `Cmax is the maximum concentration value`() {
        val ke = PKModel.keFromHalfLifeMinutes(300.0)
        val ka = PKModel.defaultKa(ke)
        val peak = PKModel.cmax(ke, ka)
        val tPeak = PKModel.tmax(ke, ka)
        (peak >= PKModel.concentration(tPeak - 10, ke, ka)) shouldBe true
        (peak >= PKModel.concentration(tPeak + 10, ke, ka)) shouldBe true
    }

    @Test
    fun `Cmax is positive for valid parameters`() {
        val ke = PKModel.keFromHalfLifeMinutes(120.0)
        (PKModel.cmax(ke, PKModel.defaultKa(ke)) > 0) shouldBe true
    }

    // MARK: - estimateKa

    @Test
    fun `Estimated ka produces tmax close to target`() {
        val ke = PKModel.keFromHalfLifeMinutes(300.0)
        val targetTmax = 60.0
        val ka = PKModel.estimateKa(targetTmax, ke)
        (abs(PKModel.tmax(ke, ka) - targetTmax) < 1.0) shouldBe true
    }

    @Test
    fun `estimateKa falls back with zero timeToPeak`() {
        val ke = PKModel.keFromHalfLifeMinutes(300.0)
        PKModel.estimateKa(0.0, ke) shouldBe PKModel.defaultKa(ke)
    }

    @Test
    fun `estimateKa falls back with negative timeToPeak`() {
        val ke = PKModel.keFromHalfLifeMinutes(300.0)
        PKModel.estimateKa(-10.0, ke) shouldBe PKModel.defaultKa(ke)
    }

    @Test
    fun `estimateKa falls back with zero ke`() {
        PKModel.estimateKa(60.0, 0.0) shouldBe 0.0
    }

    @Test
    fun `estimateKa always returns ka at or above ke`() {
        val ke = PKModel.keFromHalfLifeMinutes(300.0)
        for (ttp in listOf(10.0, 30.0, 60.0, 120.0, 240.0)) {
            (PKModel.estimateKa(ttp, ke) >= ke) shouldBe true
        }
    }

    // MARK: - timeToFraction

    @Test
    fun `Time to 3 percent is after peak`() {
        val ke = PKModel.keFromHalfLifeMinutes(300.0)
        val ka = PKModel.defaultKa(ke)
        (PKModel.timeToFraction(0.03, ke, ka) > PKModel.tmax(ke, ka)) shouldBe true
    }

    @Test
    fun `Time to 50 percent is before time to 3 percent`() {
        val ke = PKModel.keFromHalfLifeMinutes(300.0)
        val ka = PKModel.defaultKa(ke)
        (PKModel.timeToFraction(0.5, ke, ka) < PKModel.timeToFraction(0.03, ke, ka)) shouldBe true
    }

    @Test
    fun `Concentration at timeToFraction is approximately the target`() {
        val ke = PKModel.keFromHalfLifeMinutes(300.0)
        val ka = PKModel.defaultKa(ke)
        val fraction = 0.1
        val t = PKModel.timeToFraction(fraction, ke, ka)
        val peak = PKModel.cmax(ke, ka)
        val actual = PKModel.concentration(t, ke, ka)
        (abs(actual - peak * fraction) / (peak * fraction) < 0.01) shouldBe true
    }

    @Test
    fun `timeToFraction returns zero for invalid parameters`() {
        PKModel.timeToFraction(0.5, 0.0, 0.0) shouldBe 0.0
    }

    // MARK: - Absolute exposure

    /**
     * The flaw-closing gate: the normalized curve makes 5 mg and 50 mg identical;
     * the absolute curve must scale linearly with dose at *every* time point.
     * Without this, dose-dependent tolerance is inexpressible.
     */
    @Test
    fun `Absolute concentration is linear in dose`() {
        val ke = PKModel.keFromHalfLifeMinutes(300.0)
        val ka = PKModel.estimateKa(45.0, ke)
        for (t in listOf(10.0, 45.0, 120.0, 600.0, 2_000.0)) {
            val low = PKModel.concentrationAbsolute(5.0, 1.0, 0.6, 70.0, ke, ka, t)
            val high = PKModel.concentrationAbsolute(50.0, 1.0, 0.6, 70.0, ke, ka, t)
            (low > 0) shouldBe true
            (abs(high / low - 10) < 1e-9) shouldBe true
        }
    }

    @Test
    fun `Absolute concentration is inverse in body weight`() {
        val ke = PKModel.keFromHalfLifeMinutes(120.0)
        val ka = PKModel.defaultKa(ke)
        val light = PKModel.concentrationAbsolute(20.0, 1.0, 0.6, 60.0, ke, ka, 60.0)
        val heavy = PKModel.concentrationAbsolute(20.0, 1.0, 0.6, 120.0, ke, ka, 60.0)
        // Twice the mass, half the concentration for the same dose.
        (abs(heavy / light - 0.5) < 1e-9) shouldBe true
    }

    @Test
    fun `Absolute concentration scales with bioavailability`() {
        val ke = PKModel.keFromHalfLifeMinutes(120.0)
        val ka = PKModel.defaultKa(ke)
        val lowF = PKModel.concentrationAbsolute(20.0, 0.4, 0.6, 70.0, ke, ka, 60.0)
        val highF = PKModel.concentrationAbsolute(20.0, 0.8, 0.6, 70.0, ke, ka, 60.0)
        (abs(lowF / highF - 0.5) < 1e-9) shouldBe true
    }

    @Test
    fun `Absolute concentration equals prefactor times shape`() {
        val ke = PKModel.keFromHalfLifeMinutes(300.0)
        val ka = PKModel.estimateKa(45.0, ke)
        val dose = 80.0
        val f = 0.7
        val vdPerKg = 5.0
        val weight = 68.0
        val t = 90.0
        val expected = (f * dose / (vdPerKg * weight)) * PKModel.concentration(t, ke, ka)
        val actual = PKModel.concentrationAbsolute(dose, f, vdPerKg, weight, ke, ka, t)
        (abs(actual - expected) < 1e-12) shouldBe true
    }

    @Test
    fun `Absolute concentration returns zero for invalid inputs`() {
        val ke = PKModel.keFromHalfLifeMinutes(120.0)
        val ka = PKModel.defaultKa(ke)
        PKModel.concentrationAbsolute(10.0, 0.0, 0.6, 70.0, ke, ka, 60.0) shouldBe 0.0
        PKModel.concentrationAbsolute(10.0, 1.0, 0.0, 70.0, ke, ka, 60.0) shouldBe 0.0
        PKModel.concentrationAbsolute(10.0, 1.0, 0.6, 0.0, ke, ka, 60.0) shouldBe 0.0
        PKModel.concentrationAbsolute(-5.0, 1.0, 0.6, 70.0, ke, ka, 60.0) shouldBe 0.0
    }

    /**
     * Ethanol on a mass basis *is* the Widmark equation: peak BAC ≈ Dose / (r ·
     * weight). One US standard drink (14 g) in a 70 kg person with r ≈ 0.6 gives
     * about 0.33 g/L ≈ 0.033 g/dL, a realistic single-drink peak. With fast,
     * ethanol-like absorption the modeled peak approaches that ideal.
     */
    @Test
    fun `Ethanol absolute concentration approximates Widmark BAC`() {
        val ke = PKModel.keFromHalfLifeMinutes(90.0)
        val ka = 100 * ke
        val doseMg = 14_000.0
        val prefactor = 1.0 * doseMg / (0.6 * 70)
        (abs(prefactor - 333.33) < 1) shouldBe true

        val peak = PKModel.concentrationAbsolute(
            doseMg, 1.0, 0.6, 70.0, ke, ka, PKModel.tmax(ke, ka),
        )
        (peak <= prefactor) shouldBe true
        (peak > 0.88 * prefactor) shouldBe true

        val gPerDL = peak / 1_000 / 10
        (gPerDL > 0.02) shouldBe true
        (gPerDL < 0.05) shouldBe true
    }

    // MARK: - Molar concentration

    @Test
    fun `Molar concentration is mass over molar mass`() {
        val ke = PKModel.keFromHalfLifeMinutes(300.0)
        val ka = PKModel.estimateKa(45.0, ke)
        val mw = 194.19
        val mass = PKModel.concentrationAbsolute(100.0, 1.0, 0.6, 70.0, ke, ka, 45.0)
        val molar = PKModel.concentrationMolar(100.0, 1.0, 0.6, 70.0, mw, ke, ka, 45.0)
        (abs(molar - mass / 1_000 / mw) < 1e-15) shouldBe true
        (molar > 0) shouldBe true
    }

    @Test
    fun `Molar concentration returns zero for non-positive molar mass`() {
        val ke = PKModel.keFromHalfLifeMinutes(300.0)
        val ka = PKModel.defaultKa(ke)
        PKModel.concentrationMolar(100.0, 1.0, 0.6, 70.0, 0.0, ke, ka, 45.0) shouldBe 0.0
        PKModel.concentrationMolar(100.0, 1.0, 0.6, 70.0, -1.0, ke, ka, 45.0) shouldBe 0.0
    }

    // MARK: - Real-world parameters

    @Test
    fun `Caffeine PK curve is realistic`() {
        // Caffeine: half-life about 5 hours, time-to-peak about 45 minutes oral.
        val ke = PKModel.keFromHalfLifeMinutes(300.0)
        val ka = PKModel.estimateKa(45.0, ke)
        val tPeak = PKModel.tmax(ke, ka)
        val t5hl = PKModel.timeToFraction(0.03, ke, ka)

        (abs(tPeak - 45) < 2) shouldBe true
        // Mostly eliminated after about five half-lives, 25 hours.
        (t5hl < 2_000) shouldBe true
        (t5hl > 1_000) shouldBe true
    }

    // MARK: - Receptor occupancy

    @Test
    fun `Occupancy is zero for non-positive inputs`() {
        PKModel.occupancy(0.0, 100.0) shouldBe 0.0
        PKModel.occupancy(-1.0, 100.0) shouldBe 0.0
        PKModel.occupancy(100.0, 0.0) shouldBe 0.0
        PKModel.occupancy(100.0, 100.0, hillCoefficient = 0.0) shouldBe 0.0
    }

    @Test
    fun `Occupancy is half at C equals halfMax`() {
        (abs(PKModel.occupancy(250.0, 250.0) - 0.5) < 1e-12) shouldBe true
    }

    @Test
    fun `Occupancy is bounded in 0 to 1 and rises with concentration`() {
        var last = 0.0
        var c = 1.0
        while (c <= 10_000) {
            val o = PKModel.occupancy(c, 500.0)
            (o > 0 && o < 1) shouldBe true
            (o > last) shouldBe true // strictly monotonic
            last = o
            c += 250
        }
    }

    @Test
    fun `Occupancy is unit-invariant when concentration and halfMax share a unit`() {
        val inNanomolar = PKModel.occupancy(300.0, 200.0)
        val inMolar = PKModel.occupancy(300e-9, 200e-9)
        (abs(inNanomolar - inMolar) < 1e-12) shouldBe true
    }

    @Test
    fun `Hill coefficient above one sharpens the response`() {
        // Below halfMax, cooperativity lowers occupancy; above it, raises it.
        val below1 = PKModel.occupancy(100.0, 500.0, 1.0)
        val below2 = PKModel.occupancy(100.0, 500.0, 2.0)
        (below2 < below1) shouldBe true

        val above1 = PKModel.occupancy(2_000.0, 500.0, 1.0)
        val above2 = PKModel.occupancy(2_000.0, 500.0, 2.0)
        (above2 > above1) shouldBe true
    }

    /**
     * Low-dose and high-dose of the same substance must produce **different**
     * receptor occupancy. The normalized-shape model could not express this —
     * every dose normalized to the same curve, so tolerance was dose-independent,
     * which is wrong. Driving occupancy from the absolute molar pathway closes it.
     *
     * Parameters are representative stimulant-like values chosen to exercise the
     * regime, not a claim about a specific drug's measured EC₅₀.
     */
    @Test
    fun `Occupancy is dose-dependent, closing the normalized-PK flaw`() {
        val halfLife = 660.0
        val ke = PKModel.keFromHalfLifeMinutes(halfLife)
        val ka = PKModel.estimateKa(120.0, ke)
        val peakTime = PKModel.tmax(ke, ka)
        val mw = 135.2
        val releaseEC50nM = 1_000.0

        fun peakOccupancy(doseMg: Double): Double {
            val molar = PKModel.concentrationMolar(doseMg, 0.9, 4.0, 70.0, mw, ke, ka, peakTime)
            return PKModel.occupancy(molar * 1e9, releaseEC50nM)
        }

        val low = peakOccupancy(5.0)
        val high = peakOccupancy(50.0)

        (low > 0) shouldBe true
        (high > low) shouldBe true
        (high / low > 3) shouldBe true
        (low < 0.2) shouldBe true
        (high > 0.35) shouldBe true
    }
}

/**
 * Ported from `PKModelSaturableTests`.
 */
class PKModelSaturableTest {

    private val weight = 70.0
    private val vdPerKg = 0.6 // 42 L

    /** Ethanol-like zero-order kinetics: Vmax about 2.5 mg/L/min, Km about 90 mg/L. */
    private val ethanol = PKModel.Saturation.Elimination(km = 90.0, vmax = 2.5)

    // MARK: - Reduces to first order at C far below Km

    @Test
    fun `At C far below Km, saturable elimination matches the closed-form curve`() {
        val ke = PKModel.keFromHalfLifeMinutes(300.0)
        val ka = PKModel.defaultKa(ke)
        // A huge Km so the dose never approaches it; Vmax = ke·Km makes the
        // effective rate equal ke.
        val km = 1e7
        val vmax = ke * km
        val curve = PKModel.saturableCurve(
            dose = 100.0, bioavailability = 1.0, vdPerKg = vdPerKg, weightKg = weight,
            ka = ka, saturation = PKModel.Saturation.Elimination(km, vmax),
            durationMinutes = 1_200.0, stepMinutes = 1.0,
        )
        val vd = vdPerKg * weight
        val analyticPeak = (1.0 * 100 / vd) * PKModel.cmax(ke, ka)
        val relErr = abs(curve.peakParent - analyticPeak) / analyticPeak
        (relErr < 0.01) shouldBe true // RK4 against analytic agree to under 1%
    }

    // MARK: - Saturable elimination

    @Test
    fun `Above Km, elimination is zero-order and declines at about the Vmax slope`() {
        val curve = PKModel.saturableCurve(
            dose = 40_000.0, bioavailability = 1.0, vdPerKg = vdPerKg, weightKg = weight,
            ka = 0.05, saturation = ethanol, durationMinutes = 1_200.0, stepMinutes = 1.0,
        )
        // Sample the descending limb where C is well above Km and absorption has
        // finished.
        val i = 300
        val c = curve.parent[i]
        val slope = curve.parent[i + 1] - curve.parent[i]
        (c > 90 * 3) shouldBe true // firmly in the zero-order regime
        // Capacity-limited: the decline is capped near Vmax and far slower than
        // first-order would give.
        (abs(slope) > 1.8 && abs(slope) <= 2.5) shouldBe true
        val firstOrderRate = (2.5 / 90) * c
        (abs(slope) < firstOrderRate * 0.5) shouldBe true
    }

    @Test
    fun `Saturable elimination accumulates supralinearly`() {
        fun auc(dose: Double): Double = PKModel.saturableCurve(
            dose = dose, bioavailability = 1.0, vdPerKg = vdPerKg, weightKg = weight,
            ka = 0.05, saturation = ethanol, durationMinutes = 2_000.0, stepMinutes = 1.0,
        ).effectAUC

        // First-order would give exactly 2x; capacity-limited clearance makes a
        // doubled dose markedly supralinear.
        (auc(40_000.0) / auc(20_000.0) > 2.5) shouldBe true
    }

    // MARK: - Saturable activation

    /**
     * A synthetic prodrug to active metabolite: formation saturates at Km = 5
     * mg/L, and conversion is a *minor* parent-clearance route, like codeine to
     * morphine at roughly 10% — so the parent stays first-order while the
     * metabolite's formation hits its capacity ceiling.
     */
    private fun activation() = PKModel.Saturation.Activation(
        km = 5.0,
        vmax = 0.005,
        fractionConverted = 0.1,
        parentEliminationKe = PKModel.keFromHalfLifeMinutes(180.0),
        metaboliteKe = PKModel.keFromHalfLifeMinutes(120.0),
    )

    private fun activationCurve(dose: Double): PKModel.SaturableCurve = PKModel.saturableCurve(
        dose = dose, bioavailability = 1.0, vdPerKg = vdPerKg, weightKg = weight,
        ka = 0.02, saturation = activation(), durationMinutes = 1_500.0, stepMinutes = 1.0,
    )

    @Test
    fun `Parent peak scales linearly with dose while the metabolite peak ceilings`() {
        val low = activationCurve(100.0)
        val mid = activationCurve(1_000.0)
        val parentRatio = mid.peakParent / low.peakParent
        val metRatio = mid.metabolite!!.max() / low.metabolite!!.max()
        // The parent carries no saturable step: about 10x for a 10x dose.
        (parentRatio > 9 && parentRatio < 11) shouldBe true
        // Metabolite formation is capacity-limited, so its peak grows far less.
        (metRatio < parentRatio) shouldBe true
        (metRatio < 7) shouldBe true
    }

    @Test
    fun `Past the knee, extra dose buys metabolite duration rather than peak`() {
        val mid = activationCurve(1_000.0)
        val high = activationCurve(4_000.0)
        val peakRatio = high.peakEffect / mid.peakEffect
        val aucRatio = high.effectAUC / mid.effectAUC
        (peakRatio < 4) shouldBe true // the peak plateaus; linear would give 4x
        (aucRatio > peakRatio) shouldBe true // the tail lengthens
    }

    // MARK: - Guards

    @Test
    fun `Saturable curve is non-negative everywhere`() {
        val curve = PKModel.saturableCurve(
            dose = 40_000.0, bioavailability = 1.0, vdPerKg = vdPerKg, weightKg = weight,
            ka = 0.05, saturation = ethanol, durationMinutes = 1_200.0, stepMinutes = 1.0,
        )
        curve.parent.all { it >= 0 } shouldBe true
    }

    @Test
    fun `Invalid inputs and none return a degenerate curve`() {
        PKModel.saturableCurve(
            dose = 0.0, bioavailability = 1.0, vdPerKg = 0.6, weightKg = 70.0,
            ka = 0.05, saturation = ethanol, durationMinutes = 600.0,
        ).parent shouldBe listOf(0.0)

        PKModel.saturableCurve(
            dose = 100.0, bioavailability = 1.0, vdPerKg = 0.6, weightKg = 70.0,
            ka = 0.05, saturation = PKModel.Saturation.None, durationMinutes = 600.0,
        ).parent shouldBe listOf(0.0)

        PKModel.saturableCurve(
            dose = 100.0, bioavailability = 0.0, vdPerKg = 0.6, weightKg = 70.0,
            ka = 0.05, saturation = ethanol, durationMinutes = 600.0,
        ).parent shouldBe listOf(0.0)
    }
}
