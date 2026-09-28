package glass.kagerou.piru.data

import glass.kagerou.piru.data.entity.DailyDoseItemEntity
import glass.kagerou.piru.data.entity.RoutineOccurrenceEntity
import glass.kagerou.piru.engine.AdherenceCalculator
import glass.kagerou.piru.model.RouteOfAdministration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.UUID
import kotlin.math.abs

/**
 * The single writer of [RoutineOccurrenceEntity] state.
 *
 * Ported from `Piru/Data/Services/RoutineOccurrenceService.swift` (about 290
 * lines, including the `LiveOccurrence` protocol, which this port folds into the
 * private `Live` record below).
 *
 * ## What the table is for
 * One occurrence per (med x time slot x day): a med with 8:00 and 13:00 reminder
 * times has two rows per due day, a med with no set times has one "anytime" row
 * (`slotMinutes == null`). As-needed meds carry no expectation and get no
 * occurrences.
 *
 * The reason it exists is the follow-up reminder. "Still need to log X?" fires
 * ten minutes after a med's reminder time, and it must not fire for a slot the
 * user already logged. Scanning today's doses for that answer is wrong, and
 * upstream says so in as many words: a dose logged at 09:40 does not answer
 * whether the 08:00 slot was taken. Only a per-slot record answers it, so this
 * service is what keeps that record true.
 *
 * ## Re-derive, never hand-update
 * Rather than an incremental state machine hand-updated on every log, edit and
 * delete — exactly where dose-scan inference failed silently — [reconcile]
 * idempotently re-derives today's occurrence states from scratch on every call.
 * Every hook that already resyncs the med reminders (dose commits, med edits,
 * app foreground) therefore keeps the record current for free, and a reconcile
 * that runs twice writes nothing the second time.
 *
 * ## The plan is pure, the reading is not
 * [plan] takes and returns plain values and touches nothing, so the matching
 * arithmetic is testable in a JVM spec; everything that reads the store lives in
 * the suspend methods around it. That split is upstream's (`plan(dueItems:…)`
 * against `plan(from:today:)`) and it is what keeps "which slot does this dose
 * claim" a rule rather than a query result.
 *
 * ## Zone
 * Every calendar computation takes a [ZoneId] rather than reading the system
 * default inside the calculation, so a spec can pin a day boundary and the
 * callers that mean "the user's own clock" say so.
 */
class RoutineOccurrenceService(private val database: PiruDatabase) {

    /** The occurrence table, addressed only through its DAO. */
    private val routineOccurrences get() = database.routineOccurrenceDao()

    // MARK: - Reconcile

    /**
     * Re-derive today's occurrence truth: expire past-day pendings to `missed`,
     * materialize today's occurrences for due meds' slots, and re-run dose
     * matching. `skipped` is a sticky user choice and survives; `logged` rows
     * whose dose disappeared revert to `pending`.
     *
     * Writes only when the plan has something to write: an unchanged day leaves
     * the store untouched, so no observer re-evaluates for nothing. That is not
     * only an optimisation — a reconcile runs on every dose commit and every
     * foreground, and an unconditional write would invalidate every occurrence
     * reader each time one did.
     */
    suspend fun reconcile(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) {
        val today = startOfDay(now, zone)
        val plan = planFor(today, zone)
        if (plan.isEmpty) return
        apply(plan, today)
    }

