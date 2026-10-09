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
 * ## The timeout, and an honest note about it
 * These cases are **bimodal**, and it is the whole run rather than the case:
 *
 *     49 cases, fast run      39.4 s total, every case under 4 s
 *     same suite, slow run   637.2 s total, the two tag cases 301 s each, everything else under 4 s
 *
 * Six explanations have each been disproved by measuring the thing itself — the installer verify (138 ms),
 * `app.catalog()` (0-1 ms, memoized per process), `substancesWithTag` (2-5 ms), the query plan (indexed), the
 * emulator's boot age (a failure at load 1.46 on a fresh boot), and a `@Before` warm-up (added, measured, removed,
 * because the probe data contradicted it). **The cost is not in the calls these cases make**, and I have not found
 * where it is.
 *
 * So the polls are stamped. A slow run prints how many attempts the wait made and when, which is the one thing the
 * timeout exception does not say — "the condition was never true" and "it became true after 40 000 polls" are very
 * different failures. **Restarting the AVD has cleared it every time it has happened**, so a timeout here means
 * "restart the emulator" before it means "look for a bug in the app".
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
     */
    private fun pollUntil(label: String, condition: () -> Boolean) {
        var polls = 0
        val start = System.currentTimeMillis()
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
}
