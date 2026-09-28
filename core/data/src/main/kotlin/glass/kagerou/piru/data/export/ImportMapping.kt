package glass.kagerou.piru.data.export

import glass.kagerou.piru.data.CustomSubstanceBlobs
import glass.kagerou.piru.data.JsonLists
import glass.kagerou.piru.data.ManualEvent
import glass.kagerou.piru.data.ManualEvents
import glass.kagerou.piru.data.PiruDatabase
import glass.kagerou.piru.data.SubstanceColorStore
import glass.kagerou.piru.data.entity.CustomSubstanceRecordEntity
import glass.kagerou.piru.data.entity.DailyDoseItemEntity
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.data.entity.FavoriteSubstanceEntity
import glass.kagerou.piru.data.entity.InventoryItemEntity
import glass.kagerou.piru.data.entity.NotificationPreferencesEntity
import glass.kagerou.piru.data.entity.QuickLogDoseEntity
import glass.kagerou.piru.data.entity.RoutineOccurrenceEntity
import glass.kagerou.piru.data.entity.SessionEntity
import glass.kagerou.piru.data.entity.SessionNoteEntity
import glass.kagerou.piru.data.entity.SubstanceColorEntity
import glass.kagerou.piru.data.entity.UserProfileRecordEntity
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.model.RouteOfAdministration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray

/**
 * The three importers, and the writers they share.
 *
 * Ported from `importPiruNative`, `importPsyLog` and `importLegacy` in
 * `Piru/Utilities/DataExportImport+PiruNative.swift`, `+PsyLog.swift` and
 * `+Legacy.swift`.
 *
 * ## Store rows always merge
 * There is no "replace" mode in here. A replace is the *caller* having just
 * emptied the store; the importer then merges into an empty store, which restores
 * exactly. Upstream says the same thing in one line — "a replace runs this
 * against a store it has just emptied, so the merge restores exactly" — and the
 * only thing in either app that a merge and a replace treat differently is the
 * settings section, which this build does not carry.
 *
 * ## Merging never overwrites a choice made here
 * Three rules, all upstream's:
 *
 * - Doses dedupe by content ([DataExportImport.doseDedupKey]), within the file
 *   and against what is stored, so re-importing the same backup is idempotent.
 * - Colours, favourites and daily-dose items dedupe by **lowercased name**, so a
 *   row the user has since recoloured or rescheduled survives.
 * - The two singletons (profile, notification choices) take the file's values
 *   only while this device's record is absent or still entirely at its defaults.
 */
internal object DoseWriter {

    /** The dedup key of a stored row, in the same terms an incoming dose is keyed. */
    fun keyOf(entry: DoseEntryEntity): String = DataExportImport.doseDedupKey(
        substance = entry.substance,
        timestamp = Instant.ofEpochMilli(entry.timestamp.time),
        amount = entry.amount,
        unit = entry.unit,
        route = entry.route,
    )

    /** The wire route string as the enum, or [RouteOfAdministration.OTHER] for a spelling this build predates. */
    fun routeOf(wire: String): RouteOfAdministration =
        RouteOfAdministration.entries.firstOrNull { it.wireValue == wire } ?: RouteOfAdministration.OTHER
}

/**
 * Dedup and stable-id bookkeeping across one import.
 *
 * Seeded with what the store already holds, so the two sets cover the whole
 * import however many sections write through it. Upstream keeps the same two
 * sets as local variables inside `importPiruNative`; they are a class here
 * because the PsyLog importer needs the same behaviour and a second copy of the
 * rules is a second place for them to drift.
 */
internal class DoseIds(existing: List<DoseEntryEntity>) {

    private val seenKeys: MutableSet<String> = existing.mapTo(HashSet(), DoseWriter::keyOf)
    private val seenIds: MutableSet<UUID> = existing.mapTo(HashSet()) { it.id }

    /** False when this exact dose is already stored or already claimed by this import. */
    fun claim(
        substance: String,
        timestamp: Instant,
        amount: Double,
        unit: String,
        route: RouteOfAdministration,
    ): Boolean = seenKeys.add(
        DataExportImport.doseDedupKey(substance, timestamp, amount, unit, route),
    )

    /**
     * The id an imported dose keeps.
     *
     * The file's own id is kept when it is free — so a reference to it (a
     * ramp-down key, an open route) survives a wipe-and-restore — and a fresh one
     * is used when it is taken, which is what a merge of two devices that each
     * edited the same dose looks like. A pre-`id` file carries none and gets fresh
     * ids throughout.
     */
    fun idFor(fileId: String?): UUID {
        val parsed = WireUuid.read(fileId)
        if (parsed != null && seenIds.add(parsed)) return parsed
        return UUID.randomUUID().also(seenIds::add)
    }
}

