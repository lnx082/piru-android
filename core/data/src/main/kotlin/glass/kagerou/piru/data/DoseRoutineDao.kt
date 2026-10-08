package glass.kagerou.piru.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import glass.kagerou.piru.data.entity.DoseRoutineEntity

/**
 * The user's named routines ("Pre-workout", "Night").
 *
 * The table has been in the schema since v1 and had no DAO, which made it the one user-data
 * table `deleteAll` could not reach: the wipe covered thirteen tables and left this one behind,
 * while its own KDoc promised "fourteen tables and this deletes all of them". Nothing writes a
 * routine yet, so no user data was surviving a wipe in practice — but a table that cannot be
 * read or cleared is a gap that only stays harmless while it is also unused.
 *
 * Adding a DAO is not a schema change, so this ships at the same database version.
 */
@Dao
interface DoseRoutineDao {

    @Query("SELECT * FROM dose_routines ORDER BY sort_order, name COLLATE NOCASE")
    suspend fun all(): List<DoseRoutineEntity>

    @Query("SELECT * FROM dose_routines WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): DoseRoutineEntity?

    @Insert
    suspend fun insert(row: DoseRoutineEntity)

    @Update
    suspend fun update(row: DoseRoutineEntity)

    @Upsert
    suspend fun upsert(row: DoseRoutineEntity)

    @Delete
    suspend fun delete(row: DoseRoutineEntity)

    @Query("DELETE FROM dose_routines WHERE name = :name")
    suspend fun deleteByName(name: String)

    @Query("DELETE FROM dose_routines")
    suspend fun deleteAll()
}
