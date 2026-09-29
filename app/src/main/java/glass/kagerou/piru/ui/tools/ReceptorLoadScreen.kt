package glass.kagerou.piru.ui.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.components.drawScrubRule
import glass.kagerou.piru.ui.insights.InsightsEmptyPanel
import glass.kagerou.piru.ui.insights.InsightsFilterPill
import glass.kagerou.piru.ui.insights.InsightsLegendDot
import glass.kagerou.piru.ui.insights.InsightsMiddot
import glass.kagerou.piru.ui.insights.InsightsSectionCard
import glass.kagerou.piru.ui.insights.UsageTimeRange
import glass.kagerou.piru.ui.insights.rememberViewportState
import glass.kagerou.piru.ui.labels.CoreLabels
import glass.kagerou.piru.ui.labels.appLocale
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Receptor load over time — the historic counterpart to the Tolerance tool.
 *
 * Ported from `Views/Insights/ReceptorLoadView.swift` (358 lines), reading the
 * `loadTrail` path taken from `ToleranceStore.swift` into `engine/LoadTrail`.
 *
 * ## What the number means, and what it does not
 * Every point is a class's combined drive over the window, **normalised to the
 * user's own peak over the last three weeks** — not an absolute occupancy. That
 * normalisation is the whole reason the curve is readable: an absolute occupancy
 * for a tight-`Kᵢ` target pins near 1 for many half-lives after the last dose and
 * never comes back down, which makes it useless as a "how much is still loading
 * this receptor" reading. So the y-axis says "intensity relative to your recent
 * baseline", a drug holiday reads as a trough, and sustained heavy use reads as a
 * high plateau.
 *
 * ## Which classes are drawn, and why not simply the biggest
 * The classes come from the replay's own state, ordered by how toleranced they
 * are, and the first six are taken. A class whose peak over the window is at or
 * below 0.02 is dropped — a line pinned to the floor is not a finding, it is
 * noise with a legend entry. Survivors are then sorted by peak, so the tallest
 * curve is the first one drawn and the legend leads with it.
 *
 * ## Tap selects, drag pans
 * Upstream's chart scrolls and selects together. Here a tap places the scrub
 * readout at the nearest sample and a horizontal drag pans the window — one
 * gesture, one job, because Compose does not arbitrate the two the way a UIKit
 * scroll view does.
 */
