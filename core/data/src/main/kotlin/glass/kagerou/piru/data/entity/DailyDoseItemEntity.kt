package glass.kagerou.piru.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import glass.kagerou.piru.data.JsonLists
import glass.kagerou.piru.data.SubstanceIdentity
import glass.kagerou.piru.model.DoseFrequency
import glass.kagerou.piru.model.RouteOfAdministration
import java.util.Date

/**
 * A recurring medication or supplement scheduled on a fixed cadence.
 *
 * Ported from `Shared/Models/DailyDoseItem.swift`.
 *
 * ## Schedule storage
 * [frequency] is exposed as a [DoseFrequency] but persisted through
 * [frequencyRaw] so the column survives a Kotlin-level rename.
 * [frequencyDays] is exposed as a list but persisted as JSON, the same idiom as
 * every other small list in this schema.
 */
@Entity(
    tableName = "daily_dose_items",
    indices = [Index(value = ["sort_order"])],
)
data class DailyDoseItemEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id")
    val rowId: Long = 0,

    /** The substance's display name. */
    @ColumnInfo(name = "substance")
    val substance: String,

    /** Quantity of one dose in [unit]. */
    @ColumnInfo(name = "amount")
    val amount: Double,

    /** Unit of measure for [amount] (e.g. `"mg"`, `"µg"`). */
    @ColumnInfo(name = "unit", defaultValue = "mg")
    val unit: String = "mg",

    /** Route of administration, persisted by wire value. */
    @ColumnInfo(name = "route", defaultValue = "oral")
    val route: RouteOfAdministration = RouteOfAdministration.ORAL,

    /** User-defined ordering within the daily list. */
    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0,

    /**
     * Optional category label used to group items in the UI.
     *
     * Doubles as the join to a routine: items belong to a routine when this
     * equals the routine's name — string-keyed, with no relationship, matching
     * the iOS schema. Renaming a routine must cascade the new name to its items.
     */
    @ColumnInfo(name = "category", defaultValue = "")
    val category: String = "",

    /**
     * The PSID identity this item resolves to, so its "logged today" check joins a
     * matching dose by identity rather than a lowercased name — a "Concerta"
     * daily item then matches a dose logged as Methylphenidate XR, which a name
     * join never could.
     */
    @ColumnInfo(name = "substance_uid")
    val substanceUID: String? = null,

    @ColumnInfo(name = "isomer")
    val isomer: String? = null,

    @ColumnInfo(name = "release_form")
    val releaseForm: String? = null,

    @ColumnInfo(name = "salt_form")
    val saltForm: String? = null,

    /** The literal word the item was saved under. */
    @ColumnInfo(name = "product_name")
    val productName: String? = null,

    /**
     * When true, doses logged from this medication are *background*: they never
     * open or extend a recreational session (they fold into the current session
     * if one is active, otherwise form a quiet maintenance session that renders
     * as a compact "Medications" row). Stamped onto each logged dose.
     */
    @ColumnInfo(name = "is_background_med", defaultValue = "0")
    val isBackgroundMed: Boolean = false,

    /**
     * Reminder times as minutes from midnight (480 = 8:00), as JSON — zero or
     * more entries, because one med with a morning dose and an afternoon booster
     * is ONE item with two times. Empty means no reminders. Adherence expects
     * `max(1, count)` dose slots per due day.
     */
    @ColumnInfo(name = "reminder_times_json", defaultValue = "")
    val reminderTimesJson: String = "",

    /** Per-med reminder master. Only meaningful when [reminderTimesMinutes] is non-empty. */
    @ColumnInfo(name = "remind", defaultValue = "1")
    val remind: Boolean = true,

    /**
     * Per-med "ask again" override as JSON. Null means follow the global default;
     * an empty list means opted out; otherwise the re-ask cadence in minutes
     * after each reminder time.
     *
     * The null-versus-empty distinction is the whole meaning of this field, so
     * [askAgainOverrideMinutes] returns a nullable list rather than collapsing
     * both to empty.
     */
    @ColumnInfo(name = "ask_again_override_json")
    val askAgainOverrideJson: String? = null,

    /**
     * Quiet tier: collapses into one "Supplements" row in checklists, shares one
     * grouped reminder per time of day, and stays off the timeline graphs. Still
     * fully counted by adherence.
     */
    @ColumnInfo(name = "is_quiet", defaultValue = "0")
    val isQuiet: Boolean = false,

    /**
     * As-needed (PRN) schedule: no reminder times, no adherence expectation,
     * never "missed". Shown in the hub's "As needed" group.
     */
    @ColumnInfo(name = "is_as_needed", defaultValue = "0")
    val isAsNeeded: Boolean = false,

    /** Optional PRN daily cap ("up to N× daily") surfaced to the cumulative dose warning engine. */
    @ColumnInfo(name = "max_per_day")
    val maxPerDay: Int? = null,

    /** Backing storage for [frequency]. Prefer reading and writing [frequency]. */
    @ColumnInfo(name = "frequency_raw", defaultValue = "daily")
    val frequencyRaw: String = DoseFrequency.DAILY.wireValue,

    /** Weekday indices as JSON, used when [frequency] is [DoseFrequency.SPECIFIC_DAYS]. */
    @ColumnInfo(name = "frequency_days_json", defaultValue = "")
    val frequencyDaysJson: String = "",

    /** First day the schedule should be considered active. */
    @ColumnInfo(name = "start_date", defaultValue = "0")
    val startDate: Date = Date(0),
) {
    /** Daily reminder times as minutes from midnight. Empty when unset or malformed. */
    val reminderTimesMinutes: List<Int> get() = JsonLists.ints(reminderTimesJson)

    /**
     * Ask-again override: null follows the global default, an empty list is opted
     * out, otherwise the cadence in minutes.
     */
    val askAgainOverrideMinutes: List<Int>?
        get() = askAgainOverrideJson?.let { JsonLists.ints(it) }

    /** Recurrence cadence; a view over [frequencyRaw]. */
    val frequency: DoseFrequency get() = DoseFrequency.fromWire(frequencyRaw)

    /**
     * Weekday indices for [DoseFrequency.SPECIFIC_DAYS], in Foundation's
     * `Calendar` convention: `1` = Sunday … `7` = Saturday.
     */
    val frequencyDays: List<Int> get() = JsonLists.ints(frequencyDaysJson)

    /** This item's identity key — joined against a dose's identity key for the "logged today" check. */
    val identityKey: String
        get() = SubstanceIdentity.identityKey(
            substanceUID = substanceUID,
            substance = substance,
            isomer = isomer,
            releaseForm = releaseForm,
            saltForm = saltForm,
        )
}
