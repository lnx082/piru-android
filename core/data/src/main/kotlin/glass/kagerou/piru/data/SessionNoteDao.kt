package glass.kagerou.piru.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import glass.kagerou.piru.data.entity.SessionNoteEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface SessionNoteDao {

    @Query("SELECT * FROM session_notes WHERE session_id = :sessionId ORDER BY timestamp")
    fun observeForSession(sessionId: UUID): Flow<List<SessionNoteEntity>>

    @Query("SELECT * FROM session_notes WHERE session_id = :sessionId ORDER BY timestamp")
    suspend fun forSession(sessionId: UUID): List<SessionNoteEntity>

    @Query("SELECT * FROM session_notes ORDER BY timestamp DESC")
    suspend fun all(): List<SessionNoteEntity>

    @Query("SELECT * FROM session_notes WHERE id = :id")
    suspend fun byId(id: UUID): SessionNoteEntity?

    /**
     * The session's one summary note, if it has been written.
     *
     * A session has at most one — the note whose `kind` is `summary` and whose
     * text mirrors `Session.note`. More than one would mean the mirror got out of
     * step, so the writers read through this and update rather than insert.
     */
    @Query(
        "SELECT * FROM session_notes WHERE session_id = :sessionId " +
            "AND kind_raw = 'summary' ORDER BY timestamp LIMIT 1",
    )
    suspend fun summaryFor(sessionId: UUID): SessionNoteEntity?

    @Upsert
    suspend fun upsert(entity: SessionNoteEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SessionNoteEntity)

    @Update
    suspend fun update(entity: SessionNoteEntity)

    @Query("DELETE FROM session_notes WHERE id = :id")
    suspend fun deleteById(id: UUID)

    @Query("DELETE FROM session_notes")
    suspend fun deleteAll()
}
