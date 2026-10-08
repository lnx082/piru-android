package glass.kagerou.piru.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import glass.kagerou.piru.model.DoseRange
import glass.kagerou.piru.model.DurationProfile
import glass.kagerou.piru.model.SubstanceCategory
import kotlinx.serialization.json.Json
import java.util.Date
import java.util.UUID

/**
 * A user-defined substance.
 *
 * Ported from `Shared/Models/CustomSubstanceRecord.swift`.
 *
 * Custom substances used to live in a preferences blob, which put user-authored
 * data outside the store: it shared none of the store's backup and recovery
 * lifecycle, and survived a reset that wiped everything else. The iOS model moved
 * them into the store, where they are backed up and recovered with the rest of
 * the user's data.
 *
 * ## Identity
 * [id] is **not** unique. Uniqueness is enforced by [name] in code, because
 * unique constraints have sharp migration edges — the same reasoning as
 * [DoseEntryEntity].
 *
 * ## The blobs are typed here
 * The iOS model keeps [dosesJson] and [durationJson] opaque, because that target
 * must compile into the widget extensions, which carry no dependency on the
 * domain types. That constraint does not exist on this side — `:core:data`
 * already depends on `:core:model` — so the accessors below decode them with the
 * ported serializers. That turns a class of "the blob did not parse and nobody
 * noticed" bugs into a null, and lets a caller work with a [DoseRange] directly.
 * The stored representation is unchanged: the same JSON the iOS side writes.
 */
@Entity(
    tableName = "custom_substances",
    indices = [Index(value = ["name"])],
)
data class CustomSubstanceRecordEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id")
    val rowId: Long = 0,

    /** Stable identity, preserved across edits and import round-trips. */
    @ColumnInfo(name = "id")
    val id: UUID = UUID.randomUUID(),

    /** Canonical name used for logging and lookup (matched case-insensitively). */
    @ColumnInfo(name = "name", defaultValue = "")
    val name: String = "",

    /** Optional personal display label (e.g. "THC" shown as "joint"). */
    @ColumnInfo(name = "display_name")
    val displayName: String? = null,

    /** A [SubstanceCategory] wire value. Use [category] for the resolved enum. */
    @ColumnInfo(name = "category_raw", defaultValue = "Other")
    val categoryRaw: String = SubstanceCategory.OTHER.wireValue,

    /** A [glass.kagerou.piru.model.RouteOfAdministration] wire value. */
    @ColumnInfo(name = "default_route_raw", defaultValue = "oral")
    val defaultRouteRaw: String = "oral",

    @ColumnInfo(name = "unit", defaultValue = "mg")
    val unit: String = "mg",

    @ColumnInfo(name = "notes", defaultValue = "")
    val notes: String = "",

    /** JSON-encoded [DoseRange], or null. Read it through [doses]. */
    @ColumnInfo(name = "doses_json")
    val dosesJson: String? = null,

    /** JSON-encoded [DurationProfile], or null. Read it through [duration]. */
    @ColumnInfo(name = "duration_json")
    val durationJson: String? = null,

    @ColumnInfo(name = "half_life_minutes")
    val halfLifeMinutes: Double? = null,

    @ColumnInfo(name = "created_at", defaultValue = "0")
    val createdAt: Date = Date(0),
) {
    /** The stored category, or null when the value is one this build predates. */
    val category: SubstanceCategory? get() = SubstanceCategory.fromWire(categoryRaw)

    /**
     * The entry's default route, typed.
     *
     * The counterpart of [category], and it exists for the same reason: the overlay has to know **which** route a
     * personal ladder belongs to, and reading the raw wire string at that point would spread the parsing.
     *
     * An unrecognised value becomes `OTHER` rather than failing, which is the parser's own rule — the route
     * vocabulary is open-ended and a new spelling must not drop a substance.
     */
    val defaultRoute: glass.kagerou.piru.model.RouteOfAdministration
        get() = glass.kagerou.piru.model.RouteOfAdministration.from(defaultRouteRaw)

    /** The stored dose ladder, or null when absent or unreadable. */
    val doses: DoseRange? get() = decode(dosesJson)

    /** The stored duration profile, or null when absent or unreadable. */
    val duration: DurationProfile? get() = decode(durationJson)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        /**
         * Decode a stored blob, answering null rather than throwing.
         *
         * The iOS accessors are written as `(try? …) ?? nil` for the same reason:
         * a custom substance with one unreadable field must still be usable by
         * name rather than vanishing from the list.
         */
        inline fun <reified T> decode(raw: String?): T? {
            if (raw.isNullOrEmpty()) return null
            return runCatching { json.decodeFromString<T>(raw) }.getOrNull()
        }
    }
}
