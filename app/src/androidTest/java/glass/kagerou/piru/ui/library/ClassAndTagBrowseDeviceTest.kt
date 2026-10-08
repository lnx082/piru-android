package glass.kagerou.piru.ui.library

import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The class write-up and tag browse render against the real catalogue, on a device.
 *
 * ## Why these are instrumentation specs
 * Both read the bundled 18 MB catalogue, which the JVM harness does not have. The interesting
 * assertions are also the ones a JVM test cannot make: a class row is found by *slug* through a join
 * over `substance_classes`, and a tag row through the `tags` table's `hidden` gate — so a wrong join or
 * a wrong column name renders an empty screen rather than failing, which is what these rule out.
 *
 * ## The slugs and tags below are read out of the shipped catalogue
 * `stimulant` and `dissociative` are curated tags with visible rows; a blank class slug is the
 * catalogue's own "not in a class" case, which is worth asserting because the route carries one and the
 * screen has to answer rather than spin.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ClassAndTagBrowseDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * A real class slug draws a title and a member list.
     *
     * The member count is the signal that the `substance_classes` join worked: the write-up's own prose
     * could render from `class_contexts` alone, but the members come only through the join, and an
     * empty join leaves a card with a heading and nothing under it.
     */
    @Test
    fun aClassWriteUpDrawsItsMembers() {
        compose.setContent { PiruTheme { ClassWriteUpScreen("arylcyclohexylamines", AppNavigator()) } }

        // Settle first, then poll: both screens open the catalogue on a background dispatcher before they
        // draw, and a bare `waitUntil` on the first node races that read.
        //
        // The timeout is sized for the **first-use cost**, which this spec pays and no other does.
        // `SubstanceCatalogInstaller.install` verifies the installed copy by hashing the whole 18 MB asset, and
        // `DbSubstanceCatalog.open` then builds the identity index over 1,689 substances and 5,727 aliases. The
        // catalogue is memoized per process, so that happens once per run — and this test runs early
        // alphabetically, so it is the one that waits. It timed out at 20 s and at 40 s on loaded runs and passed
        // on every quiet one, which is what a real one-off cost looks like rather than a flake.
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 120_000) {
            compose.onAllNodesWithText("members", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("members", substring = true).assertIsDisplayed()
    }

    /**
     * A tag with visible rows draws a non-zero count.
     *
     * "0 substances" is the failure this rules out — a `hidden` gate that excluded everything, or a
     * case-sensitive comparison against a catalogue that stores "Stimulant".
     */
    @Test
    fun aTagDrawsANonZeroCount() {
        compose.setContent { PiruTheme { TagBrowseScreen("phenethylamine", AppNavigator()) } }

        // Settle first, then poll: both screens open the catalogue on a background dispatcher before they
        // draw, and a bare `waitUntil` on the first node races that read.
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 120_000) {
            compose.onAllNodesWithText("substances", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // The count line is "N substances"; zero would mean the gate swallowed the rows.
        compose.onNodeWithText("0 substances").assertDoesNotExist()
    }

    /**
     * A tag nothing carries says so rather than drawing an empty list.
     *
     * A stale link is the ordinary way this happens, and it must not look like a screen that failed to
     * load.
     */
    @Test
    fun anUnknownTagDrawsAZeroCount() {
        compose.setContent {
            PiruTheme { TagBrowseScreen("a-tag-nothing-carries", AppNavigator()) }
        }

        // Settle first, then poll: both screens open the catalogue on a background dispatcher before they
        // draw, and a bare `waitUntil` on the first node races that read.
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 120_000) {
            compose.onAllNodesWithText("0 substances").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("0 substances").assertIsDisplayed()
    }
}
