package glass.kagerou.piru.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import glass.kagerou.piru.model.RouteOfAdministration
import java.util.Date
import java.util.UUID

/**
 * The durable record of "was routine item X due on day Y, and what happened to
 * it" — one row per (routine, item, day-slot) where the item was due.
 *
 * Ported from `Shared/Models/RoutineOccurrence.swift`.
 *
 * Written only by the routine-occurrence service: a reconcile pass re-derives
 * the day's states, and a Skip Today action records the user's choice.
 * Follow-up cancellation and any "did I take it" surface read this record rather
 * than re-inferring from raw dose scans.
 *
 * The item is referenced by an identity snapshot ([substance] / [substanceUID] /
 * [routeRaw]), not by a foreign key — a daily item has no stable id field, and
 * adding one repeats the `DoseEntry.id` migration trap. The iOS model carries no
 * index at all; the one added here is on the day column, because every read is
 * "the occurrences for this day".
 */
@Entity(
    tableName = "routine_occurrences",
    indices = [Index(value = ["due_day"])],
)
data class RoutineOccurrenceEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id")
    val rowId: Long = 0,

    /** The owning routine, string-joined like a daily item's category. */
    @ColumnInfo(name = "routine_name", defaultValue = "")
    val routineName: String = "",

    /** The item's substance name at materialization time. */
    @ColumnInfo(name = "substance", defaultValue = "")
    val substance: String = "",

    /** The item's PSID identity when resolved — the preferred join key, so a relabeled dose still matches. */
    @ColumnInfo(name = "substance_uid")
    val substanceUID: String? = null,

    /**
     * The item's route, by wire value. A dose must match it to satisfy the
     * occurrence, so this is part of the identity and not a display detail.
     */
    @ColumnInfo(name = "route_raw", defaultValue = "oral")
    val routeRaw: String = RouteOfAdministration.ORAL.wireValue,

    /** `startOfDay` of the due date. */
    @ColumnInfo(name = "due_day", defaultValue = "0")
    val dueDay: Date = Date(0),

    /** Backing storage for [state]. Prefer reading and writing [state]. */
    @ColumnInfo(name = "state_raw", defaultValue = "pending")
    val stateRaw: String = State.PENDING.wireValue,

    /** The dose id that satisfied this occurrence, when it was logged. */
    @ColumnInfo(name = "satisfying_entry_id")
    val satisfyingEntryID: UUID? = null,

    /**
     * The med's reminder time this occurrence tracks, as minutes from midnight —
     * occurrences are keyed per (med × time slot), so an 8:00 + 13:00 med has two
     * rows per day. Null is the single "anytime" slot of a med with no set times,
     * and every pre-redesign legacy row.
     */
    @ColumnInfo(name = "slot_minutes")
    val slotMinutes: Int? = null,
) {
    /** The stored state; an unrecognized value reads as [State.PENDING]. */
    val state: State get() = State.fromWire(stateRaw)

    /** The stored route; an unrecognized value reads as oral. */
    val route: RouteOfAdministration
        get() = RouteOfAdministration.entries.firstOrNull { it.wireValue == routeRaw }
            ?: RouteOfAdministration.ORAL

    /**
     * What happened to the due item.
     *
     * [MISSED] is neutral end-of-day history, never a delivered reprimand.
     * [SKIPPED] is a user choice, and it is sticky — a reconcile pass must not
     * overwrite it back to pending.
     */
    enum class State(val wireValue: String) {
        PENDING("pending"),
        LOGGED("logged"),
        SKIPPED("skipped"),
        MISSED("missed"),
        ;

        companion object {
            fun fromWire(value: String): State =
                entries.firstOrNull { it.wireValue == value } ?: PENDING
        }
    }
}
