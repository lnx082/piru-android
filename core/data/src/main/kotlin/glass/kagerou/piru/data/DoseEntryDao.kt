package glass.kagerou.piru.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import glass.kagerou.piru.data.entity.DoseEntryEntity
import kotlinx.coroutines.flow.Flow
import java.util.Date
import java.util.UUID

@Dao
interface DoseEntryDao {

    /** Reverse chronological — the journal's order. */
    @Query("SELECT * FROM dose_entries ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<DoseEntryEntity>>

    @Query("SELECT * FROM dose_entries ORDER BY timestamp DESC")
    suspend fun all(): List<DoseEntryEntity>

    @Query("SELECT * FROM dose_entries WHERE timestamp >= :from AND timestamp < :to ORDER BY timestamp")
    suspend fun inRange(from: Date, to: Date): List<DoseEntryEntity>

    /**
     * The first row carrying [id].
     *
     * `id` is deliberately not unique, so more than one row can match until the
     * post-open repair has run. `LIMIT 1` mirrors the iOS `fetchLimit = 1`
     * on its deep-link and edit lookups.
     */
    @Query("SELECT * FROM dose_entries WHERE id = :id ORDER BY row_id LIMIT 1")
    suspend fun byId(id: UUID): DoseEntryEntity?

    @Query("SELECT * FROM dose_entries WHERE session_id = :sessionId ORDER BY timestamp")
    suspend fun forSession(sessionId: UUID): List<DoseEntryEntity>

    @Query("SELECT * FROM dose_entries WHERE row_id = :rowId")
    suspend fun byRowId(rowId: Long): DoseEntryEntity?

    /** Doses no session owns — the backfill's input, and normally empty. */
    @Query("SELECT * FROM dose_entries WHERE session_id IS NULL ORDER BY timestamp")
    suspend fun unassigned(): List<DoseEntryEntity>

    /** The doses attached to one session, ordered as a session reads: earliest first. */
    @Query("SELECT * FROM dose_entries WHERE session_id = :sessionId ORDER BY timestamp")
    suspend fun dosesFor(sessionId: UUID): List<DoseEntryEntity>

    @Query("SELECT count(*) FROM dose_entries")
    suspend fun count(): Long

    @Upsert
    suspend fun upsert(entity: DoseEntryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: DoseEntryEntity): Long

    @Update
    suspend fun update(entity: DoseEntryEntity)

    @Query("DELETE FROM dose_entries WHERE row_id = :rowId")
    suspend fun deleteByRowId(rowId: Long)

    @Query("DELETE FROM dose_entries")
    suspend fun deleteAll()

    // MARK: - Stable-id repair

    @Query("SELECT id FROM dose_entries GROUP BY id HAVING count(*) > 1")
    suspend fun duplicatedIds(): List<UUID>

    @Query("SELECT row_id FROM dose_entries WHERE id = :id ORDER BY row_id")
    suspend fun rowIdsFor(id: UUID): List<Long>

    @Query("UPDATE dose_entries SET id = :newId WHERE row_id = :rowId")
    suspend fun reassignId(rowId: Long, newId: UUID)

    /**
     * Give every duplicate [DoseEntryEntity.id] a fresh value, keeping the first
     * row's id and reassigning the rest.
     *
     * This is `StoreRecovery.backfillDuplicateEntryIDs`, and it is the *only*
     * thing guaranteeing [DoseEntryEntity.id] uniqueness — there is no unique
     * index on the column, on purpose, because the rows this repairs are exactly
     * the ones such an index would refuse to accept.
     *
     * The duplicates come from SwiftData's lightweight migration filling one
     * shared default expression into every pre-existing row. Room has no
     * equivalent hazard for a `@PrimaryKey(autoGenerate)`, but the same rows
     * arrive through an iOS backup import, so the sweep belongs on this side too.
     *
     * Runs on every launch and is a no-op once the ids are clean.
     */
    @Transaction
    suspend fun backfillDuplicateIds(): Int {
        var repaired = 0
        for (id in duplicatedIds()) {
            val rows = rowIdsFor(id)
            // First occurrence keeps the id; the rest move.
            for (rowId in rows.drop(1)) {
                reassignId(rowId, UUID.randomUUID())
                repaired++
            }
        }
        return repaired
    }
}
