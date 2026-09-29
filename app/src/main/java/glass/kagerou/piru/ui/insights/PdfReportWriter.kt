package glass.kagerou.piru.ui.insights

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import glass.kagerou.piru.engine.InteractionSeverity
import glass.kagerou.piru.engine.PKModel
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The shareable medical-style report, as a PDF.
 *
 * Ported from `Piru/iOS/PDFReportGenerator.swift` (1,071 lines). This is the
 * **exportable** report — the whole journal over a chosen window, not one session —
 * and it is the one a user hands to a prescriber.
 *
 * ## Why the layout is written out rather than declared
 * There is no document layout engine here and none is being added: `PdfDocument` takes
 * a `Canvas` per page, exactly as the on-screen charts take one, so the report is drawn
 * the way every other drawing in this app is. What upstream gets from
 * `UIGraphicsPDFRenderer` and `NSString.draw(in:)` — pagination and text wrapping — is
 * the two pieces this file adds by hand: [Cursor] for the first and [PdfText] for the
 * second. The alternative was a layout library for one document.
 *
 * ## Letter, not A4
 * Upstream's page is 612×792 points, which is US Letter. Copied rather than
 * "corrected": a clinician printing this has letter paper, the margins are tuned to it,
 * and a silently different page size would move every page break.
 *
 * ## Everything is a snapshot
 * [PdfReportData] carries only strings and numbers, resolved before the renderer starts
 * — the substance a name maps to, its half-life, the drug classes of an interaction. The
 * renderer can then run off the main thread and reach no store, which is what upstream
 * does for the same reason (`nonisolated` in the Swift). It also means the whole layout
 * is testable from a hand-built snapshot, which is where the tests actually are.
 */
internal object PdfReportWriter {

    // MARK: - Layout

    private const val PAGE_WIDTH = 612
    private const val PAGE_HEIGHT = 792
    private const val MARGIN = 50f
    private const val CONTENT_WIDTH = PAGE_WIDTH - MARGIN * 2
    /** Where the page number sits, and the ceiling a page's content may reach. */
    private const val FOOTER_BAND = 30f

    private const val TITLE_SIZE = 22f
    private const val SUBTITLE_SIZE = 11f
    private const val SECTION_SIZE = 14f
    private const val BODY_SIZE = 10f
    private const val CAPTION_SIZE = 8.5f

    private val ACCENT = Color.rgb(237, 87, 135)
    private val TEXT = Color.BLACK
    private val SECONDARY = Color.rgb(85, 85, 85)
    private val LIGHT_GRAY = Color.rgb(235, 235, 235)
    private val ZEBRA = Color.rgb(245, 245, 245)
    private val TABLE_HEADER = Color.rgb(240, 240, 240)
    private val ACCENT_LIGHT = Color.argb(20, 237, 87, 135)
    private val DANGEROUS_RED = Color.rgb(217, 38, 38)
    private val UNSAFE_ORANGE = Color.rgb(230, 140, 26)
    private val INFO_BLUE = Color.rgb(0, 122, 255)
    /** A warm off-white behind the vitals-style callouts, matching the accent. */
    private val BOX_FILL = Color.rgb(248, 246, 247)

    // MARK: - Snapshot

    /** One dose, with the two store lookups the renderer cannot make for itself. */
    internal data class EntrySnapshot(
        val substance: String,
        val amountDisplay: String,
        val route: String,
        val timestamp: Instant,
        val notes: String?,
        /** The catalog's own spelling, when the logged name resolved to one. */
        val displayName: String,
        /** The PSID family key, for the duplicate scan. Null when the name did not resolve. */
        val identityKey: String?,
        /** Elimination half-life, for the PK section. Null when the catalog has none. */
        val halfLifeMinutes: Double?,
    )

    internal data class DailyDoseSnapshot(
        val substance: String,
        val amount: Double,
        val unit: String,
        val route: String,
        val sortOrder: Int,
    )