// MARK: - Piru-native

internal object NativeImport {

    suspend fun run(
        file: PiruFile,
        db: PiruDatabase,
        catalog: SubstanceCatalog?,
    ): DataExportImport.ImportReport {
        val customSubstancesMerged = importCustomSubstances(file.customSubstances, db)

        val ids = DoseIds(db.doseEntryDao().all())
        var entriesAdded = 0
        var sessionsAdded = 0

        val sessionsById = db.sessionDao().all().associateByTo(HashMap()) { it.id }

        for (sessionData in file.sessions) {
            val requested = WireUuid.read(sessionData.id)
            val existing = requested?.let(sessionsById::get)

            val session = existing ?: run {
                val row = SessionEntity(
                    id = requested ?: UUID.randomUUID(),
                    startDate = Date(sessionData.startDate),
                    title = sessionData.title,
                    note = sessionData.note,
                )
                db.sessionDao().insert(row)
                sessionsById[row.id] = row
                sessionsAdded++
                row
            }

            for (dose in sessionData.doses) {
                val entry = doseEntity(dose, session.id, ids) ?: continue
                db.doseEntryDao().insert(entry)
                entriesAdded++
            }
            sessionsById[session.id] = session

            // Derived from the doses that just landed, so a session restored onto
            // an existing row gets the bounds the file implies rather than the
            // ones the row had.
            db.sessionDao().refreshDoseBounds(session.id)
            importNotes(sessionData.notes ?: emptyList(), session.id, db)

            // The file fills only what this device left empty, so an edit made
            // here survives a merge. Re-read after the bounds refresh, which has
            // already written to the row.
            val stored = db.sessionDao().byId(session.id) ?: continue
            var updated = stored
            if (updated.checkInIntervalMinutes == null) {
                updated = updated.copy(checkInIntervalMinutes = sessionData.checkInIntervalMinutes)
            }
            val offsets = sessionData.checkInOffsetMinutes
            if (updated.checkInOffsetMinutes.isEmpty() && !offsets.isNullOrEmpty()) {
                updated = updated.copy(checkInOffsetsJson = JsonLists.encode(offsets))
            }
            if (sessionData.checkInOffered == true) updated = updated.copy(checkInOffered = true)
            if (updated != stored) db.sessionDao().update(updated)
        }

        // Session-less doses are left unassigned; the app's cluster pass groups
        // them. Upstream does the same.
        for (orphan in file.orphanDoses) {
            val entry = doseEntity(orphan, null, ids) ?: continue
            db.doseEntryDao().insert(entry)
            entriesAdded++
        }

        // Colours — skip a substance that already has a row.
        val colorNames = db.substanceColorDao().all().mapTo(HashSet()) { it.substance.lowercase(Locale.ROOT) }
        for (color in file.substanceColors) {
            val tint = color.tint ?: continue
            if (!colorNames.add(color.substance.lowercase(Locale.ROOT))) continue
            db.substanceColorDao().insertIgnoringDuplicates(
                SubstanceColorEntity(
                    substance = color.substance,
                    hexColor = "",
                    red = tint.red,
                    green = tint.green,
                    blue = tint.blue,
                    // The user chose this; the generator must never repaint it.
                    usesDefault = false,
                ),
            )
        }

        // Favourites — dedup by substance.
        val favoriteNames = db.favoriteSubstanceDao().all().mapTo(HashSet()) { it.substance.lowercase(Locale.ROOT) }
        var favoritesAdded = 0
        for (favorite in file.favorites) {
            if (!favoriteNames.add(favorite.substance.lowercase(Locale.ROOT))) continue
            db.favoriteSubstanceDao().insert(
                FavoriteSubstanceEntity(
                    substance = favorite.substance,
                    createdAt = Date(favorite.createdAt),
                    sortOrder = favorite.sortOrder ?: 0,
                    substanceUID = favorite.substanceUID,
                    isomer = favorite.isomer,
                    releaseForm = favorite.releaseForm,
                    saltForm = favorite.saltForm,
                    productName = favorite.productName,
                ),
            )
            favoritesAdded++
        }

        // Daily-dose items — dedup by substance, restoring the full schedule.
        val medNames = db.dailyDoseItemDao().all().mapTo(HashSet()) { it.substance.lowercase(Locale.ROOT) }
        var medsAdded = 0
        for (item in file.dailyDoseItems) {
            if (!medNames.add(item.substance.lowercase(Locale.ROOT))) continue
            db.dailyDoseItemDao().insert(
                DailyDoseItemEntity(
                    substance = item.substance,
                    amount = item.amount,
                    unit = item.unit,
                    route = DoseWriter.routeOf(item.route),
                    sortOrder = item.sortOrder,
                    category = item.category,
                    isBackgroundMed = item.isBackgroundMed,
                    frequencyRaw = item.frequencyRaw,
                    frequencyDaysJson = JsonLists.encode(item.frequencyDays),
                    startDate = Date(item.startDate),
                    substanceUID = item.substanceUID,
                    isomer = item.isomer,
                    releaseForm = item.releaseForm,
                    saltForm = item.saltForm,
                    productName = item.productName,
                    reminderTimesJson = JsonLists.encode(item.reminderTimesMinutes ?: emptyList()),
                    remind = item.remind ?: true,
                    askAgainOverrideJson = item.askAgainOverrideMinutes?.let(JsonLists::encode),
                    isQuiet = item.isQuiet ?: false,
                    isAsNeeded = item.isAsNeeded ?: false,
                    maxPerDay = item.maxPerDay,
                ),
            )
            medsAdded++
        }

        importInventory(file.inventory ?: emptyList(), db, catalog = catalog)
        importNativeRecords(file, db)

        return DataExportImport.ImportReport(
            shape = DataExportImport.FileShape.PiruNative(file.appVersion),
            entriesAdded = entriesAdded,
            sessionsAdded = sessionsAdded,
            medsAdded = medsAdded,
            favoritesAdded = favoritesAdded,
            unsupported = unsupportedSections(file),
        )
    }

