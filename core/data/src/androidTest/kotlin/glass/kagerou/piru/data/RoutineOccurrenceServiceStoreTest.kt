package glass.kagerou.piru.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.data.entity.DailyDoseItemEntity
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.data.entity.RoutineOccurrenceEntity
import glass.kagerou.piru.data.entity.RoutineOccurrenceEntity.State
import glass.kagerou.piru.model.RouteOfAdministration
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.UUID
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * The reconciler against a real store — the half a JVM spec cannot see.
 *
 * `RoutineOccurrenceServiceTest` proves the matching arithmetic; this proves the
 * wiring: that a dose written to `dose_entries` ends up as a `logged` occurrence
 * row, that the re-ask gate derived from those rows comes back non-empty, and
 * that deleting the dose takes the slot back to `pending`. The first of those is
 * the bug this service was ported to fix — the notification layer read the
 * occurrence table faithfully, and nothing wrote it, so a dose logged at 08:00
 * still got "still need to log X?" at 10:00.
 *
 * Today is the real today, in UTC, because `reconcile` is what decides the day
 * window and faking that would fake the thing under test. The clock is pinned to
 * a zone rather than to the host default so the arithmetic is the same wherever
 * the emulator thinks it is.
 *
 * JUnit 4 through AndroidJUnitRunner, so the expression-bodied methods say
 * `: Unit` explicitly — kotest's `shouldBe` returns its receiver, and a method
 * that infers a return type other than `void` is rejected by the runner.
 */
