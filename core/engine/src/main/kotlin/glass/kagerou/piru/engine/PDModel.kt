package glass.kagerou.piru.engine

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Pharmacodynamic tolerance dynamics — the layer **above** [PKModel].
 *
 * [PKModel] answers *where the drug is*; `PDModel` answers *how repeated
 * occupancy changes the receptor's responsiveness over time*. Like `PKModel` it
 * is pure: no state, no I/O, every function testable in isolation. The stateful
 * orchestration — reading the dose log, resolving per-substance pharmacology,
 * persisting a snapshot — lives in the tolerance store, and the per-class rate
 * constants live with the receptor classes.
 *
 * ## The model: a dose-response right shift
 * Tolerance is a **shift factor** `S(t) ≥ 1` that moves the dose-response curve
 * right — loss of potency with the slope preserved, which is what controlled ED50
 * studies measure: `ED50(t) = ED50_naive · S(t)`. `S` is built in **log space**
 * from three additive layer contributions, each an exposure-driven leaky
 * integrator with its own gain and time constant:
 *
 * ```
 * ln S(t) = s_acute + s_adaptive + s_deep
 * ṡ_layer  = (shiftMax_layer · O(t) · drive_layer − s_layer) / τ_layer
 * ```
 *
 * where `O(t)` in `[0, 1]` is receptor occupancy from the replay. Each layer is
 * solved in closed form per step — identical structure across all three, see
 * [stepShift].
 *
 * - **Acute** (`drive = 1`, τ of hours): within-session tachyphylaxis, the redose
 *   loop.
 * - **Adaptive** (`drive = μ`, τ of days to weeks): the baseline shift people
 *   mean by "tolerance".
 * - **Deep** (`drive = gate`, τ of months): entrenched neuroadaptation. The gate
 *   keeps it **off** until the dose is sustained well **above the substance's
 *   heavy ceiling**, and the layer's ceiling provides the asymptote — so
 *   therapeutic users never accrue it. Keying the gate on dose-relative
 *   escalation rather than on the adaptive shift is the principled choice:
 *   transporter occupancy saturates, so therapeutic and heavy dosing look
 *   identical at the receptor, and only the dose relative to the heavy ceiling
 *   distinguishes significant escalation.
 *
 * The user-facing gauge is [responseFraction]; recovery milestones come from
 * [shiftDecayMinutes].
 */
object PDModel {

    // MARK: - The layer step

    /**
     * Advance one right-shift layer across [dtMinutes], treating occupancy as
     * constant over the step.
     *
     * A leaky integrator relaxing toward `shiftMax·occupancy·drive`, solved in
     * **closed form** rather than by Euler: with the target held constant,
     * `ṡ = (target − s)/τ` has the exact solution `target + (s − target)·e^{−dt/τ}`.
     * That is **unconditionally stable for any step** and exact for
     * piecewise-constant occupancy, which matters because the replay's step size
     * is chosen for resolution, not for stability.
     *
     * The same structure serves all three layers, distinguished only by their
     * `(shiftMax, τ, drive)`.
     *
     * A non-positive step or time constant returns [current] unchanged, so a
     * caller that has not resolved a half-life yet gets a flat line rather than a
     * division by zero.
     */
    fun stepShift(
        current: Double,
        shiftMax: Double,
        occupancy: Double,
        drive: Double,
        dtMinutes: Double,
        tauMinutes: Double,
    ): Double {
        if (dtMinutes <= 0 || tauMinutes <= 0) return current
        val target = max(0.0, shiftMax) * max(0.0, min(1.0, occupancy)) * max(0.0, drive)
        val decay = exp(-dtMinutes / tauMinutes)
        return target + (current - target) * decay
    }

    /**
     * A smoothstep gate in `[0, 1]`: `0` while [value] sits below [threshold],
     * ramping smoothly — C¹ at both edges, no kink — to `1` once it exceeds
     * `threshold + width`.
     *
     * `smoothstep(t) = t²(3 − 2t)` with `t = clamp((value − threshold)/width, 0, 1)`.
     *
     * A non-positive width degenerates to a step function rather than dividing by
     * zero.
     *
     * The deep layer's drive is the *product* of two of these: a **magnitude**
     * gate on the dose-relative escalation factor, times a **chronicity** gate on
     * the leaky duty-cycle accumulator. Both must be high for deep to accrue — a
     * heavy one-off binge has chronicity near zero and recovers, while a
     * therapeutic daily dose has magnitude near zero and stays stable.
     */
    fun smoothstepGate(value: Double, threshold: Double, width: Double): Double {
        if (width <= 0) return if (value >= threshold) 1.0 else 0.0
        val t = max(0.0, min(1.0, (value - threshold) / width))
        return t * t * (3 - 2 * t)
    }

    // MARK: - The gauge

