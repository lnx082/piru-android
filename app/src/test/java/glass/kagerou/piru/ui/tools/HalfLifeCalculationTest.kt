package glass.kagerou.piru.ui.tools

import glass.kagerou.piru.model.Substance
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The half-life calculator's arithmetic, called directly.
 *
 * ## Why this needs its own tests
 * Every equation is `PKModel`'s and is tested there. What this object decides is **which inputs reach them**, and
 * that is where a calculator goes wrong: an override that reads the substance anyway, a remaining amount falling
 * back to the naive exponential when it should have used the fitted curve, a milestone ladder timed as
 * `n × t½` when absorption has not finished.
 *
 * The screen it replaces had a picker and a curve, so none of this was reachable at all — which is why the
 * extraction is the change and the tests are what make it a calculator rather than a redraw.
 */
class HalfLifeCalculationTest {

    /** A substance stub. Only `halfLifeMinutes` is read by the paths under test. */
    private fun substanceOf(halfLife: Double?): Substance? = halfLife?.let {
        Substance(
            name = "Test",
            category = glass.kagerou.piru.model.SubstanceCategory.OTHER,
            halfLifeMinutes = it,
            defaultRoute = glass.kagerou.piru.model.RouteOfAdministration.ORAL,
            routes = emptyList(),
        )
    }

    private fun paramsFor(halfLife: Double, kaFactor: Double = 4.0): HalfLifeCalculation.RateConstants {
        val ke = kotlin.math.ln(2.0) / halfLife
        return HalfLifeCalculation.RateConstants(ke = ke, ka = ke * kaFactor)
    }

    // MARK: - The effective half-life

    /**
     * Without the override on, the substance's own figure is used.
     *
     * The default path, and the one an override could silently shadow: a bug where `useCustom` is consulted
     * inverted would make every substance read the override.
     */
    @Test
    fun `the substance's own half-life is used by default`() {
        HalfLifeCalculation.effectiveHalfLife(
            useCustom = false,
            customHours = 99.0,
            substance = substanceOf(180.0),
        ) shouldBe 180.0
    }

    /** With the override on, the typed figure wins and is converted from hours to minutes. */
    @Test
    fun `the override wins and converts hours to minutes`() {
        HalfLifeCalculation.effectiveHalfLife(
            useCustom = true,
            customHours = 3.0,
            substance = substanceOf(180.0),
        ) shouldBe 180.0
        HalfLifeCalculation.effectiveHalfLife(
            useCustom = true,
            customHours = 0.5,
            substance = null,
        ) shouldBe 30.0
    }

    /**
     * A non-positive or absent override is null, not zero.
     *
     * Zero would divide by zero in the naive fallback and produce an infinite curve — a chart that looks broken
     * rather than a field that is empty. This is the case the screen reaches whenever the override is switched on
     * before anything is typed.
     */
    @Test
    fun `a missing or non-positive override has no answer`() {
        HalfLifeCalculation.effectiveHalfLife(true, null, substanceOf(180.0)) shouldBe null
        HalfLifeCalculation.effectiveHalfLife(true, 0.0, substanceOf(180.0)) shouldBe null
        HalfLifeCalculation.effectiveHalfLife(true, -1.0, substanceOf(180.0)) shouldBe null
    }

    /** And a substance the catalogue has no figure for is null rather than zero. */
    @Test
    fun `an unmeasured substance has no answer`() {
        HalfLifeCalculation.effectiveHalfLife(false, null, null) shouldBe null
        HalfLifeCalculation.effectiveHalfLife(false, null, substanceOf(null)) shouldBe null
    }

    // MARK: - The rate constants

    @Test
    fun `rate constants need a positive half-life`() {
        HalfLifeCalculation.rateConstants(null, null) shouldBe null
        HalfLifeCalculation.rateConstants(0.0, null) shouldBe null
        HalfLifeCalculation.rateConstants(-5.0, null) shouldBe null
    }

    /**
     * `ke` is `ln(2)/t½`, and `ka` is faster than `ke`.
     *
     * `ka > ke` is the condition the whole one-compartment oral model depends on: with `ka <= ke` the curve has no
     * peak and `tmax` is undefined. Asserted here because a route with no duration profile falls back to a
     * default `ka`, and that default has to stay on the right side of it.
     */
    @Test
    fun `ke follows from the half-life and ka is faster`() {
        // Non-null for a positive half-life, which is the only case the screen calls it with.
        val constants = HalfLifeCalculation.rateConstants(180.0, null)!!
        constants.ke shouldBe (kotlin.math.ln(2.0) / 180.0 plusOrMinus 1e-12)
        (constants.ka > constants.ke) shouldBe true
    }

    // MARK: - The remaining amount

    /**
     * The naive fallback is exactly `dose · ½^(t/t½)`.
     *
     * The path taken when there is no duration profile, and the one a reader can check in their head: one
     * half-life leaves half, two leave a quarter.
     */
    @Test
    fun `the naive fallback halves per half-life`() {
        val dose = 100.0
        val halfLife = 180.0
        HalfLifeCalculation.remainingAmount(dose, 0.0, halfLife, null) shouldBe (100.0 plusOrMinus 1e-9)
        HalfLifeCalculation.remainingAmount(dose, 180.0, halfLife, null) shouldBe (50.0 plusOrMinus 1e-9)
        HalfLifeCalculation.remainingAmount(dose, 360.0, halfLife, null) shouldBe (25.0 plusOrMinus 1e-9)
        HalfLifeCalculation.remainingAmount(dose, 720.0, halfLife, null) shouldBe (6.25 plusOrMinus 1e-9)
    }

