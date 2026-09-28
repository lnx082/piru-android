package glass.kagerou.piru.ui.nav

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList

/**
 * The app's navigation state: which tab is showing, one push stack per tab, and
 * the modal stack.
 *
 * Ported from `Piru/Navigation/AppNavigator.swift`.
 *
 * ## Dismissal is a state mutation, not a callback
 * Upstream's header is emphatic about this and it is the reason the class exists
 * at all: dismissal here is a synchronous write to [sheetStack] rather than a
 * round-trip through the view tree. On iOS that difference is what killed the
 * "Done needs two taps" bug; the same shape is what keeps a Compose sheet from
 * closing one frame after it was asked to.
 *
 * ## Sheets that push
 * A sheet may host its own stack — quick log does, because the tray pushes a
 * picker on top of itself. While such a sheet is up, [push] targets *its* stack:
 * the tab stack is behind the sheet, so pushing there would navigate a screen the
 * user cannot see while the sheet stayed put. [sheetPaths] is keyed by depth so
 * two nested push-capable sheets each keep their own.
 *
 * ## Not persisted yet
 * Upstream restores the selected tab from `UserDefaults` at launch. That is a
 * settings dependency the MVP does not have yet, so this always starts on the
 * journal — noted rather than silently dropped, because "it forgot my tab" is a
 * bug report waiting to happen once the rest of the app feels finished.
 */
@Stable
class AppNavigator(
    initialTab: AppTab = AppTab.JOURNAL,
    initialPaths: Map<AppTab, List<PushRoute>> = emptyMap(),
) {

    var selectedTab: AppTab by mutableStateOf(initialTab)
        private set

    private val paths: Map<AppTab, SnapshotStateList<PushRoute>> =
        AppTab.entries.associateWith { tab ->
            (initialPaths[tab].orEmpty()).toMutableStateList()
        }

    /** The modal stack. The last element is what is presented. */
    val sheetStack: SnapshotStateList<SheetRoute> = emptyList<SheetRoute>().toMutableStateList()

    /** Push stacks belonging to push-capable sheets, keyed by their depth in [sheetStack]. */
    private val sheetPaths: MutableMap<Int, SnapshotStateList<PushRoute>> = mutableMapOf()

    // MARK: - Tabs

    /** Select a tab. Re-selecting the current one is a no-op rather than a reset. */
    fun select(tab: AppTab) {
        selectedTab = tab
    }

    /** The tab's push stack, for a `NavHost` to observe. */
    fun path(tab: AppTab = selectedTab): SnapshotStateList<PushRoute> = paths.getValue(tab)

    /** The push stack of the push-capable sheet at [depth], if it has one. */
    fun sheetPath(depth: Int): SnapshotStateList<PushRoute> =
        sheetPaths.getOrPut(depth) { emptyList<PushRoute>().toMutableStateList() }

    // MARK: - Push

    /**
     * Push onto the visible stack: the top push-capable sheet's when one is up,
     * otherwise [tab]'s.
     *
     * An explicit [tab] always targets that tab, which is what a deep link or a
     * widget tap needs — it must not be redirected into whatever sheet the user
     * happened to leave open.
     */
    fun push(route: PushRoute, tab: AppTab? = null) {
        val target = if (tab == null) visibleStack() else path(tab)
        target.add(route)
    }

    /**
     * Pop the visible stack, or the one named by [tab].
     *
     * Returns whether anything was popped, so a caller can decide to fall through
     * to something else — an empty stack is a real answer, not an error.
     */
    fun pop(tab: AppTab? = null): Boolean {
        val target = if (tab == null) visibleStack() else path(tab)
        if (target.isEmpty()) return false
        target.removeAt(target.lastIndex)
        return true
    }

    /** Clear the visible stack back to its root. */
    fun popToRoot(tab: AppTab? = null) {
        val target = if (tab == null) visibleStack() else path(tab)
        target.clear()
    }

    private fun visibleStack(): SnapshotStateList<PushRoute> {
        val depth = sheetStack.lastIndex
        val top = sheetStack.lastOrNull()
        return if (depth >= 0 && top != null && top.supportsPushNavigation) sheetPath(depth) else path()
    }

    // MARK: - Sheets

    /** Present [route], or bring it forward when it is already the top of the stack. */
    fun present(route: SheetRoute) {
        if (sheetStack.lastOrNull() == route) return
        sheetStack.add(route)
    }

    /**
     * Dismiss the top sheet, or the whole stack when [all] is set.
     *
     * Synchronous on purpose — see the class note. A dismissal that has to travel
     * through the view tree is one that can be dropped, and the symptom is a sheet
     * that will not close.
     */
    fun dismiss(all: Boolean = false) {
        if (sheetStack.isEmpty()) return
        val depth = sheetStack.lastIndex
        sheetPaths.remove(depth)?.clear()
        if (all) {
            for (d in sheetStack.indices) sheetPaths.remove(d)
            sheetStack.clear()
        } else {
            sheetStack.removeAt(depth)
        }
    }

    /** True when [route] is anywhere on the modal stack. */
    fun isPresented(route: SheetRoute): Boolean = route in sheetStack

    // MARK: - Cross-cutting invalidation

    /**
     * Bumped whenever a write makes anything a screen cached stale.
     *
     * A stand-in for the real thing. The stores expose `Flow`s and a screen that
     * observes one reloads on its own; this port loads once and has no view-model
     * layer yet, so a screen that must reload watches this counter instead.
     *
     * It is named for *data* rather than for the dose log because that is the
     * scope: a substance colour changes what the journal draws but not what it
     * read, and a counter called `dataVersion` invited exactly that mistake —
     * bumping it on a colour edit looked wrong, so it was not bumped, and the
     * journal kept a palette it had already cached.
     */
    var dataVersion: Int by mutableStateOf(0)
        private set

    /** Record that stored data a screen may have cached has changed. */
    fun invalidate() {
        dataVersion++
    }
}
