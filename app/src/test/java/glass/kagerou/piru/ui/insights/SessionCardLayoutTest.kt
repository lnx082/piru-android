package glass.kagerou.piru.ui.insights

import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Test

/**
 * The share card's layout decisions and its text wrapping.
 *
 * ## Why these and not the drawing
 * A canvas has no layout engine, so every position on the card is arithmetic — and arithmetic inside a `drawText`
 * call is arithmetic nothing can test. The rules live in `SessionCardLayout` and the wrapping in
 * `SessionShareImage.wrap`, so the two parts that can be wrong in a way that **still renders** are the two parts
 * that are testable:
 *
 * - A card that breaks a line mid-word renders fine and reads as a typo.
 * - A card whose columns are uneven renders fine and looks like a rendering fault.
 * - A card whose subtitle repeats the date under a date heading renders fine and looks careless.
 *
 * ## The wrapping needs a `Paint`, which needs Android
 * `wrap` takes a `Paint`, so its test runs under Robolectric — which is why this file is in the app module's test
 * source rather than beside the layout object. The layout rules themselves are plain Kotlin and are asserted first.
 */
class SessionCardLayoutTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    // MARK: - The title

    /**
     * A blank title falls back to the date.
     *
     * The ordinary case, not an error: most sessions are not named, and a card with an empty heading is a card with
     * no way to tell which session it is.
     */
    @Test
    fun `a blank title falls back to the date`() {
        SessionCardLayout.displayTitle("", "3 March") shouldBe "3 March"
        SessionCardLayout.displayTitle("   ", "3 March") shouldBe "3 March"
        SessionCardLayout.displayTitle("Festival", "3 March") shouldBe "Festival"
    }

    /**
     * The subtitle is the time, and the date only when the heading does not already say it.
     *
     * The rule that stops a card titled "3 March" from saying "3 March · 14:22" underneath itself twice.
     */
    @Test
    fun `the subtitle omits a date the heading already says`() {
        val at = Instant.parse("2026-03-03T14:22:00Z")
        // Untitled: the heading is the date, so the subtitle is only the time.
        SessionCardLayout.subtitle("", "3 March", at, zone) shouldBe "14:22"
        // Titled: the date is not in the heading, so it is added.
        SessionCardLayout.subtitle("Festival", "3 March", at, zone) shouldBe "3 March · 14:22"
    }

    /**
     * A title that happens to equal the date is treated as the date.
     *
     * The comparison is on the strings, which is upstream's own rule and is right for the reason above: if the
     * heading reads as the date, the subtitle does not need to repeat it — however it got that way.
     */
    @Test
    fun `a title equal to the date is treated as the date`() {
        val at = Instant.parse("2026-03-03T14:22:00Z")
        SessionCardLayout.subtitle("3 March", "3 March", at, zone) shouldBe "14:22"
    }

    // MARK: - The two-column threshold

    /**
     * Past eight entries the card widens and splits.
     *
     * Asserted from both sides of the threshold, because an off-by-one here changes the card's shape rather than
     * breaking it — the failure is a card nobody notices is wrong.
     */
    @Test
    fun `the two-column threshold is eight`() {
        SessionCardLayout.isTwoColumn(8) shouldBe false
        SessionCardLayout.isTwoColumn(9) shouldBe true
        SessionCardLayout.isTwoColumn(0) shouldBe false
        SessionCardLayout.cardWidth(8) shouldBe SessionCardLayout.NARROW_WIDTH
        SessionCardLayout.cardWidth(9) shouldBe SessionCardLayout.WIDE_WIDTH
    }

    /** The content width is the card's minus its padding, on both sides. */
    @Test
    fun `the content width excludes the padding on both sides`() {
        SessionCardLayout.contentWidth(1) shouldBe SessionCardLayout.NARROW_WIDTH - SessionCardLayout.PADDING * 2
        SessionCardLayout.contentWidth(20) shouldBe SessionCardLayout.WIDE_WIDTH - SessionCardLayout.PADDING * 2
    }

    /**
     * The columns are in reading order, and the first gets the extra row when the count is odd.
     *
     * `(size + 1) / 2`, which is upstream's arithmetic and is visible: a card whose left column is shorter than its
     * right looks like a rendering fault rather than a choice.
     */
    @Test
    fun `the left column takes the extra row`() {
        SessionCardLayout.splitColumns((1..9).toList()) shouldBe ((1..5).toList() to (6..9).toList())
        SessionCardLayout.splitColumns((1..8).toList()) shouldBe ((1..4).toList() to (5..8).toList())
        SessionCardLayout.splitColumns(listOf(1)) shouldBe (listOf(1) to emptyList())
        SessionCardLayout.splitColumns(emptyList<Int>()) shouldBe (emptyList<Int>() to emptyList())
    }

    // MARK: - The labels

    /**
     * One entry is "1 entry".
     *
     * A card saying "1 entries" is the kind of thing a reader notices and the author never sees, because an export
     * with one dose in it is rarely looked at.
     */
    @Test
    fun `the count line is singular for one`() {
        SessionCardLayout.entryCountText(0) shouldBe "0 entries"
        SessionCardLayout.entryCountText(1) shouldBe "1 entry"
        SessionCardLayout.entryCountText(2) shouldBe "2 entries"
    }

    /**
     * The session span, and null under a minute.
     *
     * Null rather than "0m": a session under a minute is a moment, and "0m" is a claim that nothing happened.
     */
    @Test
    fun `the session span reads as a duration and is absent under a minute`() {
        val start = Instant.parse("2026-03-03T20:00:00Z")
        SessionCardLayout.sessionSpan(start, start.plusSeconds(30)) shouldBe null
        SessionCardLayout.sessionSpan(start, start.plusSeconds(60)) shouldBe "1m"
        SessionCardLayout.sessionSpan(start, start.plusSeconds(90 * 60)) shouldBe "1h 30m"
        SessionCardLayout.sessionSpan(start, start.plusSeconds(3 * 3600)) shouldBe "3h"
    }

    /** The watermark names where the image came from — it leaves the app. */
    @Test
    fun `the watermark names the app`() {
        (SessionCardLayout.WATERMARK.contains("Piru")) shouldBe true
        (SessionCardLayout.WATERMARK.isNotBlank()) shouldBe true
    }
}
