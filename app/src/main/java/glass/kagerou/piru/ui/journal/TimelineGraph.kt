package glass.kagerou.piru.ui.journal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import glass.kagerou.piru.R
import glass.kagerou.piru.engine.ActiveSubstanceState
import glass.kagerou.piru.engine.DoseMarker
import glass.kagerou.piru.engine.SessionVitals
import glass.kagerou.piru.engine.TimelineCurveModel
import glass.kagerou.piru.engine.VitalsPalette
import glass.kagerou.piru.ui.components.ScrubReadout
import glass.kagerou.piru.ui.components.ScrubRow
import glass.kagerou.piru.ui.components.drawScrubRule
import glass.kagerou.piru.ui.components.timeScrub
import glass.kagerou.piru.ui.labels.appLocale
import glass.kagerou.piru.ui.theme.toComposeColor
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * The dose-effect graph, with a time cursor you can drag.
 *
 * ## Every number comes from the engine
 * The span, the y-scale, the tick interval and the lane set are
 * `TimelineCurveModel.computeDerived`'s output — the same model the tests pin.
 * That matters more than it sounds: an earlier version of this file computed its
 * own y-ceiling from the loudest sample and drew one flat shared axis, which is
 * wrong in a way that only shows up with two doses in view. `yNormalization` is
 * not simply the peak's reciprocal — it is clamped at 20× so a near-silent day is
 * not amplified into a loud-looking one, and it is computed against the *stacked*
 * curves, so redoses merge before they are scaled.
 *
 * ## The cursor is the now-line, moved
 * At rest the rule sits at `currentTime` exactly as before, and a chart nobody has
 * touched draws the picture it always drew. Drag it — or tap where you want it —
 * and it stays there with a readout under the plot naming each curve's height at
 * that instant, because a readout that vanishes on release cannot be read. "Back to
 * now" returns it to the present. The rule keeps the now-line's accent and dash, so
 * a chart that gained a cursor did not also gain a second visual language for "time".
 *
 * The readout's percentages are the drawn heights rather than a re-derived scale:
 * `stackedIntensity × yNormalization` is the same expression [yFor] plots, so the
 * number and the pixel under the rule cannot disagree.
 *
 * ## What is still missing from the port
 * Upstream's renderer is 1,869 lines and this draws its core: the curve lanes, the
 * tick ladder, the marker lanes, the now-line and the scrub. Not here — the conflict
 * bands, milestone ribbons, the phase-band underlay, the pinch/pan gestures and the
 * vitals lane. They are layout and gesture work rather than model work, which is why
 * they can follow without touching anything below.
 *
 * ## [plotHeight] sizes the curves, not the canvas
 * The canvas is as tall as the caller asks **plus** whatever the lanes below the plot
 * need: the clock band, then one strip per duration-less substance. Upstream computes
 * its own height the same way, in `GraphMetrics.graphHeight`, and the reason is the bug
 * this grew out of — with the marker lanes hung directly under the baseline and the
 * clock labels drawn in that same band, every marker name landed on top of the axis.
 * A caller cannot know how much room those lanes want, because it depends on how many
 * of the day's substances the *catalog* has no duration for; so the height is the
 * graph's to finish, and a chart with marker lanes is legitimately taller than one
 * without.
 */
