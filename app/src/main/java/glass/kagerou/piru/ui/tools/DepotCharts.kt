package glass.kagerou.piru.ui.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import glass.kagerou.piru.R
import glass.kagerou.piru.ui.labels.appLocale
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

/**
 * The depot serum-level charts, hand-drawn.
 *
 * Ported from `DepotCurveCard.swift` (227 lines) — `DepotCurveChart`,
 * `DepotCurveMiniChart` — and the `AssumedDepotLevelsCard` chart in
 * `HormoneLevelsView.swift`.
 *
 * ## Why `Canvas` and not a chart library
 * The layer order is the content: a shaded reference band behind, an area band,
 * the line, injection ticks, the user's own reference lines, and a "now" marker
 * in that sequence. Every one of those is a primitive this file draws in five
 * lines, and the two ports would have to agree about the z-order anyway. What the
 * library would buy is axis niceties the original already draws by hand.
 *
 * ## The one place this diverges
 * Upstream gives each ester series a `ChartSeriesMarker` only when
 * "Differentiate Without Color" is on. Compose does not expose that setting, so
 * the line styles here are **always** differentiated — the first ester solid, the
 * rest dashed and dotted. That is strictly more legible and never less, so the
 * divergence is in the safe direction.
 */

/** The alpha ladder, mirroring `Theme.Opacity` in `Piru/DesignSystem/Opacity.swift`. */
private const val OPACITY_HAIRLINE = 0.08f
private const val OPACITY_TINT = 0.10f
private const val OPACITY_TINT_ACTIVE = 0.18f
private const val OPACITY_MUTED = 0.4f
private const val OPACITY_DIMMED = 0.5f

/** One ester series' stroke, indexed by its position in the mix. */
private val SERIES_DASHES: List<FloatArray?> = listOf(
    null, // solid
    floatArrayOf(7f, 4f),
    floatArrayOf(2f, 3f),
    floatArrayOf(10f, 3f, 2f, 3f),
    floatArrayOf(4f, 4f),
)

/** The per-ester palette, matching `AssumedDepotLevelsCard.palette`. */
private val SERIES_PALETTE: List<Color> = listOf(
    Color(0xFFEF3D6F), // the accent
    Color(0xFF00897B), // teal
    Color(0xFFF57C00), // orange
    Color(0xFF8E24AA), // purple
    Color(0xFF43A047), // green
)

/**
 * The month-and-day axis format, `.dateTime.month(.abbreviated).day()` upstream.
 *
 * The pattern arrives as a string rather than being written here: the field
 * order is locale-specific (Chinese reads M月d日, not "d MMM"), and this runs
 * inside `Canvas { … }`, where a `@Composable` resource read is not allowed. The
 * callers hoist the resource and pass it down.
 *
 * The locale arrives the same way, and for the same reason: the month *name* is
 * a word, so it has to be the app's own language rather than the device's. A
 * German phone runs this build's English screens, and `Locale.getDefault()` here
 * put "28. Sep" on an English axis.
 *
 * See docs/localization.md, "Dates need two things".
 */
