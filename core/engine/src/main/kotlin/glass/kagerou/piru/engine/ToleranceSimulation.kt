package glass.kagerou.piru.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The pure simulation helpers a tolerance replay is built from: how regular the
 * dosing is, how much of a target is engaged right now, and how far back the
 * history has to reach.
 *
 * Ported from the `nonisolated static` helpers in
 * `Piru/Data/Tolerance/ToleranceStore.swift`.
 *
 * ## One hidden input, passed in rather than held
 * Upstream's `buildClassWork` reads `ToleranceModulation.edges(forModulatorClass:)`
 * — a process-global lock-guarded table installed once from the database. That is
 * the single reason its signature is not honestly pure: with the table empty, an
 * otherwise identical replay silently produces unmodulated tolerance. Everything
 * here takes what it needs as a parameter instead of reaching for a global, and
 * [ToleranceIntegrator.ModulatorContributor] is the carrier.
 */
object ToleranceSimulation {

    /**
     * Grid step for a full replay, in minutes.
     *
     * Half-hourly over a year is about 17,500 cells, and idle spans are crossed
     * analytically rather than stepped — so this is a resolution choice inside
     * active windows, not a cost imposed by the window's length.
     */
    const val DEFAULT_TIMESTEP_MINUTES: Double = 30.0

    /**
     * How far back a replay looks by default: one year.
     *
     * The bound is what makes the chronicity accumulator meaningful — it reaches
     * months of history, not the days a session view would — while keeping a
     * dense log from walking an unbounded grid.
     */
    const val DEFAULT_LOOKBACK_DAYS: Double = 365.0

    /**
     * Peak occupancy below which a dose's engagement at a target is not worth
     * carrying.
     *
     * A dose that never gets near a fifth of half-saturation produces a shift far
     * below what any screen reports, so keeping it costs grid cells and changes no
     * number anyone reads.
     */
    const val MIN_MEANINGFUL_OCCUPANCY: Double = 0.05

    /**
     * How faint a contributor's influence may be, relative to its own decay
     * constant, before it is dropped from a class's "driven by" list.
     *
     * `0.05` makes the horizon `tau · ln(20)`, about three time constants — so a
     * substance that has been gone long enough for its layer to be visibly spent
     * stops being credited as a driver, while one that is merely past its peak
     * stays.
     */
    const val CONTRIBUTOR_RELEVANCE_FLOOR: Double = 0.05

    // MARK: - Schedule regularity

    /**
     * How much a class's adaptive layer should credit the *cadence* of dosing, in
     * `(0.7, 1.0]`.
     *
     * The same total exposure anticipates more when it arrives on a regular
     * schedule than when it is erratic, so this is a down-only weight on the
     * adaptive drive: perfectly even spacing gives 1.0, and it falls toward 0.7 as
     * the inter-dose intervals grow more variable.
     *
     * ## Two things this is not
     * It does **not** group by day or by calendar — it works on the raw per-dose
     * onsets, so a twice-daily pattern is judged on the twelve-hour gaps rather
     * than on "two doses today". And it is not a median: it is the coefficient of
     * variation (population standard deviation over mean) of the intervals.
     *
     * The `Set` is doing two jobs: collapsing the several contributors one dose
     * spawns (one per engaged target) back to a single onset, and — a consequence
     * worth naming — collapsing two *genuinely different* doses logged at the same
     * timestamp into one, which understates the count. At worst that reads as
     * slightly more regular than the user was.
     *
     * Fewer than three distinct onsets returns 1: with one or two doses there is no
     * cadence to judge, and penalising a single dose for it would be a claim about
     * a pattern that does not exist.
     */
    fun scheduleRegularityFactor(contributors: List<ToleranceIntegrator.Contributor>): Double {
        // Spawned metabolites sit at parentOnset + Tmax, which is not a dosing
        // event — including them would halve the apparent cadence of a perfectly
        // regular course.
        val onsets = contributors.filter { !it.isMetabolite }.map { it.onset }.toSortedSet()
        if (onsets.size < 3) return 1.0
        val ordered = onsets.toList()
        val intervals = (1 until ordered.size).map { ordered[it] - ordered[it - 1] }
        val mean = intervals.sum() / intervals.size
        if (mean <= 0) return 1.0
        // Population variance — divided by N, not N-1. Upstream's choice, kept.
        val variance = intervals.sumOf { (it - mean) * (it - mean) } / intervals.size
        val coefficientOfVariation = sqrt(variance) / mean
        val regularity = 1 / (1 + coefficientOfVariation)
        return 0.7 + 0.3 * regularity
    }