@Composable
fun TimelineGraph(
    states: List<ActiveSubstanceState>,
    markers: List<DoseMarker>,
    currentTime: Instant,
    modifier: Modifier = Modifier,
    plotHeight: Dp = 220.dp,
    /** Merge a substance's redoses into one curve. On for the day view; the session graph does the same. */
    stackRedoses: Boolean = true,
    /** Clamp the frame to a day rather than letting a long-acting dose stretch it. */
    dayBounded: Boolean = true,
    /**
     * The phone's heart rate and blood pressure over this window, drawn as a companion
     * lane under the curves. [SessionVitals.empty] draws nothing at all — no lane, no
     * axis — so a session with no wearable looks exactly as it did before this existed.
     */
    vitals: SessionVitals = SessionVitals.empty,
    sampleCount: Int = 260,
) {
    if (states.isEmpty() && markers.isEmpty()) {
        EmptyGraph(plotHeight, modifier)
        return
    }

    val colors = PiruTheme.colors
    val measurer = rememberTextMeasurer()
    val zone = remember { ZoneId.systemDefault() }
    // The cursor readout's date-bearing pattern. Only used when the cursor leaves the
    // day the graph starts on: the day view is bounded to 24 h and a bare clock time
    // is the honest reading there, but the *session* graph sets `dayBounded = false`
    // and can span two days, where "14:32" alone names an instant a full day off.
    val dateTimePattern = stringResource(R.string.datefmt_day_month_time)
    val dateLocale = appLocale()

    // Keyed on a *bucketed* now, not on the instant itself.
    //
    // `computeDerived` walks every curve and is documented as running exactly
    // once, but a caller passing `Instant.now()` hands it a new value on every
    // recomposition — so the key changed constantly and the whole model was
    // rebuilt for every frame. The model reads `now` only to decide how far the
    // framing reaches, which a minute of resolution answers as well as a
    // nanosecond, so the bucket is both correct and stable.
    val nowBucket = currentTime.epochSecond / 60
    val derived = remember(states, markers, stackRedoses, dayBounded, nowBucket) {
        TimelineCurveModel.computeDerived(
            substances = states,
            markers = markers,
            stackRedoses = stackRedoses,
            dayBounded = dayBounded,
            currentTime = currentTime,
        )
    }

    // The framed window: where the data is still doing something.
    val spanMinutes = maxOf(derived.rawActivityTail, 60.0)
    val start = derived.earliestDose
    val tickMinutes = TimelineCurveModel.intervalForSpan(spanMinutes)

    // Where the reader has dragged the cursor, or null for "still at now". Keyed on
    // the inputs so a different day starts at the present rather than inheriting the
    // last day's cursor position.
    var scrubTime by remember(states, markers, start) { mutableStateOf<Instant?>(null) }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val widthPx = constraints.maxWidth.toFloat()
        val plotHeightPx = with(LocalDensity.current) { plotHeight.toPx() }

        // The gutter is sized from the widest marker label rather than fixed: a
        // truncated substance name is worse than a narrower plot, and the labels
        // are the only thing that makes a row of dots mean anything.
        val markerLanes = remember(markers) {
            markers.groupBy { it.substanceName.lowercase() }.values.toList()
        }
        val widestLabel = remember(markerLanes, measurer) {
            markerLanes.maxOfOrNull { lane ->
                measurer.measure(
                    lane.first().substanceName,
                    TextStyle(fontSize = 10.sp, color = colors.secondaryLabel),
                ).size.width
            } ?: 0
        }
        // The clock labels' own height, measured rather than guessed. It decides how
        // tall the band under the baseline has to be before the first marker lane may
        // begin, and a guessed constant is exactly how this band came to be too short
        // for what it had to hold.
        val clockLabelHeight = remember(measurer) {
            measurer.measure(CLOCK_LABEL_SPECIMEN, TextStyle(fontSize = 10.sp)).size.height.toFloat()
        }
        // The geometry lives outside the Canvas now, because the drag gesture needs
        // the same mapping the drawing does — an inset the draw knows and the
        // gesture does not is how a cursor ends up under the finger but not where it
        // was dropped.
        val leftGutter = 0f
        val rightGutter = maxOf(44f, widestLabel + 10f)
        val topGutter = 14f
        // Everything below the plot, in draw order and in non-overlapping bands: the
        // clock labels, then one strip per marker lane. Both halves are load-bearing.
        //
        // The lanes used to be hung at `baseline + 18f` inside a canvas too short to
        // hold them, so every marker name landed on the clock labels *and* on the axis
        // line — the bug this geometry grew out of. Reserving the bands is the fix, not
        // the spacing: a lane is placed from the canvas's own bottom edge and the clock
        // band is measured from the labels it has to hold, so neither can drift into the
        // other however many lanes a day turns out to have.
        val clockBand = clockLabelHeight + 10f
        val markerLaneGap = 6f
        val markerLaneHeight = clockLabelHeight + 8f
        val markerBands = (markerLaneGap + markerLaneHeight) * markerLanes.size
        // The cardio lane, when there is one. It sits between the curves and the clock
        // labels rather than below them: the clock belongs at the foot of the time axis
        // whatever else the graph carries, so adding a lane above it moves the clock
        // down instead of burying it. The height is fixed rather than derived from the
        // data — a lane that grew with the sample count would make two sessions
        // incomparable, and this one is a companion strip, not a plot to be measured.
        val cardioGap = if (vitals.isEmpty) 0f else 8f
        // The lane's height is a compromise between two things it has to show: a bpm range
        // that a resting heart rate makes small (30 bpm is an ordinary session), and a
        // scale legible at 8sp. At the upstream proportions a 30 bpm range put two guide
        // labels on the same pixel, so this is taller — the guides thin themselves out as
        // well, for the ranges where even this is not enough.
        val cardioBandHeight = if (vitals.isEmpty) 0f else 72f
        val cardioBand = cardioGap + cardioBandHeight
        val bottomGutter = clockBand + markerBands + cardioBand
        val plotWidth = widthPx - leftGutter - rightGutter
        val curveHeight = plotHeightPx
        // The canvas is taller than the curves by exactly the bands below them, which is
        // why a day with marker lanes draws a taller card than one without: there is
        // genuinely more to draw. Upstream reaches the same shape from the other
        // direction, by growing `GraphMetrics.graphHeight` with the lane count.
        val canvasHeightDp = with(LocalDensity.current) {
            (topGutter + curveHeight + bottomGutter).toDp()
        }
        val drawable = plotWidth > 0f && curveHeight > 0f

        fun xFor(minutes: Double): Float = leftGutter + (minutes / spanMinutes).toFloat() * plotWidth
        fun minutesForX(x: Float): Double =
            if (plotWidth <= 0f) 0.0 else (((x - leftGutter) / plotWidth).toDouble() * spanMinutes).coerceIn(0.0, spanMinutes)

        Column {
            // The cursor's own position and reading, in the words `ScrubReadout` uses
            // below — so the label a screen reader announces and the card a sighted
            // reader sees cannot say two different things about the same instant.
            //
            // At rest the cursor is *at now*, which is where the rule has always been
            // drawn, so the reading is the present rather than nothing. That is what
            // keeps this node present without a drag: a cursor that only exists once
            // someone has touched it has no node to focus and no state to read, which
            // is exactly the gap this closes.
            val cursorMoment = scrubTime ?: currentTime
            val cursorMinutes = minutesBetween(start, cursorMoment)
            val cursorAt = cursorMoment.atZone(zone).let { atZone ->
                val sameDay = atZone.toLocalDate() == start.atZone(zone).toLocalDate()
                if (sameDay) {
                    atZone.format(DateTimeFormatter.ofPattern("HH:mm"))
                } else {
                    atZone.format(DateTimeFormatter.ofPattern(dateTimePattern, dateLocale))
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(canvasHeightDp)
                    .timeScrub(
                        enabled = drawable,
                        label = stringResource(R.string.scrub_label_journal),
                        readout = stringResource(R.string.scrub_cursor_at, cursorAt),
                        cursorFraction = (cursorMinutes / spanMinutes).toFloat().coerceIn(0f, 1f),
                        // One step tries to be an hour: the window here is bounded to a
                        // day, but its length follows the day's own activity, so the
                        // fraction is what an hour *is* on this particular day.
                        stepFraction = (60.0 / spanMinutes).toFloat(),
                    ) { fraction ->
                        val minutes = minutesForX(fraction * widthPx)
                        scrubTime = start.plusMillis((minutes * 60_000.0).toLong())
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    if (!drawable) return@Canvas

                    val baseline = topGutter + curveHeight
                    fun yFor(value: Double): Float = baseline - (value * derived.yNormalization).toFloat() * curveHeight

                    // The bands below the plot, measured from the canvas's own foot
                    // upward: the marker lanes first, then the clock, then the cardio
                    // lane. Anchoring here rather than to the plot's top is what makes
                    // the bands un-overlappable — each one's space is reserved before
                    // the next is placed, whatever any of them turns out to hold.
                    val lanesTop = size.height - markerBands
                    val clockTop = lanesTop - clockBand
                    val cardioTop = clockTop - cardioBandHeight

                    // The cardio lane first, so the curves' fill and the cursor's rule
                    // are drawn over it rather than under.
                    if (vitals.isEmpty.not()) {
                        drawCardioLane(
                            vitals = vitals,
                            start = start,
                            spanMinutes = spanMinutes,
                            xFor = ::xFor,
                            // The same instant the rule below is drawn at: the cursor's
                            // when the reader has moved it, the present otherwise.
                            cursorMinute = (scrubTime?.let { minutesBetween(start, it) }
                                ?: minutesBetween(start, currentTime))
                                .takeIf { it in 0.0..spanMinutes },
                            top = cardioTop,
                            height = cardioBandHeight,
                            ink = colors.secondaryLabel,
                            measurer = measurer,
                        )
                    }

                    // The tick ladder, drawn under the curves so a gridline never crosses one.
                    var tick = 0.0
                    while (tick <= spanMinutes) {
                        val x = xFor(tick)
                        drawLine(
                            color = colors.secondaryLabel.copy(alpha = 0.15f),
                            start = Offset(x, topGutter),
                            end = Offset(x, baseline),
                            strokeWidth = 1f,
                        )
                        val at = start.plusMillis((tick * 60_000).toLong()).atZone(zone)
                        val label = at.format(DateTimeFormatter.ofPattern("HH:mm"))
                        val measured = measurer.measure(label, TextStyle(fontSize = 10.sp, color = colors.secondaryLabel))
                        drawText(
                            textLayoutResult = measured,
                            topLeft = Offset(
                                x = (x - measured.size.width / 2f).coerceIn(0f, size.width - measured.size.width),
                                // Centred in the clock band, which is measured from this
                                // very text — so the labels sit in reserved space rather
                                // than in whatever was left over.
                                y = clockTop + (clockBand - measured.size.height) / 2f,
                            ),
                        )
                        tick += tickMinutes
                    }

                    // The curve lanes. Lane order is the engine's — first-dose order — so a
                    // substance does not swap lanes between two renders of the same day.
                    for (group in derived.stackedGroups) {
                        if (group.isEmpty()) continue
                        val tint = group.first().tint.toComposeColor()
                        val path = Path()
                        path.moveTo(xFor(0.0), baseline)
                        for (index in 0 until sampleCount) {
                            val minutes = spanMinutes * index / (sampleCount - 1)
                            val value = TimelineCurveModel.stackedIntensity(minutes, group, start)
                            path.lineTo(xFor(minutes), yFor(value))
                        }
                        path.lineTo(xFor(spanMinutes), baseline)
                        path.close()
                        drawPath(path, color = tint.copy(alpha = 0.25f))
                        drawPath(path, color = tint, style = Stroke(width = 2.5f))
                    }

                    // Single doses that resolve no duration are not curves, but they are still
                    // the *fact* the graph is about — so they get a lane of dots each rather
                    // than disappearing from the picture.
                    //
                    // Placed from the foot of the marker lanes upward, which is the half of
                    // the fix that keeps them off the clock labels: lane 0 sits lowest, the
                    // clock band is the only thing above the last lane, and the cardio lane
                    // (when there is one) is above that.
                    var laneIndex = 0
                    for (group in markerLanes) {
                        val y = lanesTop + laneIndex * (markerLaneGap + markerLaneHeight) + markerLaneHeight / 2f
                        val first = group.first()
                        val measured = measurer.measure(
                            first.substanceName,
                            TextStyle(fontSize = 10.sp, color = colors.secondaryLabel),
                        )
                        // Right-aligned in the gutter, so every lane's label ends together and
                        // the plot's right edge stays a clean line.
                        drawText(
                            textLayoutResult = measured,
                            topLeft = Offset(size.width - measured.size.width, y - measured.size.height / 2f),
                        )
                        drawLine(
                            color = colors.secondaryLabel.copy(alpha = 0.25f),
                            start = Offset(leftGutter, y),
                            end = Offset(leftGutter + plotWidth, y),
                            strokeWidth = 1f,
                        )
                        for (marker in group) {
                            val minutes = minutesBetween(start, marker.timestamp)
                            if (minutes < 0 || minutes > spanMinutes) continue
                            drawCircle(
                                color = marker.tint.toComposeColor(),
                                radius = 3.5f,
                                center = Offset(xFor(minutes), y),
                            )
                        }
                        laneIndex++
                    }

                    // The cursor: where the reader put it, or at now. Gated to the window
                    // so a rule is never pinned to an edge claiming the time is somewhere
                    // it is not — a reader who drags past the data gets the data's edge,
                    // because the gesture already clamps to the span.
                    val cursorMinutes = scrubTime?.let { minutesBetween(start, it) }
                        ?: minutesBetween(start, currentTime)
                    if (cursorMinutes in 0.0..spanMinutes) {
                        drawScrubRule(
                            x = xFor(cursorMinutes),
                            color = colors.accent,
                            top = topGutter,
                            bottom = baseline,
                        )
                    }

                    drawLine(
                        color = colors.secondaryLabel.copy(alpha = 0.3f),
                        start = Offset(leftGutter, baseline),
                        end = Offset(leftGutter + plotWidth, baseline),
                        strokeWidth = 1f,
                    )
                }
            }

            scrubTime?.let { at ->
                val minutes = minutesBetween(start, at)
                // A curve that has not started yet, or has already decayed away, is not
                // a reading — a row of zeroes under the rule would be noise wearing the
                // shape of a finding.
                val rows = derived.stackedGroups
                    .filter { it.isNotEmpty() }
                    .mapNotNull { group ->
                        val drawn = TimelineCurveModel.stackedIntensity(minutes, group, start) * derived.yNormalization
                        if (drawn < 0.005) return@mapNotNull null
                        drawn to ScrubRow(
                            label = group.first().substanceName,
                            value = "${(drawn * 100).roundToInt()}%",
                            tint = group.first().tint.toComposeColor(),
                        )
                    }
                    .sortedByDescending { it.first }
                    .map { it.second }

                val atZone = at.atZone(zone)
                val sameDay = atZone.toLocalDate() == start.atZone(zone).toLocalDate()
                ScrubReadout(
                    title = if (sameDay) {
                        atZone.format(DateTimeFormatter.ofPattern("HH:mm"))
                    } else {
                        atZone.format(DateTimeFormatter.ofPattern(dateTimePattern, dateLocale))
                    },
                    rows = rows,
                    emptyText = stringResource(R.string.journal_scrub_readout_empty),
                    onReset = { scrubTime = null },
                )
            }
        }
    }
}

