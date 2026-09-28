package glass.kagerou.piru.engine

import glass.kagerou.piru.model.P3Color
import java.time.Instant

/**
 * The body-load trail: what is in the body, sampled across a window.
 *
 * The time-resolved counterpart of [ActiveSubstanceCalculator.compute], which
 * answers only for one instant. Ported from `BodyLevelsManager` / `BodyLevelsPlan`
 * in `Piru/Data/BodyLevels/`, and it is exactly the data `BodyLoadChart` draws —
 * one line per substance tracing its estimated in-body amount, each normalised to
 * its own peak so a 500 mg dose and a 5 mg one are both readable on one plot,
 * with the scrub readout restoring the real amount in the substance's own unit.
 *
 * ## It samples the readout rather than re-deriving it
 * Every grid point calls the same [ActiveSubstanceCalculator.compute] the "what is
 * in your body now" screen calls, so a curve and the number under it can never
 * disagree — the trail *is* that readout, swept over time. The one visible
 * consequence is that `compute`'s own 3 %-remaining floor is inherited: a
 * substance's tail steps to zero at the point it crosses the floor rather than
 * decaying smoothly into it. Against a y-axis normalised to the series' peak that
 * step is under a pixel.
 *
 * ## Why the amount is carried and not only the fraction
 * The fraction is what the chart draws; the amount is what the scrub readout
 * prints ("1.2 g", "40 mg"), and it is the number a reader actually asked for. The
 * chart normalises it away, so both have to travel together or the readout would
 * have to reconstruct a magnitude the samples already held.
 *
 * The engine carries no clock and no calendar: [now] is a parameter, so the window
 * is whatever the caller asks for and the same call on the same inputs gives the
 * same numbers however it is scheduled.
 */
object BodyLoadTrail {

    /**
     * One sampled instant of a substance's in-body amount.
     *
     * [amount] is in the series' [Series.unit]; [fraction] is that amount over
     * [Series.peak], which is what the chart plots.
     */
    data class Point(val date: Instant, val amount: Double, val fraction: Double)

    /**
     * One substance's curve across the window.
     *
     * [id] is the canonical name with its quantity family — the same identity
     * [ActiveSubstanceCalculator] groups a single sample by — so a substance logged
     * in both mg and mL is two series rather than one summed wrongly, and a
     * substance logged in both g and mg is *one* series.
     *
     * Deliberately not [ActiveSubstance.id]: that carries the unit compute() met
     * first, which can change between two calls on the same log, and a key that
     * changes mid-window splits one substance into two curves that hand off.
     *
     * [unit] is the unit of the first sample that saw the substance, kept for the
     * readout; it does not move with the samples either.
     */
    data class Series(
        val id: String,
        val displayName: String,
        val unit: String,
        val tint: P3Color,
        /** The largest amount this substance reaches inside the window — the normaliser. */
        val peak: Double,
        val points: List<Point>,
    )

    /**
     * The most samples a trail will carry, whatever the window.
     *
     * A year at an hour is 8,760 points and about as many `compute` passes, which is
     * seconds of off-main work for a chart a few hundred pixels wide. The cap trades
     * resolution the screen cannot show for a load that finishes.
     */
    const val MAXIMUM_SAMPLES: Int = 2_000

    private const val MINUTES_PER_DAY: Double = 1_440.0
    private const val MILLIS_PER_MINUTE: Double = 60_000.0

    /**
     * Sample spacing for a window of [spanMinutes], in minutes.
     *
     * Coarser as the window widens — 15 minutes over a week, twelve hours over a
     * year — so a long trace is not an unreadable comb of points, and then clamped
     * by [MAXIMUM_SAMPLES] so the total work stays bounded. Upstream's own steps,
     * kept: they are chosen so a visible curve keeps its shape at every range.
     */
    fun sampleStepMinutes(spanMinutes: Double): Double = maxOf(
        when {
            spanMinutes <= 7 * MINUTES_PER_DAY -> 15.0
            spanMinutes <= 30 * MINUTES_PER_DAY -> 60.0
            spanMinutes <= 90 * MINUTES_PER_DAY -> 180.0
            spanMinutes <= 365 * MINUTES_PER_DAY -> 720.0
            else -> 720.0
        },
        spanMinutes / MAXIMUM_SAMPLES,
    )

