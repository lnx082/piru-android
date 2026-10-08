package glass.kagerou.piru.ui.tools

import java.util.Locale

/**
 * The solution arithmetic, with no UI attached.
 *
 * Ported from `SolutionMathView`, whose two modes are the two questions someone with a powder and a vial
 * actually has:
 *
 * - **Solvent needed** — "I have this much, I want that concentration, how much liquid?"
 * - **Concentration** — "I put this much in that much, what did I make?"
 *
 * ## Why this is a separate object
 * The screen is a form and a label. The arithmetic is the part that can be wrong in a way nobody notices —
 * a divisor the wrong way round gives a plausible number — so it lives where a JVM test can call it
 * directly. `SolutionMathView` computes inline in a SwiftUI computed property, which is why upstream has no
 * test for either formula.
 */
object SolutionMath {

    /** Which question is being answered. */
    enum class Mode {
        /** Amount and target concentration in, volume out. */
        SOLVENT_NEEDED,

        /** Amount and volume in, concentration out. */
        CONCENTRATION,
    }

    /**
     * The solvent volume a target concentration needs, in millilitres.
     *
     * `amount / concentration`, and **null rather than 0 or infinity** for a non-positive input. Both
     * divisors matter: a concentration of zero would divide by zero, and a non-positive amount has no
     * answer either. Returning null is what makes the screen show its placeholder instead of "Infinity ml".
     */
    fun solventNeededMl(amountMg: Double?, concentrationMgPerMl: Double?): Double? {
        val amount = amountMg ?: return null
        val concentration = concentrationMgPerMl ?: return null
        if (amount <= 0.0 || concentration <= 0.0) return null
        return amount / concentration
    }

    /**
     * The concentration an amount in a volume makes, in milligrams per millilitre.
     *
     * The same guard as above and for the same reason: a volume of zero is a division by zero, and a
     * non-positive amount is not a solution.
     */
    fun concentrationMgPerMl(amountMg: Double?, volumeMl: Double?): Double? {
        val amount = amountMg ?: return null
        val volume = volumeMl ?: return null
        if (amount <= 0.0 || volume <= 0.0) return null
        return amount / volume
    }

    /**
     * A result for display.
     *
     * Upstream's rule, kept: a whole number loses its decimal, and anything else is printed to four
     * significant figures rather than a fixed number of places. The alternative is worse at both ends —
     * `%.2f` gives "0.00" for a microgram-scale solution, and `%.0f` gives "3" for 3.45.
     */
    fun formatResult(value: Double): String =
        if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            // `%.4g` is four significant figures, which is what upstream uses and what a measuring
            // instrument's precision justifies.
            String.format(Locale.ROOT, "%.4g", value)
        }
}