private fun axisDateFormat(pattern: String, locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern(pattern, locale)

/**
 * The depot serum-level chart: the calibrated curve with its typical-range band,
 * injection ticks, the user's reference lines, a "now" marker, and the zoom menu.
 *
 * @param referenceBand a citable laboratory reference region shaded behind the
 *   curve (the male total-T range) — a reference, not a target.
 * @param onPinch the visible window in days, or null to fall back to the preset.
 * @param title a caller-supplied heading; null takes the default "Estimated
 *   <analyte> level", which is a resource and so cannot be a default value here.
 */
@Composable
internal fun DepotCurveChart(
    result: DepotCurveResult,
    analyte: Analyte,
    referenceLow: Double?,
    referenceHigh: Double?,
    chartRange: ChartRange,
    onChartRangeChange: (ChartRange) -> Unit,
    onPinch: (Double) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    referenceBand: ClosedRange<Double>? = null,
) {
    val colors = PiruTheme.colors
    val heading = if (title != null) {
        title
    } else {
        stringResource(R.string.toolsb_depot_chart_estimated_level_title, stringResource(analyte.displayNameRes))
    }
    val secondary = colors.secondaryLabel
    val accent = colors.accent
    val measurer = rememberTextMeasurer()
    val zone = remember { ZoneId.systemDefault() }
    val axisPattern = stringResource(R.string.datefmt_month_day)
    val dateLocale = appLocale()

    // Hoisted: a `@Composable` theme read cannot happen inside `Canvas { … }`.
    val referenceFill = secondary.copy(alpha = OPACITY_HAIRLINE)
    val bandFill = accent.copy(alpha = OPACITY_TINT)
    val tickColor = secondary.copy(alpha = OPACITY_DIMMED)
    val referenceLine = secondary.copy(alpha = OPACITY_MUTED)
    val nowColor = accent.copy(alpha = 0.35f)
    val gridColor = secondary.copy(alpha = OPACITY_DIMMED)
    val labelStyle = TextStyle(fontSize = 10.sp, color = secondary)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                heading,
                style = MaterialTheme.typography.labelMedium,
                color = secondary,
            )
            RangeMenu(chartRange = chartRange, onChartRangeChange = onChartRangeChange)
        }

        val totalSpanDays = max(
            0.0,
            (result.range.endInclusive.toEpochMilli() - result.range.start.toEpochMilli()) / 1000.0 / SECONDS_PER_DAY,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .pinchToZoom(totalSpanDays = totalSpanDays, onPinch = onPinch),
        ) {
            Canvas(Modifier.fillMaxWidth().height(220.dp)) {
                drawDepotCurve(
                    result = result,
                    analyte = analyte,
                    referenceLow = referenceLow,
                    referenceHigh = referenceHigh,
                    referenceBand = referenceBand,
                    referenceFill = referenceFill,
                    bandFill = bandFill,
                    tickColor = tickColor,
                    referenceLine = referenceLine,
                    nowColor = nowColor,
                    gridColor = gridColor,
                    accent = accent,
                    labelStyle = labelStyle,
                    measurer = measurer,
                    zone = zone,
                    axisPattern = axisPattern,
                    locale = dateLocale,
                )
            }
        }
    }
}

/** The zoom presets — a compact menu, matching the insights charts. */
@Composable
private fun RangeMenu(chartRange: ChartRange, onChartRangeChange: (ChartRange) -> Unit) {
    val accent = PiruTheme.colors.accent
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = stringResource(chartRange.labelRes)
    Box {
        Text(
            currentLabel,
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            modifier = Modifier
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (range in ChartRange.entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(range.labelRes)) },
                    onClick = {
                        expanded = false
                        onChartRangeChange(range)
                    },
                )
            }
        }
    }
}

/**
 * Pinch to zoom the visible window continuously between one week and the whole
 * span.
 *
 * Ported from the `MagnifyGesture` in `DepotCurveCard.swift`, including its
 * one-week floor and its "the base is captured at the start of the gesture"
 * behaviour — which is what makes a pinch feel linear rather than accelerating.
 *
 * ## Only a real pinch is consumed
 * The chart sits inside a scrolling column. Consuming every pointer change would
 * swallow the page's vertical scroll while a finger rests on the graph, which
 * upstream's `MagnifyGesture` does not do. So a drag is left alone and the parent
 * scrolls; only a change of scale is claimed.
 */
private fun Modifier.pinchToZoom(totalSpanDays: Double, onPinch: (Double) -> Unit): Modifier {
    // The callbacks close over values that change after every recompute, and the
    // gesture must see the current ones rather than the first ones.
    return this.pointerInput(totalSpanDays) {
        if (totalSpanDays <= 7.0) return@pointerInput
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            // `null` until the first event of this gesture, which is exactly
            // upstream's `pinchBaseDays == nil` guard.
            var base: Double? = null
            while (true) {
                val event = awaitPointerEvent()
                val zoom = event.calculateZoom()
                if (base == null) base = totalSpanDays
                val anchor = base
                if (anchor != null && zoom > 0f && zoom != 1f) {
                    val next = (anchor / zoom).coerceIn(7.0, max(7.0, totalSpanDays))
                    onPinch(next)
                    for (change in event.changes) change.consume()
                }
                if (event.changes.none { it.pressed }) break
            }
        }
    }
}

