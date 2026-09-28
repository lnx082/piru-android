package glass.kagerou.piru.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import glass.kagerou.piru.data.entity.ToleranceStateEntity
import glass.kagerou.piru.engine.ReceptorClasses
import java.util.Date
import kotlinx.coroutines.flow.Flow

/**
 * The tolerance cache: one row per mechanism class, rewritten by each replay.
 *
 * Ported from `ToleranceStore.persist(_:now:)` and `loadCachedSnapshot()`.
 *
 * ## Three outcomes per class, and the third is the one that is easy to get wrong
 * A replay produces a row for every class some in-window dose drives. What happens
 * to the *other* rows is where the behaviour lives:
 * - **Computed** — written with its six scalars and a fresh `lastUpdated`.
 * - **A stale key that is not a class at all** — deleted. These are legacy
 *   per-receptor cache keys from before the engine went per-class; they can never
 *   be recomputed, so leaving them would grow the table forever.
 * - **A valid class that is no longer driven** — **reset to naïve**, not deleted.
 *   All four layers plus `chronicExposure` go to zero, which is `S = 1`: a
 *   substance the user has stopped taking reads as *rested*, because that is what
 *   they are. The row stays so the set of rows is stable — a class that vanished
 *   and reappeared would make "no row" mean both "never driven" and "recovered".
 *
 * Deleting instead of resetting would look almost identical on screen and quietly
 * break the second meaning. It is also the shape a naive "rewrite the table"
 * implementation produces, which is why the two rules are spelled out here rather
 * than left to the caller.
 */
@Dao
interface ToleranceStateDao {

    @Query("SELECT * FROM tolerance_states")
    fun observeAll(): Flow<List<ToleranceStateEntity>>

    @Query("SELECT * FROM tolerance_states")
    suspend fun all(): List<ToleranceStateEntity>

    @Query("SELECT * FROM tolerance_states WHERE target = :receptorClass")
    suspend fun forClass(receptorClass: String): ToleranceStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ToleranceStateEntity)

    @Query("DELETE FROM tolerance_states WHERE target = :receptorClass")
    suspend fun deleteClass(receptorClass: String)

    @Query(
        """
        UPDATE tolerance_states
           SET s_acute = 0, s_adaptive = 0, s_deep = 0, s_synthesis = 0,
               chronic_exposure = 0, last_updated = :updatedAt
         WHERE target = :receptorClass
        """,
    )
    suspend fun resetToNaive(receptorClass: String, updatedAt: Long)

    @Query("DELETE FROM tolerance_states")
    suspend fun deleteAll()

    /**
     * Write one replay's result: upsert what it computed, reset the classes it no
     * longer drives, and drop keys that are not classes.
     *
     * One transaction, because a reader between the steps would otherwise see a
     * half-written cache — and the half that reads as *naïve* is indistinguishable
     * from "fully rested", so the gap would render as a confident wrong answer
     * rather than as missing data.
     *
     * `validClasses` is derived here rather than taken as a parameter: a caller
     * passing a stale or partial set would silently delete real class rows, and
     * there is no reason to put that decision in two places.
     */
    @Transaction
    suspend fun persist(states: List<ToleranceStateEntity>, nowEpochMillis: Long) {
        // The checkpoint timestamp is stamped here rather than trusted from the caller.
        // It is the anchor the slow accumulators are decayed forward from on the next
        // replay, so a row written without it reads as "never updated" — and the
        // accumulators it carries would then be dropped as carrying nothing forward.
        // Upstream sets it in the same place, for the same reason.
        for (state in states) upsert(state.copy(lastUpdated = Date(nowEpochMillis)))
        val computed = states.mapTo(mutableSetOf()) { it.target }
        val valid = ReceptorClasses.ReceptorClass.entries.mapTo(mutableSetOf()) { it.wireValue }
        for (row in all()) {
            if (row.target in computed) continue
            if (row.target in valid) resetToNaive(row.target, nowEpochMillis) else deleteClass(row.target)
        }
    }

    /** Drop the cache without rewriting it — for a store reset or a data restore. */
    @Transaction
    suspend fun clear() {
        deleteAll()
    }
}
