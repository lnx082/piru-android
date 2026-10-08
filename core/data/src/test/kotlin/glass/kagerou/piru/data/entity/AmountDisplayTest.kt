package glass.kagerou.piru.data.entity

import glass.kagerou.piru.model.RouteOfAdministration
import io.kotest.matchers.shouldBe
import java.util.Date
import org.junit.jupiter.api.Test

/**
 * The amount readout's two markers, and why neither is decoration.
 *
 * ## `~` — BUG #39's read side
 * `isApproximate` was **set by the quick log and read by nothing**. The quick-log field's own comment described the
 * journal as drawing a `~` before the figure, and nothing did: the entity, the export and the schema all carried
 * the flag while every surface printed the number as though it had been measured. A dose someone estimated and a
 * dose someone weighed were indistinguishable on screen **and in the printed report**, which is exactly the kind of
 * difference a reader would act on.
 *
 * ## `?` — the older marker, kept because it is the same decision
 * An unknown dose prints `?` rather than `0 mg`, because a zero is a claim the log does not make. The two markers
 * live in one function for that reason: both answer "how much was this, really", and a call site that handled one
 * and forgot the other is how the second went missing.
 */
class AmountDisplayTest {

    private fun dose(
        amount: Double = 100.0,
        unit: String = "mg",
        unknown: Boolean = false,
        approximate: Boolean = false,
    ) = DoseEntryEntity.create(
        substance = "Test",
        amount = amount,
        unit = unit,
        route = RouteOfAdministration.ORAL,
        timestamp = Date(0),
    ).copy(isUnknownDose = unknown, isApproximate = approximate)

    /** A measured dose prints its numeral and no marker. */
    @Test
    fun `a measured dose has no marker`() {
        dose(amount = 100.0).amountDisplay shouldBe "100"
        dose(amount = 12.5).amountDisplay shouldBe "12.5"
    }

    /**
     * An estimated dose carries the tilde.
     *
     * The whole of BUG #39's read side. Before this, `dose(approximate = true).amountDisplay` was the same string as
     * the measured one.
     */
    @Test
    fun `an estimated dose carries the tilde`() {
        dose(amount = 100.0, approximate = true).amountDisplay shouldBe "~100"
        dose(amount = 12.5, approximate = true).amountDisplay shouldBe "~12.5"
    }

    /** An unknown dose prints `?`, and nothing else — not `~?` and not `0`. */
    @Test
    fun `an unknown dose prints a question mark`() {
        dose(amount = 0.0, unknown = true).amountDisplay shouldBe "?"
        // Both flags set: the absent amount is the stronger statement, and `~?` would be nonsense.
        dose(amount = 0.0, unknown = true, approximate = true).amountDisplay shouldBe "?"
    }

    /**
     * The three cases are pairwise distinct.
     *
     * The property that makes the markers worth having: a reader can tell a measured dose from an estimated one from
     * an unknown one by the string alone. Asserted as a set, so it fails if any two collapse — which is how the
     * marker went missing in the first place.
     */
    @Test
    fun `the three cases are distinguishable`() {
        val measured = dose(amount = 100.0).amountDisplay
        val estimated = dose(amount = 100.0, approximate = true).amountDisplay
        val unknown = dose(amount = 100.0, unknown = true).amountDisplay
        setOf(measured, estimated, unknown).size shouldBe 3
    }

    /**
     * The marker composes with the magnitude rounding the readout already did.
     *
     * `amountDisplay` rounds to a readable number of digits, so a long value does not print twenty characters. The
     * tilde is a prefix on that, not a replacement for it.
     */
    @Test
    fun `the tilde prefixes the rounded numeral`() {
        val plain = dose(amount = 100.04).amountDisplay
        val marked = dose(amount = 100.04, approximate = true).amountDisplay
        marked shouldBe "~$plain"
    }
}
