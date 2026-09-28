package glass.kagerou.piru.ui.meds

import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.InventoryMath
import glass.kagerou.piru.data.entity.DailyDoseItemEntity
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.AdherenceCalculator
import glass.kagerou.piru.engine.AdherenceEntry
import glass.kagerou.piru.engine.AdherenceItem
import glass.kagerou.piru.engine.AdherenceStatus
import glass.kagerou.piru.engine.DayStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date

/**
 * The meds screens' write path and their three store reads.
 *
 * Ported from the write halves of `Piru/Views/Journal/DailyDose/MedFormView.swift`
 * (`save()`), `MedDetailView.swift` (the in-place model bindings) and
 * `MyMedsCard.swift` (`log(slots:)`, `unlog(_:)`), plus the two fetches
 * `MyMedsModel.swift` performs (`refreshStreak`, and `MyMedsInfoModel`'s
 * supply projections).
 *
 * ## Why an edit is a delete and an insert
 * iOS binds a form straight to the `@Model`: mutating `item.amount` is the
 * write. Room has no such binding, and `DailyDoseItemDao` ships **no update
 * statement** — `all`, `observeAll`, `byRowId`, `insert`, `delete`, `deleteAll`
 * and nothing else. A `@PrimaryKey(autoGenerate = true)` row fed back through
 * `@Insert` collides on its own key and aborts, so the edit is a `delete`
 * followed by an `insert` that re-supplies the **same** `row_id`.
 *
 * The row id is preserved deliberately rather than renumbered: it is what the
 * pushed med-detail route is keyed by, and renumbering it would drop the user
 * out of the screen they are editing. SQLite accepts an explicit primary key on
 * insert, so the pair is a faithful edit.
 *
 * This is the one place in the app that knows that. A DAO `@Update` would
 * replace the pair with a single statement and this file would shrink to a call;
 * it is the first thing to ask `:core:data` for.
 *
 * ## Every write ends in `reconcileRoutineOccurrences`
 * Upstream calls `DoseNotificationManager.syncMedReminders` after a med edit and
 * after a dose commit. The port's equivalent is
 * [PiruApplication.reconcileRoutineOccurrences], which re-derives the occurrence
 * record and re-arms the reminders in that order — so it is called here rather
 * than left to a screen's `onDisappear`, which a configuration change can skip.
 */
internal object MedsStore {

    /**
     * Create or update a med and reschedule from the saved state.
     *
     * [entity] is the finished row — the form has already resolved identity,
     * canonicalised the name and normalised the reminder times, because those
     * are decisions the form owns, not the store.
     */
    suspend fun save(
        app: PiruApplication,
        existing: DailyDoseItemEntity?,
        entity: DailyDoseItemEntity,
    ): Long {
        val dao = app.database.dailyDoseItemDao()
        val rowId = if (existing == null) {
            dao.insert(entity.copy(rowId = 0))
        } else {
            dao.delete(existing)
            dao.insert(entity.copy(rowId = existing.rowId))
        }
        app.reconcileRoutineOccurrences()
        return rowId
    }

    /** Remove a med. Logged doses are untouched — they are history, not schedule. */
    suspend fun delete(app: PiruApplication, item: DailyDoseItemEntity) {
        app.database.dailyDoseItemDao().delete(item)
        app.reconcileRoutineOccurrences()
    }