    /**
     * Merge imported custom substances: add new ones, update same-name matches
     * keeping the existing row's identity, skip exact duplicates.
     */
    suspend fun importCustomSubstances(
        list: List<PiruCustomSubstanceData>,
        db: PiruDatabase,
    ): Int {
        val dao = db.customSubstanceDao()
        val byName = dao.all().associateByTo(HashMap()) { it.name.lowercase(Locale.ROOT) }
        var changed = 0
        for (imported in list) {
            val key = imported.name.lowercase(Locale.ROOT)
            val incoming = imported.toEntity()
            val existing = byName[key]
            if (existing != null) {
                // Compared with the row id normalised away: it is storage, not
                // content, and a row that differs only in it is not a change.
                if (existing.copy(rowId = 0) == incoming.copy(rowId = 0)) continue
                val merged = incoming.copy(rowId = existing.rowId, id = existing.id)
                dao.update(merged)
                byName[key] = merged
            } else {
                val row = incoming.copy(rowId = 0)
                dao.insert(row)
                byName[key] = row
            }
            changed++
        }
        return changed
    }

    private suspend fun importNotes(
        notes: List<PiruSessionNoteData>,
        sessionId: UUID,
        db: PiruDatabase,
    ) {
        val dao = db.sessionNoteDao()
        val existing = dao.forSession(sessionId).mapTo(HashSet()) { it.id }
        for (note in notes) {
            val id = WireUuid.read(note.id) ?: UUID.randomUUID()
            if (!existing.add(id)) continue
            dao.insert(
                SessionNoteEntity(
                    id = id,
                    timestamp = Date(note.timestamp),
                    text = note.text,
                    shulgin = note.shulgin,
                    mood = note.mood,
                    energy = note.energy,
                    social = note.social,
                    worked = note.worked,
                    descriptorsJson = JsonLists.encodeStrings(note.descriptors),
                    heartRate = note.heartRate,
                    kindRaw = note.kind,
                    sessionId = sessionId,
                ),
            )
        }
    }

