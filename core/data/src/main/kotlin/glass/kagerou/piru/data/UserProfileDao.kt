package glass.kagerou.piru.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import glass.kagerou.piru.data.entity.UserProfileRecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * The single profile row.
 *
 * ## One row, and the DAO is what keeps it that way
 * The iOS model has no key: there is simply one record, found by "take the
 * first". Room needs a primary key, so the entity carries an auto-generated one
 * and the convention moves here — every read takes the **lowest** `row_id` and
 * every write goes through [edit], which updates that row rather than inserting
 * a second.
 *
 * That matters more than it looks. `bodyWeightKg` scales every pharmacokinetic
 * calculation in the app, so a second row would not be a cosmetic duplicate: it
 * would be a second answer to "how heavy is this person", and which one the
 * models used would depend on the order rows came back in.
 */
@Dao
interface UserProfileDao {

    /** The row, or null before the first write. */
    @Query("SELECT * FROM user_profile ORDER BY row_id LIMIT 1")
    suspend fun current(): UserProfileRecordEntity?

    /** The row as it changes, for a screen that shows the weight. */
    @Query("SELECT * FROM user_profile ORDER BY row_id LIMIT 1")
    fun observe(): Flow<UserProfileRecordEntity?>

    @Insert
    suspend fun insert(row: UserProfileRecordEntity): Long

    @Update
    suspend fun update(row: UserProfileRecordEntity)

    @Query("SELECT COUNT(*) FROM user_profile")
    suspend fun count(): Int

    @Query("DELETE FROM user_profile")
    suspend fun deleteAll()

    /**
     * Read the row, hand it to [change], and write back what comes out.
     *
     * [change] returning null means "leave it alone", which is how a caller
     * avoids a pointless write — the update path is the one that has to touch
     * `row_id`, and rewriting an unchanged row would invalidate every observer
     * for nothing.
     */
    @Transaction
    suspend fun edit(change: (UserProfileRecordEntity) -> UserProfileRecordEntity?): UserProfileRecordEntity {
        val existing = current()
        if (existing == null) {
            val seeded = (change(DEFAULTS) ?: DEFAULTS)
            val id = insert(seeded.copy(rowId = 0))
            return seeded.copy(rowId = id)
        }
        val updated = change(existing) ?: return existing
        update(updated)
        return updated
    }

    companion object {
        /**
         * What a fresh profile is.
         *
         * Kept here rather than in the store so [edit] can seed a row without the
         * caller having to know what an empty one looks like.
         */
        val DEFAULTS = UserProfileRecordEntity()
    }
}
