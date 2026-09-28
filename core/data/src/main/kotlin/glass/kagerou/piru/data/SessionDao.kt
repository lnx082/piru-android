package glass.kagerou.piru.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import glass.kagerou.piru.data.entity.SessionEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface SessionDao {

    @Query("SELECT * FROM sessions ORDER BY start_date DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY start_date DESC")
    suspend fun all(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: UUID): SessionEntity?

    /**
     * Sessions whose span could overlap `[from, to]`, for the placement search.
     *
     * Matches the iOS predicate: a session is a candidate when it starts at or
     * before `to` and its last dose is at or after `from`. A null `last_dose_date`
     * — a row predating the field — is always a candidate, because the field only
     * ever *bounds* the fetch and placement re-derives the true span from the
     * doses. Being generous here over-fetches; it can never misplace.
     */
    @Query(
        "SELECT * FROM sessions WHERE start_date <= :to " +
            "AND (last_dose_date IS NULL OR last_dose_date >= :from) " +
            "ORDER BY start_date",
    )
    suspend fun inWindow(from: Long, to: Long): List<SessionEntity>

    /** The most recent session at or before [at], which is the candidate a new dose tries to join. */
    @Query("SELECT * FROM sessions WHERE start_date <= :at ORDER BY start_date DESC LIMIT 1")
    suspend fun latestAtOrBefore(at: Long): SessionEntity?

    @Upsert
    suspend fun upsert(entity: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SessionEntity)

    @Update
    suspend fun update(entity: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: UUID)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()

    /**
     * Recompute [SessionEntity.startDate] and [SessionEntity.lastDoseDate] from
     * the doses currently attached.
     *
     * One statement rather than a read-modify-write, so a concurrent dose move
     * cannot interleave between the read and the write. A session with no doses
     * is left untouched — the correlated subqueries yield null and the `WHERE`
     * rejects them — which is what the iOS `refreshDoseBounds()` does with its
     * early return, so a soon-to-be-deleted session does not jump to a sentinel
     * date.
     */
    @Query(
        "UPDATE sessions SET " +
            "start_date = (SELECT min(timestamp) FROM dose_entries WHERE session_id = sessions.id), " +
            "last_dose_date = (SELECT max(timestamp) FROM dose_entries WHERE session_id = sessions.id) " +
            "WHERE EXISTS (SELECT 1 FROM dose_entries WHERE session_id = sessions.id)",
    )
    suspend fun refreshAllDoseBounds()

    @Transaction
    suspend fun refreshDoseBounds(id: UUID) {
        val session = byId(id) ?: return
        val bounds = doseBounds(id) ?: return
        update(session.copy(startDate = bounds.first, lastDoseDate = bounds.second))
    }

    @Query("SELECT min(timestamp) AS lo, max(timestamp) AS hi FROM dose_entries WHERE session_id = :id")
    suspend fun doseBoundsRaw(id: UUID): BoundsRow?

    private suspend fun doseBounds(id: UUID): Pair<java.util.Date, java.util.Date>? {
        val row = doseBoundsRaw(id) ?: return null
        val lo = row.lo ?: return null
        return lo to (row.hi ?: lo)
    }
}

/** Min and max timestamp over a session's doses, either of which is null when the session is empty. */
data class BoundsRow(val lo: java.util.Date?, val hi: java.util.Date?)
