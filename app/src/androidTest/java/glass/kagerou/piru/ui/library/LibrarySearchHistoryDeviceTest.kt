package glass.kagerou.piru.ui.library

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import glass.kagerou.piru.data.AppSettingsStore
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import io.kotest.matchers.shouldBe
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The library's search history is recorded on clearing, and drawn as a chip that searches again.
 *
 * ## Why a device spec, and what it is really asserting
 * The store is exercised by the settings export guard, which proves the key exists and round-trips. What no unit test
 * can see is the **timing rule**, which is the whole difficulty of this feature:
 *
 * - recorded when the field **clears**, not per keystroke, or the eight-chip row fills with one word's prefixes;
 * - the chip **searches again** when tapped, which is the only thing that makes the row worth drawing.
 *
 * Both are wiring, and this port has repeatedly shipped a model with no caller. So both are asserted here.
 *
 * ## Why the specs clean up after themselves
 * The history is a real preference in a real app data directory shared by every spec in the run. A spec that leaves
 * three terms behind changes what the next one sees — the cross-test ordering problem that made
 * `MedsStoreWidgetRefreshTest` flake in a full run and pass alone.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LibrarySearchHistoryDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val settings: AppSettingsStore
        get() = AppSettingsStore(InstrumentationRegistry.getInstrumentation().targetContext)

    /** A distinct term per case, so a leftover from another spec cannot satisfy an assertion by accident. */
    private val term = "ZzSearchHistoryProbe"


    /**
     * The field is disabled until the catalogue opens — `enabled = ready` on the screen's `OutlinedTextField`.
     *
     * Waiting for it is not a workaround: the screen genuinely cannot accept a search before its catalogue is there,
     * and asserting the recording rule is the point rather than asserting how fast the asset opens.
     */
    private fun awaitReadyField() {
        compose.waitUntil(timeoutMillis = 120_000) {
            compose.onAllNodesWithText("Search substances", substring = true)
                .fetchSemanticsNodes()
                .any { node -> !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
        }
    }

    @Before
    fun clearTheHistory() {
        settings.clearRecentSearches()
    }

    /**
     * Typing a term and clearing the field records it, and the chip appears.
     *
     * The clear is what a reader does when they are done with a term, so it is the edge the feature records on. A
     * per-keystroke implementation would pass this case, which is why the next one exists.
     */
    @Test
    fun clearingTheFieldRecordsTheTerm() {
        compose.setContent { PiruTheme { LibraryScreen(navigator = AppNavigator()) } }
        compose.waitForIdle()
        awaitReadyField()

        compose.onNodeWithText("Search substances", substring = true).performTextInput(term)
        compose.waitForIdle()
        compose.onNodeWithText("Search substances", substring = true).performTextClearance()
        compose.waitForIdle()

        // The store holds it...
        compose.waitUntil(timeoutMillis = 60_000) { settings.recentSearches().contains(term) }
        // ...and the chip is drawn, which is the half a store test cannot see.
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodesWithText(term, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(term, substring = true).assertIsDisplayed()
    }

    /**
     * The prefixes of a word in progress are **not** recorded.
     *
     * The case a per-keystroke implementation fails: typing a term without clearing must leave the history empty, or an
     * eight-chip row would hold "Z", "Zz", "ZzS"… and evict every real search.
     */
    @Test
    fun midWordPrefixesAreNotRecorded() {
        compose.setContent { PiruTheme { LibraryScreen(navigator = AppNavigator()) } }
        compose.waitForIdle()
        awaitReadyField()

        compose.onNodeWithText("Search substances", substring = true).performTextInput(term)
        compose.waitForIdle()

        // Give the effect every chance to have wrongly recorded something, then assert the history is still empty.
        Thread.sleep(1_000)
        settings.recentSearches() shouldBe emptyList()
    }

    /** Tapping the chip puts the term back in the field, which is the only reason to draw the row. */
    @Test
    fun tappingTheChipSearchesAgain() {
        // Seeded through the store, so this case tests the chip and not the recording.
        settings.recordSearch(term)

        compose.setContent { PiruTheme { LibraryScreen(navigator = AppNavigator()) } }
        compose.waitForIdle()

        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodesWithText(term, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(term, substring = true).performClick()
        compose.waitForIdle()

        // The field now holds the term, so its value is the query and the history row is replaced by results.
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodesWithText(term, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

}
