package glass.kagerou.piru.ui.journal

import glass.kagerou.piru.engine.ActiveSubstanceState

/**
 * The Active Now card's two decisions, as functions.
 *
 * ## Why the card exists
 * Ported from `ActiveNowCard`, which the file's own comment calls "the Journal's **state** surface: everything
 * pharmacologically active right now, in one card". The feed reads plan → state → log: `MyMedsCard` is the plan,
 * the day list below is the log, and this is the single answer to "what is in effect?". The port had the plan and
 * the log and no state card, so the journal's middle — the one thing that changes on its own — was a graph of
 * everything rather than a reading of what is happening.
 *
 * ## The layout rule, which is the reason this is data
 * A **lone** dose gets the quick-glance treatment: a phase bar with a countdown, no graph. The bar alone tells
 * that story. Two or more distinct **substances** get dots and names with a window of the continuous timeline
 * beneath, because overlapping curves need a picture where one bar cannot say it.
 *
 * That rule is worth a function rather than an `if` in the layout for the reason the layout rule is subtle: it
 * keys on **distinct substances**, not on the number of states. A single substance redosed three times is still
 * one curve, and drawing three overlapping bands of the same compound would be a graph nobody can read.
 */
internal object ActiveNow {

    /**
     * Whether the card has anything to say.
     *
     * An empty state list is the ordinary case — nothing is active — and the card **hides** rather than drawing an
     * empty frame. Upstream's parent decides that too: a card that always draws trains the reader to scroll past
     * the one surface whose whole point is that it changes on its own.
     */
    fun worthShowing(states: List<ActiveSubstanceState>): Boolean = states.isNotEmpty()

    /**
     * Whether to draw the continuous-timeline window.
     *
     * Two or more distinct substances, matching upstream's `showsGraph`. Distinct by lowercased name, because the
     * catalogue's spellings are not normalised and "MDMA" and "mdma" are one substance.
     */
    fun showsGraph(states: List<ActiveSubstanceState>): Boolean = distinctSubstanceCount(states) >= 2

    /**
     * One entry per distinct substance, keyed case-insensitively and carrying the **first-seen spelling**.
     *
     * The single grouping every other answer here reads from. My first version had three separate expressions —
     * a lowercased count in the graph rule, a case-sensitive `distinct()` in the headline, and a raw map — and
     * they disagreed for a log containing "MDMA" and "mdma", so the count said one substance and the headline said
     * two. The test caught it twice: once for the graph rule and once for the headline.
     *
     * The spelling kept is the first seen, so the card shows what the user typed rather than a canonicalised name.
     */
    fun distinctSubstances(states: List<ActiveSubstanceState>): List<String> {
        val seen = LinkedHashMap<String, String>()
        for (state in states) {
            seen.putIfAbsent(state.substanceName.lowercase(), state.substanceName)
        }
        return seen.values.toList()
    }

    /** How many different substances are active. Reads [distinctSubstances], so it cannot disagree with it. */
    fun distinctSubstanceCount(states: List<ActiveSubstanceState>): Int =
        distinctSubstances(states).size

    /**
     * The card's title line: the substance when there is one, otherwise how many are active.
     *
     * Returned as data rather than as text because the singular case names a substance and the plural case counts
     * — two different resources, and choosing between them is the decision worth testing.
     */
    sealed interface Headline {
        /** One substance, by the name the user logged. */
        data class Single(val substance: String) : Headline

        /** Several, with how many distinct substances there are. */
        data class Multiple(val substances: Int) : Headline
    }

    fun headline(states: List<ActiveSubstanceState>): Headline? {
        if (states.isEmpty()) return null
        // The same grouping the count and the graph rule use, rather than a third expression that happens to
        // agree for the inputs I happened to test.
        val distinct = distinctSubstances(states)
        return if (distinct.size == 1) Headline.Single(distinct.first()) else Headline.Multiple(distinct.size)
    }
}
