package glass.kagerou.piru.ui.insights

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.model.P3Color
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import glass.kagerou.piru.ui.theme.toComposeColor
import androidx.compose.ui.graphics.toArgb

/**
 * The shareable session image: one card, drawn onto a bitmap.
 *
 * Ported from `SessionShareImage`, which renders a SwiftUI view through `ImageRenderer`. There is no equivalent on
 * Android that takes a composable off-screen without a graphics layer and a frame, so this draws onto an
 * `android.graphics.Canvas` — **the same approach `PdfReportWriter` already takes for the PDF report**, which is
 * why the two look alike and why the text measuring below is shared in spirit.
 *
 * ## Why a bitmap and not a captured composable
 * A captured composable needs a live composition, so the export would have to be staged on screen and hidden — which
 * means it depends on the screen it was launched from, and cannot be produced for a session the user is not looking
 * at. Drawing directly means the export is a function of the data, which is also what makes it testable: the
 * arithmetic is all in [SessionCardLayout] and the calls here are `drawText` and `drawRect`.
 *
 * ## Why the text wrapping is hand-rolled
 * A canvas has no layout engine and no `StaticLayout` helper here, so every paragraph is measured and broken
 * explicitly. That is the one part of this file that could be wrong in a way that looks fine — a line broken
 * mid-word still renders — which is why [wrap] is a separate function with its own tests.
 */
internal object SessionShareImage {

    /** The card's background, matching the app's own surface rather than a share-specific colour. */
    private val BACKGROUND = Color.parseColor("#12101A")
    private val PRIMARY = Color.parseColor("#F2F0F5")
    private val SECONDARY = Color.parseColor("#A9A4B8")
    private val ACCENT = Color.parseColor("#7C5CFF")

    /**
     * How the on-screen palette's `P3Color` is turned into a bitmap pixel.
     *
     * Through `toComposeColor` and then `toArgb`, which is the same conversion the charts use — the whole point of
     * BUG #3's fix was that a substance's colour must be the *same* colour wherever it is drawn, and an export that
     * converted it differently would be a second, wrong answer.
     */
    private fun argb(tint: P3Color): Int = tint.toComposeColor().toArgb()

