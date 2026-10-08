package glass.kagerou.piru.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import glass.kagerou.piru.data.entity.CustomSubstanceRecordEntity
import glass.kagerou.piru.data.entity.DailyDoseItemEntity
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.data.entity.DoseRoutineEntity
import glass.kagerou.piru.data.entity.FavoriteSubstanceEntity
import glass.kagerou.piru.data.entity.InventoryItemEntity
import glass.kagerou.piru.data.entity.LabMeasurementEntity
import glass.kagerou.piru.data.entity.NotificationPreferencesEntity
import glass.kagerou.piru.data.entity.QuickLogDoseEntity
import glass.kagerou.piru.data.entity.RoutineOccurrenceEntity
import glass.kagerou.piru.data.entity.SessionEntity
import glass.kagerou.piru.data.entity.SessionNoteEntity
import glass.kagerou.piru.data.entity.SubstanceColorEntity
import glass.kagerou.piru.data.entity.ToleranceStateEntity
import glass.kagerou.piru.data.entity.UserProfileRecordEntity

/**
 * The user's own data: what they logged, what they track, what they configured.
 *
 * Distinct from the bundled substance catalog, which is a fixed read-only
 * artifact opened separately (see `glass.kagerou.piru.substance.SubstanceDb`).
 * The two never join at the SQL level — the catalog is keyed by substance name
 * and PSID, and this store references those by value. That is also why nothing
 * here is a foreign key into the catalog: the catalog is replaced wholesale on a
 * data pass, and a user's dose must survive a substance being renamed or removed
 * from it.
 *
 * ## Relationships
 * Only two, matching the iOS schema: a dose belongs to a session (nullify —
 * deleting a session leaves its doses unassigned), and a note belongs to a
 * session (cascade — a note has no meaning outside it). Everything else joins by
 * string or JSON, a deliberate choice upstream so the same flat schema compiles
 * into the widget extensions; that shape is kept here rather than normalized,
 * because the two stores must round-trip through the same backup format.
 *
 * ## Which tables hold "user data"
 * Not all of these count as content. A store with only a [ToleranceStateEntity]
 * or a [UserProfileRecordEntity] row is *empty* — the first is a derived cache
 * and the second a lone settings row, and neither should make a fresh install
 * look data-bearing to a recovery prompt. The iOS
 * `StoreRecovery.countUserRows` tallies only `DoseEntry`, `DailyDoseItem`,
 * `FavoriteSubstance` and `SubstanceColor`; `UserDataCounter` reproduces that
 * exactly, and the choice of which four is the definition, not an oversight.
 *
 * ## No version ladder yet
 * The iOS store uses SwiftData's automatic lightweight migration and ships no
 * `SchemaMigrationPlan` at all; additive model changes need no version bump.
 * Room has no equivalent, so this starts at version 1 with no migrations and a
 * documented rule: an additive change ships as a `Migration` from the previous
 * version, and a destructive fallback is never acceptable — this is user data
 * with no server copy.
 */
