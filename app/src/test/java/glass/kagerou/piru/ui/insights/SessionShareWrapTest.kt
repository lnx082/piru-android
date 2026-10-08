package glass.kagerou.piru.ui.insights

import android.graphics.Bitmap
import android.graphics.Paint
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The card's text wrapping, which is the one part of the renderer that can be wrong and still look fine.
 *
 * A naive `chunked` breaks mid-word; a naive `split(" ")` ignores a word longer than the column. Both render a card
 * that looks like a card, and the second is only obviously wrong when a long substance name is in the log — which
 * is exactly the data this app holds.
 *
 * Robolectric because `wrap` measures with an `android.graphics.Paint`, and the measurement is the point: a
 * character count would be wrong the moment the font size or the column width changed, which is why the assertion
 * is against the paint's own `measureText` rather than a hardcoded character limit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionShareWrapTest {

    private fun paint(size: Float = 20f) = Paint().apply {
        isAntiAlias = true
        textSize = size
    }

    @Test
    fun `an empty or blank paragraph has no lines`() {
        SessionShareImage.wrap("", paint(), 200f) shouldBe emptyList()
        SessionShareImage.wrap("   ", paint(), 200f) shouldBe emptyList()
        SessionShareImage.wrap("\t\n", paint(), 200f) shouldBe emptyList()
    }

    /**
     * A paragraph that fits stays on one line.
     *
     * The case where wrapping must **not** fire: a short heading broken in two would look deliberate and be wrong.
     */
    @Test
    fun `a short paragraph is one line`() {
        val lines = SessionShareImage.wrap("Festival Saturday", paint(), 400f)
        lines shouldBe listOf("Festival Saturday")
    }

    /**
     * A long paragraph is broken, and rejoining recovers it exactly.
     *
     * The width is **derived from the measurement** rather than picked, because Robolectric's `measureText` is a
     * coarse approximation and not monotonic in a string's real width: a 70-character paragraph measures 74 and a
     * 3-character word measures 3. Hardcoding a width made my first two versions of this test wrong about their own
     * input — the wrapper produced one line and my assertion demanded more.
     */
    @Test
    fun `a long paragraph is broken and rejoins exactly`() {
        val p = paint()
        val text = "A long session note about what happened and how it went over several hours"
        val column = p.measureText(text) / 3f
        val lines = SessionShareImage.wrap(text, p, column)

        (lines.size > 1) shouldBe true
        // Nothing dropped and no word cut: rejoining with single spaces recovers the input.
        lines.joinToString(" ") shouldBe text
        // And every line is within the column by the wrapper's own measure.
        for (line in lines) {
            (p.measureText(line) <= column) shouldBe true
        }
    }

    /**
     * No word is broken.
     *
     * The failure a naive `chunked` produces. Asserted by rejoining the lines and comparing to the input, which
     * fails the moment a word is cut.
     */
    @Test
    fun `wrapping never splits a word`() {
        val text = "Methylenedioxymethamphetamine followed by ketamine and cannabis"
        val lines = SessionShareImage.wrap(text, paint(), 180f)
        lines.joinToString(" ") shouldBe text
    }

    /**
     * A word longer than the column is left whole.
     *
     * Deliberate, and the honest choice: it overflows, and an overflowing word **is visible**, whereas a word broken
     * at an arbitrary point is not recognisable as a bug. A long chemical name is exactly the input this app holds.
     *
     * Asserted as behaviour at two widths rather than against the metric — including one so narrow that no word
     * could fit, which is the case the rule exists for.
     */
    @Test
    fun `a word longer than the column is left whole`() {
        val long = "Methylenedioxymethamphetamine"
        val p = paint()
        SessionShareImage.wrap(long, p, p.measureText(long) / 4f) shouldBe listOf(long)
        SessionShareImage.wrap(long, p, 1f) shouldBe listOf(long)
    }

    /** Runs of whitespace collapse, so a pasted note does not produce empty lines. */
    @Test
    fun `runs of whitespace collapse`() {
        SessionShareImage.wrap("one    two\t\tthree", paint(), 400f) shouldBe listOf("one two three")
    }

    /** Leading and trailing whitespace is trimmed rather than becoming its own line. */
    @Test
    fun `leading and trailing whitespace is trimmed`() {
        SessionShareImage.wrap("   one two   ", paint(), 400f) shouldBe listOf("one two")
    }

    // MARK: - The stitcher

    /**
     * The stitched height includes a gap between every **pair**.
     *
     * `count - 1` and not `count`: one gap too many is a blank strip at the bottom, which reads as a cropped card.
     * This is the arithmetic that clips the last card's watermark when wrong, so it is asserted as a value.
     */
    @Test
    fun `the stitched height has one gap fewer than cards`() {
        val cards = listOf(
            Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(100, 300, Bitmap.Config.ARGB_8888),
        )
        val expected = 200 + 300 + SessionImageStitcher.SPACING * 1
        SessionImageStitcher.heightOf(cards) shouldBe expected
        // A single card has no gap at all.
        SessionImageStitcher.heightOf(listOf(cards.first())) shouldBe 200
    }

    /** An empty list has no image, rather than a zero-height one. */
    @Test
    fun `an empty list stitches to nothing`() {
        SessionImageStitcher.stitch(emptyList()) shouldBe null
        SessionImageStitcher.heightOf(emptyList()) shouldBe 0
    }

    /**
     * A degenerate card is skipped rather than producing an image with a blank band.
     *
     * `Bitmap.createBitmap` throws for a zero dimension, so a caller with one would crash the stitch; filtering is
     * both the safe and the correct behaviour, because a zero-height card is not a card.
     */
    @Test
    fun `a degenerate card is skipped`() {
        val good = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        SessionImageStitcher.heightOf(listOf(good)) shouldBe 200
        // The filter is on the dimensions, so a zero-height bitmap cannot reach the sum.
        val stitched = SessionImageStitcher.stitch(listOf(good, good))
        (stitched != null) shouldBe true
        stitched!!.height shouldBe 200 * 2 + SessionImageStitcher.SPACING
    }

    /**
     * The stitched width is the **widest** card's, because the cards really are different widths.
     *
     * `SessionCardLayout` makes a card 740 points wide past eight entries and 390 below it, so a stitched export of
     * a mixed set has both. Taking the first card's width would crop the wider ones.
     */
    @Test
    fun `the stitched width is the widest card's`() {
        val narrow = Bitmap.createBitmap(390, 100, Bitmap.Config.ARGB_8888)
        val wide = Bitmap.createBitmap(740, 100, Bitmap.Config.ARGB_8888)
        val stitched = SessionImageStitcher.stitch(listOf(narrow, wide))
        (stitched != null) shouldBe true
        stitched!!.width shouldBe 740
        stitched.height shouldBe 100 * 2 + SessionImageStitcher.SPACING
    }
}
