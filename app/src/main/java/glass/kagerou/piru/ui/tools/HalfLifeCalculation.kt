package glass.kagerou.piru.ui.tools

import glass.kagerou.piru.engine.PKModel
import glass.kagerou.piru.engine.PKResolver
import glass.kagerou.piru.model.DurationProfile
import glass.kagerou.piru.model.Substance
import kotlin.math.pow

/**
 * The single-dose pharmacokinetics behind the half-life screen.
 *
 * Ported from `HalfLifeCalculation`. Every equation is `PKModel`'s and every half-life resolution is
 * `PKResolver`'s; what lives here is **which inputs reach them** — the typed half-life override, the
 * `(ke, ka)` pair for the picked route, the remaining amount, and the milestone ladder the screen labels.
 *
 * ## Why it is a separate object with no UI
 * The port's half-life screen had a substance picker and a curve, and nothing else: no dose, no elapsed time, no
 * route, no override, no remaining figure and no milestones. Those are the calculator, and a calculator whose
 * arithmetic cannot be called is one whose arithmetic cannot be checked. Upstream keeps the same split for the
 * same reason, and it is exactly the split that let this port's recovery chart drift for as long as it did.
 */
object HalfLifeCalculation {

    /**
     * The `(ke, ka)` pair a curve is drawn from.
     *
     * A named pair rather than two `Double` parameters: they are both per-minute rate constants of the same
     * magnitude, so a transposed call site produces a plausible curve rather than a compile error.
     */
    data class RateConstants(val ke: Double, val ka: Double)

    /**
     * One `½ⁿ` step of the elimination ladder.
     *
     * [minutes] is the time to reach [fraction] of the dose remaining, which is **not** `n × halfLife` for an
     * oral dose: absorption is still filling the compartment while elimination empties it, so the early
     * milestones come later than the naive ladder. Upstream times them against the fitted curve and falls back
     * to the bare half-life when there is no curve, and so does this.
     */
    data class Milestone(val n: Int, val fraction: Double, val minutes: Double)

    /**
     * The half-life the screen models with, in minutes.
     *
     * The typed override when the toggle is on, otherwise whatever the substance carries. **Null when neither
     * answers**, which every caller has to keep handling: a compound nobody has measured has no half-life, and
     * inventing one would be the one thing this screen must not do.
     *
     * A non-positive override is null rather than zero. Zero would divide by zero in the naive fallback and
     * produce an infinite curve.
     */
    fun effectiveHalfLife(useCustom: Boolean, customHours: Double?, substance: Substance?): Double? {
        if (useCustom) {
            val hours = customHours ?: return null
            return if (hours > 0) hours * 60 else null
        }
        return PKResolver.halfLifeMinutes(substance)
    }

    /**
     * `(ke, ka)` fitted to the route's acute profile, or null when no positive half-life resolves.
     *
     * The route matters because `ka` comes from the route's own duration profile: an oral dose and an
     * insufflated one of the same substance have different absorption, and a curve drawn from the wrong route's
     * `ka` is a wrong time-to-peak.
     */
    fun rateConstants(
        halfLifeMinutes: Double?,
        duration: DurationProfile?,
    ): RateConstants? {
        val halfLife = halfLifeMinutes ?: return null
        if (halfLife <= 0) return null
        val (ke, ka) = PKResolver.rateConstants(halfLife, duration)
        return RateConstants(ke = ke, ka = ka)
    }

    /** Time of peak concentration in minutes; zero when the rate constants are unknown. */
    fun peakTime(rateConstants: RateConstants?): Double =
        rateConstants?.let { PKModel.tmax(it.ke, it.ka) } ?: 0.0

    /**
     * Amount still in the body — absorption site plus central compartment — at [elapsedMinutes].
     *
     * Falls back to plain exponential decay when only a half-life is known, which is the honest reading for a
     * substance with no duration profile: `dose · ½^(t/t½)`.
     *
     * A negative elapsed time is clamped to zero rather than extrapolated backwards, because "how much was in me
     * before I took it" is not a question the model answers.
     */
    fun remainingAmount(
        dose: Double,
        elapsedMinutes: Double,
        halfLifeMinutes: Double,
        rateConstants: RateConstants?,
    ): Double {
        if (dose <= 0 || halfLifeMinutes <= 0) return 0.0
        val elapsed = if (elapsedMinutes > 0) elapsedMinutes else 0.0
        if (rateConstants != null) {
            return dose * PKModel.fractionRemainingInBody(elapsed, rateConstants.ke, rateConstants.ka)
        }
        return dose * 0.5.pow(elapsed / halfLifeMinutes)
    }

    /**
     * The first four `½ⁿ` steps.
     *
     * Timed against the fitted curve when one exists and against the bare half-life otherwise — upstream's rule,
     * kept because the two answers genuinely differ. The search is bounded at eight half-lives: a curve that has
     * not reached `1/16` by then is not going to, and an unbounded search on a saturating model can wander.
     */
    fun milestones(halfLifeMinutes: Double, rateConstants: RateConstants?): List<Milestone> {
        if (halfLifeMinutes <= 0) return emptyList()
        return (1..4).map { n ->
            val fraction = 0.5.pow(n.toDouble())
            val minutes = if (rateConstants != null) {
                PKModel.timeToFraction(
                    fraction,
                    ke = rateConstants.ke,
                    ka = rateConstants.ka,
                    maxMinutes = halfLifeMinutes * 8,
                )
            } else {
                halfLifeMinutes * n
            }
            Milestone(n = n, fraction = fraction, minutes = minutes)
        }
    }
}
