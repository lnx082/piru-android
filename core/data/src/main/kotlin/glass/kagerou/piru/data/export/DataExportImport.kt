package glass.kagerou.piru.data.export

import glass.kagerou.piru.data.PiruDatabase
import glass.kagerou.piru.data.UserProfileStore
import glass.kagerou.piru.data.entity.CustomSubstanceRecordEntity
import glass.kagerou.piru.data.entity.DailyDoseItemEntity
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.data.entity.FavoriteSubstanceEntity
import glass.kagerou.piru.data.entity.InventoryItemEntity
import glass.kagerou.piru.data.entity.QuickLogDoseEntity
import glass.kagerou.piru.data.entity.RoutineOccurrenceEntity
import glass.kagerou.piru.data.entity.SessionEntity
import glass.kagerou.piru.data.entity.SessionNoteEntity
import glass.kagerou.piru.data.entity.SubstanceColorEntity
import glass.kagerou.piru.engine.SessionDay
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.model.LegacyColorImport
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.model.RouteOfAdministration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Every user-data entity, out to a file and back.
 *
 * Ported from `Piru/Utilities/DataExportImport.swift` and its four `+…`
 * extensions. This is the **only** bridge between the iOS app and this one — a
 * user moving in either direction carries their whole log through the format
 * defined here — so the field names, the date convention, the absent-versus-null
 * behaviour and the dedup key are all copied from the Swift rather than chosen.
 *
 * ## The three shapes a file can be
 * [classify] decides from the **top-level keys alone**, exactly as upstream does,
 * so a file that no importer can take fails with "this isn't a Piru export"
 * rather than with a decoder complaint about field eleven:
 *
 * - `piruExportVersion` — Piru-native (this build and the iOS build both write
 *   version [PIRU_EXPORT_VERSION]; a *newer* number is refused by name).
 * - `experiences` — PsychonautWiki's format, both its modern shape (with
 *   `exportSource`) and its older one.
 * - `doseEntries` — Piru's own pre-1.0 backup.
 * - `sealed` + `kind` — an encrypted backup. Named and refused here; only the
 *   restore flow, which asks for a passphrase, can open it.
 *
 * ## Leniency is one-sided, and where it differs it is deliberate
 * Every reader here is at least as forgiving as the Swift one, and in three
 * places more so. Each is a case where throwing would lose a dose:
 *
 * - An unknown `route` string reads as [RouteOfAdministration.OTHER]. The Swift
 *   synthesized `Codable` throws, which fails the whole file.
 * - A `null` where Swift expects an array reads as the empty list.
 * - The restock blob's `kind` already had this rule (see
 *   [glass.kagerou.piru.data.ManualEvents]); it is extended to the export.
 *
 * ## What this build cannot carry, and says so
 * `labMeasurements`, `customUnits`, `drinkPresets` and `settings` have no table
 * on Android. An import **counts** what it dropped and returns it in
 * [ImportReport.unsupported] so the screen can say it out loud, and an export
 * **omits** the sections rather than writing a false `[]` that would claim the
 * user has none. Neither direction is silent.
 */
object DataExportImport {

    /**
     * The Piru-native format this build writes and the newest it reads.
     *
     * An additive change keeps the number; only a file an older build would
     * misread bumps it. See the Swift constant's own note.
     */
    const val PIRU_EXPORT_VERSION: Int = 2

    /**
     * The JSON the wire types are read and written with.
     *
     * `explicitNulls = false` is the load-bearing setting: the iOS native types
     * are synthesized `Codable`, whose `encodeIfPresent` **omits** a `nil`
     * optional rather than writing `null`, and this is the same rule. The PsyLog
     * shape is different — it writes explicit `null`s in half a dozen places —
     * and is therefore built by hand key by key in [makePsyLogFile] rather than
     * by configuration.
     *
     * `encodeDefaults = true` because Swift writes every non-optional property
     * whatever its value.
     */
    internal val wireJson: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    /** Which importer owns a file. */
    sealed interface FileShape {
        /** Piru-native, with the app version that wrote it when it named one. */
        data class PiruNative(val appVersion: String?) : FileShape

        /** PsychonautWiki's format. */
        data object PsyLog : FileShape

        /** Piru's own early `doseEntries` backup. */
        data object Legacy : FileShape
    }

    /** Why a file could not be handed to any importer. */
    sealed class ImportFileException(message: String) : Exception(message) {
        /** Zero bytes: the save never wrote the content. */
        class Empty : ImportFileException("the file is empty")

        class NotJson : ImportFileException("the file is not valid JSON")

        /** Valid JSON, but none of the shapes Piru reads. */
        class Unrecognized : ImportFileException("no Piru importer owns this file")

        /** An encrypted backup, which only the restore flow can open. */
        class Encrypted : ImportFileException("the file is an encrypted backup")

