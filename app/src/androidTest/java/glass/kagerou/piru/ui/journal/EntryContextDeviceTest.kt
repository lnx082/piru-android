package glass.kagerou.piru.ui.journal

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
 * The entry's "Around this dose" card renders against the real catalogue and a real database.
 *
 * ## Why this is an instrumentation spec
 * The card's window comes from the catalogue — the duration on the entry's **own route row** — so a JVM test can
 * only exercise the arithmetic in `EntryContext`, not the wiring. And the wiring is where the failure is
 * invisible: a wrong route lookup, a null catalogue or a suspend read made on the click leaves the card either
 * absent or drawing the *unjudged* wording, and **both look like a substance that simply had no duration**. So the
 * assertion here has to name the outcome rather than check that the card exists.
 *
 * ## What is seeded
 * Two doses of a substance the catalogue definitely has a duration for, three hours apart, in one session. Three
 * hours is inside half of a typical course, so the neighbour must come back **marked as overlapping** — which is
 * the only assertion that distinguishes "the window resolved" from "the window was null".
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class EntryContextDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** A substance with a route duration in the catalogue, and the route to use. */
    private val substance = "MDMA"
    private val route = RouteOfAdministration.ORAL

    /**
     * A neighbour inside the window is drawn and marked as overlapping.
     *
     * The load-bearing case: it fails if the catalogue lookup, the route match or the suspend read is wrong, and it
     * is the only case that can tell a resolved window from a null one.
     */
    @Test
    fun aNearbyDoseIsDrawnAndMarkedAsOverlapping() {
        val app = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as PiruApplication
        val database = app.database

        // The window is **measured from the catalogue**, not assumed. The first version of this test used a
        // three-hour offset on the belief that it sat inside half of MDMA's course; the card printed "3h before"
        // with no overlap label, and the probe's own `window=345.0` says why — the boundary is 172.5 minutes, so
        // 180 was outside it by 7.5. Read the number, then pick the offset from it.
        val window = runBlocking {
            app.catalog().lookup(substance)
                ?.routes?.firstOrNull { it.route == route }
                ?.duration?.estimatedTotalMinutes
        }
        check(window != null && window > 0) { "the catalogue has no duration for $substance/$route" }
        // A third of the window, which is inside half of it for any positive duration.
        val offsetSeconds = (window / 3.0).toLong() * 60L

        val now = Instant.now()
        val earlier = Date(now.minusSeconds(offsetSeconds).toEpochMilli())
        val later = Date(now.toEpochMilli())

        // Clear before seeding: these tests share the app database, and a row left by an earlier run changes which
        // neighbours the card draws. A test that depends on state it did not create is the same mistake as one
        // that passes vacuously.
        runBlocking { database.doseEntryDao().deleteAll() }

        val neighbourId = runBlocking {
            database.doseEntryDao().insert(
                DoseEntryEntity(
                    timestamp = earlier,
                    substance = substance,
                    amount = 100.0,
                    unit = "mg",
                    route = route,
                ),
            )
        }
        val entryId = runBlocking {
            database.doseEntryDao().insert(
                DoseEntryEntity(
                    timestamp = later,
                    substance = substance,
                    amount = 50.0,
                    unit = "mg",
                    route = route,
                ),
            )
        }

        // The entry's own page, opened by its timestamp and id exactly as the route does.
        compose.setContent {
            PiruTheme {
                EntryDetailScreen(
                    timestampEpochMillis = later.time,
                    idOrNull = entryId.toString(),
                    navigator = AppNavigator(),
                )
            }
        }

        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 120_000) {
            compose.onAllNodesWithText("Around this dose", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // The card is there.
        compose.onNodeWithText("Around this dose", substring = true).assertIsDisplayed()

        // The row's metadata line, built from the same helper the screen uses — so a change to either the offset
        // or the wording fails here rather than passing on a stale string.
        //
        // **This is the assertion that matters.** It names the whole line, including the overlap label, which is
        // the only outcome that distinguishes a resolved window from a null one: with no window the row still
        // draws, with the same offset and the same substance name, and only this label differs. Asserting the
        // offset alone passed while the card was saying the window was unknown, which is exactly the confusion
        // this test exists to prevent.
        val offsetLabel = EntryContext.describeOffset(-(offsetSeconds / 60L))
        compose.onNodeWithText("$offsetLabel · overlapping").assertExists()

        // Clean up, so the rows do not outlive the run.
        runBlocking {
            database.doseEntryDao().deleteByRowId(neighbourId)
            database.doseEntryDao().deleteByRowId(entryId)
        }
    }

    /**
     * A substance the catalogue has no duration for draws the honest wording rather than claiming nothing
     * overlapped.
     *
     * The distinction `EntryContext.Result.judged` exists for. A fallback window would make this card claim the
     * neighbour did not overlap, which is a statement about the user's own data that the app cannot support.
     */
    @Test
    fun aSubstanceWithoutADurationSaysSoRatherThanClaimingNoOverlap() {
        val app = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as PiruApplication
        val database = app.database

        val now = Instant.now()
        val mine = "Not A Real Substance 9000"
        val earlier = Date(now.minusSeconds(3600).toEpochMilli())
        val later = Date(now.toEpochMilli())

        // Same deterministic clear as the first case, and for the same reason.
        runBlocking { database.doseEntryDao().deleteAll() }

        val neighbourId = runBlocking {
            database.doseEntryDao().insert(
                DoseEntryEntity(timestamp = earlier, substance = "Caffeine", amount = 80.0, unit = "mg", route = route),
            )
        }
        val entryId = runBlocking {
            database.doseEntryDao().insert(
                DoseEntryEntity(timestamp = later, substance = mine, amount = 1.0, unit = "mg", route = route),
            )
        }

        compose.setContent {
            PiruTheme {
                EntryDetailScreen(
                    timestampEpochMillis = later.time,
                    idOrNull = entryId.toString(),
                    navigator = AppNavigator(),
                )
            }
        }

        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 120_000) {
            compose.onAllNodesWithText("Around this dose", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // The neighbour is listed — it happened, whatever its profile.
        compose.onNodeWithText("Caffeine", substring = true).assertIsDisplayed()
        // And the card says it could not judge, rather than claiming the Caffeine did not overlap.
        compose.onNodeWithText("nothing can be said", substring = true).assertIsDisplayed()

        runBlocking {
            database.doseEntryDao().deleteByRowId(neighbourId)
            database.doseEntryDao().deleteByRowId(entryId)
        }
    }
}