    /**
     * Log one dose per item, now, and regroup.
     *
     * The entry carries the med's **full identity** — product word, PSID family,
     * isomer and release form — because that is what makes the med's "logged
     * today" check join a dose by identity rather than by a lowercased name:
     * a med saved as "Concerta" is answered by a dose logged as
     * Methylphenidate·XR, which a name join never could. See
     * `DailyDoseItemEntity.identityKey` and `AdherenceCalculator.identityMatches`.
     *
     * Returns how many entries were written.
     */
    suspend fun log(
        app: PiruApplication,
        items: List<DailyDoseItemEntity>,
        at: Instant = Instant.now(),
    ): Int {
        if (items.isEmpty()) return 0
        val dao = app.database.doseEntryDao()
        val sessions = app.sessionRepository()
        val timestamp = Date.from(at)
        var written = 0
        for (item in items) {
            val rowId = dao.insert(
                DoseEntryEntity.create(
                    substance = item.substance,
                    amount = item.amount,
                    unit = item.unit,
                    route = item.route,
                    timestamp = timestamp,
                ).copy(
                    substanceUID = item.substanceUID,
                    isomer = item.isomer,
                    releaseForm = item.releaseForm,
                    saltForm = item.saltForm,
                    productName = item.productName,
                    // Quiet meds are also background meds: they fold into an
                    // active session rather than opening one. Stamped at log time
                    // so a later edit to the template cannot rewrite history.
                    isBackgroundMed = item.isBackgroundMed,
                ),
            )
            sessions.assignSession(rowId)
            written++
        }
        app.reconcileRoutineOccurrences()
        return written
    }

    /**
     * Undo a logged dose: drop the entry and re-derive what it was standing for.
     *
     * The session's cached dose bounds are refreshed in the same pass, because
     * `SessionEntity` keeps `last_dose_date` as a memo and a deletion is exactly
     * the event that invalidates it.
     */
    suspend fun unlog(app: PiruApplication, entry: DoseEntryEntity) {
        val sessionId = entry.sessionId
        app.database.doseEntryDao().deleteByRowId(entry.rowId)
        if (sessionId != null) app.database.sessionDao().refreshDoseBounds(sessionId)
        app.reconcileRoutineOccurrences()
    }

    // MARK: - Reads

    /**
     * Doses logged since [since], oldest first.
     *
     * A ranged read rather than `all()`: the card wants the last 48 hours, and
     * the upper bound is the DAO's own `to` — an entry cannot be logged in the
     * future, so the open end costs nothing.
     */
    suspend fun entriesSince(app: PiruApplication, since: Instant): List<DoseEntryEntity> =
        app.database.doseEntryDao().inRange(Date.from(since), Date(Long.MAX_VALUE))

    /**
     * The most recent entry since [since] that credits [item] — what the check
     * circle un-logs when it is tapped a second time.
     */
    fun latestMatch(
        entries: List<DoseEntryEntity>,
        item: DailyDoseItemEntity,
        since: Instant,
    ): DoseEntryEntity? = entries
        .filter { it.timestamp.toInstant() >= since && credits(it, item) }
        .maxByOrNull { it.timestamp }

    /**
     * Whether a logged dose credits a scheduled med: the same join the adherence
     * calendar and the checklist both run, so the two cannot disagree about what
     * has been taken today.
     */
    fun credits(entry: DoseEntryEntity, item: DailyDoseItemEntity): Boolean =
        AdherenceCalculator.matches(
            entryKey = entry.identityKey,
            entryName = entry.substance,
            entryRoute = entry.route,
            itemKey = item.identityKey,
            itemName = item.substance,
            itemRoute = item.route,
        )

