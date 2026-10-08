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
        // ## This spec is unreliable in a full-suite run, and the cause is not yet explained
        // Three versions of this comment have each claimed a cause and been wrong. What is actually measured:
        //
        //   fresh boot, load 1.46, class alone      BUILD SUCCESSFUL in 35s    3/3, this case 1.772s
        //   same emulator, full suite (twice)       BUILD FAILED in 2m52s / 4m54s   121s / 121s
        //   degraded emulator, load 7.96, 308 MB    BUILD FAILED in 4m11s
        //
        // So it is **fast alone and hangs in the suite**, on a fresh emulator with a low load average — which is
        // not the "starved machine" story, and the machine is not the whole of it. In a failing run the other 39
        // cases take about two seconds each while this one takes 121, so it is a stall in this path rather than
        // general slowness, and it only appears under the suite.
        //
        // Ruled out, so the next person does not redo it: the tag query is indexed
        // (`SEARCH tags USING INDEX idx_tags_tag (tag=?)`), `substancesWithTag` is that query plus map lookups, the
        // catalogue is memoized per process, and the class passes alone.
        //
        // The timeout is not the fix — it already exceeds 120 s — and neither is deleting the assertion, which is
        // the only check that a `hidden` gate has not swallowed every tag row. This comment records the state
        // rather than pretending to resolve it.
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

        // Settle first, then poll — same reason and the same unexplained suite-only stall as the first case in
        // this class.
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

        // Settle first, then poll — same reason and the same unexplained suite-only stall as the first case in
        // this class.
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 120_000) {
            compose.onAllNodesWithText("0 substances").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("0 substances").assertIsDisplayed()
    }
}
