package glass.kagerou.piru.engine

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Depot (oil and intramuscular) PK for injectable hormone esters.
 *
 * Ported from `Shared/Engines/PKModel+Depot.swift`, which is an extension on
 * `PKModel`. Kotlin cannot extend an `object` from another file — an object is a
 * single declaration — so this is a sibling object whose name carries the
 * association. Every function still belongs to `PKModel` conceptually: the
 * one-compartment oral model in [PKModel] is the special case of this chain.
 *
 * ## The model
 * A three-compartment linear chain: injection depot → serum ester → serum
 * hormone.
 *
 * ```
 * E(t) = d·k₁·k₂·[ e^{−k₁t}/((k₁−k₂)(k₁−k₃))
 *                − e^{−k₂t}/((k₁−k₂)(k₂−k₃))
 *                + e^{−k₃t}/((k₁−k₃)(k₂−k₃)) ]
 * ```
 *
 * All rates are **per day**: depot kinetics run on a days-to-weeks timescale and
 * the primary literature reports in days. `d` folds F/Vd into a single
 * amplitude. The output is a serum concentration in the analyte's canonical unit
 * (pg/mL for estradiol, ng/dL for testosterone) once `d` is set to that unit's
 * scale.
 *
 * This is the exact solution of a two-stage first-order cascade feeding a
 * first-order clearance, so it is closed-form at every point — no numerical
 * integration anywhere.
 *
 * ## Times are compared to the millisecond
 * The iOS side subtracts two `Date`s as a `Double` second count, which is
 * sub-microsecond precise. This works in epoch milliseconds, because that is what
 * [Instant] carries without a second field. The difference is at most half a
 * millisecond on an axis measured in days, so it is far below the precision of
 * any depot datum — noted because it is a real difference in arithmetic, not
 * because it is expected to matter.
 */
object PKModelDepot {

    /** Seconds in a day. */
    const val SECONDS_PER_DAY: Double = 86_400.0

    /**
     * Minimum separation between rate constants before the closed form loses
     * precision to a vanishing denominator.
     *
     * Rates are per day, so 1e-6/day is about 0.05 s of half-life difference —
     * far finer than any measured depot rate, which is what makes nudging a
     * coincident pair apart invisible in the curve.
     */
    private const val RATE_EPSILON = 1e-6

    /**
     * One ester's kinetics.
     *
     * @param d amplitude, output-unit per mg. Wraps F/Vd; a population value from
     *   the ester table, or user-calibrated.
     * @param k1 rate constant 1, per day — the slow terminal depot-release rate
     *   for most esters.
     * @param k2 rate constant 2, per day.
     * @param k3 rate constant 3, per day.
     */
    @Serializable
    data class DepotParameters(
        val d: Double,
        val k1: Double,
        val k2: Double,
        val k3: Double,
    ) {
        /**
         * The same parameters with a replaced amplitude — the amplitude-calibration
         * output, where the shape is held and the y-axis scaled.
         */
        fun withAmplitude(newD: Double): DepotParameters = copy(d = newD)

        /**
         * The same parameters with [k1] scaled by [s] — the rate-fit output.
         *
         * Scaling k1 stretches or compresses the curve's rise and terminal decay in
         * time, which is where individual depot variation lands (injection depth,
         * oil vehicle, subcutaneous versus intramuscular). k2 and k3 stay put so
         * the fitted k1 never crosses them; the caller's search range is what
         * enforces that.
         */
        fun withK1Scale(s: Double): DepotParameters = copy(k1 = k1 * s)
    }

    /** One point of a sampled depot curve. */
    data class DepotPoint(val date: Instant, val concentration: Double)

    /**
     * Serum concentration [days] after a single injection. Zero for negative
     * [days].
     *
     * The three rate constants are distinct for every population ester and stay
     * distinct under the calibration bounds, since only k1 is fitted and only
     * within half to twice its population value, never crossing the much larger
     * k2 and k3. A pair that lands within [RATE_EPSILON] is nudged apart rather
     * than dividing by zero.
     */
    fun depotConcentration(
        doseMg: Double,
        days: Double,
        parameters: DepotParameters,
    ): Double {
        val p = parameters
        if (doseMg <= 0 || days < 0 || p.d <= 0 || p.k1 <= 0 || p.k2 <= 0 || p.k3 <= 0) return 0.0

        val (k1, k2, k3) = separatedRates(p.k1, p.k2, p.k3)

        val t1 = exp(-k1 * days) / ((k1 - k2) * (k1 - k3))
        val t2 = exp(-k2 * days) / ((k1 - k2) * (k2 - k3))
        val t3 = exp(-k3 * days) / ((k1 - k3) * (k2 - k3))

        return max(0.0, doseMg * p.d * k1 * k2 * (t1 - t2 + t3))
    }

    /**
     * Multi-dose superposition: the total serum concentration at [at], summing
     * each prior injection's single-dose contribution. An injection at or after
     * [at] contributes nothing.
     */
    fun depotConcentrationMultiDose(
        injections: List<Injection>,
        at: Instant,
        parameters: DepotParameters,
    ): Double {
        var total = 0.0
        for (injection in injections) {
            val days = daysBetween(injection.date, at)
            if (days < 0) continue
            total += depotConcentration(injection.doseMg, days, parameters)
        }
        return total
    }

