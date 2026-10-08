package glass.kagerou.piru.ui.meds

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import glass.kagerou.piru.PiruTestApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.entity.DailyDoseItemEntity
import glass.kagerou.piru.model.DoseFrequency
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The My Meds hub, drawn against a real Room store.
 *
 * ## Why this screen, and what it proves
 * Almost every data-driven screen in this app reads the substance catalogue as well as the
 * store, and the catalogue is an 18 MB asset rather than a table — so an in-memory database
 * does not make those screens testable on the JVM. `MyMedsHubScreen` reads only the store,
 * which makes it the screen where the in-memory harness can actually be shown to work.
 *
 * The store is a real in-memory Room database: the DAO's SQL, the generated bindings and the
 * entity are the ones under test, not a fake of them.
 *
 * ## What it asserts
 * That an empty schedule renders the hub's own empty state, and that a med in the store
 * becomes a row carrying its name. The first alone would pass on a screen that always drew
 * its empty state, which is why both are here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PiruTestApplication::class)
class MyMedsHubScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<PiruTestApplication>()
    private val resources = app.resources

    private fun string(id: Int): String = resources.getString(id)

    /** One scheduled med, timed, so the hub's own grouping has something to place. */
    private fun schedule(substance: String) = runBlocking {
        app.database.dailyDoseItemDao().insert(
            DailyDoseItemEntity(
                substance = substance,
                amount = 10.0,
                sortOrder = 1,
                reminderTimesJson = "[480]",
                frequencyRaw = DoseFrequency.DAILY.wireValue,
                startDate = Date(0),
            ),
        )
    }

    @Test
    fun anEmptyScheduleDrawsTheHubsEmptyState() {
        compose.setContent {
            PiruTheme { MyMedsHubScreen(AppNavigator(), onOpenMed = {}) }
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(string(R.string.meds_no_meds_yet)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(string(R.string.meds_no_meds_yet)).assertIsDisplayed()
    }

    @Test
    fun aScheduledMedBecomesARow() {
        schedule("MedsProbe")

        compose.setContent {
            PiruTheme { MyMedsHubScreen(AppNavigator(), onOpenMed = {}) }
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("MedsProbe"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("MedsProbe").assertIsDisplayed()
    }
}
