package glass.kagerou.piru.substance

import glass.kagerou.piru.model.DiazepamEquivalent
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The diazepam-equivalence ratio.
 *
 * ## Why this file exists at all
 * The equivalence screen printed the dataset's **citation prose** and never the ratio, so the one number a converter
 * exists to produce was absent from the converter. Upstream computes `equivalentDiazepamMg / doseMg` and this port
 * dropped it.
 *
 * ## The rule the cases are built around
 * A row that cannot support a ratio must return **null**, not zero. `diazepam / dose` with a zero dose is a division
 * by zero; with a zero equivalence it is a zero dressed as an answer. And `0 mg` of a substance is not an equivalence
 * of `0 mg` — it is a dose nobody took. Every one of those three shapes has its own case, because collapsing them is
 * how a converter starts inventing figures.
 */
class BenzoEquivalenceTest {

    private fun row(
        doseMg: Double? = null,
        diazepamMg: Double? = null,
        text: String? = null,
        cited: Boolean = false,
    ) = DiazepamEquivalent(
        doseMg = doseMg,
        equivalentDiazepamMg = diazepamMg,
        displayText = text,
        isCited = cited,
    )

    // MARK: - The ratio

    /**
     * Alprazolam, the reference case: `0.5 mg ≈ 10 mg diazepam`, so **20 mg of diazepam per mg**.
     *
     * Asserted with a tolerance because it is a division, not because the expected value is uncertain.
     */
    @Test
    fun `alprazolam is twenty to one`() {
        val result = BenzoEquivalence.ratio(row(0.5, 10.0, "0.5 mg alprazolam ≈ 10 mg diazepam"))!!
        result.diazepamPerMg shouldBe (20.0 plusOrMinus 1e-9)
    }

    /** Diazepam against itself is one to one, which is the ratio's identity and worth pinning. */
    @Test
    fun `diazepam is one to one`() {
        BenzoEquivalence.ratio(row(10.0, 10.0))!!.diazepamPerMg shouldBe (1.0 plusOrMinus 1e-9)
    }

    /** A weaker benzodiazepine gives a ratio below one, which the arithmetic must not clamp. */
    @Test
    fun `a weaker benzodiazepine is below one`() {
        // 20 mg temazepam ≈ 10 mg diazepam.
        BenzoEquivalence.ratio(row(20.0, 10.0))!!.diazepamPerMg shouldBe (0.5 plusOrMinus 1e-9)
    }

    /** The citation and its cited flag come through, because every figure is shown beside its source. */
    @Test
    fun `the citation is carried`() {
        val result = BenzoEquivalence.ratio(row(0.5, 10.0, "0.5 mg alprazolam ≈ 10 mg diazepam", cited = true))!!
        result.sourceText shouldBe "0.5 mg alprazolam ≈ 10 mg diazepam"
        result.isCited shouldBe true
    }

    /** A blank citation is not a citation: the caller gets null and draws nothing rather than an empty line. */
    @Test
    fun `a blank citation is not carried`() {
        BenzoEquivalence.ratio(row(0.5, 10.0, "   "))!!.sourceText shouldBe null
    }

    // MARK: - The three ways a row carries no answer

    /** No row at all has no ratio. */
    @Test
    fun `no row means no ratio`() {
        BenzoEquivalence.ratio(null) shouldBe null
    }

    /** A row that did not parse into two figures has no ratio, whichever figure is missing. */
    @Test
    fun `a half-parsed row has no ratio`() {
        BenzoEquivalence.ratio(row(doseMg = 0.5)) shouldBe null
        BenzoEquivalence.ratio(row(diazepamMg = 10.0)) shouldBe null
        BenzoEquivalence.ratio(row()) shouldBe null
    }

    /**
     * A zero or negative figure is an **absent** equivalence, not a weak one.
     *
     * The case that would otherwise be a division by zero, or a zero presented as an answer.
     */
    @Test
    fun `a non-positive figure has no ratio`() {
        BenzoEquivalence.ratio(row(0.0, 10.0)) shouldBe null
        BenzoEquivalence.ratio(row(-0.5, 10.0)) shouldBe null
        BenzoEquivalence.ratio(row(0.5, 0.0)) shouldBe null
        BenzoEquivalence.ratio(row(0.5, -10.0)) shouldBe null
    }

    // MARK: - The conversion

    /** A dose converts to its diazepam equivalent. */
    @Test
    fun `a dose converts`() {
        val result = BenzoEquivalence.ratio(row(0.5, 10.0))!!
        // 2 mg of alprazolam is 40 mg of diazepam.
        BenzoEquivalence.equivalentFor(result, 2.0) shouldBe (40.0 plusOrMinus 1e-9)
    }

    /** A dose of zero converts to **null**, not zero: nothing is equivalent to nothing. */
    @Test
    fun `a zero dose converts to nothing`() {
        val result = BenzoEquivalence.ratio(row(0.5, 10.0))!!
        BenzoEquivalence.equivalentFor(result, 0.0) shouldBe null
        BenzoEquivalence.equivalentFor(result, -1.0) shouldBe null
    }

    /** And with no ratio there is no conversion, whatever the dose. */
    @Test
    fun `no ratio means no conversion`() {
        BenzoEquivalence.equivalentFor(null, 2.0) shouldBe null
    }

    /** A fractional dose converts fractionally, because the ratio is linear and the arithmetic must be too. */
    @Test
    fun `a fractional dose converts`() {
        val result = BenzoEquivalence.ratio(row(0.5, 10.0))!!
        BenzoEquivalence.equivalentFor(result, 0.25) shouldBe (5.0 plusOrMinus 1e-9)
    }
}