        /** A native file in a format newer than [PIRU_EXPORT_VERSION]. */
        class NewerFormat(val version: Int, val appVersion: String?) :
            ImportFileException("this build reads format $PIRU_EXPORT_VERSION, the file is format $version")

        /** A native file the decoder rejected, with the app that wrote it. */
        class MalformedNative(val appVersion: String?, val detail: String) : ImportFileException(detail)
    }

    /** A section the file carried that this build has no table for. */
    data class UnsupportedSection(val name: String, val rows: Int)

    /**
     * What an import actually did.
     *
     * The counts are the rows *added*, not the rows read: a merge re-importing
     * the same file adds nothing, and a restore that says "1,204 entries" when
     * it added none is a lie the user cannot check.
     */
    data class ImportReport(
        val shape: FileShape,
        val entriesAdded: Int = 0,
        val sessionsAdded: Int = 0,
        val medsAdded: Int = 0,
        val favoritesAdded: Int = 0,
        val unsupported: List<UnsupportedSection> = emptyList(),
    )

    // MARK: - Naming and identity

    /**
     * The export file's name, without an extension.
     *
     * Upstream's `exportFilename`: the device's **own** zone, because this is a
     * name the user reads in a file picker and it should say when they made it.
     * The pattern is `yyyy-MM-dd'T'HHmmss` and the locale is pinned to
     * [Locale.ROOT] by the port's rule — a locale-sensitive formatter would put
     * the device's own digits in the name.
     */
    fun exportFilename(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss", Locale.ROOT)
            .withZone(zone)
            .format(now)
        return "Piru $stamp"
    }

    /**
     * Content-based identity for a dose, used to skip duplicates on import.
     *
     * Lowercased substance and unit so cosmetic casing differences do not defeat
     * it; the millisecond timestamp matches the export precision. Copied from
     * upstream's `doseDedupKey`, including the order of the five parts — the key
     * never leaves this process, but a reordering would be invisible and would
     * change which of two same-second doses survives a merge.
     */
    fun doseDedupKey(
        substance: String,
        timestamp: Instant,
        amount: Double,
        unit: String,
        route: RouteOfAdministration,
    ): String = "${substance.lowercase(Locale.ROOT)}|${timestamp.toEpochMilli()}|$amount|" +
        "${unit.lowercase(Locale.ROOT)}|${route.wireValue}"

    // MARK: - Reading without importing

