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
import glass.kagerou.piru.data.entity.SessionEntity
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The session page draws what is still on board.
 *
 * ## Why this is a device spec
 * `SessionBodyLoadModelTest` is 23 cases on the model's rules, and **a session page that never calls the model passes
 * every one of them** while the block is absent — the wiring gap this port has hit repeatedly. And the model needs the
 * **real catalogue** to resolve a half-life, which the JVM harness does not have.
 *
 * ## What is asserted
 * That the card renders at all for a session with a modelable substance, and that its heading and a remaining-amount
 * line are on screen. The exact number is not asserted: it depends on how long the test itself takes, and pinning it
 * would make this a test of the clock.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SessionBodyLoadDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Caffeine has a half-life in the catalogue, so it is modelable and belongs in the active list. */
    @Test
    fun theSessionPageDrawsTheBodyLoad() {
        val app = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as PiruApplication
        val database = app.database

        runBlocking { database.doseEntryDao().deleteAll() }

        val session = runBlocking {
            val id = UUID.randomUUID()
            database.sessionDao().insert(
                SessionEntity(id = id, startDate = Date(), title = "Body load spec"),
            )
            id
        }
        runBlocking {
            database.doseEntryDao().insert(
                DoseEntryEntity(
                    timestamp = Date(Instant.now().toEpochMilli()),
                    substance = "Caffeine",
                    amount = 200.0,
                    unit = "mg",
                    route = RouteOfAdministration.ORAL,
                    sessionId = session,
                ),
            )
        }

        compose.setContent {
            PiruTheme { SessionDetailScreen(sessionId = session.toString(), navigator = AppNavigator()) }
        }
        compose.waitForIdle()

        compose.waitUntil(timeoutMillis = 120_000) {
            compose.onAllNodesWithText("Still on board", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Still on board", substring = true).assertIsDisplayed()
        // The card's own second line, which is the one thing on this page that only the body-load model can produce:
        // `still in the body` appears nowhere else. Asserting the substance name instead found **two** nodes — the
        // body-load row and the entry list — which is what the first version of this spec failed on.
        compose.onNodeWithText("still in the body", substring = true).assertIsDisplayed()

        runBlocking {
            database.doseEntryDao().deleteAll()
            database.sessionDao().deleteById(session)
        }
    }
}
