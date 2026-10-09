package glass.kagerou.piru.ui.library

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The class write-up and tag browse render against the real catalogue, on a device.
 *
 * ## Why these are instrumentation specs
 * Both read the bundled 18 MB catalogue, which the JVM harness does not have. The interesting assertions are also
 * the ones a JVM test cannot make: a class row is found by *slug* through a join over `substance_classes`, and a tag
 * row through the `tags` table's `hidden` gate — so a wrong join or a wrong column name renders an empty screen
 * rather than failing, which is what these rule out.
 *
 * The slugs and tags below are read out of the shipped catalogue: `stimulant` and `dissociative` are curated tags
 * with visible rows, and a blank class slug is the catalogue's own "not in a class" case.
 *
 * ## One real defect in this area is fixed; a stall remains and it is the harness's
 *
 * **Fixed.** `aSmallTagDrawsItsCount` used to fail with
 *
 *     java.lang.IndexOutOfBoundsException: Index 1, size 1
 *       at androidx.compose.foundation.lazy.layout.MutableIntervalList.get(IntervalList.kt:227)
 *       at androidx.compose.foundation.lazy.layout.LazyLayoutIntervalContent.getKey(...)
 *
 * — the lazy list's interval bookkeeping disagreeing with its provider during a measure. `TagBrowseScreen` now gives
 * its header an explicit key and keys its rows by index and id, so a key cannot collide; four consecutive runs since
 * produced no such exception, where before it appeared in most failing runs. It was **not** a duplicate key: the
 * catalogue has 1,689 substances and 1,689 distinct ids.
 *
 * **Open, and measured.** A case still stalls at 300 s on some runs, and the stamps place it precisely:
 *
 *     the screen's effect      loaded = true at 9-31 ms, with the right row count
 *     a sibling case           conditionNow = true on its first poll, in 18 ms
 *     the stalled case         its own `waitForIdle` does not return
 *
 * So the condition is satisfiable and the data is present; `waitForIdle` is what does not return. Ruled out by
 * measurement: row count (a tag with **8** rows stalled while one with **222** passed in the same run), the catalogue
 * (0-1 ms warm), the installer verify (138 ms), the query plan (indexed), the emulator's load (it failed at 1.46
 * fresh and at 3.19 loaded), its disk (4.7 GB free) and its boot age.
 *
 * The budget is 300 s and the polls are stamped, because the stamps are what separated the fixed crash from this
 * open stall — seven theories were each disproved before they did.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ClassAndTagBrowseDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * Runs [body] and prints how long it took, so a failing run says where its time went.
     *
     * A diagnostic that stays: six theories have each been disproved by measuring something else, so the next
     * failure should measure **itself**.
     */
    private fun <T> stamp(label: String, body: () -> T): T {
        val start = System.currentTimeMillis()
        val out = body()
        println("TAGSTAMP " + label + " " + (System.currentTimeMillis() - start) + " ms")
        return out
    }

    /** The application, for the stamps. */
    private fun app(): PiruApplication =
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as PiruApplication

    /**
     * Polls until [condition] holds, printing progress so a slow run explains itself.
     *
     * The budget is 300 s, which is margin against a 39 s green suite. It is deliberately **not** larger: a failing
     * run already passes at 300 s and a 120 s budget also failed, so raising it is not what makes a bad run pass.
     *
     * ## What the two stamps established
     * In the run where this case stalled, the condition was polled **14 000 times over 275 s** and every one returned
     * empty — while the screen's own effect had finished at **9 ms with 6 rows**, and the class's **next** case found
     * its node on the **first** poll, in 18 ms.
     *
     * So the condition is satisfiable and the data is present; the tree is empty for whichever case sits in that
     * position. That is a harness question rather than an app one, and it is stated as such rather than guessed at.
     */
    private fun pollUntil(label: String, condition: () -> Boolean) {
        var polls = 0
        val start = System.currentTimeMillis()
        // Stamp the idle wait separately from the poll. `waitUntil` does both, and conflating them is why three
        // theories about the data were all wrong: the effect finishes in milliseconds and the stall is elsewhere.
        val idleStart = System.currentTimeMillis()
        compose.waitForIdle()
        println(
            "TAGSTAMP " + label + " waitForIdle=" + (System.currentTimeMillis() - idleStart) +
                "ms conditionNow=" + condition(),
        )
        compose.waitUntil(timeoutMillis = 300_000) {
            polls++
            if (polls % 2000 == 0) {
                println("TAGSTAMP " + label + " polls=" + polls + " at " + (System.currentTimeMillis() - start) + " ms")
            }
            condition()
        }
        println("TAGSTAMP " + label + " done polls=" + polls + " in " + (System.currentTimeMillis() - start) + " ms")
    }

    /**
     * A real class slug draws a title and a member list.
     *
     * The member count is the signal that the `substance_classes` join worked: the write-up's own prose could render
     * from `class_contexts` alone, but the members come only through the join, and an empty join leaves a card with a
     * heading and nothing under it.
     */
    @Test
    fun aClassWriteUpDrawsItsMembers() {
        compose.setContent { PiruTheme { ClassWriteUpScreen("arylcyclohexylamines", AppNavigator()) } }

        pollUntil("aClassWriteUpDrawsItsMembers") {
            compose.onAllNodesWithText("members", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("members", substring = true).assertIsDisplayed()
    }

    /**
     * A tag with visible rows draws a non-zero count.
     *
     * "0 substances" is the failure this rules out — a `hidden` gate that excluded everything, or a case-sensitive
     * comparison against a catalogue that stores "Stimulant".
     */
    @Test
    fun aTagDrawsANonZeroCount() {
        // Warm the path this screen depends on, so the poll below measures the screen rather than the catalogue.
        // These stamps are what established that neither call is the cost.
        val catalog = stamp("catalog()") { runBlocking { app().catalog() } }
        stamp("substancesWithTag") { catalog.substancesWithTag("phenethylamine") }

        compose.setContent { PiruTheme { TagBrowseScreen("phenethylamine", AppNavigator()) } }

        pollUntil("aTagDrawsANonZeroCount") {
            compose.onAllNodesWithText("substances", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // The count line is "N substances"; zero would mean the gate swallowed the rows.
        compose.onNodeWithText("0 substances").assertDoesNotExist()
    }

    /**
     * A tag nothing carries says so rather than drawing an empty list.
     *
     * A stale link is the ordinary way this happens, and it must not look like a screen that failed to load.
     */
    @Test
    fun anUnknownTagDrawsAZeroCount() {
        val catalog = stamp("catalog()") { runBlocking { app().catalog() } }
        stamp("substancesWithTag") { catalog.substancesWithTag("a-tag-nothing-carries") }

        compose.setContent { PiruTheme { TagBrowseScreen("a-tag-nothing-carries", AppNavigator()) } }

        pollUntil("anUnknownTagDrawsAZeroCount") {
            compose.onAllNodesWithText("0 substances").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("0 substances").assertIsDisplayed()
    }

    /**
     * A tag with a **handful** of rows draws its count, quickly.
     *
     * Added to isolate the one variable left: `phenethylamine` surfaces 222 rows and its screen intermittently takes
     * 300 s, while the tag that surfaces none never hangs. `phenothiazine` surfaces 8. It still proves the tag join —
     * a non-zero count is what the assertion is for — while drawing few enough rows that a stall here would mean the
     * row count is **not** the trigger.
     */
    @Test
    fun aSmallTagDrawsItsCount() {
        val catalog = stamp("catalog()") { runBlocking { app().catalog() } }
        stamp("substancesWithTag") { catalog.substancesWithTag("phenothiazine") }

        compose.setContent { PiruTheme { TagBrowseScreen("phenothiazine", AppNavigator()) } }

        pollUntil("aSmallTagDrawsItsCount") {
            compose.onAllNodesWithText("substances", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("0 substances").assertDoesNotExist()
    }
}
