package glass.kagerou.piru.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant

/**
 * The record-and-model view: days used against days off, cumulative exposure in
 * clinical equivalents where they exist, whether a dose has crept up, and where
 * two substances were active at once.
 *
 * Ported from `Views/Insights/PatternsView.swift` (363 lines), reading the value
 * `SummaryStatsPort.kt` computes — the same `JournalSummary` the PDF clinician
 * report renders upstream, so the screen and the print-out cannot disagree.
 *
 * ## The default range is 90 days, not the Usage screen's 30
 * A pattern needs more than a month of days to be a pattern; upstream defaults
 * this page to ninety days for that reason and this one matches.
 *
 * ## What is deliberately absent from the opioid card
 * No caution band, no traffic light, no red/amber/green. CDC 2022 removed the
 * 90 MME/day threshold its 2016 guideline carried and reframed 50 as a point to
 * "pause and carefully reassess", stating its dosage recommendations "are not
 * intended to be used as an inflexible, rigid standard of care". Sorting a day
 * into a colour band would assert the rigidity the source withdrew, and hand the
 * reader a verdict where the number was the point.
 */
@Composable
fun PatternsScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication

    // Ninety days by default, per upstream; the same enum the Usage screen uses.
    var range by rememberSaveable { mutableStateOf(UsageTimeRange.NINETY_DAYS) }
    var report by remember { mutableStateOf<JournalSummary?>(null) }
    var entryCount by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    val calendar = remember { InsightsCalendar.ambient(null) }

    LaunchedEffect(navigator.dataVersion, range) {
        loaded = false
        failure = null
        runCatching {
            val entries = app.database.doseEntryDao().all()
            entryCount = entries.size
            val catalog = app.catalog()
            val now = Instant.now()
            val start = range.days?.let { now.minusSeconds(it * 86_400L) }
                ?: entries.minOfOrNull { it.timestamp.time }?.let { Instant.ofEpochMilli(it) }
                ?: now.minusSeconds(90 * 86_400L)

            // Tint keys are the canonical names the resolver files substances
            // under, plus the logged spellings so an unresolved dose still gets
            // its own colour rather than the neutral stand-in.
            val names = entries.map { it.substance } +
                entries.mapNotNull { catalog.lookup(it.substance)?.name }
            val tints = app.palette().tintsFor(names.distinct())

            val resolved = SummaryStatsResolver.resolve(entries, catalog, catalog, tints, now)
            report = SummaryStats.report(resolved.first, resolved.second, start, now, calendar)
        }.onFailure { failure = it::class.simpleName + ": " + it.message }
        loaded = true
    }

    val accent = PiruTheme.colors.accent

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Patterns", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "What the log adds up to over a window: days used, cumulative " +
                        "exposure, whether a dose has moved, and what was active together.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(UsageTimeRange.entries.size) { index ->
                    val option = UsageTimeRange.entries[index]
                    InsightsFilterPill(
                        label = option.displayName,
                        color = accent,
                        isSelected = option == range,
                        showDot = false,
                        onClick = { range = option },
                    )
                }
            }
        }

        failure?.let { message ->
            item {
                InsightsEmptyPanel("The log could not be read", message)
            }
        }

        if (failure == null && loaded && entryCount == 0) {
            item {
                InsightsEmptyPanel(
                    "No logged entries",
                    "Add entries to see your patterns. Every card here is a reading of " +
                        "what you have already written down.",
                )
            }
        }

        val shown = report
        if (failure == null && loaded && entryCount > 0 && (shown == null || shown.isEmpty)) {
            item { InsightsEmptyPanel("Nothing to summarize", "Nothing logged in this range.") }
        }

        if (shown != null && !shown.isEmpty) {
            item { HolidayCard(shown.holidays) }
            if (shown.exposure.isNotEmpty()) item { ExposureCard(shown) }
            if (shown.escalation.isNotEmpty()) item { EscalationCard(shown) }
            if (shown.overlaps.isNotEmpty()) item { OverlapCard(shown) }
            item {
                Text(
                    "A record and a model, not medical advice. Exposure uses published " +
                        "equivalents where they exist, and the substance's typical dose otherwise.",
                    style = MaterialTheme.typography.labelSmall,
                    color = PiruTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

// MARK: - Days used

/**
 * Days used against days off.
 *
 * Framed as a record — days used, longest break — and never as failed
 * abstinence, which is why the tiles say "of 90 days" rather than naming a
 * target.
 */
@Composable
private fun HolidayCard(holidays: HolidayStats) {
    InsightsSectionCard(title = "Days used") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(holidays.daysUsed.toString(), "of ${holidays.totalDays} days", Modifier.weight(1f))
            StatTile(
                InsightsFormat.percent(holidays.fractionUsed),
                "of days",
                Modifier.weight(1f),
            )
            StatTile("${holidays.longestBreakDays}d", "longest break", Modifier.weight(1f))
            if (holidays.currentBreakDays > 0) {
                StatTile("${holidays.currentBreakDays}d", "since last", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = PiruTheme.colors.secondaryLabel)
    }
}

// MARK: - Cumulative exposure

@Composable
private fun ExposureCard(report: JournalSummary) {
    InsightsSectionCard(
        title = "Cumulative exposure",
        subtitle = "Total taken this range, in each substance's common-dose unit",
    ) {
        if (report.opioidPeakDayMme != null || report.benzoDiazepamPerDay != null) {
            SummaryCallout(report)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (stat in report.exposure) {
                ExposureRow(stat, report.substances[stat.substanceIndex])
            }
        }
    }
}

@Composable
private fun SummaryCallout(report: JournalSummary) {
    val info = PiruTheme.colors.info
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        report.opioidPeakDayMme?.let { peak ->
            MmeBand(peak, report.opioidMmePerDay ?: 0.0)
        }
        report.benzoDiazepamPerDay?.let { equivalent ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(info.copy(alpha = 0.15f))
                    .padding(12.dp),
            ) {
                Text(
                    "Benzodiazepines ≈ ${InsightsFormat.oneDecimal(equivalent)} mg diazepam-eq/day",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * The opioid total in morphine-milligram equivalents — the reader's own numbers,
 * converted.
 *
 * See the screen's own note on why there is no band and no colour here.
 */
@Composable
private fun MmeBand(peakDayMme: Double, dailyMean: Double) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Opioids: peak day ≈ ${InsightsFormat.whole(peakDayMme)} MME",
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            "Average ${InsightsFormat.oneDecimal(dailyMean)} MME/day over the range",
            style = MaterialTheme.typography.labelSmall,
            color = PiruTheme.colors.secondaryLabel,
        )
    }
}

@Composable
private fun ExposureRow(stat: ExposureStat, substance: SummarySubstance) {
    val tint = Color(substance.tint.red.toFloat(), substance.tint.green.toFloat(), substance.tint.blue.toFloat(), 1f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InsightsLegendDot(tint, size = 11.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(substance.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${InsightsFormat.exposure(stat.total)} ${substance.unit}",
                style = MaterialTheme.typography.labelSmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        CumulativeSparkline(stat.cumulative, tint)
    }
}

/** The running total over the window: area under a line, no axes. */
@Composable
private fun CumulativeSparkline(points: List<ExposureStat.CumulativePoint>, tint: Color) {
    if (points.size < 2) return
    Box(modifier = Modifier.width(90.dp).height(30.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val peak = points.maxOf { it.total }.coerceAtLeast(0.0001)
            val firstMillis = points.first().date.toEpochMilli()
            val spanMillis = (points.last().date.toEpochMilli() - firstMillis).coerceAtLeast(1L).toDouble()
            val line = Path()
            val area = Path()
            for ((index, point) in points.withIndex()) {
                val x = (size.width * ((point.date.toEpochMilli() - firstMillis) / spanMillis)).toFloat()
                val y = size.height * (1f - (point.total / peak).toFloat())
                if (index == 0) {
                    line.moveTo(x, y)
                    area.moveTo(x, y)
                } else {
                    line.lineTo(x, y)
                    area.lineTo(x, y)
                }
            }
            area.lineTo(size.width, size.height)
            area.lineTo(0f, size.height)
            area.close()
            drawPath(area, color = tint.copy(alpha = 0.2f))
            drawPath(line, color = tint, style = Stroke(width = 2f))
        }
    }
}

// MARK: - Dose trend

/**
 * The first-third median against the last-third median, per substance.
 *
 * A dose that has crept *down* is reported as plainly as one that crept up:
 * this is a reading of the record, and the copy says "rising" or "falling"
 * rather than anything about what either means.
 */
@Composable
private fun EscalationCard(report: JournalSummary) {
    InsightsSectionCard(title = "Dose trend") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (stat in report.escalation) {
                EscalationRow(stat, report.substances[stat.substanceIndex])
            }
        }
    }
}

@Composable
private fun EscalationRow(stat: EscalationStat, substance: SummarySubstance) {
    val tint = Color(substance.tint.red.toFloat(), substance.tint.green.toFloat(), substance.tint.blue.toFloat(), 1f)
    val (glyph, colour) = when (stat.direction) {
        EscalationDirection.RISING -> "↑" to doseLevelAccent(5)
        EscalationDirection.FALLING -> "↓" to doseLevelAccent(2)
        EscalationDirection.STEADY -> "→" to PiruTheme.colors.secondaryLabel
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InsightsLegendDot(tint, size = 11.dp)
        Text(
            substance.displayName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${InsightsFormat.exposure(stat.earlyMedian)} → ${InsightsFormat.exposure(stat.lateMedian)}",
            style = MaterialTheme.typography.labelSmall,
            color = PiruTheme.colors.secondaryLabel,
        )
        Text(
            if (stat.direction == EscalationDirection.STEADY) "steady" else InsightsFormat.signedPercent(stat.change),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = colour,
        )
        Text(glyph, style = MaterialTheme.typography.labelMedium, color = colour)
    }
}

// MARK: - Active together

/**
 * Hours two substances were both above the body-load floor.
 *
 * Sampled hourly by the aggregation, so "≈ 6 h" means six hours of real overlap
 * rather than two timestamps that happen to be close.
 */
@Composable
private fun OverlapCard(report: JournalSummary) {
    InsightsSectionCard(title = "Active together") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Upstream caps this at six rows: past that a list stops being a
            // reading and starts being a dump.
            for (overlap in report.overlaps.take(6)) {
                val a = report.substances[overlap.a]
                val b = report.substances[overlap.b]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    InsightsLegendDot(
                        Color(a.tint.red.toFloat(), a.tint.green.toFloat(), a.tint.blue.toFloat(), 1f),
                        size = 9.dp,
                    )
                    InsightsLegendDot(
                        Color(b.tint.red.toFloat(), b.tint.green.toFloat(), b.tint.blue.toFloat(), 1f),
                        size = 9.dp,
                    )
                    Text(
                        "${a.displayName} · ${b.displayName}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        hoursText(overlap.hours),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }
    }
}

/** Whole days past 24 hours, whole hours below it — "2.1 days" reads better than "50 h". */
private fun hoursText(hours: Double): String =
    if (hours >= 24) "${InsightsFormat.oneDecimal(hours / 24)} days" else "${Math.round(hours)} h"