@Composable
private fun EmptyGraph(height: Dp, modifier: Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.journal_no_doses_to_draw),
            style = MaterialTheme.typography.bodyMedium,
            color = PiruTheme.colors.secondaryLabel,
        )
    }
}

private fun minutesBetween(from: Instant, to: Instant): Double =
    Duration.between(from, to).toMillis() / 60_000.0

/**
 * The companion cardio lane: heart rate and blood pressure under the effect curves.
 *
 * Ported from `TimelineGraphRenderer.drawVitalsLane` and `drawVitalsLaneBackdrop`.
 *
 * ## What it draws, and why each part is drawn that way
 * - **Heart rate as a min–max envelope with a mean line.** A wrist sensor bursts to
 *   seconds-apart samples during movement and idles at five-minute intervals at rest,
 *   so a line through every sample is a picture of the sampling rate as much as of the
 *   heart. The samples are binned across the visible window and each bin contributes a
 *   low, a high and a mean; the envelope is the range actually measured, the line is
 *   the middle of it.
 * - **Blood pressure as systolic-to-diastolic bars**, on its own mmHg scale. There is
 *   no continuous BP sensor, so these are spot checks: a bar per reading, never
 *   interpolated into a curve that would invent readings between them.
 * - **A bpm scale with labelled guides**, snapped to tens and floored at a 20 bpm
 *   span. Without them the lane is a shape with no numbers, which is what the upstream
 *   "no legend" report was about.
 * - **The lane is only drawn when there is something to draw.** Empty vitals produce no
 *   lane, no band and no axis — the caller does not have to gate it, and a session with
 *   no wearable is byte-for-byte the picture it was before this existed.
 *
 * Two scales share one lane, each by its own axis, which is why the labels sit in
 * opposite corners: bpm on the left, mmHg on the right.
 */