    /**
     * One sampled point on a tolerance recovery curve: days from now, and tolerance as a percentage.
     *
     * The percentage is the same quantity the tolerance bar draws, so a chart and a bar for one class can
     * never disagree.
     */
    data class TolerancePoint(val day: Double, val percent: Double)

    /**
     * Four right-shift layers' worth of state, as the tolerance replay holds it.
     *
     * Its own type rather than four positional `Double`s: three are minutes-scale layer magnitudes and one
     * is a synthesis pool, and a transposed argument list would be a plausible-looking wrong curve.
     */
    data class ToleranceLayers(
        val acute: Double,
        val adaptive: Double,
        val deep: Double,
        val synthesis: Double,
        val tauAcuteMinutes: Double,
        val tauAdaptiveMinutes: Double,
        val tauDeepMinutes: Double,
        val tauSynthesisMinutes: Double,
    ) {
        /** The four layer magnitudes, in the order the shift factor sums them. */
        val magnitudes: List<Double> get() = listOf(acute, adaptive, deep, synthesis)
    }

    /**
     * The tolerance recovery curve over [windowMinutes], sampled at [sampleCount] points.
     *
     * ## The formula, and why it is not a ratio
     * Each layer decays as `s·e^{-t/τ}`; the shift factor is `exp(Σ)` of them, because the shift is
     * multiplicative in the model. Tolerance is then `1 - responseFraction(shift, …)` — the **saturating**
     * read the gauge uses, not `shift / initialShift`, which is a linear ratio of a quantity the model
     * treats exponentially and then through a hill-like response.
     *
     * The difference is not cosmetic. With a cap of 0.5 and a representative occupancy of 0.6, a shift factor
     * of 2 and one of 20 both read as saturated; a linear ratio would draw them as far apart.
     *
     * ## Why the sample at t = 0 is the current tolerance
     * The curve starts where the user is now and descends toward naive, the same orientation as the bar
     * beside it. A curve that started at full and fell would read as "you will become tolerant".
     */
    fun toleranceRecoveryCurve(
        layers: ToleranceLayers,
        representativeOccupancy: Double,
        occupancyCap: Double?,
        windowMinutes: Double,
        sampleCount: Int = 24,
    ): List<TolerancePoint> {
        if (sampleCount < 2) return emptyList()
        val span = max(windowMinutes, 1.0)
        return (0 until sampleCount).map { index ->
            val minutes = span * index / (sampleCount - 1)
            val shift = exp(
                layers.acute * exp(-minutes / layers.tauAcuteMinutes) +
                    layers.adaptive * exp(-minutes / layers.tauAdaptiveMinutes) +
                    layers.deep * exp(-minutes / layers.tauDeepMinutes) +
                    layers.synthesis * exp(-minutes / layers.tauSynthesisMinutes),
            )
            val tolerance = 1.0 - responseFraction(
                shiftFactor = shift,
                representativeOccupancy = representativeOccupancy,
                occupancyCap = occupancyCap,
            )
            TolerancePoint(
                day = minutes / 1_440.0,
                // Clamped to the axis, as upstream: a floating-point excursion outside `[0, 100]` would
                // draw a line off the top of the chart rather than at it.
                percent = (tolerance * 100).coerceIn(0.0, 100.0),
            )
        }
    }

    /**
     * The per-layer shares of a class's current shift, for a legend or a bar.
     *
     * `s / Σ s` is the **right** use of a linear ratio: these are the layer magnitudes themselves, before
     * the exponential, so a share of them is a share of the state. The recovery curve above is the place a
     * ratio is wrong.
     */
    fun layerShares(magnitudes: List<Double>): List<Double> {
        val values = magnitudes.map { if (it > 0) it else 0.0 }
        val total = values.sum()
        if (total <= 0) return List(values.size) { 0.0 }
        return values.map { it / total }
    }

    /**
     * The tolerance the model reads right now for a class's layers.
     *
     * `toleranceRecoveryCurve` sampled at zero, named separately because the bar and the chart's first point
     * must be the same number — and because a caller that only wants "how tolerant is this" should not have
     * to sample a curve to ask.
     */
    fun toleranceNow(
        layers: ToleranceLayers,
        representativeOccupancy: Double,
        occupancyCap: Double?,
    ): Double {
        val shift = exp(layers.magnitudes.sum())
        val tolerance = 1.0 - responseFraction(
            shiftFactor = shift,
            representativeOccupancy = representativeOccupancy,
            occupancyCap = occupancyCap,
        )
        return (tolerance * 100).coerceIn(0.0, 100.0)
    }

