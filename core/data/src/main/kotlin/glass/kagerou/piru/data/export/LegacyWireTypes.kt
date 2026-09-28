package glass.kagerou.piru.data.export

import kotlinx.serialization.Serializable

/**
 * Piru's own early backup format — import only.
 *
 * Ported from `Piru/Utilities/DataExportImport+Legacy.swift`. Nothing writes this
 * shape any more; it exists because a user who backed up with a pre-1.0 build and
 * never upgraded the file still has a file, and the only importer it has is this
 * one.
 *
 * ## Dates are ISO-8601 here, and only here
 * Every other Piru format writes epoch milliseconds. This one decoded with
 * `JSONDecoder.dateDecodingStrategy = .iso8601`, so its `timestamp` is a string —
 * [DataExportImport.readIso8601] is the one place that matters, and it is the
 * single field in the whole export layer where a `Date` is not a number.
 *
 * ## Every field but the three sections is optional on the entry
 * The Swift structs are synthesized `Decodable` with no custom `init(from:)`, so
 * `doseEntries`, `dailyDoseItems` and `substanceColors` are *required* keys, while
 * `notes`, `tags` and the location fields are optional. That split is reproduced
 * exactly: making the three arrays optional would accept a file iOS rejects,
 * which is a difference in behaviour between two apps reading the same bytes.
 */
@Serializable
data class LegacyPiruData(
    val doseEntries: List<LegacyDoseEntry>,
    val dailyDoseItems: List<LegacyDailyDoseItem>,
    val substanceColors: List<LegacySubstanceColor>,
)

@Serializable
data class LegacyDoseEntry(
    val substance: String,
    val amount: Double,
    val unit: String,
    /** A [glass.kagerou.piru.model.RouteOfAdministration.wireValue]. */
    val route: String,
    /** ISO-8601, because this format predates the millisecond convention. */
    val timestamp: String,
    val notes: String? = null,
    val tags: List<String>? = null,
    val locationName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

@Serializable
data class LegacyDailyDoseItem(
    val substance: String,
    val amount: Double,
    val unit: String,
    val route: String,
    val sortOrder: Int = 0,
)

@Serializable
data class LegacySubstanceColor(
    val substance: String,
    val hexColor: String,
)
