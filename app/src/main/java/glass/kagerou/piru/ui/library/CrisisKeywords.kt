package glass.kagerou.piru.ui.library

/**
 * Distress detection for a search box.
 *
 * Ported from `CrisisKeywords.swift`, which shares one matcher between the quick-log dock and the library
 * search so both surface the same resources for the same queries and the two cannot drift apart.
 *
 * ## Whole words, and why that is not fussiness
 * Single-word keywords match on **word boundaries**, not as substrings. The upstream comment names the
 * case: "armod" contains "od", and a crisis panel appearing over a search for armodafinil is the failure
 * mode that makes a safety feature worse than nothing — a warning that fires on ordinary input is one a
 * reader learns to dismiss, and the one time it matters they will have stopped reading it.
 *
 * Multi-word keywords ("bad trip", "call 911") are matched as phrases, because their words are ordinary
 * on their own and only mean something together.
 *
 * ## What this deliberately is not
 * Not a classifier, not a sentiment score, and not a check for the substance someone is asking about.
 * It is a small fixed list and a boundary rule, and it is honest about being exactly that: the panel it
 * gates says help is available, and does not attempt to assess anything.
 */
object CrisisKeywords {

    /**
     * The keywords, in the form they are matched.
     *
     * Kept verbatim from upstream, including the two that look like phone numbers: someone who types
     * "911" into a substance search is not looking for a compound, and someone who types "call 911" is
     * past the point of a search box.
     */
    private val keywords: Set<String> = setOf(
        "help", "emergency", "overdose", "bad trip", "dying", "scared",
        "panic", "ambulance", "hospital", "not okay", "freaking out",
        "call 911", "911", "poisoning", "too much", "od", "can't breathe",
    )

    /**
     * Whether [query] reads as a call for help.
     *
     * A blank query is never a match: an empty search box is the state the screen opens in, and drawing
     * the panel then would make it chrome rather than a response.
     */
    fun matches(query: String): Boolean {
        val normalized = query.lowercase().trim()
        if (normalized.isEmpty()) return false
        val words = normalized
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
            .toSet()
        return keywords.any { keyword ->
            if (keyword.contains(' ')) normalized.contains(keyword) else keyword in words
        }
    }
}
