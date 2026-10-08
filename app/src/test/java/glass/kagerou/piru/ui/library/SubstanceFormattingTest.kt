package glass.kagerou.piru.ui.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The two unit formatters the substance page's new sections use.
 *
 * ## Why these are worth a test of their own
 * Both take a `Double` straight off the catalogue, where the same physical quantity arrives in wildly
 * different magnitudes: a half-life ranges from minutes (a research chemical) to weeks (a long-acting
 * depot), and a tolerance figure ranges from under a day to several weeks. A single unit is therefore
 * unreadable at one end of each range — "0.02 d" for a half-life, or "504 h" for a tolerance reset.
 *
 * The thresholds and the trailing-zero rule are the parts that are easy to get subtly wrong and
 * invisible when they are: "4.0 h" and "4 h" both look fine until they sit in a column together.
 */
class SubstanceFormattingTest {

    // MARK: - formatHalfLife

    @Test
    fun `a half-life under two hours reads in minutes`() {
        formatHalfLife(0.0) shouldBe "0 min"
        formatHalfLife(30.0) shouldBe "30 min"
        formatHalfLife(119.0) shouldBe "119 min"
    }

    /**
     * The boundary is two hours, and both sides of it are asserted.
     *
     * `120` is the first value that reads in hours; `119` is the last that reads in minutes. An
     * off-by-one here would put "1 min" or "2.0 h" where the other belongs, and nothing would look
     * broken.
     */
    @Test
    fun `the minute-to-hour boundary is two hours`() {
        formatHalfLife(119.0) shouldBe "119 min"
        formatHalfLife(120.0) shouldBe "2 h"
    }

    /**
     * A whole number of hours loses its decimal, and a fractional one keeps exactly one.
     *
     * This is the rule that makes a column of half-lives readable: "4 h" beside "3.5 h", never "4.0 h".
     */
    @Test
    fun `whole hours drop the decimal and fractions keep one`() {
        formatHalfLife(240.0) shouldBe "4 h"
        formatHalfLife(210.0) shouldBe "3.5 h"
        formatHalfLife(90.0) shouldBe "90 min"
    }

    /**
     * Two days and beyond reads in days.
     *
     * Chosen by the same reasoning as the minute boundary: "48 h" is worse than "2 d", and the depot
     * antipsychotics and the long-acting benzodiazepines are exactly the substances whose half-lives
     * land here.
     */
    @Test
    fun `two days and beyond reads in days`() {
        // One minute under two days still reads in hours, at the formatter's one decimal place: 2879
        // minutes is 47.9833 hours and prints as 48.0, which is the rounding a reader sees.
        formatHalfLife(2 * 24 * 60 - 1.0) shouldBe "48.0 h"
        formatHalfLife(2 * 24 * 60.0) shouldBe "2.0 d"
        formatHalfLife(14 * 24 * 60.0) shouldBe "14.0 d"
    }

    /**
     * A negative half-life cannot produce a negative string.
     *
     * Not physical, but the catalogue's columns are nullable `Double`s read from several sources, and
     * "-5 min" on a reference card would read as a real figure.
     */
    @Test
    fun `a negative half-life clamps to zero`() {
        formatHalfLife(-1.0) shouldBe "0 min"
    }

    // MARK: - formatDays

    @Test
    fun `a tolerance figure under a day reads in hours`() {
        formatDays(0.5) shouldBe "12 h"
        formatDays(0.25) shouldBe "6 h"
    }

    @Test
    fun `the hour-to-day boundary is one day`() {
        formatDays(0.99) shouldBe "24 h"
        formatDays(1.0) shouldBe "1 d"
    }

    /**
     * A fortnight is where weeks take over, because "21.0 d" is a number nobody converts in their head.
     *
     * Asserted on both sides of the boundary for the same reason as the others: the failure mode is a
     * plausible-looking value in the wrong unit.
     */
    @Test
    fun `a fortnight and beyond reads in weeks`() {
        formatDays(13.0) shouldBe "13 d"
        formatDays(13.5) shouldBe "13.5 d"
        formatDays(14.0) shouldBe "2.0 weeks"
        formatDays(21.0) shouldBe "3.0 weeks"
    }

    @Test
    fun `whole days drop the decimal`() {
        formatDays(1.0) shouldBe "1 d"
        formatDays(2.0) shouldBe "2 d"
        formatDays(1.5) shouldBe "1.5 d"
    }

    @Test
    fun `a negative tolerance clamps to zero`() {
        formatDays(-3.0) shouldBe "0 h"
    }
}