    internal data class ReportData(
        val entries: List<EntrySnapshot>,
        val dailyDoseItems: List<DailyDoseSnapshot>,
        val findings: List<Finding>,
        val interactions: List<CompressedInteraction>,
        val duplicates: List<DuplicateGroup>,
        val substanceSummary: List<SubstanceStat>,
        val clinical: JournalSummary?,
        val start: Instant,
        val end: Instant,
        val generatedAt: Instant,
        val zone: ZoneId,
        /** The report's copy, resolved by the caller because a resource is not available here. */
        val copy: Copy,
    ) {
        /**
         * The report's own words. Passed in rather than read from resources: this runs
         * off the main thread with no `Context`, and the report ships in two languages.
         */
        data class Copy(
            val title: String,
            val period: String,
            val generated: String,
            val currentMedications: String,
            val substance: String,
            val dose: String,
            val schedule: String,
            val keyFindings: String,
            val interactions: String,
            val interactionFootnote: String,
            val duplicateTitle: String,
            val duplicateBlurb: String,
            val substanceSummary: String,
            val totalDoses: String,
            val medications: String,
            val pkProfiles: String,
            val usageLog: String,
            val notes: String,
            val disclaimer: String,
            val page: String,
            val moreSubstance: String,
            val daysUsed: String,
        )
    }

    internal data class DuplicateGroup(val names: List<String>, val totalEntries: Int)

    internal data class SubstanceStat(
        val displayName: String,
        val totalDoses: Int,
        /** Null when the catalog has no half-life, which is also when there is no chart. */
        val halfLifeMinutes: Double?,
    )

    // MARK: - Page cursor

    /**
     * Where the next thing goes, and which page it goes on.
     *
     * Android's `PdfDocument` page is begun and finished explicitly, so the cursor owns
     * both — including the page number, which cannot be drawn until the page is being
     * finished (it is drawn onto the page that is ending, not the one starting). That
     * is why [newPage] finishes the previous page rather than merely starting the next.
     */
    private class Cursor(private val document: PdfDocument, private val copy: ReportData.Copy) {
        var canvas: Canvas? = null
            private set
        var y: Float = MARGIN
            private set
        private var pageNumber = 0
        private var page: PdfDocument.Page? = null

        fun newPage() {
            finishPage()
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber + 1).create()
            val started = document.startPage(info)
            page = started
            canvas = started.canvas
            pageNumber += 1
            y = MARGIN
        }

        /** Break to a new page when [needed] would not fit above the footer band. */
        fun ensureSpace(needed: Float) {
            if (y + needed > PAGE_HEIGHT - MARGIN - FOOTER_BAND) newPage()
        }

        fun advance(by: Float) {
            y += by
        }

        fun moveTo(value: Float) {
            y = value
        }

        fun finish() {
            finishPage()
        }

        private fun finishPage() {
            val current = page ?: return
            canvas?.let { drawPageNumber(it) }
            document.finishPage(current)
            page = null
            canvas = null
        }

