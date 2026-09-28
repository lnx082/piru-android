package glass.kagerou.piru.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import glass.kagerou.piru.model.RouteOfAdministration
import java.util.Date
import java.util.UUID

/**
 * A single logged dose of a substance.
 *
 * Ported from `Shared/Models/DoseEntry.swift`.
 *
 * ## Storage conventions
 * - [route] persists as its `wireValue` (`"oral"`, `"insufflation"`, …), making
 *   the column human-readable and stable across renames of the Kotlin type.
 * - [tags] is a view over [tagsRaw], a comma-separated string. Tags are
 *   CSV-encoded rather than a related table because the tag set is small,
 *   read-mostly, and the iOS schema keeps every list flat so it compiles into
 *   the widget extensions unchanged.
 *
 * ## Invariants
 * - [amount] is clamped non-negative at construction and is `0` for an
 *   [isUnknownDose] row. [isApproximate] is cleared when [isUnknownDose] is set:
 *   an approximation is a claim about a number this dose does not have.
 *
 * ## Identity
 * [id] is deliberately **not** unique. Uniqueness is app-level: a fresh value
 * per insert, plus a post-open sweep that reassigns duplicates (the iOS
 * `StoreRecovery.backfillDuplicateEntryIDs`), because a lightweight migration
 * fills one shared default into every pre-existing row. A Room unique index here
 * would reject exactly the rows that sweep exists to repair.
 */
