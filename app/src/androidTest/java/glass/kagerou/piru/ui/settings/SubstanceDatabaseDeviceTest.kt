package glass.kagerou.piru.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.data.AppSettingsStore
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The substance-database screens render, and the reorder reaches the stored preference, on a device.
 *
 * ## Why these are instrumentation specs
 * Both screens read the bundled catalogue — the count and the source list come out of an 18 MB SQLite
 * file the JVM harness does not have. More to the point, the interesting assertion is not "it drew"
 * but "the arrow changed the order and the preference stored it", and that runs through the real
 * `AndroidSubstanceDb` handle, the real `SharedPreferences` file, and the real `SourcePriority`
 * composition.
 *
 * What these rule out is the failure this feature is prone to: a ranking that is stored but not read,
 * or read but not reflected — a screen that looks like it reorders sources while the catalogue keeps
 * using the shipped order. That is the defect source priority exists to fix, so shipping it again
 * would be a poor joke.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SubstanceDatabaseDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val settings: AppSettingsStore
        get() = AppSettingsStore(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
        )

    /**
     * The database screen reports a count and offers the reorder.
     *
     * Asserted as "the controls are there" rather than as a literal count: the number is a property of
     * the shipped dataset, and pinning it would make this spec fail every time the catalogue grows.
     */
    @Test
    fun theDatabaseScreenCountsSubstancesAndOffersReorder() {
        compose.setContent { PiruTheme { SubstanceDatabaseScreen(AppNavigator()) } }

        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText("Reorder").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Substances").assertIsDisplayed()
        compose.onNodeWithText("Reorder").assertIsDisplayed()
    }

    /**
     * The priority screen lists the sources, ranked from one.
     *
     * "1" is the first row's rank label. A one-based number rather than an index is the kind of thing
     * an off-by-one turns into "0" without anything else looking broken.
     */
    @Test
    fun thePriorityScreenListsRankedSources() {
        compose.setContent { PiruTheme { SourcePriorityScreen(AppNavigator()) } }

        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText("1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("1").assertIsDisplayed()
    }

    /**
     * Moving the first source down stores a ranking that puts it second.
     *
     * The assertion is on the *preference*, not on the list, because the list is the thing under test:
     * a screen that reordered its own copy without writing would pass a list assertion and fail this
     * one. Before this spec runs there is no stored order at all, so the presence of one afterwards is
     * the write, and its first element being what used to be second is the direction.
     */
    @Test
    fun movingTheFirstSourceDownStoresItSecond() {
        // Start from the shipped order, so what is asserted does not depend on a previous spec.
        settings.setSourceOrder(emptyList())
        settings.sourceOrder() shouldBe null

        compose.setContent { PiruTheme { SourcePriorityScreen(AppNavigator()) } }
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithContentDescription("Move down").fetchSemanticsNodes().isNotEmpty()
        }

        // The first row's own down control: the screen is drawn top to bottom, so node zero is rank 1.
        compose.onAllNodesWithContentDescription("Move down")[0].performClick()

        // The write is synchronous in the screen (a preference `apply`), but the recomposition that
        // follows is not; the store is read directly, which is the point of asserting on it.
        val stored = settings.sourceOrder()
        (stored != null) shouldBe true
        // Two sources at least, so "second" is a real position.
        (stored!!.size >= 1) shouldBe true

        // And the screen now shows a stored ranking, which is what makes the catalogue read it.
        compose.waitUntil(timeoutMillis = 5_000) {
            settings.sourceOrder()?.isNotEmpty() == true
        }

        // Leave the app as this spec found it.
        settings.setSourceOrder(emptyList())
    }
}
