package glass.kagerou.piru.data.export

import glass.kagerou.piru.model.DoseRange
import glass.kagerou.piru.model.DurationProfile
import glass.kagerou.piru.model.P3Color
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The two on-disk shapes a Piru export can take.
 *
 * Ported from `ExportFormat` in `Piru/Utilities/DataExportImport.swift`.
 */
enum class ExportFormat {
    /** Piru-native, lossless — sessions, per-dose location, background flags. */
    PIRU,

    /**
     * PsychonautWiki's modern interchange format. Importable by PW, and lossy for
     * everything Piru carries that PW has no field for.
     */
    PSY_LOG,
}

/**
 * Piru's own export shape — a complete dump of the user's data, including the
 * things the PsyLog format cannot represent.
 *
 * Ported from `PiruFile` and its `Piru*Data` siblings in
 * `Piru/Utilities/DataExportImport+PiruNative.swift` and
 * `+PiruNativeRecords.swift`.
 *
 * ## Every section but the version decodes as absent-and-empty
 * The hand-written `init(from:)` upstream is what makes a file from a build that
 * had no favourites yet — or one trimmed by hand — still import what it does
 * carry. A `@Serializable` class with a default of `emptyList()` / `null` on each
 * property reproduces that exactly, and only `piruExportVersion` is required.
 *
 * ## `null` is omitted, not written
 * The iOS types are synthesized `Codable`, whose `encodeIfPresent` skips a `nil`
 * optional rather than writing `null`. [DataExportImport.nativeJson] therefore
 * sets `explicitNulls = false`, which is the same rule. The one place the iOS
 * format *does* write `null` is inside the settings map, which is not modelled
 * here — see [PiruFile.settings].
 *
 * ## Sections with no Android table
 * `labMeasurements`, `customUnits` and `drinkPresets` decode into raw
 * [JsonElement]s rather than into models, because this build has no table for
 * them. Keeping them as elements means an import can *count* what it could not
 * store — and say so — instead of silently discarding it, and means this build
 * never writes a false `[]` claiming the user has none. See
 * [DataExportImport.ImportReport].
 */
@Serializable
data class PiruFile(
    val piruExportVersion: Int,
    val appVersion: String = "?",
    val exportedAt: Long = 0L,
    val sessions: List<PiruSessionData> = emptyList(),
    /** Doses not assigned to any session. Normally empty; kept for a defensive import. */
    val orphanDoses: List<PiruDoseData> = emptyList(),
    val dailyDoseItems: List<PiruDailyDoseData> = emptyList(),
    val substanceColors: List<PiruColorData> = emptyList(),
    val favorites: List<PiruFavoriteData> = emptyList(),
    val customSubstances: List<PiruCustomSubstanceData> = emptyList(),
    val inventory: List<PiruInventoryData>? = null,
    /** No Android table. Decoded, counted and reported, never written. */
    val labMeasurements: List<JsonElement>? = null,
    /** No Android table. See [labMeasurements]. */
    val customUnits: List<JsonElement>? = null,
    /** No Android table. See [labMeasurements]. */
    val drinkPresets: List<JsonElement>? = null,
    val quickLogDoses: List<PiruQuickLogDoseData>? = null,
    val routineOccurrences: List<PiruRoutineOccurrenceData>? = null,
    val profile: PiruProfileData? = null,
    val notificationPreferences: PiruNotificationPreferencesData? = null,
    /**
     * App preferences that live outside the store — skins, dock, tab layout,
     * journal grouping and the like.
     *
     * Deliberately an opaque [JsonElement]: none of the keys upstream exports
     * exist in this build (there are no skins, no dock and no tab layout here),
     * so there is nothing to read them into. Keeping the element means a file
     * carrying the section still imports rather than failing on an unknown shape.
     */
    val settings: JsonElement? = null,
)

@Serializable
data class PiruSessionData(
    /** A UUID. Written as `id`; see [WireUuid]. */
    val id: String,
    val startDate: Long,
    val title: String? = null,
    val note: String? = null,
    val doses: List<PiruDoseData> = emptyList(),
    val notes: List<PiruSessionNoteData>? = null,
    val checkInIntervalMinutes: Double? = null,
    val checkInOffsetMinutes: List<Int>? = null,
    val checkInOffered: Boolean? = null,
)