    /**
     * Inventory — merged by substance identity plus salt, which is the same key
     * `InventoryDao.byIdentity` matches on: an alias and its canonical name are one
     * item. Manual events union by id so a re-import is idempotent, the earliest
     * `trackingStart` wins, and a missing scalar setting is filled rather than
     * overwritten.
     */
    private suspend fun importInventory(
        imported: List<PiruInventoryData>,
        db: PiruDatabase,
        catalog: SubstanceCatalog?,
    ) {
        if (imported.isEmpty()) return
        val dao = db.inventoryDao()
        val items = dao.all().toMutableList()
        val takenIds = items.mapTo(HashSet()) { it.id }

        for (inv in imported) {
            val events = inv.manualEvents.map { event ->
                ManualEvent(
                    id = WireUuid.read(event.id) ?: UUID.randomUUID(),
                    kind = ManualEvent.Kind.fromWire(event.kind),
                    amount = event.amount,
                    date = Instant.ofEpochMilli(event.date),
                    note = event.note,
                    setsBaseline = event.setsBaseline,
                )
            }
            val match = items.firstOrNull {
                it.substance.equals(inv.substance, ignoreCase = true) && it.saltForm == inv.saltForm
            }
            if (match == null) {
                val fileId = WireUuid.read(inv.id)
                val id = if (fileId != null && takenIds.add(fileId)) fileId else UUID.randomUUID()
                takenIds.add(id)
                val row = InventoryItemEntity(
                    id = id,
                    substance = inv.substance,
                    saltForm = inv.saltForm,
                    unit = inv.unit,
                    trackingStart = Instant.ofEpochMilli(inv.trackingStart),
                    lowStockThreshold = inv.lowStockThreshold,
                    baselineQuantity = inv.baselineQuantity,
                    doseSize = inv.doseSize,
                    unitStrengthMG = inv.unitStrengthMG,
                    restocksJson = ManualEvents.encode(events),
                    createdAt = Instant.ofEpochMilli(inv.createdAt),
                    sortOrder = inv.sortOrder ?: 0,
                )
                // `upsert`, not `insert`: the row may carry the file's own id and
                // the store may already hold it.
                dao.upsert(row)
                items += row
            } else {
                val byId = match.manualEvents.associateByTo(LinkedHashMap()) { it.id }
                for (event in events) byId.putIfAbsent(event.id, event)
                var merged = match.withManualEvents(byId.values.sortedBy { it.date })
                val importedStart = Instant.ofEpochMilli(inv.trackingStart)
                if (importedStart < merged.trackingStart) merged = merged.copy(trackingStart = importedStart)
                if (merged.lowStockThreshold == null) merged = merged.copy(lowStockThreshold = inv.lowStockThreshold)
                if (merged.baselineQuantity == null) merged = merged.copy(baselineQuantity = inv.baselineQuantity)
                if (merged.doseSize == null) merged = merged.copy(doseSize = inv.doseSize)
                // A strength only means something in the unit it was recorded
                // against, so it is taken only when the two agree.
                if (merged.unitStrengthMG == null && merged.unit == inv.unit) {
                    merged = merged.copy(unitStrengthMG = inv.unitStrengthMG)
                }
                val index = items.indexOfFirst { it.id == match.id }
                items[index] = merged
                dao.upsert(merged)
            }
        }

        recomputeQuantities(db, catalog)
    }

    /**
     * Refill the `currentQuantity` cache after a bulk import.
     *
     * Upstream calls `InventoryService.recomputeAll(in:notify:false)`. The
     * `notify: false` half matters: a restore must not fire one low-stock alert per
     * item. Without a catalog the cache is left alone rather than filled with a
     * guess — it is documented as self-healing, and a wrong number that looks
     * authoritative is worse than a stale one that gets fixed on the next write.
     */
    private suspend fun recomputeQuantities(db: PiruDatabase, catalog: SubstanceCatalog?) {
        if (catalog == null) return
        val buckets = runCatching {
            glass.kagerou.piru.data.InventoryMath.bucketDoses(db.doseEntryDao().all(), catalog)
        }.getOrNull() ?: return
        for (item in db.inventoryDao().all()) {
            val quantity = runCatching {
                glass.kagerou.piru.data.InventoryMath.quantity(item, buckets, catalog)
            }.getOrNull() ?: continue
            if (quantity != item.currentQuantity) {
                db.inventoryDao().upsert(item.copy(currentQuantity = quantity))
            }
        }
    }

    /**
     * The sections beyond the journal.
     *
     * The two singletons take the file's values only while this device's record is
     * absent or untouched — every field at its default. A replace has just deleted
     * the row and a fresh install has only the default one, so a merge never
     * overwrites a choice made here.
     */
    private suspend fun importNativeRecords(file: PiruFile, db: PiruDatabase) {
        importQuickLogDoses(file.quickLogDoses ?: emptyList(), db)
        importRoutineOccurrences(file.routineOccurrences ?: emptyList(), db)
        importProfile(file.profile, db)
        importNotificationPreferences(file.notificationPreferences, db)
        // `settings` is deliberately not applied: none of the keys upstream
        // exports — skins, dock, tab layout, journal grouping, the timeline
        // toggles — exist in this build, so there is nothing to write them to.
        // It is reported by `unsupportedSections` instead of being dropped in
        // silence.
    }

