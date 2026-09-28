package glass.kagerou.piru.data.export

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * PsychonautWiki's modern interchange format.
 *
 * Ported from `Piru/Utilities/DataExportImport+PsyLog.swift`.
 *
 * ## Read leniently, written by hand
 * The upstream types have hand-written `init(from:)`s that swallow every optional
 * difference PW and Piru have introduced over the years, and hand-written
 * `encode(to:)`s that write the *exact* modern PW shape and nothing else. Both
 * halves are reproduced: the `@Serializable` classes below are decode-only, and
 * [DataExportImport] builds the encoded objects key by key, which is what the
 * Swift `encode(to:)` does too.
 *
 * That split is why there is no `PsyLogIngestion.location` on the way out. It is
 * a Piru extension PW never wrote, present only in older Piru exports, and
 * upstream decodes it while deliberately not emitting it — so a hand-built object
 * is the only honest way to keep the two behaviours apart.
 *
 * ## The `try?` fields
 * Two fields upstream decodes with `try?` — a malformed value becomes "none"
 * rather than failing the file, because a real PsychonautWiki file writes
 * `customSubstances: []` as a *string* array placeholder and Piru wrote
 * `timedNotes` the same way. They decode as raw [JsonElement]s here so the
 * importer can attempt the real shape and fall back exactly as `try?` does.
 */
@Serializable
data class PsyLogFile(
    val experiences: List<PsyLogExperience>,
    val substanceCompanions: List<PsyLogCompanion> = emptyList(),
    val customUnits: List<PsyLogCustomUnit> = emptyList(),
    /** Attempted as [PiruCustomSubstanceData]s; anything else reads as none. */
    val customSubstances: List<JsonElement> = emptyList(),
    val dailyDoseItems: List<PsyLogDailyDoseItem> = emptyList(),
) {
    companion object {
        /**
         * Stamped on export so PsychonautWiki recognizes the file as the modern
         * format. PW's importer rejects a file with no `exportSource` as "legacy".
         * Verified upstream against the current PW Journal app; copied verbatim
         * because it is a string PW matches on.
         */
        const val EXPORT_SOURCE_VALUE = "iOS Journal 15.0"
    }
}

/**
 * A place attached to an experience — and, as a Piru extension, to an individual
 * ingestion. Matches PW's `{name, latitude, longitude}`, and the coordinates are
 * optional because PW's own users can name a place without attaching one.
 */
@Serializable
data class PsyLogLocation(
    val name: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/**
 * PW's timed note.
 *
 * `color` is one of PW's named substance colours and `isPartOfTimeline` whether
 * PW draws it on its graph. Piru writes `blue` and `true` and reads neither back.
 */
@Serializable
data class PsyLogTimedNote(
    val creationDate: Long,
    val time: Long,
    val note: String,
    val color: String = "blue",
    val isPartOfTimeline: Boolean = true,
)

@Serializable
data class PsyLogExperience(
    val title: String = "",
    val isFavorite: Boolean = false,
    val text: String = "",
    val location: PsyLogLocation? = null,
    val ingestions: List<PsyLogIngestion> = emptyList(),
    /**
     * Kept raw so the importer can apply upstream's `try?` — an array that is not
     * PW's object shape reads as *no* notes rather than failing the import.
     */
    val timedNotes: List<JsonElement> = emptyList(),
    /**
     * Both dates are optional here and resolved by the importer, because PW writes
     * `sortDate: null` for an experience that never had one and its own importer —
     * and therefore this one — falls back to the earliest ingestion.
     */
    val creationDate: Long? = null,
    val sortDate: Long? = null,
)

@Serializable
data class PsyLogIngestion(
    val substanceName: String? = null,
    val customUnitId: Int? = null,
    val dose: Double? = null,
    /** Required: upstream decodes it with a throwing `decode`, not `decodeIfPresent`. */
    val time: Long,
    val administrationRoute: String = "ORAL",
    val notes: String = "",
    val units: String = "mg",
    /** A Piru extension, present only in older Piru exports. Decoded, never written. */
    val location: PsyLogLocation? = null,
)

@Serializable
data class PsyLogCompanion(
    val color: String,
    val substanceName: String,
)

/** The subset of a daily-dose item PW understands: no schedule, no reminders. */
@Serializable
data class PsyLogDailyDoseItem(
    val substance: String,
    val amount: Double,
    val unit: String,
    val route: String = "ORAL",
    val sortOrder: Int = 0,
)

/**
 * A PW custom unit.
 *
 * Only the four fields upstream reads are modelled; PW's `doseComponents`,
 * `roaInfos` and the rest are ignored on decode, which is what `CodingKeys` with
 * no matching property does there too.
 */
@Serializable
data class PsyLogCustomUnit(
    val id: Int,
    val name: String,
    val unit: String = "mg",
    val color: String? = null,
)
