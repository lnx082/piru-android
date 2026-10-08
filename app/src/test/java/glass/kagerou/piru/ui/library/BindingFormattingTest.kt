package glass.kagerou.piru.ui.library

import glass.kagerou.piru.engine.BindingHit
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The two formatters the receptor and metabolism sections use.
 *
 * ## Why the units are the interesting part
 * `bindingAffinityText` has to pick one of three affinities that are **not** interchangeable — a Ki is an
 * affinity, an EC50 a potency, an IC50 an inhibition — and label it, because printing one in the other's
 * place is a wrong number rather than a missing one. `formatNm` has the same problem one level down:
 * affinities span picomolar to micromolar, so a single unit is unreadable at one end.
 *
 * `formatPercent` exists for `metabolitePotencyVsParentPct`, whose values run to 20000 and whose column
 * carries `Double`s.
 */
class BindingFormattingTest {

    private fun hit(
        target: String = "MOR",
        action: String = "agonist",
        ki: Double? = null,
        ec50: Double? = null,
        ic50: Double? = null,
        species: String? = null,
        source: String = "",
    ) = BindingHit(
        id = 1L,
        substanceName = "Test",
        target = target,
        action = action,
        kiNm = ki,
        ec50Nm = ec50,
        ic50Nm = ic50,
        species = species,
        sourceSlug = source,
    )

    /**
     * Each affinity is labelled with its own symbol.
     *
     * The failure this rules out is a row whose Ki prints as "EC50": the number would be right and the
     * meaning wrong, which is worse than omitting it.
     */
    @Test
    fun `each affinity keeps its own label`() {
        bindingAffinityText(hit(ki = 12.0)) shouldBe "Ki 12 nM"
        bindingAffinityText(hit(ec50 = 40.0)) shouldBe "EC50 40 nM"
        bindingAffinityText(hit(ic50 = 7.0)) shouldBe "IC50 7 nM"
    }

    /**
     * Species and source ride along, because a number without them is not checkable.
     */
    @Test
    fun `species and source are printed with the number`() {
        val text = bindingAffinityText(hit(ki = 12.0, species = "human", source = "pdsp"))
        text shouldBe "Ki 12 nM · human · pdsp"
    }

    /**
     * A row with no measured affinity still prints what it has.
     *
     * The row's existence is information — the catalogue recorded that this target was measured — so a
     * blank line would throw that away. An entirely empty row prints an empty string, which the card
     * renders as a blank detail line rather than crashing.
     */
    @Test
    fun `a row with no affinity still prints its species and source`() {
        bindingAffinityText(hit(species = "rat", source = "pdsp")) shouldBe "rat · pdsp"
        bindingAffinityText(hit()) shouldBe ""
    }

    // MARK: - formatNm

    /**
     * Sub-nanomolar affinities keep two decimals.
     *
     * `%.0f` on 0.4 gives "0 nM", which reads as no affinity at all — the opposite of what a picomolar
     * figure means.
     */
    @Test
    fun `a sub-nanomolar affinity keeps its decimals`() {
        formatNm(0.4) shouldBe "0.40 nM"
        formatNm(0.05) shouldBe "0.05 nM"
    }

    @Test
    fun `a nanomolar affinity reads as whole nanomolar`() {
        formatNm(1.0) shouldBe "1 nM"
        formatNm(12.0) shouldBe "12 nM"
        formatNm(999.0) shouldBe "999 nM"
    }

    /**
     * A micromolar affinity converts, because "12000 nM" is a number nobody reads at a glance.
     *
     * The boundary is 1000 nM, asserted from both sides.
     */
    @Test
    fun `a micromolar affinity reads in micromolar`() {
        formatNm(1000.0) shouldBe "1.0 µM"
        formatNm(12000.0) shouldBe "12.0 µM"
        formatNm(999.0) shouldBe "999 nM"
    }

    @Test
    fun `a negative affinity clamps`() {
        formatNm(-1.0) shouldBe "0.00 nM"
    }

    // MARK: - formatPercent

    @Test
    fun `a whole percentage loses its decimal`() {
        formatPercent(80.0) shouldBe "80%"
        formatPercent(20000.0) shouldBe "20000%"
        formatPercent(0.0) shouldBe "0%"
    }

    @Test
    fun `a fractional percentage keeps one decimal`() {
        formatPercent(12.5) shouldBe "12.5%"
        formatPercent(33.333) shouldBe "33.3%"
    }

    /**
     * The value is treated as a percentage, not a fraction.
     *
     * Already a percentage in the column, so 80 means eighty and a formatter that multiplied by 100 would
     * print "8000%". Every case here would still be shaped right, which is why the magnitude is asserted
     * and not only the suffix.
     */
    @Test
    fun `the value is treated as a percentage and not a fraction`() {
        formatPercent(80.0) shouldBe "80%"
        formatPercent(5.0) shouldBe "5%"
        formatPercent(150.0) shouldBe "150%"
    }
}
