package glass.kagerou.piru.engine

import java.time.Instant

/**
 * One mechanism class's receptor-load trail: its combined drive over a window,
 * normalised to the user's own recent peak.
 *
 * Ported from `ToleranceStore.loadTrail` in
 * `Piru/Data/Tolerance/ToleranceStore.swift` — the instance wrapper that resolves
 * a dose log (around line 365) and the `nonisolated static` core it calls (around
 * line 1166, next to `combinedDrive` and `recentPeakDrive`).
 *
 * ## What is left to do here
 * The arithmetic already exists and is not re-implemented: [ToleranceSimulation]
 * owns `combinedDrive`, `recentPeakDrive` and the normalised sampling loop. What
 * the static core upstream adds on top of those is two things this file does —
 * turning a dose log into one class's contributors ([ToleranceReplay.prepare]), and
 * placing the samples on the wall clock, which the engine otherwise has no business
 * knowing about.
 *
 * ## Why the curve is normalised rather than absolute
 * The denominator is the user's **own** peak drive over the last three weeks, so
 * the curve clears to roughly zero as a drug leaves the body. Absolute occupancy
 * cannot do that: for a tight-`Kᵢ` target it pins near 1 for many half-lives after
 * the last dose and never comes down, which makes it useless as a "how much is
 * still loading this receptor" reading. A load of 0 therefore means "nothing
 * dosed in the reference window", and an **empty** list means something else
 * entirely — the window or the inputs were degenerate, so there is no answer.
 *
 * ## The minutes axis
 * [ToleranceReplay.SimDose.timestampMinutes] carries no origin of its own, so
 * [now] is placed on the same axis by the convention the rest of the app already
 * uses for the tolerance replay: minutes since the Unix epoch
 * (`Instant.toEpochMilli() / 60_000.0`, exactly as `ToleranceRepository` builds
 * both its doses and its `now`). Handing this function a log on any other axis
 * produces dates that are right relative to each other and wrong against the
 * clock.
 */
object LoadTrail {

    /** One sample of the trail: an absolute instant, and load as a fraction of the user's recent peak. */
    data class TrailPoint(val date: Instant, val load: Double)

    private const val NANOS_PER_MINUTE: Double = 60_000_000_000.0

    /**
     * The load trail for one class over `[now − pastHorizonMinutes, now + horizonMinutes]`.
     *
     * The points start at the window's lower edge — `now` itself when
     * [pastHorizonMinutes] is 0, which is the forward-only reading the withdrawal
     * clock wants — and step by [stepMinutes] to the upper edge inclusive.
     *
     * An empty result means the class is not driven by any in-window dose: no dose
     * at all in the lookback, a non-positive body weight, or a class no surviving
     * dose's engagement reached. It never means "the load is zero" — that is a
     * trail of zero-valued points, and the two are drawn differently on purpose.
     */
    fun loadTrail(
        doses: List<ToleranceReplay.SimDose>,
        params: Map<String, PharmacologyParameters>,
        now: Instant,
        weightKg: Double,
        receptorClass: ReceptorClasses.ReceptorClass,
        horizonMinutes: Double,
        stepMinutes: Double,
        pastHorizonMinutes: Double = 0.0,
        lookbackDays: Double = ToleranceSimulation.DEFAULT_LOOKBACK_DAYS,
    ): List<TrailPoint> {
        // Checked here rather than left to the core purely so a degenerate sampling
        // request does not pay for the whole dose-log resolve first.
        if (!(stepMinutes > 0 && horizonMinutes >= 0 && pastHorizonMinutes >= 0)) return emptyList()

        val nowMinutes = now.toEpochMilli() / 60_000.0
        val prepared = ToleranceReplay.prepare(doses, params, nowMinutes, weightKg, lookbackDays)
            ?: return emptyList()
        val work = prepared.work.firstOrNull { it.receptorClass == receptorClass } ?: return emptyList()

        val samples = ToleranceSimulation.loadTrail(
            contributors = work.contributors,
            totalMinutes = prepared.totalMinutes,
            horizonMinutes = horizonMinutes,
            stepMinutes = stepMinutes,
            pastHorizonMinutes = pastHorizonMinutes,
        )

        // The core's samples are minutes from the start of the replay window, which
        // the wrapper upstream places at `now − totalMinutes`. Offsetting from `now`
        // in nanoseconds rather than rebuilding an origin from epoch minutes keeps
        // `now`'s own precision, so a caller's sub-minute `now` round-trips exactly.
        return samples.map { sample ->
            val offsetMinutes = sample.minutes - prepared.totalMinutes
            TrailPoint(
                date = now.plusNanos((offsetMinutes * NANOS_PER_MINUTE).toLong()),
                load = sample.load,
            )
        }
    }
}
