package glass.kagerou.piru.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import glass.kagerou.piru.data.entity.InventoryItemEntity
import glass.kagerou.piru.engine.EmptySubstanceCatalog
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The inventory table, on the engine the app actually ships with.
 *
 * ## Why the migration is tested separately from the DAO
 * A migration is the one piece of schema code that cannot be corrected after the
 * fact: a store that migrates wrongly has already lost what it was migrating. So
 * [migrationFromOneToTwo] opens a real v1 database, runs the migration, and lets
 * Room **validate** the result against the entity — which is the only check that
 * catches a hand-written `CREATE TABLE` that is one `DEFAULT` away from the
 * generated one. The DDL was copied from the exported schema, and this is what
 * proves the copy is still exact.
 */
@RunWith(AndroidJUnit4::class)
class InventoryDaoTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PiruDatabase::class.java,
    )

    private fun openInMemory(): PiruDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        PiruDatabase::class.java,
    ).build()

    @Test
    fun migrationFromOneToTwo() {
        helper.createDatabase("migration-test.db", 1).close()
        // Validation on: Room compares the migrated schema against the entity and
        // throws rather than handing back a store it cannot read.
        helper.runMigrationsAndValidate("migration-test.db", 2, true, PiruDatabase.MIGRATION_1_2).close()
    }

    @Test
    fun anItemRoundTripsThroughTheStore(): Unit = runBlocking {
        val db = openInMemory()
        try {
            val item = InventoryItemEntity(
                substance = "Caffeine",
                unit = "mg",
                trackingStart = Instant.parse("2026-01-01T00:00:00Z"),
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                sortOrder = 3,
            ).withManualEvents(
                listOf(
                    ManualEvent(kind = ManualEvent.Kind.INITIAL, amount = 1000.0, date = Instant.parse("2026-01-01T00:00:00Z")),
                    ManualEvent(kind = ManualEvent.Kind.ADJUSTMENT, amount = -25.0, date = Instant.parse("2026-01-02T00:00:00Z"), note = "spilled"),
                ),
            )
            db.inventoryDao().upsert(item)

            val read = db.inventoryDao().byId(item.id).shouldNotBeNull()
            read.substance shouldBe "Caffeine"
            read.sortOrder shouldBe 3
            read.trackingStart shouldBe Instant.parse("2026-01-01T00:00:00Z")
            read.manualEvents.size shouldBe 2
            read.manualEvents[1].note shouldBe "spilled"
            read.manualEvents[1].kind shouldBe ManualEvent.Kind.ADJUSTMENT
            // The instants survive the column, which is the thing a converter
            // mistake would silently shift by fifty-eight years.
            read.manualEvents[1].date shouldBe Instant.parse("2026-01-02T00:00:00Z")
        } finally {
            db.close()
        }
    }

    @Test
    fun identityMatchingIsCaseInsensitiveOnNameAndExactOnSalt(): Unit = runBlocking {
        val db = openInMemory()
        try {
            val dao = db.inventoryDao()
            dao.upsert(item("Caffeine", null))
            dao.upsert(item("Caffeine", "Citrate"))

            dao.byIdentity("caffeine", null).shouldNotBeNull()
            dao.byIdentity("CAFFEINE", "Citrate").shouldNotBeNull()
            // A different salt is a different supply, and a null matches only a null.
            dao.byIdentity("Caffeine", "Hydrochloride").shouldBeNull()
            (dao.byIdentity("Caffeine", null)?.saltForm) shouldBe null
        } finally {
            db.close()
        }
    }

    @Test
    fun theReplayAgreesWithWhatWasStored(): Unit = runBlocking {
        val db = openInMemory()
        try {
            val dao = db.inventoryDao()
            val start = Instant.parse("2026-01-01T00:00:00Z")
            val item = InventoryItemEntity(
                substance = "Caffeine",
                unit = "mg",
                trackingStart = start,
                createdAt = start,
            ).withManualEvents(listOf(ManualEvent(kind = ManualEvent.Kind.INITIAL, amount = 500.0, date = start)))
            dao.upsert(item)

            // The cache column is written as part of the row, not through a
            // targeted setter: the replay is in-memory and the row it writes back
            // is always the freshest copy, so a separate UPDATE statement would be
            // a second way to say the same thing.
            val replayed = InventoryMath.quantity(item, emptyMap(), EmptySubstanceCatalog)
            dao.upsert(item.copy(currentQuantity = replayed))
            dao.byId(item.id).shouldNotBeNull().currentQuantity shouldBe 500.0
        } finally {
            db.close()
        }
    }

    private fun item(substance: String, saltForm: String?) = InventoryItemEntity(
        substance = substance,
        saltForm = saltForm,
        unit = "mg",
        trackingStart = Instant.parse("2026-01-01T00:00:00Z"),
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )
}
