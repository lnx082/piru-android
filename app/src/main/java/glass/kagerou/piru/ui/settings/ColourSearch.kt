package glass.kagerou.piru.ui.settings

/**
 * Which substances a colour search shows.
 *
 * ## What the search is for, and what it is not
 * The screen lists the substances **in the user's own log** — typically a few dozen — so a search over it is not a
 * way to find something hidden, it is a way to *narrow* a list the user is scanning while looking for one name.
 * That is a different job from the library's search, and it is why this filter is simple: substring, case
 * insensitive, on the name the user logged.
 *
 * ## Why it does not match aliases
 * Deliberately, and it is the one judgement here worth stating. The library's search resolves aliases because a
 * reader may not know the name a substance is filed under. This screen's rows are the **user's own words** — the
 * name they typed into their log — so matching an alias they never used would surface a row titled something they
 * did not search for. If a user logged "Concerta" and searches "methylphenidate", they get nothing, and the honest
 * fix is for them to see the name they actually wrote.
 */
internal object ColourSearch {

    /**
     * The rows matching [query], in the order given.
     *
     * A blank query returns everything, including a whitespace-only one: `"   "` is not a search, and treating it
     * as one that matches nothing would make the list vanish when a user taps space.
     *
     * Trimming before matching means `" mdma "` finds MDMA, which is what a paste from elsewhere produces.
     */
    fun filter(names: List<String>, query: String): List<String> {
        val needle = query.trim()
        if (needle.isEmpty()) return names
        return names.filter { it.contains(needle, ignoreCase = true) }
    }

    /**
     * Whether the empty state should say "no matches" rather than "nothing logged".
     *
     * Two different empty screens, and the distinction is the whole reason this is a function: a user who has
     * logged nothing needs to be told to log something, and a user whose search matched nothing needs to be told
     * their search matched nothing. Showing the first message to the second user is a lie about their own data.
     */
    fun emptyStateIsSearchResult(allCount: Int, query: String): Boolean =
        allCount > 0 && query.trim().isNotEmpty()
}
