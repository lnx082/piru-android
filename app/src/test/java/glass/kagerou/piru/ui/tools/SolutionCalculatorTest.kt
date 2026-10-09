package glass.kagerou.piru.ui.tools

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * The solution and concentration calculator.
 *
 * ## The formula has no third case
 * `mass = volume × concentration`, and `concentration = mass ÷ volume`. A calculator that looks like it has a third
 * is solving for the same unknown another way, so this takes **exactly two** of the three figures and computes the
 * missing one. The cases are mostly about that contract: three figures, one figure and no figures must each be
 * refused, because the tool cannot know which one the reader meant to leave out.
 *
 * ## The refusals are the dangerous half
 * A calculator that returns a **negative** dose or a **zero** concentration does not look broken — it produces a number
 * in the same style as a right answer, and a reader who typed a unit wrong gets a plausible figure rather than a
 * complaint. Each refusal has its own case for that reason.
 */
class SolutionCalculatorTest {

    private fun failureOf(block: () -> Unit): SolutionCalculator.Failure =
        runCatching(block)
            .exceptionOrNull()
            .shouldBeInstanceOf<SolutionCalculator.SolutionException>()
            .failure

    // MARK: - The three solves

    /** Volume and concentration give the mass: 2 mL at 200 mg/mL is 400 mg. */
    @Test
    fun `volume and concentration give the mass`() {
        val result = SolutionCalculator.solve(
            SolutionCalculator.Inputs(volumeML = 2.0, concentrationPerML = 200.0),
        )
        result.unknown shouldBe SolutionCalculator.Unknown.MASS
        result.value shouldBe (400.0 plusOrMinus 1e-9)
    }

    /** Mass and concentration give the volume: 400 mg at 200 mg/mL is 2 mL. */
    @Test
    fun `mass and concentration give the volume`() {
        val result = SolutionCalculator.solve(
            SolutionCalculator.Inputs(mass = 400.0, concentrationPerML = 200.0),
        )
        result.unknown shouldBe SolutionCalculator.Unknown.VOLUME
        result.value shouldBe (2.0 plusOrMinus 1e-9)
    }

    /** Mass and volume give the concentration: 400 mg in 2 mL is 200 mg/mL. */
    @Test
    fun `mass and volume give the concentration`() {
        val result = SolutionCalculator.solve(
            SolutionCalculator.Inputs(mass = 400.0, volumeML = 2.0),
        )
        result.unknown shouldBe SolutionCalculator.Unknown.CONCENTRATION
        result.value shouldBe (200.0 plusOrMinus 1e-9)
    }

    /** The three solves are inverses of each other, which is the property that makes the tool usable. */
    @Test
    fun `the three solves are inverses`() {
        val mass = SolutionCalculator.solve(
            SolutionCalculator.Inputs(volumeML = 2.5, concentrationPerML = 40.0),
        ).value
        val volume = SolutionCalculator.solve(
            SolutionCalculator.Inputs(mass = mass, concentrationPerML = 40.0),
        ).value
        volume shouldBe (2.5 plusOrMinus 1e-9)
        val concentration = SolutionCalculator.solve(
            SolutionCalculator.Inputs(mass = mass, volumeML = volume),
        ).value
        concentration shouldBe (40.0 plusOrMinus 1e-9)
    }

    /** A fractional concentration — an unusual but real one — survives without rounding to nothing. */
    @Test
    fun `a small concentration is not rounded away`() {
        val result = SolutionCalculator.solve(
            SolutionCalculator.Inputs(mass = 0.25, volumeML = 2.0),
        )
        result.value shouldBe (0.125 plusOrMinus 1e-12)
    }

    // MARK: - The contract: exactly two figures

    /** One figure is not enough to know which is missing: all three shapes are refused. */
    @Test
    fun `one figure is refused`() {
        failureOf { SolutionCalculator.solve(SolutionCalculator.Inputs(mass = 400.0)) } shouldBe
            SolutionCalculator.Failure.NOT_TWO_GIVEN
        failureOf { SolutionCalculator.solve(SolutionCalculator.Inputs(volumeML = 2.0)) } shouldBe
            SolutionCalculator.Failure.NOT_TWO_GIVEN
        failureOf { SolutionCalculator.solve(SolutionCalculator.Inputs(concentrationPerML = 200.0)) } shouldBe
            SolutionCalculator.Failure.NOT_TWO_GIVEN
    }

    /** Three figures leave nothing to solve for, and guessing which to ignore would be inventing an answer. */
    @Test
    fun `three figures are refused`() {
        failureOf {
            SolutionCalculator.solve(
                SolutionCalculator.Inputs(mass = 400.0, volumeML = 2.0, concentrationPerML = 200.0),
            )
        } shouldBe SolutionCalculator.Failure.NOT_TWO_GIVEN
    }

    /** No figures at all are refused, as the empty case of the same rule. */
    @Test
    fun `no figures are refused`() {
        failureOf { SolutionCalculator.solve(SolutionCalculator.Inputs()) } shouldBe
            SolutionCalculator.Failure.NOT_TWO_GIVEN
    }

    // MARK: - The refusals that matter