/**
 * A legend-less, non-interactive preview of a depot serum curve.
 *
 * Ported from `DepotCurveMiniChart`. The Insights "Hormone Levels" card's inline
 * chart, reading the same [DepotCurveResult] the full chart draws so the two can
 * never diverge.
 */
@Composable
internal fun DepotCurveMiniChart(
    result: DepotCurveResult,
    analyte: Analyte,
    modifier: Modifier = Modifier,
    tint: Color = PiruTheme.colors.accent,
    referenceLow: Double? = null,
    referenceHigh: Double? = null,
    referenceBand: ClosedRange<Double>? = null,
    height: Int = 76,
) {
    val secondary = PiruTheme.colors.secondaryLabel
    val measurer = rememberTextMeasurer()
    val zone = remember { ZoneId.systemDefault() }
    val axisPattern = stringResource(R.string.datefmt_month_day)
    val dateLocale = appLocale()

    val referenceFill = secondary.copy(alpha = OPACITY_HAIRLINE)
    val bandFill = tint.copy(alpha = OPACITY_TINT)
    val referenceLine = secondary.copy(alpha = OPACITY_MUTED)
    val nowColor = tint.copy(alpha = 0.35f)
    val labelStyle = TextStyle(fontSize = 10.sp, color = secondary)

    Canvas(modifier.fillMaxWidth().height(height.dp)) {
        drawDepotCurve(
            result = result,
            analyte = analyte,
            referenceLow = referenceLow,
            referenceHigh = referenceHigh,
            referenceBand = referenceBand,
            referenceFill = referenceFill,
            bandFill = bandFill,
            tickColor = Color.Transparent,
            referenceLine = referenceLine,
            nowColor = nowColor,
            gridColor = Color.Transparent,
            accent = tint,
            labelStyle = labelStyle,
            measurer = measurer,
            zone = zone,
            axisPattern = axisPattern,
            locale = dateLocale,
            axes = false,
            lineWidth = 2f,
        )
    }
}

/**
 * One series per logged ester — the assumed depot contribution each ester makes,
 * distinct from the serum sum.
 *
 * Ported from `AssumedDepotLevelsCard`. A switch or a mix reads honestly rather
 * than being flattened to one dominant ester.
 */
@Composable
internal fun AssumedDepotLevelsChart(
    analyte: Analyte,
    perEster: List<Pair<EsterPKRecord, List<DepotCurveResult.Point>>>,
    modifier: Modifier = Modifier,
) {
    val colors = PiruTheme.colors
    val secondary = colors.secondaryLabel
    val measurer = rememberTextMeasurer()
    val zone = remember { ZoneId.systemDefault() }
    val gridColor = secondary.copy(alpha = OPACITY_DIMMED)
    val labelStyle = TextStyle(fontSize = 10.sp, color = secondary)
    val axisPattern = stringResource(R.string.datefmt_month_day)
    val dateLocale = appLocale()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val totalSpan = perEster
            .flatMap { it.second }
            .let { points ->
                val first = points.minOfOrNull { it.date } ?: Instant.EPOCH
                val last = points.maxOfOrNull { it.date } ?: Instant.EPOCH
                Pair(first, last)
            }

        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val leftPad = 40.dp.toPx()
            val rightPad = 12.dp.toPx()
            val topPad = 8.dp.toPx()
            val bottomPad = 22.dp.toPx()
            val plotW = size.width - leftPad - rightPad
            val plotH = size.height - topPad - bottomPad
            if (plotW <= 0 || plotH <= 0) return@Canvas

            val start = totalSpan.first
            val end = totalSpan.second
            val spanMillis = (end.toEpochMilli() - start.toEpochMilli()).coerceAtLeast(1L).toDouble()

            val yMax = perEster.flatMap { it.second }.maxOfOrNull { it.level }?.times(1.08) ?: 1.0
            val safeMax = if (yMax > 0) yMax else 1.0

            fun x(date: Instant) = leftPad + ((date.toEpochMilli() - start.toEpochMilli()) / spanMillis).toFloat() * plotW
            fun y(level: Double) = topPad + plotH - (level / safeMax).toFloat() * plotH

            // X gridlines and labels, so the series share an axis with the serum
            // chart above them.
            for (i in 0..3) {
                val fraction = i / 3f
                val gx = leftPad + fraction * plotW
                drawLine(
                    color = gridColor,
                    start = Offset(gx, topPad),
                    end = Offset(gx, topPad + plotH),
                    strokeWidth = 0.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
                val date = Instant.ofEpochMilli(start.toEpochMilli() + (spanMillis * fraction).toLong())
                val label = axisDateFormat(axisPattern, dateLocale).withZone(zone).format(date)
                val measured = measurer.measure(label, labelStyle)
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(
                        (gx - measured.size.width / 2f).coerceIn(0f, size.width - measured.size.width),
                        topPad + plotH + 4.dp.toPx(),
                    ),
                )
            }

            perEster.forEachIndexed { index, (_, points) ->
                val color = SERIES_PALETTE[index % SERIES_PALETTE.size]
                val dash = SERIES_DASHES[index % SERIES_DASHES.size]
                val path = Path()
                points.forEachIndexed { i, point ->
                    val px = x(point.date)
                    val py = y(point.level)
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = dash?.let { PathEffect.dashPathEffect(it) },
                    ),
                )
            }
        }
        Text(
            analyte.canonicalUnit,
            style = MaterialTheme.typography.labelSmall,
            color = secondary,
        )
    }
}

