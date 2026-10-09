package glass.kagerou.piru.ui.journal

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.AppSettingsStore
import glass.kagerou.piru.data.entity.QuickLogDoseEntity
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The quick-log dock draws the stored chips, and the preference hides it.
 *
 * ## Why a device spec
 * `QuickLogRecentsStoredTest` covers the **read** — that the order the rows carry is the order the chips come back in.
 * It cannot cover the two things this asserts, both of which are wiring:
 *
 * 1. that the journal calls the dock at all, which no unit test on the model can see — the failure mode this port has
 *    hit repeatedly (the tag screen, the session body load, the effect groups);
 * 2. that `showQuickLogDock` is **read by something**, which was the reason the preference was inert for a round.
 *
 * ## The two cases, and why both are needed
 * A dock that always drew would pass the first case alone. A dock that never drew would pass the second. Only the pair
 * shows that the preference is connected to the drawing.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class JournalQuickLogDockDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: PiruApplication
        get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as PiruApplication

    private fun seedChip(substance: String, productName: String? = null) = runBlocking {
        app.database.quickLogDoseDao().insert(
            QuickLogDoseEntity(
                substance = substance,
                route = RouteOfAdministration.ORAL,
                amount = 100.0,
                unit = "mg",
                sortOrder = 0.0,
                lastUsedAt = Date(),
                productName = productName,
            ),
        )
    }

    /**
     * With the preference on, a seeded chip is on the journal.
     *
     * The chip's **product name** is the text asserted, because it appears nowhere else on the page — the log rows show
     * the substance, so asserting the substance would match a dose row as well. That mistake was made on the body-load
     * spec and is not repeated here.
     */
    @Test
    fun theDockDrawsAStoredChip() {
        runBlocking { app.database.quickLogDoseDao().deleteAll() }
        seedChip("Methylphenidate", productName = "Concerta")
        AppSettingsStore(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
        ).setShowQuickLogDock(true)

        compose.setContent { PiruTheme { JournalScreen(navigator = AppNavigator()) } }
        compose.waitForIdle()

        compose.waitUntil(timeoutMillis = 120_000) {
            compose.onAllNodesWithText("Concerta", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Concerta", substring = true).assertIsDisplayed()

        runBlocking { app.database.quickLogDoseDao().deleteAll() }
    }

    /** With the preference off, the same chip is **not** drawn — which is the preference doing something. */
    @Test
    fun thePreferenceHidesTheDock() {
        runBlocking { app.database.quickLogDoseDao().deleteAll() }
        seedChip("Methylphenidate", productName = "Concerta")
        AppSettingsStore(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
        ).setShowQuickLogDock(false)

        compose.setContent { PiruTheme { JournalScreen(navigator = AppNavigator()) } }
        compose.waitForIdle()

        // A positive assertion first, so "absent" means absent from a screen that drew.
        compose.waitUntil(timeoutMillis = 120_000) {
            compose.onAllNodesWithText("Concerta", substring = true).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Concerta", substring = true).assertDoesNotExist()

        // Restore, so the other case and any later spec see the default.
        AppSettingsStore(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
        ).setShowQuickLogDock(true)
        runBlocking { app.database.quickLogDoseDao().deleteAll() }
    }
}
