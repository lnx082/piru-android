package glass.kagerou.piru.engine

import glass.kagerou.piru.model.P3Color
import java.time.Duration
import java.time.Instant

/**
 * Evaluates the journal's dose-effect curves over an **arbitrary time window** —
 * the engine behind the continuous timeline ribbon.
 *
 * Ported from `Shared/Engines/TimelineWindowEvaluator.swift`.
 *
 * Time is continuous; sessions are an analysis artifact. Because every curve is
 * model-evaluated — a phase-shaped bell per dose, Hill-merged per substance —
 * a window's plot is simply the same sum evaluated over `[start, end]` for every
 * dose whose activity window intersects it. This reuses the session math
 * verbatim: no pharmacology is re-derived here.
 *
 * ## Two deliberate deviations from the session display path
 * Both exist for cross-window consistency.
 *
 * **Per-dose extent clipping.** A dose contributes only inside
 * `[timestamp, timestamp + curveExtent]`. The session renderer draws exactly
 * that, but its underlying sum would still carry a sub-two-percent Gaussian tail
 * past the extent. Clipping makes the evaluated value a **pure function of
 * absolute time** — independent of which window it is sampled through — so
 * adjacent tiles agree exactly at their shared boundary, and culling
 * out-of-window doses never changes a value.
 *
 * **No amplitude compression, no per-window normalization.** Values are the raw
 * Hill-merged intensity in `[0, 1)`. Compression and y-scaling depend on the
 * *whole* dose set on screen, so they belong to the view's shared y-scale policy
 * rather than to one window's samples — baking them in here would make tile
 * seams visible.
 */
object TimelineWindowEvaluator {

    /**
     * Samples per evaluated window, endpoints inclusive. 121 across a six-hour
     * tile is one sample every three minutes — smooth at any plausible tile
     * width.
     */
    const val DEFAULT_SAMPLE_COUNT: Int = 121

    /**
     * One substance-route group's sampled curve over the window.
     *
     * @param key the grouping key (`name|route`, lowercased) — matching
     *   [TimelineCurveModel.stackedGroups] so redoses share a curve while
     *   distinct routes stay separate, exactly like the session graph.
     * @param values raw Hill-merged intensity at each of the window's uniformly
     *   spaced instants, endpoints inclusive.
     * @param doseTimes timestamps of this group's doses that fall inside the
     *   window — the baseline tick positions.
     */
    data class Series(
        val key: String,
        val name: String,
        val tint: P3Color,
        val values: List<Double>,
        val doseTimes: List<Instant>,
    )

    /** Everything a view needs to draw one window. */
    data class WindowPlot(
        val start: Instant,
        val end: Instant,
        val sampleCount: Int,
        val series: List<Series>,
        /** The highest sample across all series; zero when the window is silent. */
        val peakValue: Double,
    )

    /**
     * The absolute interval a dose's curve occupies: from the dose to the point
     * the drawn curve returns to baseline.
     */
    fun activityInterval(dose: ActiveSubstanceState): Interval {
        val extentMinutes = TimelineCurveModel.curveExtent(dose)
        return Interval(dose.doseTimestamp, extentMinutes * 60)
    }

    /**
     * The interval a dose's curve is actually *visible* over when drawn beside
     * [peers] — [activityInterval] trimmed by [TimelineCurveModel.visibleExtent].
     *
     * Use this to **size** a window; use [activityInterval] to decide **which
     * doses** a window must evaluate. They are deliberately different questions:
     * culling with the trimmed interval would drop a dose that still contributes
     * a sliver, while sizing with the untrimmed one leaves dead axis.
     */
    fun visibleInterval(dose: ActiveSubstanceState, peers: List<ActiveSubstanceState>): Interval {
        val peerMagnitude = peers.maxOfOrNull { it.doseMagnitude } ?: dose.doseMagnitude
        val minutes = TimelineCurveModel.visibleExtent(dose, peerMagnitude)
        return Interval(dose.doseTimestamp, minutes * 60)
    }