/**
 * One timestamped note inside a session.
 *
 * `kind` is the raw `SessionNote.Kind` and `descriptors` are SubFxOnEx concept
 * ids exactly as stored — both stay strings on the wire so a vocabulary this
 * build does not know round-trips instead of collapsing.
 */
@Serializable
data class PiruSessionNoteData(
    val id: String,
    val timestamp: Long,
    val text: String = "",
    val shulgin: Int? = null,
    val mood: Int? = null,
    val energy: Int? = null,
    val social: Int? = null,
    val worked: Int? = null,
    val descriptors: List<String> = emptyList(),
    val heartRate: Double? = null,
    val kind: String = "observation",
)

@Serializable
data class PiruDoseData(
    val id: String? = null,
    val substance: String,
    val amount: Double,
    val unit: String,
    /** A [glass.kagerou.piru.model.RouteOfAdministration.wireValue]. */
    val route: String,
    val saltForm: String? = null,
    val substanceUID: String? = null,
    val isomer: String? = null,
    val releaseForm: String? = null,
    val productName: String? = null,
    val displayNameSnapshot: String? = null,
    val timestamp: Long,
    val notes: String? = null,
    val tags: List<String> = emptyList(),
    val isBackgroundMed: Boolean = false,
    val locationName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isApproximate: Boolean? = null,
    val isUnknownDose: Boolean? = null,
    val hadGrapefruit: Boolean? = null,
    val volumeML: Double? = null,
    val abv: Double? = null,
    val drinkName: String? = null,
)

@Serializable
data class PiruDailyDoseData(
    val substance: String,
    val amount: Double,
    val unit: String,
    val route: String,
    val sortOrder: Int = 0,
    val category: String = "",
    val isBackgroundMed: Boolean = false,
    val frequencyRaw: String = "daily",
    val frequencyDays: List<Int> = emptyList(),
    val startDate: Long = 0L,
    val substanceUID: String? = null,
    val isomer: String? = null,
    val releaseForm: String? = null,
    val saltForm: String? = null,
    val productName: String? = null,
    val reminderTimesMinutes: List<Int>? = null,
    val remind: Boolean? = null,
    /** `null` follows the global Ask Again default; `[]` opts out. */
    val askAgainOverrideMinutes: List<Int>? = null,
    val isQuiet: Boolean? = null,
    val isAsNeeded: Boolean? = null,
    val maxPerDay: Int? = null,
)

/**
 * One picked colour.
 *
 * Format 2 writes `p3`; format 1 files carry `hexColor`, an sRGB hex, for every
 * substance the exporting device had met. Both are read [PiruColorData.tint].
 */
@Serializable
data class PiruColorData(
    val substance: String,
    val p3: P3Color? = null,
    val hexColor: String? = null,
) {
    /** The colour this row names, in whichever of the two forms the file used. */
    val tint: P3Color?
        get() = p3 ?: hexColor?.let(glass.kagerou.piru.model.LegacyColorImport::p3)
}

@Serializable
data class PiruFavoriteData(
    val substance: String,
    val createdAt: Long = 0L,
    val sortOrder: Int? = null,
    val substanceUID: String? = null,
    val isomer: String? = null,
    val releaseForm: String? = null,
    val saltForm: String? = null,
    val productName: String? = null,
)

/**
 * Everything needed to reconstruct an inventory item's stock.
 *
 * `currentQuantity` and `lowStockNotified` are deliberately absent — both are
 * derived and rebuilt by the replay after import. `trackingStart` is preserved so
 * the dose-consumption window matches the source device exactly.
 */
@Serializable
data class PiruInventoryData(
    val id: String? = null,
    val sortOrder: Int? = null,
    val substance: String,
    val saltForm: String? = null,
    val unit: String,
    val trackingStart: Long,
    val lowStockThreshold: Double? = null,
    val baselineQuantity: Double? = null,
    val doseSize: Double? = null,
    val unitStrengthMG: Double? = null,
    val createdAt: Long = 0L,
    val manualEvents: List<PiruManualEventData> = emptyList(),
)

