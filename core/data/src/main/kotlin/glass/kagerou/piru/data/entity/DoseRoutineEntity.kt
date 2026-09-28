package glass.kagerou.piru.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import glass.kagerou.piru.data.JsonLists

/**
 * A named set of daily-dose items — "Pre-workout", "Night" — staged together
 * from one pill on the log screen, with an optional time of day and a repeating
 * reminder.
 *
 * Ported from `Shared/Models/DoseRoutine.swift`.
 *
 * Items join a routine by [DailyDoseItemEntity.category] equalling [name] —
 * string-keyed, with no relationship, so the schema stays flat as it is on the
 * iOS side. Renaming a routine must cascade the new name to its items'
 * category; nothing enforces that at the SQL level, and adding a foreign key
 * would reject the flat shape the iOS store writes.
 */
@Entity(tableName = "dose_routines")
data class DoseRoutineEntity(
    /** Unique on the iOS side, so it is the primary key here. Also the join key for [DailyDoseItemEntity.category]. */
    @PrimaryKey
    @ColumnInfo(name = "name")
    val name: String,

    /** User-defined position in the routines list and the log screen pills. */
    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0,

    /**
     * Optional time of day as minutes from midnight (420 = 7:00). Drives pill
     * ordering and, when [remind] is on, the daily reminder.
     */
    @ColumnInfo(name = "time_minutes")
    val timeMinutes: Int? = null,

    /** Whether to schedule a repeating daily reminder at [timeMinutes]. */
    @ColumnInfo(name = "remind", defaultValue = "0")
    val remind: Boolean = false,

    /**
     * Snooze-style re-asks after the [remind] notification, as JSON: minutes past
     * [timeMinutes] for each follow-up ("still need to log?"). Empty means no
     * follow-ups, and is only meaningful while [remind] is on.
     */
    @ColumnInfo(name = "follow_up_minutes_json", defaultValue = "")
    val followUpMinutesJson: String = "",
) {
    /** [followUpMinutesJson] as minutes. Empty when unset or malformed. */
    val followUpMinutes: List<Int> get() = JsonLists.ints(followUpMinutesJson)
}
