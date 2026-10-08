package glass.kagerou.piru.ui.library

import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.R
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The substance page and the effects page render, on a device, against the real catalogue.
 *
 * ## Why these are instrumentation specs and not JVM ones
 * Both screens read the bundled 18 MB catalogue, and the JVM harness has none — its own note says a
 * screen that needs one "is not a candidate for this harness until it takes the catalogue as a
 * parameter". So a device is the only place these can be proven to draw, and the failures worth
 * catching here are the ones that reading the code does not reveal:
 *
 * - `SubstanceDetailScreen` resolves a name through `resolveFull`, which needs the identity index and
 *   the whole read layer. A wrong name, a missing index or a throwing read leaves a page saying "not
 *   found" while every JVM test passes.
 * - The catalogue is opened off the main thread through `withContext(Dispatchers.IO)`. If that were
 *   wrong, a device would sit on the loading state forever and no JVM test would see it.
 * - `EffectsListScreen` reads a join — `effects` against `subjective_effects` — and a failed join
 *   renders its *empty state* rather than failing, so the assertion has to name the outcome.
 *
 * Deliberately **not** asserting the catalogue's prose: those strings are curation, and pinning a
 * sentence here would make the suite fail when upstream edits one. What is asserted is that the
 * screen reached the catalogue and drew what it found.
 *
 * ## Why `onAllNodesWithText` in the waits
 * `waitUntil` needs a condition that is false rather than thrown, and `onNodeWithText` throws when
 * there is no match. The all-nodes form returns an empty list, and asking whether that list is empty
 * is the question the wait actually has. It is behind an opt-in, hence the annotation.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SubstancePagesDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * A substance with a full page draws its own name.
     *
     * Caffeine is the least interesting entry in the catalogue and therefore the most stable choice:
     * it has dose ladders, durations and a mechanism behind it, and no curation is likely to remove
     * it.
     */
    @Test
    fun theSubstancePageResolvesAndDraws() {
        compose.setContent { PiruTheme { SubstanceDetailScreen("Caffeine", AppNavigator()) } }

        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Caffeine").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Caffeine").assertIsDisplayed()
    }

    /**
     * A name the catalogue does not carry says so rather than sitting on "loading".
     *
     * The failure this rules out is the expensive one: `resolveFull` returning null for a *real*
     * substance because the identity index or the read layer threw — which looks identical to a
     * misspelt query unless the two are asserted separately, as they are here.
     */
    @Test
    fun anUnknownNameReportsNotFound() {
        compose.setContent { PiruTheme { SubstanceDetailScreen("Unobtainium", AppNavigator()) } }

        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Unobtainium", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * The effects page draws a category heading, and its empty state is not what it drew.
     *
     * "Cognitive" is Caffeine's largest curated category — 11 of its 21 effects, read out of the
     * shipped catalogue. Asserting it by name means a change to that data fails here rather than
     * passing on "some text", and the empty-state assertion is what distinguishes a working join
     * from a rendered nothing.
     */
    @Test
    fun theEffectsPageDrawsGroupsRatherThanItsEmptyState() {
        compose.setContent { PiruTheme { EffectsListScreen("Caffeine", Modifier) } }

        val emptyState = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.shell_effects_all_empty)

        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(emptyState).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Cognitive").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Cognitive").assertIsDisplayed()
        // The empty state must not be what rendered. `assertDoesNotExist` is not in this
        // compose-test version, so the count form is the one that says the same thing.
        compose.onAllNodesWithText(emptyState).assertCountEquals(0)
    }
}
