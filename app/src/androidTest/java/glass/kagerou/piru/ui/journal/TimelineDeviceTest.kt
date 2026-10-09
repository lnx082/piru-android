package glass.kagerou.piru.ui.journal

import androidx.activity.ComponentActivity
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
 * The continuous timeline renders against a real store, on a device.
 *
 * ## Why this is instrumentation and not JVM
 * It reads the Room log *and* the substance catalogue — the tint lookup opens the 18 MB file — and the
 * JVM harness has neither at once. The failure worth ruling out is the screen's own empty state being
 * indistinguishable from a failed read:
 *
 * - `0 doses` is a **correct** answer for a fresh install, so the count line has to appear either way.
 * - A read that threw must not look the same, which is why the screen counts what it loaded rather than
 *   falling through to a blank list.
 *
 * So the assertion is that the header and its count line both draw, on whatever the store happens to
 * hold. Asserting a non-zero count would be a spec that passes only on a phone someone has used.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TimelineDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * The timeline draws its title and a count, whatever the log holds.
     *
     * The count is matched by its "doses" suffix so it holds for zero and for many — the two cases the
     * screen has to render identically apart from the number.
     */
    @Test
    fun theTimelineDrawsTitleAndCount() {
        compose.setContent { PiruTheme { TimelineScreen(AppNavigator()) } }

        // 120 s, not 20. The case draws in **3.7 s alone** and took **21.3 s** in a full-suite run — the same
        // bimodality the tag cases have, where the whole run is sometimes six times slower and no measurement has
        // found the cause. A 20 s budget sat inside that spread, so it failed on the slow runs and passed on the
        // fast ones; 120 s is the budget every other spec in the suite already uses, and it is margin against a
        // 3.7 s quiet run rather than a fudge.
        //
        // Restarting the AVD has cleared the slow mode every time it has appeared, so a timeout here means
        // "restart the emulator" before it means "look for a bug in the app".
        compose.waitUntil(timeoutMillis = 120_000) {
            compose.onAllNodesWithText("doses", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Timeline").assertIsDisplayed()
        compose.onNodeWithText("doses", substring = true).assertIsDisplayed()
    }
}