    private suspend fun importQuickLogDoses(list: List<PiruQuickLogDoseData>, db: PiruDatabase) {
        if (list.isEmpty()) return
        val dao = db.quickLogDoseDao()
        val keys = dao.all().mapNotNullTo(HashSet()) { it.key }
        for (chip in list) {
            val row = QuickLogDoseEntity(
                substance = chip.substance,
                route = DoseWriter.routeOf(chip.route),
                amount = chip.amount,
                unit = chip.unit,
                sortOrder = chip.sortOrder,
                lastUsedAt = Date(chip.lastUsedAt),
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
            if (!keys.add(row.key)) continue
            dao.insert(row.copy(rowId = 0))
        }
    }

    /**
     * Past days are history the reconcile never re-derives — a Skip is a user
     * choice, a miss is a record — so every row travels, keyed so a re-import adds
     * nothing.
     */
    private suspend fun importRoutineOccurrences(list: List<PiruRoutineOccurrenceData>, db: PiruDatabase) {
        if (list.isEmpty()) return
        val dao = db.routineOccurrenceDao()
        val keys = dao.all().mapNotNullTo(HashSet(), ::occurrenceKey)
        for (data in list) {
            val day = Date(data.dueDay)
            val row = RoutineOccurrenceEntity(
                routineName = data.routineName,
                substance = data.substance,
                substanceUID = data.substanceUID,
                routeRaw = data.route,
                dueDay = day,
                stateRaw = data.state,
                satisfyingEntryID = WireUuid.read(data.satisfyingEntryID),
                slotMinutes = data.slotMinutes,
            )
            if (!keys.add(occurrenceKey(row))) continue
            dao.insert(row.copy(rowId = 0))
        }
    }

    /**
     * A routine occurrence's identity.
     *
     * An empty PSID is the same as none — upstream spells that `uid.flatMap { $0.isEmpty ? nil : $0 }`
     * — and a missing slot is the literal `-`, so "no slot" cannot collide with
     * slot zero.
     */
    private fun occurrenceKey(row: RoutineOccurrenceEntity): String {
        val identity = row.substanceUID?.takeIf { it.isNotEmpty() } ?: row.substance.lowercase(Locale.ROOT)
        return "${row.routineName}|$identity|${row.routeRaw}|${row.dueDay.time}|${row.slotMinutes ?: "-"}"
    }

    private suspend fun importProfile(imported: PiruProfileData?, db: PiruDatabase) {
        if (imported == null) return
        val dao = db.userProfileDao()
        val existing = dao.current()
        if (existing == null) {
            dao.insert(imported.applyTo(UserProfileRecordEntity()))
            return
        }
        // The file only speaks while this device's record is entirely default.
        if (profileDataOf(existing) == PiruProfileData()) {
            dao.update(imported.applyTo(existing))
        }
    }

    private suspend fun importNotificationPreferences(
        imported: PiruNotificationPreferencesData?,
        db: PiruDatabase,
    ) {
        if (imported == null) return
        val dao = db.notificationPreferencesDao()
        val existing = dao.current()
        if (existing == null) {
            dao.insert(imported.applyTo(NotificationPreferencesEntity()))
            return
        }
        if (preferencesDataOf(existing) == PiruNotificationPreferencesData()) {
            dao.update(imported.applyTo(existing))
        }
    }

    /** The sections the file carried that this build has no table for. */
    private fun unsupportedSections(file: PiruFile): List<DataExportImport.UnsupportedSection> =
        buildList {
            file.labMeasurements?.takeIf { it.isNotEmpty() }?.let {
                add(DataExportImport.UnsupportedSection("labMeasurements", it.size))
            }
            file.customUnits?.takeIf { it.isNotEmpty() }?.let {
                add(DataExportImport.UnsupportedSection("customUnits", it.size))
            }
            file.drinkPresets?.takeIf { it.isNotEmpty() }?.let {
                add(DataExportImport.UnsupportedSection("drinkPresets", it.size))
            }
            if (file.settings != null) {
                // A section rather than a list of rows: it is one blob of app
                // preferences, and the count is meaningless.
                add(DataExportImport.UnsupportedSection("settings", 0))
            }
        }

    // MARK: - Mapping

    private fun doseEntity(data: PiruDoseData, sessionId: UUID?, ids: DoseIds): DoseEntryEntity? {
        val timestamp = Instant.ofEpochMilli(data.timestamp)
        val route = DoseWriter.routeOf(data.route)
        if (!ids.claim(data.substance, timestamp, data.amount, data.unit, route)) return null
        return DoseEntryEntity(
            id = ids.idFor(data.id),
            substance = data.substance,
            amount = data.amount,
            unit = data.unit,
            route = route,
            saltForm = data.saltForm,
            isomer = data.isomer,
            releaseForm = data.releaseForm,
            substanceUID = data.substanceUID,
            productName = data.productName,
            displayNameSnapshot = data.displayNameSnapshot,
            timestamp = Date(data.timestamp),
            notes = data.notes,
            // CSV, through the entity's own writer — see the note on the third site below.
            tagsRaw = doseTagsRaw(data.tags),
            sessionId = sessionId,
            isBackgroundMed = data.isBackgroundMed,
            locationName = data.locationName,
            latitude = data.latitude,
            longitude = data.longitude,
            hadGrapefruit = data.hadGrapefruit,
            isApproximate = data.isApproximate ?: false,
            isUnknownDose = data.isUnknownDose ?: false,
            volumeML = data.volumeML,
            abv = data.abv,
            drinkName = data.drinkName,
        )
    }

    private fun PiruCustomSubstanceData.toEntity(): CustomSubstanceRecordEntity =
        CustomSubstanceRecordEntity(
            id = WireUuid.read(id) ?: UUID.randomUUID(),
            name = name,
            displayName = displayName,
            categoryRaw = category,
            defaultRouteRaw = defaultRoute,
            unit = unit,
            notes = notes,
            dosesJson = CustomSubstanceBlobs.encodeDoses(doses),
            durationJson = CustomSubstanceBlobs.encodeDuration(duration),
            halfLifeMinutes = halfLifeMinutes,
            createdAt = Date(createdAt),
        )

    private fun profileDataOf(record: UserProfileRecordEntity): PiruProfileData =
        PiruProfileData(
            disclosureTier = record.disclosureTierRaw,
            bodyWeightKg = record.bodyWeightKg,
            weightSource = record.weightSourceRaw,
            grapefruitLoggingEnabled = record.grapefruitLoggingEnabled,
            aldh2Deficient = record.aldh2Deficient,
        )

    private fun PiruProfileData.applyTo(record: UserProfileRecordEntity): UserProfileRecordEntity =
        record.copy(
            disclosureTierRaw = disclosureTier,
            bodyWeightKg = bodyWeightKg,
            weightSourceRaw = weightSource,
            grapefruitLoggingEnabled = grapefruitLoggingEnabled,
            aldh2Deficient = aldh2Deficient,
        )

    private fun preferencesDataOf(
        record: NotificationPreferencesEntity,
    ): PiruNotificationPreferencesData = PiruNotificationPreferencesData(
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
        askAgainDefaultMinutes = record.askAgainDefaultJson?.let { record.askAgainDefaultMinutes },
    )

    private fun PiruNotificationPreferencesData.applyTo(
        record: NotificationPreferencesEntity,
    ): NotificationPreferencesEntity = record.copy(
        masterEnabled = masterEnabled,
        hydrationEnabled = hydrationEnabled,
        sleepEnabled = sleepEnabled,
        phaseEnabled = phaseEnabled,
        cumulativeEnabled = cumulativeEnabled,
        routineEnabled = routineEnabled,
        routineFollowUpEnabled = routineFollowUpEnabled,
        inventoryEnabled = inventoryEnabled,
        checkInEnabled = checkInEnabled,
        quietHoursEnabled = quietHoursEnabled,
        quietHoursStartMinutes = quietHoursStartMinutes,
        quietHoursEndMinutes = quietHoursEndMinutes,
        routineTimeSensitive = routineTimeSensitive,
        routineFollowUpTimeSensitive = routineFollowUpTimeSensitive,
        cumulativeTimeSensitive = cumulativeTimeSensitive,
        // `null` follows the global default, which on this side is the column
        // being null; an empty list is "off" and is written as the empty list.
        askAgainDefaultJson = askAgainDefaultMinutes?.let(JsonLists::encode),
    )
}

// MARK: - PsychonautWiki

internal object PsyLogImport {