/** The shared geometry of the depot band-and-line chart. */
private fun DrawScope.drawDepotCurve(
    result: DepotCurveResult,
    analyte: Analyte,
    referenceLow: Double?,
    referenceHigh: Double?,
    referenceBand: ClosedRange<Double>?,
    referenceFill: Color,
    bandFill: Color,
    tickColor: Color,
    referenceLine: Color,
    nowColor: Color,
    gridColor: Color,
    accent: Color,
    labelStyle: TextStyle,
    measurer: TextMeasurer,
    zone: ZoneId,
    axisPattern: String,
    locale: Locale,
    axes: Boolean = true,
    lineWidth: Float = 2.2f,
) {
    if (result.points.isEmpty()) return

    val leftPad = (if (axes) 40.dp else 6.dp).toPx()
    val rightPad = (if (axes) 12.dp else 6.dp).toPx()
    val topPad = (if (axes) 12.dp else 6.dp).toPx()
    val labelArea = (if (axes) 20.dp else 0.dp).toPx()
    val plotW = size.width - leftPad - rightPad
    val plotH = size.height - topPad - labelArea
    if (plotW <= 0 || plotH <= 0) return

    val start = result.range.start
    val end = result.range.endInclusive
    val spanMillis = (end.toEpochMilli() - start.toEpochMilli()).coerceAtLeast(1L).toDouble()

    // The y-axis starts at zero: a serum level is a concentration and cannot be
    // negative, so a floor below zero would draw empty space the data never
    // occupies. Headroom above, because the band's upper edge is a real value a
    // reader needs to see rather than touch.
    val yMax = maxOf(
        result.points.maxOf { it.bandHigh },
        referenceHigh ?: 0.0,
        referenceBand?.endInclusive ?: 0.0,
        result.peakHigh,
    ) * 1.06
    val safeMax = if (yMax > 0) yMax else 1.0

    fun x(date: Instant) = leftPad + ((date.toEpochMilli() - start.toEpochMilli()) / spanMillis).toFloat() * plotW
    fun y(level: Double) = topPad + plotH - (level / safeMax).toFloat() * plotH

    // Layer 1 — the citable reference region, behind everything.
    if (referenceBand != null) {
        val top = y(referenceBand.endInclusive)
        val bottom = y(referenceBand.start)
        drawRect(
            color = referenceFill,
            topLeft = Offset(leftPad, top),
            size = Size(plotW, bottom - top),
        )
    }

    // Layer 2 — the typical-range band around the level.
    val band = Path().apply {
        result.points.forEachIndexed { i, point ->
            val px = x(point.date)
            if (i == 0) moveTo(px, y(point.bandHigh)) else lineTo(px, y(point.bandHigh))
        }
        result.points.reversed().forEach { point -> lineTo(x(point.date), y(point.bandLow)) }
        close()
    }
    drawPath(band, bandFill)

    // Layer 3 — the level itself.
    val line = Path().apply {
        result.points.forEachIndexed { i, point ->
            val px = x(point.date)
            val py = y(point.level)
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
    }
    drawPath(line, accent, style = Stroke(width = lineWidth.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))

    // Layer 4 — one tick per injection.
    for (date in result.injectionDates) {
        val px = x(date)
        drawLine(
            color = tickColor,
            start = Offset(px, topPad),
            end = Offset(px, topPad + plotH),
            strokeWidth = 1.dp.toPx(),
        )
    }

    // Layer 5 — the user's own reference lines, dashed.
    val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
    for (value in listOfNotNull(referenceLow, referenceHigh)) {
        val py = y(value)
        drawLine(
            color = referenceLine,
            start = Offset(leftPad, py),
            end = Offset(leftPad + plotW, py),
            strokeWidth = 1.dp.toPx(),
            pathEffect = dash,
        )
    }

    // Layer 6 — "now".
    val now = Instant.now()
    if (now in start..end) {
        val px = x(now)
        drawLine(
            color = nowColor,
            start = Offset(px, topPad),
            end = Offset(px, topPad + plotH),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
        )
    }

    if (!axes) return

    // The y-axis unit, at the top of the axis rather than rotated beside it: the
    // values here run to hundreds and a rotated label costs a third of the plot
    // width to say "pg/mL".
    val unitLabel = measurer.measure(analyte.canonicalUnit, labelStyle)
    drawText(unitLabel, topLeft = Offset(0f, 0f))

    // Axis frame.
    drawLine(
        color = referenceLine,
        start = Offset(leftPad, topPad),
        end = Offset(leftPad, topPad + plotH),
        strokeWidth = 1.dp.toPx(),
    )
    drawLine(
        color = referenceLine,
        start = Offset(leftPad, topPad + plotH),
        end = Offset(leftPad + plotW, topPad + plotH),
        strokeWidth = 1.dp.toPx(),
    )

    // X ticks — four across the span, gridline dashed, labelled with the
    // caller's month-and-day pattern.
    val formatter = axisDateFormat(axisPattern, locale).withZone(zone)
    for (i in 0..3) {
        val fraction = i / 3f
        val gx = leftPad + fraction * plotW
        drawLine(
            color = gridColor,
            start = Offset(gx, topPad),
            end = Offset(gx, topPad + plotH),
            strokeWidth = 0.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )
        val date = Instant.ofEpochMilli(start.toEpochMilli() + (spanMillis * fraction).toLong())
        val measured = measurer.measure(formatter.format(date), labelStyle)
        drawText(
            textLayoutResult = measured,
            topLeft = Offset(
                (gx - measured.size.width / 2f).coerceIn(0f, size.width - measured.size.width),
                topPad + plotH + 4.dp.toPx(),
            ),
        )
    }
}

/**
 * The small statistic tile both depot screens use.
 *
 * Ported from `InjectionLevelsMetricsCard.metricTile` — an uppercase caption, a
 * rounded-bold value, and a caption below — over the input background.
 */
@Composable
internal fun MetricTile(key: String, value: String, sub: String, modifier: Modifier = Modifier) {
    val colors = PiruTheme.colors
    Column(
        modifier = modifier
            .background(colors.inputBackground, MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            key.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            color = colors.secondaryLabel,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            sub,
            style = MaterialTheme.typography.labelSmall,
            color = colors.secondaryLabel,
        )
    }
}

/** A painted legend swatch for one series, matching `ChartSeriesKey`. */
@Composable
internal fun SeriesSwatch(index: Int, modifier: Modifier = Modifier) {
    val color = SERIES_PALETTE[index % SERIES_PALETTE.size]
    val dash = SERIES_DASHES[index % SERIES_DASHES.size]
    Canvas(modifier.width(18.dp).height(8.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = 2.dp.toPx(),
            pathEffect = dash?.let { PathEffect.dashPathEffect(it) },
        )
    }
}