    /**
     * The superposition curve, sampled at [pointCount] evenly spaced points across
     * [from] to [to].
     */
    fun depotCurve(
        injections: List<Injection>,
        from: Instant,
        to: Instant,
        parameters: DepotParameters,
        pointCount: Int = 600,
    ): List<DepotPoint> {
        if (pointCount <= 1) {
            return listOf(DepotPoint(from, depotConcentrationMultiDose(injections, from, parameters)))
        }
        val startMillis = from.toEpochMilli()
        val spanMillis = to.toEpochMilli() - startMillis
        return (0 until pointCount).map { i ->
            val frac = i.toDouble() / (pointCount - 1)
            val date = Instant.ofEpochMilli(startMillis + (spanMillis * frac).toLong())
            DepotPoint(date, depotConcentrationMultiDose(injections, date, parameters))
        }
    }

    // MARK: - Multi-ester summation

    /** One injection: when, and how much. */
    data class Injection(val date: Instant, val doseMg: Double)

    /**
     * One ester's dose history and the depot parameters to model it with — a single
     * input to a summed multi-ester serum curve.
     *
     * A user who switches esters (valerate to cypionate) or mixes them at
     * different concentrations produces one contribution per ester, each carrying
     * **that ester's own** rate constants.
     */
    data class DepotContribution(
        val injections: List<Injection>,
        val parameters: DepotParameters,
    )

    /**
     * A summed multi-ester serum curve: the total at each sample plus each ester's
     * own contribution, all on the **same** time grid.
     */
    data class SummedDepotCurve(
        /**
         * Per-contribution sampled curves, aligned to the input order — index `i` is
         * `input[i]`'s own curve, so a switch or a mix reads honestly rather than
         * being flattened to one dominant ester.
         */
        val contributions: List<List<DepotPoint>>,
        /** The serum total: the elementwise sum of every contribution over the grid. */
        val total: List<DepotPoint>,
    )

    /**
     * Sum several esters' depot curves on one shared time grid: each contribution is
     * sampled with its own parameters at the same sample dates, then summed into the
     * serum total.
     *
     * The per-ester arrays are kept for the "assumed depot levels" display; the
     * total is what a lab measures and what calibration fits against. The
     * single-ester [depotCurve] is the one-contribution special case — the sum of
     * one curve is that curve.
     */
    fun depotCurveSummed(
        contributions: List<DepotContribution>,
        from: Instant,
        to: Instant,
        pointCount: Int = 600,
    ): SummedDepotCurve {
        if (pointCount <= 1) {
            val per = contributions.map { c ->
                listOf(DepotPoint(from, depotConcentrationMultiDose(c.injections, from, c.parameters)))
            }
            val total = listOf(DepotPoint(from, per.sumOf { it.first().concentration }))
            return SummedDepotCurve(per, total)
        }

        val startMillis = from.toEpochMilli()
        val spanMillis = to.toEpochMilli() - startMillis
        val dates = (0 until pointCount).map { i ->
            val frac = i.toDouble() / (pointCount - 1)
            Instant.ofEpochMilli(startMillis + (spanMillis * frac).toLong())
        }

        val perEster = mutableListOf<List<DepotPoint>>()
        val totals = DoubleArray(pointCount)
        for (c in contributions) {
            val series = dates.mapIndexed { idx, date ->
                val value = depotConcentrationMultiDose(c.injections, date, c.parameters)
                totals[idx] += value
                DepotPoint(date, value)
            }
            perEster += series
        }
        return SummedDepotCurve(
            contributions = perEster,
            total = dates.mapIndexed { idx, date -> DepotPoint(date, totals[idx]) },
        )
    }

    /**
     * Serum total across every contribution at one instant — the multi-ester
     * superposition sum, for calibrating against a lab draw's exact time.
     */
    fun depotConcentrationSummed(
        contributions: List<DepotContribution>,
        at: Instant,
    ): Double = contributions.sumOf {
        depotConcentrationMultiDose(it.injections, at, it.parameters)
    }

    // MARK: - Internals

    /** Days from [earlier] to [later], fractional and possibly negative. */
    private fun daysBetween(earlier: Instant, later: Instant): Double =
        Duration.between(earlier, later).toMillis() / (1000.0 * SECONDS_PER_DAY)

    /**
     * The three rates, guaranteed pairwise separated by at least [RATE_EPSILON],
     * nudging any coincident pair upward.
     *
     * Ordering-independent, so the symmetric closed form does not depend on which
     * parameter a caller happened to name first.
     */
    private fun separatedRates(a: Double, b: Double, c: Double): Triple<Double, Double, Double> {
        val k1 = a
        var k2 = b
        var k3 = c
        if (abs(k1 - k2) < RATE_EPSILON) k2 += RATE_EPSILON
        if (abs(k1 - k3) < RATE_EPSILON) k3 += 2 * RATE_EPSILON
        if (abs(k2 - k3) < RATE_EPSILON) k3 += 2 * RATE_EPSILON
        return Triple(k1, k2, k3)
    }
}