    suspend fun run(
        file: PsyLogFile,
        db: PiruDatabase,
        catalog: SubstanceCatalog? = null,
    ): DataExportImport.ImportReport {
        NativeImport.importCustomSubstances(customSubstancesOf(file), db)

        val customUnits = file.customUnits.associateBy({ it.id }, { it })
        val ids = DoseIds(db.doseEntryDao().all())
        var entriesAdded = 0
        var sessionsAdded = 0

        for (experience in file.experiences) {
            val sessionDoses = mutableListOf<DoseEntryEntity>()
            for (ingestion in experience.ingestions) {
                val name = ingestion.substanceName
                    ?: ingestion.customUnitId?.let { customUnits[it]?.name }
                    ?: continue
                val dose = ingestion.dose
                if (dose == null || dose <= 0) continue

                val route = routeFromPsyLogName(ingestion.administrationRoute)
                val timestamp = Instant.ofEpochMilli(ingestion.time)
                if (!ids.claim(name, timestamp, dose, ingestion.units, route)) continue

                // A dose's own location is a Piru extension present only in older
                // Piru exports; otherwise the experience's location is copied onto
                // every dose, because PW stores it per experience and Piru per
                // dose.
                val location = ingestion.location ?: experience.location
                sessionDoses += DoseEntryEntity(
                    id = ids.idFor(null),
                    substance = name,
                    amount = dose,
                    unit = ingestion.units,
                    route = route,
                    timestamp = Date(ingestion.time),
                    // The tags are extracted *and* the text is kept whole, which
                    // is what upstream does: the note was written with its tags in
                    // it, and rewriting it would change what the user typed.
                    notes = ingestion.notes.ifEmpty { null },
                    tagsRaw = doseTagsRaw(NoteTags.extract(ingestion.notes)),
                    locationName = location?.name,
                    latitude = location?.latitude,
                    longitude = location?.longitude,
                )
            }

            // Only when it brought in at least one new dose, so a re-import does
            // not accumulate empty sessions.
            if (sessionDoses.isEmpty()) continue

            val start = sessionDoses.minOf { it.timestamp.time }
            val session = SessionEntity(
                id = UUID.randomUUID(),
                startDate = Date(start),
                title = experience.title.ifEmpty { null },
                note = experience.text.ifEmpty { null },
            )
            db.sessionDao().insert(session)
            sessionsAdded++

            for (dose in sessionDoses) {
                db.doseEntryDao().insert(dose.copy(sessionId = session.id))
                entriesAdded++
            }
            db.sessionDao().refreshDoseBounds(session.id)

            for (timed in timedNotesOf(experience)) {
                if (timed.note.isBlank()) continue
                db.sessionNoteDao().insert(
                    SessionNoteEntity(
                        timestamp = Date(timed.time),
                        text = timed.note,
                        sessionId = session.id,
                    ),
                )
            }
        }

        // PW makes every substance carry one of its own palette names; those are
        // dropped in favour of this app's class colours, so an import does not
        // arrive as a list of "custom" colours the user never chose here. All this
        // does is make sure a row exists for each name.
        if (catalog != null) {
            val store = SubstanceColorStore(db, catalog)
            for (name in file.substanceCompanions.map { it.substanceName }) {
                runCatching { store.ensureRow(name) }
            }
        }

        var medsAdded = 0
        if (file.dailyDoseItems.isNotEmpty()) {
            val dao = db.dailyDoseItemDao()
            val names = dao.all().mapTo(HashSet()) { it.substance.lowercase(Locale.ROOT) }
            for (item in file.dailyDoseItems) {
                if (!names.add(item.substance.lowercase(Locale.ROOT))) continue
                dao.insert(
                    DailyDoseItemEntity(
                        substance = item.substance,
                        amount = item.amount,
                        unit = item.unit,
                        route = routeFromPsyLogName(item.route),
                        sortOrder = item.sortOrder,
                    ),
                )
                medsAdded++
            }
        }

        return DataExportImport.ImportReport(
            shape = DataExportImport.FileShape.PsyLog,
            entriesAdded = entriesAdded,
            sessionsAdded = sessionsAdded,
            medsAdded = medsAdded,
        )
    }

