package glass.kagerou.piru.engine

/**
 * Hashtags in a note, pulled out as the dose's tags.
 *
 * Ported from `Piru/Utilities/TagExtractor.swift`. Upstream calls it on every entry save, which is
 * why a dose's `tags` column is a view over its notes rather than something the user maintains
 * separately: `#headache` typed into a note *is* the tag.
 *
 * ## Why this did not exist here
 * `DoseEntryEntity` has carried the column and `withTags` since it was written, and the export
 * round-trips it — but nothing on this side ever called `withTags`, so the only way a tag could
 * exist was an import from iOS. A field that can only be populated by a file is a field the app
 * cannot use.
 *
 * ## The rules, and why each one is here
 * - `#` followed by word characters. Upstream's pattern is `#(\w+)`, which deliberately does not
 *   match `#-` or a bare `#`.
 * - **Lowercased**, because a tag is a key: `#Sleep` and `#sleep` are the same tag and must not
 *   produce two.
 * - **Deduplicated in first-seen order**, not sorted: the order the user wrote them in is the order
 *   they are shown in, and a sorted list would reshuffle a note's tags under it.
 */
object TagExtractor {

    /**
     * The characters a tag may contain after the `#`.
     *
     * `\w` in Kotlin's regex is ASCII-only by default, which is what upstream's `NSRegularExpression`
     * means by `\w` too. Pinned as a constant because the difference matters for a Chinese or
     * accented tag, and a silent change of meaning here would be very hard to notice.
     */
    private const val TAG_PATTERN = "#(\\w+)"

    private val pattern = Regex(TAG_PATTERN)

    /**
     * Every distinct tag in [text], lowercased, in the order they first appear.
     *
     * A null or blank note has no tags, which is the same answer as a note without a `#` — the
     * column stays null rather than holding an empty string, because "no tags" and "an empty list
     * of tags" would then be two different rows that mean the same thing.
     */
    fun extractTags(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<String>()
        for (match in pattern.findAll(text)) {
            val tag = match.groupValues[1].lowercase()
            if (tag.isEmpty()) continue
            if (seen.add(tag)) out += tag
        }
        return out
    }

    /**
     * The tags a user is most likely to reach for.
     *
     * Upstream's `suggestions`, kept in its order. Not used by the extractor — it is here so a
     * suggestion chip and an extracted tag come from one place rather than two lists that drift.
     */
    val suggestions: List<String> = listOf(
        "headache", "anxiety", "sleep", "pain", "nausea",
        "mood", "energy", "focus", "relax", "appetite",
    )
}