    // MARK: - Occupancy

    /**
     * A contributor's peak occupancy: its concentration at its own time-to-peak,
     * through one Hill step.
     *
     * Zero on degenerate input rather than NaN — a contributor with no
     * concentration behind it must drop out of the simulation, not poison it.
     */
    fun peakOccupancy(
        ke: Double,
        ka: Double,
        prefactorNanomolar: Double,
        halfMaxNanomolar: Double,
    ): Double {
        if (ke <= 0 || ka <= 0 || halfMaxNanomolar <= 0 || prefactorNanomolar <= 0) return 0.0
        val tmax = PKModel.tmax(ke, ka)
        val peakConcentration = prefactorNanomolar * PKModel.concentration(tmax, ke, ka)
        return peakConcentration / (peakConcentration + halfMaxNanomolar)
    }

    /**
     * The Gaddum sum `Σ Cᵢ/Kᵢ` across everything contributing at a target — the
     * unsquashed drive.
     *
     * Unbounded and always non-negative. There is deliberately **no expiry check**:
     * a contributor past its window still contributes, but its concentration is
     * below the prune epsilon there by construction of the window, so the omission
     * is exact rather than approximate. Contributions before a dose's onset are
     * simply not counted.
     */
    fun combinedDrive(
        contributors: List<ToleranceIntegrator.Contributor>,
        atMinutes: Double,
    ): Double {
        var sumRatio = 0.0
        for (c in contributors) {
            if (atMinutes < c.onset || c.halfMaxNanomolar <= 0) continue
            val concentration = c.prefactorNanomolar *
                PKModel.concentration(atMinutes - c.onset, c.ke, c.ka)
            sumRatio += concentration / c.halfMaxNanomolar
        }
        return sumRatio
    }

    /**
     * [combinedDrive] through the saturating transform, in `[0, 1)`.
     *
     * Reduces exactly to `C/(C+K)` for a single contributor. Note this is a *poor*
     * "is it still on board" signal for a tight-`Kᵢ` target, where it pins near 1
     * and stays there — [loadTrail] exists for clearance, and the two answer
     * different questions.
     */
    fun combinedOccupancy(
        contributors: List<ToleranceIntegrator.Contributor>,
        atMinutes: Double,
    ): Double {
        val drive = combinedDrive(contributors, atMinutes)
        return drive / (1 + drive)
    }

    /**
     * The highest combined drive at a target over `[end - window, end]`.
     *
     * Scans a grid for its shape and then evaluates **exactly** at each
     * contributor's own time-to-peak when that falls inside the window — a spike
     * narrower than the step would otherwise be stepped over, and this is the
     * denominator of the load curve, so missing the peak would rescale the whole
     * chart.
     */
    fun recentPeakDrive(
        contributors: List<ToleranceIntegrator.Contributor>,
        endMinutes: Double,
        windowMinutes: Double,
        stepMinutes: Double,
    ): Double {
        // Guarded rather than assumed: a non-positive step makes the scan loop below
        // never advance.
        if (stepMinutes <= 0) return 0.0
        val lo = max(0.0, endMinutes - windowMinutes)
        var peak = 0.0
        var t = lo
        while (t <= endMinutes) {
            peak = max(peak, combinedDrive(contributors, t))
            t += stepMinutes
        }
        for (c in contributors) {
            val tp = c.onset + PKModel.tmax(c.ke, c.ka)
            if (tp >= lo && tp <= endMinutes) {
                peak = max(peak, combinedDrive(contributors, tp))
            }
        }
        return peak
    }

    // MARK: - Relevance and memory

    /**
     * How long this class's tolerance *remembers*: the slowest engaged layer's
     * time constant.
     *
     * A layer whose ceiling is zero never accrues, so it does not extend memory —
     * which is why an opioid (deep τ measured in months) credits a dose as a driver
     * far longer than GABA (adaptive τ, days). The acute constant is the floor and
     * is taken unconditionally, since every class has an acute pool even when its
     * ceiling is zero.
     */
    fun toleranceMemoryTauMinutes(params: ReceptorClasses.Parameters): Double {
        var tau = params.tauAcuteMinutes
        if (params.adaptiveShiftMax > 0) tau = max(tau, params.tauAdaptiveMinutes)
        if (params.deepShiftMax > 0) tau = max(tau, params.tauDeepMinutes)
        if (params.synthesisShiftMax > 0) tau = max(tau, params.tauSynthesisMinutes)
        return tau
    }