@Database(
    entities = [
        DoseEntryEntity::class,
        SessionEntity::class,
        SessionNoteEntity::class,
        SubstanceColorEntity::class,
        ToleranceStateEntity::class,
        UserProfileRecordEntity::class,
        FavoriteSubstanceEntity::class,
        DailyDoseItemEntity::class,
        DoseRoutineEntity::class,
        RoutineOccurrenceEntity::class,
        NotificationPreferencesEntity::class,
        CustomSubstanceRecordEntity::class,
        QuickLogDoseEntity::class,
        InventoryItemEntity::class,
        LabMeasurementEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class PiruDatabase : RoomDatabase() {
    abstract fun doseEntryDao(): DoseEntryDao
    abstract fun sessionDao(): SessionDao
    abstract fun sessionNoteDao(): SessionNoteDao
    abstract fun substanceColorDao(): SubstanceColorDao
    abstract fun toleranceStateDao(): ToleranceStateDao
    abstract fun userDataDao(): UserDataDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun dailyDoseItemDao(): DailyDoseItemDao
    abstract fun routineOccurrenceDao(): RoutineOccurrenceDao
    abstract fun notificationPreferencesDao(): NotificationPreferencesDao

    /**
     * The user's own substances.
     *
     * The table has been in [entities] since v1; the DAO arrived with the export,
     * which is the first thing that had to read and write it. Adding a DAO is not
     * a schema change, so this ships at the same database version.
     */
    abstract fun customSubstanceDao(): CustomSubstanceDao

    /** The user's favourites and their quick-log chips. See [FavoriteSubstanceDao]. */
    abstract fun favoriteSubstanceDao(): FavoriteSubstanceDao

    /** See [QuickLogDoseDao]. */
    abstract fun quickLogDoseDao(): QuickLogDoseDao

    /**
     * The user's lab results.
     *
     * v3: these were JSON in a private preferences file until it became clear they
     * were outside every export, import and erase path. See
     * [LabMeasurementEntity] for the full account, and [MIGRATION_2_3] for the
     * table and the backfill that follows it.
     */
    abstract fun labMeasurementDao(): LabMeasurementDao

    companion object {
        /**
         * Open the user's store, creating it on first launch.
         *
         * No `fallbackToDestructiveMigration`: the class doc's rule is that an additive
         * change ships a `Migration` from the previous version, and this is user data
         * with no server copy to restore it from. A destructive fallback would turn the
         * first schema mistake into silent data loss.
         */
        fun open(context: Context, name: String = "piru.db"): PiruDatabase =
            Room.databaseBuilder(context.applicationContext, PiruDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        /**
         * v1 → v2: the tracked supplies.
         *
         * Purely additive — one table, no existing column touched — which is what
         * makes this safe to run against a live store without a snapshot first.
         * The DDL is written out rather than trusting Room to reverse-engineer it
         * from the entity, because Room *validates* the migrated schema against
         * that entity on open: a missing `DEFAULT` or a differently named index
         * fails the migration with an exception rather than a wrong table, so the
         * two must match exactly and the generated `createSql` is the thing to
         * copy from.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `inventory_items` (" +
                        "`id` TEXT NOT NULL, " +
                        "`substance` TEXT NOT NULL DEFAULT '', " +
                        "`salt_form` TEXT, " +
                        "`unit` TEXT NOT NULL DEFAULT 'mg', " +
                        "`tracking_start` INTEGER NOT NULL, " +
                        "`low_stock_threshold` REAL, " +
                        "`low_stock_notified` INTEGER NOT NULL DEFAULT 0, " +
                        "`baseline_quantity` REAL, " +
                        "`dose_size` REAL, " +
                        "`unit_strength_mg` REAL, " +
                        "`current_quantity` REAL NOT NULL DEFAULT 0, " +
                        "`restocks_json` TEXT NOT NULL DEFAULT '', " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`sort_order` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`id`))",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_inventory_items_substance_salt_form` " +
                        "ON `inventory_items` (`substance`, `salt_form`)",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_inventory_items_sort_order` " +
                        "ON `inventory_items` (`sort_order`)",
                )
            }
        }

        /**
         * v2 → v3: the lab results become a table.
         *
         * Additive, like the one above, and written out for the same reason: Room
         * validates the migrated schema against the entity on open, so a missing
         * `DEFAULT` or a differently named index fails loudly rather than
         * silently producing a table that does not match.
         *
         * ## The rows themselves are backfilled after this runs, not inside it
         * The v2 rows live in the `piru.labMeasurements` preferences file as a JSON
         * blob (`LabMeasurementStore`), and a `Migration` has no `Context`, so it
         * has no way to open that file. Copying the blob in here would mean both
         * reaching outside the connection this method is given and doing JSON
         * parsing inside a schema migration.
         *
         * So this creates the table and nothing else, and
         * `LabMeasurementStore.importLegacyRows` copies the blob in on the next
         * launch, once, before any reader runs. Until that has happened the table
         * being empty is correct: an empty table and an absent one mean the same
         * thing to every reader, and the backfill is what makes the rows appear.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `lab_measurements` (" +
                        "`row_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`id` TEXT NOT NULL, " +
                        "`date` INTEGER NOT NULL, " +
                        "`analyte_key` TEXT NOT NULL, " +
                        "`value` REAL NOT NULL, " +
                        "`input_unit` TEXT NOT NULL, " +
                        "`ester_id` TEXT, " +
                        "`excluded_from_calibration` INTEGER NOT NULL DEFAULT 0, " +
                        "`note` TEXT, " +
                        "`created_at` INTEGER NOT NULL)",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_lab_measurements_analyte_key` " +
                        "ON `lab_measurements` (`analyte_key`)",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_lab_measurements_date` " +
                        "ON `lab_measurements` (`date`)",
                )
            }
        }
    }
}
