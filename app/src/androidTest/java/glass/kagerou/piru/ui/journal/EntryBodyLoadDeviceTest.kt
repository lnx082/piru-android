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
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The entry page draws what is still in the body from **this dose**.
 *
 * ## Why a device spec and not a JVM one
 * `SessionBodyLoadModelTest` is 23 cases on the model's rules, and a page that never calls the model passes every one
 * of them while the block is absent — the wiring gap this port has hit repeatedly (the tag screen, the session's own
 * body load, the effect groups). The model also needs the **real catalogue** to resolve a half-life, which the JVM
 * harness does not have.
 *
 * ## What is asserted
 * The heading, and the row's own line — `still in the body`, which appears nowhere else on the page. Asserting the
 * substance name would match the dose card as well; that mistake was made on the session's version of this spec.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class EntryBodyLoadDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Caffeine has a half-life in the catalogue, so it is modelable and belongs in the block. */
    @Test
    fun theEntryPageDrawsWhatIsInTheBody() {
        val app = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as PiruApplication

        val entry = DoseEntryEntity(
            id = UUID.randomUUID(),
            timestamp = Date(Instant.now().toEpochMilli()),
            substance = "Caffeine",
            amount = 200.0,
            unit = "mg",
            route = RouteOfAdministration.ORAL,
        )
        runBlocking { app.database.doseEntryDao().insert(entry) }

        compose.setContent {
            PiruTheme {
                EntryDetailScreen(
                    timestampEpochMillis = entry.timestamp.time,
                    idOrNull = entry.id.toString(),
                    navigator = AppNavigator(),
                )
            }
        }
        compose.waitForIdle()

        compose.waitUntil(timeoutMillis = 120_000) {
            compose.onAllNodesWithText("In your body", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("In your body", substring = true).assertIsDisplayed()
        // The block's own line, which only the body-load card can produce.
        compose.onNodeWithText("still in the body", substring = true).assertIsDisplayed()

        runBlocking { app.database.doseEntryDao().deleteAll() }
    }
}