    /**
     * Read today's inputs and plan the reconcile.
     *
     * The day window is resolved once, here, and handed to the plan as instants
     * rather than re-derived there — so the reads and the expiry agree on where
     * today starts by construction instead of by both getting the arithmetic
     * right.
     */
    private suspend fun planFor(today: Instant, zone: ZoneId): Plan {
        val dayStart = Date(today.toEpochMilli())
        val dayEnd = Date(today.plus(1, ChronoUnit.DAYS).toEpochMilli())

        val expired = routineOccurrences.idsBefore(dayStart, RoutineOccurrenceEntity.State.PENDING.wireValue)

        val dueItems = database.dailyDoseItemDao().all().mapNotNull { itemSnapshot(it, today, zone) }

        val occurrences = routineOccurrences.forDay(dayStart, dayEnd).map { row ->
            OccurrenceSnapshot(
                rowId = row.rowId,
                substance = row.substance,
                substanceUID = row.substanceUID,
                route = row.route,
                slotMinutes = row.slotMinutes,
                state = row.state,
                satisfyingEntryID = row.satisfyingEntryID,
            )
        }

        val entries = database.doseEntryDao().inRange(dayStart, dayEnd).map { dose ->
            EntrySnapshot(
                id = dose.id,
                substance = dose.substance,
                substanceUID = dose.substanceUID,
                route = dose.route,
                timestamp = dose.timestamp.toInstant(),
            )
        }

        return plan(dueItems, occurrences, expired, entries, zone)
    }

    /** Write `plan` into the store. Every statement here is one the DAO owns. */
    private suspend fun apply(plan: Plan, today: Instant) {
        for (rowId in plan.expire) {
            // State alone: a row on its way to `missed` is history, and whatever
            // satisfying dose it carried must survive the transition.
            routineOccurrences.setState(rowId, RoutineOccurrenceEntity.State.MISSED.wireValue)
        }
        if (plan.deletes.isNotEmpty()) routineOccurrences.deleteByRowIds(plan.deletes)
        for ((rowId, outcome) in plan.updates) {
            routineOccurrences.setOutcome(rowId, outcome.state.wireValue, outcome.satisfyingEntryID)
        }
        if (plan.inserts.isNotEmpty()) {
            val dueDay = Date(today.toEpochMilli())
            routineOccurrences.insertAll(plan.inserts.map { it.toEntity(dueDay) })
        }
    }

    // MARK: - Skip Today

    /**
     * The user's "stop asking about these today": every still-pending occurrence
     * whose slot key matches becomes `skipped` (sticky through later reconciles).
     *
     * The caller resyncs the reminders afterwards so the remaining follow-ups
     * cancel — this method records the choice, it does not reschedule. The write
     * is the state alone: a slot that was logged and then skipped keeps the dose
     * that satisfied it.
     */
    suspend fun skipToday(
        slotKeys: Set<String>,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        for (row in todaysRows(now, zone)) {
            if (row.state != RoutineOccurrenceEntity.State.PENDING) continue
            if (!slotKeys.contains(slotKey(row))) continue
            routineOccurrences.setState(row.rowId, RoutineOccurrenceEntity.State.SKIPPED.wireValue)
        }
    }

