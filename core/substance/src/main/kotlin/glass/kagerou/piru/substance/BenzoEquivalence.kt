package glass.kagerou.piru.substance

import glass.kagerou.piru.model.DiazepamEquivalent

/**
 * How much diazepam a benzodiazepine dose is equivalent to.
 *
 * Ported from `BenzoEquivalence.diazepamPerMg` and `diazepamEquivalent(forDoseMg:)`. The port had the citation prose and
 * the equivalence screen printed **that** — the sentence the dataset carries — but never the ratio, so the one thing a
 * converter is for was missing from the converter.
 *
 * ## Why the numbers are approximate, and why that is said out loud
 * Ashton's table, manufacturer labels and clinical tables **disagree** with each other, by amounts that matter at the
 * top of a dose ladder. So this returns the ratio and the caller always shows the citation beside it — never a bare
 * milligram presented as clinical truth. That is upstream's own rule and the reason [Result] carries the source text
 * rather than leaving the caller to look it up.
 *
 * ## Three ways a row carries no answer
 * - **Either number missing.** The dataset's prose is parsed into two figures; a row that did not parse has no ratio.
 * - **Either number non-positive.** A zero or negative equivalence is not a weak equivalence, it is an absent one, and
 *   dividing by it would either fail or produce a nonsense figure.
 * - **A dose of zero.** Nothing is equivalent to nothing, so the caller gets null rather than `0 mg`.
 */
object BenzoEquivalence {

    /** A citation and the ratio parsed from it. */
    data class Result(
        /** Milligrams of diazepam equivalent to **1 mg** of this benzodiazepine. */
        val diazepamPerMg: Double,
        /** The cited sentence the ratio came from, shown beside every figure. */
        val sourceText: String?,
        /** Whether the shipped number matches the reference table the citation names. */
        val isCited: Boolean,
    )

    /**
     * The ratio for [equivalent], or null when the row cannot support one.
     *
     * Both figures must be positive: `diazepam / dose` with a zero dose is a division by zero, and with a zero
     * equivalence it is a meaningless zero dressed as a result.
     */
    fun ratio(equivalent: DiazepamEquivalent?): Result? {
        if (equivalent == null) return null
        val dose = equivalent.doseMg?.takeIf { it > 0 } ?: return null
        val diazepam = equivalent.equivalentDiazepamMg?.takeIf { it > 0 } ?: return null
        return Result(
            diazepamPerMg = diazepam / dose,
            sourceText = equivalent.displayText?.takeIf { it.isNotBlank() },
            isCited = equivalent.isCited,
        )
    }

    /**
     * The diazepam-equivalent milligrams of [doseMg].
     *
     * Null rather than zero for a non-positive dose: `0 mg` of alprazolam is not "0 mg of diazepam" as a measurement,
     * it is a dose nobody took.
     */
    fun equivalentFor(result: Result?, doseMg: Double): Double? {
        if (result == null) return null
        if (doseMg <= 0.0) return null
        return result.diazepamPerMg * doseMg
    }
}