    /**
     * The **response fraction** in `[0, 1]`: how much of the naive effect you
     * would feel at your usual dose under the current right shift [shiftFactor].
     *
     * The shift means the effective dose is `D/S`; with occupancy `O = C/(C+K)`
     * and `C` proportional to dose, the occupancy at `D/S` is `C/(C + K·S)`, so
     * the ratio to the naive occupancy is `(r+1)/(r+S)` where `r = C/K` comes
     * from the representative peak occupancy at the usual dose.
     *
     * Equals 1 at `S = 1`, decreasing as `S` grows; at low representative
     * occupancy it is approximately `1/S`.
     *
     * ## The occupancy cap, and which classes need it
     * The uncapped ratio is the physically exact usual-dose occupancy ratio, which
     * is the right thing for **agonists, PAMs and antagonists** — a heavy opioid
     * user at their elevated dose then shows a realistic *residual* response
     * rather than "barely anything".
     *
     * For **release and reuptake** classes, occupancy saturates at recreational
     * doses — a releaser at the transporter sits at occupancy near 1 — so felt
     * effect tracks flux rather than static occupancy. Those classes pass
     * [occupancyCap] to evaluate at the sensitive half-saturation point instead,
     * so a meaningful shift does not wash out to "no tolerance". Null means
     * uncapped; the caller decides per class.
     */
    fun responseFraction(
        shiftFactor: Double,
        representativeOccupancy: Double,
        occupancyCap: Double? = null,
    ): Double {
        if (shiftFactor <= 0) return 1.0
        val capped = occupancyCap?.let { min(it, representativeOccupancy) } ?: representativeOccupancy
        // Clamp just below 1 so a fully saturated, uncapped occupancy cannot
        // divide by zero. It then reads a large ratio and a response near 1,
        // which is the honest reading for that degenerate case.
        val occupancy = max(0.0, min(0.999_999, capped))
        val ratio = occupancy / (1 - occupancy)
        return max(0.0, min(1.0, (ratio + 1) / (ratio + shiftFactor)))
    }

    // MARK: - Recovery forecast

    /**
     * Minutes until the total right shift decays to [targetShift] with **no
     * further occupancy**, given the current per-layer `(s, τ)` contributions.
     *
     * With occupancy at zero each layer decays `s(t) = s·e^{−t/τ}`, so
     * `S(t) = exp(Σ s·e^{−t/τ})` is strictly decreasing toward 1 — found by
     * bisection.
     *
     * Returns `0` when the shift is already at or below the target, and **null
     * when `targetShift < 1`**: a full reset to `S = 1` is asymptotic and never
     * reached in finite time, so a target below it is unreachable rather than
     * merely far off.
     */
    fun shiftDecayMinutes(layers: List<Layer>, targetShift: Double): Double? {
        val initialSum = layers.sumOf { max(0.0, it.s) }
        val currentShift = exp(initialSum)
        if (currentShift <= targetShift) return 0.0
        if (targetShift < 1) return null

        fun shiftAt(minutes: Double): Double =
            exp(layers.sumOf { it.s * exp(-minutes / max(1.0, it.tau)) })

        var low = 0.0
        var high = 1.0
        // Expand the bracket until the shift is below the target, capped at about
        // five years of minutes — beyond that the answer is "not in any horizon
        // this forecast is for", and the cap is returned as-is.
        while (shiftAt(high) > targetShift && high < 2_628_000) high *= 2
        if (shiftAt(high) > targetShift) return high
        repeat(60) {
            val mid = (low + high) / 2
            if (shiftAt(mid) > targetShift) low = mid else high = mid
        }
        return (low + high) / 2
    }

    /** One layer's current log-shift contribution and its time constant, in minutes. */
    data class Layer(val s: Double, val tau: Double)

    // MARK: - Cross-substance combination

    /**
     * Combine the occupancy contributions of several ligands at one **shared,
     * competitive** target into a single site-occupancy fraction, by Gaddum
     * competitive summation: `Σrᵢ / (1 + Σrᵢ)`, where each ligand's binding ratio
     * `rᵢ = Oᵢ/(1 − Oᵢ)` is recovered from its individual occupancy.
     *
     * This is the physically correct form for ligands competing for the same
     * site: two ligands each at half saturation give `2/3`, not the `0.75` the
     * probabilistic union `1 − Π(1 − Oᵢ)` produces — the union treats the site as
     * though each ligand had its own independent copy.
     *
     * It is order-independent, stays in `[0, 1]`, and is *cheaper* than the union.
     * The load-bearing property is that it reduces **exactly** to a single
     * occupancy when only one ligand is present, so a single-substance right
     * shift is unchanged; only polydrug co-occupancy at one target moves.
     */
    fun competitiveOccupancy(occupancies: List<Double>): Double {
        var sumRatio = 0.0
        for (raw in occupancies) {
            val clamped = min(1.0, max(0.0, raw))
            // A ligand at or above full occupancy has an unbounded ratio, so the
            // sum saturates and the result is 1 without dividing by zero.
            if (clamped >= 1) return 1.0
            sumRatio += clamped / (1 - clamped)
        }
        if (!sumRatio.isFinite()) return 1.0
        return sumRatio / (1 + sumRatio)
    }
}
