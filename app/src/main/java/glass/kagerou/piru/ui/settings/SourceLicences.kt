package glass.kagerou.piru.ui.settings

/**
 * Pulls a licence out of a source's own description.
 *
 * ## Why an extractor and not a slug-to-licence map
 * `sources` has **no licence column**. The licence is inside the sentence the catalogue wrote about each source:
 *
 *     dosewiki        "Community encyclopedia. CC0."
 *     psychonautwiki  "Community wiki. CC BY-SA 4.0."
 *     freeodwiki      "Chinese-language community wiki. CC BY-SA 4.0."
 *     wikidata        "Open knowledge base. CC0."
 *
 * A map keyed by slug would be a second copy of data the catalogue already holds, and it would be wrong the day a
 * source is added or relicensed — silently, because a missing entry and an unlicensed source look the same. This
 * reads the licence out of the catalogue's own text and returns null when there is none, which is the honest
 * answer for the eleven sources that name no licence.
 *
 * ## What it recognises
 * The four identifiers the catalogue actually uses. A licence the catalogue starts using tomorrow is not
 * recognised, the source shows no licence line, and `SourceLicencesTest` is where that shows up — rather than a
 * wrong licence being printed, which is the failure mode worth avoiding.
 */
internal object SourceLicences {

    /**
     * The identifiers to look for, longest first.
     *
     * Longest first matters: `"CC BY-SA 4.0"` and `"CC0"` both appear, and a search for `"CC"` would match the
     * non-commercial variant if the catalogue ever carried one. Ordering by length and matching whole identifiers
     * keeps `CC0` from being found inside `CC BY-SA`.
     */
    private val KNOWN: List<String> = listOf(
        "CC BY-SA 4.0",
        "CC BY-SA 3.0",
        "CC BY 4.0",
        "CC0",
        "public domain",
    )

    /**
     * The licence named in [description], or null.
     *
     * Case-insensitive, because the catalogue's own casing is not consistent — `"CC0"` appears in two rows and a
     * future one could write `"cc0"`.
     */
    fun licenceIn(description: String?): String? {
        val text = description ?: return null
        if (text.isBlank()) return null
        return KNOWN.firstOrNull { text.contains(it, ignoreCase = true) }
    }
}
