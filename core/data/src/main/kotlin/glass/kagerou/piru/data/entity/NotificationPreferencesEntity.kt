package glass.kagerou.piru.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import glass.kagerou.piru.data.JsonLists

/**
 * Per-type notification enablement — the durable backing of the notifications
 * management screen.
 *
 * Ported from `Shared/Models/NotificationPreferences.swift`.
 *
 * A singleton record, created lazily by the preferences store, which also seeds
 * it once from the legacy wellness and phase flags. It lives in the store rather
 * than in preferences so a user's notification setup rides the existing
 * backup/restore path.
 *
 * Defaults mirror shipped behaviour: the types that fire with no switch
 * (routine, inventory) default on; the flag-gated session types default off
 * until onboarding or the management screen enables them.
 *
 * ## Single row, by convention
 * The iOS model has no key — there is one row, found by "take the first". Room
 * needs a primary key, so this gets an auto-generated one and the same
 * convention: read the lowest [rowId], update it, never insert a second.
 */
@Entity(tableName = "notification_preferences")
data class NotificationPreferencesEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id")
    val rowId: Long = 0,

    /**
     * Master posture — false pauses every notification type without touching the
     * per-type choices.
     */
    @ColumnInfo(name = "master_enabled", defaultValue = "1")
    val masterEnabled: Boolean = true,

    @ColumnInfo(name = "hydration_enabled", defaultValue = "0")
    val hydrationEnabled: Boolean = false,

    @ColumnInfo(name = "sleep_enabled", defaultValue = "0")
    val sleepEnabled: Boolean = false,

    @ColumnInfo(name = "phase_enabled", defaultValue = "0")
    val phaseEnabled: Boolean = false,

    @ColumnInfo(name = "cumulative_enabled", defaultValue = "0")
    val cumulativeEnabled: Boolean = false,

    @ColumnInfo(name = "routine_enabled", defaultValue = "1")
    val routineEnabled: Boolean = true,

    @ColumnInfo(name = "routine_follow_up_enabled", defaultValue = "1")
    val routineFollowUpEnabled: Boolean = true,

    @ColumnInfo(name = "inventory_enabled", defaultValue = "1")
    val inventoryEnabled: Boolean = true,

    @ColumnInfo(name = "check_in_enabled", defaultValue = "1")
    val checkInEnabled: Boolean = true,

    /**
     * Quiet hours: dose reminders and session nudges whose fire time falls inside
     * the window are silenced. Safety warnings (cumulative dose) and routines the
     * user timed explicitly are exempt. Minutes from midnight; the window may
     * wrap, as 23:00 → 07:00 does.
     */
    @ColumnInfo(name = "quiet_hours_enabled", defaultValue = "0")
    val quietHoursEnabled: Boolean = false,

    @ColumnInfo(name = "quiet_hours_start_minutes", defaultValue = "1380")
    val quietHoursStartMinutes: Int = 23 * 60,

    @ColumnInfo(name = "quiet_hours_end_minutes", defaultValue = "420")
    val quietHoursEndMinutes: Int = 7 * 60,

    /** Per-type Time Sensitive delivery — the user decides which eligible types break through Focus. */
    @ColumnInfo(name = "routine_time_sensitive", defaultValue = "1")
    val routineTimeSensitive: Boolean = true,

    @ColumnInfo(name = "routine_follow_up_time_sensitive", defaultValue = "1")
    val routineFollowUpTimeSensitive: Boolean = true,

    @ColumnInfo(name = "cumulative_time_sensitive", defaultValue = "1")
    val cumulativeTimeSensitive: Boolean = true,

    /**
     * Global "ask again" cadence as JSON, or null when never written.
     *
     * The null-versus-empty distinction carries meaning and must survive:
     * **null reads as the `[10]` default**, while an explicit empty list is a
     * deliberate "no re-asks" and round-trips as empty. A single empty default
     * here would silently turn "never configured" into "opted out", disabling
     * re-asks for every user who never opened the screen.
     */
    @ColumnInfo(name = "ask_again_default_json")
    val askAgainDefaultJson: String? = null,
) {
    /**
     * Minutes after a med's reminder time for each "still need to log?" re-ask.
     * Falls back to `[10]` when the column is null; an explicit empty list stays
     * empty. A med can override or opt out individually.
     */
    val askAgainDefaultMinutes: List<Int>
        get() {
            val raw = askAgainDefaultJson ?: return DEFAULT_ASK_AGAIN
            return if (raw.isEmpty()) emptyList() else JsonLists.ints(raw)
        }

    companion object {
        /** The cadence assumed when nothing has been written — ten minutes. */
        val DEFAULT_ASK_AGAIN: List<Int> = listOf(10)
    }
}
