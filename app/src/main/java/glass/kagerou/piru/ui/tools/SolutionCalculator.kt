package glass.kagerou.piru.ui.tools

import glass.kagerou.piru.model.ByVolumeDosing
import glass.kagerou.piru.model.ByVolumeDosing.Concentration
import java.util.Locale

/**
 * The solution and concentration calculator.
 *
 * Upstream reaches concentration arithmetic through `ByVolumeDosing`, which is a *substance's* description of how it is
 * measured. This is the standalone tool: a reader has a vial, a powder or a bottle, knows two of the three figures, and
 * wants the third. It is the same arithmetic with no substance attached — which is why it is its own object rather
 * than a screen reaching into `ByVolumeDosing` with a synthetic substance.
 *
 * ## The one formula, and the two ways it is written
 * `mass = volume × concentration`, and `concentration = mass ÷ volume`.
 *
 * There is no third case, and a calculator that appears to have one is usually solving for the same unknown by a
 * different route. So the three inputs are [mass], [volumeML] and [concentrationPerML], **exactly two** must be given,
 * and the missing one is computed. Naming that explicitly is what stops the tool offering four fields and guessing
 * which two the reader meant.
 *
 * ## The units are the caller's, and are never converted here
 * A concentration is "milligrams per millilitre" only because the reader said so. This takes and returns plain numbers
 * in whatever unit the caller displays, because a calculator that silently converted between milligrams and grams
 * would give an answer whose unit the reader has to re-derive — and the one case where that matters, a percent
 * solution, is not a unit conversion at all but a density term, which is why [Concentration] carries it separately.
 *
 * ## The refusals
 * - **A zero volume.** `mass ÷ 0` is a division by zero, and a zero-volume solution has no concentration.
 * - **A zero concentration.** `mass ÷ 0` again, and "0 mg/mL" is an empty vial rather than a weak one.
 * - **A negative figure.** No mass, volume or concentration is negative; a negative one is a typo, and propagating it
 *   would give an answer that reads as plausible.
 */
internal object SolutionCalculator {

    /** Which figure is missing. Exactly one per call. */
    enum class Unknown { MASS, VOLUME, CONCENTRATION }

    /** The three figures, as the caller has them. `null` marks the one to solve for. */
    data class Inputs(
        val mass: Double? = null,
        val volumeML: Double? = null,
        val concentrationPerML: Double? = null,
    )

    /** A solved figure, with the unit the caller supplied. */
    data class Result(
        val unknown: Unknown,
        val value: Double,
    )

    /** Why a set of inputs cannot be solved, as a case rather than a message. */
    enum class Failure {
        /** Fewer or more than two figures: the tool cannot know which one was meant to be missing. */
        NOT_TWO_GIVEN,

        /** One of the given figures is zero, and the formula divides by it. */
        DIVIDES_BY_ZERO,

        /** One of the given figures is negative, which is a typo rather than a measurement. */
        NEGATIVE,

        /** The answer is not finite — an overflow, which a wildly wrong unit can produce. */
        NOT_FINITE,
    }

    class SolutionException(val failure: Failure) : Exception(failure.name)

    /**
     * Solves for the one figure [inputs] leaves out.
     *
     * @throws SolutionException with the [Failure] that applies.
     */
    fun solve(inputs: Inputs): Result {
        val given = listOfNotNull(inputs.mass, inputs.volumeML, inputs.concentrationPerML)
        if (given.size != 2) throw SolutionException(Failure.NOT_TWO_GIVEN)
        if (given.any { it < 0.0 }) throw SolutionException(Failure.NEGATIVE)

        val result = when {
            inputs.mass == null -> {
                val volume = inputs.volumeML!!
                val concentration = inputs.concentrationPerML!!
                if (concentration == 0.0) throw SolutionException(Failure.DIVIDES_BY_ZERO)
                Result(Unknown.MASS, volume * concentration)
            }

            inputs.volumeML == null -> {
                val mass = inputs.mass
                val concentration = inputs.concentrationPerML!!
                // A zero concentration is an empty vial: dividing by it is undefined, and reporting `0` would say the
                // reader needs no volume rather than that they gave no strength.
                if (concentration == 0.0) throw SolutionException(Failure.DIVIDES_BY_ZERO)
                Result(Unknown.VOLUME, mass / concentration)
            }

            else -> {
                val mass = inputs.mass
                val volume = inputs.volumeML
                if (volume == 0.0) throw SolutionException(Failure.DIVIDES_BY_ZERO)
                Result(Unknown.CONCENTRATION, mass / volume)
            }
        }

        if (!result.value.isFinite()) throw SolutionException(Failure.NOT_FINITE)
        return result
    }

    /**
     * Whether a volume of a solution contains a dose, for the "how much do I draw up" reading.
     *
     * The same multiplication as [solve]'s mass branch, exposed separately because it is the question a reader asks
     * with **both** figures already in hand — "I want 40 mg from a 200 mg/mL vial" — and it is the reason the tool is
     * useful rather than merely symmetrical.
     */
    fun volumeFor(mass: Double, concentrationPerML: Double): Double {
        if (concentrationPerML == 0.0) throw SolutionException(Failure.DIVIDES_BY_ZERO)
        if (mass < 0.0 || concentrationPerML < 0.0) throw SolutionException(Failure.NEGATIVE)
        val value = mass / concentrationPerML
        if (!value.isFinite()) throw SolutionException(Failure.NOT_FINITE)
        return value
    }

    /**
     * A figure as a reader should see it: no trailing zeros, at most three decimals.
     *
     * Three rather than two because a concentration is often a small number — `0.125 mg/mL` — and two decimals would
     * round it to `0.13`, which is a different solution. A **point** whatever the device's locale is, for the reason
     * every other figure in this port uses one: the number sits in a sentence and may be copied out of the app.
     */
    fun format(value: Double): String {
        if (value == value.toLong().toDouble()) return value.toLong().toString()
        val text = String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
        return text
    }

    /**
     * The strength field's label, which depends on what kind of solution this is.
     *
     * A mass-per-volume solution is described by a **concentration** in mg/mL; a percent solution is described by a
     * **strength** in %. Upstream's `strengthFieldLabel` makes the same distinction, and it is the difference between a
     * reader typing `40` meaning 40 mg/mL and typing `40` meaning 40 % — a thousandfold apart.
     */
    fun strengthLabel(concentration: Concentration): String = when (concentration) {
        is Concentration.MassPerVolume -> "Concentration"
        is Concentration.PercentByVolume -> "Strength"
    }
}
