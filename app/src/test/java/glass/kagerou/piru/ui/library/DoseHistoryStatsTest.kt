package glass.kagerou.piru.ui.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The "Your History" summary's arithmetic.
 *
 * ## The three claims worth pinning
 * **Ties are deterministic.** Two amounts logged equally often have no most-common one between them, and a summary
 * that picks a different one on each run cannot be checked. This port takes the lower amount, which is a deliberate
 * difference from upstream's "whichever the comparison reaches first" and is asserted so it stays deliberate.
 *
 * **No rows means no stats, not zero stats.** `min == max == 0` renders as "you logged 0 mg", which is a claim about a
 * dose that was never taken.
 *
 * **The range is the extremes of the amounts themselves**, including a single-entry history where `min == max` and the
 * card prints one figure rather than a span.
 */
class DoseHistoryStatsTest {

    @Test
    fun `an empty history has no stats`() {
        DoseHistoryStats.rebuild(emptyList()) shouldBe null
    }

    /** One entry is its own minimum and maximum, which the card renders as a single figure rather than a span. */
    @Test
    fun `a single entry is its own range`() {
        val stats = DoseHistoryStats.rebuild(listOf(100.0))!!
        stats.minDose shouldBe 100.0
        stats.maxDose shouldBe 100.0
        stats.mostCommon shouldBe 100.0
        stats.mostCommonCount shouldBe 1
    }

    /** The range is the extremes, not the first and last: a log is not sorted. */
    @Test
    fun `the range is the extremes in any order`() {
        val stats = DoseHistoryStats.rebuild(listOf(50.0, 200.0, 100.0))!!
        stats.minDose shouldBe 50.0
        stats.maxDose shouldBe 200.0
    }

    /** The most common is the most frequent, not the largest. */
    @Test
    fun `the most common is the most frequent`() {
        val stats = DoseHistoryStats.rebuild(listOf(100.0, 100.0, 100.0, 200.0, 50.0))!!
        stats.mostCommon shouldBe 100.0
        stats.mostCommonCount shouldBe 3
    }

    /**
     * A tie resolves to the **lower** amount, every time.
     *
     * The difference from upstream, whose `max(by:)` reaches one of the two by ordering accident. Asserted with the
     * list in both orders, because an implementation that returned "the first most-frequent entry seen" would pass one
     * and fail the other.
     */
    @Test
    fun `a tie resolves to the lower amount both ways round`() {
        val ascending = DoseHistoryStats.rebuild(listOf(100.0, 100.0, 200.0, 200.0))!!
        ascending.mostCommon shouldBe 100.0
        ascending.mostCommonCount shouldBe 2

        val descending = DoseHistoryStats.rebuild(listOf(200.0, 200.0, 100.0, 100.0))!!
        descending.mostCommon shouldBe 100.0
        descending.mostCommonCount shouldBe 2
    }

    /**
     * Amounts are keyed exactly, because they are the numbers the user typed.
     *
     * `100.0` twice is one dose logged twice; `100.1` is a different one. Approximating the key would merge two doses
     * the user distinguished.
     */
    @Test
    fun `amounts are keyed exactly`() {
        val stats = DoseHistoryStats.rebuild(listOf(100.0, 100.1, 100.1))!!
        stats.mostCommon shouldBe 100.1
        stats.mostCommonCount shouldBe 2
        stats.minDose shouldBe 100.0
        stats.maxDose shouldBe 100.1
    }

    /** A history of one repeated amount has a range of one figure, which is the card's single-number branch. */
    @Test
    fun `one repeated amount is a single figure`() {
        val stats = DoseHistoryStats.rebuild(listOf(25.0, 25.0, 25.0))!!
        stats.minDose shouldBe stats.maxDose
        stats.mostCommon shouldBe 25.0
        stats.mostCommonCount shouldBe 3
    }

    /** Fractional doses survive: a log is not restricted to whole milligrams. */
    @Test
    fun `fractional doses survive`() {
        val stats = DoseHistoryStats.rebuild(listOf(0.5, 0.25, 0.5))!!
        stats.minDose shouldBe 0.25
        stats.maxDose shouldBe 0.5
        stats.mostCommon shouldBe 0.5
    }
}
