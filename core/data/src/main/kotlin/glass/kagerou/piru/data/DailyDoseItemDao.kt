package glass.kagerou.piru.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import glass.kagerou.piru.data.entity.DailyDoseItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * The recurring medications and supplements.
 *
 * ## Why this exists rather than a projection
 * The routine-reminder scheduler and the occurrence reconciler both need these
 * rows, and the scheduler used to read them through its own hand-written SQL
 * projection with its own copy of the column names. One table, two readers, two
 * chances to disagree with the schema — and the entity already derives every
 * field both of them want ([DailyDoseItemEntity.reminderTimesMinutes],
 * [DailyDoseItemEntity.askAgainOverrideMinutes], [DailyDoseItemEntity.identityKey]),
 * so the projection bought nothing but drift. The entity is the read.
 *
 * ## Ordering
 * `sort_order` then `row_id`, which is what the scheduler anchors notification
 * identifiers on and what the list screens fall back to when nothing has been
 * dragged. The `row_id` tiebreak is not decoration: two items can share a
 * `sort_order`, and an unstable order would mint two different identifiers for
 * the same reminder on two consecutive passes.
 *
 * ## No writer in this build
 * The meds editor that would create these rows is not ported, so a fresh install
 * has an empty table and only a restored backup can fill it. The write half is
 * nevertheless part of the DAO: the reconciler's on-device spec needs real rows
 * to derive from, and an importer writing them by hand would be spelling the
 * same twenty-two column names this DAO exists to stop spelling twice.
 */
@Dao
interface DailyDoseItemDao {

    /** Every item, in display order. */
    @Query("SELECT * FROM daily_dose_items ORDER BY sort_order, row_id")
    suspend fun all(): List<DailyDoseItemEntity>

    /** Every item, as it changes, for a list that shows the schedule. */
    @Query("SELECT * FROM daily_dose_items ORDER BY sort_order, row_id")
    fun observeAll(): Flow<List<DailyDoseItemEntity>>

    @Query("SELECT * FROM daily_dose_items WHERE row_id = :rowId")
    suspend fun byRowId(rowId: Long): DailyDoseItemEntity?

    @Insert
    suspend fun insert(item: DailyDoseItemEntity): Long

    @Delete
    suspend fun delete(item: DailyDoseItemEntity)

    @Query("DELETE FROM daily_dose_items")
    suspend fun deleteAll()
}