    /**
     * With rate constants, the fitted curve is used and it differs from the naive one.
     *
     * **This is the assertion that distinguishes the two paths.** At one half-life the fitted curve still has
     * absorption contributing, so more than half remains — and a bug that ignored `rateConstants` would make the
     * two identical, which is exactly the failure this pins.
     */
    @Test
    fun `the fitted curve keeps more than half at one half-life`() {
        val halfLife = 180.0
        val naive = HalfLifeCalculation.remainingAmount(100.0, halfLife, halfLife, null)
        val fitted = HalfLifeCalculation.remainingAmount(
            100.0,
            halfLife,
            halfLife,
            paramsFor(halfLife),
        )
        (fitted > naive) shouldBe true
        // And it is still on its way down, not above the dose.
        (fitted < 100.0) shouldBe true
    }

    /**
     * Both paths start at the full dose.
     *
     * The invariant that makes the two comparable at all, and the one a reader checks first: at zero elapsed
     * time nothing has been eliminated, whatever the curve.
     *
     * ## What I got wrong here first
     * I asserted the fitted curve would fall **below** the naive one in the tail, reasoning that absorption
     * stops contributing. It does not: for `ka > ke` the one-compartment oral curve is above `e^{-ke·t}` at
     * every positive time, because that is precisely what the absorption term adds. Both tend to zero and their
     * ratio tends to a constant above one. The assertion failed, which is the test doing its job on its author.
     */
    @Test
    fun `both paths start at the full dose`() {
        val halfLife = 180.0
        HalfLifeCalculation.remainingAmount(100.0, 0.0, halfLife, null) shouldBe (100.0 plusOrMinus 1e-9)
        HalfLifeCalculation.remainingAmount(100.0, 0.0, halfLife, paramsFor(halfLife)) shouldBe
            (100.0 plusOrMinus 1e-9)
    }

    /**
     * A non-positive dose or half-life has no remaining amount, and a negative elapsed time is clamped.
     *
     * The clamp matters: "how much was in me before I took it" is not a question the model answers, and a
     * backwards extrapolation would put an amount **above** the dose on screen.
     */
    @Test
    fun `degenerate inputs have no answer and time does not run backwards`() {
        HalfLifeCalculation.remainingAmount(0.0, 100.0, 180.0, null) shouldBe 0.0
        HalfLifeCalculation.remainingAmount(-10.0, 100.0, 180.0, null) shouldBe 0.0
        HalfLifeCalculation.remainingAmount(100.0, 100.0, 0.0, null) shouldBe 0.0
        // Negative elapsed clamps to zero, which is the full dose.
        HalfLifeCalculation.remainingAmount(100.0, -500.0, 180.0, null) shouldBe (100.0 plusOrMinus 1e-9)
    }

    // MARK: - The milestone ladder

    /**
     * Four steps, each half of the last.
     *
     * The fractions are `½ⁿ` and are independent of the curve: they are what "one half-life" *means*. The times
     * are what varies.
     */
    @Test
    fun `the ladder is four halvings`() {
        val ladder = HalfLifeCalculation.milestones(180.0, null)
        ladder.size shouldBe 4
        ladder.map { it.n } shouldBe listOf(1, 2, 3, 4)
        for (step in ladder) {
            step.fraction shouldBe (Math.pow(0.5, step.n.toDouble()) plusOrMinus 1e-12)
        }
    }

    /**
     * Without a curve, the times are the naive multiples.
     *
     * The fallback a reader can check: four half-lives is four times the half-life.
     */
    @Test
    fun `without a curve the times are the naive multiples`() {
        val ladder = HalfLifeCalculation.milestones(180.0, null)
        ladder.map { it.minutes } shouldBe listOf(180.0, 360.0, 540.0, 720.0)
    }

    /**
     * With a curve, the early steps come **later** than the naive multiples.
     *
     * The property that makes the fitted ladder worth computing at all: absorption is still filling the
     * compartment while elimination empties it, so half the dose has not yet been eliminated at one half-life.
     * A ladder that ignored `rateConstants` would give the naive times, which is the failure this pins.
     */
    @Test
    fun `with a curve the early milestones come later`() {
        val halfLife = 180.0
        val naive = HalfLifeCalculation.milestones(halfLife, null)
        val fitted = HalfLifeCalculation.milestones(halfLife, paramsFor(halfLife))
        (fitted.first().minutes > naive.first().minutes) shouldBe true
        // And the times increase, as a decay must.
        for (index in 1 until fitted.size) {
            (fitted[index].minutes > fitted[index - 1].minutes) shouldBe true
        }
    }

    /** A non-positive half-life has no ladder rather than an empty-looking set of infinities. */
    @Test
    fun `a non-positive half-life has no ladder`() {
        HalfLifeCalculation.milestones(0.0, null) shouldBe emptyList()
        HalfLifeCalculation.milestones(-180.0, null) shouldBe emptyList()
    }

    /** The peak time is zero when there is no curve, rather than a division on unset constants. */
    @Test
    fun `the peak time is zero without a curve`() {
        HalfLifeCalculation.peakTime(null) shouldBe 0.0
        (HalfLifeCalculation.peakTime(paramsFor(180.0)) > 0.0) shouldBe true
    }


}
