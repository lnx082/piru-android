package glass.kagerou.piru.ui.journal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import glass.kagerou.piru.engine.ActiveSubstanceState
import glass.kagerou.piru.engine.DoseMarker
import glass.kagerou.piru.engine.TimelineCurveModel
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.ui.theme.toComposeColor
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The dose-effect graph.
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
 * ## What is still missing from the port
 * Upstream's renderer is 1,869 lines and this draws its core: the curve lanes,
 * the tick ladder, the marker lanes and the now-line. Not here — the scrub
 * interaction, the conflict bands, milestone ribbons, the phase-band underlay and
 * the pinch/pan gestures. They are layout and gesture work rather than model work,
 * which is why they can follow without touching anything below.
 */
@Composable
fun TimelineGraph(
    states: List<ActiveSubstanceState>,
    markers: List<DoseMarker>,
    currentTime: Instant,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    /** Merge a substance's redoses into one curve. On for the day view; the session graph does the same. */
    stackRedoses: Boolean = true,
    /** Clamp the frame to a day rather than letting a long-acting dose stretch it. */
    dayBounded: Boolean = true,
    sampleCount: Int = 260,
) {
    if (states.isEmpty() && markers.isEmpty()) {
        EmptyGraph(height, modifier)
        return
    }

    val colors = PiruTheme.colors
    val measurer = rememberTextMeasurer()
    val zone = remember { ZoneId.systemDefault() }

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

    // The framed window: where the data is still doing something. The full
    // scrollable extent is `rawDataTail`, which the scrub gesture would pan to.
    val spanMinutes = maxOf(derived.rawActivityTail, 60.0)
    val start = derived.earliestDose
    val tickMinutes = TimelineCurveModel.intervalForSpan(spanMinutes)

    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        // The gutter is sized from the widest marker label rather than fixed: a
        // truncated substance name is worse than a narrower plot, and the labels
        // are the only thing that makes a row of dots mean anything.
        val markerLanes = markers.groupBy { it.substanceName.lowercase() }.values.toList()
        val widestLabel = markerLanes.maxOfOrNull { lane ->
            measurer.measure(
                lane.first().substanceName,
                TextStyle(fontSize = 10.sp, color = colors.secondaryLabel),
            ).size.width
        } ?: 0
        val leftGutter = 0f
        val rightGutter = maxOf(44f, widestLabel + 10f)
        val topGutter = 14f
        val bottomGutter = 22f + markerLanes.size * 12f
        val plotWidth = size.width - leftGutter - rightGutter
        val plotHeight = size.height - topGutter - bottomGutter
        if (plotWidth <= 0 || plotHeight <= 0) return@Canvas

        val baseline = topGutter + plotHeight
        fun xFor(minutes: Double): Float = leftGutter + (minutes / spanMinutes).toFloat() * plotWidth
        fun yFor(value: Double): Float = baseline - (value * derived.yNormalization).toFloat() * plotHeight

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
                    y = baseline + 4f,
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
        var laneIndex = 0
        for (group in markerLanes) {
            val y = baseline + 18f + laneIndex * 12f
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

        // The now-line, only when now is inside the window — a line pinned to the
        // edge would claim the present is somewhere it is not.
        val nowMinutes = minutesBetween(start, currentTime)
        if (nowMinutes in 0.0..spanMinutes) {
            val x = xFor(nowMinutes)
            drawLine(
                color = colors.accent,
                start = Offset(x, topGutter),
                end = Offset(x, baseline),
                strokeWidth = 2f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
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

@Composable
private fun EmptyGraph(height: Dp, modifier: Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        Text(
            "No doses to draw yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = PiruTheme.colors.secondaryLabel,
        )
    }
}

private fun minutesBetween(from: Instant, to: Instant): Double =
    Duration.between(from, to).toMillis() / 60_000.0

