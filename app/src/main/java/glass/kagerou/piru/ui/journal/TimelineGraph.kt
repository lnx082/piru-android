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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import glass.kagerou.piru.R
import glass.kagerou.piru.engine.ActiveSubstanceState
import glass.kagerou.piru.engine.DoseMarker
import glass.kagerou.piru.engine.TimelineCurveModel
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
        val bottomGutter = clockBand + (markerLaneGap + markerLaneHeight) * markerLanes.size
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
                                y = baseline + (clockBand - measured.size.height) / 2f,
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
                    // Placed from the canvas's own bottom edge and upward, which is the half
                    // of the fix that keeps them off the clock labels: lane 0 sits lowest and
                    // the clock band is the only thing between the last lane and the plot.
                    var laneIndex = 0
                    for (group in markerLanes) {
                        val y = size.height - laneIndex * (markerLaneGap + markerLaneHeight) - markerLaneHeight / 2f
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
 * The shape every clock label has, measured to size the band that holds them.
 *
 * `HH:mm` at the axis' 10sp is the tallest a tick label ever gets, and every tick is
 * that shape, so one specimen is enough to reserve the room. Measured rather than
 * written as a dp constant on purpose: the constant is what let the marker lanes drift
 * onto the axis, because it was the same 22f for both the label band and the lanes.
 */
private const val CLOCK_LABEL_SPECIMEN = "00:00"