@RunWith(AndroidJUnit4::class)
class RoutineOccurrenceServiceStoreTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun now(): Instant = Instant.now()

    private fun todayStart(): Instant = now().atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

    /** A time on the pinned day, as an instant. */
    private fun at(hour: Int, minute: Int = 0): Instant = todayStart().plusSeconds((hour * 60L + minute) * 60L)

    private fun openInMemory(): PiruDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        PiruDatabase::class.java,
    ).build()

    private suspend fun PiruDatabase.addMed(
        substance: String,
        times: List<Int>,
        route: RouteOfAdministration = RouteOfAdministration.ORAL,
        isAsNeeded: Boolean = false,
    ): Long = dailyDoseItemDao().insert(
        DailyDoseItemEntity(
            substance = substance,
            amount = 10.0,
            route = route,
            reminderTimesJson = times.joinToString(",", "[", "]"),
            isAsNeeded = isAsNeeded,
            // Long before the test day, so every cadence is due.
            startDate = Date(0),
        ),
    )

    private suspend fun PiruDatabase.addDose(
        substance: String,
        timestamp: Instant,
        route: RouteOfAdministration = RouteOfAdministration.ORAL,
    ): Long = doseEntryDao().insert(
        DoseEntryEntity(
            id = UUID.randomUUID(),
            substance = substance,
            amount = 10.0,
            route = route,
            timestamp = Date(timestamp.toEpochMilli()),
        ),
    )

    private fun key(substance: String, slotMinutes: Int?, route: RouteOfAdministration = RouteOfAdministration.ORAL) =
        RoutineOccurrenceService.slotKey(substance, null, route, slotMinutes)

    private suspend fun PiruDatabase.occurrences(): List<RoutineOccurrenceEntity> {
        val start = todayStart()
        return routineOccurrenceDao().forDay(
            Date(start.toEpochMilli()),
            Date(start.plus(1, ChronoUnit.DAYS).toEpochMilli()),
        )
    }

    // MARK: - The bug

    @Test
    fun aLoggedDoseMakesItsSlotSatisfiedSoTheReAskStops(): Unit = runBlocking {
        val db = openInMemory()
        try {
            db.addMed("Vitamin D3", listOf(9 * 60))
            db.addDose("Vitamin D3", at(9, 5))
            val service = RoutineOccurrenceService(db)

            service.reconcile(now(), zone)

            service.satisfiedSlotKeys(now(), zone) shouldBe setOf(key("Vitamin D3", 9 * 60))
            db.occurrences().single().state shouldBe State.LOGGED
        } finally {
            db.close()
        }
    }

    @Test
    fun anUnloggedSlotIsNotSatisfiedButIsStillRecorded(): Unit = runBlocking {
        val db = openInMemory()
        try {
            db.addMed("Vitamin D3", listOf(9 * 60))
            val service = RoutineOccurrenceService(db)

            service.reconcile(now(), zone)

            // The row exists so the slot can be answered at all; it just has no
            // dose behind it, so the re-ask still has a reason to fire.
            service.satisfiedSlotKeys(now(), zone).shouldBeEmpty()
            db.occurrences().single().state shouldBe State.PENDING
        } finally {
            db.close()
        }
    }

    @Test
    fun aDoseOnTheWrongRouteDoesNotSatisfyTheSlot(): Unit = runBlocking {
        val db = openInMemory()
        try {
            db.addMed("Melatonin", listOf(9 * 60))
            db.addDose("Melatonin", at(9, 5), route = RouteOfAdministration.SUBLINGUAL)
            val service = RoutineOccurrenceService(db)

            service.reconcile(now(), zone)

            service.satisfiedSlotKeys(now(), zone).shouldBeEmpty()
        } finally {
            db.close()
        }
    }

    @Test
    fun aMultiTimeMedIsSatisfiedPerSlotNotPerMed(): Unit = runBlocking {
        val db = openInMemory()
        try {
            db.addMed("Methylphenidate", listOf(8 * 60, 13 * 60))
            db.addDose("Methylphenidate", at(8, 5))
            val service = RoutineOccurrenceService(db)

            service.reconcile(now(), zone)

            val satisfied = service.satisfiedSlotKeys(now(), zone)
            satisfied.contains(key("Methylphenidate", 8 * 60)) shouldBe true
            // 09:40 dose does not answer for the 13:00 slot, and neither does the
            // 08:05 one: the slots are separate questions.
            satisfied.contains(key("Methylphenidate", 13 * 60)) shouldBe false
        } finally {
            db.close()
        }
    }

    // MARK: - Re-derivation

    @Test
    fun reconcileIsIdempotent(): Unit = runBlocking {
        val db = openInMemory()
        try {
            db.addMed("Vitamin D3", listOf(9 * 60))
            db.addDose("Vitamin D3", at(9, 5))
            val service = RoutineOccurrenceService(db)

            service.reconcile(now(), zone)
            service.reconcile(now(), zone)

            db.occurrences().size shouldBe 1
        } finally {
            db.close()
        }
    }

    @Test
    fun deletingTheDoseReturnsTheSlotToPending(): Unit = runBlocking {
        val db = openInMemory()
        try {
            db.addMed("Vitamin D3", listOf(9 * 60))
            val doseRow = db.addDose("Vitamin D3", at(9, 5))
            val service = RoutineOccurrenceService(db)
            service.reconcile(now(), zone)
            db.occurrences().single().state shouldBe State.LOGGED

            db.doseEntryDao().deleteByRowId(doseRow)
            service.reconcile(now(), zone)

            db.occurrences().single().state shouldBe State.PENDING
            service.satisfiedSlotKeys(now(), zone).shouldBeEmpty()
        } finally {
            db.close()
        }
    }

    @Test
    fun aMedWithNoReminderTimesGetsOneAnytimeSlot(): Unit = runBlocking {
        val db = openInMemory()
        try {
            db.addMed("Vitamin D3", emptyList())
            db.addDose("Vitamin D3", at(9, 5))
            val service = RoutineOccurrenceService(db)

            service.reconcile(now(), zone)

            // One row, with no time on it: the med expects a dose a day but has no
            // hour to expect it at, and any dose that day answers for it.
            val row = db.occurrences().single()
            row.slotMinutes shouldBe null
            row.state shouldBe State.LOGGED
            service.satisfiedSlotKeys(now(), zone) shouldBe setOf(key("Vitamin D3", null))
        } finally {
            db.close()
        }
    }

    @Test
    fun anAsNeededMedGetsNoOccurrenceAndNoSatisfiedSlot(): Unit = runBlocking {
        val db = openInMemory()
        try {
            db.addMed("Ibuprofen", emptyList(), isAsNeeded = true)
            db.addDose("Ibuprofen", at(9, 5))
            val service = RoutineOccurrenceService(db)

            service.reconcile(now(), zone)

            db.occurrences().shouldBeEmpty()
            service.satisfiedSlotKeys(now(), zone).shouldBeEmpty()
        } finally {
            db.close()
        }
    }

    @Test
    fun yesterdaysPendingExpiresToMissedAndHistorySurvives(): Unit = runBlocking {
        val db = openInMemory()
        try {
            val yesterday = Date(todayStart().minus(1, ChronoUnit.DAYS).toEpochMilli())
            val dao = db.routineOccurrenceDao()
            val stale = dao.insert(
                RoutineOccurrenceEntity(
                    substance = "Vitamin D3",
                    routeRaw = RouteOfAdministration.ORAL.wireValue,
                    dueDay = yesterday,
                    slotMinutes = 9 * 60,
                ),
            )
            val done = dao.insert(
                RoutineOccurrenceEntity(
                    substance = "Magnesium",
                    routeRaw = RouteOfAdministration.ORAL.wireValue,
                    dueDay = yesterday,
                    stateRaw = State.LOGGED.wireValue,
                    slotMinutes = 21 * 60,
                ),
            )
            val service = RoutineOccurrenceService(db)

            service.reconcile(now(), zone)

            // A pending row from a day that is over is `missed` — neutral history,
            // not a reprimand — and a row that already settled is left alone.
            dao.byRowId(stale)!!.state shouldBe State.MISSED
            dao.byRowId(done)!!.state shouldBe State.LOGGED
        } finally {
            db.close()
        }
    }

    // MARK: - Skip Today

    @Test
    fun skipTodaySatisfiesTheSlotAndSurvivesALaterDose(): Unit = runBlocking {
        val db = openInMemory()
        try {
            db.addMed("Vitamin D3", listOf(9 * 60))
            val service = RoutineOccurrenceService(db)
            service.reconcile(now(), zone)

            service.skipToday(setOf(key("Vitamin D3", 9 * 60)), now(), zone)
            service.satisfiedSlotKeys(now(), zone) shouldBe setOf(key("Vitamin D3", 9 * 60))

            // Logging the dose afterwards does not un-dismiss the slot the user
            // said to stop asking about.
            db.addDose("Vitamin D3", at(9, 5))
            service.reconcile(now(), zone)
            db.occurrences().single().state shouldBe State.SKIPPED
        } finally {
            db.close()
        }
    }

    @Test
    fun skipTodayLeavesTheOtherSlotsPending(): Unit = runBlocking {
        val db = openInMemory()
        try {
            db.addMed("Methylphenidate", listOf(8 * 60, 13 * 60))
            val service = RoutineOccurrenceService(db)
            service.reconcile(now(), zone)

            service.skipToday(setOf(key("Methylphenidate", 8 * 60)), now(), zone)

            service.satisfiedSlotKeys(now(), zone) shouldBe setOf(key("Methylphenidate", 8 * 60))
            db.occurrences().first { it.slotMinutes == 13 * 60 }.state shouldBe State.PENDING
        } finally {
            db.close()
        }
    }
}