private fun DrawScope.drawCardioLane(
    vitals: SessionVitals,
    start: Instant,
    spanMinutes: Double,
    xFor: (Double) -> Float,
    /** The minute the reader's cursor sits at, or the present. Null draws no marker. */
    cursorMinute: Double?,
    top: Float,
    height: Float,
    ink: Color,
    measurer: TextMeasurer,
) {
    val heart = VitalsPalette.heart.toComposeColor()
    val pressure = VitalsPalette.bloodPressure.toComposeColor()
    val labelFont = TextStyle(fontSize = 8.sp, color = ink)

    // The tinted band, so the lane reads as a companion strip rather than as more plot.
    drawRect(
        color = heart.copy(alpha = 0.06f),
        topLeft = Offset(0f, top),
        size = Size(size.width, height),
    )

    // The bpm scale gets its own left gutter and the trace is clipped out of it. Both
    // were missing at first, so the guide numbers, the unit caption and the trace all
    // shared the lane's left edge and printed on top of one another. A gutter costs the
    // trace about a seventh of its width and buys a legible axis, which is the trade the
    // effect curves above already make for their own gutter.
    val axisGutter = 26f
    val captionRow = 11f
    val plotTop = top + 2f
    val plotBottom = top + height - captionRow - 1f
    val plotHeight = plotBottom - plotTop

    val heartRate = vitals.heartRate
        .map { minutesBetween(start, it.date) to it.bpm }
        .filter { (minute, _) -> minute >= -5.0 && minute <= spanMinutes + 5.0 }

    // Everything in the gutter is drawn here, after the trace, so a scale label is never
    // under a line. The trace itself is clipped to the plot below, which is what keeps
    // the gutter clean rather than merely usually clean.
    fun drawScale(text: String, y: Float, tint: Color, atLeft: Boolean = true) {
        val measured = measurer.measure(text, TextStyle(fontSize = 8.sp, color = tint))
        val x = if (atLeft) axisGutter - measured.size.width - 3f else size.width - measured.size.width - 3f
        drawText(
            textLayoutResult = measured,
            topLeft = Offset(x, y.coerceIn(plotTop, (plotBottom - measured.size.height).coerceAtLeast(plotTop))),
        )
    }

    if (heartRate.isNotEmpty()) {
        // A bpm scale from the visible data, padded and snapped to tens, floored at a
        // 20 bpm span so a flat resting trace is not amplified into a dramatic one.
        var low = ((heartRate.minOf { it.second } - 6.0) / 10.0).let { floor(it) * 10.0 }
        var high = ((heartRate.maxOf { it.second } + 6.0) / 10.0).let { ceil(it) * 10.0 }
        if (high - low < 20.0) high = low + 20.0
        fun yFor(bpm: Double): Float =
            plotTop + (1f - ((bpm.coerceIn(low, high) - low) / (high - low)).toFloat()) * plotHeight

        var guide = floor(low / 20.0) * 20.0
        if (guide <= low) guide += 20.0
        val guides = mutableListOf<Pair<Double, Float>>()
        while (guide < high) {
            val y = yFor(guide)
            drawLine(
                color = ink.copy(alpha = 0.18f),
                start = Offset(axisGutter, y),
                end = Offset(size.width, y),
                strokeWidth = 0.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f, 4f)),
            )
            guides += guide to y
            guide += 20.0
        }

        // Bin across the visible window: one low, one high and one mean per bin. Sixty
        // bins is the upstream count, and it is about the number of distinguishable
        // x-positions on a phone-width lane rather than a modelling choice.
        val bins = 60
        val lows = FloatArray(bins) { Float.MAX_VALUE }
        val highs = FloatArray(bins) { -Float.MAX_VALUE }
        val sums = DoubleArray(bins)
        val counts = IntArray(bins)
        for ((minute, bpm) in heartRate) {
            val fraction = minute / spanMinutes
            if (fraction < 0.0 || fraction > 1.0) continue
            val index = (fraction * bins).toInt().coerceIn(0, bins - 1)
            lows[index] = minOf(lows[index], bpm.toFloat())
            highs[index] = maxOf(highs[index], bpm.toFloat())
            sums[index] += bpm
            counts[index]++
        }

        val filled = (0 until bins).filter { counts[it] > 0 }
        // The lane's own time mapping, past the gutter, rather than the caller's `xFor`:
        // that one spans the full canvas width, which would put the first sample behind
        // the bpm labels and put the trace a gutter's width to the left of the plot it is
        // supposed to share a time axis with. One mapping for the trace and the cursor
        // marker, so they cannot disagree.
        val plotLeft = axisGutter
        val plotWidth = (size.width - plotLeft).coerceAtLeast(1f)
        fun laneX(minute: Double): Float =
            plotLeft + (minute / spanMinutes).toFloat().coerceIn(0f, 1f) * plotWidth
        fun binX(index: Int): Float = laneX((index + 0.5) / bins * spanMinutes)

        clipRect(left = plotLeft, top = top, right = size.width, bottom = plotBottom) {
            if (filled.size >= 2) {
                val envelope = Path()
                envelope.moveTo(binX(filled.first()), yFor(highs[filled.first()].toDouble()))
                for (index in filled.drop(1)) envelope.lineTo(binX(index), yFor(highs[index].toDouble()))
                for (index in filled.reversed()) envelope.lineTo(binX(index), yFor(lows[index].toDouble()))
                envelope.close()
                drawPath(envelope, color = heart.copy(alpha = 0.16f))
            }
            // A straight polyline rather than the upstream smoothed curve: the smoothing
            // helper is a Catmull-Rom with its own overshoot rules, and at this lane's
            // height the difference is not visible while a wrong implementation would be.
            if (filled.size >= 2) {
                val mean = Path()
                mean.moveTo(binX(filled.first()), yFor(sums[filled.first()] / counts[filled.first()]))
                for (index in filled.drop(1)) mean.lineTo(binX(index), yFor(sums[index] / counts[index]))
                drawPath(mean, color = heart, style = Stroke(width = 1.6f))
            } else if (filled.size == 1) {
                val index = filled.first()
                drawCircle(
                    color = heart,
                    radius = 2f,
                    center = Offset(binX(index), yFor(sums[index] / counts[index])),
                )
            }

            // The heart-rate marker: at the cursor when the reader has moved it, otherwise
            // at now. Interpolated between samples so the dot tracks the rule smoothly
            // rather than snapping between readings. The minute comes in as a parameter
            // rather than being recomputed, so the dot and the rule cannot disagree.
            if (cursorMinute != null && cursorMinute in 0.0..spanMinutes) {
                interpolatedBpm(heartRate, cursorMinute)?.let { bpm ->
                    val centre = Offset(laneX(cursorMinute), yFor(bpm))
                    drawCircle(color = heart, radius = 3f, center = centre)
                    drawCircle(
                        color = Color.White.copy(alpha = 0.9f),
                        radius = 3f,
                        center = centre,
                        style = Stroke(width = 1f),
                    )
                }
            }
        }

        // Guides are collected rather than drawn immediately, so the *labels* can be
        // thinned after the trace: a 30 bpm range across a lane this tall still puts a
        // 40 and a 60 within a few points of each other, and two numbers on one pixel
        // read as a smear. One label at a time, each clear of the last — same rule the
        // blood-pressure readings use below.
        var lastGuideBottom = -Float.MAX_VALUE
        for ((value, y) in guides) {
            val measured = measurer.measure(value.toInt().toString(), TextStyle(fontSize = 8.sp, color = heart))
            val wanted = (y - measured.size.height / 2f)
                .coerceIn(plotTop, (plotBottom - measured.size.height).coerceAtLeast(plotTop))
            if (wanted < lastGuideBottom + 2f) continue
            lastGuideBottom = wanted + measured.size.height
            drawScale(value.toInt().toString(), wanted + measured.size.height / 2f, heart)
        }
        drawScale("bpm", plotTop - 1f, heart)
    }

    val bloodPressure = vitals.bloodPressure.filter {
        val minute = minutesBetween(start, it.date)
        minute >= 0.0 && minute <= spanMinutes
    }
    if (bloodPressure.isEmpty()) return

    var bpLow = (floor(bloodPressure.minOf { it.diastolic } / 10.0) * 10.0) - 10.0
    var bpHigh = (ceil(bloodPressure.maxOf { it.systolic } / 10.0) * 10.0) + 10.0
    if (bpHigh - bpLow < 30.0) bpHigh = bpLow + 30.0
    bpLow = maxOf(0.0, bpLow)
    fun yForPressure(value: Double): Float =
        plotTop + (1f - ((value.coerceIn(bpLow, bpHigh) - bpLow) / (bpHigh - bpLow)).toFloat()) * plotHeight

    // Every reading keeps its bar — the marks are the data. Only the *labels* thin out:
    // five readings inside forty minutes land within a few points of each other on a
    // multi-hour axis, and drawing all five prints them on top of one another. The last
    // reading is always labelled, because it is the one being asked about.
    var lastLabelRight = -Float.MAX_VALUE
    for ((index, reading) in bloodPressure.withIndex()) {
        val x = xFor(minutesBetween(start, reading.date))
        val systolicY = yForPressure(reading.systolic)
        val diastolicY = yForPressure(reading.diastolic)
        drawLine(
            color = pressure,
            start = Offset(x, systolicY),
            end = Offset(x, diastolicY),
            strokeWidth = 1.4f,
        )
        for (cap in listOf(systolicY, diastolicY)) {
            drawLine(
                color = pressure,
                start = Offset(x - 2.5f, cap),
                end = Offset(x + 2.5f, cap),
                strokeWidth = 1.4f,
            )
        }

        val text = "${reading.systolic.roundToInt()}/${reading.diastolic.roundToInt()}"
        val measured = measurer.measure(text, TextStyle(fontSize = 8.sp, color = pressure))
        val toTheRight = x + measured.size.width + 6f <= size.width
        val originX = if (toTheRight) x + 4f else x - 4f - measured.size.width
        val isLast = index == bloodPressure.lastIndex
        if (!isLast && originX <= lastLabelRight + 8f) continue
        lastLabelRight = originX + measured.size.width
        // Above its own bar, clamped into the plot: a high systolic reading would
        // otherwise print its numbers up into the caption row.
        val labelY = (systolicY - measured.size.height - 2f)
            .coerceIn(plotTop, (plotTop + plotHeight - measured.size.height).coerceAtLeast(plotTop))
        drawText(measured, topLeft = Offset(originX, labelY))
    }

    val caption = measurer.measure("mmHg", labelFont)
    drawText(
        textLayoutResult = caption,
        topLeft = Offset(size.width - caption.size.width - 2f, plotBottom + 1f),
    )
}

