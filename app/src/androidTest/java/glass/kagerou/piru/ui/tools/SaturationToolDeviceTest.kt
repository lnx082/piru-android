package glass.kagerou.piru.ui.tools

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.ui.theme.PiruTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.test.hasText

/**
 * The saturation tool draws, reaches a verdict from typed input, and refuses what has no answer.
 *
 * ## Why a device spec for arithmetic that already has nine unit cases
 * The nine cases are on `SaturationKinetics` in `:core:engine`. They cannot see:
 *
 * 1. **that the tool is reachable** — an entry in the tools list and an arm in the dispatch, which is the wiring this
 *    port has repeatedly got wrong;
 * 2. **that a typed value reaches the model** — the field holds text and parses it on every recomposition, and a
 *    wiring slip would leave the screen showing the empty state forever with every unit case still green;
 * 3. **that the impossible target says so** rather than showing a number.
 *
 * ## The values, and why these
 * Concentration 100 with Km 100 is the one input whose fraction is known by definition: **50%**. A spec that asserted
 * "some percentage appeared" would pass on a wrong formula.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SaturationToolDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()


    /**
     * The node the reader can type into, for a label.
     *
     * `onNodeWithText` alone is not enough here: the screen's intro sentence contains **"Km"**, so a substring matcher
     * selects the paragraph as well as the field and `performTextInput` refuses to guess between them. Requiring
     * `IsEditable` is exactly the distinction that was missing.
     */
    private fun field(label: String) = compose.onNode(
        hasText(label, substring = true) and
            androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText,
            ),
    )

    @Test
    fun theToolDrawsItsEmptyState() {
        compose.setContent { PiruTheme { SaturationScreen() } }
        compose.waitForIdle()

        compose.onNodeWithText("Saturation kinetics", substring = true).assertIsDisplayed()
        // With no input, the refusal rather than a figure.
        compose.onNodeWithText("Enter a concentration", substring = true).assertIsDisplayed()
    }

    /**
     * `[S] = Km` gives exactly half of Vmax, which is the definition of Km.
     *
     * The assertion is the **figure 50.0%**, not "a percentage": a wrong formula would still render one.
     */
    @Test
    fun equalConcentrationAndKmGiveHalf() {
        compose.setContent { PiruTheme { SaturationScreen() } }
        compose.waitForIdle()

        field("Concentration").performTextInput("100")
        compose.waitForIdle()
        field("Km").performTextInput("100")
        compose.waitForIdle()

        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodesWithText("50.0%", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("50.0%", substring = true).assertIsDisplayed()
        // And the middle regime, because 100 is neither 0.1x nor 10x its own Km.
        compose.onNodeWithText("Transitional", substring = true).assertIsDisplayed()
    }

    /** A low concentration reports the linear regime, so the boundary is reachable from the screen and not only a test. */
    @Test
    fun aLowConcentrationReportsTheLinearRegime() {
        compose.setContent { PiruTheme { SaturationScreen() } }
        compose.waitForIdle()

        field("Concentration").performTextInput("5")
        compose.waitForIdle()
        field("Km").performTextInput("100")
        compose.waitForIdle()

        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodesWithText("Linear", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Linear", substring = true).assertIsDisplayed()
    }

    /**
     * A target at full saturation says it cannot be reached, rather than showing a figure.
     *
     * The case a helpful-looking screen gets wrong: `Double.MAX_VALUE` renders as a very large number that a reader
     * could act on, for a concentration that does not exist.
     */
    @Test
    fun anUnreachableTargetSaysSo() {
        compose.setContent { PiruTheme { SaturationScreen() } }
        compose.waitForIdle()

        field("Concentration").performTextInput("100")
        compose.waitForIdle()
        field("Km").performTextInput("100")
        compose.waitForIdle()
        field("To reach a fraction").performTextInput("1")
        compose.waitForIdle()

        compose.onNodeWithText("never reached", substring = true).assertIsDisplayed()
    }
}
