package glass.kagerou.piru.ui.tools

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The two solution formulas, called directly.
 *
 * ## Why a test matters more here than for most screens
 * Both formulas are one division, and **a division the wrong way round gives a plausible number**. Solvent
 * needed is `amount / concentration` and concentration is `amount / volume` — the same shape, so a
 * transposition between the two modes is a wrong answer a user would act on with a real substance and a real
 * syringe. Upstream computes both inline in a SwiftUI computed property and has no test for either.
 *
 * The guards are the other half: a zero divisor has to be *no answer* rather than `Infinity`, and a
 * non-positive amount is not a solution.
 */
class SolutionMathTest {

    /**
     * The direction of each formula, with numbers where the wrong direction is obvious.
     *
     * 500 mg at 50 mg/ml is 10 ml. The transposed reading — 50/500 — would give 0.1, which is a tenth of the
     * liquid and a hundred times the intended concentration.
     */
    @Test
    fun `each mode divides in its own direction`() {
        SolutionMath.solventNeededMl(amountMg = 500.0, concentrationMgPerMl = 50.0) shouldBe 10.0
        SolutionMath.concentrationMgPerMl(amountMg = 500.0, volumeMl = 10.0) shouldBe 50.0
    }

    /**
     * A real-sized example at each end of the scale.
     *
     * A milligram in a millilitre is 1 mg/ml — the concentration a volumetric user would call "1:1" — and a
     * microgram-scale solution has to survive as a number rather than rounding to zero.
     */
    @Test
    fun `ordinary solutions come out right`() {
        SolutionMath.concentrationMgPerMl(amountMg = 100.0, volumeMl = 10.0) shouldBe 10.0
        SolutionMath.concentrationMgPerMl(amountMg = 1.0, volumeMl = 1.0) shouldBe 1.0
        SolutionMath.concentrationMgPerMl(amountMg = 0.001, volumeMl = 1.0) shouldBe 0.001
        SolutionMath.solventNeededMl(amountMg = 1000.0, concentrationMgPerMl = 10.0) shouldBe 100.0
    }

    /**
     * A zero divisor is no answer, not infinity.
     *
     * `500.0 / 0.0` is `Infinity` in Kotlin, which would print as "Infinity ml" on a screen someone is
     * using to measure a substance. Null is what draws the placeholder.
     */
    @Test
    fun `a zero divisor has no answer`() {
        SolutionMath.solventNeededMl(amountMg = 500.0, concentrationMgPerMl = 0.0) shouldBe null
        SolutionMath.concentrationMgPerMl(amountMg = 500.0, volumeMl = 0.0) shouldBe null
    }

    /**
     * A non-positive amount is not a solution.
     *
     * Zero is the case a user reaches by clearing a field, and it would otherwise divide to zero and read as
     * a real answer. A negative amount is not physical and must not produce a negative volume.
     */
    @Test
    fun `a non-positive amount has no answer`() {
        SolutionMath.solventNeededMl(amountMg = 0.0, concentrationMgPerMl = 50.0) shouldBe null
        SolutionMath.solventNeededMl(amountMg = -5.0, concentrationMgPerMl = 50.0) shouldBe null
        SolutionMath.concentrationMgPerMl(amountMg = 0.0, volumeMl = 10.0) shouldBe null
        SolutionMath.concentrationMgPerMl(amountMg = -5.0, volumeMl = 10.0) shouldBe null
    }

    /**
     * A missing input is no answer.
     *
     * The form holds text and parses it to a nullable `Double`, so an empty field arrives as null rather
     * than as zero — and the two must not be conflated, because zero *is* a value the user can type.
     */
    @Test
    fun `a missing input has no answer`() {
        SolutionMath.solventNeededMl(amountMg = null, concentrationMgPerMl = 50.0) shouldBe null
        SolutionMath.solventNeededMl(amountMg = 500.0, concentrationMgPerMl = null) shouldBe null
        SolutionMath.concentrationMgPerMl(amountMg = null, volumeMl = 10.0) shouldBe null
        SolutionMath.concentrationMgPerMl(amountMg = 500.0, volumeMl = null) shouldBe null
    }

    /**
     * A whole result loses its decimal; a fractional one keeps four significant figures.
     *
     * Upstream's rule. The alternative is worse at both ends: `%.2f` gives "0.00" for a microgram-scale
     * solution, and `%.0f` gives "3" for 3.45.
     */
    @Test
    fun `whole results drop the decimal and fractions keep significant figures`() {
        SolutionMath.formatResult(10.0) shouldBe "10"
        SolutionMath.formatResult(100.0) shouldBe "100"
        SolutionMath.formatResult(0.0) shouldBe "0"
        SolutionMath.formatResult(3.45) shouldBe "3.450"
        SolutionMath.formatResult(0.001) shouldBe "0.001000"
        SolutionMath.formatResult(12.3456) shouldBe "12.35"
    }
}
