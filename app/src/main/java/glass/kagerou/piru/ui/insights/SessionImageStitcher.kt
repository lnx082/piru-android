package glass.kagerou.piru.ui.insights

import android.graphics.Bitmap
import android.graphics.Canvas

/**
 * Several session cards stacked into one tall image.
 *
 * Ported from `ReportsModel.exportStitchedImage`. Three rules, each of which a reader would notice:
 *
 * 1. **A gap between cards**, so a scroll through the image shows where one session ends. Without it, two cards
 *    running together look like one session with a confused heading.
 * 2. **The width is the widest card's**, and narrower cards are **centred** in it. That matters because the cards
 *    are genuinely different widths: `SessionCardLayout` makes a card 740 points wide past eight entries and 390
 *    below it, so a stitched export of a mixed set has both. Centring is what keeps their headings in a line rather
 *    than ragged down the left.
 * 3. **Nothing is scaled.** A shrunk card would be illegible at the size a reader views the long image, and the
 *    alternative — cropping — loses rows. The image is tall, which is what a long image is for.
 *
 * ## Why this is separate from the renderer
 * It takes bitmaps and returns a bitmap, so it needs no data model, no catalogue and no tint resolution — which
 * makes the whole of it testable. The one thing it can get wrong that *looks* fine is the total height, and an
 * off-by-one there clips the last card's watermark.
 */
internal object SessionImageStitcher {

    /** The gap between cards, in pixels. Upstream's 24 points at the renderer's own scale. */
    const val SPACING: Int = 24 * SessionCardLayout.SCALE

    /**
     * Stacks [cards] top to bottom. Null for an empty list.
     *
     * Null rather than an empty bitmap: a share sheet offering a zero-height image is worse than the button not
     * being there, which is the same call the single-session renderer makes.
     */
    fun stitch(cards: List<Bitmap>): Bitmap? {
        val live = cards.filter { it.width > 0 && it.height > 0 }
        if (live.isEmpty()) return null

        val width = live.maxOf { it.width }
        // The total includes a gap between every **pair**, so `count - 1` gaps and not `count`: one extra gap is a
        // blank strip at the bottom, which reads as a cropped card.
        val totalHeight = live.sumOf { it.height } + SPACING * (live.size - 1)
        if (totalHeight <= 0) return null

        val output = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        var y = 0
        for (card in live) {
            // Centred horizontally, because the cards really are different widths.
            val x = ((width - card.width) / 2).toFloat()
            canvas.drawBitmap(card, x, y.toFloat(), null)
            y += card.height + SPACING
        }
        return output
    }

    /**
     * The height a stitched image of these cards will have.
     *
     * Exposed because it is the arithmetic that clips a card when wrong, and because a caller can size a preview
     * from it without making the bitmap.
     */
    fun heightOf(cards: List<Bitmap>): Int {
        val live = cards.filter { it.width > 0 && it.height > 0 }
        if (live.isEmpty()) return 0
        return live.sumOf { it.height } + SPACING * (live.size - 1)
    }
}
