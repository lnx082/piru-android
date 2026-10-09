package glass.kagerou.piru.engine

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Saturation kinetics: the fraction of Vmax, the regime, and the two figures that are deliberately absent.
 *
 * ## What is being pinned
 * Not the arithmetic of one division, but the four places where a **plausible number would be wrong**:
 *
 * 1. a zero or negative Km, which would otherwise give "fully saturated at every dose" or a negative fraction;
 * 2. a concentration at exactly a boundary, where two inclusive comparisons would make the answer depend on order;
 * 3. a target at or above saturation, where no figure exists because the asymptote is never reached;
 * 4. the doubling question, which is the reason the object exists and the one a reader will check by hand.
 */
class SaturationKineticsTest {

    private val km = 100.0

    /** At `[S] = Km` exactly half the capacity is occupied — the definition of Km. */
    @Test
    fun `at Km the fraction is one half`() {
        val result = SaturationKinetics.solve(concentration = km, km = km).getOrThrow()
        result.fractionOfVmax shouldBe (0.5 plusOrMinus 1e-12)
    }

    /** The fraction rises with concentration and never reaches one, however large the concentration. */
    @Test
    fun `the fraction rises towards one and never reaches it`() {
        val low = SaturationKinetics.solve(concentration = km * 0.1, km = km).getOrThrow().fractionOfVmax
        val high = SaturationKinetics.solve(concentration = km * 1000, km = km).getOrThrow().fractionOfVmax
        (low < high) shouldBe true
        (high < 1.0) shouldBe true
    }

    /**
     * The boundary cases, which are the reason the two thresholds are named constants.
     *
     * At exactly `0.1·Km` the regime is **linear**, and at exactly `10·Km` it is **saturated**. Both comparisons are
     * inclusive at their own end, so a value on a boundary has one answer rather than whichever branch ran first.
     */
    @Test
    fun `the regime boundaries are inclusive and do not overlap`() {
        SaturationKinetics.regimeFor(km * SaturationKinetics.LINEAR_AT_OR_BELOW_KM_MULTIPLE, km) shouldBe
            SaturationKinetics.Regime.LINEAR
        SaturationKinetics.regimeFor(km * SaturationKinetics.SATURATED_AT_OR_ABOVE_KM_MULTIPLE, km) shouldBe
            SaturationKinetics.Regime.SATURATED
        // And just inside each boundary is the middle regime, so the tests are not both landing on one branch.
        SaturationKinetics.regimeFor(km * 0.11, km) shouldBe SaturationKinetics.Regime.TRANSITIONAL
        SaturationKinetics.regimeFor(km * 9.9, km) shouldBe SaturationKinetics.Regime.TRANSITIONAL
    }

    /** The answer to "why did doubling not double the effect": the fold increase needed for a target fraction. */
    @Test
    fun `reaching ninety percent takes nine times the Km concentration`() {
        // [S] = Km·f/(1-f); at f=0.9 that is 9·Km.
        val result = SaturationKinetics.solve(concentration = km, km = km, targetFraction = 0.9).getOrThrow()
        result.concentrationForTarget!! shouldBe (km * 9.0 plusOrMinus 1e-9)
    }

    /** Going from half to ninety percent takes nine times as much concentration — the point of the whole object. */
    @Test
    fun `half to ninety percent is a ninefold increase`() {
        SaturationKinetics.foldIncrease(fromFraction = 0.5, toFraction = 0.9)!! shouldBe (9.0 plusOrMinus 1e-9)
        // And half to three quarters is threefold, which is the same arithmetic with a smaller ask.
        SaturationKinetics.foldIncrease(fromFraction = 0.5, toFraction = 0.75)!! shouldBe (3.0 plusOrMinus 1e-9)
    }

    /**
     * **No figure exists** for full saturation, and none for a target that is not above the current one.
     *
     * This is the case a helpful-looking implementation gets wrong by returning `Double.MAX_VALUE` or a very large
     * number — a figure a reader could act on, for a concentration that does not exist.
     */
    @Test
    fun `no concentration reaches full saturation`() {
        SaturationKinetics.concentrationFor(1.0, km) shouldBe null
        SaturationKinetics.concentrationFor(1.5, km) shouldBe null
        SaturationKinetics.concentrationFor(0.0, km) shouldBe null
        SaturationKinetics.concentrationFor(-0.5, km) shouldBe null
        SaturationKinetics.concentrationFor(null, km) shouldBe null
        // And a target below the starting fraction is not a fold *increase*.
        SaturationKinetics.foldIncrease(fromFraction = 0.8, toFraction = 0.5) shouldBe null
    }

    /**
     * A zero or negative Km is refused rather than answered.
     *
     * At `Km = 0` the fraction is `1.0` for **any** positive concentration — "fully saturated at every dose", which is
     * exactly wrong — and a negative Km gives a negative fraction. Both are plausible-looking numbers.
     */
    @Test
    fun `a Km that is not positive is refused`() {
        SaturationKinetics.solve(concentration = 50.0, km = 0.0).isFailure shouldBe true
        SaturationKinetics.solve(concentration = 50.0, km = -10.0).isFailure shouldBe true
    }

    /** Missing inputs are refused, and a negative concentration is refused. */
    @Test
    fun `missing and impossible inputs are refused`() {
        SaturationKinetics.solve(concentration = null, km = km).isFailure shouldBe true
        SaturationKinetics.solve(concentration = 50.0, km = null).isFailure shouldBe true
        SaturationKinetics.solve(concentration = -1.0, km = km).isFailure shouldBe true
    }

    /** A zero concentration is a valid one, and gives a fraction of zero rather than a failure. */
    @Test
    fun `zero concentration is a valid reading`() {
        SaturationKinetics.solve(concentration = 0.0, km = km).getOrThrow().fractionOfVmax shouldBe 0.0
        SaturationKinetics.regimeFor(0.0, km) shouldBe SaturationKinetics.Regime.LINEAR
    }
}
