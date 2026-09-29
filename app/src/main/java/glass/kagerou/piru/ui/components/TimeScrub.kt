package glass.kagerou.piru.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant

/**
 * The draggable time cursor — one rule you move along a chart's time axis, and the
 * readout it drives.
 *
 * Ported from the scrub half of `Shared/TimelineGraphView.swift` (the rule, its
 * per-curve dots and the floating callout) and `Piru/Views/Insights/BodyLoadChart.swift`
 * (the `chartXSelection` rule and `BodyLoadReadout`), reduced to the piece the four
 * screens share. The interaction is the same everywhere it appears: drag anywhere on
 * the plot and the rule follows your finger; the readout beneath names the instant
 * and what each series is worth at it.
 *
 * ## The rule is the one that was already there
 * The colour is the theme accent, because on the journal this cursor *is* the graph's
 * "now" line — dragging it moves that line off the present. A second line was
 * considered and rejected: two vertical rules on one plot, one of them live, is a
 * question the reader has to answer before they can use the chart.
 *
 * ## Deliberately different from iOS in one place
 * Upstream clears the rule on release, so the readout is only visible while a finger
 * is down. That is unusable for the thing the readout is for — reading a number — so
 * here the cursor stays where it was put and a "back to now" affordance returns it.
 *
 * ## What the gesture claims, and why the callback is a slot
 * Every chart that carries one of these sits inside a `LazyColumn`, so a drag over
 * the plot must not eat the page's scroll. [detectHorizontalDragGestures] is the
 * detector for that: it slop-tests the **x component alone**, so a purely vertical
 * drag is never claimed and the list scrolls as usual.
 *
 * What it does *not* do is compare the two axes. A slanted drag whose sideways
 * travel passes the touch slop is claimed, and the page will not scroll for it.
 * That is the price of any horizontal drag surface — a detector that demanded
 * "mostly sideways" would refuse almost every real thumb arc, which on a phone is
 * never straight. (`ui/tools/DepotCharts.kt`'s `pinchToZoom` states the same
 * principle for its scale gesture: only a real pinch is consumed, so a finger
 * resting on the graph does not freeze the page.)
 *
 * The callback is a fresh lambda on every recomposition, so keying `pointerInput` on
 * it would restart the gesture while the user is mid-drag. It is read through
 * [rememberUpdatedState] instead, which is what makes the modifier a `@Composable`.
 *
 * ## Reaching the cursor without a finger
 * The plot is a `Canvas`, which carries no semantics, so for a screen reader the
 * entire feature used to be absent — there was no node to focus and nothing to
 * hear. [label] and [readout] put one there: the graph says what it is, the node's
 * state is the cursor's current reading, and two custom actions move the cursor one
 * [stepFraction] at a time. VoiceOver/TalkBack users open the actions from the
 * local context menu, which is why they are `CustomAccessibilityAction` rather than
 * a swipe handler — a swipe on a graph would fight the page scroll.
 *
 * The step is the caller's to give because only the caller knows its grid: the
 * journal samples a day's window, the trails sample a window that reaches into the
 * future, and one "step" has to mean one sample of whichever grid is on screen.
 *
 * A chart whose cursor can legitimately be absent (the half-life curve starts with
 * none) exposes no node while it is absent, and that is the correct behaviour rather
 * than an oversight: there is no reading to announce. Each of those callers seeds a
 * cursor where it can, so the feature is reachable without a drag.
 */
