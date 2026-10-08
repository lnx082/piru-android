package glass.kagerou.piru.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import glass.kagerou.piru.data.entity.LabMeasurementEntity

/**
 * The user's lab results.
 *
 * The table arrived in v3 — see [LabMeasurementEntity] for why it moved out of a
 * preferences file, and `PiruDatabase.MIGRATION_2_3` for the copy that brings
 * existing rows across.
 *
 * Reads are ordered newest-first because every reader wants it that way: the
 * hormone-levels screen plots a series forwards from the oldest point, and the
 * injection-levels tool shows a history where the most recent draw is the one a
 * user looks for. Ordering in SQL rather than at each call site keeps the two
 * screens agreeing about what "the list" is.
 */
@Dao
interface LabMeasurementDao {

    @Query("SELECT * FROM lab_measurements ORDER BY date DESC, row_id DESC")
    suspend fun all(): List<LabMeasurementEntity>

    /** The rows for one analyte, oldest first — the order a series is plotted in. */
    @Query(
        "SELECT * FROM lab_measurements WHERE analyte_key = :analyteKey " +
            "ORDER BY date ASC, row_id ASC",
    )
    suspend fun forAnalyte(analyteKey: String): List<LabMeasurementEntity>

    @Query("SELECT * FROM lab_measurements WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): LabMeasurementEntity?

    @Insert
    suspend fun insert(row: LabMeasurementEntity): Long

    @Update
    suspend fun update(row: LabMeasurementEntity)

    @Delete
    suspend fun delete(row: LabMeasurementEntity)

    @Query("DELETE FROM lab_measurements WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE lab_measurements SET excluded_from_calibration = :excluded WHERE id = :id")
    suspend fun setExcluded(id: String, excluded: Boolean)

    @Query("DELETE FROM lab_measurements")
    suspend fun deleteAll()
}
