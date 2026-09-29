package glass.kagerou.piru.ui.insights

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Text on a fixed-size page: one line, or a wrapped block, with its own height.
 *
 * Android's `Canvas` has no equivalent of UIKit's `NSString.draw(at:withAttributes:)`
 * or `boundingRect(with:options:)`, which the iOS report renderer is built on — it has
 * `drawText`, which draws one line and does not wrap. So the two pieces that renderer
 * gets for free live here: wrap a string to a width and say how tall the result will
 * be before anything is drawn (the renderer needs the height to decide whether the
 * block still fits on this page), then draw it inside that box.
 *
 * ## Why the height is quoted before the draw
 * Every section decides whether it fits with `ensureSpace(height)`, which breaks the
 * page if it does not. A wrapped paragraph is the only element whose height is not
 * known up front, so it has to be measured first and drawn second — the two calls are
 * deliberately separate for that reason rather than a single `drawWrapped` that
 * paginates internally.
 *
 * ## Why a `Paint` is passed in rather than created per call
 * A `Paint` carries the face, the size and the colour, and the report has five styles
 * that never change. Building one per line would re-resolve the typeface for every row
 * of a table that can run to hundreds of rows.
 */
internal object PdfText {

    /** One laid-out line: what to draw. Its baseline is the block's, walked by leading. */
    private data class Line(val text: String)

    /**
     * The height [text] needs at [width], wrapped on spaces.
     *
     * No trailing spacing is included: the caller adds its own leading, because the
     * report's sections space themselves differently (a table row is tighter than a
     * paragraph) and a measurement that baked in one of those choices would be wrong
     * for the others.
     */
    fun height(text: String, paint: Paint, width: Float): Float {
        if (text.isEmpty()) return 0f
        return lines(text, paint, width).size * lineHeight(paint)
    }

    /**
     * Draw [text] wrapped inside the box whose top-left is `[x], [y]`.
     *
     * @return the height it consumed, which is [height] for the same arguments.
     */
    fun draw(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        paint: Paint,
        width: Float,
    ): Float {
        if (text.isEmpty()) return 0f
        val leading = lineHeight(paint)
        var baseline = y - paint.fontMetrics.ascent
        for (line in lines(text, paint, width)) {
            canvas.drawText(line.text, x, baseline, paint)
            baseline += leading
        }
        return lines(text, paint, width).size * leading
    }

    /** Draw a single line, returning nothing: its x/y are the caller's business. */
    fun drawLine(canvas: Canvas, text: String, x: Float, baseline: Float, paint: Paint) {
        canvas.drawText(text, x, baseline, paint)
    }

    /** One line's width, for right-aligning a label against the page's edge. */
    fun width(text: String, paint: Paint): Float = paint.measureText(text)

    /** The leading the drawn lines use, which is what [height] measures in. */
    fun lineHeight(paint: Paint): Float {
        val metrics = paint.fontMetrics
        // A touch of leading: the report's body text at 10pt would otherwise set the
        // lines flush and read as a block.
        return (metrics.descent - metrics.ascent) + 1.5f
    }

    private fun lines(text: String, paint: Paint, width: Float): List<Line> {
        val out = mutableListOf<Line>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isEmpty()) {
                out += Line("")
                continue
            }
            var current = StringBuilder()
            for (word in paragraph.split(' ')) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (current.isNotEmpty() && paint.measureText(candidate) > width) {
                    out += Line(current.toString())
                    current = StringBuilder(word)
                } else {
                    current = StringBuilder(candidate)
                }
            }
            if (current.isNotEmpty()) out += Line(current.toString())
        }
        return out
    }

    /** The bold face, resolved once: `Typeface.DEFAULT_BOLD` is what the pages use. */
    val bold: Typeface get() = Typeface.DEFAULT_BOLD

    val regular: Typeface get() = Typeface.DEFAULT
}