@Entity(
    tableName = "dose_entries",
    indices = [
        // The dominant sort and filter key across the app: the journal's
        // reverse-chronological query, the tolerance replay's lookback windows,
        // both warning predicates, and session recovery.
        Index(value = ["timestamp"]),
        // Exact-match lookups for deep links, edits and dedup.
        Index(value = ["id"]),
        // Room requires an index on a foreign key's child column.
        Index(value = ["session_id"]),
        // Inventory and session assignment both bucket by resolved identity.
        Index(value = ["substance_uid"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            // Deleting a session never deletes its doses — they simply become
            // unassigned, which is the iOS `.nullify` rule.
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class DoseEntryEntity(
    /** Surrogate key; see the note on [id]. */
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id")
    val rowId: Long = 0,

    /**
     * Stable identity for cross-boundary references — route payloads, deep
     * links, notification keys, and the Piru export format. Not unique; see the
     * class note.
     */
    @ColumnInfo(name = "id")
    val id: UUID = UUID.randomUUID(),

    /** The substance's canonical (non-normalized) display name as logged. */
    @ColumnInfo(name = "substance")
    val substance: String,

    /** Quantity of the dose in [unit]. Non-negative; `0` for an unknown dose. */
    @ColumnInfo(name = "amount")
    val amount: Double,

    /** Unit of measure for [amount] (e.g. `"mg"`, `"µg"`, `"g"`, `"mL"`). */
    @ColumnInfo(name = "unit", defaultValue = "mg")
    val unit: String = "mg",

    /** Route of administration, persisted by wire value. */
    @ColumnInfo(name = "route", defaultValue = "oral")
    val route: RouteOfAdministration = RouteOfAdministration.ORAL,

    /**
     * The salt or ester form logged (Citrate, Glycinate, Carbonate…), for the
     * handful of substances that offer a choice. Null for every other dose.
     */
    @ColumnInfo(name = "salt_form")
    val saltForm: String? = null,

    /**
     * The stereoisomer form logged (D/S/L/R), for the substances that resolve to
     * a distinct enantiomer (Focalin = `D`, Esketamine = `S`). Null means
     * racemic or unspecified. Participates in the PSID form identity, so a
     * Focalin dose is a distinct recent or favorite from a methylphenidate one.
     */
    @ColumnInfo(name = "isomer")
    val isomer: String? = null,

    /**
     * The release form logged — `"XR"` (the umbrella for XR/ER/SR/CR/LA/XL),
     * `"IR"`, or `"DEP"`; null means standard or unspecified.
     *
     * Identity and label only. No source carries a distinct extended-release
     * dose or duration, so this records *which form the logged string named* —
     * recovered from a brand ("Concerta" → `"XR"`) — and never selects a dose
     * ladder or a curve.
     */
    @ColumnInfo(name = "release_form")
    val releaseForm: String? = null,

    /**
     * The PSID FAMILY (`substances.substance_uid`) this dose resolves to — the
     * stable, collision-proof substance identity, superseding the fuzzy
     * [substance] name match. Null for a dose whose name does not resolve (a
     * typo, a deletion, an exotic custom substance): such a dose stays fully
     * functional through the retained [substance] string.
     */
    @ColumnInfo(name = "substance_uid")
    val substanceUID: String? = null,

    /**
     * The name the user named this dose by, when it was not the canonical one —
     * the catalog alias their search matched ("Concerta", "Vyvanse",
     * "Adderall"), or the literal string a daily item was saved under. Null when
     * they named the substance itself, so this never asserts a product they did
     * not say. Not a facet and not a key: [substance] stays canonical and every
     * lookup keeps resolving through it.
     */
    @ColumnInfo(name = "product_name")
    val productName: String? = null,

    /**
     * The composite display title captured at resolve time, so the journal can
     * title a dose from its resolved identity without re-deriving, and the title
     * stays stable even if the catalog later relabels. Null until the dose
     * resolves to a [substanceUID].
     */
    @ColumnInfo(name = "display_name_snapshot")
    val displayNameSnapshot: String? = null,

    /** When the dose was taken. Epoch milliseconds; see [glass.kagerou.piru.data.Converters]. */
    @ColumnInfo(name = "timestamp")
    val timestamp: Date,

    /** Optional free-form note attached to the dose. */
    @ColumnInfo(name = "notes")
    val notes: String? = null,

    /** CSV backing storage for [tags]. Prefer reading and writing [tags]. */
    @ColumnInfo(name = "tags_raw")
    val tagsRaw: String? = null,

    /** The owning session, or null when unassigned. */
    @ColumnInfo(name = "session_id")
    val sessionId: UUID? = null,

    /**
     * Whether this dose was logged as a *background* medication. Background
     * doses never open or extend a recreational session — they fold into the
     * current one if active, else form a quiet maintenance session. Stamped at
     * log time from the daily item so it survives later edits to the template.
     */
    @ColumnInfo(name = "is_background_med", defaultValue = "0")
    val isBackgroundMed: Boolean = false,

    /** Optional human-readable place where the dose was taken. */
    @ColumnInfo(name = "location_name")
    val locationName: String? = null,

    /** Latitude of [locationName] in degrees, or null if no location is set. */
    @ColumnInfo(name = "latitude")
    val latitude: Double? = null,

    /** Longitude of [locationName] in degrees, or null if no location is set. */
    @ColumnInfo(name = "longitude")
    val longitude: Double? = null,

    /**
     * Whether grapefruit was taken with this dose — a per-dose CYP3A4-inhibition
     * context flag. Only ever set for grapefruit-sensitive substrates when the
     * user has enabled grapefruit logging; null or false otherwise. Tri-state on
     * purpose: null means "not asked", which is not the same as "no".
     */
    @ColumnInfo(name = "had_grapefruit")
    val hadGrapefruit: Boolean? = null,

    /**
     * Whether [amount] is the user's *estimate* rather than a measured figure —
     * "about half a tab", "a bump", a split capsule eyeballed. Renders the
     * amount with a leading `~` so a guess never reads as a precise measurement;
     * nothing else changes, because an approximate amount is still the best
     * number we have.
     */
    @ColumnInfo(name = "is_approximate", defaultValue = "0")
    val isApproximate: Boolean = false,

    /**
     * Whether the dose was taken without knowing how much — a line, a pill of
     * unknown strength, some of someone's drink. The dose is a record
     * (substance, route, time, notes, tags) with no number: [amount] is `0`,
     * the readouts print `?`, and every numeric engine leaves it out.
     */
    @ColumnInfo(name = "is_unknown_dose", defaultValue = "0")
    val isUnknownDose: Boolean = false,

    /**
     * By-volume input metadata for drinks logged by concentration × volume.
     * [amount] remains the canonical grams the PK model and ladder run on; these
     * record *how it was measured* so the dose round-trips as a drink on edit.
     */
    @ColumnInfo(name = "volume_ml")
    val volumeML: Double? = null,

    /** Strength as percent alcohol-by-volume, paired with [volumeML]. */
    @ColumnInfo(name = "abv")
    val abv: Double? = null,

    /** Optional user-given drink name (e.g. "IPA"), shown in place of the bare substance where present. */
    @ColumnInfo(name = "drink_name")
    val drinkName: String? = null,
) {
    /**
     * The amount as a journal readout: `?` for an unknown dose, else the
     * magnitude-rounded numeral. The one string every "amount unit" line prints.
     */
    val amountDisplay: String
        get() = if (isUnknownDose) "?" else glass.kagerou.piru.model.doseFormatted(amount)

    /**
     * Free-form labels attached to the dose, decoded from [tagsRaw]. Whitespace
     * around each tag is trimmed on read.
     *
     * There is no setter here: writing tags is [withTags], which keeps the CSV
     * encoding in one place instead of spreading it across every edit site.
     */
    val tags: List<String>
        get() = tagsRaw
            ?.takeIf { it.isNotEmpty() }
            ?.split(",")
            ?.map { it.trim() }
            ?: emptyList()

    /** This entry with [values] stored as its tags. An empty list clears the column. */
    fun withTags(values: List<String>): DoseEntryEntity =
        copy(tagsRaw = if (values.isEmpty()) null else values.joinToString(","))

    /**
     * This dose's substance-identity key, matching the one recents, favorites and
     * daily items group on. Used to join a dose to its curated chip and to a daily
     * item's "logged today" check by identity rather than a bare name.
     */
    val identityKey: String
        get() = glass.kagerou.piru.data.SubstanceIdentity.identityKey(
            substanceUID = substanceUID,
            substance = substance,
            isomer = isomer,
            releaseForm = releaseForm,
            saltForm = saltForm,
        )

    companion object {
        /**
         * Build an entry, applying the two invariants the iOS initializer
         * enforces: [amount] is clamped non-negative, and an unknown dose has no
         * amount and is never also marked approximate.
         */
        fun create(
            substance: String,
            amount: Double,
            unit: String = "mg",
            route: RouteOfAdministration = RouteOfAdministration.ORAL,
            timestamp: Date,
            isUnknownDose: Boolean = false,
            isApproximate: Boolean = false,
            tags: List<String> = emptyList(),
            id: UUID = UUID.randomUUID(),
        ): DoseEntryEntity = DoseEntryEntity(
            id = id,
            substance = substance,
            amount = if (isUnknownDose) 0.0 else maxOf(0.0, amount),
            unit = unit,
            route = route,
            timestamp = timestamp,
            tagsRaw = if (tags.isEmpty()) null else tags.joinToString(","),
            isApproximate = isApproximate && !isUnknownDose,
            isUnknownDose = isUnknownDose,
        )
    }
}
