package glass.kagerou.piru.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.data.entity.RoutineOccurrenceEntity
import glass.kagerou.piru.data.entity.RoutineOccurrenceEntity.State
import glass.kagerou.piru.model.RouteOfAdministration
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.Date
import java.util.UUID
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * The occurrence table's SQL, on the engine the app ships with.
 *
 * The rules checked here are the ones a JVM spec cannot see: which rows a
 * half-open day window selects, what the two targeted setters actually touch, and
 * that the wire values survive the round trip through `state_raw` and
 * `route_raw`.
 *
 * JUnit 4 through AndroidJUnitRunner, like the other DAO specs in this module —
 * and the expression-bodied methods below therefore say `: Unit` explicitly,
 * because kotest's `shouldBe` returns its receiver and an inferred return type
 * other than `void` makes the runner reject the whole class.
 */
@RunWith(AndroidJUnit4::class)
class RoutineOccurrenceDaoTest {

    private fun openInMemory(): PiruDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        PiruDatabase::class.java,
    ).build()

    /** The day the fixture rows are keyed by — an arbitrary fixed day, not today. */
    private val day = Date(1_772_000_000_000L)

    private fun nextDay(): Date = Date(day.time + 86_400_000L)

    private fun row(
        substance: String = "Vitamin D3",
        slotMinutes: Int? = 540,
        state: State = State.PENDING,
        dueDay: Date = day,
        satisfyingEntryID: UUID? = null,
        substanceUID: String? = null,
    ): RoutineOccurrenceEntity = RoutineOccurrenceEntity(
        substance = substance,
        substanceUID = substanceUID,
        routeRaw = RouteOfAdministration.ORAL.wireValue,
        dueDay = dueDay,
        stateRaw = state.wireValue,
        satisfyingEntryID = satisfyingEntryID,
        slotMinutes = slotMinutes,
    )

    @Test
    fun theDayWindowIsHalfOpenAndTheStateSurvivesTheColumn(): Unit = runBlocking {
        val db = openInMemory()
        try {
            val dao = db.routineOccurrenceDao()
            dao.insert(row(slotMinutes = 540, state = State.LOGGED))
            dao.insert(row(slotMinutes = 1_320, state = State.SKIPPED))
            // The next day's first row: inside the store, outside the window.
            dao.insert(row(slotMinutes = 540, dueDay = nextDay()))

            val today = dao.forDay(day, nextDay())
            today.size shouldBe 2
            today.map { it.slotMinutes } shouldContainExactlyInAnyOrder listOf(540, 1_320)
            today.first { it.slotMinutes == 1_320 }.state shouldBe State.SKIPPED
            dao.forDay(nextDay(), Date(nextDay().time + 86_400_000L)).size shouldBe 1
        } finally {
            db.close()
        }
    }

    @Test
    fun anAnytimeSlotRoundTripsAsNull(): Unit = runBlocking {
        val db = openInMemory()
        try {
            val dao = db.routineOccurrenceDao()
            val id = dao.insert(row(slotMinutes = null))
            dao.byRowId(id).shouldNotBeNull().slotMinutes shouldBe null
        } finally {
            db.close()
        }
    }

    @Test
    fun idsBeforeSelectsOnlyTheNamedStateAndOnlyEarlierDays(): Unit = runBlocking {
        val db = openInMemory()
        try {
            val dao = db.routineOccurrenceDao()
            val yesterday = Date(day.time - 86_400_000L)
            val stale = dao.insert(row(slotMinutes = 540, dueDay = yesterday, state = State.PENDING))
            // Settled history from the same past day. It is before `day` like the
            // row above, and the state filter is what keeps it out of the expiry —
            // a day that ended leaves its own record of what happened.
            val done = dao.insert(row(slotMinutes = 600, dueDay = yesterday, state = State.LOGGED))
            dao.insert(row(slotMinutes = 660, dueDay = yesterday, state = State.SKIPPED))
            // Today is not "before" today.
            dao.insert(row(slotMinutes = 720, dueDay = day, state = State.PENDING))

            dao.idsBefore(day, State.PENDING.wireValue) shouldContainExactlyInAnyOrder listOf(stale)
            dao.idsBefore(day, State.LOGGED.wireValue) shouldContainExactlyInAnyOrder listOf(done)
            // Nothing precedes the oldest day in the store.
            dao.idsBefore(yesterday, State.PENDING.wireValue).shouldBeEmpty()
        } finally {
            db.close()
        }
    }

    @Test
    fun setStateLeavesTheSatisfyingDoseAloneAndSetOutcomeWritesBoth(): Unit = runBlocking {
        val db = openInMemory()
        try {
            val dao = db.routineOccurrenceDao()
            val entryID = UUID.randomUUID()
            val id = dao.insert(row(state = State.LOGGED, satisfyingEntryID = entryID))

            dao.setState(id, State.MISSED.wireValue)
            val missed = dao.byRowId(id).shouldNotBeNull()
            missed.state shouldBe State.MISSED
            // Expiry is history, not erasure: the dose that satisfied the slot is
            // still the reason it was ever logged.
            missed.satisfyingEntryID shouldBe entryID

            dao.setOutcome(id, State.PENDING.wireValue, null)
            val reverted = dao.byRowId(id).shouldNotBeNull()
            reverted.state shouldBe State.PENDING
            reverted.satisfyingEntryID.shouldBeNull()
        } finally {
            db.close()
        }
    }

    @Test
    fun deleteByRowIdsRemovesExactlyThoseRows(): Unit = runBlocking {
        val db = openInMemory()
        try {
            val dao = db.routineOccurrenceDao()
            val keep = dao.insert(row(slotMinutes = 540))
            val drop = dao.insert(row(slotMinutes = 1_320))
            dao.deleteByRowIds(listOf(drop))

            dao.byRowId(drop).shouldBeNull()
            dao.forDay(day, nextDay()).map { it.rowId } shouldContainExactlyInAnyOrder listOf(keep)
        } finally {
            db.close()
        }
    }
}