    /**
     * A zero concentration is refused rather than answered.
     *
     * `mass ÷ 0` is a division by zero, and reporting `0` for the volume would say "you need no solution" rather than
     * "you gave no strength" — the more dangerous of the two readings.
     */
    @Test
    fun `a zero concentration is refused`() {
        failureOf {
            SolutionCalculator.solve(SolutionCalculator.Inputs(volumeML = 2.0, concentrationPerML = 0.0))
        } shouldBe SolutionCalculator.Failure.DIVIDES_BY_ZERO
        failureOf {
            SolutionCalculator.solve(SolutionCalculator.Inputs(mass = 400.0, concentrationPerML = 0.0))
        } shouldBe SolutionCalculator.Failure.DIVIDES_BY_ZERO
    }

    /** A zero volume is refused for the same reason on the concentration branch. */
    @Test
    fun `a zero volume is refused`() {
        failureOf {
            SolutionCalculator.solve(SolutionCalculator.Inputs(mass = 400.0, volumeML = 0.0))
        } shouldBe SolutionCalculator.Failure.DIVIDES_BY_ZERO
    }

    /**
     * A negative figure is refused as a **typo**, not propagated.
     *
     * The case whose failure is worst: a negative mass or volume produces a negative result, which reads as a real
     * number in the same style as a right answer.
     */
    @Test
    fun `a negative figure is refused`() {
        failureOf {
            SolutionCalculator.solve(SolutionCalculator.Inputs(mass = -400.0, volumeML = 2.0))
        } shouldBe SolutionCalculator.Failure.NEGATIVE
        failureOf {
            SolutionCalculator.solve(SolutionCalculator.Inputs(volumeML = -2.0, concentrationPerML = 200.0))
        } shouldBe SolutionCalculator.Failure.NEGATIVE
        failureOf {
            SolutionCalculator.solve(SolutionCalculator.Inputs(mass = 400.0, concentrationPerML = -200.0))
        } shouldBe SolutionCalculator.Failure.NEGATIVE
    }

    /** A result that is not finite is refused rather than printed as `Infinity`. */
    @Test
    fun `a non-finite result is refused`() {
        failureOf {
            SolutionCalculator.solve(
                SolutionCalculator.Inputs(volumeML = Double.MAX_VALUE, concentrationPerML = Double.MAX_VALUE),
            )
        } shouldBe SolutionCalculator.Failure.NOT_FINITE
    }

    /** Zero **is** a valid mass and a valid answer: an empty syringe is a real endpoint. */
    @Test
    fun `a zero mass is allowed`() {
        val result = SolutionCalculator.solve(
            SolutionCalculator.Inputs(mass = 0.0, concentrationPerML = 200.0),
        )
        result.value shouldBe 0.0
    }

    // MARK: - The reading a reader actually wants

    /**
     * "I want 40 mg from a 200 mg/mL vial" gives 0.2 mL.
     *
     * The question asked with **both** figures already in hand, and the reason the tool is useful rather than merely
     * symmetrical.
     */
    @Test
    fun `a target mass gives the volume to draw`() {
        SolutionCalculator.volumeFor(mass = 40.0, concentrationPerML = 200.0) shouldBe (0.2 plusOrMinus 1e-12)
    }

    /** A zero concentration is refused here too, since it is the same division. */
    @Test
    fun `the draw-up reading refuses a zero concentration`() {
        failureOf { SolutionCalculator.volumeFor(mass = 40.0, concentrationPerML = 0.0) } shouldBe
            SolutionCalculator.Failure.DIVIDES_BY_ZERO
    }

    // MARK: - Formatting

    /** A whole figure has no decimals; a fractional one is trimmed of trailing zeros. */
    @Test
    fun `figures are trimmed`() {
        SolutionCalculator.format(400.0) shouldBe "400"
        SolutionCalculator.format(0.2) shouldBe "0.2"
        SolutionCalculator.format(0.125) shouldBe "0.125"
        SolutionCalculator.format(2.5) shouldBe "2.5"
    }

    /**
     * A figure keeps up to **three** decimals, because a concentration is often small.
     *
     * `0.125 mg/mL` at two decimals is `0.13`, which is a different solution — the rounding would be a changed answer,
     * not an untidy one.
     */
    @Test
    fun `three decimals survive`() {
        SolutionCalculator.format(0.125) shouldBe "0.125"
        SolutionCalculator.format(0.0625) shouldBe "0.063"
    }

    /** The figures use a point whatever the device's locale is. */
    @Test
    fun `figures ignore the device locale`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            SolutionCalculator.format(0.125) shouldBe "0.125"
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    /**
     * The strength field is labelled by what kind of solution it is.
     *
     * A mass-per-volume solution is a **concentration** in mg/mL; a percent solution is a **strength** in %. Reading
     * `40` as 40 mg/mL when the reader meant 40 % is a thousandfold error, so the label is the guard.
     */
    @Test
    fun `the strength label depends on the kind of solution`() {
        SolutionCalculator.strengthLabel(
            glass.kagerou.piru.model.ByVolumeDosing.Concentration.MassPerVolume,
        ) shouldBe "Concentration"
        SolutionCalculator.strengthLabel(
            glass.kagerou.piru.model.ByVolumeDosing.Concentration.PercentByVolume(densityGramsPerML = 0.789),
        ) shouldBe "Strength"
    }
}
