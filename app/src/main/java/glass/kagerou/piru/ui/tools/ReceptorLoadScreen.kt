package glass.kagerou.piru.ui.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.LoadTrail
import glass.kagerou.piru.engine.PKModel
import glass.kagerou.piru.engine.ReceptorClasses
import glass.kagerou.piru.engine.ToleranceReplay
import glass.kagerou.piru.model.DoseUnit
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.model.SubstanceColorGenerator
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.insights.InsightsEmptyPanel
import glass.kagerou.piru.ui.insights.InsightsFilterPill
import glass.kagerou.piru.ui.insights.InsightsLegendDot
import glass.kagerou.piru.ui.insights.InsightsMiddot
import glass.kagerou.piru.ui.insights.InsightsSectionCard
import glass.kagerou.piru.ui.insights.UsageTimeRange
import glass.kagerou.piru.ui.insights.rememberViewportState
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

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
        runCatching {
            val entries = app.database.doseEntryDao().all()
            entryCount = entries.size
            series = buildSeries(app, entries, range)
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
                Text("Receptor load", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "How hard each mechanism has been driven across the range, relative " +
                        "to your own recent baseline. A modeled load from your logged doses — " +
                        "it models receptor drive, not how you feel.",
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
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("The replay could not run", style = MaterialTheme.typography.titleSmall)
                        Text(message, style = MaterialTheme.typography.bodySmall, color = PiruTheme.colors.dangerText)
                    }
                }
            }
        }

        if (failure == null && !loaded) {
            item {
                Text(
                    "Replaying your log…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        if (failure == null && loaded && entryCount == 0) {
            item {
                InsightsEmptyPanel(
                    "No logged entries",
                    "Add entries to see modeled receptor load.",
                )
            }
        }

        if (failure == null && loaded && entryCount > 0 && series.isEmpty()) {
            item {
                InsightsEmptyPanel(
                    "Nothing to model",
                    "None of your logged substances in this range drive a modeled mechanism.",
                )
            }
        }

        if (series.isNotEmpty()) {
            item {
                val visible = series.filterNot { hidden.contains(it.id) }
                // Hiding every line would leave an empty plot with no way back,
                // so the legend can never zero the chart out.
                val shown = visible.ifEmpty { series }

                InsightsSectionCard(title = "Receptor load over time", subtitle = "Relative to your recent baseline") {
                    ReceptorLoadChart(shown, selectedDate, zoom.windowSeconds) { selectedDate = it }
                    selectedDate?.let { ReceptorLoadReadout(shown, it) }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (level in ZoomLevel.entries) {
                            InsightsFilterPill(
                                label = level.label,
                                color = accent,
                                isSelected = level == zoom,
                                showDot = false,
                                onClick = { zoom = level },
                            )
                        }
                    }
                    Text(
                        "Wide shows 180 days at once, Medium 90, Close 30.",
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
                    "A modeled relative load from your logged doses. Predicted from a " +
                        "model, not measured. Not medical advice.",
                    style = MaterialTheme.typography.labelSmall,
                    color = PiruTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

/** How much of the trail is on screen at once. */
private enum class ZoomLevel(val label: String, val windowSeconds: Double) {
    WIDE("Wide", 180 * 86_400.0),
    MEDIUM("Medium", 90 * 86_400.0),
    CLOSE("Close", 30 * 86_400.0),
}

// MARK: - Loading

/** A class's trail, ready to draw. */
private data class ReceptorLoadSeries(
    val id: String,
    val name: String,
    val color: Color,
    val peak: Double,
    val points: List<LoadPoint>,
)

private data class LoadPoint(val date: Instant, val load: Double)

private const val MINIMUM_PEAK = 0.02
private const val MAXIMUM_SERIES = 6

/**
 * Builds the trails.
 *
 * The classes come from a full replay (the same one the Tolerance tool runs), so
 * the ordering is by what is actually toleranced rather than by what happens to
 * be in the log, and a class the user has never heard of can still be the one
 * carrying the shift.
 */
private suspend fun buildSeries(
    app: PiruApplication,
    entries: List<DoseEntryEntity>,
    range: UsageTimeRange,
): List<ReceptorLoadSeries> {
    val log = entries.mapNotNull { it.toSimDose() }
    if (log.isEmpty()) return emptyList()

    val pharmacology = app.catalog()
    val now = Instant.now()
    val nowMinutes = now.toEpochMilli() / 60_000.0
    val weight = app.profile().weightKgOrDefault()

    // The representatives are resolved alongside the logged names, never instead
    // of them: a PK-less substance is modelled as its class representative, and
    // that representative is usually not in the log at all.
    val params = pharmacology.pharmacologyForLog(log.map { it.substance }.toSet() + pharmacology.classRepresentativeNames())

    val cards = ToleranceReplay.simulate(log, params, nowMinutes, weight)
    val classes = cards.values
        .sortedByDescending { it.severity }
        .map { it.receptorClass }
        .take(MAXIMUM_SERIES)

    val pastHorizonSeconds = range.days?.let { it * 86_400.0 }
        ?: (now.toEpochMilli() - (entries.minOf { it.timestamp.time })).toDouble().div(1000.0)
    val stepSeconds = step(forWindowSeconds = pastHorizonSeconds)

    // The engine's lookback defaults to a year. A range longer than that would
    // sample past the replay window and draw as flat zero, which reads as "no
    // use" rather than "not computed" — so the lookback is widened to cover the
    // requested window.
    val lookbackDays = maxOf(365.0, pastHorizonSeconds / 86_400.0 + 1.0)

    val out = ArrayList<ReceptorLoadSeries>()
    for (receptorClass in classes) {
        val trail = LoadTrail.loadTrail(
            doses = log,
            params = params,
            now = now,
            weightKg = weight,
            receptorClass = receptorClass,
            horizonMinutes = 0.0,
            stepMinutes = stepSeconds / 60.0,
            pastHorizonMinutes = pastHorizonSeconds / 60.0,
            lookbackDays = lookbackDays,
        )
        val peak = trail.maxOfOrNull { it.load } ?: continue
        if (peak <= MINIMUM_PEAK) continue
        out += ReceptorLoadSeries(
            id = receptorClass.wireValue,
            name = receptorClass.casualName,
            color = classColor(receptorClass),
            peak = peak,
            points = trail.map { LoadPoint(it.date, it.load) },
        )
    }
    return out.sortedByDescending { it.peak }
}

/**
 * Sample spacing for the trail: coarser as the window widens, so a year's trace
 * is not an unreadable comb. Three hours is `loadTrail`'s own default and the
 * spacing a month of data wants.
 */
private fun step(forWindowSeconds: Double): Double = when {
    forWindowSeconds < 31 * 86_400.0 -> 3 * 3_600.0
    forWindowSeconds < 91 * 86_400.0 -> 6 * 3_600.0
    forWindowSeconds < 366 * 86_400.0 -> 12 * 3_600.0
    else -> maxOf(12 * 3_600.0, forWindowSeconds / 1_000.0)
}

/**
 * A base colour per mechanism class, drawn through the same generator the
 * substances use but seeded on the class.
 *
 * `OTHER` is the seed because a class is not a substance category: it takes the
 * achromatic branch, which spreads hues evenly around the wheel at low chroma —
 * exactly what a set of a dozen class labels wants. The same construction the
 * Tolerance tool uses, so a class keeps one colour across the two screens.
 */
private fun classColor(receptorClass: ReceptorClasses.ReceptorClass): Color {
    val p3: P3Color = SubstanceColorGenerator.displayP3(SubstanceCategory.OTHER, "class:${receptorClass.wireValue}")
    return Color(p3.red.toFloat(), p3.green.toFloat(), p3.blue.toFloat(), 1f)
}

/**
 * A logged dose as the replay sees it.
 *
 * Two ordinary reasons to drop one, both of which are answers rather than
 * errors: a dose of unknown amount has no concentration to compute, and a dose
 * in a unit that is not a mass — millilitres, IU — has no milligram equivalent.
 */
private fun DoseEntryEntity.toSimDose(): ToleranceReplay.SimDose? {
    if (isUnknownDose) return null
    val mg = DoseUnit.convert(amount, from = unit, to = "mg") ?: return null
    return ToleranceReplay.SimDose(
        substance = substance,
        amountMg = mg,
        timestampMinutes = timestamp.toInstant().toEpochMilli() / 60_000.0,
    )
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
    val ruleInk = PiruTheme.colors.secondaryLabel.copy(alpha = 0.45f)
    val labelInk = PiruTheme.colors.secondaryLabel
    val measurer = rememberTextMeasurer()

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
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            viewport.pan(dragAmount.x, widthPx)
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
                    val x = xFor(selectedDate)
                    var y = 0f
                    while (y < size.height) {
                        drawLine(ruleInk, Offset(x, y), Offset(x, minOf(y + 6f, size.height)), strokeWidth = 1.5f)
                        y += 12f
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                visibleFrom?.let { shortDate(it) } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = PiruTheme.colors.secondaryLabel,
            )
            Text(
                "share of your recent peak",
                style = MaterialTheme.typography.labelSmall,
                color = PiruTheme.colors.secondaryLabel,
            )
            Text(
                visibleTo?.let { shortDate(it) } ?: "",
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
            Text(shortDate(date), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            for ((item, load) in rows) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    InsightsLegendDot(item.color, size = 7.dp)
                    Text(item.name, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    Text(
                        "${Math.round(load * 100)}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
            if (rows.isEmpty()) {
                Text(
                    "Nothing driven at this time",
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
                    item.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isHidden) ink else MaterialTheme.colorScheme.onSurface,
                )
                if (isHidden) {
                    InsightsMiddot()
                    Text("hidden", style = MaterialTheme.typography.labelSmall, color = ink)
                }
            }
        }
    }
}

private val SHORT_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ROOT)

private fun shortDate(instant: Instant): String = SHORT_DAY.format(instant.atZone(ZoneId.systemDefault()))