    /**
     * The subset of [doses] whose activity window intersects `[start, end]`.
     *
     * Doses outside contribute exactly zero — see the clipping note — so dropping
     * them changes nothing. This is what a memoization key is built from: a tile
     * only re-renders when a dose *it can see* changes.
     */
    fun relevantDoses(
        doses: List<ActiveSubstanceState>,
        start: Instant,
        end: Instant,
    ): List<ActiveSubstanceState> {
        if (end <= start) return emptyList()
        val window = Interval.between(start, end)
        return doses.filter { activityInterval(it).intersects(window) }
    }

    /**
     * Evaluate every dose curve over `[start, end]`, producing one sampled series
     * per (substance, route) group — the same grouping, superposition and Hill
     * link as the session graph's stacked rendering.
     *
     * Sample instants are quantized to the millisecond, because that is what
     * [Instant] carries. On the round-number windows this is used with — a
     * six-hour tile in 121 steps is one every three minutes — the offsets land on
     * exact milliseconds, so nothing is lost; it would only show up on a span that
     * divides unevenly.
     */
    fun evaluate(
        doses: List<ActiveSubstanceState>,
        start: Instant,
        end: Instant,
        sampleCount: Int = DEFAULT_SAMPLE_COUNT,
    ): WindowPlot {
        val count = maxOf(sampleCount, 2)
        if (end <= start) {
            return WindowPlot(start, end, count, emptyList(), 0.0)
        }

        val relevant = relevantDoses(doses, start, end)
        val groups = TimelineCurveModel.stackedGroups(relevant)
        val spanMillis = Duration.between(start, end).toMillis()

        val series = mutableListOf<Series>()
        var peak = 0.0

        for (group in groups) {
            val first = group.firstOrNull() ?: continue
            val extents = group.map { TimelineCurveModel.curveExtent(it) }

            val values = DoubleArray(count)
            for (i in 0 until count) {
                val t = start.plusMillis((spanMillis.toDouble() * i / (count - 1)).toLong())
                val v = intensity(group, extents, t)
                values[i] = v
                peak = maxOf(peak, v)
            }

            val doseTimes = group.map { it.doseTimestamp }
                .filter { it >= start && it <= end }
                .sorted()

            series += Series(
                key = "${first.substanceName.lowercase()}|${first.route.lowercase()}",
                name = first.substanceName,
                tint = first.tint,
                values = values.toList(),
                doseTimes = doseTimes,
            )
        }

        return WindowPlot(start, end, count, series, peak)
    }

    /**
     * The group's merged intensity at an absolute instant: linear dose
     * superposition through one saturating Hill link, keyed by absolute time,
     * with each dose clipped to its own curve extent so the value matches what
     * the session actually draws and is independent of the window.
     */
    fun intensity(
        group: List<ActiveSubstanceState>,
        extents: List<Double>,
        at: Instant,
    ): Double {
        var sum = 0.0
        for ((index, dose) in group.withIndex()) {
            val localMinutes = Duration.between(dose.doseTimestamp, at).toMillis() / 60_000.0
            if (localMinutes < 0 || localMinutes > extents[index]) continue
            sum += dose.doseMagnitude * TimelineCurveModel.intensity(localMinutes, dose)
        }
        return TimelineCurveModel.hill(sum)
    }

    /**
     * A time interval carrying an exact fractional duration.
     *
     * Deliberately stores a duration rather than two instants: a curve extent is
     * a fractional number of minutes, and deriving it back from two [Instant]s
     * would quantize it to the millisecond. The failure mode is subtle — the
     * interval still *looks* right, but `duration` stops matching the extent it
     * was built from, which is exactly what a spec asserting on it would catch.
     *
     * Intersection is inclusive at both ends, which is what the tile-boundary
     * agreement depends on.
     */
    data class Interval(val start: Instant, val durationSeconds: Double) {
        val end: Instant get() = start.plusMillis((durationSeconds * 1000).toLong())

        /** True when the two intervals share at least one instant. */
        fun intersects(other: Interval): Boolean = start <= other.end && other.start <= end

        companion object {
            fun between(start: Instant, end: Instant) =
                Interval(start, Duration.between(start, end).toMillis() / 1000.0)
        }
    }
}

/** This instant plus [minutes], which may be fractional. */
private fun Instant.plusMinutes(minutes: Double): Instant =
    plusMillis((minutes * 60_000).toLong())
