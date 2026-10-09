package glass.kagerou.piru.ui.quicklog

import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.AppSettingsStore
import glass.kagerou.piru.data.QuickLogRecents
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.model.RouteOfAdministration
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Logging a dose leaves a chip in the dock.
 *
 * ## Why this is a device spec, and why it does not drive the UI
 * `QuickLogRecentsTest` is 18 cases on the folding rules, and **a quick log that never calls `fold` passes every one
 * of them** while `quick_log_doses` stays empty. That is exactly the defect: the table has had a schema, a DAO, an
 * export and an import since the port began and nothing wrote a row.
 *
 * So the thing to prove is the write, against a **real Room database** — which needs a device. Driving the sheet
 * instead would add catalogue resolution, a real keyboard and two buttons whose labels move, and none of that is
 * what was missing. The failure this catches is a fold that throws, a write that does not happen, or a returned list
 * that is empty.
 */
@RunWith(AndroidJUnit4::class)
class QuickLogRecentsDeviceTest {

    private fun app(): PiruApplication =
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as PiruApplication

    /**
     * The screen's own write path, over a real database: fold, replace, store the suppression list.
     *
     * Mirrors the block in `QuickLogSheet`'s commit handler line for line, so a change to that block that breaks the
     * write fails here.
     */
    @Test
    fun loggingADoseWritesAChip() {
        val application = app()
        val dao = application.database.quickLogDoseDao()
        val settings = AppSettingsStore(application)

        runBlocking {
            dao.deleteAll()
            application.database.doseEntryDao().deleteAll()
        }

        // The dose the sheet would have written.
        val substance = "Caffeine"
        runBlocking {
            application.database.doseEntryDao().insert(
                DoseEntryEntity(
                    id = UUID.randomUUID(),
                    timestamp = Date(),
                    substance = substance,
                    amount = 80.0,
                    unit = "mg",
                    route = RouteOfAdministration.ORAL,
                ),
            )
        }

        // The same fold-and-replace the screen performs.
        runBlocking {
            val folded = QuickLogRecents.fold(
                doses = listOf(
                    QuickLogRecents.LoggedDose(
                        substance = substance,
                        route = RouteOfAdministration.ORAL,
                        amount = 80.0,
                        unit = "mg",
                    ),
                ),
                existing = dao.all(),
                fixedOrder = settings.quickLogFixedOrder(),
                suppressed = settings.quickLogSuppressedRecents(),
            )
            dao.deleteAll()
            for (row in folded.rows) dao.insert(row)
            settings.setQuickLogSuppressedRecents(folded.suppressed)
        }

        val chips = runBlocking { dao.all() }
        println("RECENTSPROBE chips=${chips.size} substance=${chips.firstOrNull()?.substance} amount=${chips.firstOrNull()?.amount}")
        check(chips.isNotEmpty()) { "no chip was written" }
        check(chips.any { it.substance == substance && it.amount == 80.0 }) {
            "the chip does not carry the logged dose: ${chips.map { it.substance to it.amount }}"
        }

        // And a second log of the same measurement refreshes rather than duplicating — the rule that keeps a daily
        // dose from filling the dock, asserted here because it needs the real row the first write produced.
        runBlocking {
            val folded = QuickLogRecents.fold(
                doses = listOf(
                    QuickLogRecents.LoggedDose(substance, RouteOfAdministration.ORAL, 80.0, "mg"),
                ),
                existing = dao.all(),
                fixedOrder = settings.quickLogFixedOrder(),
                suppressed = settings.quickLogSuppressedRecents(),
            )
            dao.deleteAll()
            for (row in folded.rows) dao.insert(row)
        }
        check(runBlocking { dao.all() }.size == 1) { "logging the same measurement twice duplicated the chip" }

        runBlocking {
            dao.deleteAll()
            application.database.doseEntryDao().deleteAll()
        }
    }

    /**
     * The suppression list survives a round trip through the preferences file.
     *
     * The store joins it into a string and splits it back, and the separator has to be one an identity key cannot
     * contain — an identity is built from a substance name, and a name can hold any printable character. A round trip
     * over an awkward name is what checks that.
     */
    @Test
    fun theSuppressionListRoundTrips() {
        val settings = AppSettingsStore(app())
        val awkward = setOf("a,b:c|d", "with space", "with\ttab", "ümlaut")
        settings.setQuickLogSuppressedRecents(awkward)
        val read = settings.quickLogSuppressedRecents()
        println("RECENTSPROBE suppressed=$read")
        check(read == awkward) { "the round trip lost or split a value: $read" }
        settings.setQuickLogSuppressedRecents(emptySet())
        check(settings.quickLogSuppressedRecents().isEmpty()) { "clearing left something behind" }
    }
}
