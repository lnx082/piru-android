package glass.kagerou.piru.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import glass.kagerou.piru.data.entity.NotificationPreferencesEntity
import kotlinx.coroutines.flow.Flow

/**
 * The single notification-setup row.
 *
 * ## One row, and the DAO is what keeps it that way
 * The iOS model has no key: there is simply one record, found by "take the
 * first". Room needs a primary key, so the entity carries an auto-generated one
 * and the convention moves here — every read takes the **lowest** `row_id` and
 * every write goes through [edit], which updates that row rather than inserting
 * a second. The shape is `UserProfileDao`'s, deliberately: the two singletons
 * fail the same way and should be readable as the same pattern.
 *
 * What a second row would cost here is quieter than a second body weight but not
 * smaller: `load()` is what seeds every reader's mirror, and a row that appeared
 * alongside the first would be adopted by whichever query happened to return it,
 * silently re-enabling notification types the user had turned off.
 */
@Dao
interface NotificationPreferencesDao {

    /** The row, or null before the first write. */
    @Query("SELECT * FROM notification_preferences ORDER BY row_id LIMIT 1")
    suspend fun current(): NotificationPreferencesEntity?

    /** The row as it changes, so the settings screen can follow it. */
    @Query("SELECT * FROM notification_preferences ORDER BY row_id LIMIT 1")
    fun observe(): Flow<NotificationPreferencesEntity?>

    @Insert
    suspend fun insert(row: NotificationPreferencesEntity): Long

    @Update
    suspend fun update(row: NotificationPreferencesEntity)

    @Query("SELECT COUNT(*) FROM notification_preferences")
    suspend fun count(): Int

    /** Drop the row so the next read seeds a fresh one — the "delete everything" half. */
    @Query("DELETE FROM notification_preferences")
    suspend fun deleteAll()

    /**
     * Read the row, hand it to [change], and write back what comes out.
     *
     * [change] returning null means "leave it alone". On an empty table the
     * change is applied to [DEFAULTS] and inserted, which is how the row is
     * seeded without the caller having to know what an empty one looks like —
     * the iOS store seeds it the same way, on the first read rather than at
     * install, because a user who never opens the screen never needs a row.
     */
    @Transaction
    suspend fun edit(
        change: (NotificationPreferencesEntity) -> NotificationPreferencesEntity?,
    ): NotificationPreferencesEntity {
        val existing = current()
        if (existing == null) {
            val seeded = change(DEFAULTS) ?: DEFAULTS
            val id = insert(seeded.copy(rowId = 0))
            return seeded.copy(rowId = id)
        }
        val updated = change(existing) ?: return existing
        update(updated)
        return updated
    }

    companion object {
        /**
         * What a fresh row is: the model's own defaults, which are the shipped
         * behaviour rather than a neutral placeholder — routine, follow-up,
         * inventory and check-in on; the flag-gated session types off.
         */
        val DEFAULTS = NotificationPreferencesEntity()
    }
}
