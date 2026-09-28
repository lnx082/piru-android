package glass.kagerou.piru.substance

/**
 * Which spelling of a substance to show, in the app's language.
 *
 * Ported from `Piru/Utilities/LocalizedSubstanceName.swift`.
 *
 * ## Tables in, globals out
 * Upstream holds the table in an `OSAllocatedUnfairLock` installed once at store
 * init, and the *user preference* (show English names) is read from
 * `UserDefaults` on every call. Neither survives a port well: a lock-guarded
 * global is what the iOS side needs because a `nonisolated` function has no store
 * to reach, and it makes every test that wants a different table swap the
 * process-wide one. Here the table and the preference are parameters, so a test
 * supplies both and nothing else in the process notices.
 */
object LocalizedSubstanceName {

    /**
     * The `localized_names.lang` tag for a content language; English has none,
     * because English names are the canonical column rather than a table row.
     */
    fun languageFor(content: ContentLanguage): String? =
        if (content == ContentLanguage.EN) null else content.wireValue

    /**
     * Whether the feature has anything to offer in [content] at all — the Settings
     * toggle is hidden when it does not.
     */
    fun isAvailable(content: ContentLanguage): Boolean = languageFor(content) != null

    /**
     * The localized title for [canonicalName], or null to keep the name as it is.
     *
     * Null rather than the canonical name on purpose: the caller's `displayTitle`
     * ladder treats "no localized name" as a link it falls through, and returning
     * the input would make the ladder's next rung (a regional spelling) unreachable.
     */
    fun resolve(
        canonicalName: String,
        language: String?,
        usesEnglishNames: Boolean,
        table: Map<String, Map<String, String>>,
    ): String? {
        if (usesEnglishNames) return null
        return resolve(canonicalName, language, table)
    }

    /** The preference-free lookup, against a table keyed by lowercased canonical name then language tag. */
    fun resolve(
        canonicalName: String,
        language: String?,
        table: Map<String, Map<String, String>>,
    ): String? {
        if (language == null) return null
        return table[canonicalName.lowercase()]?.get(language)
    }
}

/**
 * Which spelling of a substance to show in the user's region.
 *
 * Ported from `Piru/Utilities/RegionalSubstanceName.swift`.
 *
 * The variants are stated explicitly per entry — `base` and `alternate` are both
 * written down — so it does not matter which spelling the database happens to use
 * as the canonical row. That is what lets Acetaminophen (US canonical) and
 * Salbutamol (INN canonical) resolve correctly from the same table.
 */
object RegionalSubstanceName {

    /**
     * @param base shown by default, everywhere outside [alternateRegions].
     * @param alternate the regional spelling.
     * @param alternateRegions ISO 3166-1 alpha-2 region codes.
     */
    data class Variant(
        val base: String,
        val alternate: String,
        val alternateRegions: Set<String>,
    )

    /**
     * The region-appropriate spelling for [canonicalName], or null when the
     * substance has no regional variant — the caller then keeps its existing name.
     *
     * A null [region] is not "unknown, say nothing": it is treated as the US
     * default, which is what upstream does and what keeps a device with no region
     * set from silently losing every adopted-name substitution.
     */
    fun resolve(canonicalName: String, region: String?, table: Map<String, Variant>): String? {
        val variant = table[canonicalName.lowercase()] ?: return null
        return if (variant.alternateRegions.contains(region ?: "US")) variant.alternate else variant.base
    }

    /** Split a stored comma-separated region list ("US,CA,JP") into its codes, ignoring empty segments. */
    fun regions(raw: String): Set<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
