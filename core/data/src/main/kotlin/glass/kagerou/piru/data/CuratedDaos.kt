package glass.kagerou.piru.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import glass.kagerou.piru.data.entity.FavoriteSubstanceEntity
import glass.kagerou.piru.data.entity.QuickLogDoseEntity

/**
 * The two "curated rows" tables, and why they arrived together.
 *
 * Both have been in the schema since v1 and both were counted by
 * [UserDataDao] — the favourites table is one of the four that decide whether a
 * store is worth recovering — but neither had a way in or out. The export is the
 * first thing that had to read every user-authored row, and it cannot honestly
 * write `"favorites": []` for a user who has favourites, or drop a set of
 * quick-log chips a restore was supposed to bring back.
 *
 * Adding a DAO is not a schema change: the tables and their indices are already
 * in the recorded schema, so this ships at the same database version.
 */
@Dao
interface FavoriteSubstanceDao {

    @Query("SELECT * FROM favorite_substances ORDER BY sort_order, created_at DESC")
    suspend fun all(): List<FavoriteSubstanceEntity>

    @Query("SELECT * FROM favorite_substances WHERE LOWER(substance) = LOWER(:substance) LIMIT 1")
    suspend fun byName(substance: String): FavoriteSubstanceEntity?

    @Upsert
    suspend fun upsert(row: FavoriteSubstanceEntity)

    @Insert
    suspend fun insert(row: FavoriteSubstanceEntity)

    @Update
    suspend fun update(row: FavoriteSubstanceEntity)

    @Delete
    suspend fun delete(row: FavoriteSubstanceEntity)

    @Query("DELETE FROM favorite_substances")
    suspend fun deleteAll()
}

/**
 * The curated quick-log chips.
 *
 * [QuickLogDoseEntity.key] is the identity an import merges on. It is computed
 * in Kotlin from the row's own fields rather than stored in a column, so the
 * importer reads [all] once and builds the key set from the entities — there is
 * no SQL form of it to select.
 */
@Dao
interface QuickLogDoseDao {

    @Query("SELECT * FROM quick_log_doses ORDER BY sort_order, row_id")
    suspend fun all(): List<QuickLogDoseEntity>

    @Query("SELECT * FROM quick_log_doses ORDER BY sort_order, row_id")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<QuickLogDoseEntity>>

    @Insert
    suspend fun insert(row: QuickLogDoseEntity): Long

    @Upsert
    suspend fun upsert(row: QuickLogDoseEntity)

    /**
     * Remove one chip.
     *
     * The per-row delete least-recently-used eviction needs. Without it the only way to drop a chip is `deleteAll`
     * and re-insert the survivors, which **rewrites every other row's `row_id`** — the same mistake the custom
     * substances DAO had.
     */
    @Query("DELETE FROM quick_log_doses WHERE row_id = :rowId")
    suspend fun deleteByRowId(rowId: Long)

    @Query("DELETE FROM quick_log_doses")
    suspend fun deleteAll()
}