        private fun drawPageNumber(target: Canvas) {
            val paint = paint(CAPTION_SIZE, SECONDARY)
            // The pattern comes from resources — Chinese writes 第 3 页 where English
            // writes "Page 3" — so the number's position in the sentence is the
            // translation's business and not this file's.
            //
            // `Locale.ROOT`, per the port's standing rule: the number is one the app
            // produced, and a device with a locale-sensitive NumberFormat would render
            // its own digits into a document that is otherwise the app's.
            val text = String.format(Locale.ROOT, copy.page, pageNumber)
            val width = PdfText.width(text, paint)
            target.drawText(text, (PAGE_WIDTH - width) / 2f, PAGE_HEIGHT - 22f, paint)
        }
    }

    // MARK: - Entry point

    /**
     * Render [data] to a PDF.
     *
     * @return the document's bytes, ready to be written to a file and shared.
     */
    fun write(data: ReportData): ByteArray {
        val document = PdfDocument()
        val cursor = Cursor(document, data.copy)
        try {
            drawHeader(cursor, data)

            if (data.dailyDoseItems.isNotEmpty()) {
                drawSectionHeader(cursor, data.copy.currentMedications)
                drawMedicationsTable(cursor, data)
            }
            if (data.findings.isNotEmpty()) {
                drawSectionHeader(cursor, data.copy.keyFindings)
                drawKeyFindings(cursor, data)
            }
            if (data.interactions.isNotEmpty() || data.clinical != null) {
                drawSectionHeader(cursor, data.copy.interactions)
                drawInteractions(cursor, data)
            }
            if (data.duplicates.isNotEmpty()) {
                drawSectionHeader(cursor, data.copy.duplicateTitle)
                drawDuplicates(cursor, data)
            }
            if (data.substanceSummary.isNotEmpty()) {
                drawSectionHeader(cursor, data.copy.substanceSummary)
                drawSubstanceSummary(cursor, data)
                drawPkCharts(cursor, data)
            }
            if (data.entries.isNotEmpty()) {
                drawSectionHeader(cursor, data.copy.usageLog)
                drawUsageLog(cursor, data)
            }
            drawFooter(cursor, data)
            cursor.finish()
            return document.toBytes()
        } finally {
            @Suppress("DEPRECATION")
            document.close()
        }
    }

    // MARK: - Header

    private fun drawHeader(cursor: Cursor, data: ReportData) {
        cursor.newPage()
        val canvas = cursor.canvas ?: return

        canvas.drawText(data.copy.title, MARGIN, cursor.y + TITLE_SIZE, paint(TITLE_SIZE, ACCENT, bold = true))
        cursor.advance(TITLE_SIZE + 8f)

        canvas.drawRect(MARGIN, cursor.y, MARGIN + CONTENT_WIDTH, cursor.y + 2f, paint(TITLE_SIZE, ACCENT))
        cursor.advance(16f)

        val subtitlePaint = paint(SUBTITLE_SIZE, SECONDARY)
        val formatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
            .withZone(data.zone)

        canvas.drawText(
            "${data.copy.period}: ${formatter.format(data.start)} — ${formatter.format(data.end)}",
            MARGIN,
            cursor.y + SUBTITLE_SIZE,
            subtitlePaint,
        )
        cursor.advance(16f)
        canvas.drawText(
            "${data.copy.generated}: ${formatter.format(data.generatedAt)}",
            MARGIN,
            cursor.y + SUBTITLE_SIZE,
            subtitlePaint,
        )
        cursor.advance(18f)

        // Quick stats as pills. Each pill is sized to its own text, so the row reads as
        // one line of facts rather than a table with one column.
        val statsPaint = paint(BODY_SIZE, TEXT, bold = true)
        val stats = buildList {
            add("${data.substanceSummary.size} ${data.copy.substance}") 
            add("${data.entries.size} ${data.copy.totalDoses}")
            data.clinical?.let { clinical ->
                add("${(clinical.holidays.fractionUsed * 100).roundToInt()}% ${data.copy.daysUsed}")
                clinical.opioidPeakDayMme?.let { add("${it.roundToInt()} MME/day") }
            }
        }
        var badgeX = MARGIN
        for (stat in stats) {
            val width = PdfText.width(stat, statsPaint)
            canvas.drawRoundRect(
                RectF(badgeX - 6f, cursor.y - 2f, badgeX + width + 6f, cursor.y + BODY_SIZE + 8f),
                8f,
                8f,
                paint(BODY_SIZE, ZEBRA),
            )
            canvas.drawText(stat, badgeX, cursor.y + BODY_SIZE + 2f, statsPaint)
            badgeX += width + 20f
        }
        cursor.advance(BODY_SIZE + 24f)
    }

    // MARK: - Section chrome

    private fun drawSectionHeader(cursor: Cursor, title: String) {
        cursor.ensureSpace(80f)
        cursor.advance(12f)
        val canvas = cursor.canvas ?: return
        val titlePaint = paint(SECTION_SIZE, TEXT, bold = true)

        // The accent bar is what makes a section findable when a reader is skimming a
        // ten-page report for one number.
        canvas.drawRoundRect(
            RectF(MARGIN, cursor.y + 2f, MARGIN + 3f, cursor.y + SECTION_SIZE - 1f),
            1.5f,
            1.5f,
            paint(SECTION_SIZE, ACCENT),
        )
        canvas.drawText(title, MARGIN + 10f, cursor.y + SECTION_SIZE, titlePaint)
        cursor.advance(SECTION_SIZE + 6f)
        canvas.drawRect(MARGIN, cursor.y, MARGIN + CONTENT_WIDTH, cursor.y + 0.5f, paint(BODY_SIZE, LIGHT_GRAY))
        cursor.advance(10f)
    }

    private fun drawRowBackground(cursor: Cursor, rowIndex: Int, height: Float) {
        if (rowIndex % 2 == 0) return
        val canvas = cursor.canvas ?: return
        canvas.drawRoundRect(
            RectF(MARGIN - 4f, cursor.y - 1f, MARGIN + CONTENT_WIDTH + 4f, cursor.y + height - 1f),
            2f,
            2f,
            paint(BODY_SIZE, ZEBRA),
        )
    }

    // MARK: - Medications

    private fun drawMedicationsTable(cursor: Cursor, data: ReportData) {
        val columns = floatArrayOf(CONTENT_WIDTH * 0.50f, CONTENT_WIDTH * 0.25f, CONTENT_WIDTH * 0.25f)
        val header = listOf(data.copy.substance, data.copy.dose, data.copy.schedule)
        val headerPaint = paint(BODY_SIZE, SECONDARY, bold = true)
        val rowPaint = paint(BODY_SIZE, TEXT)

        cursor.ensureSpace(40f)
        val canvas = cursor.canvas ?: return
        canvas.drawRoundRect(
            RectF(MARGIN - 4f, cursor.y - 2f, MARGIN + CONTENT_WIDTH + 4f, cursor.y + 16f),
            2f,
            2f,
            paint(BODY_SIZE, TABLE_HEADER),
        )
        var x = MARGIN
        for ((index, title) in header.withIndex()) {
            canvas.drawText(title, x, cursor.y + 11f, headerPaint)
            x += columns[index]
        }
        cursor.advance(18f)
        canvas.drawRect(MARGIN, cursor.y, MARGIN + CONTENT_WIDTH, cursor.y + 0.5f, paint(BODY_SIZE, LIGHT_GRAY))
        cursor.advance(6f)

        for (item in data.dailyDoseItems.sortedBy { it.sortOrder }) {
            cursor.ensureSpace(18f)
            val row = cursor.canvas ?: return
            row.drawText(item.substance, MARGIN, cursor.y + 11f, rowPaint)
            row.drawText(
                "${formatAmount(item.amount)} ${item.unit}",
                MARGIN + columns[0],
                cursor.y + 11f,
                rowPaint,
            )
            row.drawText(item.route, MARGIN + columns[0] + columns[1], cursor.y + 11f, rowPaint)
            cursor.advance(18f)
        }
    }

    // MARK: - Key findings

    /**
     * The findings, one per line, with a coloured mark by severity.
     *
     * A warning carries a filled square rather than an emoji or a symbol: the PDF is
     * rendered with the platform's default face, and a glyph the face lacks prints as a
     * blank box — which is exactly the wrong failure for the section a prescriber reads
     * first.
     */
    private fun drawKeyFindings(cursor: Cursor, data: ReportData) {
        val textPaint = paint(BODY_SIZE, TEXT)
        for (finding in data.findings) {
            val width = CONTENT_WIDTH - 18f
            val height = PdfText.height(finding.summary, textPaint, width)
            cursor.ensureSpace(height + 8f)
            val canvas = cursor.canvas ?: return
            val colour = when (finding.severity) {
                Finding.Severity.WARNING -> UNSAFE_ORANGE
                Finding.Severity.INFO -> INFO_BLUE
            }
            canvas.drawRect(MARGIN, cursor.y + 1f, MARGIN + 6f, cursor.y + 7f, paint(BODY_SIZE, colour))
            PdfText.draw(canvas, finding.summary, MARGIN + 18f, cursor.y, textPaint, width)
            cursor.advance(height + 6f)
        }
        cursor.advance(4f)
    }

    // MARK: - Interactions

    private fun drawInteractions(cursor: Cursor, data: ReportData) {
        val significant = data.interactions.filter {
            it.severity == InteractionSeverity.DANGEROUS || it.severity == InteractionSeverity.UNSAFE
        }
        val cautionCount = data.interactions.count { it.severity == InteractionSeverity.CAUTION }
        val textPaint = paint(BODY_SIZE, TEXT, bold = true)
        val detailPaint = paint(CAPTION_SIZE, SECONDARY)

        // Upstream caps this list at eight: a report is a summary, and a table of every
        // class pair is the screen's job rather than the page's.
        val cap = 8
        for ((index, interaction) in significant.take(cap).withIndex()) {
            cursor.ensureSpace(30f)
            drawRowBackground(cursor, index, 28f)
            val canvas = cursor.canvas ?: return
            val colour = if (interaction.severity == InteractionSeverity.DANGEROUS) DANGEROUS_RED else UNSAFE_ORANGE
            canvas.drawRect(MARGIN, cursor.y + 2f, MARGIN + 6f, cursor.y + 8f, paint(BODY_SIZE, colour))
            canvas.drawText(
                "${interaction.classA.label} + ${interaction.classB.label}",
                MARGIN + 22f,
                cursor.y + 11f,
                textPaint,
            )
            cursor.advance(14f)
            val detail = "(${interaction.substancesA.joinToString(", ")}, " +
                "${interaction.substancesB.joinToString(", ")})"
            val detailHeight = PdfText.height(detail, detailPaint, CONTENT_WIDTH - 22f)
            PdfText.draw(cursor.canvas ?: return, detail, MARGIN + 22f, cursor.y, detailPaint, CONTENT_WIDTH - 22f)
            cursor.advance(detailHeight + 6f)
        }

        val canvas = cursor.canvas ?: return
        if (significant.size > cap) {
            cursor.ensureSpace(16f)
            canvas.drawText(
                data.copy.moreSubstance.format(significant.size - cap),
                MARGIN,
                (cursor.canvas ?: return).let { cursor.y + 10f },
                detailPaint,
            )
            cursor.advance(16f)
        }
        if (cautionCount > 0) {
            cursor.ensureSpace(16f)
            (cursor.canvas ?: return).drawText(
                "$cautionCount ${data.copy.interactions}",
                MARGIN,
                cursor.y + 10f,
                detailPaint,
            )
            cursor.advance(16f)
        }
        cursor.ensureSpace(16f)
        (cursor.canvas ?: return).drawText(data.copy.interactionFootnote, MARGIN, cursor.y + 10f, detailPaint)
        cursor.advance(24f)
    }

    // MARK: - Duplicates

    private fun drawDuplicates(cursor: Cursor, data: ReportData) {
        val textPaint = paint(BODY_SIZE, TEXT)
        val blurbPaint = paint(CAPTION_SIZE, SECONDARY)
        cursor.ensureSpace(PdfText.height(data.copy.duplicateBlurb, blurbPaint, CONTENT_WIDTH) + 8f)
        PdfText.draw(cursor.canvas ?: return, data.copy.duplicateBlurb, MARGIN, cursor.y, blurbPaint, CONTENT_WIDTH)
        cursor.advance(PdfText.height(data.copy.duplicateBlurb, blurbPaint, CONTENT_WIDTH) + 6f)

        for (group in data.duplicates) {
            cursor.ensureSpace(16f)
            (cursor.canvas ?: return).drawText(
                "• ${group.names.joinToString(" / ")} — ${group.totalEntries}",
                MARGIN,
                cursor.y + 10f,
                textPaint,
            )
            cursor.advance(16f)
        }
        cursor.advance(6f)
    }

    // MARK: - Substance summary

    private fun drawSubstanceSummary(cursor: Cursor, data: ReportData) {
        val textPaint = paint(BODY_SIZE, TEXT)
        val secondary = paint(CAPTION_SIZE, SECONDARY)
        for ((index, stat) in data.substanceSummary.withIndex()) {
            cursor.ensureSpace(28f)
            drawRowBackground(cursor, index, 26f)
            val canvas = cursor.canvas ?: return
            canvas.drawText(stat.displayName, MARGIN, cursor.y + 11f, textPaint)
            val right = "${stat.totalDoses} ${data.copy.totalDoses}"
            val width = PdfText.width(right, secondary)
            canvas.drawText(right, MARGIN + CONTENT_WIDTH - width, cursor.y + 11f, secondary)
            cursor.advance(14f)
            stat.halfLifeMinutes?.let { halfLife ->
                canvas.drawText(
                    formatHalfLifeLabel(halfLife),
                    MARGIN,
                    cursor.y + 9f,
                    secondary,
                )
            }
            cursor.advance(14f)
        }
        cursor.advance(8f)
    }

    // MARK: - PK charts

    /**
     * One concentration curve per substance that has a half-life.
     *
     * ## What the curve is, and why it is drawn from the model rather than from the log
     * It is a **single dose's** one-compartment Bateman curve, normalised to its own Cmax,
     * running out to the point where 5% remains. That is what a clinician reads a
     * half-life as, and it is deliberately *not* the user's dose history: this section
     * says "here is how fast this substance leaves", and a chart of actual doses would
     * answer a different question while looking similar.
     *
     * ## Why the axis picks its own interval
     * Caffeine's 5% point is a few hours out and amiodarone's is months. A fixed grid
     * would either crowd the short curve or leave the long one unlabelled, so the
     * interval is chosen from the span in [bestHourInterval] — the same ladder upstream
     * uses.
     */
    private fun drawPkCharts(cursor: Cursor, data: ReportData) {
        val charts = data.substanceSummary
            .mapNotNull { stat -> stat.halfLifeMinutes?.takeIf { it > 0 }?.let { stat to it } }
            // The five busiest, matching upstream: the pages after this one are the log.
            .take(5)
        if (charts.isEmpty()) return

        drawSectionHeader(cursor, data.copy.pkProfiles)

        val chartWidth = CONTENT_WIDTH
        val chartHeight = 80f
        for ((stat, halfLifeMinutes) in charts) {
            cursor.ensureSpace(chartHeight + 34f)
            val canvas = cursor.canvas ?: return

            canvas.drawText(stat.displayName, MARGIN, cursor.y + BODY_SIZE + 2f, paint(BODY_SIZE, TEXT, bold = true))
            val halfLifeLabel = formatHalfLifeLabel(halfLifeMinutes)
            val captionPaint = paint(CAPTION_SIZE, SECONDARY)
            canvas.drawText(
                halfLifeLabel,
                MARGIN + chartWidth - PdfText.width(halfLifeLabel, captionPaint),
                cursor.y + CAPTION_SIZE + 2f,
                captionPaint,
            )
            cursor.advance(16f)

            val originY = cursor.y
            val ke = PKModel.keFromHalfLifeMinutes(halfLifeMinutes)
            val ka = PKModel.defaultKa(ke)
            val cmax = PKModel.cmax(ke, ka)
            val totalMinutes = if (cmax > 0) PKModel.timeToFraction(0.05, ke, ka) else 0.0
            if (cmax <= 0 || totalMinutes <= 0) {
                cursor.advance(chartHeight + 10f)
                continue
            }

            canvas.drawRoundRect(
                RectF(MARGIN, originY, MARGIN + chartWidth, originY + chartHeight),
                3f,
                3f,
                paint(BODY_SIZE, ZEBRA),
            )
            for (fraction in listOf(0.25, 0.5, 0.75, 1.0)) {
                val gy = originY + chartHeight - (fraction.toFloat() * chartHeight)
                canvas.drawRect(MARGIN, gy, MARGIN + chartWidth, gy + 0.5f, paint(BODY_SIZE, LIGHT_GRAY))
            }
            canvas.drawText("100%", MARGIN + 2f, originY + CAPTION_SIZE, captionPaint)
            canvas.drawText(
                "50%",
                MARGIN + 2f,
                originY + chartHeight - 0.5f * chartHeight + CAPTION_SIZE - 2f,
                captionPaint,
            )

            val interval = bestHourInterval(totalMinutes)
            var gridMinutes = interval
            while (gridMinutes < totalMinutes) {
                val gx = MARGIN + (gridMinutes / totalMinutes).toFloat() * chartWidth
                canvas.drawRect(gx, originY, gx + 0.5f, originY + chartHeight, paint(BODY_SIZE, LIGHT_GRAY))
                val label = formatTimeLabel(gridMinutes)
                canvas.drawText(
                    label,
                    gx - PdfText.width(label, captionPaint) / 2f,
                    originY + chartHeight + CAPTION_SIZE + 1f,
                    captionPaint,
                )
                gridMinutes += interval
            }

            val steps = 200
            val curve = Path()
            val fill = Path()
            fill.moveTo(MARGIN, originY + chartHeight)
            for (index in 0..steps) {
                val minutes = totalMinutes * index / steps
                val concentration = PKModel.concentration(minutes, ke, ka) / cmax
                val px = MARGIN + (minutes / totalMinutes).toFloat() * chartWidth
                val py = originY + chartHeight - concentration.toFloat() * chartHeight
                if (index == 0) curve.moveTo(px, py) else curve.lineTo(px, py)
                fill.lineTo(px, py)
            }
            fill.lineTo(MARGIN + chartWidth, originY + chartHeight)
            fill.close()
            canvas.drawPath(fill, paint(BODY_SIZE, ACCENT_LIGHT))
            canvas.drawPath(curve, paint(BODY_SIZE, ACCENT))

            cursor.advance(chartHeight + 22f)
        }
    }

    /**
     * A grid interval that keeps the labels legible at this span.
     *
     * Upstream's ladder, copied: an hour up to six, then two, six, a day, two days.
     */
    private fun bestHourInterval(totalMinutes: Double): Double {
        val totalHours = totalMinutes / 60
        return when {
            totalHours <= 6 -> 60.0
            totalHours <= 24 -> 120.0
            totalHours <= 48 -> 360.0
            totalHours <= 168 -> 1_440.0
            else -> 2_880.0
        }
    }

    // MARK: - Usage log

    private fun drawUsageLog(cursor: Cursor, data: ReportData) {
        val bodyPaint = paint(BODY_SIZE, TEXT)
        val captionPaint = paint(CAPTION_SIZE, SECONDARY)
        val formatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault()).withZone(data.zone)

        for (entry in data.entries.sortedBy { it.timestamp }) {
            cursor.ensureSpace(20f)
            val canvas = cursor.canvas ?: return
            canvas.drawText(entry.substance, MARGIN, cursor.y + 11f, paint(BODY_SIZE, TEXT, bold = true))
            val substanceWidth = PdfText.width(entry.substance, paint(BODY_SIZE, TEXT, bold = true))
            canvas.drawText(
                "${entry.amountDisplay} · ${entry.route}",
                MARGIN + substanceWidth + 8f,
                cursor.y + 11f,
                bodyPaint,
            )
            val stamp = formatter.format(entry.timestamp)
            canvas.drawText(
                stamp,
                MARGIN + CONTENT_WIDTH - PdfText.width(stamp, captionPaint),
                cursor.y + 11f,
                captionPaint,
            )
            cursor.advance(15f)
            entry.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                val height = PdfText.height(notes, captionPaint, CONTENT_WIDTH - 10f)
                cursor.ensureSpace(height + 4f)
                PdfText.draw(cursor.canvas ?: return, notes, MARGIN + 10f, cursor.y, captionPaint, CONTENT_WIDTH - 10f)
                cursor.advance(height + 3f)
            }
            cursor.advance(2f)
        }
        cursor.advance(4f)
    }

    // MARK: - Footer

    private fun drawFooter(cursor: Cursor, data: ReportData) {
        val paint = paint(CAPTION_SIZE, SECONDARY)
        val height = PdfText.height(data.copy.disclaimer, paint, CONTENT_WIDTH - 16f)
        cursor.ensureSpace(height + 36f)
        cursor.advance(16f)

        val boxTop = cursor.y
        (cursor.canvas ?: return).drawRoundRect(
            RectF(MARGIN - 4f, boxTop, MARGIN + CONTENT_WIDTH + 4f, boxTop + height + 16f),
            4f,
            4f,
            paint(BODY_SIZE, BOX_FILL),
        )
        (cursor.canvas ?: return).drawRoundRect(
            RectF(MARGIN - 4f, boxTop, MARGIN + CONTENT_WIDTH + 4f, boxTop + height + 16f),
            4f,
            4f,
            paint(BODY_SIZE, LIGHT_GRAY, stroke = true),
        )
        cursor.advance(8f)
        PdfText.draw(cursor.canvas ?: return, data.copy.disclaimer, MARGIN + 4f, cursor.y, paint, CONTENT_WIDTH - 16f)
        cursor.advance(height + 12f)
    }

    // MARK: - Helpers

    /**
     * A `Paint`, built per call.
     *
     * Per call rather than cached because a `Paint` is mutable and the report draws from
     * one thread with a handful of styles; a shared instance that some later line
     * recoloured would be a bug that only shows on the page after it.
     */
    private fun paint(size: Float, colour: Int, bold: Boolean = false, stroke: Boolean = false): Paint =
        Paint().apply {
            isAntiAlias = true
            color = colour
            textSize = size
            typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            if (stroke) {
                style = Paint.Style.STROKE
                strokeWidth = 0.5f
            }
        }

    private fun formatAmount(value: Double): String =
        if (value == value.roundToInt().toDouble()) value.roundToInt().toString()
        else String.format(Locale.ROOT, "%.1f", value)

    private fun formatTimeLabel(minutes: Double): String {
        val hours = minutes / 60
        if (hours < 24) {
            return if (hours % 1.0 == 0.0) "${hours.roundToInt()}h" else String.format(Locale.ROOT, "%.1fh", hours)
        }
        val days = hours / 24
        return if (days % 1.0 == 0.0) "${days.roundToInt()}d" else String.format(Locale.ROOT, "%.1fd", days)
    }

    /** `t½ = …`, the way a half-life is written on a chart. */
    private fun formatHalfLifeLabel(minutes: Double): String {
        val half = "t\u00BD"
        if (minutes < 60) return "$half = ${minutes.roundToInt()} min"
        val hours = minutes / 60
        if (hours < 24) {
            return if (hours % 1.0 == 0.0) "$half = ${hours.roundToInt()}h" else String.format(Locale.ROOT, "$half = %.1fh", hours)
        }
        val days = hours / 24
        return if (days % 1.0 == 0.0) "$half = ${days.roundToInt()}d" else String.format(Locale.ROOT, "$half = %.1fd", days)
    }
}

/** `PdfDocument.toBytes()` is not in the platform API; the report needs the document as bytes. */
private fun PdfDocument.toBytes(): ByteArray {
    val stream = ByteArrayOutputStream()
    // A temp-free write: `writeTo` is the documented way out, and for a report this size
    // (tens of KB) an in-memory buffer is the right destination rather than a file the
    // caller would have to remember to delete.
    writeTo(stream)
    return stream.toByteArray()
}