    /**
     * Read just enough of the file to name its importer.
     *
     * Throws an [ImportFileException] for anything no importer could make sense
     * of, so the message says what the file *is* rather than which field a
     * decoder happened to trip on.
     */
    fun classify(text: String): FileShape {
        if (text.isBlank()) throw ImportFileException.Empty()
        val root = FoundationJSON.parse(text) ?: throw ImportFileException.NotJson()
        val obj = root as? JsonObject ?: throw ImportFileException.Unrecognized()

        obj["piruExportVersion"]?.let { versionElement ->
            // A version that is present but not an integer is a file claiming to
            // be a Piru export without saying which one — the same "no importer
            // owns this" answer as an unknown top-level shape, not a decode error.
            val version = (versionElement as? JsonPrimitive)
                ?.takeIf { !it.isString }
                ?.content?.toIntOrNull()
                ?: throw ImportFileException.Unrecognized()
            val appVersion = (obj["appVersion"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
            if (version > PIRU_EXPORT_VERSION) {
                throw ImportFileException.NewerFormat(version, appVersion)
            }
            return FileShape.PiruNative(appVersion)
        }
        if (obj.containsKey("experiences")) return FileShape.PsyLog
        if (obj.containsKey("doseEntries")) return FileShape.Legacy
        if (obj.containsKey("sealed") && obj.containsKey("kind")) throw ImportFileException.Encrypted()
        throw ImportFileException.Unrecognized()
    }

    /**
     * Classify and fully decode the file without touching any store, throwing
     * what [importJSON] would.
     *
     * A destructive restore runs this **before** wiping: a file that cannot
     * import must never empty the journal on its way to failing.
     */
    fun validate(text: String) {
        when (val shape = classify(text)) {
            is FileShape.PiruNative -> {
                try {
                    wireJson.decodeFromString(PiruFile.serializer(), text)
                } catch (error: Exception) {
                    throw ImportFileException.MalformedNative(shape.appVersion, decodeDetail(error))
                }
            }
            // A PsyLog decode failure is surfaced as-is: upstream lets
            // `DecodingError` through here too, and the message already names the
            // path.
            FileShape.PsyLog -> wireJson.decodeFromString(PsyLogFile.serializer(), text)
            FileShape.Legacy -> LegacyImport.decode(text)
        }
    }

    // MARK: - User-facing messages

    /**
     * A message for a failed import.
     *
     * The stock `DecodingError` description names neither the field nor the file
     * shape, which upstream records as having made real user reports
     * undiagnosable — hence the per-case copy below. The same reasoning applies
     * here, with kotlinx's "at path: $.sessions[2].startDate" lifted out of its
     * message and into the sentence.
     */
    fun importErrorMessage(error: Throwable): String = when (error) {
        is ImportFileException.Empty ->
            "The file is empty. Nothing was saved into it, so export again and wait for the save to finish before importing."
        is ImportFileException.NotJson -> "The file isn't valid JSON."
        is ImportFileException.Unrecognized -> "This file isn't a Piru export or a PsychonautWiki journal."
        is ImportFileException.Encrypted ->
            "This is an encrypted Piru backup. Import it from Data & Backup, which asks for its passphrase."
        is ImportFileException.NewerFormat -> writtenBy(
            error.appVersion,
            "This file uses export format ${error.version}, which this version of Piru can't read yet. " +
                "Update Piru, then import it.",
        )
        is ImportFileException.MalformedNative -> writtenBy(error.appVersion, error.detail)
        else -> error.message ?: error.toString()
    }

    /** Appends the writing app when the file named one, so a report says which build produced it. */
    private fun writtenBy(appVersion: String?, message: String): String =
        if (appVersion.isNullOrEmpty()) message else "$message The file was written by $appVersion."

    /**
     * kotlinx's decode failures already carry the field path — `at path:
     * $.experiences[2].sortDate` — so it is lifted out rather than reproduced.
     */
    private fun decodeDetail(error: Exception): String {
        val message = error.message ?: return error.toString()
        val path = PATH_IN_MESSAGE.find(message)?.groupValues?.get(1)
        return if (path == null) {
            "The file has a field this version can't read. $message"
        } else {
            "The file has an unexpected value at: ${path.removePrefix("$.")}."
        }
    }

    private val PATH_IN_MESSAGE = Regex("at path: (\\S+)")

    // MARK: - Export

    /**
     * Build the export text.
     *
     * @param appVersion what to write into `PiruFile.appVersion`. Upstream reads
     *   it from the bundle; this module has no `BuildConfig`, so the app passes
     *   `"Piru ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"`.
     * @param catalog only used by the PsychonautWiki export, which needs no
     *   catalog at all — kept off the signature entirely; see [makePsyLogFile].
     */
    suspend fun exportJSON(
        format: ExportFormat,
        db: PiruDatabase,
        appVersion: String,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        dayBoundaryHour: Int = SessionDay.DEFAULT_BOUNDARY_HOUR,
    ): String = when (format) {
        ExportFormat.PIRU -> FoundationJSON.write(
            wireJson.encodeToJsonElement(PiruFile.serializer(), makePiruFile(db, appVersion, now)),
        )
        ExportFormat.PSY_LOG -> FoundationJSON.write(makePsyLogFile(db, zone, dayBoundaryHour))
    }

    // MARK: - Import

    /**
     * Route the file to the importer [classify] names.
     *
     * A native file the decoder rejects surfaces as
     * [ImportFileException.MalformedNative] so the message can say which app wrote
     * it.
     *
     * ## There is no merge-or-replace flag here, and that is deliberate
     * Upstream threads an `ImportMode` through for exactly one reason: its
     * settings section applies differently under each. This build carries no
     * settings section (see the class note), so the only difference between a
     * merge and a replace is whether the **caller** emptied the store first — a
     * replace is a wipe followed by this call. Every store row merges, and a merge
     * into a store that was just emptied restores it exactly. A flag here would
     * be a parameter nothing reads.
     *
     * ## What the caller still owes
     * Upstream ends by clustering session-less doses
     * (`SessionService.assignUnassignedDoses`) and re-resolving PSID identity.
     * This build's cluster pass is
     * `SessionRepository.ensureSessionsPopulated`, which the app already runs at
     * launch and on the write path; it is not called from here because
     * `:core:data`'s repositories are built by the app, not by this object. A
     * caller that wants imported doses grouped immediately should run it.
     *
     * @param catalog consulted for the inventory replay after an import, so the
     *   cached quantity on each supply is right rather than stale, and for the
     *   colour rows a PsychonautWiki import seeds. Null is accepted and means
     *   both are skipped rather than guessed at, which keeps this callable from a
     *   test with no bundled database.
     */
    suspend fun importJSON(
        text: String,
        db: PiruDatabase,
        catalog: SubstanceCatalog? = null,
    ): ImportReport {
        val shape = classify(text)
        return when (shape) {
            is FileShape.PiruNative -> {
                val file = try {
                    wireJson.decodeFromString(PiruFile.serializer(), text)
                } catch (error: Exception) {
                    throw ImportFileException.MalformedNative(shape.appVersion, decodeDetail(error))
                }
                NativeImport.run(file, db, catalog)
            }
            FileShape.PsyLog -> PsyLogImport.run(
                wireJson.decodeFromString(PsyLogFile.serializer(), text),
                db,
                catalog,
            )
            FileShape.Legacy -> LegacyImport.run(LegacyImport.decode(text), db)
        }
    }

    // MARK: - Delete

    /**
     * Remove every user-data row and commit.
     *
     * Upstream's `PiruSchema.deleteAll` walks all seventeen models. This build has
     * fourteen tables and this deletes all of them — the three that differ are the
     * three with no table here (see the class note), not a shorter list by
     * oversight.
     *
     * The per-table order the *screen* uses before calling this is a separate,
     * load-bearing thing; see `DataStorageScreen`.
     */
    suspend fun deleteAll(db: PiruDatabase) {
        db.doseEntryDao().deleteAll()
        db.sessionDao().deleteAll()
        db.sessionNoteDao().deleteAll()
        db.substanceColorDao().deleteAll()
        db.toleranceStateDao().deleteAll()
        db.userProfileDao().deleteAll()
        db.favoriteSubstanceDao().deleteAll()
        db.dailyDoseItemDao().deleteAll()
        db.routineOccurrenceDao().deleteAll()
        db.notificationPreferencesDao().deleteAll()
        db.customSubstanceDao().deleteAll()
        db.quickLogDoseDao().deleteAll()
        db.inventoryDao().deleteAll()
        db.labMeasurementDao().deleteAll()
    }

    /**
     * Re-read what a bulk import can leave stale in memory.
     *
     * Upstream's `refreshLiveStores` reconfigures ten singletons — the profile,
     * the notification choices, custom units, the skin, the dock, the tab layout
     * and the search history — then re-syncs med reminders and re-warms the
     * substance catalog. This module can reach exactly one of them:
     * [UserProfileStore], which lives here.
     *
     * The rest is **not** a silent omission. Upstream's live stores do not exist
     * in this build at all (there are no skins, no dock, no tab layout and no
     * search history), so the only real counterparts are the notification
     * preferences and the catalog, both of which belong to the app module and are
     * refreshed by `PiruApplication.refreshLiveStores`. Call this first, then
     * that.
     */
    suspend fun refreshLiveStores(db: PiruDatabase) {
        UserProfileStore(db).load()
    }

    // MARK: - The Piru-native file

    private suspend fun makePiruFile(
        db: PiruDatabase,
        appVersion: String,
        now: Instant,
    ): PiruFile {
        val entries = db.doseEntryDao().all()
        val sessions = db.sessionDao().all()
        val notes = db.sessionNoteDao().all()

        // Grouping by session id rather than by walking each session's own query
        // keeps this to three reads for the whole journal, which is what makes a
        // large export feasible on a phone.
        val dosesBySession = entries.filter { it.sessionId != null }.groupBy { it.sessionId!! }
        val notesBySession = notes.filter { it.sessionId != null }.groupBy { it.sessionId!! }

        val sessionData = sessions
            .sortedBy { it.startDate.time }
            .map { session ->
                PiruSessionData(
                    id = WireUuid.write(session.id),
                    startDate = session.startDate.time,
                    title = session.title,
                    note = session.note,
                    doses = (dosesBySession[session.id] ?: emptyList()).sortedWith(DOSE_ORDER).map(::doseData),
                    // Written even when empty: upstream always emits the array, so
                    // a file with `"notes": []` and one with no `notes` key are
                    // different bytes for the same thing, and only one of them is
                    // what the iOS build writes.
                    notes = (notesBySession[session.id] ?: emptyList()).sortedWith(NOTE_ORDER).map(::noteData),
                    checkInIntervalMinutes = session.checkInIntervalMinutes,
                    checkInOffsetMinutes = session.checkInOffsetMinutes,
                    checkInOffered = session.checkInOffered,
                )
            }

        val orphans = entries.filter { it.sessionId == null }.sortedWith(DOSE_ORDER).map(::doseData)

        val colors = db.substanceColorDao().all()
            .filter { !it.usesDefault || it.isLegacy }
            .mapNotNull { row -> colorData(row) }

        val favorites = db.favoriteSubstanceDao().all().map { row ->
            PiruFavoriteData(
                substance = row.substance,
                createdAt = row.createdAt.time,
                sortOrder = row.sortOrder,
                substanceUID = row.substanceUID,
                isomer = row.isomer,
                releaseForm = row.releaseForm,
                saltForm = row.saltForm,
                productName = row.productName,
            )
        }

        val customSubstances = db.customSubstanceDao().all().map(::customSubstanceData)

        val inventory = db.inventoryDao().all().map { item ->
            PiruInventoryData(
                id = WireUuid.write(item.id),
                sortOrder = item.sortOrder,
                substance = item.substance,
                saltForm = item.saltForm,
                unit = item.unit,
                trackingStart = item.trackingStart.toEpochMilli(),
                lowStockThreshold = item.lowStockThreshold,
                baselineQuantity = item.baselineQuantity,
                doseSize = item.doseSize,
                unitStrengthMG = item.unitStrengthMG,
                createdAt = item.createdAt.toEpochMilli(),
                manualEvents = item.manualEvents.map { event ->
                    PiruManualEventData(
                        id = WireUuid.write(event.id),
                        kind = event.kind.wireValue,
                        amount = event.amount,
                        date = event.date.toEpochMilli(),
                        note = event.note,
                        setsBaseline = event.setsBaseline,
                    )
                },
            )
        }

        val quickLogDoses = db.quickLogDoseDao().all().map { chip ->
            PiruQuickLogDoseData(
                substance = chip.substance,
                route = chip.route.wireValue,
                amount = chip.amount,
                unit = chip.unit,
                sortOrder = chip.sortOrder,
                lastUsedAt = chip.lastUsedAt.time,
                volumeML = chip.volumeML,
                abv = chip.abv,
                drinkName = chip.drinkName,
                emoji = chip.emoji,
                substanceUID = chip.substanceUID,
                isomer = chip.isomer,
                releaseForm = chip.releaseForm,
                saltForm = chip.saltForm,
                productName = chip.productName,
            )
        }

        val routineOccurrences = db.routineOccurrenceDao().all().map { row ->
            PiruRoutineOccurrenceData(
                routineName = row.routineName,
                substance = row.substance,
                substanceUID = row.substanceUID,
                // The raw string, not the parsed enum: the enum's `OTHER` and the
                // row's own spelling are not the same thing, and a state this
                // build predates must survive the round trip.
                route = row.routeRaw,
                dueDay = row.dueDay.time,
                state = row.stateRaw,
                satisfyingEntryID = row.satisfyingEntryID?.let(WireUuid::write),
                slotMinutes = row.slotMinutes,
            )
        }

        // Lab results were the one kind of user-authored row this file could not
        // carry, because they were not in the database — see `LabMeasurementEntity`.
        // They are v3, and they are here.
        val labMeasurements = db.labMeasurementDao().all().map { row ->
            PiruLabMeasurementData(
                id = row.id,
                date = row.date.time,
                analyteKey = row.analyteKey,
                value = row.value,
                inputUnit = row.inputUnit,
                esterID = row.esterId,
                excludedFromCalibration = row.excludedFromCalibration,
                note = row.note,
                createdAt = row.createdAt.time,
            )
        }

        val profile = db.userProfileDao().current()?.let { record ->
            PiruProfileData(
                disclosureTier = record.disclosureTierRaw,
                bodyWeightKg = record.bodyWeightKg,
                weightSource = record.weightSourceRaw,
                grapefruitLoggingEnabled = record.grapefruitLoggingEnabled,
                aldh2Deficient = record.aldh2Deficient,
            )
        }

        val preferences = db.notificationPreferencesDao().current()?.let { record ->
            PiruNotificationPreferencesData(
                masterEnabled = record.masterEnabled,
                hydrationEnabled = record.hydrationEnabled,
                sleepEnabled = record.sleepEnabled,
                phaseEnabled = record.phaseEnabled,
                cumulativeEnabled = record.cumulativeEnabled,
                routineEnabled = record.routineEnabled,
                routineFollowUpEnabled = record.routineFollowUpEnabled,
                inventoryEnabled = record.inventoryEnabled,
                checkInEnabled = record.checkInEnabled,
                quietHoursEnabled = record.quietHoursEnabled,
                quietHoursStartMinutes = record.quietHoursStartMinutes,
                quietHoursEndMinutes = record.quietHoursEndMinutes,
                routineTimeSensitive = record.routineTimeSensitive,
                routineFollowUpTimeSensitive = record.routineFollowUpTimeSensitive,
                cumulativeTimeSensitive = record.cumulativeTimeSensitive,
                // `nil` when the cadence was never edited, so the importing device
                // keeps reading its own default; a stored empty list is a real
                // choice ("off") and is written as one.
                askAgainDefaultMinutes = record.askAgainDefaultJson?.let { record.askAgainDefaultMinutes },
            )
        }

        return PiruFile(
            piruExportVersion = PIRU_EXPORT_VERSION,
            appVersion = appVersion,
            exportedAt = now.toEpochMilli(),
            sessions = sessionData,
            orphanDoses = orphans,
            dailyDoseItems = db.dailyDoseItemDao().all().map(::dailyDoseData),
            substanceColors = colors,
            favorites = favorites,
            customSubstances = customSubstances,
            inventory = inventory,
            labMeasurements = labMeasurements,
            // customUnits, drinkPresets and settings are left null — and therefore
            // absent from the file — because this build has no table for them. A
            // `[]` here would be a claim that the user has none, which is not the
            // same statement as "this file doesn't carry them". See the class note.
            quickLogDoses = quickLogDoses,
            routineOccurrences = routineOccurrences,
            profile = profile,
            notificationPreferences = preferences,
            settings = null,
        )
    }

    private fun doseData(entry: DoseEntryEntity): PiruDoseData = PiruDoseData(
        id = WireUuid.write(entry.id),
        substance = entry.substance,
        amount = entry.amount,
        unit = entry.unit,
        route = entry.route.wireValue,
        saltForm = entry.saltForm,
        substanceUID = entry.substanceUID,
        isomer = entry.isomer,
        releaseForm = entry.releaseForm,
        productName = entry.productName,
        displayNameSnapshot = entry.displayNameSnapshot,
        timestamp = entry.timestamp.time,
        notes = entry.notes,
        tags = entry.tags,
        isBackgroundMed = entry.isBackgroundMed,
        locationName = entry.locationName,
        latitude = entry.latitude,
        longitude = entry.longitude,
        isApproximate = entry.isApproximate,
        isUnknownDose = entry.isUnknownDose,
        hadGrapefruit = entry.hadGrapefruit,
        volumeML = entry.volumeML,
        abv = entry.abv,
        drinkName = entry.drinkName,
    )

    private fun noteData(note: SessionNoteEntity): PiruSessionNoteData = PiruSessionNoteData(
        id = WireUuid.write(note.id),
        timestamp = note.timestamp.time,
        text = note.text,
        shulgin = note.shulgin,
        mood = note.mood,
        energy = note.energy,
        social = note.social,
        worked = note.worked,
        descriptors = note.descriptors,
        heartRate = note.heartRate,
        kind = note.kindRaw,
    )

    private fun dailyDoseData(item: DailyDoseItemEntity): PiruDailyDoseData = PiruDailyDoseData(
        substance = item.substance,
        amount = item.amount,
        unit = item.unit,
        route = item.route.wireValue,
        sortOrder = item.sortOrder,
        category = item.category,
        isBackgroundMed = item.isBackgroundMed,
        frequencyRaw = item.frequencyRaw,
        frequencyDays = item.frequencyDays,
        startDate = item.startDate.time,
        substanceUID = item.substanceUID,
        isomer = item.isomer,
        releaseForm = item.releaseForm,
        saltForm = item.saltForm,
        productName = item.productName,
        reminderTimesMinutes = item.reminderTimesMinutes,
        remind = item.remind,
        askAgainOverrideMinutes = item.askAgainOverrideMinutes,
        isQuiet = item.isQuiet,
        isAsNeeded = item.isAsNeeded,
        maxPerDay = item.maxPerDay,
    )

    /**
     * One colour row, or null when a legacy row's hex is unreadable.
     *
     * Format 2 writes `p3` only; `hexColor` is the format-1 field and this build
     * never writes it. The tint of a legacy row is derived from its hex exactly
     * as [SubstanceColorEntity] resolves it, so a row the colour-update notice has
     * not yet converted exports the colour the user actually sees.
     */
    private fun colorData(row: SubstanceColorEntity): PiruColorData? {
        val tint = if (row.isLegacy) {
            runCatching { LegacyColorImport.p3(row.hexColor) }.getOrNull() ?: return null
        } else {
            P3Color(red = row.red, green = row.green, blue = row.blue)
        }
        return PiruColorData(substance = row.substance, p3 = tint)
    }

    private fun customSubstanceData(row: CustomSubstanceRecordEntity): PiruCustomSubstanceData =
        PiruCustomSubstanceData(
            id = WireUuid.write(row.id),
            name = row.name,
            // The raw column, not `category?.wireValue`: a category this build
            // predates is stored verbatim and must travel back verbatim.
            category = row.categoryRaw,
            defaultRoute = row.defaultRouteRaw,
            unit = row.unit,
            notes = row.notes,
            duration = row.duration,
            createdAt = row.createdAt.time,
            displayName = row.displayName,
            doses = row.doses,
            halfLifeMinutes = row.halfLifeMinutes,
        )

    /**
     * Doses and notes in time order, with the row id as the tiebreak.
     *
     * Upstream sorts on the timestamp alone, which leaves two doses logged in the
     * same millisecond in whatever order the fetch happened to return. The
     * tiebreak makes this file deterministic, which is what lets a test pin its
     * text — and insertion order is the reading a reader would expect anyway.
     */
    private val DOSE_ORDER = compareBy<DoseEntryEntity>({ it.timestamp.time }, { it.rowId })
    private val NOTE_ORDER = compareBy<SessionNoteEntity>({ it.timestamp.time }, { it.id.toString() })

    // MARK: - The PsychonautWiki file

    /**
     * Build PW's modern file.
     *
     * Doses group by **session**, so a Piru session becomes one PW "experience"
     * carrying its title and note; session-less doses fall back to one experience
     * per session day.
     *
     * Every object is assembled key by key rather than by a serializer, because
     * PW's shape is not Swift-synthesized `Codable`'s output: it writes explicit
     * `null`s in six places, a `ratings: []` that Piru has no field for, and
     * deliberately **omits** `location` on an ingestion even though it decodes it.
     * A generated encoder cannot express "decode this, never write it", and the
     * upstream `encode(to:)` is hand-written for that reason.
     */
    private suspend fun makePsyLogFile(
        db: PiruDatabase,
        zone: ZoneId,
        dayBoundaryHour: Int,
    ): JsonObject {
        val entries = db.doseEntryDao().all()
        val notes = db.sessionNoteDao().all()
        val sessions = db.sessionDao().all().associateBy { it.id }
        val notesBySession = notes.filter { it.sessionId != null }.groupBy { it.sessionId!! }

        val titleFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US).withZone(zone)

        // A group per session, plus a group per session day for the doses no
        // session owns. Built as an intermediate list so the whole set can be
        // sorted by its real start, which is what upstream does.
        val groups = mutableListOf<PsyLogGroup>()
        for ((sessionId, sessionDoses) in entries.filter { it.sessionId != null }.groupBy { it.sessionId!! }) {
            val sorted = sessionDoses.sortedWith(DOSE_ORDER)
            val session = sessions[sessionId]
            if (session == null) {
                // A dose pointing at a session row that no longer exists. Grouping
                // it by day is the only honest reading left; dropping it would lose
                // a dose to a dangling reference.
                groups += dayGroups(sorted, titleFormatter, zone, dayBoundaryHour)
                continue
            }
            groups += PsyLogGroup(
                title = session.title ?: titleFormatter.format(session.startDate.toInstant()),
                note = session.note ?: "",
                start = session.startDate.toInstant(),
                doses = sorted,
                // The summary already rides in `text`; PW's timed notes carry the
                // rest of the timeline.
                notes = (notesBySession[sessionId] ?: emptyList())
                    .filter { it.kind != SessionNoteEntity.Kind.SUMMARY }
                    .sortedWith(NOTE_ORDER),
            )
        }
        groups += dayGroups(entries.filter { it.sessionId == null }, titleFormatter, zone, dayBoundaryHour)
        groups.sortBy { it.start }

        val colorRows = db.substanceColorDao().all()
        return buildJsonObject {
            put("exportSource", JsonPrimitive(PsyLogFile.EXPORT_SOURCE_VALUE))
            put("experiences", JsonArray(groups.map(::psyLogExperience)))
            put(
                "substanceCompanions",
                wireJson.encodeToJsonElement(
                    ListSerializer(PsyLogCompanion.serializer()),
                    colorRows.map { row ->
                        PsyLogCompanion(
                            color = PsyLogColorMap.name(nearest = colorTint(row)),
                            substanceName = row.substance,
                        )
                    },
                ),
            )
            // PW's modern shape always carries the key; Piru has no custom units
            // to put in it, so it is written empty rather than omitted.
            put("customUnits", JsonArray(emptyList()))
        }
    }

    /** One PW experience: this build's shape, on its way out. */
    private class PsyLogGroup(
        val title: String,
        val note: String,
        val start: Instant,
        val doses: List<DoseEntryEntity>,
        val notes: List<SessionNoteEntity>,
    )

    /** Session-less doses, one PW experience per session day, oldest first. */
    private fun dayGroups(
        doses: List<DoseEntryEntity>,
        titleFormatter: DateTimeFormatter,
        zone: ZoneId,
        dayBoundaryHour: Int,
    ): List<PsyLogGroup> = doses
        .groupBy { SessionDay.sessionDayStart(it.timestamp.toInstant(), zone, dayBoundaryHour) }
        .toSortedMap()
        .map { (day, dayDoses) ->
            PsyLogGroup(
                title = titleFormatter.format(day),
                note = "",
                start = day,
                doses = dayDoses.sortedWith(DOSE_ORDER),
                notes = emptyList(),
            )
        }

    private fun psyLogExperience(group: PsyLogGroup): JsonObject {
        val startMs = (group.doses.firstOrNull()?.timestamp?.time) ?: group.start.toEpochMilli()
        return buildJsonObject {
            // `ratings` is PW's own field, which Piru has nowhere to keep. Written
            // as an empty array because that is what upstream writes, not omitted.
            put("ratings", JsonArray(emptyList()))
            put("title", JsonPrimitive(group.title))
            put("isFavorite", JsonPrimitive(false))
            put("creationDate", JsonPrimitive(startMs))
            // Explicitly `null` when there is none: upstream encodes the optional
            // with `encode(_:forKey:)` rather than `encodeIfPresent`, so a missing
            // location is a written `null`, not an absent key.
            put("location", group.doses.firstNotNullOfOrNull(::psyLogLocation) ?: JsonNull)
            put("sortDate", JsonPrimitive(startMs))
            put("text", JsonPrimitive(group.note))
            put("timedNotes", JsonArray(group.notes.map(::psyLogTimedNote)))
            put("ingestions", JsonArray(group.doses.map(::psyLogIngestion)))
        }
    }

    /**
     * A note flattened to one line, because PW has one text field per note.
     *
     * The structure (Shulgin, "did it work", mood, energy, social, heart rate) and
     * the effect descriptors are folded into the line with the text; the native
     * format keeps every field separate.
     *
     * **One divergence, stated rather than hidden.** Upstream resolves each stored
     * descriptor id to its display name through `SubjectiveEffectOntology`, and
     * this build has no such table — the ids are written as they are stored. A
     * descriptor imported from iOS therefore exports as an id. The rest of the
     * line is identical: [ShulginScale] and [WorkedScale] are ported whole.
     */
    private fun psyLogTimedNote(note: SessionNoteEntity): JsonObject = wireJson.encodeToJsonElement(
        PsyLogTimedNote.serializer(),
        PsyLogTimedNote(
            creationDate = note.timestamp.time,
            time = note.timestamp.time,
            note = flattenedNoteLine(note),
        ),
    ) as JsonObject

    private fun flattenedNoteLine(note: SessionNoteEntity): String {
        val structure = buildList {
            note.shulgin?.let { ShulginScale.glyph(it) }?.let(::add)
            note.worked?.let { WorkedScale.exportWord(it) }?.let(::add)
            note.mood?.let { add("mood ${signed(it)}") }
            note.energy?.let { add("energy ${signed(it)}") }
            note.social?.let { add("social ${signed(it)}") }
            note.heartRate?.let { add("♥ ${Math.round(it)}") }
        }.joinToString(" · ")

        val pieces = mutableListOf<String>()
        if (structure.isNotEmpty()) pieces += "[$structure]"
        if (note.descriptors.isNotEmpty()) pieces += "[" + note.descriptors.joinToString(", ") + "]"
        if (note.text.isNotEmpty()) pieces += note.text
        return pieces.joinToString(" ")
    }

    private fun signed(value: Int): String = when {
        value > 0 -> "+$value"
        // A real minus sign, not a hyphen: this is display text in a report, and
        // upstream writes U+2212.
        value < 0 -> "−${Math.abs(value)}"
        else -> "0"
    }

    /**
     * Internal rather than private so the format spec can pin its output.
     *
     * A copy of this in the test would pin the copy. It is `internal`, not
     * public: nothing outside `:core:data` writes a PsychonautWiki ingestion.
     */
    internal fun psyLogIngestion(entry: DoseEntryEntity): JsonObject {
        val noteText = buildString {
            append(entry.notes ?: "")
            if (entry.tags.isNotEmpty()) {
                val tags = entry.tags.joinToString(" ") { "#$it" }
                if (isNotEmpty()) append(' ')
                append(tags)
            }
        }
        return buildJsonObject {
            // The five `encodeNil`s upstream writes by hand.
            put("customUnitId", JsonNull)
            put("creationDate", JsonPrimitive(entry.timestamp.time))
            put("consumerName", JsonNull)
            // `encode` on an Optional writes `null`; `encodeIfPresent` would skip.
            put("substanceName", JsonPrimitive(entry.substance))
            put("estimatedDoseStandardDeviation", JsonNull)
            put("isDoseAnEstimate", JsonPrimitive(false))
            put("stomachFullness", JsonNull)
            put("dose", JsonPrimitive(entry.amount))
            put("endTime", JsonNull)
            put("time", JsonPrimitive(entry.timestamp.time))
            put("administrationRoute", JsonPrimitive(entry.route.psylogName()))
            put("notes", JsonPrimitive(noteText))
            put("units", JsonPrimitive(entry.unit))
            // PW's modern ingestions always carry this flag. `location` is
            // deliberately NOT written — PW keeps location at the experience
            // level, and upstream's hand-written encoder omits it even though its
            // decoder reads it back from older Piru files.
            put("isHiddenInTimeline", JsonPrimitive(false))
        }
    }

    /**
     * A dose's location as the exportable shape, or null when it has no name.
     *
     * Coordinates ride along when present; a name-only location still round-trips,
     * because PW's shape allows one.
     */
    private fun psyLogLocation(entry: DoseEntryEntity): JsonObject? {
        val name = entry.locationName ?: return null
        return wireJson.encodeToJsonElement(
            PsyLogLocation.serializer(),
            PsyLogLocation(name = name, latitude = entry.latitude, longitude = entry.longitude),
        ) as JsonObject
    }

    /** The colour a row shows, or the neutral tone when a legacy hex is unreadable. */
    private fun colorTint(row: SubstanceColorEntity): P3Color {
        if (!row.isLegacy) return P3Color(red = row.red, green = row.green, blue = row.blue)
        return runCatching { LegacyColorImport.p3(row.hexColor) }.getOrDefault(P3Color.NEUTRAL)
    }
}