@Serializable
data class PiruManualEventData(
    val id: String,
    /** A [glass.kagerou.piru.data.ManualEvent.Kind.wireValue]. */
    val kind: String,
    val amount: Double,
    val date: Long,
    val note: String? = null,
    val setsBaseline: Boolean = false,
)

/** Piru-native wire shape for a user-defined substance. Mirrors the store row field for field. */
@Serializable
data class PiruCustomSubstanceData(
    val id: String,
    val name: String,
    /** A [glass.kagerou.piru.model.SubstanceCategory.wireValue]. */
    val category: String = "other",
    /** A [glass.kagerou.piru.model.RouteOfAdministration.wireValue]. */
    val defaultRoute: String = "oral",
    val unit: String = "mg",
    val notes: String = "",
    val duration: DurationProfile? = null,
    val createdAt: Long = 0L,
    val displayName: String? = null,
    val doses: DoseRange? = null,
    val halfLifeMinutes: Double? = null,
)

/** One curated quick-log chip. */
@Serializable
data class PiruQuickLogDoseData(
    val substance: String,
    val route: String,
    val amount: Double,
    val unit: String,
    val sortOrder: Double = 0.0,
    val lastUsedAt: Long = 0L,
    val volumeML: Double? = null,
    val abv: Double? = null,
    val drinkName: String? = null,
    val emoji: String? = null,
    val substanceUID: String? = null,
    val isomer: String? = null,
    val releaseForm: String? = null,
    val saltForm: String? = null,
    val productName: String? = null,
)

/**
 * One med-slot occurrence.
 *
 * Past days are history the reconcile never re-derives — a Skip is a user choice,
 * a miss is a record — so every row travels.
 */
@Serializable
data class PiruRoutineOccurrenceData(
    val routineName: String,
    val substance: String,
    val substanceUID: String? = null,
    val route: String = "oral",
    val dueDay: Long,
    val state: String = "pending",
    val satisfyingEntryID: String? = null,
    val slotMinutes: Int? = null,
)

/** The profile singleton. */
@Serializable
data class PiruProfileData(
    val disclosureTier: String = "harm-reduction",
    val bodyWeightKg: Double? = null,
    val weightSource: String = "estimated",
    val grapefruitLoggingEnabled: Boolean = false,
    val aldh2Deficient: Boolean = false,
)

/** The notification-preferences singleton. */
@Serializable
data class PiruNotificationPreferencesData(
    val masterEnabled: Boolean = true,
    val hydrationEnabled: Boolean = false,
    val sleepEnabled: Boolean = false,
    val phaseEnabled: Boolean = false,
    val cumulativeEnabled: Boolean = false,
    val routineEnabled: Boolean = true,
    val routineFollowUpEnabled: Boolean = true,
    val inventoryEnabled: Boolean = true,
    val checkInEnabled: Boolean = true,
    val quietHoursEnabled: Boolean = false,
    val quietHoursStartMinutes: Int = 23 * 60,
    val quietHoursEndMinutes: Int = 7 * 60,
    val routineTimeSensitive: Boolean = true,
    val routineFollowUpTimeSensitive: Boolean = true,
    val cumulativeTimeSensitive: Boolean = true,
    /**
     * `null` when the cadence was never edited, so the importing install keeps
     * reading the built-in default rather than a frozen copy of it.
     */
    val askAgainDefaultMinutes: List<Int>? = null,
)

/**
 * The UUID spelling every Piru file uses: **uppercase**, hyphenated.
 *
 * `java.util.UUID.toString()` is lowercase, so this is not cosmetic — a lowercase
 * id would be a different byte string from the one the iOS side wrote, and the
 * whole point of the format is that the two spell it the same way. The restock
 * blob in [glass.kagerou.piru.data.ManualEvents] made the same choice for the
 * same reason.
 *
 * Parsing is case-insensitive on both platforms, so an id from a file written by
 * something else still resolves.
 */
internal object WireUuid {
    fun write(value: java.util.UUID): String = value.toString().uppercase(java.util.Locale.ROOT)

    fun read(value: String?): java.util.UUID? =
        value?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
}
