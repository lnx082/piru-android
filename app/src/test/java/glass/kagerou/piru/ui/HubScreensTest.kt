package glass.kagerou.piru.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import glass.kagerou.piru.PiruTestApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.ui.insights.InsightsScreen
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import glass.kagerou.piru.ui.tools.ToolsScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The hub screens, asserted on the JVM.
 *
 * ## Why these two screens, and why a JVM test
 * The app module had no UI spec at all, so a screen that drew nothing, or that lost a
 * section, was only ever caught by someone looking at it. The two hubs are the cheapest
 * place to start and the most valuable: they are pure — a navigator and a static list of
 * groups — so they need no store, no catalogue and no permission, and they are the entry
 * point to most of the app. A hub that silently drops a card hides a whole feature.
 *
 * Robolectric rather than a device, so this can run in CI with no emulator. That is the
 * point of the exercise: the failures worth catching here are "it renders" and "its labels
 * are the ones a user reads", neither of which needs real hardware.
 *
 * ## The labels come from the resources, not from this file
 * Every expected string is read back through `getString`. A hard-coded expectation would
 * make this a copy of the catalog that goes stale the first time a translation lands, and
 * then it would fail for the wrong reason — or worse, pass against a string nobody ships.
 */
@RunWith(RobolectricTestRunner::class)
// `application` is the point: the real one schedules WorkManager on start-up, which
// `androidx.startup` normally initialises from the manifest and Robolectric does not
// run. See PiruTestApplication's note.
@Config(sdk = [34], application = PiruTestApplication::class)
class HubScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    private fun string(id: Int): String = resources.getString(id)

    /**
     * Insights draws its title, its subtitle, and the first card of its first group.
     *
     * The subtitle is asserted with the title because they are written as a pair: a hub
     * whose framing line went missing reads as a list of features, which is the opposite of
     * what this screen's copy is for.
     */
    @Test
    fun insightsDrawsItsHeadingAndItsFirstGroup() {
        compose.setContent { PiruTheme { InsightsScreen(AppNavigator()) } }

        compose.onNodeWithText(string(R.string.toolsb_insights_hub_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.toolsb_insights_hub_subtitle)).assertIsDisplayed()
        // A group heading, and one card inside it.
        compose.onNodeWithText(string(R.string.toolsb_insights_hub_group_right_now)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.toolsb_insights_hub_body_load_title)).assertIsDisplayed()
    }

    /**
     * And a card navigates.
     *
     * A hub whose cards do not route is a screen that looks right and does nothing, which
     * is exactly the kind of failure a render-only assertion misses.
     */
    @Test
    fun tappingAnInsightsCardPushesItsRoute() {
        val navigator = AppNavigator()
        compose.setContent { PiruTheme { InsightsScreen(navigator) } }

        compose.onNodeWithText(string(R.string.toolsb_insights_hub_body_load_title)).performClick()

        assertTrue(
            "tapping the body-load card should push a route; the stack was " +
                navigator.path().joinToString(),
            navigator.path().isNotEmpty(),
        )
    }

    /**
     * Tools draws its heading and its sections.
     *
     * Tools is the other hub, and the same argument applies: it is where the instruments
     * live, and a dropped section is a tool nobody can reach.
     */
    @Test
    fun toolsDrawsItsHeadingAndASection() {
        compose.setContent { PiruTheme { ToolsScreen(AppNavigator()) } }

        compose.onNodeWithText(string(R.string.tools_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.tools_group_in_me)).assertIsDisplayed()
    }
}
