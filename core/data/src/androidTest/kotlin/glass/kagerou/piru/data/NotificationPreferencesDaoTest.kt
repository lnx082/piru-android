package glass.kagerou.piru.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * The preferences singleton, on the engine the app ships with.
 *
 * The one rule this file exists for is the convention the schema cannot express:
 * **one row, read by the lowest `row_id`, updated rather than appended to.** A
 * second row would not look like a duplicate — it would look like a user whose
 * notification switches had reverted, because whichever row a query returned
 * first would become the answer.
 *
 * JUnit 4 through AndroidJUnitRunner, so the expression-bodied methods say
 * `: Unit` explicitly; kotest's `shouldBe` returns its receiver, and a method
 * that infers a return type other than `void` is rejected by the runner.
 */
@RunWith(AndroidJUnit4::class)
class NotificationPreferencesDaoTest {

    private fun openInMemory(): PiruDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        PiruDatabase::class.java,
    ).build()

    @Test
    fun theTableStartsEmptyAndAnIdentityEditSeedsTheDefaults(): Unit = runBlocking {
        val db = openInMemory()
        try {
            val dao = db.notificationPreferencesDao()
            dao.current().shouldBeNull()

            val seeded = dao.edit { it }

            // The row the model's own defaults describe, and the row_id the store
            // an insert actually got — not the 0 the caller held.
            seeded.rowId shouldBe dao.current()!!.rowId
            seeded.masterEnabled shouldBe true
            seeded.routineEnabled shouldBe true
            seeded.phaseEnabled shouldBe false
            // Null, not an empty list: "never written" is not "opted out".
            seeded.askAgainDefaultJson.shouldBeNull()
            seeded.askAgainDefaultMinutes shouldBe listOf(10)
            dao.count() shouldBe 1
        } finally {
            db.close()
        }
    }

    @Test
    fun aSecondEditUpdatesTheOneRowRatherThanInsertingAnother(): Unit = runBlocking {
        val db = openInMemory()
        try {
            val dao = db.notificationPreferencesDao()
            val first = dao.edit { it }
            val second = dao.edit { it.copy(routineEnabled = false, quietHoursEnabled = true) }

            dao.count() shouldBe 1
            second.rowId shouldBe first.rowId
            val read = dao.current().shouldNotBeNull()
            read.routineEnabled shouldBe false
            read.quietHoursEnabled shouldBe true
            // Untouched fields survive the write.
            read.inventoryEnabled shouldBe true
        } finally {
            db.close()
        }
    }

    @Test
    fun aNullChangeIsTheReadWithoutAWriteAndAnEmptyListStaysEmpty(): Unit = runBlocking {
        val db = openInMemory()
        try {
            val dao = db.notificationPreferencesDao()
            // `null` means "leave it alone" — the read-only shape the profile store
            // uses, and it still seeds on an empty table.
            dao.edit { null }.shouldNotBeNull()
            dao.count() shouldBe 1

            // An explicit empty cadence is "no re-asks", and it must not collapse
            // into the null that means "never configured".
            val written = dao.edit { it.copy(askAgainDefaultJson = "") }
            written.askAgainDefaultMinutes shouldBe emptyList()
            written.askAgainDefaultJson shouldBe ""
            dao.count() shouldBe 1
        } finally {
            db.close()
        }
    }

    @Test
    fun deleteAllDropsTheRowSoTheNextReadSeedsAFreshOne(): Unit = runBlocking {
        val db = openInMemory()
        try {
            val dao = db.notificationPreferencesDao()
            dao.edit { it.copy(routineEnabled = false) }
            dao.deleteAll()
            dao.current().shouldBeNull()

            // Seeded again from the defaults, not from the suspended choice — this
            // is the notification half of "delete everything".
            dao.edit { it }.routineEnabled shouldBe true
            dao.count() shouldBe 1
        } finally {
            db.close()
        }
    }
}