@Composable
fun ReceptorLoadScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication

    var range by rememberSaveable { mutableStateOf(UsageTimeRange.NINETY_DAYS) }
    var zoom by rememberSaveable { mutableStateOf(ZoomLevel.MEDIUM) }
    var series by remember { mutableStateOf<List<ReceptorLoadSeries>>(emptyList()) }
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    var selectedDate by remember { mutableStateOf<Instant?>(null) }
    var entryCount by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(navigator.dataVersion, range) {
        loaded = false
        failure = null
        // Off the main thread: the trail is a whole-log replay plus up to six
        // integrations over a year of history each, and `LaunchedEffect` continues on
        // the composition's dispatcher — which is the main one. The suspension is
        // also what lets the frame that says "replaying" actually reach the screen
        // before the work blocks whatever thread it is on.
        runCatching {
            val entries = withContext(Dispatchers.Default) { app.database.doseEntryDao().all() }
            entryCount = entries.size
            series = withContext(Dispatchers.Default) { buildReceptorLoadSeries(app, entries, range) }
        }.onFailure { failure = it::class.simpleName + ": " + it.message }
        loaded = true
    }

    LaunchedEffect(range) {
        selectedDate = null
        hidden = emptySet()
    }

    val accent = PiruTheme.colors.accent

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.toolsb_receptor_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.toolsb_receptor_subtitle),
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
                        // The localized spelling; `displayName` is the English one
                        // and is kept for the screens that have not moved yet.
                        label = stringResource(option.displayNameRes),
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
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.toolsb_receptor_failure_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(message, style = MaterialTheme.typography.bodySmall, color = PiruTheme.colors.dangerText)
                    }
                }
            }
        }

        if (failure == null && !loaded) {
            item {
                Text(
                    stringResource(R.string.toolsb_receptor_replaying),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        if (failure == null && loaded && entryCount == 0) {
            item {
                InsightsEmptyPanel(
                    stringResource(R.string.toolsb_no_logged_entries),
                    stringResource(R.string.toolsb_receptor_empty_no_entries),
                )
            }
        }

        if (failure == null && loaded && entryCount > 0 && series.isEmpty()) {
            item {
                InsightsEmptyPanel(
                    stringResource(R.string.toolsb_receptor_empty_nothing_title),
                    stringResource(R.string.toolsb_receptor_empty_nothing_detail),
                )
            }
        }

        if (series.isNotEmpty()) {
            item {
                val visible = series.filterNot { hidden.contains(it.id) }
                // Hiding every line would leave an empty plot with no way back,
                // so the legend can never zero the chart out.
                val shown = visible.ifEmpty { series }

                InsightsSectionCard(
                    title = stringResource(R.string.toolsb_receptor_chart_title),
                    subtitle = stringResource(R.string.toolsb_receptor_chart_subtitle),
                ) {
                    ReceptorLoadChart(shown, selectedDate, zoom.windowSeconds) { selectedDate = it }
                    selectedDate?.let { ReceptorLoadReadout(shown, it) }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (level in ZoomLevel.entries) {
                            InsightsFilterPill(
                                label = stringResource(level.labelRes),
                                color = accent,
                                isSelected = level == zoom,
                                showDot = false,
                                onClick = { zoom = level },
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.toolsb_receptor_zoom_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                    ReceptorLoadLegend(series, hidden) { id ->
                        hidden = if (hidden.contains(id)) hidden - id else hidden + id
                    }
                }
            }

            item {
                Text(
                    stringResource(R.string.toolsb_receptor_disclaimer),
                    style = MaterialTheme.typography.labelSmall,
                    color = PiruTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

/** How much of the trail is on screen at once, and the label for its pill. */
private enum class ZoomLevel(val labelRes: Int, val windowSeconds: Double) {
    WIDE(R.string.toolsb_receptor_zoom_wide, 180 * 86_400.0),
    MEDIUM(R.string.toolsb_receptor_zoom_medium, 90 * 86_400.0),
    CLOSE(R.string.toolsb_receptor_zoom_close, 30 * 86_400.0),
}

// MARK: - Chart

@Composable
private fun ReceptorLoadChart(
    series: List<ReceptorLoadSeries>,
    selectedDate: Instant?,
    windowSeconds: Double,
    onSelect: (Instant?) -> Unit,
) {
    val gridInk = PiruTheme.colors.secondaryLabel.copy(alpha = 0.25f)
    val labelInk = PiruTheme.colors.secondaryLabel
    // The cursor is the theme accent — the same rule the Tolerance tool's own load
    // chart draws and the journal's time cursor uses. One chart, one cursor.
    val accent = PiruTheme.colors.accent
    val measurer = rememberTextMeasurer()
    val axisDayPattern = stringResource(R.string.datefmt_day_month)
    val dateLocale = appLocale()

    val first = series.minOfOrNull { it.points.first().date }
    val last = series.maxOfOrNull { it.points.last().date }
    val spanMillis = if (first != null && last != null) (last.toEpochMilli() - first.toEpochMilli()).toDouble() else 1.0
    val viewport = rememberViewportState(spanMillis, windowSeconds * 1000)

    val visibleFrom = first?.let {
        Instant.ofEpochMilli(it.toEpochMilli() + (spanMillis * viewport.offsetFraction).toLong())
    }
    val visibleTo = visibleFrom?.let { Instant.ofEpochMilli(it.toEpochMilli() + viewport.visibleMillis.toLong()) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            val widthPx = constraints.maxWidth.toFloat()
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(viewport.scrollable(), series.size) {
                        // Horizontal-only, which is what this gesture has always meant:
                        // it pans by `dragAmount.x` and nothing else. The all-direction
                        // detector claimed vertical drags too and then panned by their
                        // (near-zero) x — so a swipe that began on the plot scrolled
                        // neither the page nor the window, which is a dead zone on a
                        // band this wide. See `TimeScrub.kt` for what this detector
                        // does and does not claim.
                        detectHorizontalDragGestures { change, dragAmount ->
                            change.consume()
                            // `dragAmount` is the horizontal delta itself, not an Offset.
                            viewport.pan(dragAmount, widthPx)
                        }
                    }
                    .pointerInput(visibleFrom, visibleTo, series.size) {
                        detectTapGestures { offset ->
                            val from = visibleFrom ?: return@detectTapGestures
                            val to = visibleTo ?: return@detectTapGestures
                            val at = nearestInstant(series, from, to, offset.x / widthPx)
                            onSelect(if (selectedDate == at) null else at)
                        }
                    },
            ) {
                val from = visibleFrom ?: return@Canvas
                val to = visibleTo ?: return@Canvas
                val spanVisible = maxOf(1L, to.toEpochMilli() - from.toEpochMilli()).toDouble()

                fun xFor(date: Instant): Float =
                    (((date.toEpochMilli() - from.toEpochMilli()) / spanVisible) * size.width).toFloat()

                // The y-axis is fixed 0…1 by definition: every point is already a
                // fraction of the user's own recent peak.
                for (fraction in listOf(0.0, 0.5, 1.0)) {
                    val y = size.height * (1f - fraction.toFloat())
                    drawLine(gridInk, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    drawText(
                        measurer.measure(
                            "${(fraction * 100).toInt()}%",
                            TextStyle(fontSize = 10.sp, color = labelInk),
                        ),
                        topLeft = Offset(2f, (y - 12f).coerceAtLeast(0f)),
                    )
                }

                for (item in series) {
                    val points = item.points.filter { it.date >= from && it.date <= to }
                    if (points.isEmpty()) continue
                    val path = Path()
                    var started = false
                    for (point in points) {
                        val x = xFor(point.date)
                        val y = size.height * (1f - point.load.toFloat().coerceIn(0f, 1f))
                        if (started) path.lineTo(x, y) else path.moveTo(x, y)
                        started = true
                    }
                    drawPath(path, color = item.color, style = Stroke(width = 3f))
                }

                if (selectedDate != null && selectedDate >= from && selectedDate <= to) {
                    drawScrubRule(x = xFor(selectedDate), color = accent)
                }
            }
        }
        // The same three-part axis line `TrailChart` draws, given the same treatment for
        // the same reason: the window's two ends, the caption saying what the y-axis is a
        // fraction of, and a minimum gap so a long caption cannot run its neighbours into
        // one string. This chart predates `TrailChart` and has not been folded into it —
        // its y-axis is per-series normalised rather than shared — so the rule is repeated
        // here rather than shared, and the spacing has to be kept in step by hand.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                space = 12.dp,
                alignment = Alignment.CenterHorizontally,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                visibleFrom?.let { shortTrailDate(it, axisDayPattern, dateLocale) } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = PiruTheme.colors.secondaryLabel,
            )
            Text(
                stringResource(R.string.toolsb_receptor_axis_caption),
                style = MaterialTheme.typography.labelSmall,
                color = PiruTheme.colors.secondaryLabel,
            )
            Text(
                visibleTo?.let { shortTrailDate(it, axisDayPattern, dateLocale) } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}

/** The sampled instant nearest a tap along the visible window. */
private fun nearestInstant(
    series: List<ReceptorLoadSeries>,
    from: Instant,
    to: Instant,
    fraction: Float,
): Instant {
    val target = from.toEpochMilli() + ((to.toEpochMilli() - from.toEpochMilli()) * fraction).toLong()
    val all = series.flatMap { it.points }
    return all.minByOrNull { abs(it.date.toEpochMilli() - target) }?.date ?: from
}

/** The scrub readout: every class's load at the nearest sample, biggest first. */
@Composable
private fun ReceptorLoadReadout(series: List<ReceptorLoadSeries>, date: Instant) {
    val rows = series.mapNotNull { item ->
        val point = item.points.minByOrNull { abs(it.date.toEpochMilli() - date.toEpochMilli()) }
            ?: return@mapNotNull null
        // Below half a percent there is nothing being driven worth a row.
        if (point.load <= 0.005) null else item to point.load
    }.sortedByDescending { it.second }

    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                shortTrailDate(date, stringResource(R.string.datefmt_day_month), appLocale()),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            for ((item, load) in rows) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    InsightsLegendDot(item.color, size = 7.dp)
                    Text(
                        CoreLabels.receptorCasualName(item.receptorClass),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${Math.round(load * 100)}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
            if (rows.isEmpty()) {
                Text(
                    stringResource(R.string.toolsb_receptor_readout_empty),
                    style = MaterialTheme.typography.labelSmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
    }
}

/** The clickable legend: a dot and a name, dimmed once its line is hidden. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReceptorLoadLegend(
    series: List<ReceptorLoadSeries>,
    hidden: Set<String>,
    onToggle: (String) -> Unit,
) {
    val ink = PiruTheme.colors.secondaryLabel
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (item in series) {
            val isHidden = hidden.contains(item.id)
            Row(
                modifier = Modifier.clickable { onToggle(item.id) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                InsightsLegendDot(if (isHidden) item.color.copy(alpha = 0.3f) else item.color, size = 9.dp)
                Text(
                    CoreLabels.receptorCasualName(item.receptorClass),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isHidden) ink else MaterialTheme.colorScheme.onSurface,
                )
                if (isHidden) {
                    InsightsMiddot()
                    Text(
                        stringResource(R.string.toolsb_receptor_legend_hidden),
                        style = MaterialTheme.typography.labelSmall,
                        color = ink,
                    )
                }
            }
        }
    }
}

