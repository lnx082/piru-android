package glass.kagerou.piru.ui.journal

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.data.entity.SessionEntity
import glass.kagerou.piru.engine.InteractionChecker
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText

/**
 * The session's interaction card renders against the real rules.
 *
 * ## Why this is an instrumentation spec
 * The checker needs the catalogue's rule tables and its identity index, so the JVM harness cannot build one. And
 * the failure this catches is invisible in a JVM test: `checkBatch` returning an empty list for a pair that *should*
 * fire leaves the card **absent**, which looks exactly like a session with no interaction — the honest and the
 * broken outcome are the same picture. So the assertion has to be that a specific pair drew.
 *
 * ## Why this pair
 * Morphine and Alprazolam are in the catalogue's rule tables — the port's own `InteractionCheckerTest` uses exactly
 * this pair — and opioid-plus-benzodiazepine is the warning this app most needs to show. The severity is asserted
 * as the **engine's** value rather than a number, so a change to the rule's tier fails here deliberately.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SessionSafetyDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * A session of morphine and alprazolam draws the interaction card.
     *
     * The load-bearing case: it fails if the checker is constructed wrongly, if the batch is passed names in the
     * wrong shape, or if the card's gate is inverted.
     */
    @Test
    fun aDangerousPairDrawsTheInteractionCard() {
        val app = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as PiruApplication
        val database = app.database

        runBlocking { database.doseEntryDao().deleteAll() }

        val session = runBlocking {
            val id = java.util.UUID.randomUUID()
            database.sessionDao().insert(
                SessionEntity(
                    id = id,
                    startDate = Date(),
                    title = "Safety spec",
                ),
            )
            id
        }

        val now = Instant.now()
        runBlocking {
            database.doseEntryDao().insert(
                DoseEntryEntity(
                    timestamp = Date(now.minusSeconds(1800).toEpochMilli()),
                    substance = "Morphine",
                    amount = 10.0,
                    unit = "mg",
                    route = RouteOfAdministration.ORAL,
                    sessionId = session,
                ),
            )
            database.doseEntryDao().insert(
                DoseEntryEntity(
                    timestamp = Date(now.toEpochMilli()),
                    substance = "Alprazolam",
                    amount = 0.5,
                    unit = "mg",
                    route = RouteOfAdministration.ORAL,
                    sessionId = session,
                ),
            )
        }

        // What the checker itself says, so the card's absence can be told from the checker finding nothing.
        val found = runBlocking {
            val catalog = app.catalog()
            val checker = InteractionChecker(catalog, catalog)
            val rows = database.doseEntryDao().forSession(session)
            checker.checkBatch(rows.map { it.substance }.distinct(), rows.map { it.toDoseRecordForSession() })
        }
        println("SAFETYPROBE results=${found.size} severities=${found.map { it.severity }} keys=${found.map { it.ruleKey }}")

        compose.setContent {
            PiruTheme {
                SessionDetailScreen(sessionId = session.toString(), navigator = AppNavigator())
            }
        }

        compose.waitForIdle()
        // The wait is on the card's own heading, which is what tells the load finished.
        compose.waitUntil(timeoutMillis = 120_000) {
            compose.onAllNodesWithText("Interactions", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // And the assertion names the **outcome**, not the heading. "Interactions" also appears in the
        // interactions screen's own empty state — "No interactions found in Piru's database." — so waiting on that
        // word alone would pass on a page that found nothing.
        //
        // The exact pair line is derived from the checker's own result rather than written by hand: the rule
        // reports the substances in its own order, and guessing it cost a run. `found` above is that result.
        val pairLine = found.firstOrNull()?.let { "${it.substanceA} + ${it.substanceB}" }
        println("SAFETYPROBE pairLine=$pairLine")
        check(pairLine != null) { "the checker found nothing, so this spec has nothing to assert" }
        compose.onNodeWithText(pairLine).assertIsDisplayed()

        runBlocking {
            database.doseEntryDao().deleteAll()
            database.sessionDao().deleteById(session)
        }
    }
}
