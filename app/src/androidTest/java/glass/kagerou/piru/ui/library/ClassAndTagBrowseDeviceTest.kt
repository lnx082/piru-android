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
        // ## This spec is intermittently slow in a full suite, fast alone, and the cause is unidentified
        // Roughly nine runs on this emulator: five failures, four passes, in both directions. The experiments:
        //
        //   fresh boot, class alone (120s budget)     SUCCESSFUL, 3/3, this case 1.772s
        //   full suite (120s budget)                  FAILED, this case 121.884s, its sibling 122.747s
        //   class alone (300s budget)                 SUCCESSFUL, 3/3, 2.6-3.3s
        //   full suite (300s budget)                  SUCCESSFUL in 61s, 40/40, nothing over 5s
        //   up 1h15m, full suite (43 specs)            FAILED in 6m53s, this case 304.482s
        //   after restarting the AVD, same suite       SUCCESSFUL in 71s, 43/43, nothing over 10s
        //
        // **Restarting the emulator is what fixes it.** That has now happened twice — the first time a run failing
        // three ways at 4m11s went green at 55s with no code change. So the useful instruction here is "restart the
        // AVD", not "this spec is flaky", and a timeout in this file should send the reader there first.
        //
        // and the probes that ruled out every mechanism I could name:
        //
        //   app.catalog() warm (memoized in-process)   0 ms
        //   app.catalog() after deleting the file      0 ms
        //   installer re-verify after deleting it    138 ms
        //   substancesWithTag(known, 222 rows)       764 ms
        //   substancesWithTag(unknown)                 2 ms
        //
        // So nothing in this path is slow when measured, the tag query is indexed, and the catalogue is memoized
        // per process. I tried warming the catalogue in a `@Before` and **removed it**, because the probe data
        // contradicts the hypothesis it was based on and an unproven change in a test reads as the fix.
        //
        // The budget is 300 s. A failing run passes at 300 s with every case under 5 s, which is margin against a
        // 61 s green suite — and a 120 s budget already failed, so raising it is not what makes a bad run pass either.
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 300_000) {
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

        // Settle first, then poll — same reason and the same unexplained suite-only slowness as the first case
        // in this class.
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 300_000) {
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

        // Settle first, then poll — same reason and the same unexplained suite-only slowness as the first case
        // in this class.
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 300_000) {
            compose.onAllNodesWithText("0 substances").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("0 substances").assertIsDisplayed()
    }
}