/**
 * Floor and ceiling for a double, named so the scale arithmetic above reads as it does
 * in Swift. The engine's own `floor`/`ceil` are not used here because these are draw-time
 * axis bounds, not model maths.
 */
private fun floor(value: Double): Double = kotlin.math.floor(value)

private fun ceil(value: Double): Double = kotlin.math.ceil(value)

/** The sample's value at [minute], linearly between the two samples that bracket it. */
private fun interpolatedBpm(heartRate: List<Pair<Double, Double>>, minute: Double): Double? {
    val first = heartRate.firstOrNull() ?: return null
    val last = heartRate.lastOrNull() ?: return null
    if (minute <= first.first) return first.second
    if (minute >= last.first) return last.second
    for (index in 1 until heartRate.size) {
        val (minuteAt, bpm) = heartRate[index]
        if (minuteAt >= minute) {
            val (previousMinute, previousBpm) = heartRate[index - 1]
            val span = minuteAt - previousMinute
            val t = if (span > 0.0) (minute - previousMinute) / span else 0.0
            return previousBpm + (bpm - previousBpm) * t
        }
    }
    return last.second
}

/**
 * The shape every clock label has, measured to size the band that holds them.
 *
 * `HH:mm` at the axis' 10sp is the tallest a tick label ever gets, and every tick is
 * that shape, so one specimen is enough to reserve the room. Measured rather than
 * written as a dp constant on purpose: the constant is what let the marker lanes drift
 * onto the axis, because it was the same 22f for both the label band and the lanes.
 */
private const val CLOCK_LABEL_SPECIMEN = "00:00"
