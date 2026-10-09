package glass.kagerou.piru.ui.library

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.jupiter.api.Test

/**
 * The history header's span, and the dose figures beside it.
 *
 * ## The one decision in the span
 * A single month prints as **one figure**. Upstream compares at month granularity for exactly that reason: "March 2025
 * – March 2025" reads as a range and is not one. A history spanning two months prints both, so the header never claims
 * a span it does not have and never hides one it does.
 *
 * ## The one decision in the figures
 * A whole amount has no trailing `.0` — the same rule the body-load and metabolite amounts follow, because `100.0`
 * reads as a measurement taken to a tenth of a milligram. And the trimming of a fractional amount must not turn
 * `2.50` into `2.5` and then into `2.`.
 */
class HistorySpanTest {

    private val utc = ZoneId.of("UTC")

    private fun at(year: Int, month: Int, day: Int): java.time.Instant =
        ZonedDateTime.of(year, month, day, 12, 0, 0, 0, utc).toInstant()

    /** One month is one figure, whatever the days are. */
    @Test
    fun `a single month is one figure`() {
        // Printed first, because the first version of this case asserted "March 2025" for both pairs on the reasoning
        // that a `MMMM yyyy` formatter must produce a full month name — and one of the two came back "Mar 2025". What
        // the two pairs are is now a recorded fact rather than an inference.
        val first = historySpan(at(2025, 3, 1), at(2025, 3, 28), utc)
        val second = historySpan(at(2025, 3, 9), at(2025, 3, 9), utc)
        println("HISTORYPROBE first='" + first + "' second='" + second + "'")

        first shouldBe "Mar 2025"
        second shouldBe "Mar 2025"
    }

    /** Two months print both, so the header does not hide a span. */
    @Test
    fun `two months print both`() {
        val span = historySpan(at(2024, 1, 15), at(2025, 3, 2), utc)
        span shouldBe "Jan 2024 – Mar 2025"
        span.contains("–") shouldBe true
    }

    /**
     * The same month in **different years** is two figures.
     *
     * The case a month-only comparison gets wrong: March 2024 and March 2025 are the same month and a year apart.
     */
    @Test
    fun `the same month in another year is a span`() {
        val span = historySpan(at(2024, 3, 10), at(2025, 3, 10), utc)
        span shouldNotBe "Mar 2024"
        span shouldBe "Mar 2024 – Mar 2025"
    }

    // MARK: - The figures

    /** A whole amount has no decimal part. */
    @Test
    fun `a whole amount has no decimal`() {
        formatDose(100.0) shouldBe "100"
        formatDose(0.0) shouldBe "0"
        formatDose(1_000.0) shouldBe "1000"
    }

    /**
     * A fractional amount keeps its significant decimals and **no** trailing zero or bare point.
     *
     * `2.50` trimmed carelessly becomes `2.5` and then `2.` — the second step is the failure this pins.
     */
    @Test
    fun `a fractional amount trims cleanly`() {
        formatDose(2.5) shouldBe "2.5"
        formatDose(0.25) shouldBe "0.25"
        formatDose(12.5) shouldBe "12.5"
    }

    /** The decimal separator is a point whatever the device's locale is, as everywhere else in this port. */
    @Test
    fun `the figures ignore the device locale`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            formatDose(2.5) shouldBe "2.5"
        } finally {
            java.util.Locale.setDefault(original)
        }
    }
}
