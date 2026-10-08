package glass.kagerou.piru.ui.settings

import glass.kagerou.piru.ui.nav.AppTab

/**
 * Which tabs the bottom bar shows, and in what order.
 *
 * ## Why the rules are here and not in the bar
 * Three of them are decisions with a wrong answer that still renders:
 *
 * - **At least one tab stays.** Hiding every tab produces a bar with nothing in it and no way back to the settings
 *   that would fix it, which is a one-way trip. The last visible tab cannot be hidden.
 * - **The selected tab stays visible.** Hiding the tab you are standing on leaves the app showing a screen with no
 *   bar item to return to it, and pushing a route onto a hidden tab's stack is how a user loses their place.
 * - **A newly added tab defaults to visible.** Preferences are stored as the *visible* set, so a tab added in a
 *   later build is absent from an older stored list. Reading absence as "hidden" would silently withhold a new tab
 *   from every existing user — so the stored list is a preference **order**, and a tab it does not mention is
 *   appended rather than dropped.
 *
 * All three are pure functions of the stored list, the user's choice and the selected tab, so they can be tested
 * without a bar.
 */
internal object TabLayout {

    /**
     * The tabs to draw, in order.
     *
     * [storedVisible] is the user's saved set as wire values, or null when they have never changed anything — in
     * which case every tab shows in its declared order. [selected] is forced into the result even if the stored
     * list omits it, because a selected tab with no bar item is a screen the user cannot leave.
     */
    fun visibleTabs(
        storedHidden: List<String>?,
        selected: AppTab,
    ): List<AppTab> {
        val all = AppTab.entries
        // No stored preference: every tab, in its declared order.
        if (storedHidden == null) return all

        val hidden = storedHidden.toSet()
        val shown = all.filterNot { it.wireValue in hidden }

        // The selected tab is shown whatever the preference says. Applied on **read** rather than only on write,
        // because a stored preference can come from an import or from a version that did not have this guard — and
        // a selected tab with no bar item is a screen the user cannot leave.
        return if (selected in shown) shown else shown + selected
    }

    /**
     * Whether [tab] may be hidden, given what is currently visible.
     *
     * The last one may not: a bar with nothing in it has no way back to the setting that emptied it.
     */
    fun canHide(tab: AppTab, currentlyVisible: List<AppTab>): Boolean =
        currentlyVisible.size > 1 && tab in currentlyVisible

    /**
     * The stored list after toggling [tab].
     *
     * ## Why this writes a **complete** set
     * It starts from what is visible now, changes one membership, and returns everything. That matters for two
     * reasons:
     *
     * 1. A plain set operation is what makes a hide *stick*. My first version built its result as
     *    `visible + unmentioned`, and because [visibleTabs] appends unmentioned tabs on every read, hiding four of
     *    five tabs brought all five back. The one-way-trip test caught it.
     * 2. Writing the complete set means nothing is left unmentioned, so [visibleTabs]'s append rule fires only for
     *    the case it exists for — a list written by an older build. A user who hides one tab records the rest as
     *    visible rather than leaving them unmentioned and therefore un-hideable.
     *
     * The guards are applied here rather than trusted to the caller: the last visible tab cannot be hidden, and the
     * selected one cannot be. A caller that forgot either would produce a state the user cannot undo.
     */
    fun toggle(
        storedHidden: List<String>?,
        currentlyVisible: List<AppTab>,
        selected: AppTab,
        tab: AppTab,
        visible: Boolean,
    ): List<String> {
        // Every tab, minus what should be shown, is hidden — so the result is always a complete statement and no
        // tab is ever left "unmentioned".
        val shown = currentlyVisible.toMutableSet()
        if (visible) {
            shown.add(tab)
        } else {
            if (!canHide(tab, currentlyVisible)) return hiddenFrom(currentlyVisible, selected)
            shown.remove(tab)
        }
        // A hide that would strand the selected tab is refused.
        shown.add(selected)
        return hiddenFrom(shown.toList(), selected)
    }

    /** The wire values to store for a given set of shown tabs. */
    private fun hiddenFrom(shown: Collection<AppTab>, selected: AppTab): List<String> {
        val shownSet = shown.toMutableSet().apply { add(selected) }
        return AppTab.entries.filterNot { it in shownSet }.map { it.wireValue }
    }

    /**
     * Whether the bottom bar shows labels.
     *
     * A separate preference from the tab set and stored separately, because it is about *drawing* rather than about
     * which screens exist. Fewer than three tabs with labels is fine; five labels on a narrow phone is where the
     * labels are what make the bar unreadable.
     */
    fun showsLabels(tabCount: Int, labelsPreferred: Boolean): Boolean = labelsPreferred || tabCount < 3
}
