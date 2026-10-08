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

        // Settle first, then poll: the screen opens the catalogue on a background dispatcher before it draws,
        // and a bare `waitUntil` on the first node races that read.
        //
        // ## The timeout is for a **starved emulator**, and that was measured rather than assumed
        // My first version of this comment claimed a "first-use cost" and sized the number for it. That was a
        // guess. What the runs since showed:
        //
        //   fresh boot   load 1.34, free 459 MB   BUILD SUCCESSFUL in 55s    40/40
        //   under load   load 7.96, free 308 MB   BUILD FAILED in 4m11s      1 failure
        //
        // and in a failing run this spec's three cases took 121.3s, 2.4s and 2.4s — so the slow one was starved
        // rather than doing slow work. The product-side candidates are ruled out: the tag query is indexed
        // (`SEARCH tags USING INDEX idx_tags_tag`), `substancesWithTag` is that query plus map lookups, and the
        // catalogue is memoized per process so a warm run re-opens nothing.
        //
        // 120 s against a 55 s quiet run is generous. Raising it again would hide a starved emulator instead of
        // fixing anything, which is why the number stays here.
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

        // Settle first, then poll — same reason and same environment caveat as the first case in this class:
        // the number is sized for a starved emulator, not for the work.
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

        // Settle first, then poll — same reason and same environment caveat as the first case in this class:
        // the number is sized for a starved emulator, not for the work.
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 120_000) {
            compose.onAllNodesWithText("0 substances").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("0 substances").assertIsDisplayed()
    }
}