@Composable
fun Modifier.timeScrub(
    enabled: Boolean = true,
    /**
     * What the plot is, for a screen reader. Read from resources by the caller, so
     * this file stays free of screen names.
     */
    label: String? = null,
    /**
     * The cursor's current reading — "Cursor at 14:32" — or null when the cursor is
     * absent and there is nothing to announce.
     */
    /**
     * The cursor's current reading — "Cursor at 14:32" — or null when the cursor is
     * absent and there is nothing to announce.
     */
    readout: String? = null,
    /**
     * Where the cursor currently sits, as a fraction of the window, or null when it
     * is absent.
     *
     * This is a second value rather than something derivable from [readout] because
     * the step actions need a *number* to step from, and this modifier parses no
     * formatted dates: each caller already holds its cursor in the unit its own
     * readout needs (an instant, a fraction, minutes since a dose), and converting to
     * a fraction here would mean guessing at that unit.
     */
    cursorFraction: Float? = null,
    /**
     * How far one labelled action moves the cursor, as a fraction of the window.
     * Ignored unless a [label], a [readout] and a [cursorFraction] are all present.
     */
    stepFraction: Float = 0.02f,
    onFraction: (Float) -> Unit,
): Modifier {
    val latest by rememberUpdatedState(onFraction)
    if (!enabled) return this
    // Hoisted above the `semantics { }` block, which is not a composable lambda.
    val content = label
    val state = readout
    val at = cursorFraction
    val backLabel = stringResource(R.string.scrub_cursor_back)
    val forwardLabel = stringResource(R.string.scrub_cursor_forward)
    val hint = stringResource(R.string.scrub_cursor_hint)

    val scrubbed = this
        .pointerInput(Unit) {
            detectHorizontalDragGestures { change, _ ->
                change.consume()
                latest(fractionOf(change.position.x, size.width.toFloat()))
            }
        }
        .pointerInput(Unit) {
            // A tap places the cursor exactly, which a drag cannot do on a plot this
            // wide — the same "tap selects" affordance the receptor-load chart uses.
            detectTapGestures { offset ->
                latest(fractionOf(offset.x, size.width.toFloat()))
            }
        }

    if (content == null || state == null || at == null) return scrubbed

    return scrubbed.semantics(mergeDescendants = true) {
        // The hint rides in the description rather than in a separate field: Compose
        // semantics has no hint, and a reader that hears "dose effect over today"
        // and then two unexplained actions has been told what the graph is but not
        // what it is for.
        contentDescription = if (state.isNotEmpty()) "$content. $state. $hint" else "$content. $hint"
        stateDescription = state
        // The direction is the axis' own: the fraction runs left to right, so
        // "forward" is later in time on every chart that carries this cursor.
        customActions = listOf(
            CustomAccessibilityAction(backLabel) {
                latest((at - stepFraction).coerceIn(0f, 1f))
                true
            },
            CustomAccessibilityAction(forwardLabel) {
                latest((at + stepFraction).coerceIn(0f, 1f))
                true
            },
        )
    }
}

/**
 * A pointer's x as a fraction of the plot, clamped to `0..1`.
 *
 * The clamp is load-bearing rather than defensive: a drag that leaves the node keeps
 * reporting positions outside it, and an unclamped fraction becomes a negative or
 * past-the-end instant that the readout would print as fact — "after -4.2 h,
 * 100 % remaining" for a finger just off the left edge.
 */
private fun fractionOf(x: Float, width: Float): Float =
    (x / width.coerceAtLeast(1f)).coerceIn(0f, 1f)

/**
 * The rule itself: a dashed vertical line, drawn in the theme accent.
 *
 * The dash pattern and stroke width are the journal graph's existing "now" line's,
 * so a chart that gains a cursor does not also gain a new visual language. Does
 * nothing when [x] falls outside the draw area, so a caller can pass the cursor's
 * position unconditionally.
 */
fun DrawScope.drawScrubRule(
    x: Float,
    color: Color,
    top: Float = 0f,
    bottom: Float = size.height,
    strokeWidth: Float = 2f,
) {
    if (x < 0f || x > size.width) return
    drawLine(
        color = color,
        start = Offset(x, top),
        end = Offset(x, bottom),
        strokeWidth = strokeWidth,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
    )
}

/** The instant [fraction] of the way across the window `[from, to]`. */
fun scrubInstantAt(fraction: Float, from: Instant, to: Instant): Instant {
    val spanMillis = (to.toEpochMilli() - from.toEpochMilli()).toDouble()
    val offset = (spanMillis * fraction.coerceIn(0f, 1f).toDouble()).toLong()
    return Instant.ofEpochMilli(from.toEpochMilli() + offset)
}

/** Where [instant] falls across the window `[from, to]`, in `0..1`. A degenerate window reads 0. */
fun scrubFractionOf(instant: Instant, from: Instant, to: Instant): Float {
    val spanMillis = (to.toEpochMilli() - from.toEpochMilli()).toDouble()
    if (spanMillis <= 0) return 0f
    val fraction = (instant.toEpochMilli() - from.toEpochMilli()) / spanMillis
    return fraction.toFloat().coerceIn(0f, 1f)
}

