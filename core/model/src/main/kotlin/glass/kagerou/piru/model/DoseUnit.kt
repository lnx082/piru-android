package glass.kagerou.piru.model

/**
 * Conversion between the bare mass units a dose can be written in.
 *
 * Deliberately bare spellings only. A qualified unit ("mg (freebase)",
 * "mg (salt)") states a basis, and folding it onto plain mg would let a freebase
 * amount be compared against a salt amount as though the qualifier were
 * decoration. Rate and per-mass units ("µg/kg", "mcg/hr") are not masses at all
 * and must keep failing.
 */
object DoseUnit {

    // Written as escapes on purpose: these two spellings look identical on
    // screen and only differ by codepoint, so a literal here is one editor
    // re-encode away from silently collapsing into a single key.
    /** MICRO SIGN (U+00B5). The canonical spelling; see [canonicalUnit]. */
    private const val MICRO = "µg"

    /** GREEK SMALL LETTER MU (U+03BC) — the same prefix typed from a different codepoint. */
    private const val GREEK_MICRO = "μg"

    /** Conversion factor from each canonical mass unit to milligrams. */
    private val toMg: Map<String, Double> = mapOf(MICRO to 0.001, "mg" to 1.0, "g" to 1_000.0)

    /**
     * Every spelling of a bare mass unit that resolves to a canonical key.
     *
     * "µg" and "μg" are different codepoints — MICRO SIGN (U+00B5) and GREEK
     * SMALL LETTER MU (U+03BC) — and the catalog contains both, because
     * different upstreams type them differently. Keying only on the first meant
     * [convert] returned null for LSD's oral ladder, all three fentanyl routes
     * and sufentanil IV: no scale-precision warning on microgram-dosed drugs, no
     * tolerance contribution, no combined-depression term. Silent, and worst
     * exactly where the margin is thinnest.
     */
    private val canonicalUnit: Map<String, String> = mapOf(
        MICRO to MICRO,
        GREEK_MICRO to MICRO,
        "mcg" to MICRO,
        "ug" to MICRO,
        "microgram" to MICRO,
        "micrograms" to MICRO,
        "mcgs" to MICRO,
        "ugs" to MICRO,
        "mg" to "mg",
        "mgs" to "mg",
        "milligram" to "mg",
        "milligrams" to "mg",
        "g" to "g",
        "gs" to "g",
        "gram" to "g",
        "grams" to "g",
        "gm" to "g",
    )

    /** Fold a written mass unit onto its canonical key, or null if it is not a bare mass unit. */
    fun canonical(unit: String): String? = canonicalUnit[unit.trim().lowercase()]

    /**
     * Convert a dose amount between compatible mass units (µg, mg, g). Returns
     * null if either unit is not a convertible mass unit (e.g. mL, IU).
     */
    fun convert(amount: Double, from: String, to: String): Double? {
        // Identity first, and for ANY unit: converting mL to mL is the amount
        // itself, and the inventory replay depends on that for non-mass units.
        if (from == to) return amount
        val fromUnit = canonical(from) ?: return null
        val toUnit = canonical(to) ?: return null
        if (fromUnit == toUnit) return amount
        val fromFactor = toMg[fromUnit] ?: return null
        val toFactor = toMg[toUnit] ?: return null
        return amount * fromFactor / toFactor
    }
}

/**
 * A colloquial unit that resolves to a fixed amount in a known physical unit.
 * e.g. `UnitAlias("drink", 14.0, "g")` for alcohol — "2 drinks" is logged as
 * 28 g of ethanol.
 */
data class UnitAlias(
    /** User-facing display label (what appears in the unit picker). */
    val label: String,
    /** How many [unit]s a single instance of [label] represents. */
    val amountPerUnit: Double,
    /** The physical unit that [amountPerUnit] is denominated in. */
    val unit: String,
)
