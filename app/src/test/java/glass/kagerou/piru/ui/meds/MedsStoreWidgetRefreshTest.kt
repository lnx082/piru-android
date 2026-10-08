package glass.kagerou.piru.ui.meds

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.PiruTestApplication
import glass.kagerou.piru.data.entity.DailyDoseItemEntity
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.widget.MedWidgetRefresh
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every med write arms the widget's refresh — including the one that unticks a dose.
 *
 * ## What this pins, and why it was not pinned before
 * `MedsStore.unlog` deleted the dose, refreshed the session's dose bounds and returned. It did **not** call
 * `settleSchedule`, which `save`, `delete` and `log` all end in — so unticking a dose on the journal's med card
 * left it drawn as taken on the home-screen widget until the next boundary or midnight.
 *
 * The store's own doc says its writes end in `settleSchedule` "rather than by its callers: a caller cannot see
 * whether it remembered, and the two paths that forgot are the reason this exists". That reasoning was right
 * and the mechanism was still bypassable, because nothing asserted it. This file does, by observing the one
 * effect `settleSchedule` has that a JVM harness can see.
 *
 * ## Why the enqueue is the observable
 * `settleSchedule` does two things. `refreshAll` returns immediately when no widget is placed, so it proves
 * nothing in a test; `scheduleNext` enqueues unique work under MedWidgetRefresh.WORK_NAME, which a Robolectric `WorkManager`
 * records. So the assertion is "after this write, the refresh is armed" — and the harness's own starting state
 * is asserted in each test rather than assumed, so a stale enqueue cannot make one pass.
 *
 * `log` is the control: if the log path fails this too, the observation is broken rather than the product.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PiruTestApplication::class)
class MedsStoreWidgetRefreshTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val app: PiruApplication
        get() = ApplicationProvider.getApplicationContext<Context>()
            .applicationContext as PiruApplication

    /**
     * A synchronous `WorkManager`, initialised per test.
     *
     * `SynchronousExecutor` so an enqueued request is queryable immediately: the point of the test is *that* a
     * write enqueued, not when the work ran.
     */
    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    /** Whether the widget's refresh is currently armed. */
    private fun refreshArmed(): Boolean = runBlocking {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(MedWidgetRefresh.WORK_NAME)
            .get()
            .isNotEmpty()
    }

    /**
     * A fresh harness has no armed refresh.
     *
     * The precondition the two assertions below rest on: without it, an implementation whose application
     * start-up always armed a refresh would make both pass regardless of what the write did.
     */
    @Test
    fun theHarnessStartsWithNoArmedRefresh() {
        refreshArmed() shouldBe false
    }

    /**
     * A write that commits a med arms the refresh.
     *
     * MedsStore.delete rather than log: the log path reaches pp.sessionRepository(), which needs the
     * substance catalogue and this harness deliberately has none. delete takes the same settleSchedule tail
     * through the same database, so it serves as the control equally well.
     *
     * If this fails, the observation mechanism is wrong rather than the unlog path — which is exactly the
     * distinction that makes the next test meaningful.
     */
    @Test
    fun deletingAMedArmsTheRefresh() {
        runBlocking {
            refreshArmed() shouldBe false
            val rowId = app.database.dailyDoseItemDao().insert(medItem())
            val item = app.database.dailyDoseItemDao().byRowId(rowId)!!

            MedsStore.delete(app, item)
            refreshArmed() shouldBe true
        }
    }

    /**
     * **Unticking a dose arms the refresh.** The regression.
     *
     * The row is inserted straight through the DAO rather than via `log`, so nothing has armed the refresh
     * beforehand and the only thing that can is `unlog` itself.
     */
    @Test
    fun unloggingADoseArmsTheRefresh() {
        runBlocking {
            refreshArmed() shouldBe false
            val rowId = app.database.doseEntryDao().insert(doseEntry())
            val entry = app.database.doseEntryDao().byRowId(rowId)!!

            MedsStore.unlog(app, entry)
            refreshArmed() shouldBe true
        }
    }

    /**
     * And it really deleted the row.
     *
     * Asserted with the refresh, because an `unlog` that armed the widget and wrote nothing would pass the
     * assertion above — and a widget redrawn from an unchanged store is a different bug from one never redrawn.
     */
    @Test
    fun unloggingADoseDeletesIt() {
        runBlocking {
            val rowId = app.database.doseEntryDao().insert(doseEntry())
            val entry = app.database.doseEntryDao().byRowId(rowId)!!

            MedsStore.unlog(app, entry)
            app.database.doseEntryDao().byRowId(rowId) shouldBe null
        }
    }

    /** A dose row the store will accept. */
    private fun doseEntry(): DoseEntryEntity = DoseEntryEntity.create(
        substance = "Test substance ${UUID.randomUUID()}",
        amount = 10.0,
        unit = "mg",
        route = glass.kagerou.piru.model.RouteOfAdministration.ORAL,
        timestamp = Date.from(Instant.now()),
    )

    /** A minimal scheduled med for the log control. */
    private fun medItem(): DailyDoseItemEntity = DailyDoseItemEntity(
        substance = "Test med ${UUID.randomUUID()}",
        amount = 10.0,
    )
}