    /**
     * The current adherence streak, or null when there is nothing scheduled.
     *
     * ## Why this walks a year by hand
     * Upstream reaches `AdherenceStreakStore.shared`, which caches a whole-year
     * walk keyed on the dose-log revision. This build has no such store, and
     * `AdherenceCalculator.monthDays` builds one month at a time, so the year is
     * assembled here: the entries are bucketed by local day **once**, and each
     * day is scored against its own bucket rather than re-filtering the whole log
     * per day — which is what makes a year affordable at all.
     *
     * Returning null for an empty schedule mirrors upstream's early `guard`,
     * and is what keeps the header chip reading "0/0" instead of inventing a
     * streak for a journal with no meds in it.
     */
    suspend fun streak(
        app: PiruApplication,
        items: List<DailyDoseItemEntity>,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int? {
        if (items.isEmpty()) return null
        val adherenceItems = items.map { it.toAdherenceItem() }
        val today = now.atZone(zone).toLocalDate()
        val firstDay = today.minusDays(365)

        val entries = app.database.doseEntryDao().all()
        val byDay = entries.groupBy { it.timestamp.toInstant().atZone(zone).toLocalDate() }

        val days = ArrayList<DayStatus>(366)
        var day: LocalDate = firstDay
        while (!day.isAfter(today)) {
            val dayStart = day.atStartOfDay(zone).toInstant()
            val dayEntries = (byDay[day] ?: emptyList()).map { it.toAdherenceEntry() }
            val reading = AdherenceCalculator.dayAdherence(
                date = dayStart,
                entries = dayEntries,
                items = adherenceItems,
                zone = zone,
            )
            // Only days that were actually asked about; NoData days are stepped
            // over by the walk itself, and carrying them here would be 300
            // identical entries.
            if (reading.status != AdherenceStatus.NoData) days += DayStatus(reading.date, reading.status)
            day = day.plusDays(1)
        }
        return AdherenceCalculator.streak(days, now, zone)
    }

    /**
     * Each non-PRN med's supply projection, for the card's restock line.
     *
     * ## Only the last seven days are read
     * `InventoryMath.runOut` needs at least five distinct dosing days inside a
     * seven-day window, and it divides that window's consumption by seven. Doses
     * older than the window therefore cannot change its answer, so reading them
     * would only cost a full-table scan on the Journal screen.
     *
     * A med scheduled without a salt still matches the salt-less tracked supply;
     * a salted schedule wants its own — the same two-step fallback
     * `InventoryService.find` makes upstream.
     */
    suspend fun supplyProjections(
        app: PiruApplication,
        items: List<DailyDoseItemEntity>,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<MyMedsInfo.SupplyProjection> {
        val tracked = items.filter { !it.isAsNeeded }
        if (tracked.isEmpty()) return emptyList()

        val from = Date.from(now.minus(7, ChronoUnit.DAYS))
        val doses = app.database.doseEntryDao().inRange(from, Date.from(now))
        if (doses.isEmpty()) return emptyList()

        val catalog = app.catalog()
        val bucketed = InventoryMath.bucketDoses(doses, catalog)
        val inventory = app.database.inventoryDao()

        val projections = ArrayList<MyMedsInfo.SupplyProjection>()
        val seen = HashSet<String>()
        for (item in tracked) {
            val stock = inventory.byIdentity(item.substance, item.saltForm)
                ?: inventory.byIdentity(item.substance, null)
                ?: continue
            if (!seen.add(stock.id.toString())) continue
            val runOut = InventoryMath.runOut(stock, bucketed, catalog, now, zone) ?: continue
            projections += MyMedsInfo.SupplyProjection(
                // Upstream falls back through `CustomSubstanceStore.displayName`;
                // this build has no reader for that table, so a med the user named
                // shows the name they gave it and otherwise its substance.
                name = item.productName ?: item.substance,
                daysLeft = runOut.daysLeft,
                itemId = stock.id.toString(),
            )
        }
        return projections
    }

    /** The item the med form should edit, or null for a new med. */
    suspend fun itemByRowId(app: PiruApplication, rowId: Long): DailyDoseItemEntity? =
        app.database.dailyDoseItemDao().byRowId(rowId)
}

/** The narrow projection adherence reads. Mirrors `AdherenceScreen`'s private mapping. */
internal fun DailyDoseItemEntity.toAdherenceItem(): AdherenceItem = AdherenceItem(
    substance = substance,
    identityKey = identityKey,
    route = route,
    isAsNeeded = isAsNeeded,
    startDate = startDate.toInstant(),
    frequency = frequency,
    frequencyDays = frequencyDays,
    reminderTimesMinutes = reminderTimesMinutes,
    sortOrder = sortOrder,
)

/** One logged dose, narrowed to what adherence reads. */
internal fun DoseEntryEntity.toAdherenceEntry(): AdherenceEntry = AdherenceEntry(
    substance = substance,
    identityKey = identityKey,
    route = route,
    timestamp = timestamp.toInstant(),
)
