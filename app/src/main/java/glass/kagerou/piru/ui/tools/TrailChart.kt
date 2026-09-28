package glass.kagerou.piru.ui.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import glass.kagerou.piru.R
import glass.kagerou.piru.ui.components.drawScrubRule
import glass.kagerou.piru.ui.components.scrubInstantAt
import glass.kagerou.piru.ui.components.timeScrub
import glass.kagerou.piru.ui.labels.appLocale
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One sampled point of a trail: when, and how far up its own scale the series was.
 *
 * [fraction] is already normalised to that series' peak — every line on this chart
 * is a share of its own maximum, never an absolute amount, because a 500 mg dose and
 * a 5 mg one have to share one plot. The absolute magnitude belongs in the readout,
 * which the caller renders from the same samples.
 */
internal data class TrailChartPoint(val date: Instant, val fraction: Double)

/**
 * One line: which series it is, how it is coloured, and where it is worth what.
 *
 * Carries no label on purpose — the plot draws lines and no legend, and the names
 * live in the readout, which is the only place a reader can match a colour to a
 * name anyway.
 */
internal data class TrailChartSeries(
    val id: String,
    val tint: Color,
    val points: List<TrailChartPoint>,
)

/**
 * A multi-series normalised trail with a draggable time cursor.
 *
 * The shared chart behind the body-load and receptor-load screens. Both draw the
 * same thing — one line per substance or mechanism class, each as a share of its own
 * peak, over a window that reaches into the future — and both want the same
 * interaction, so the drawing and the gesture live here once. What differs between
 * them is what the samples *mean*, and that is why the readout is not here: the
 * cursor is hoisted ([selected] / [onSelect]) and the caller renders its own
 * readout, which is where a drug's milligrams and a receptor's percentage part ways.
 *
 * ## Why the cursor is hoisted rather than owned
 * The readout has to sit outside the chart and survive the chart's recomposition,
 * and it needs the same instant the rule is drawn at. One value, held by the caller,
 * passed down twice — which is also upstream's shape (`BodyLoadChart` takes a
 * `@Binding selectedDate`; `BodyLoadReadout` takes the date).
 *
 * [axisCaption] names what the y-axis means for *this* caller, because "share of its
 * own peak" is only true of one of the two.
 */
@Composable
internal fun TrailChart(
    series: List<TrailChartSeries>,
    selected: Instant?,
    onSelect: (Instant) -> Unit,
    windowFrom: Instant,
    windowTo: Instant,
    axisCaption: String,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
) {
    val gridInk = PiruTheme.colors.secondaryLabel.copy(alpha = 0.25f)
    val labelInk = PiruTheme.colors.secondaryLabel
    // Read outside the draw scope: a `@Composable` theme read cannot happen inside
    // `Canvas { … }`, which is a plain lambda.
    val accent = PiruTheme.colors.accent
    val measurer = rememberTextMeasurer()
    val axisPattern = stringResource(R.string.datefmt_day_month)
    val dateLocale = appLocale()

    val spanMillis = (windowTo.toEpochMilli() - windowFrom.toEpochMilli()).coerceAtLeast(1L).toDouble()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(
            modifier = modifier
                .fillMaxWidth()
                .height(height)
                .timeScrub { fraction -> onSelect(scrubInstantAt(fraction, windowFrom, windowTo)) },
        ) {
            fun xFor(date: Instant): Float =
                (((date.toEpochMilli() - windowFrom.toEpochMilli()) / spanMillis) * size.width).toFloat()

            // The y-axis is fixed 0…1 by definition: every point already arrived
            // normalised, so there is no scale to derive.
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
                if (item.points.isEmpty()) continue
                val path = Path()
                var started = false
                for (point in item.points) {
                    val x = xFor(point.date)
                    val y = size.height * (1f - point.fraction.toFloat().coerceIn(0f, 1f))
                    if (started) path.lineTo(x, y) else path.moveTo(x, y)
                    started = true
                }
                drawPath(path, color = item.tint, style = Stroke(width = 3f))
            }

            selected?.let { at ->
                drawScrubRule(x = xFor(at), color = accent)
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                shortTrailDate(windowFrom, axisPattern, dateLocale),
                style = MaterialTheme.typography.labelSmall,
                color = PiruTheme.colors.secondaryLabel,
            )
            Text(
                axisCaption,
                style = MaterialTheme.typography.labelSmall,
                color = PiruTheme.colors.secondaryLabel,
            )
            Text(
                shortTrailDate(windowTo, axisPattern, dateLocale),
                style = MaterialTheme.typography.labelSmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}

/**
 * A cursor readout's heading: the day and the clock together.
 *
 * These trails span days, so the hour alone — which is what the journal's day graph
 * shows, correctly, because it is one day — would name an instant that happened
 * thirty times over the window.
 */
@Composable
internal fun trailReadoutTitle(instant: Instant): String =
    DateTimeFormatter.ofPattern(
        stringResource(R.string.datefmt_day_month_time),
        appLocale(),
    ).format(instant.atZone(ZoneId.systemDefault()))