    /**
     * Sample every substance's in-body amount across `[now − pastMinutes, now + futureMinutes]`.
     *
     * The future half is not decoration: it is where the question "when is this out
     * of me" is answered, and it is the reason the trail runs past [now] at all.
     * Causality is the sampler's, not this function's — a sample in the future sees
     * only the doses that have already happened by then, because that is what
     * [ActiveSubstanceCalculator.compute] does with a `now` in the future.
     *
     * Every series carries a point at every grid instant, zero before its first dose
     * and after its last, so a curve starts and ends on the floor rather than
     * appearing mid-plot. A series whose peak is zero never had anything in the body
     * and is dropped. Ordered by peak, biggest first — the legend's order, and the
     * order the "now" list already uses.
     *
     * An empty result means the window or the grid was degenerate, not that nothing
     * is in the body.
     */
    fun build(
        entries: List<DoseRecord>,
        colorMap: Map<String, P3Color>,
        catalog: SubstanceCatalog,
        fallbackTint: P3Color,
        now: Instant,
        pastMinutes: Double,
        futureMinutes: Double,
        stepMinutes: Double = sampleStepMinutes(pastMinutes + futureMinutes),
    ): List<Series> {
        if (entries.isEmpty()) return emptyList()
        if (!(stepMinutes > 0) || pastMinutes < 0 || futureMinutes < 0) return emptyList()

        val grid = grid(now, pastMinutes, futureMinutes, stepMinutes)
        if (grid.isEmpty()) return emptyList()

        val accumulators = LinkedHashMap<String, Accumulator>()
        for ((index, at) in grid.withIndex()) {
            for (active in ActiveSubstanceCalculator.compute(entries, colorMap, catalog, fallbackTint, at)) {
                // Keyed on the name and the quantity family, not on `active.id` —
                // that id embeds the *displayed* unit, which compute() takes from
                // whichever dose it met first, so a substance logged in both g and mg
                // can answer with a different id at different samples. Grouping on it
                // would split one substance into two curves that hand off mid-window.
                val key = active.name + "|" + ActiveSubstanceCalculator.unitFamily(active.unit)
                val accumulator = accumulators.getOrPut(key) {
                    Accumulator(active.name, active.unit, active.tint, DoubleArray(grid.size))
                }
                accumulator.amounts[index] = active.totalRemaining
            }
        }

        return accumulators
            .map { (id, accumulator) ->
                val peak = accumulator.amounts.maxOrNull() ?: 0.0
                Series(
                    id = id,
                    displayName = accumulator.name,
                    unit = accumulator.unit,
                    tint = accumulator.tint,
                    peak = peak,
                    points = grid.mapIndexed { index, date ->
                        val amount = accumulator.amounts[index]
                        Point(
                            date = date,
                            amount = amount,
                            // Guarded rather than assumed: the series is dropped below
                            // when its peak is zero, but the division happens first.
                            fraction = if (peak > 0) amount / peak else 0.0,
                        )
                    },
                )
            }
            .filter { it.peak > 0 }
            .sortedByDescending { it.peak }
    }

    /**
     * The sample instants, oldest first and inclusive of both edges.
     *
     * Stepped in whole minutes from [now] rather than accumulated by addition, so a
     * long window cannot drift off the grid it was asked for.
     */
    private fun grid(
        now: Instant,
        pastMinutes: Double,
        futureMinutes: Double,
        stepMinutes: Double,
    ): List<Instant> {
        val startMillis = now.toEpochMilli() - (pastMinutes * MILLIS_PER_MINUTE).toLong()
        val endMillis = now.toEpochMilli() + (futureMinutes * MILLIS_PER_MINUTE).toLong()
        val stepMillis = (stepMinutes * MILLIS_PER_MINUTE).toLong()
        if (stepMillis <= 0) return emptyList()
        val out = ArrayList<Instant>()
        var millis = startMillis
        while (millis <= endMillis) {
            out += Instant.ofEpochMilli(millis)
            millis += stepMillis
        }
        return out
    }

    /** One substance's running total across the grid, and the labels its first sample carried. */
    private class Accumulator(
        val name: String,
        val unit: String,
        val tint: P3Color,
        val amounts: DoubleArray,
    )
}