    /**
     * Today's slot keys that need no more re-asks: `logged` or `skipped`.
     *
     * Assumes [reconcile] ran this pass. The question answered is "what does the
     * record say", and a record that has not been re-derived is a record from
     * before the last dose.
     */
    suspend fun satisfiedSlotKeys(
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Set<String> = todaysRows(now, zone)
        .filter { it.state == RoutineOccurrenceEntity.State.LOGGED || it.state == RoutineOccurrenceEntity.State.SKIPPED }
        .map { slotKey(it) }
        .toSet()

    private suspend fun todaysRows(now: Instant, zone: ZoneId): List<RoutineOccurrenceEntity> {
        val today = startOfDay(now, zone)
        return routineOccurrences.forDay(
            Date(today.toEpochMilli()),
            Date(today.plus(1, ChronoUnit.DAYS).toEpochMilli()),
        )
    }

    // MARK: - Snapshots

    /** One due med's slots, as the plan reads it. */
    data class ItemSnapshot(
        val substance: String,
        val substanceUID: String?,
        val route: RouteOfAdministration,
        /** Sorted reminder times, or a single `null` "anytime" slot. */
        val slots: List<Int?>,
    )

    /** One existing occurrence, as the plan reads it. */
    data class OccurrenceSnapshot(
        val rowId: Long,
        override val substance: String,
        override val substanceUID: String?,
        override val route: RouteOfAdministration,
        override val slotMinutes: Int?,
        val state: RoutineOccurrenceEntity.State,
        val satisfyingEntryID: UUID?,
    ) : LiveOccurrence

    /** One of today's logged doses, as the plan reads it. */
    data class EntrySnapshot(
        val id: UUID,
        val substance: String,
        val substanceUID: String?,
        val route: RouteOfAdministration,
        val timestamp: Instant,
    )

    /**
     * What one reconcile has to write. Empty on an unchanged day.
     *
     * Keyed by `row_id` throughout, because a plan is only ever applied back to
     * the rows it was built from — see [RoutineOccurrenceDao] on why nothing else
     * holds one.
     */
    class Plan {

        /** One occurrence to create. */
        data class NewOccurrence(
            val substance: String,
            val substanceUID: String?,
            val route: RouteOfAdministration,
            val slotMinutes: Int?,
            val state: RoutineOccurrenceEntity.State,
            val satisfyingEntryID: UUID?,
        ) {
            /** The row this materializes into, on `dueDay`. */
            fun toEntity(dueDay: Date): RoutineOccurrenceEntity = RoutineOccurrenceEntity(
                substance = substance,
                substanceUID = substanceUID,
                routeRaw = route.wireValue,
                dueDay = dueDay,
                stateRaw = state.wireValue,
                satisfyingEntryID = satisfyingEntryID,
                slotMinutes = slotMinutes,
            )
        }

        /** A settled state: what it is, and which dose put it there. */
        data class Outcome(
            val state: RoutineOccurrenceEntity.State,
            val satisfyingEntryID: UUID?,
        )

        /** Past-day pendings that become `missed`. */
        val expire: MutableList<Long> = ArrayList()

        val inserts: MutableList<NewOccurrence> = ArrayList()

        /** Today's pendings whose (med x slot) is no longer due. */
        val deletes: MutableList<Long> = ArrayList()

        /** Existing occurrences whose match outcome changed. */
        val updates: MutableMap<Long, Outcome> = LinkedHashMap()

        val isEmpty: Boolean
            get() = expire.isEmpty() && inserts.isEmpty() && deletes.isEmpty() && updates.isEmpty()
    }

    companion object {

        /**
         * The stable key of one (med x slot): identity (uid when resolved, else
         * lowercased name) + route + slot minutes.
         *
         * Shared by the reminder scheduler and the Skip Today action so both
         * sides agree on which occurrence a notification is about. A second
         * implementation is how "a logged dose does not suppress the re-ask"
         * arrives: the keys would disagree by a route's spelling and the gate
         * would silently stop matching.
         */
        fun slotKey(
            substance: String,
            substanceUID: String?,
            route: RouteOfAdministration,
            slotMinutes: Int?,
        ): String {
            val identity = if (!substanceUID.isNullOrEmpty()) substanceUID else substance.lowercase()
            return "$identity|${route.wireValue}|${slotMinutes ?: "any"}"
        }

        /** [slotKey] for a stored occurrence. */
        fun slotKey(occurrence: RoutineOccurrenceEntity): String = slotKey(
            substance = occurrence.substance,
            substanceUID = occurrence.substanceUID,
            route = occurrence.route,
            slotMinutes = occurrence.slotMinutes,
        )

        /**
         * One item's slots for `day`, or null when it has none: as-needed meds
         * carry no expectation, and an off-schedule day has no slots to
         * materialize. Pure, so the due-day rules are a JVM spec rather than a
         * device test.
         */
        fun itemSnapshot(item: DailyDoseItemEntity, day: Instant, zone: ZoneId): ItemSnapshot? {
            if (item.isAsNeeded) return null
            val due = AdherenceCalculator.isDue(
                startDate = item.startDate.toInstant(),
                frequency = item.frequency,
                frequencyDays = item.frequencyDays,
                on = day,
                zone = zone,
            )
            if (!due) return null
            val times = item.reminderTimesMinutes.sorted()
            return ItemSnapshot(
                substance = item.substance,
                substanceUID = item.substanceUID,
                route = item.route,
                // A med with no set times still expects one dose a day; it just has
                // no time to expect it at, which is the `null` "anytime" slot.
                slots = if (times.isEmpty()) listOf(null) else times,
            )
        }

        /**
         * The pure reconcile: which occurrences to create, drop, expire, and
         * which match outcomes changed.
         *
         * The matching rules run as a batch over today's entries in timestamp
         * order: identity (uid-first, else name) + route, one claim per entry,
         * nearest slot time on a tie. Unclaimed occurrences revert to `pending`,
         * which is the whole delete/edit reconciliation — nothing has to notice a
         * deletion, because the next pass simply finds nothing claiming the slot.
         */
        fun plan(
            dueItems: List<ItemSnapshot>,
            occurrences: List<OccurrenceSnapshot>,
            expired: List<Long>,
            entries: List<EntrySnapshot>,
            zone: ZoneId,
        ): Plan {
            val plan = Plan()
            plan.expire += expired

            // Today's live set: the existing rows that still correspond to a due
            // slot (or are settled history), plus a row for every due slot without
            // one. Live rows are positional — `assignment` is keyed by index.
            val live = ArrayList<Live>()
            for (occurrence in occurrences) {
                val stillDue = dueItems.any { item ->
                    item.slots.any { slot -> corresponds(occurrence, item, slot) }
                }
                // Only a pending row is dropped: a logged or skipped one is the
                // day's history, and re-deriving must not erase what happened just
                // because the schedule changed under it.
                if (occurrence.state == RoutineOccurrenceEntity.State.PENDING && !stillDue) {
                    plan.deletes += occurrence.rowId
                } else {
                    live += Live(
                        substance = occurrence.substance,
                        substanceUID = occurrence.substanceUID,
                        route = occurrence.route,
                        slotMinutes = occurrence.slotMinutes,
                        state = occurrence.state,
                        existing = occurrence,
                    )
                }
            }
            for (item in dueItems) {
                for (slot in item.slots) {
                    if (live.any { corresponds(it, item, slot) }) continue
                    live += Live(
                        substance = item.substance,
                        substanceUID = item.substanceUID,
                        route = item.route,
                        slotMinutes = slot,
                        state = RoutineOccurrenceEntity.State.PENDING,
                        existing = null,
                    )
                }
            }

            val claimed = HashSet<Int>()
            val assignment = HashMap<Int, UUID>()
            for (entry in entries.sortedBy { it.timestamp }) {
                val entryMinutes = minutesOfDay(entry.timestamp, zone)
                val best = live.indices
                    .filter { live[it].state != RoutineOccurrenceEntity.State.SKIPPED }
                    .filter { !claimed.contains(it) }
                    .filter { matches(entry, live[it]) }
                    // `minByOrNull` keeps the first minimum on a tie, which is the
                    // earliest slot in `live` order — an arbitrary but stable
                    // choice the outcome does not depend on, since both candidates
                    // end up logging whichever dose is nearest.
                    .minByOrNull { distance(entryMinutes, live[it].slotMinutes) }
                    ?: continue
                claimed += best
                assignment[best] = entry.id
            }

            for ((index, row) in live.withIndex()) {
                val outcome = when {
                    // A user's skip is not a match outcome. It survives the pass,
                    // and it survives a dose arriving afterwards: the re-ask was
                    // dismissed, and logging the dose does not un-dismiss it.
                    row.state == RoutineOccurrenceEntity.State.SKIPPED ->
                        Plan.Outcome(RoutineOccurrenceEntity.State.SKIPPED, row.existing?.satisfyingEntryID)

                    assignment.containsKey(index) ->
                        Plan.Outcome(RoutineOccurrenceEntity.State.LOGGED, assignment[index])

                    else -> Plan.Outcome(RoutineOccurrenceEntity.State.PENDING, null)
                }
                val existing = row.existing
                if (existing == null) {
                    plan.inserts += Plan.NewOccurrence(
                        substance = row.substance,
                        substanceUID = row.substanceUID,
                        route = row.route,
                        slotMinutes = row.slotMinutes,
                        state = outcome.state,
                        satisfyingEntryID = outcome.satisfyingEntryID,
                    )
                } else if (existing.state != outcome.state || existing.satisfyingEntryID != outcome.satisfyingEntryID) {
                    plan.updates[existing.rowId] = outcome
                }
            }
            return plan
        }

        // MARK: - Joins

        /**
         * A row the plan is reasoning about: either one that exists, or one about
         * to be created. Upstream models this with a protocol so the joins below
         * can take both; a private record does it here.
         */
        private class Live(
            override val substance: String,
            override val substanceUID: String?,
            override val route: RouteOfAdministration,
            override val slotMinutes: Int?,
            val state: RoutineOccurrenceEntity.State,
            val existing: OccurrenceSnapshot?,
        ) : LiveOccurrence

        /** Whether a live row is a due item's slot: identity + route + slot. */
        private fun corresponds(row: LiveOccurrence, item: ItemSnapshot, slot: Int?): Boolean =
            row.slotMinutes == slot &&
                row.route == item.route &&
                identityMatches(row.substanceUID, row.substance, item.substanceUID, item.substance)

        /**
         * Whether a dose satisfies a slot: identity and route.
         *
         * Deliberately not the time of day — the nearest-slot claim above is what
         * decides which of several matching slots a dose is for. Identity runs
         * through [AdherenceCalculator.identityMatches], the same join the
         * quick-log chips and the adherence calendar use, so the checklist and the
         * Log sheet cannot disagree about what has been taken today.
         */
        private fun matches(entry: EntrySnapshot, row: LiveOccurrence): Boolean =
            entry.route == row.route &&
                identityMatches(entry.substanceUID, entry.substance, row.substanceUID, row.substance)

        private fun identityMatches(keyA: String?, nameA: String, keyB: String?, nameB: String): Boolean =
            AdherenceCalculator.identityMatches(keyA = keyA, nameA = nameA, keyB = keyB, nameB = nameB)

        private fun minutesOfDay(instant: Instant, zone: ZoneId): Int {
            val time = instant.atZone(zone).toLocalTime()
            return time.hour * 60 + time.minute
        }

        /**
         * How far a dose is from a slot, in minutes. An "anytime" slot has no time
         * to be near, so it sorts after every timed slot and is claimed last —
         * which is what makes a timed med's dose prefer its own slot over the
         * untimed one it would otherwise also match.
         */
        private fun distance(minutes: Int, slotMinutes: Int?): Int =
            if (slotMinutes == null) Int.MAX_VALUE else abs(minutes - slotMinutes)
    }
}

/** `startOfDay` in `zone` — the day the occurrence table is keyed by. */
private fun startOfDay(instant: Instant, zone: ZoneId): Instant =
    instant.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

/**
 * The identity fields the matching joins read, shared by an existing occurrence
 * and one the plan is about to create — upstream's `LiveOccurrence` protocol.
 *
 * A protocol rather than a parameter list because the two joins that take it
 * ([RoutineOccurrenceService.plan]'s `corresponds` and `matches`) are the rules
 * the whole table is derived by, and a rule that had to be told which of the two
 * shapes it was looking at would be two rules.
 */
interface LiveOccurrence {
    val substance: String
    val substanceUID: String?
    val route: RouteOfAdministration
    val slotMinutes: Int?
}
