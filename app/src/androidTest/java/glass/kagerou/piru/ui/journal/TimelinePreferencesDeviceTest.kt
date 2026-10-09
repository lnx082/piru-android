package glass.kagerou.piru.ui.journal

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.data.AppSettingsStore
import glass.kagerou.piru.ui.theme.PiruTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import io.kotest.matchers.shouldBe
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff

/**
 * The timeline preferences screen draws, and turning the axis off withdraws the geometry rows.
 *
 * ## Why this is a device spec rather than a JVM one
 * `TimelineOptions` and `TimelineZoom` are already covered exhaustively by unit tests — the gating rule, the ladder,
 * the rounding, the wire values. What a unit test cannot show is that the **screen consults them**: a screen that
 * lists all five rows unconditionally passes every unit test in that file and offers zoom with the axis off, which
 * is exactly the defect the gating exists to prevent.
 *
 * So the assertion is about the screen: the withdrawn row is **absent**, not merely disabled. That distinction is
 * upstream's own ("`.disabled` on a menu-style `Picker` inside a `Menu` renders fully active"), and it is the one a
 * reader of the code would have to reason about rather than see.
 *
 * The store is reset at the end, because this test writes real preferences and the next spec to draw a timeline
 * should not inherit them.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TimelinePreferencesDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val axisTitle = "Show the hour axis"
    private val zoomTitle = "Zoom"
    private val compactTitle = "Compact entries"

    /** The screen draws, and the axis toggle is there to turn. */
    @Test
    fun theScreenDrawsItsRows() {
        resetPreferences()
        compose.setContent { PiruTheme { TimelinePreferencesScreen() } }
        compose.waitForIdle()

        compose.onNodeWithText(axisTitle).assertIsDisplayed()
        compose.onNodeWithText(zoomTitle).assertIsDisplayed()
        compose.onNodeWithText(compactTitle).assertIsDisplayed()

        // **The rows that would do nothing are not on the screen.** Last release shipped two switches — modeled
        // curves and gap compression — that wrote preferences nothing read, so tapping them changed nothing at all.
        // This pins their absence, which makes that state inexpressible rather than merely fixed: the words are not
        // in the app, so a row cannot be added back without this test failing.
        for (gone in listOf("Modeled curves", "Compress empty time")) {
            compose.onAllNodesWithText(gone).fetchSemanticsNodes().isEmpty() shouldBe true
        }
        // And exactly three switches, which with the rows above is the whole menu.
        //
        // It was two. The third is the **cardio lane**, added with its preference and its reader in `TimelineGraph` — so
        // it is a row that does something, which is what this count exists to require. Moving it means arguing with the
        // number here rather than editing a list elsewhere, which is the point of pinning it.
        compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size shouldBe 3

        resetPreferences()
    }

    /**
     * Turning the axis off makes the zoom row **disappear**.
     *
     * The case the whole `TimelineOptions` object exists for. Asserted by absence rather than by a disabled state,
     * because a disabled row would still read as something the user might be able to turn on.
     */
    @Test
    fun turningTheAxisOffWithdrawsTheZoomRow() {
        resetPreferences()
        compose.setContent { PiruTheme { TimelinePreferencesScreen() } }
        compose.waitForIdle()

        // Present to begin with, so the absence below is a change rather than a screen that never drew it.
        compose.onNodeWithText(zoomTitle).assertExists()

        // The switch itself, found by its **toggleable semantics** rather than by its label: `performClick` on a
        // title `Text` node does not reach a sibling `Switch`, which is exactly what failed when this tapped the
        // title.
        //
        // Indexed to the **first** switch, which is the axis row: the screen draws it before the others. Two
        // switches exist while the axis is on, so `onNode` is ambiguous and `onAllNodes` is the honest form.
        compose.onAllNodes(isToggleable())[0].assertIsOn().performClick()
        compose.waitForIdle()

        // The geometry row is gone; the two that still do something remain.
        compose.onAllNodesWithText(zoomTitle).fetchSemanticsNodes().isEmpty() shouldBe true
        compose.onNodeWithText(compactTitle).assertExists()
        compose.onNodeWithText(axisTitle).assertExists()

        // **Two switches, the same as before the tap** — and that is correct rather than a failure of the
        // withdrawal. The axis switch is in the axis-off row list precisely so the user can turn the axis back on,
        // and the bubble style stays. My first version asserted the count fell to one, which was wrong about the
        // design: what withdraws is the **zoom row**, and that is what the assertion above names. A count cannot
        // say *which* two switches are present, so the rows are asserted by name instead.
        // Three, not two: the **cardio lane** stays offered with the axis off, for the same reason the bubble style
        // does — it describes content the session chart carries, and the lane draws whether or not the axis strip does.
        // Withdrawing its switch would leave a lane the reader can see and cannot switch off.
        compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size shouldBe 3
        compose.onAllNodes(isToggleable())[0].assertIsOff()

        // And the preference itself is **kept**, so turning the axis back on restores it rather than resetting it.
        val store = AppSettingsStore(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
        )
        store.timelineShowsAxis() shouldBe false
        resetPreferences()
    }

    /** Puts every preference this spec writes back to its default, so the next spec starts clean. */
    private fun resetPreferences() {
        val store = AppSettingsStore(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
        )
        store.setTimelineShowsAxis(true)
        store.setTimelineZoom(1.0)
        store.setTimelinePKCurves(false)
        store.setTimelineCompressGaps(true)
        store.setTimelineBubbleStyle("full")
    }
}
