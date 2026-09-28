package glass.kagerou.piru.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import glass.kagerou.piru.data.JsonLists
import java.util.Date
import java.util.UUID

/**
 * A set of [DoseEntryEntity] rows grouped by temporal proximity — the Journal's
 * primary organizing unit, replacing calendar-day bucketing.
 *
 * Ported from `Shared/Models/Session.swift`. A session is decided at log time by
 * `SessionClustering` and persisted; it is never silently re-clustered
 * afterwards. The user owns it from then on via merge / split / reassign. A lone
 * dose is a session of one — the model is uniform, with no special-casing.
 *
 * ## Maintenance sessions
 * A session whose doses are *all* background medications is a *maintenance*
 * session — it renders as a compact "Medications" row rather than a full
 * timeline card. That is derived, not stored, so it stays correct as doses move.
 *
 * ## Delete rule
 * The `doses` relationship is `.nullify` on the iOS side, so deleting a session
 * never deletes its doses — they simply become unassigned. The foreign key on
 * [DoseEntryEntity] carries that, not this entity.
 */
@Entity(
    tableName = "sessions",
    indices = [
        Index(value = ["start_date"]),
        Index(value = ["last_dose_date"]),
    ],
)
data class SessionEntity(
    /**
     * Stable identifier for routing and deep links. Unique on the iOS side via
     * `@Attribute(.unique)`, so it is the primary key here — no surrogate is
     * needed, unlike [DoseEntryEntity] whose id is deliberately not unique.
     */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: UUID,

    /**
     * Denormalized first-dose timestamp, cached for cheap sorting and day-header
     * grouping. Kept in sync via `refreshDoseBounds()` whenever the earliest dose
     * changes (insert / delete / time-edit / reassign).
     */
    @ColumnInfo(name = "start_date")
    val startDate: Date,

    /**
     * Denormalized last-dose timestamp. It only ever *bounds a fetch*, so a
     * stale value can over-fetch but never misplace, and a null (a row predating
     * the field) is always fetched and self-heals.
     */
    @ColumnInfo(name = "last_dose_date")
    val lastDoseDate: Date? = null,

    /** Optional user-authored session title (e.g. "Festival Saturday"). */
    @ColumnInfo(name = "title")
    val title: String? = null,

    /**
     * The session's summary text, mirroring the `summary` [SessionNoteEntity].
     * Kept in step by the note service so every reader of this field keeps
     * working while the timeline notes are the primary record.
     */
    @ColumnInfo(name = "note")
    val note: String? = null,

    /**
     * Which check-in schedule this session runs. `null` = off, the default. A
     * positive value is an interval; the two sentinels are `0` (the fixed
     * T+30 m / 1 h / 2 h / 4 h / 6 h ladder) and `-1` (the times in
     * [checkInOffsetMinutes]).
     */
    @ColumnInfo(name = "check_in_interval_minutes")
    val checkInIntervalMinutes: Double? = null,

    /**
     * Custom check-in times in minutes after the session's latest dose, as JSON
     * — read only while [checkInIntervalMinutes] holds the custom sentinel.
     *
     * Exposed as raw JSON so the entity stays a plain row. The iOS setter runs
     * the value through `CheckInOffsets.normalized` (ascending, unique,
     * positive, capped); that belongs to the domain layer and lands with the
     * `CheckInOffsets` port, so nothing should write this column directly.
     */
    @ColumnInfo(name = "check_in_offsets_json", defaultValue = "")
    val checkInOffsetsJson: String = "",

    /** Set once the check-in offer has been shown (accepted or dismissed), so it is offered exactly once. */
    @ColumnInfo(name = "check_in_offered", defaultValue = "0")
    val checkInOffered: Boolean = false,
) {
    /** [checkInOffsetsJson] as minutes. Empty when unset or malformed. */
    val checkInOffsetMinutes: List<Int> get() = JsonLists.ints(checkInOffsetsJson)
}