    /**
     * The PsyLog file's `customSubstances`, which upstream decodes with `try?`.
     *
     * A real PsychonautWiki file writes `customSubstances: []` as a *string*
     * array, so an array that is not Piru's object shape reads as **no customs**
     * rather than failing the import — the whole array, not the bad elements, is
     * the unit of that decision, and that is what `try?` around the whole decode
     * means.
     */
    private fun customSubstancesOf(file: PsyLogFile): List<PiruCustomSubstanceData> {
        if (file.customSubstances.isEmpty()) return emptyList()
        return runCatching {
            DataExportImport.wireJson.decodeFromJsonElement(
                ListSerializer(PiruCustomSubstanceData.serializer()),
                JsonArray(file.customSubstances),
            )
        }.getOrDefault(emptyList())
    }

    /**
     * The experience's timed notes, also decoded with upstream's `try?` — an array
     * that is not PW's object shape carries no notes.
     */
    private fun timedNotesOf(experience: PsyLogExperience): List<PsyLogTimedNote> {
        if (experience.timedNotes.isEmpty()) return emptyList()
        return runCatching {
            DataExportImport.wireJson.decodeFromJsonElement(
                ListSerializer(PsyLogTimedNote.serializer()),
                JsonArray(experience.timedNotes),
            )
        }.getOrDefault(emptyList())
    }
}

// MARK: - Legacy

internal object LegacyImport {

