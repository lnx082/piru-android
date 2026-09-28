package glass.kagerou.piru.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import glass.kagerou.piru.data.entity.RoutineOccurrenceEntity
import java.util.Date
import java.util.UUID

/**
 * The routine-occurrence record — one row per (routine, item, day-slot).
 *
 * ## Written by one thing
 * [RoutineOccurrenceService] is the only writer, exactly as on iOS. The DAO is
 * deliberately narrow for that reason: there is no `upsert` and no general
 * `update`, because a second way to write a state would be a second answer to
 * "was this slot taken", and the whole design of the table is that there is one.
 * The two targeted setters below are the two things a reconcile or a Skip Today
 * actually does — change a state, and change a state together with the dose that
 * satisfied it.
 *
 * ## Why `row_id` is the handle
 * The plan a reconcile builds is keyed by row, and the row is identified by
 * `row_id` here rather than by the iOS `PersistentIdentifier`. Nothing outside
 * this DAO and its service should hold one: it is an implementation detail of
 * how the store addresses a row, not part of the occurrence's identity, which is
 * `(identity, route, slot, day)` — see [RoutineOccurrenceService.slotKey].
 */
@Dao
interface RoutineOccurrenceDao {

    /**
     * The rows whose `due_day` falls in `[from, to)`.
     *
     * The window is half-open because a day's rows are exactly
     * `[startOfDay, startOfDay + 1 day)` — and a closed upper bound would read
     * the next day's first row whenever a zone change put a boundary on a
     * millisecond that another row shared.
     */
    @Query("SELECT * FROM routine_occurrences WHERE due_day >= :from AND due_day < :to")
    suspend fun forDay(from: Date, to: Date): List<RoutineOccurrenceEntity>

    /**
     * The ids of rows before [day] that are still [state].
     *
     * Only the id is returned: the one caller moves them to `missed` without
     * reading anything else, and `missed` is history whose satisfying dose (if
     * any) must survive untouched — which is why the setter below sets the state
     * alone rather than rewriting the whole row.
     */
    @Query("SELECT row_id FROM routine_occurrences WHERE due_day < :day AND state_raw = :state")
    suspend fun idsBefore(day: Date, state: String): List<Long>

    @Query("SELECT * FROM routine_occurrences WHERE row_id = :rowId")
    suspend fun byRowId(rowId: Long): RoutineOccurrenceEntity?

    @Insert
    suspend fun insert(row: RoutineOccurrenceEntity): Long

    @Insert
    suspend fun insertAll(rows: List<RoutineOccurrenceEntity>)

    /**
     * Set the state and leave everything else alone.
     *
     * The end-of-day expiry (`pending` to `missed`) and the Skip Today action
     * both go through here, and both must not touch `satisfying_entry_id`: a
     * skipped slot that was logged first keeps the dose that satisfied it, and a
     * missed row is history rather than a wiped row.
     */
    @Query("UPDATE routine_occurrences SET state_raw = :state WHERE row_id = :rowId")
    suspend fun setState(rowId: Long, state: String)

    /** Set the state and the satisfying dose together — what a match outcome is. */
    @Query("UPDATE routine_occurrences SET state_raw = :state, satisfying_entry_id = :satisfyingEntryId WHERE row_id = :rowId")
    suspend fun setOutcome(rowId: Long, state: String, satisfyingEntryId: UUID?)

    @Query("DELETE FROM routine_occurrences WHERE row_id IN (:rowIds)")
    suspend fun deleteByRowIds(rowIds: List<Long>)

    @Query("DELETE FROM routine_occurrences WHERE row_id = :rowId")
    suspend fun deleteByRowId(rowId: Long)

    /**
     * Drop the whole record.
     *
     * The occurrence table is derived, so "delete everything" has no reason to
     * keep it: a row here describes a routine that no longer exists and would
     * keep a re-ask suppressed into a journal the user is starting over.
     */
    @Query("DELETE FROM routine_occurrences")
    suspend fun deleteAll()
}
