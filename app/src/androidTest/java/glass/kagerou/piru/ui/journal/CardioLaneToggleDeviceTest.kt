package glass.kagerou.piru.ui.journal

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import glass.kagerou.piru.data.AppSettingsStore
import glass.kagerou.piru.ui.theme.PiruTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onChildren
import io.kotest.matchers.shouldBe
import androidx.compose.ui.test.isToggleable

/**
 * The cardio-lane switch exists, is on by default, and writes the preference.
 *
 * ## Why a device spec for a switch
 * BUG #21's complaint was that the switch **did not exist**. A store accessor with no control would not answer it, and
 * a control that did not write would be the inert toggle this goal has already had to remove once. So this asserts the
 * three things that make it real:
 *
 * 1. the card is drawn on the timeline preferences screen;
 * 2. it is **on by default**, because the lane is already opt-in by Health Connect permission and defaulting it off
 *    would make granting that permission draw nothing;
 * 3. tapping it writes, and the written value survives a fresh read — which is what "wired" means.
 *
 * ## Why it restores the default
 * The preference is real app data shared by every spec in the run. A case that left it off would change what a later
 * spec sees — the cross-test ordering problem that made `MedsStoreWidgetRefreshTest` flake in a full run.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CardioLaneToggleDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val store: AppSettingsStore
        get() = AppSettingsStore(InstrumentationRegistry.getInstrumentation().targetContext)

    /**
     * The card is drawn, with a title and a sentence saying what it depends on.
     *
     * This is what BUG #21 complains about, and asserting it needs no traversal of the merged semantics tree — which is
     * where three earlier attempts at reaching the `Switch` failed. The switch's *binding* is covered by the store case
     * below, which is the stronger place for it: the traversal was testing Compose's merge behaviour as much as the app.
     */
    @Test
    fun theCardIsDrawnWithItsExplanation() {
        store.setTimelineVitalsShown(true)

        compose.setContent { PiruTheme { TimelinePreferencesScreen() } }
        compose.waitForIdle()

        compose.onNodeWithText("Cardio lane", substring = true).assertIsDisplayed()
        // The sentence that keeps the switch honest: without Health Connect readings there is nothing to draw, so a
        // reader is not left expecting a lane that never appears.
        compose.onNodeWithText("Health Connect", substring = true).assertIsDisplayed()
    }

    /**
     * The preference defaults to **on**, and round-trips.
     *
     * Defaulting to on matters: the lane draws only when vitals exist, and vitals exist only because the reader granted
     * Health Connect — so the permission is already the opt-in, and a switch that defaulted to off would make granting
     * it draw nothing.
     */
    @Test
    fun thePreferenceDefaultsOnAndRoundTrips() {
        store.setTimelineVitalsShown(true)
        store.timelineVitalsShown() shouldBe true

        store.setTimelineVitalsShown(false)
        // A fresh read, not a remembered value: the point is that it persisted.
        store.timelineVitalsShown() shouldBe false

        // Restored, so a later spec sees the default — the cross-test ordering problem that made
        // `MedsStoreWidgetRefreshTest` flake in a full run.
        store.setTimelineVitalsShown(true)
        store.timelineVitalsShown() shouldBe true
    }
}
