package glass.kagerou.piru.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import glass.kagerou.piru.data.entity.CustomSubstanceRecordEntity
import glass.kagerou.piru.model.DoseRange
import glass.kagerou.piru.model.DurationProfile
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * The user's own substances.
 *
 * The table has been in the schema since v1 as part of the "same flat shape as
 * iOS" rule, but nothing read or wrote it — a custom substance could be created
 * on iOS, exported, and imported here into a table no screen queried. This DAO
 * exists because the export has to be able to *carry* them: `PiruFile.customSubstances`
 * is a required section, and writing an empty array for a user who has a dozen
 * would be the exact silent loss the export exists to prevent.
 *
 * No screen is wired to it yet. That is deliberate and does not make the DAO
 * wrong — a row that survives the round trip is strictly better than one that
 * does not, and the library screen arrives separately.
 *
 * ## Merge is by lowercased name, not by id
 * `DataExportImport.importCustomSubstances` matches on `name.lowercased()` and
 * keeps the *existing* row's UUID, so re-importing a backup updates a substance
 * the user has since edited rather than minting a second row with the same name.
 */
@Dao
interface CustomSubstanceDao {

    @Query("SELECT * FROM custom_substances ORDER BY row_id")
    suspend fun all(): List<CustomSubstanceRecordEntity>

    @Query("SELECT * FROM custom_substances WHERE LOWER(name) = LOWER(:name) ORDER BY row_id LIMIT 1")
    suspend fun byName(name: String): CustomSubstanceRecordEntity?

    @Query("SELECT * FROM custom_substances WHERE id = :id")
    suspend fun byId(id: UUID): CustomSubstanceRecordEntity?

    @Insert
    suspend fun insert(row: CustomSubstanceRecordEntity): Long

    @Update
    suspend fun update(row: CustomSubstanceRecordEntity)

    @Upsert
    suspend fun upsert(row: CustomSubstanceRecordEntity)

    /**
     * Removes one entry.
     *
     * Added because the screen that edits entries had only [deleteAll] to work with, and removing one row by
     * re-inserting the others **loses their `row_id`s** — they come back as new rows — as well as writing the whole
     * table to delete a single entry.
     */
    @Query("DELETE FROM custom_substances WHERE id = :id")
    suspend fun deleteById(id: UUID)

    @Query("DELETE FROM custom_substances")
    suspend fun deleteAll()
}

/**
 * The two JSON blobs a custom-substance row carries.
 *
 * Kept beside the DAO rather than on the entity because the entity's accessors
 * are read-only and the iOS side writes these same shapes through
 * `JSONEncoder`, so one place should own both directions. An empty list and a
 * null are the same thing here — the column holds `null` for "no ladder", which
 * is what the iOS optional encodes to.
 */
object CustomSubstanceBlobs {

    private val json = Json { ignoreUnknownKeys = true }

    fun encodeDoses(value: DoseRange?): String? =
        value?.let { runCatching { json.encodeToString(DoseRange.serializer(), it) }.getOrNull() }

    fun encodeDuration(value: DurationProfile?): String? =
        value?.let { runCatching { json.encodeToString(DurationProfile.serializer(), it) }.getOrNull() }
}
