package glass.kagerou.piru.engine

/**
 * Saturation kinetics: what fraction of an enzyme's capacity a concentration occupies, and the two regimes that follow.
 *
 * ## The model
 *
 *     v = Vmax · [S] / (Km + [S])
 *
 * the Michaelis–Menten form, which describes a saturable process: at low substrate almost everything is free enzyme and
 * the rate is nearly proportional to concentration, while at high substrate the enzyme is saturated and the rate
 * approaches `Vmax` however much more is added.
 *
 * ## Why this belongs in the app at all
 * It is the arithmetic behind two questions a reader actually has:
 *
 * 1. **"Why did doubling the dose not double the effect?"** — because the process was already near saturation. The
 *    `fractionOfVmax` answers that directly.
 * 2. **"Is this dose in the linear range?"** — `regime` names it, and the linear range is the only one where dose
 *    scales effect predictably.
 *
 * ## Why it is here and not in `:app`, where the calculator lives
 * It is **pure arithmetic with a decision in it** — the clamp at the Km boundary and the division guard — and the
 * failure it prevents is a number that looks plausible and is wrong. That is the same rule `BenzoEquivalence` was moved
 * down for, and the same reason it has tests that a screen cannot provide.
 *
 * ## What this deliberately does not do
 * No attempt to model **two** substrates, inhibition, or cooperativity. Each is a different equation with different
 * constants the catalogue does not carry, and offering a Knob for one while calling it "saturation kinetics" would be
 * the misreporting this project keeps finding — a control that says more than it does.
 */
object SaturationKinetics {

    /** The regime a concentration sits in, which is what decides whether dose and effect scale together. */
    enum class Regime(val wireValue: String) {
        /** Well below Km: the rate is nearly proportional to concentration. Dose scales effect. */
        LINEAR("linear"),

        /** Around Km: the rate rises more slowly than concentration, and a doubling gives less than double. */
        TRANSITIONAL("transitional"),

        /** Well above Km: the process is nearly saturated and more substrate barely changes the rate. */
        SATURATED("saturated"),
    }

    /**
     * At or below this multiple of Km, the process counts as linear.
     *
     * `0.1` is the conventional figure: at `[S] = 0.1·Km` the Michaelis–Menten fraction is `0.1/1.1 ≈ 9.1%` of Vmax,
     * within a tenth of the proportional value. Named rather than inlined because the boundary is a judgement, and a
     * judgement inside a comparison is invisible to a reader.
     */
    const val LINEAR_AT_OR_BELOW_KM_MULTIPLE: Double = 0.1

    /**
     * At or above this multiple of Km, the process counts as saturated.
     *
     * `10.0` is the mirror of the figure above: at `[S] = 10·Km` the fraction is `10/11 ≈ 90.9%` of Vmax, so adding
     * substrate buys under a tenth of the remaining capacity.
     */
    const val SATURATED_AT_OR_ABOVE_KM_MULTIPLE: Double = 10.0

    /** Why a calculation could not be made. */
    enum class Failure {
        /** The concentration was not given, or was not a number. */
        NO_CONCENTRATION,

        /** Km was not given, or was not a number. */
        NO_KM,

        /** Km was zero or negative, so the denominator has no positive value and the fraction is undefined. */
        KM_NOT_POSITIVE,

        /** The concentration was negative, which is not a concentration. */
        CONCENTRATION_NEGATIVE,

        /** The concentration or Km was so large the arithmetic left the range a Double can carry. */
        NOT_FINITE,
    }

    /** A completed calculation. */
    data class Result(
        /** The concentration the reader gave. */
        val concentration: Double,
        /** The half-saturation constant the reader gave. */
        val km: Double,
        /**
         * The rate as a fraction of Vmax, in `0.0..1.0`.
         *
         * The number that answers "why did doubling the dose not double the effect": at `0.5`, half the capacity is
         * occupied and a doubling buys a third more, not twice as much.
         */
        val fractionOfVmax: Double,
        /** Which regime the concentration sits in. */
        val regime: Regime,
        /**
         * The concentration that would reach [targetFraction] of Vmax, when that is reachable.
         *
         * Rearranged from the same equation: `[S] = Km · f / (1 - f)`. Null when the target is `1.0` or more, because
         * full saturation is approached asymptotically and **never reached** — a dose promising it would be a lie.
         */
        val concentrationForTarget: Double?,
    )

    /**
     * The fraction of Vmax at a concentration.
     *
     * Returns a failure rather than a number when the inputs cannot produce one, because every failure here would
     * otherwise be a plausible-looking figure: a negative Km gives a negative fraction, and a zero Km gives `1.0` for
     * any positive concentration — "fully saturated at every dose", which is exactly wrong.
     */
    fun solve(
        concentration: Double?,
        km: Double?,
        targetFraction: Double? = null,
    ): kotlin.Result<Result> {
        if (concentration == null || concentration.isNaN()) return failure(Failure.NO_CONCENTRATION)
        if (km == null || km.isNaN()) return failure(Failure.NO_KM)
        if (km <= 0.0) return failure(Failure.KM_NOT_POSITIVE)
        if (concentration < 0.0) return failure(Failure.CONCENTRATION_NEGATIVE)
        if (!concentration.isFinite() || !km.isFinite()) return failure(Failure.NOT_FINITE)

        val fraction = concentration / (km + concentration)
        if (!fraction.isFinite()) return failure(Failure.NOT_FINITE)

        return kotlin.Result.success(
            Result(
                concentration = concentration,
                km = km,
                fractionOfVmax = fraction,
                regime = regimeFor(concentration = concentration, km = km),
                concentrationForTarget = concentrationFor(targetFraction, km),
            ),
        )
    }

    /**
     * The concentration that reaches a given fraction.
     *
     * Returns null for a target at or above `1.0`, and for a target of zero. **Full saturation is approached
     * asymptotically and never reached**, so a figure for it does not exist — returning `Double.MAX_VALUE` instead
     * would be a number a reader could act on.
     */
    fun concentrationFor(targetFraction: Double?, km: Double): Double? {
        val target = targetFraction ?: return null
        if (!target.isFinite() || target <= 0.0 || target >= 1.0) return null
        return km * target / (1.0 - target)
    }

    /**
     * Which regime a concentration sits in.
     *
     * The boundaries are **inclusive at both ends** and do not overlap, so a value exactly at `0.1·Km` is linear and
     * one exactly at `10·Km` is saturated. A pair of comparisons that both matched at a boundary would make the answer
     * depend on evaluation order.
     */
    fun regimeFor(concentration: Double, km: Double): Regime = when {
        concentration <= LINEAR_AT_OR_BELOW_KM_MULTIPLE * km -> Regime.LINEAR
        concentration >= SATURATED_AT_OR_ABOVE_KM_MULTIPLE * km -> Regime.SATURATED
        else -> Regime.TRANSITIONAL
    }

    /** How much more concentration is needed to go from one fraction to another, as a multiple. */
    fun foldIncrease(fromFraction: Double, toFraction: Double): Double? {
        if (fromFraction <= 0.0 || fromFraction >= 1.0) return null
        if (toFraction <= 0.0 || toFraction >= 1.0) return null
        if (toFraction <= fromFraction) return null
        // [S] is Km·f/(1-f), so the ratio of two concentrations is the ratio of the two f/(1-f) terms.
        return (toFraction / (1.0 - toFraction)) / (fromFraction / (1.0 - fromFraction))
    }

    private fun failure(reason: Failure): kotlin.Result<Result> =
        kotlin.Result.failure(IllegalArgumentException(reason.name))
}
