package glass.kagerou.piru.ui.insights

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The session share card's layout decisions, kept out of the drawing.
 *
 * Ported from `SessionShareImage`, whose `ImageRenderer` renders a SwiftUI view. Android has no equivalent that
 * takes a composable off-screen without a graphics layer and a frame, so the card is drawn onto a
 * `android.graphics.Canvas` the way `PdfReportWriter` already draws the PDF — and **that is why these rules are
 * here rather than in the draw calls**: a canvas has no layout engine, so every position is arithmetic somebody has
 * to get right, and arithmetic in a `drawText` call is arithmetic nothing can test.
 *
 * ## The four decisions
 * 1. **The title falls back to the date**, so a card never has an empty heading — an untitled session is the
 *    ordinary case, not an error.
 * 2. **The subtitle is the capture time, and the date only when it differs from the title.** Upstream's rule, and
 *    it is the reason a card titled "3 March" does not say "3 March · 14:22" underneath itself twice.
 * 3. **Two columns past eight entries.** One column of thirty rows is a strip; two columns of fifteen is a card.
 * 4. **The watermark.** A shareable image leaves the app, so it says where it came from.
 */
internal object SessionCardLayout {

    /** Entries past this go to two columns. Upstream's `twoColumnThreshold`. */
    const val TWO_COLUMN_THRESHOLD: Int = 8

    /** The one-column card width, in points. Two columns are wider, which is why the caller needs to know. */
    const val NARROW_WIDTH: Int = 390
    const val WIDE_WIDTH: Int = 740

    /** The padding inside the card, on every side. */
    const val PADDING: Int = 20

    /** Rendered at this multiple, so the image is sharp on a high-density screen. Upstream uses 3. */
    const val SCALE: Int = 3

    /** Whether the card splits its lists. */
    fun isTwoColumn(entryCount: Int): Boolean = entryCount > TWO_COLUMN_THRESHOLD

    /** The card's width for a given entry count. */
    fun cardWidth(entryCount: Int): Int = if (isTwoColumn(entryCount)) WIDE_WIDTH else NARROW_WIDTH

    /** The content width, which is what the columns and the graph are laid out in. */
    fun contentWidth(entryCount: Int): Int = cardWidth(entryCount) - PADDING * 2

    /**
     * The heading: the session's own title, or the date when it has none.
     *
     * A blank or whitespace-only title is no title, which is the ordinary case rather than an error — most sessions
     * are not named.
     */
    fun displayTitle(title: String, dateText: String): String =
        title.takeIf { it.isNotBlank() } ?: dateText

    /**
     * The line under the heading: the time the card was made, and the date only when the heading does not already
     * say it.
     *
     * The comparison is on the strings, which is upstream's own — a title that happens to equal the date text is
     * treated as the date, because that is what it is: `displayTitle` returned the date.
     */
    fun subtitle(title: String, dateText: String, capturedAt: Instant, zone: ZoneId): String {
        val time = capturedAt.atZone(zone).format(TIME_FORMAT)
        val heading = displayTitle(title, dateText)
        return if (heading == dateText) time else "$dateText · $time"
    }

    /** Locale-independent: the card is an image, so it cannot be re-localised after it is made. */
    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /**
     * Splits a list into the two columns, in reading order.
     *
     * The first half gets the **extra** row when the count is odd — `(size + 1) / 2`, upstream's arithmetic — so a
     * list of nine becomes five and four rather than four and five. That is deliberate and visible: a card whose
     * left column is shorter than its right looks like a rendering fault.
     */
    fun <T> splitColumns(items: List<T>): Pair<List<T>, List<T>> {
        if (items.isEmpty()) return emptyList<T>() to emptyList()
        val mid = (items.size + 1) / 2
        return items.take(mid) to items.drop(mid)
    }

    /**
     * The entry count line, singular for one.
     *
     * A card saying "1 entries" is the kind of thing a reader notices and the author never sees, because the export
     * that produces it is rarely looked at with one dose in it.
     */
    fun entryCountText(count: Int): String = if (count == 1) "1 entry" else "$count entries"

    /** How long the session ran, for the header line. Null when it is under a minute, which reads as a moment. */
    fun sessionSpan(start: Instant, end: Instant): String? {
        val minutes = Duration.between(start, end).toMinutes()
        if (minutes < 1) return null
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0L -> "${rest}m"
            rest == 0L -> "${hours}h"
            else -> "${hours}h ${rest}m"
        }
    }

    /** The watermark. A shareable image leaves the app, so it says where it came from. */
    const val WATERMARK: String = "Generated by Piru · kagerou.glass/piru"
}