    private val iso8601: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    /**
     * Decode a legacy file without touching any store.
     *
     * Its `timestamp` is an ISO-8601 string, because this format predates the
     * millisecond convention every later one uses — the only place in the export
     * layer where a date is not a number.
     */
    fun decode(text: String): LegacyPiruData =
        DataExportImport.wireJson.decodeFromString(LegacyPiruData.serializer(), text)

    /**
     * Import a legacy file.
     *
     * Note what is **not** here: any dedup. Upstream's `importLegacy` inserts
     * every row it reads, and does it again if you import the same file twice.
     * That is reproduced rather than improved, because a merge that silently
     * dropped "duplicates" would drop real doses — this format has no dose ids and
     * its timestamps have one-second precision, so two doses a second apart are
     * indistinguishable from one entered twice.
     */
    suspend fun run(data: LegacyPiruData, db: PiruDatabase): DataExportImport.ImportReport {
        var entriesAdded = 0
        for (entry in data.doseEntries) {
            db.doseEntryDao().insert(
                DoseEntryEntity(
                    substance = entry.substance,
                    amount = entry.amount,
                    unit = entry.unit,
                    route = DoseWriter.routeOf(entry.route),
                    timestamp = Date(readIso8601(entry.timestamp)?.toEpochMilli() ?: 0L),
                    notes = entry.notes,
                    tagsRaw = doseTagsRaw(entry.tags ?: emptyList()),
                    locationName = entry.locationName,
                    latitude = entry.latitude,
                    longitude = entry.longitude,
                ),
            )
            entriesAdded++
        }

        for (item in data.dailyDoseItems) {
            db.dailyDoseItemDao().insert(
                DailyDoseItemEntity(
                    substance = item.substance,
                    amount = item.amount,
                    unit = item.unit,
                    route = DoseWriter.routeOf(item.route),
                    sortOrder = item.sortOrder,
                ),
            )
        }

        for (color in data.substanceColors) {
            val tint = runCatching { glass.kagerou.piru.model.LegacyColorImport.p3(color.hexColor) }
                .getOrNull() ?: continue
            db.substanceColorDao().insertIgnoringDuplicates(
                SubstanceColorEntity(
                    substance = color.substance,
                    hexColor = "",
                    red = tint.red,
                    green = tint.green,
                    blue = tint.blue,
                    usesDefault = false,
                ),
            )
        }

        return DataExportImport.ImportReport(
            shape = DataExportImport.FileShape.Legacy,
            entriesAdded = entriesAdded,
        )
    }

    /**
     * Parse the one ISO-8601 spelling this format wrote, and the two variants a
     * hand-edited or third-party file might carry.
     *
     * Swift's `.iso8601` decoding strategy is `ISO8601DateFormatter` with its
     * default options, which writes `2023-05-01T12:00:00Z` — no fractional
     * seconds. A file with them, or with a numeric offset instead of `Z`, is
     * accepted here rather than rejected, because the cost of the alternative is
     * losing the whole journal over a formatting detail.
     */
    private fun readIso8601(text: String): Instant? {
        runCatching { return Instant.parse(text) }
        runCatching { return OffsetDateTime.parse(text, iso8601).toInstant() }
        return null
    }
}

/**
 * The `tags_raw` column's encoding, in one place.
 *
 * **CSV, not JSON.** `DoseEntryEntity.tags` splits on a comma, and `withTags` is
 * the entity's own writer for exactly this reason — its KDoc says the encoding
 * lives in one method "instead of spreading it across every edit site".
 *
 * This function exists because the import spread it anyway, and got it wrong at
 * all three sites: each wrote `JsonLists.encodeStrings(...)`, which looks right
 * beside `descriptorsJson` two hundred lines away and is wrong here. A single tag
 * of `"morning"` was stored as the text `["morning"]`, so reading it back gave a
 * list whose one element was `["morning"]` — a round trip that preserved nonsense
 * perfectly.
 *
 * What caught it was the device round-trip spec, not the format spec: the bytes
 * were self-consistent, and only exporting a real row and importing it back shows
 * that "self-consistent" was the problem.
 *
 * Null for an empty list, matching [glass.kagerou.piru.data.entity.DoseEntryEntity.withTags]
 * and `create` — the column stores absence as null rather than as an empty string.
 */
private fun doseTagsRaw(tags: List<String>): String? =
    if (tags.isEmpty()) null else tags.joinToString(",")
