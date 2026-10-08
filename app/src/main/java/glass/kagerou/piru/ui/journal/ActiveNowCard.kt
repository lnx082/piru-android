package glass.kagerou.piru.ui.journal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.engine.ActiveSubstanceState
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import glass.kagerou.piru.ui.theme.toComposeColor
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * Everything pharmacologically active right now, in one card.
 *
 * Ported from `ActiveNowCard`, which its own file calls "the Journal's **state** surface". The feed reads plan →
 * state → log: `MyMedsCard` is the plan, the day list is the log, and this is the single answer to "what is in
 * effect?" — the one thing on the screen that changes without the user doing anything.
 *
 * ## The two shapes, and why they differ
 * A **lone** substance gets a phase bar with a countdown and no graph: the bar alone tells that story, and a curve
 * for one compound is a graph for its own sake. Two or more distinct substances get a window of the continuous
 * timeline, because overlapping curves need a picture where one bar cannot say it.
 *
 * The rule keys on **distinct substances** rather than on the number of active states — a single substance redosed
 * three times is still one compound, and three bands of it would be unreadable. That decision lives in
 * [ActiveNow], where it is tested.
 *
 * ## The countdown ticks without a per-frame timer
 * The card re-reads the clock once a minute while it is on screen, which is what upstream's
 * `TimelineView(.periodic(by: 60))` does. A phase bar whose countdown is frozen is worse than no countdown: it
 * says something false with confidence.
 */
@Composable
fun ActiveNowCard(
    states: List<ActiveSubstanceState>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!ActiveNow.worthShowing(states)) return

    // One tick a minute. `remember` on the states so a new log resets it rather than carrying a stale instant.
    var now by remember(states) { mutableStateOf(Instant.now()) }
    LaunchedEffect(states) {
        while (true) {
            now = Instant.now()
            delay(60_000)
        }
    }

    val headline = ActiveNow.headline(states) ?: return
    val showsGraph = ActiveNow.showsGraph(states)

    PiruCard(modifier = modifier.fillMaxWidth(), onClick = onOpen) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (headline) {
                        is ActiveNow.Headline.Single -> headline.substance
                        is ActiveNow.Headline.Multiple ->
                            stringResource(R.string.journal_active_now_count, headline.substances)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.journal_active_now),
                    style = MaterialTheme.typography.labelLarge,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }

            if (headline is ActiveNow.Headline.Single) {
                val state = states.first()
                PhaseBar(state = state, now = now)
            } else {
                // Dots and names, one row per substance: the colour is what ties a row to its curve below.
                for (state in states) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(color = state.tint.toComposeColor(), shape = CircleShape),
                        )
                        Text(state.substanceName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (showsGraph) {
                ActiveWindow(states = states, now = now)
            }
        }
    }
}

/**
 * A phase bar for one substance, with the time left in the current phase beside it.
 *
 * The phases are drawn from the state's own boundaries rather than recomputed: they are what the engine used to
 * decide the substance is active at all, so a bar drawn from anything else could disagree with the card's own
 * presence.
 */
@Composable
private fun PhaseBar(state: ActiveSubstanceState, now: Instant) {
    val elapsed = java.time.Duration.between(state.doseTimestamp, now).toMillis() / 60_000.0
    val total = state.totalMinutes.coerceAtLeast(1.0)
    val fraction = (elapsed / total).coerceIn(0.0, 1.0)
    val accent = state.tint.toComposeColor()
    val track = PiruTheme.colors.secondaryLabel.copy(alpha = 0.18f)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.fillMaxWidth().height(8.dp)) {
            Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                drawRect(color = track, size = size)
                drawRect(
                    color = accent,
                    topLeft = Offset.Zero,
                    size = Size(size.width * fraction.toFloat(), size.height),
                )
            }
        }
        Text(
            // The amount and route, which is what the bar is a picture of.
            stringResource(
                R.string.journal_active_now_dose,
                trimNumber(state.amount),
                state.unit,
                state.route,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = PiruTheme.colors.secondaryLabel,
        )
    }
}

/**
 * The continuous timeline's window over the active states.
 *
 * Hand-drawn from each state's own phase boundaries: the app has no charting dependency, and what this needs is
 * one closed curve per substance over a shared axis. Each curve is a trapezoid through its phases — onset rising,
 * peak flat, offset falling — which is the same shape the engine's phase boundaries describe and therefore cannot
 * disagree with the bars above.
 */
@Composable
private fun ActiveWindow(states: List<ActiveSubstanceState>, now: Instant) {
    val start = states.minOf { it.doseTimestamp }
    val end = states.maxOf { it.doseTimestamp.plusSeconds((it.totalMinutes * 60).toLong()) }
    val span = java.time.Duration.between(start, end).toMillis().coerceAtLeast(1L).toDouble()
    val nowFraction = (java.time.Duration.between(start, now).toMillis() / span).coerceIn(0.0, 1.0)
    val rule = PiruTheme.colors.secondaryLabel.copy(alpha = 0.4f)

    Box(modifier = Modifier.fillMaxWidth().height(72.dp)) {
        Canvas(Modifier.fillMaxWidth().height(72.dp)) {
            for (state in states) {
                // The phase ends as fractions of the shared axis, from this state's own dose.
                val offset = java.time.Duration.between(start, state.doseTimestamp).toMillis()
                fun at(minutes: Double): Float =
                    ((offset + minutes * 60_000.0) / span).toFloat().coerceIn(0f, 1f)

                val height = state.doseIntensity.toFloat().coerceIn(0.05f, 1f)
                val path = Path().apply {
                    moveTo(at(0.0) * size.width, size.height)
                    lineTo(at(state.onsetEndMinutes) * size.width, size.height * (1f - height * 0.55f))
                    lineTo(at(state.comeupEndMinutes) * size.width, size.height * (1f - height * 0.85f))
                    lineTo(at(state.peakEndMinutes) * size.width, size.height * (1f - height))
                    lineTo(at(state.offsetEndMinutes) * size.width, size.height * (1f - height * 0.5f))
                    lineTo(at(state.totalMinutes) * size.width, size.height)
                }
                drawPath(path, color = state.tint.toComposeColor(), style = Stroke(width = 2f))
            }
            // The "now" rule, so the curves are read against when the reader is.
            drawLine(
                color = rule,
                start = Offset(size.width * nowFraction.toFloat(), 0f),
                end = Offset(size.width * nowFraction.toFloat(), size.height),
                strokeWidth = 1f,
            )
        }
    }
}

/** A number without a trailing `.0`, which the amount columns carry as `Double`s. */
private fun trimNumber(value: Double): String {
    val rounded = Math.round(value * 10.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        String.format(java.util.Locale.ROOT, "%.1f", rounded)
    }
}
