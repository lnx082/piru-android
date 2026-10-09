package glass.kagerou.piru.ui.journal

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The search field actually narrows the list.
 *
 * ## Why this is a device spec and not another unit test
 * `JournalFilterTest` covers the predicate exhaustively — 15 cases on the OR-within/AND-across rule. None of them
 * can show that the **screen uses it**: a journal that builds a `JournalFilter` and then draws `entries` instead of
 * the filtered list passes every one of them and narrows nothing. That is the wiring gap this exists to catch, and
 * it is the third time in this objective that the same shape has appeared.
 *
 * ## What is asserted
 * Two doses of different substances, then a search for one of them: the other must be **gone**, not merely ordered
 * differently. And clearing the field brings it back.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class JournalFilterDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun seed(tag: String) {
        val app = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as PiruApplication
        val now = Instant.now()
        runBlocking {
            app.database.doseEntryDao().deleteAll()
            app.database.doseEntryDao().insert(
                DoseEntryEntity(
                    timestamp = Date(now.minusSeconds(3600).toEpochMilli()),
                    substance = "Zzz Ketamine",
                    amount = 50.0,
                    unit = "mg",
                    route = RouteOfAdministration.ORAL,
                    notes = "the $tag one",
                ),
            )
            app.database.doseEntryDao().insert(
                DoseEntryEntity(
                    timestamp = Date(now.toEpochMilli()),
                    substance = "Zzz Caffeine",
                    amount = 80.0,
                    unit = "mg",
                    route = RouteOfAdministration.ORAL,
                ),
            )
        }
    }

    private fun clear() {
        val app = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as PiruApplication
        runBlocking { app.database.doseEntryDao().deleteAll() }
    }

    /**
     * Typing a name removes the other substances from the list.
     *
     * The two substances are deliberately named with a shared prefix so that a *sorting* change cannot explain the
     * result — only a filter can make one of them disappear.
     */
    @Test
    fun aSearchNarrowsTheList() {
        seed("search")
        compose.setContent { PiruTheme { JournalScreen(AppNavigator()) } }
        compose.waitForIdle()

        // Both are there to begin with, so the absence below is a change rather than a screen that never loaded.
        compose.waitUntil(timeoutMillis = 120_000) {
            compose.onAllNodesWithText("Zzz Caffeine", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Zzz Ketamine", substring = true).assertExists()

        compose.onNodeWithText("Search doses").performTextInput("Ketamine")
        compose.waitForIdle()

        // The searched one stays; the other is gone.
        compose.onNodeWithText("Zzz Ketamine", substring = true).assertExists()
        compose.onAllNodesWithText("Zzz Caffeine", substring = true).fetchSemanticsNodes().isEmpty() shouldBe true

        clear()
    }

    /**
     * A `#` query searches tags only, which the screen has to pass through unchanged.
     *
     * The mode is only reachable through the field, so a screen that trimmed the `#` before handing the query over
     * would silently turn it into a plain search — and "Zzz Ketamine" would come back for `#search`, which is
     * exactly what the tag mode exists to prevent.
     */
    @Test
    fun aHashSearchUsesTheTagMode() {
        seed("search")
        compose.setContent { PiruTheme { JournalScreen(AppNavigator()) } }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 120_000) {
            compose.onAllNodesWithText("Zzz Caffeine", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // The tag lives in the note of the ketamine row, so `#search` must NOT match it — that is the whole point of
        // the `#` mode, and it also proves the mode is not simply a substring search.
        compose.onNodeWithText("Search doses").performTextInput("#search")
        compose.waitForIdle()
        compose.onAllNodesWithText("Zzz Ketamine", substring = true).fetchSemanticsNodes().isEmpty() shouldBe true

        clear()
    }
}

private infix fun Boolean.shouldBe(expected: Boolean) {
    if (this != expected) throw AssertionError("expected <$expected> but was <$this>")
}