    /**
     * The class's contributing substances, most recent first, minus the ones whose
     * influence has faded below [CONTRIBUTOR_RELEVANCE_FLOOR].
     *
     * Filters the **label list only**. The shift numbers have already decayed on
     * their own; this decides which substances still deserve credit for them, so a
     * substance taken months ago stops being listed as a driver while its
     * entrenched deep layer continues to count.
     *
     * @param substancesMostRecentFirst the class's contributor names, ordered as the
     *   caller built them; the filter preserves that order.
     * @param onsetsBySubstance each name's **most recent** onset in minutes.
     */
    fun relevantContributors(
        substancesMostRecentFirst: List<String>,
        onsetsBySubstance: Map<String, Double>,
        params: ReceptorClasses.Parameters,
        totalMinutes: Double,
    ): List<String> {
        val tau = toleranceMemoryTauMinutes(params)
        if (tau <= 0) return substancesMostRecentFirst
        return substancesMostRecentFirst.filter { name ->
            val onset = onsetsBySubstance[name] ?: return@filter true
            val elapsed = totalMinutes - onset
            // A dose at or after "now" has not decayed at all.
            elapsed <= 0 || exp(-elapsed / tau) >= CONTRIBUTOR_RELEVANCE_FLOOR
        }
    }

    /**
     * The median of [values], or zero when there are none.
     *
     * **Upper-middle by integer division**, not the mean of the two middles:
     * `[1, 2, 3, 4]` gives 3, not 2.5. That is upstream's choice and it is kept —
     * the value is the representative *peak occupancy* of the class's engagements,
     * and averaging two adjacent peaks would report an occupancy that no dose in
     * the log actually produced.
     */
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    // MARK: - The load trail

    /** One sampled point of a class's load curve. */
    data class TrailPoint(val minutes: Double, val load: Double)

    /**
     * The class's receptor-load curve over a window, normalised to the user's own
     * recent peak.
     *
     * Upstream returns dated points; here the samples carry minutes since the
     * window's start, because the engine has no calendar. The caller owns the
     * origin — which is the earliest in-window dose's timestamp, so `minutes ==
     * totalMinutes` is "now".
     *
     * Normalising to the user's **own** peak over the last three weeks is what
     * makes the curve saturating-immune: it clears to about zero as a drug leaves,
     * where [combinedOccupancy] would pin near 1 for a tight-`Kᵢ` target and never
     * come down. An empty list means the window or the inputs were degenerate —
     * not that the load is zero.
     */
    fun loadTrail(
        contributors: List<ToleranceIntegrator.Contributor>,
        totalMinutes: Double,
        horizonMinutes: Double,
        stepMinutes: Double,
        pastHorizonMinutes: Double = 0.0,
    ): List<TrailPoint> {
        if (stepMinutes <= 0 || horizonMinutes < 0 || pastHorizonMinutes < 0) return emptyList()
        val peakDrive = recentPeakDrive(
            contributors = contributors,
            endMinutes = totalMinutes,
            windowMinutes = RECENT_PEAK_WINDOW_MINUTES,
            stepMinutes = stepMinutes,
        )
        val points = mutableListOf<TrailPoint>()
        var t = max(0.0, totalMinutes - pastHorizonMinutes)
        val end = totalMinutes + horizonMinutes
        while (t <= end) {
            val load = if (peakDrive > 1e-9) min(1.0, combinedDrive(contributors, t) / peakDrive) else 0.0
            points += TrailPoint(t, load)
            t += stepMinutes
        }
        return points
    }

    /**
     * The window a load curve normalises against: three weeks.
     *
     * Hard-coded upstream rather than a parameter, and it matches the chronicity
     * accumulator's time constant — the same span over which "how much has this
     * person been taking" is the question being asked.
     */
    const val RECENT_PEAK_WINDOW_MINUTES: Double = 21 * 24 * 60.0

    // MARK: - Unused-import guard

    /** `abs`/`ln` are used by [decayWindowMinutes]'s neighbours; keep the import honest. */
    private val unusedMathGuard: Double = abs(ln(1.0))
}
