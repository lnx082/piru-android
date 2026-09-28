package glass.kagerou.piru.engine

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Pure steady-state pharmacokinetics for a fixed repeated-dosing schedule.
 *
 * Ported from `Piru/Views/Tools/SteadyStateModel.swift` — the `SteadyStateModel`
 * enum, which is the arithmetic. (The `SteadyStateInputs` observable that wraps it
 * upstream is view state: text fields, a recompute key, a stored result. That
 * belongs with the Android screen, not in the engine, so it is deliberately not
 * here.)
 *
 * ## No new pharmacology
 * This is [PKModel.fractionRemainingInBody] superposed at a fixed interval, so the
 * plateau is a sum of curves the single-dose calculator already draws. Amounts are
 * in the dose's own units — a *body content*, not a concentration — which is why no
 * volume of distribution appears anywhere in this file. The accumulation ratio is
 * dimensionless and depends on the half-life and the interval alone.
 *
 * ## The two guard rails are load-bearing, not tidiness
 * A user typing a near-zero interval against a long half-life would otherwise ask
 * for hundreds of thousands of superposed doses at each of ~840 sample points —
 * a multi-second hang on every keystroke. So the window is floored (at six
 * intervals and at 1.2 × the 97 % time) and the dose count is capped, rather than
 * trusting the inputs.
 */
object SteadyStateModel {

    /**
     * Fraction-of-steady-state landmarks, as multiples of the half-life.
     *
     * The approach to steady state is `1 − 2^(−t/t½)`, which does not involve the
     * dose or the interval — 90 % at 3.32 half-lives, 95 % at 4.32, ~97 % at 5.
     */
    const val TIME_90_MULTIPLE: Double = 3.3219
    const val TIME_95_MULTIPLE: Double = 4.3219
    const val TIME_97_MULTIPLE: Double = 5.0

    /** Samples across the climb to plateau. Upstream's 600, inclusive of both ends. */
    const val CURVE_SAMPLE_COUNT: Int = 600

    /** Samples across the final full interval, where the plateau is read off. */
    const val PLATEAU_SAMPLE_COUNT: Int = 240

    /**
     * Hard ceiling on how many doses are superposed.
     *
     * Reached only by inputs no real schedule produces — the shortest interval the
     * UI accepts against the longest half-life it knows. Truncating there is what
     * keeps the plateau faithful for every schedule that is not already absurd,
     * while bounding the work at a fixed number of evaluations.
     */
    const val MAX_DOSE_COUNT: Int = 5_000

    /** One sample of the climb: minutes since the schedule started, and body content. */
    data class CurvePoint(val minutes: Double, val amount: Double)

    data class Result(
        val dose: Double,
        /** Body content while climbing to plateau, as `(minutes, amount)`. */
        val curve: List<CurvePoint>,
        val totalMinutes: Double,
        /** Body content just after / just before a dose at steady state, in the dose's units. */
        val peakAmount: Double,
        val troughAmount: Double,
        /** The interval mean, `(peak + trough) / 2`. */
        val averageAmount: Double,
        /** Trough-based `1/(1 − e^(−ke·τ))` — how many single doses' worth accumulate. */
        val accumulationRatio: Double,
        /** Peak-to-trough swing as a percentage of the interval mean. */
        val fluctuationPercent: Double,
        val time90: Double,
        val time95: Double,
        val time97: Double,
    )

    /**
     * The plateau a fixed schedule reaches, or null when any input is non-positive.
     *
     * Null is "cannot answer", not "nothing accumulates": a dose of zero, a half-life
     * of zero and an interval of zero are all missing inputs rather than small ones.
     * [ka] is not guarded because [PKModel.fractionRemainingInBody] already treats a
     * non-positive one as "the model cannot say" and answers the whole dose is still
     * there, which is the safe direction.
     */
    fun compute(
        dose: Double,
        halfLifeMinutes: Double,
        intervalMinutes: Double,
        ke: Double,
        ka: Double,
    ): Result? {
        if (!(dose > 0 && halfLifeMinutes > 0 && intervalMinutes > 0 && ke > 0)) return null

        val time90 = TIME_90_MULTIPLE * halfLifeMinutes
        val time95 = TIME_95_MULTIPLE * halfLifeMinutes
        val time97 = TIME_97_MULTIPLE * halfLifeMinutes

        val totalMinutes = max(time97 * 1.2, max(intervalMinutes * 6, intervalMinutes + 1))
        // `toInt()` truncates toward zero, which is `floor` here: both operands are
        // positive, and the window is floored at six intervals so the ratio is at
        // least 6. The `coerceAtMost` before the conversion keeps a ratio large
        // enough to overflow an Int from wrapping negative — which would silently
        // superpose nothing and report a plateau of zero.
        val intervals = (totalMinutes / intervalMinutes).coerceAtMost(MAX_DOSE_COUNT.toDouble())
        val doseCount = min(intervals.toInt() + 1, MAX_DOSE_COUNT)

        // Body content = the sum over already-taken doses. The loop is bounded by
        // `doseCount` rather than by `t`, and breaks on the first dose that has not
        // happened yet because the doses are walked oldest-first.
        fun bodyContent(t: Double): Double {
            var total = 0.0
            for (n in 0 until doseCount) {
                val elapsed = t - n * intervalMinutes
                if (elapsed < 0) break
                total += dose * PKModel.fractionRemainingInBody(elapsed, ke, ka)
            }
            return total
        }

        val step = totalMinutes / CURVE_SAMPLE_COUNT
        val curve = List(CURVE_SAMPLE_COUNT + 1) { i ->
            val t = i * step
            CurvePoint(minutes = t, amount = bodyContent(t))
        }

        // Peak and trough are read off the *final full interval* — the plateau — not
        // off the whole curve, which would report the first dose's own peak. The
        // window's lower edge is a dose instant because the window is an exact
        // multiple of the interval... except when the cap truncated the dose count,
        // where it is the best available approximation of the plateau.
        val ssStart = totalMinutes - intervalMinutes
        var peak = 0.0
        var trough = Double.POSITIVE_INFINITY
        for (i in 0..PLATEAU_SAMPLE_COUNT) {
            val amount = bodyContent(ssStart + intervalMinutes * i / PLATEAU_SAMPLE_COUNT)
            peak = max(peak, amount)
            trough = min(trough, amount)
        }
        // Unreachable while the plateau loop runs at least once, which it always
        // does. Kept because an infinite trough would otherwise be carried into the
        // average and out to the screen as "+∞", which reads as a real number.
        if (!trough.isFinite()) trough = 0.0

        val average = (peak + trough) / 2
        val fluctuation = if (average > 0) (peak - trough) / average * 100 else 0.0
        val accumulation = 1 / (1 - exp(-ke * intervalMinutes))

        return Result(
            dose = dose,
            curve = curve,
            totalMinutes = totalMinutes,
            peakAmount = peak,
            troughAmount = trough,
            averageAmount = average,
            accumulationRatio = accumulation,
            fluctuationPercent = fluctuation,
            time90 = time90,
            time95 = time95,
            time97 = time97,
        )
    }
}
