package glass.kagerou.piru.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.data.entity.DailyDoseItemEntity
import glass.kagerou.piru.model.DoseFrequency
import glass.kagerou.piru.model.RouteOfAdministration
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.Date
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * The scheduled meds, on the engine the app ships with.
 *
 * The JSON columns are the point. `reminder_times_json` decides how many slots a
 * med materializes, and `ask_again_override_json` has a **null-versus-empty**
 * distinction that is the whole meaning of the field — null follows the global
 * default, empty is a deliberate opt-out — so both are asserted through a real
 * insert and a real read rather than through the entity's getters alone.
 *
 * JUnit 4 through AndroidJUnitRunner, so the expression-bodied methods say
 * `: Unit` explicitly: kotest's `shouldBe` returns its receiver, and a method
 * whose inferred return type is not `void` is rejected by the runner.
 */
@RunWith(AndroidJUnit4::class)
class DailyDoseItemDaoTest {

    private fun openInMemory(): PiruDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        PiruDatabase::class.java,
    ).build()

    private fun item(
        substance: String,
        sortOrder: Int = 0,
        reminderTimesJson: String = "",
        askAgainOverrideJson: String? = null,
        frequency: DoseFrequency = DoseFrequency.DAILY,
    ): DailyDoseItemEntity = DailyDoseItemEntity(
        substance = substance,
        amount = 10.0,
        sortOrder = sortOrder,
        reminderTimesJson = reminderTimesJson,
        askAgainOverrideJson = askAgainOverrideJson,
        frequencyRaw = frequency.wireValue,
        startDate = Date(0),
    )

    @Test
    fun aMedRoundTripsWithItsReminderLadder(): Unit = runBlocking {
        val db = openInMemory()
        try {
            val dao = db.dailyDoseItemDao()
            val id = dao.insert(item("Methylphenidate", reminderTimesJson = "[480,780]"))

            val read = dao.byRowId(id).shouldNotBeNull()
            read.substance shouldBe "Methylphenidate"
            read.reminderTimesMinutes shouldBe listOf(480, 780)
            read.frequency shouldBe DoseFrequency.DAILY
            read.route shouldBe RouteOfAdministration.ORAL
            // Never written is null, which the scheduler reads as the global
            // default rather than as "no re-asks".
            read.askAgainOverrideJson.shouldBeNull()
            read.askAgainOverrideMinutes.shouldBeNull()
        } finally {
            db.close()
        }
    }

    @Test
    fun anExplicitEmptyOverrideStaysEmptyAndIsNotNull(): Unit = runBlocking {
        val db = openInMemory()
        try {
            val dao = db.dailyDoseItemDao()
            val optedOut = dao.insert(item("Zinc", askAgainOverrideJson = "[]"))
            val following = dao.insert(item("B12", askAgainOverrideJson = null))

            dao.byRowId(optedOut).shouldNotBeNull().askAgainOverrideMinutes shouldBe emptyList()
            dao.byRowId(following).shouldNotBeNull().askAgainOverrideMinutes.shouldBeNull()
        } finally {
            db.close()
        }
    }

    @Test
    fun theListIsOrderedBySortOrderAndThenByInsertion(): Unit = runBlocking {
        val db = openInMemory()
        try {
            val dao = db.dailyDoseItemDao()
            dao.insert(item("Third", sortOrder = 2))
            dao.insert(item("First", sortOrder = 0))
            dao.insert(item("Second", sortOrder = 1))
            // Two items sharing a sort order: the row_id tiebreak is what keeps the
            // scheduler's notification identifiers stable between passes.
            val a = dao.insert(item("Tie A", sortOrder = 3))
            val b = dao.insert(item("Tie B", sortOrder = 3))

            dao.all().map { it.substance } shouldBe listOf("First", "Second", "Third", "Tie A", "Tie B")
            (a < b) shouldBe true
        } finally {
            db.close()
        }
    }

    @Test
    fun aWeeklyCadenceAndItsWeekdaysSurviveTheColumns(): Unit = runBlocking {
        val db = openInMemory()
        try {
            val dao = db.dailyDoseItemDao()
            val id = dao.insert(
                item("Methotrexate", frequency = DoseFrequency.SPECIFIC_DAYS)
                    .copy(frequencyDaysJson = "[2,4]", startDate = Date(TimeUnit.DAYS.toMillis(20_000))),
            )

            val read = dao.byRowId(id).shouldNotBeNull()
            read.frequency shouldBe DoseFrequency.SPECIFIC_DAYS
            read.frequencyDays shouldBe listOf(2, 4)
            read.startDate shouldBe Date(TimeUnit.DAYS.toMillis(20_000))
        } finally {
            db.close()
        }
    }
}
