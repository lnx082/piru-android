package glass.kagerou.piru.data

import androidx.room.Dao
import androidx.room.Query

/**
 * The counts that decide whether the store holds anything.
 *
 * ## Which four tables, and why that is the definition
 * The iOS `StoreRecovery.countUserRows` tallies exactly [doseEntries],
 * [dailyDoseItems], [favorites] and [substanceColors] — and deliberately
 * **excludes** the tolerance cache, the profile row, notification preferences
 * and the routine-occurrence history.
 *
 * That exclusion is the point, not an oversight. A store holding only a
 * tolerance cache row, or only a profile row, is *empty* as far as the user is
 * concerned: those are a derived cache and a lone settings row. Counting them
 * would make a freshly installed app look data-bearing and offer a recovery
 * prompt over nothing. Any change to this list changes what "empty" means, and
 * the recovery flow reads it — so it is a decision to make deliberately, in both
 * codebases at once.
 */
@Dao
interface UserDataDao {

    @Query("SELECT count(*) FROM dose_entries")
    suspend fun doseEntries(): Long

    @Query("SELECT count(*) FROM daily_dose_items")
    suspend fun dailyDoseItems(): Long

    @Query("SELECT count(*) FROM favorite_substances")
    suspend fun favorites(): Long

    @Query("SELECT count(*) FROM substance_colors")
    suspend fun substanceColors(): Long
}
