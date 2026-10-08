package glass.kagerou.piru.ui.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The quantity formatter the inventory card uses.
 *
 * ## Why this needs a test rather than trusting `%.1f`
 * `InventoryItemEntity.currentQuantity` is maintained by the restock arithmetic, which adds and
 * subtracts `Double`s — so a stash of nine 1 mg doses reads as `8.999999999`, and printing that on a
 * reference card is the kind of number that makes a reader distrust everything else on the page.
 *
 * The rounding is the feature. This pins it, including the cases where it must *not* round: a genuinely
 * fractional stash keeps its decimal, because "8.5 g" and "9 g" are different amounts of a substance.
 */
class InventoryQuantityFormattingTest {

    @Test
    fun `floating-point drift is rounded away`() {
        formatQuantity(8.999999999) shouldBe "9"
        formatQuantity(0.30000000000000004) shouldBe "0.3"
        formatQuantity(2.9999999999) shouldBe "3"
    }

    @Test
    fun `a whole quantity loses its decimal`() {
        formatQuantity(0.0) shouldBe "0"
        formatQuantity(1.0) shouldBe "1"
        formatQuantity(100.0) shouldBe "100"
    }

    /**
     * A real fraction keeps one decimal, and the half-up rule is the one a person would use.
     *
     * `Math.round(0.25 * 10) / 10` is 0.3, not 0.2 — Kotlin's `Math.round` is half-up on the magnitude,
     * which is what a reader expects and what `String.format` alone would not guarantee.
     */
    @Test
    fun `a fractional quantity keeps one decimal`() {
        formatQuantity(0.5) shouldBe "0.5"
        formatQuantity(8.5) shouldBe "8.5"
        formatQuantity(0.25) shouldBe "0.3"
        formatQuantity(1.44) shouldBe "1.4"
    }

    /**
     * A quantity is never negative on screen.
     *
     * The restock arithmetic can go below zero if a dose is logged against an empty stash, and the form
     * that lets a user set a quantity allows a negative entry. "-3 mg on hand" is not a fact about a
     * shelf, so the card would be reading as a bug.
     */
    @Test
    fun `a negative quantity clamps to zero`() {
        formatQuantity(-1.0) shouldBe "0"
        formatQuantity(-0.4) shouldBe "0"
    }
}
