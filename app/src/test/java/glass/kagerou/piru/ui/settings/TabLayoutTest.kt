package glass.kagerou.piru.ui.settings

import glass.kagerou.piru.ui.nav.AppTab
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Which tabs the bar draws, and the three rules that keep it usable.
 *
 * ## Why the store holds the **hidden** tabs, and not the visible ones
 * This is the bug the tests in this file found, and it is worth stating because the wrong design looks right.
 *
 * The bar has to show a tab the user has never seen a preference for — one added by a later build — so
 * `visibleTabs` used to append anything the stored list did not mention. That rule is correct and it made hiding
 * impossible to express: `toggle` removed a tab, and the next read appended it straight back. Hiding four of five
 * tabs brought all five back, which the one-way-trip test below walks through.
 *
 * A tab can be **visible**, **hidden**, or **never mentioned**, and the third means visible. A list of visible tabs
 * cannot tell the second from the third, so no amount of care inside `toggle` fixes it. Storing what is *hidden*
 * makes every stored preference a complete statement: everything absent is visible, including a tab from a future
 * build, and including one the user hid — the state that was inexpressible.
 *
 * ## The two guards
 * The last visible tab cannot be hidden, and the selected one cannot be. Both are applied on **read** as well as
 * on write, because a preference can arrive from an import or from a build that did not have the guard.
 */
class TabLayoutTest {

    private val selected = AppTab.JOURNAL

    // MARK: - The hidden set

    /** No preference shows every tab in its declared order. */
    @Test
    fun `no stored preference shows every tab`() {
        TabLayout.visibleTabs(storedHidden = null, selected = selected) shouldContainExactly
            AppTab.entries.toList()
    }

    /**
     * "Hide nothing" and "no preference" are the same bar.
     *
     * The store writes null for "hide nothing", so these differ only in spelling — but an empty list can arrive
     * from a hand-edited preference or an import, and the result has to be a full bar rather than an empty one.
     */
    @Test
    fun `an empty hidden list shows everything`() {
        TabLayout.visibleTabs(storedHidden = emptyList(), selected = selected) shouldContainExactly
            AppTab.entries.toList()
        TabLayout.visibleTabs(storedHidden = null, selected = selected) shouldContainExactly
            AppTab.entries.toList()
    }

    /** Only the hidden tabs are missing, and the rest keep the declared order. */
    @Test
    fun `only the hidden tabs are missing`() {
        val hidden = listOf(AppTab.TOOLS.wireValue, AppTab.INSIGHTS.wireValue)
        TabLayout.visibleTabs(hidden, selected) shouldContainExactly
            AppTab.entries.filterNot { it == AppTab.TOOLS || it == AppTab.INSIGHTS }
    }

    /**
     * A tab **not mentioned** in the hidden set is visible.
     *
     * The property that makes the hidden set the right thing to store, and what the visible-set version could not
     * express. A tab added by a later build is absent from an older stored set — and absence means visible, so a
     * new tab appears for every existing user rather than being silently withheld.
     */
    @Test
    fun `a tab not in the hidden set is visible`() {
        val hidden = listOf(AppTab.TOOLS.wireValue)
        val tabs = TabLayout.visibleTabs(hidden, selected)
        (AppTab.SEARCH in tabs) shouldBe true
        (AppTab.TOOLS in tabs) shouldBe false
        tabs.size shouldBe AppTab.entries.size - 1
    }

    /**
     * A wire value the enum does not have is ignored, and the tab shows.
     *
     * The safe direction: a value we cannot resolve is one we cannot name, and hiding a tab because of an
     * unreadable preference would be a screen disappearing for a reason the user cannot see.
     */
    @Test
    fun `an unknown wire value does not hide anything`() {
        val hidden = listOf("a-tab-that-was-removed", AppTab.TOOLS.wireValue)
        val tabs = TabLayout.visibleTabs(hidden, selected)
        (AppTab.TOOLS in tabs) shouldBe false
        tabs.size shouldBe AppTab.entries.size - 1
    }

    // MARK: - The selected tab

    /**
     * The selected tab is shown even when the hidden set names it.
     *
     * Hiding the tab you are standing on leaves a screen with no bar item to return to it, so the guard is applied
     * on **read** as well as on write — a stored preference can come from an import, or from a version that did not
     * have the rule.
     */
    @Test
    fun `the selected tab is shown even when the stored set hides it`() {
        val hidden = listOf(AppTab.JOURNAL.wireValue, AppTab.TOOLS.wireValue)
        val tabs = TabLayout.visibleTabs(hidden, selected = AppTab.JOURNAL)
        (AppTab.JOURNAL in tabs) shouldBe true
        (AppTab.TOOLS in tabs) shouldBe false
    }

