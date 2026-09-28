package glass.kagerou.piru.model

/**
 * How much to trust a **predicted** pharmacology parameter — Vd, Kᵢ/EC₅₀, a
 * tolerance constant.
 *
 * Ported from `Shared/Formatting/ConfidenceTier.swift`.
 *
 * Mirrors the grading of the citation-verification pipeline: every curated
 * parameter is graded HIGH, MEDIUM or LOW from primary literature, and anything
 * that falls back to a class default ships [UNVERIFIED]. The app renders this
 * tier next to every prediction — the house labelling rule is "predicted (model,
 * confidence)", never "measured".
 *
 * ## Declaration order is trust order, least first
 * Upstream declares these most-to-least and overrides `<` onto an explicit rank.
 * Kotlin's `Enum.compareTo` is **final**, so that override is not available here,
 * and the inherited comparison is by ordinal. Rather than keep a rank field that
 * every call site has to remember to consult, the constants are declared in
 * **ascending** trust: the natural ordering *is* the trust ordering, so
 * `minOf(a, b)` is the less trustworthy tier and `maxOf` the more — which is what
 * every caller means, and which keeps an accidental `sorted()` from silently
 * doing the opposite.
 *
 * A derived value inherits the floor: a parameter built from several graded
 * inputs is only as trustworthy as its weakest one. Read it through [flooredBy],
 * which says that out loud.
 */
enum class ConfidenceTier(val wireValue: String) {
    /** No verified value for this substance; a class default stood in. The strongest caveat. */
    UNVERIFIED("unverified"),

    /** Sparse binding data — ordinal affinity only, with no reliable absolute number. */
    LOW("low"),

    /** Class-default (tier-mapped) parameters, or a single-source or caveated value. */
    MEDIUM("medium"),

    /** Literature Kᵢ/EC₅₀ and a measured Vd for this specific substance and target. */
    HIGH("high"),
    ;

    /**
     * Parse a pipeline grade string (`"HIGH"`, `"MEDIUM"`, `"LOW"`, `"NONE"`, …),
     * case-insensitively. An unknown, empty or `NONE` grade is [UNVERIFIED] — a
     * missing grade is not a trustworthy one.
     */
    companion object {
        fun fromGrade(grade: String?): ConfidenceTier =
            when (grade?.trim()?.uppercase()) {
                "HIGH" -> HIGH
                "MEDIUM", "MED" -> MEDIUM
                "LOW" -> LOW
                else -> UNVERIFIED
            }
    }
}

/**
 * The less trustworthy of two tiers — what a derived value inherits.
 *
 * A parameter built from several graded inputs is only as trustworthy as its
 * weakest one, so this is the direction every fold runs: scaling a rat row to a
 * human floors the tier, and a non-clinical potency basis floors a metabolite's.
 *
 * Named rather than written as a bare `minOf` because "floor the confidence" is
 * the intent at every call site, and `minOf` over a type whose ordering means
 * *trust* is a reading a reviewer should not have to reconstruct.
 */
fun ConfidenceTier.flooredBy(ceiling: ConfidenceTier): ConfidenceTier = minOf(this, ceiling)
