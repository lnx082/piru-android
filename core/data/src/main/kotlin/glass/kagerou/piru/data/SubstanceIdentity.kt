package glass.kagerou.piru.data

import glass.kagerou.piru.model.RouteOfAdministration

/**
 * The identity keys that recents, favorites and daily items group on.
 *
 * Ported from `QuickLogDose.identityKey` and `QuickLogDose.makeKey`, which the
 * iOS side exposes as statics because three different `@Model` types need them.
 * Kept in one place here for the same reason.
 */
object SubstanceIdentity {

    /**
     * The substance-identity portion of every recents key: the PSID family plus
     * the form facets that make one drug two distinct recents (a picked isomer, a
     * release form).
     *
     * Falls back to the lowercased name when the row has not resolved to a
     * [substanceUID] — so a pre-PSID row keys exactly as it did before, and two
     * casings of one name still collide.
     *
     * [productName] is deliberately **not** part of this: "Concerta" and
     * "Methylphenidate XR" both resolve to Methylphenidate·XR and share one card.
     * Facets of null, empty, or `"0"` (the PSID unspecified sentinel) are absent,
     * so an unfaceted resolved dose keys by the bare family uid and matches a
     * plain log.
     */
    fun identityKey(
        substanceUID: String?,
        substance: String,
        isomer: String?,
        releaseForm: String?,
        saltForm: String?,
    ): String {
        val base = if (!substanceUID.isNullOrEmpty()) substanceUID else substance.lowercase()
        val facets = listOf(present(isomer), present(releaseForm), present(saltForm))
        if (facets.all { it == null }) return base
        return "$base#" + facets.joinToString(",") { it ?: UNSPECIFIED }
    }

    /**
     * The full key for a quick-log chip.
     *
     * For a by-volume drink it folds in the drink's name, strength and volume, so
     * distinct drinks — an IPA and a cider that happen to share grams — stay
     * distinct chips; otherwise the key is `identity|route|amount|unit`.
     *
     * ## One known divergence
     * The volume and strength are rendered with the platform's own `toString`.
     * Swift and Kotlin agree for every value these fields can hold (a volume in
     * millilitres, an ABV percentage), but they part company above ~1e7, where
     * Kotlin switches to scientific notation and Swift does not. These are keys,
     * never displayed, so the only consequence would be a chip that fails to
     * match after such a value — impossible for a real drink, and noted rather
     * than guarded.
     */
    fun makeKey(
        substance: String,
        route: RouteOfAdministration,
        amount: Double,
        unit: String,
        substanceUID: String? = null,
        isomer: String? = null,
        releaseForm: String? = null,
        saltForm: String? = null,
        volumeML: Double? = null,
        abv: Double? = null,
        drinkName: String? = null,
    ): String {
        val identity = identityKey(
            substanceUID = substanceUID,
            substance = substance,
            isomer = isomer,
            releaseForm = releaseForm,
            saltForm = saltForm,
        )
        val base = "$identity|${route.wireValue}|$amount|$unit"
        if (volumeML == null && abv == null && drinkName == null) return base
        return "$base|${(drinkName ?: "").lowercase()}|${volumeML ?: ""}|${abv ?: ""}"
    }

    /** The PSID facet sentinel meaning "unspecified". */
    private const val UNSPECIFIED = "0"

    /** A facet is present when it is set, non-empty and not the unspecified sentinel. */
    private fun present(value: String?): String? =
        value?.takeIf { it.isNotEmpty() && it != UNSPECIFIED }
}