    // MARK: - The last tab

    /** The last visible tab cannot be hidden. */
    @Test
    fun `the last visible tab cannot be hidden`() {
        TabLayout.canHide(AppTab.JOURNAL, listOf(AppTab.JOURNAL)) shouldBe false
        TabLayout.canHide(AppTab.JOURNAL, listOf(AppTab.JOURNAL, AppTab.LIBRARY)) shouldBe true
    }

    /** A tab that is not visible cannot be hidden again. */
    @Test
    fun `a hidden tab cannot be hidden`() {
        TabLayout.canHide(AppTab.TOOLS, listOf(AppTab.JOURNAL, AppTab.LIBRARY)) shouldBe false
    }

    // MARK: - The toggles

    /** Hiding a tab records it as hidden, and showing it again removes it from the set. */
    @Test
    fun `hiding and showing round-trips`() {
        val visible = TabLayout.visibleTabs(null, selected)
        val hidden = TabLayout.toggle(
            storedHidden = null,
            currentlyVisible = visible,
            selected = selected,
            tab = AppTab.TOOLS,
            visible = false,
        )
        (AppTab.TOOLS.wireValue in hidden) shouldBe true
        // And the tab is gone from the bar.
        (AppTab.TOOLS in TabLayout.visibleTabs(hidden, selected)) shouldBe false

        val shown = TabLayout.toggle(
            storedHidden = hidden,
            currentlyVisible = TabLayout.visibleTabs(hidden, selected),
            selected = selected,
            tab = AppTab.TOOLS,
            visible = true,
        )
        (AppTab.TOOLS.wireValue in shown) shouldBe false
        (AppTab.TOOLS in TabLayout.visibleTabs(shown, selected)) shouldBe true
    }

    /**
     * Hiding every tab in turn leaves one — the one-way trip, walked to its end.
     *
     * **This is the test that found the visible-set bug.** With the visible set stored, hiding four of five tabs
     * brought all five back, because each removed tab became "unmentioned" and was appended on the next read.
     */
    @Test
    fun `hiding every tab in turn leaves one`() {
        var stored: List<String>? = null
        var visible = TabLayout.visibleTabs(stored, selected)
        for (tab in AppTab.entries) {
            stored = TabLayout.toggle(
                storedHidden = stored,
                currentlyVisible = visible,
                selected = selected,
                tab = tab,
                visible = false,
            )
            visible = TabLayout.visibleTabs(stored, selected)
        }
        // One tab survives, and it is the one the user is standing on.
        visible shouldContainExactly listOf(selected)
    }

    /** The toggle refuses to hide the selected tab, even when asked to directly. */
    @Test
    fun `the toggle refuses to hide the selected tab`() {
        val visible = TabLayout.visibleTabs(null, selected)
        val after = TabLayout.toggle(
            storedHidden = null,
            currentlyVisible = visible,
            selected = selected,
            tab = selected,
            visible = false,
        )
        (selected in TabLayout.visibleTabs(after, selected)) shouldBe true
    }

    /**
     * The toggle's result is always a **complete** statement.
     *
     * Every tab is either in the hidden set or not, so nothing is ever left unmentioned and the append rule is not
     * needed at all. Asserted because a partial set would reintroduce the bug: a tab absent from a partial set
     * reads as visible whether or not the user hid it.
     */
    @Test
    fun `the toggle's result names every hidden tab`() {
        val visible = TabLayout.visibleTabs(null, selected)
        val hidden = TabLayout.toggle(
            storedHidden = null,
            currentlyVisible = visible,
            selected = selected,
            tab = AppTab.LIBRARY,
            visible = false,
        )
        hidden shouldContainExactly listOf(AppTab.LIBRARY.wireValue)
        // And the bar is exactly the entries minus that one.
        TabLayout.visibleTabs(hidden, selected) shouldContainExactly
            AppTab.entries.filterNot { it == AppTab.LIBRARY }
    }

    // MARK: - Labels

    /**
     * Labels show unless the user turns them off, and a short bar always shows them.
     *
     * Two tabs with no labels is a pair of unlabelled icons, which is harder to read than two words — so the
     * preference is overridden below three rather than obeyed into a worse bar.
     */
    @Test
    fun `a short bar shows labels whatever the preference says`() {
        TabLayout.showsLabels(tabCount = 2, labelsPreferred = false) shouldBe true
        TabLayout.showsLabels(tabCount = 1, labelsPreferred = false) shouldBe true
        TabLayout.showsLabels(tabCount = 3, labelsPreferred = false) shouldBe false
        TabLayout.showsLabels(tabCount = 5, labelsPreferred = false) shouldBe false
        TabLayout.showsLabels(tabCount = 5, labelsPreferred = true) shouldBe true
    }
}