    /**
     * Renders the card, or null when there is nothing to draw.
     *
     * Null for an empty session rather than a card saying "0 entries": a share sheet offering an empty image is a
     * worse outcome than the button not being there.
     */
    fun render(
        context: Context,
        title: String,
        dateText: String,
        entries: List<DoseEntryEntity>,
        tintFor: (String) -> P3Color,
        /**
         * The route's display label, resolved by the caller.
         *
         * `CoreLabels.route` is `@Composable`, and this renderer is not: it is called from a share handler rather
         * than from a composition. Passing the label in also keeps this a pure function of its inputs, which is what
         * makes the card's arithmetic testable without a device.
         */
        routeLabelFor: (glass.kagerou.piru.model.RouteOfAdministration) -> String,
        zone: ZoneId = ZoneId.systemDefault(),
        capturedAt: Instant = Instant.now(),
    ): Bitmap? {
        if (entries.isEmpty()) return null

        val twoColumn = SessionCardLayout.isTwoColumn(entries.size)
        val width = SessionCardLayout.cardWidth(entries.size)
        val padding = SessionCardLayout.PADDING

        // Laid out first into a list of operations, so the bitmap can be sized to the content rather than to a
        // guess. A card whose height is fixed is a card with either a gap at the bottom or a clipped last row.
        val heading = SessionCardLayout.displayTitle(title, dateText)
        val subtitle = SessionCardLayout.subtitle(title, dateText, capturedAt, zone)
        val operations = layout(
            heading = heading,
            subtitle = subtitle,
            entries = entries,
            tintFor = tintFor,
            routeLabelFor = routeLabelFor,
            zone = zone,
            width = width,
            padding = padding,
            twoColumn = twoColumn,
        )

        val height = operations.lastOrNull()?.let { it.y + it.height }?.toInt() ?: 0
        if (height <= 0) return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)
        for (operation in operations) operation.draw(canvas)
        return bitmap
    }

    /**
     * The card's date line, from the session's own start.
     *
     * Here rather than in the screen so the format is the card's rather than the screen's: the image is what a
     * reader sees outside the app, and a date formatted for a toolbar would be the wrong shape on a card.
     */
    fun dateText(start: Instant, zone: ZoneId): String =
        start.atZone(zone).format(DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.getDefault()))

    /** One drawable step, with the vertical position it occupies. Laid out before anything is drawn. */
    private sealed interface Op {
        val y: Float
        val height: Float
        fun draw(canvas: Canvas)
    }

    private data class RectOp(
        override val y: Float,
        override val height: Float,
        val left: Float,
        val right: Float,
        val colour: Int,
    ) : Op {
        override fun draw(canvas: Canvas) {
            canvas.drawRect(left, y, right, y + height, Paint().apply { color = colour })
        }
    }

    private data class TextOp(
        override val y: Float,
        override val height: Float,
        val lines: List<String>,
        val x: Float,
        val paint: Paint,
        val lineHeight: Float,
    ) : Op {
        override fun draw(canvas: Canvas) {
            var baseline = y - paint.ascent()
            for (line in lines) {
                canvas.drawText(line, x, baseline, paint)
                baseline += lineHeight
            }
        }
    }

    /**
     * Breaks a paragraph into lines that fit [maxWidth].
     *
     * ## The one thing here that can look fine and be wrong
     * A naive `chunked` breaks mid-word; a naive `split(" ")` ignores a single word longer than the column. This
     * measures each candidate line with the paint that will draw it, which is the only way to know — a character
     * count would be wrong the moment the card's font or width changes.
     *
     * A word longer than the width is **left whole on its own line** rather than hyphenated: it overflows, and an
     * overflowing word is visible, whereas a word broken at an arbitrary point is not recognisable as a bug.
     */
    internal fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return emptyList()
        val words = text.trim().split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) lines.add(current.toString())
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }

    private fun textPaint(size: Float, colour: Int, bold: Boolean = false) = Paint().apply {
        isAntiAlias = true
        color = colour
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    /** Lays the card out top to bottom, returning every operation with its position. */
    private fun layout(
        heading: String,
        subtitle: String,
        entries: List<DoseEntryEntity>,
        tintFor: (String) -> P3Color,
        routeLabelFor: (glass.kagerou.piru.model.RouteOfAdministration) -> String,
        zone: ZoneId,
        width: Int,
        padding: Int,
        twoColumn: Boolean,
    ): List<Op> {
        val ops = mutableListOf<Op>()
        val left = padding.toFloat()
        val contentWidth = (width - padding * 2).toFloat()
        val right = width - padding.toFloat()
        var y = padding.toFloat()

        // --- Header -------------------------------------------------------------------
        val headingPaint = textPaint(34f, PRIMARY, bold = true)
        val headingLines = wrap(heading, headingPaint, contentWidth)
        val headingLineHeight = headingPaint.textSize * 1.25f
        ops += TextOp(y, headingLineHeight * headingLines.size, headingLines, left, headingPaint, headingLineHeight)
        y += headingLineHeight * headingLines.size + 4f

        val subtitlePaint = textPaint(18f, SECONDARY)
        val subtitleLines = wrap(subtitle, subtitlePaint, contentWidth)
        val subtitleLineHeight = subtitlePaint.textSize * 1.3f
        ops += TextOp(y, subtitleLineHeight * subtitleLines.size, subtitleLines, left, subtitlePaint, subtitleLineHeight)
        y += subtitleLineHeight * subtitleLines.size + 8f

        // The accent rule, which is the card's only decoration and marks where the header ends.
        ops += RectOp(y, 3f, left, left + 40f, ACCENT)
        y += 3f + 18f

        // --- Entries ------------------------------------------------------------------
        val countPaint = textPaint(16f, SECONDARY)
        val countText = SessionCardLayout.entryCountText(entries.size)
        ops += TextOp(y, countPaint.textSize * 1.3f, listOf(countText), left, countPaint, countPaint.textSize * 1.3f)
        y += countPaint.textSize * 1.5f + 6f

        val namePaint = textPaint(19f, PRIMARY, bold = true)
        val metaPaint = textPaint(15f, SECONDARY)
        val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

        if (twoColumn) {
            val (first, second) = SessionCardLayout.splitColumns(entries)
            val columnGap = 16f
            val columnWidth = (contentWidth - columnGap) / 2f
            val leftEnd = drawEntries(ops, first, left, y, columnWidth, tintFor, routeLabelFor, zone, timeFormat, namePaint, metaPaint)
            val rightY = drawEntries(
                ops, second, left + columnWidth + columnGap, y, columnWidth,
                tintFor, routeLabelFor, zone, timeFormat, namePaint, metaPaint,
            )
            // The taller column decides, so neither is clipped.
            y = maxOf(leftEnd, rightY)
        } else {
            y = drawEntries(ops, entries, left, y, contentWidth, tintFor, routeLabelFor, zone, timeFormat, namePaint, metaPaint)
        }

        // --- Watermark ------------------------------------------------------------------
        y += 10f
        val markPaint = textPaint(13f, SECONDARY)
        val markLines = wrap(SessionCardLayout.WATERMARK, markPaint, contentWidth)
        ops += TextOp(y, markPaint.textSize * 1.3f * markLines.size, markLines, left, markPaint, markPaint.textSize * 1.3f)
        y += markPaint.textSize * 1.3f * markLines.size + padding

        return ops
    }

    /** One column of entries, returning the y it finished at. */
    private fun drawEntries(
        ops: MutableList<Op>,
        entries: List<DoseEntryEntity>,
        left: Float,
        startY: Float,
        columnWidth: Float,
        tintFor: (String) -> P3Color,
        routeLabelFor: (glass.kagerou.piru.model.RouteOfAdministration) -> String,
        zone: ZoneId,
        timeFormat: DateTimeFormatter,
        namePaint: Paint,
        metaPaint: Paint,
    ): Float {
        var y = startY
        val dotRadius = 5f
        val lineHeight = namePaint.textSize * 1.25f
        for (entry in entries) {
            // The dot, so a reader can match a row to the substance's colour elsewhere.
            ops += RectOp(y + 4f, dotRadius * 2, left, left + dotRadius * 2, argb(tintFor(entry.substance)))
            val textLeft = left + dotRadius * 2 + 8f
            val textWidth = columnWidth - (dotRadius * 2 + 8f)

            val nameLines = wrap(entry.substance, namePaint, textWidth)
            ops += TextOp(y, lineHeight * nameLines.size, nameLines, textLeft, namePaint, lineHeight)
            y += lineHeight * nameLines.size

            // The amount through the entity's own readout, which carries `?` and `~` — the same string the journal
            // and the PDF print, so an exported image cannot disagree with the screen it came from.
            val meta = buildString {
                append(entry.amountDisplay)
                append(' ')
                append(entry.unit)
                append(" · ")
                append(entry.timestamp.toInstant().atZone(zone).format(timeFormat))
                // The route through the app's own label, so the image says "oral" the way every screen does
                // rather than printing the enum's wire value.
                append(" · ")
                append(routeLabelFor(entry.route))
            }
            val metaLines = wrap(meta, metaPaint, textWidth)
            ops += TextOp(y, metaPaint.textSize * 1.3f * metaLines.size, metaLines, textLeft, metaPaint, metaPaint.textSize * 1.3f)
            y += metaPaint.textSize * 1.3f * metaLines.size + 10f
        }
        return y
    }

    /**
     * Writes the bitmap to a shareable cache file and returns a `content://` URI for it.
     *
     * Cache rather than external storage, and through the same `FileProvider` the PDF export uses — so the share
     * intent carries a grant the receiving app can use, which is the fix for the print-spooler `SecurityException`
     * this port already hit once for the PDF.
     */
    fun writeToCache(context: Context, bitmap: Bitmap, name: String): android.net.Uri {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(directory, "$name.png")
        FileOutputStream(file).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** A `RectF` helper kept for the callers that need one; the draws above use plain coordinates. */
    internal fun rect(left: Float, top: Float, right: Float, bottom: Float): RectF =
        RectF(left, top, right, bottom)
}