/**
 * The value a sampled series has at [at], by linear interpolation between the two
 * samples that bracket it.
 *
 * That is the value the plot draws there: a trail's path is straight segments
 * between its samples, so reading the *nearest* sample instead puts a number under
 * the cursor that the line does not pass through — invisible on a dense grid, and a
 * lie on a coarse one, which is exactly where a long window lands (twelve hours a
 * sample over a year).
 *
 * Null outside the series' own span: a substance that had not been taken yet, or one
 * whose tail has left the window, has no value there, and reporting the nearest
 * endpoint would name a number the line never reaches.
 *
 * [timeOf] and [valueOf] rather than a projected pair list, and `inline` rather than
 * a lambda-taking function, because this runs once per series per pointer move and
 * the grids reach two thousand points: a projection would allocate a list per frame,
 * and a keyed `remember` would compare one per frame. Binary search for the same
 * reason — the scan this replaces was the drag's dominant cost.
 */
inline fun <T> List<T>.interpolateAt(
    at: Instant,
    timeOf: (T) -> Instant,
    valueOf: (T) -> Double,
): Double? {
    if (isEmpty()) return null
    val target = at.toEpochMilli()
    if (target < timeOf(this[0]).toEpochMilli() || target > timeOf(this[size - 1]).toEpochMilli()) return null

    var low = 0
    var high = size - 1
    while (low < high) {
        val mid = (low + high) / 2
        if (timeOf(this[mid]).toEpochMilli() < target) low = mid + 1 else high = mid
    }
    val upperTime = timeOf(this[low]).toEpochMilli()
    val upperValue = valueOf(this[low])
    if (low == 0 || upperTime == target) return upperValue

    val lowerTime = timeOf(this[low - 1]).toEpochMilli()
    val spanMillis = (upperTime - lowerTime).toDouble()
    // Duplicate timestamps on the grid would divide by zero; the later sample wins.
    if (spanMillis <= 0.0) return upperValue
    val lowerValue = valueOf(this[low - 1])
    val t = (target - lowerTime) / spanMillis
    return lowerValue + (upperValue - lowerValue) * t
}

/**
 * One line of a readout: the series it names, what it is worth at the cursor, and
 * the dot that ties the line back to its curve. A null [tint] draws no dot — for a
 * readout with a single row, where there is nothing to tell apart.
 */
data class ScrubRow(val label: String, val value: String, val tint: Color? = null)

/**
 * The card under a scrubbed chart: when the cursor is, and what is there.
 *
 * Deliberately the same shape as the receptor-load chart's own readout — a bold
 * instant, then a row per series — so moving between screens that now all have a
 * cursor does not mean re-learning what the block under the chart means.
 *
 * [onReset] draws the "back to now" affordance; pass null on a chart whose cursor is
 * not anchored to the present — the half-life decay curve is a single dose's own
 * arc, with no "now" on it to return to, so a reset there would name a place that
 * does not exist.
 *
 * [emptyText] is optional because not every cursor can be empty: the half-life
 * readout always has its one row, and a string for a state it can never reach is
 * copy nobody will ever read.
 */
@Composable
fun ScrubReadout(
    title: String,
    rows: List<ScrubRow>,
    modifier: Modifier = Modifier,
    emptyText: String? = null,
    onReset: (() -> Unit)? = null,
) {
    PiruCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (onReset != null) {
                    Text(
                        stringResource(R.string.scrub_reset),
                        style = MaterialTheme.typography.labelSmall,
                        color = PiruTheme.colors.accent,
                        modifier = Modifier.clip(CircleShape).clickable(onClick = onReset).padding(4.dp),
                    )
                }
            }

            for (row in rows) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (row.tint != null) {
                        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(row.tint))
                    }
                    Text(
                        row.label,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        row.value,
                        style = MaterialTheme.typography.labelSmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }

            if (rows.isEmpty() && emptyText != null) {
                Text(
                    emptyText,
                    style = MaterialTheme.typography.labelSmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
    }
}
