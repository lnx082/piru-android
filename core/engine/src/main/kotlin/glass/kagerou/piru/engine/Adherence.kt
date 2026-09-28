/*
 * The adherence arithmetic: which scheduled doses a day expects, which logged
 * doses satisfy them, and how a day reads once the two are compared.
 *
 * Ported from `Piru/Utilities/AdherenceCalculator.swift` (its `AdherenceStatus`,
 * `ItemAdherence`, `DayAdherence` and `AdherenceCalculator` types) together with
 * the scheduling core it delegates to, `Shared/MedSchedule.swift`. The month
 * roll-up that upstream keeps in the Insights layer
 * (`Piru/Views/Insights/AdherenceModel.swift`, `InsightsView.swift`) is ported
 * here as well, so the day arithmetic and the sum over days stay in one place.
 *
 * Two things differ from the Swift original, both deliberate:
 *
 * - Inputs are plain values ([AdherenceEntry], [AdherenceItem]) rather than
 *   SwiftData models — the Sendable-snapshot shape upstream already keeps for
 *   its off-main year scan. The store maps its rows onto these, and the math
 *   stays in a pure-JVM module with no database and no main actor.
 * - Every calendar computation takes the [ZoneId] it should be read in.
 *   Upstream reads `Calendar.current`, which is correct for an app that lives
 *   in one zone and untestable everywhere else; here the zone is an argument,
 *   so a test can pin it and a future caller can score a travelled day in the
 *   zone it was lived in.
 */

package glass.kagerou.piru.engine

import glass.kagerou.piru.model.DoseFrequency
import glass.kagerou.piru.model.RouteOfAdministration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * How a day's scheduled doses stand against what was actually logged.
 *
 * The declaration order is the upstream order (`complete`, `partial`, `missed`,
 * `noData`) and is kept even though nothing here sorts by it: Kotlin enums take
 * their `compareTo` from the declaration order and it cannot be overridden, so
 * reordering these later would silently change any comparison a consumer adds.
 */
enum class AdherenceStatus {
    Complete,
    Partial,
    Missed,
    NoData,
}

/**
 * One logged dose, narrowed to the fields adherence reads.
 *
 * [identityKey] is the substance's resolved identity (upstream `substanceUID`
 * resolved through the catalog). It is what lets a dose logged under a brand
 * name credit a med scheduled under its generic name — see
 * [AdherenceCalculator.matches] for the join rule, including why an empty key
 * falls back to the name.
 */
data class AdherenceEntry(
    val substance: String,
    val identityKey: String = "",
    val route: RouteOfAdministration,
    val timestamp: Instant,
)

/**
 * One scheduled item, narrowed to the fields adherence reads.
 *
 * [isAsNeeded] is carried rather than filtered by the caller because "never
 * counts" is a property of the item, and an item that drops out of the
 * denominator entirely (rather than contributing zero) is exactly the
 * distinction the caller cannot make for us.
 *
 * [reminderTimesMinutes] is the per-day reminder ladder in minutes from
 * midnight; its size is how many dose slots the item expects per due day (see
 * [AdherenceCalculator.dayAdherence]) and its values place those slots either
 * side of the noon seam.
 */
data class AdherenceItem(
    val substance: String,
    val identityKey: String = "",
    val route: RouteOfAdministration,
    val isAsNeeded: Boolean = false,
    val startDate: Instant,
    val frequency: DoseFrequency,
    val frequencyDays: List<Int> = emptyList(),
    val reminderTimesMinutes: List<Int> = emptyList(),

    /** Display order within the day's list; part of [ItemAdherence.id]. */
    val sortOrder: Int = 0,
)

/**
 * One item's standing for one day.
 *
 * [takenCount] is clamped to [totalCount]: three doses logged against a
 * one-slot med is still one slot taken, not a day 300% complete.
 */
data class ItemAdherence(
    val item: AdherenceItem,
    val takenCount: Int,
    val totalCount: Int,
) {
    /** Every expected slot for this item is satisfied. */
    val taken: Boolean get() = takenCount >= totalCount

    /** Stable identity for a list row, matching upstream's `substance + sortOrder`. */
    val id: String get() = item.substance + item.sortOrder.toString()
}

/**
 * One day's adherence.
 *
 * [date] is the date the caller asked about, unchanged — a day's own label, not
 * a normalised midnight, because the month roll-up filters on it (see
 * [AdherenceCalculator.monthSummary]) and callers are expected to pass a day
 * boundary.
 */
data class DayAdherence(
    val date: Instant,
    val status: AdherenceStatus,
    val takenCount: Int,
    val totalCount: Int,
    val items: List<ItemAdherence>,
    /**
     * The day split at noon, or null when the split would say nothing: a routine
     * that lives entirely in one half of the day, or one whose meds carry no
     * reminder times to place them by.
     *
     * A two-a-day routine turns on *which* half went missing, which a single
     * circle cannot say, so the halves are tallied and carried separately rather
     * than derived from the headline count.
     */
    val halves: Halves? = null,
) {
    /**
     * A day counted per half-day. One side can be complete while the other is
     * missed, and the kept halves stay `Complete`/`Partial`/`Missed` — never
     * `NoData`, since a half exists only when something is due in it.
     */
    data class Halves(
        val morningTaken: Int,
        val morningTotal: Int,
        val eveningTaken: Int,
        val eveningTotal: Int,
    ) {
        val morning: AdherenceStatus get() = statusOf(morningTaken, morningTotal)
        val evening: AdherenceStatus get() = statusOf(eveningTaken, eveningTotal)

        companion object {
            /** The same four-way reading a whole day gets, applied to one half. */
            fun statusOf(taken: Int, total: Int): AdherenceStatus = when {
                total == 0 -> AdherenceStatus.NoData
                taken >= total -> AdherenceStatus.Complete
                taken > 0 -> AdherenceStatus.Partial
                else -> AdherenceStatus.Missed
            }
        }
    }
}

/** One day reduced to what a streak walk reads. */
data class DayStatus(val date: Instant, val status: AdherenceStatus)

/**
 * A month's scheduled doses taken and due, over the days that can honestly be
 * counted. [hasData] is false when nothing was due yet — an empty month reads
 * as "nothing to report", not as zero percent.
 */
data class MonthAdherence(val taken: Int, val due: Int, val hasData: Boolean)

/**
 * The adherence calculations, all pure: give them instants, items and a zone,
 * get a reading back. No store, no clock, no main actor.
 */
object AdherenceCalculator {

    /**
     * Noon, as minutes from midnight — the seam the day's halves are cut at.
     * A local clock hour, not a claim about anyone's morning.
     */
    const val NOON_MINUTES: Int = 12 * 60

    /**
     * Whether a logged dose satisfies a scheduled item: the substance identity
     * AND the route must match.
     *
     * The route gate is the load-bearing half. The same substance by another
     * route is deliberately a plain journal entry and not adherence credit — an
     * injected dose does not answer an oral prescription, and those are the
     * cases (stimulants, opioids) where treating it as answered would matter
     * most.
     *
     * Identity joins by [identityKey] with a case-insensitive name fallback, so
     * a resolved-id item still credits a legacy name-only dose and vice versa,
     * exactly as upstream's name join did.
     */
    fun matches(
        entryKey: String?, entryName: String, entryRoute: RouteOfAdministration,
        itemKey: String?, itemName: String, itemRoute: RouteOfAdministration,
    ): Boolean {
        if (entryRoute != itemRoute) return false
        return identityMatches(entryKey, entryName, itemKey, itemName)
    }

    /** [matches] over the value types, for callers holding whole records. */
    fun matches(entry: AdherenceEntry, item: AdherenceItem): Boolean = matches(
        entryKey = entry.identityKey, entryName = entry.substance, entryRoute = entry.route,
        itemKey = item.identityKey, itemName = item.substance, itemRoute = item.route,
    )

    /**
     * Whether two substance references name the same drug.
     *
     * Keys only join when **both** sides carry one: a dose logged before its
     * substance resolved has an empty key, and an empty key must never match
     * another empty key, or every unresolved dose would credit every unresolved
     * med. When the keys cannot speak, the names do — which is also what covers
     * a med whose id family was re-pinned after the dose was logged.
     */
    fun identityMatches(keyA: String?, nameA: String, keyB: String?, nameB: String): Boolean {
        if (keyA != null && keyB != null && keyA.isNotEmpty() && keyB.isNotEmpty() && keyA == keyB) return true
        return nameA.lowercase() == nameB.lowercase()
    }

    /**
     * Whether a scheduled item is due on `date`.
     *
     * Nothing is due before its start date, and every cadence is measured in
     * **calendar days of [zone]** from the start date's own day — not in
     * 24-hour spans, so a dose scheduled every other day stays every other day
     * across a daylight-saving change.
     */
    fun isDue(
        startDate: Instant,
        frequency: DoseFrequency,
        frequencyDays: List<Int>,
        on: Instant,
        zone: ZoneId,
    ): Boolean {
        val day = localDay(on, zone)
        val start = localDay(startDate, zone)

        // Not due before the prescription start date.
        if (day < start) return false

        return when (frequency) {
            DoseFrequency.DAILY -> true

            DoseFrequency.EVERY_OTHER_DAY -> ChronoUnit.DAYS.between(start, day) % 2 == 0L
            DoseFrequency.WEEKLY -> ChronoUnit.DAYS.between(start, day) % 7 == 0L
            DoseFrequency.BIWEEKLY -> ChronoUnit.DAYS.between(start, day) % 14 == 0L

            DoseFrequency.MONTHLY -> {
                val startDay = start.dayOfMonth
                val checkDay = day.dayOfMonth
                // Same day-of-month, accounting for shorter months.
                if (checkDay == startDay) true
                // A start day past the end of this month (start=31, February)
                // lands on the month's last day rather than skipping the month.
                else startDay > day.lengthOfMonth() && checkDay == day.lengthOfMonth()
            }

            DoseFrequency.SPECIFIC_DAYS -> frequencyDays.contains(foundationWeekday(day))
        }
    }

    /** [isDue] over the value type. */
    fun isDue(item: AdherenceItem, on: Instant, zone: ZoneId): Boolean =
        isDue(item.startDate, item.frequency, item.frequencyDays, on, zone)

    /**
     * One day's adherence over the doses logged and the items scheduled.
     *
     * Only items that are due and not as-needed reach the denominator. PRN meds
     * carry no expectation, so they are never counted and never marked missed —
     * an item that cannot be missed must not be able to make a day read missed.
     * With nothing due the day is [AdherenceStatus.NoData], which is also what
     * keeps a gap of off-days from breaking a streak.
     *
     * Each item expects `max(1, reminderTimesMinutes.size)` slots, so a
     * two-a-day med can read "1 of 2" on its own; doses beyond that are clamped
     * away rather than letting extra doses paper over a missed item.
     *
     * The halves are a second tally rather than a split of the first: an item
     * with no reminder times has a slot but no hour to file it under, and one
     * such item takes the whole day's split away without disturbing the
     * headline count.
     */
    fun dayAdherence(
        date: Instant,
        entries: List<AdherenceEntry>,
        items: List<AdherenceItem>,
        zone: ZoneId,
    ): DayAdherence {
        val day = localDay(date, zone)
        val dayStart = day.atStartOfDay(zone).toInstant()
        // The next calendar day's midnight, not `dayStart + 24h`: a 23- or
        // 25-hour day must still end where the next day begins.
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
        val dayEntries = entries.filter { it.timestamp >= dayStart && it.timestamp < dayEnd }

        val dueItems = items.filter { !it.isAsNeeded && isDue(it, on = date, zone = zone) }

        if (dueItems.isEmpty()) {
            return DayAdherence(
                date = date, status = AdherenceStatus.NoData,
                takenCount = 0, totalCount = 0, items = emptyList(),
            )
        }

        val itemResults = ArrayList<ItemAdherence>(dueItems.size)
        var matched = 0
        var expected = 0
        var morningTaken = 0
        var morningTotal = 0
        var eveningTaken = 0
        var eveningTotal = 0
        var splittable = true

        for (item in dueItems) {
            val itemExpected = maxOf(1, item.reminderTimesMinutes.size)
            val itemDoses = dayEntries.filter { matches(it, item) }
            val itemTaken = minOf(itemDoses.size, itemExpected)
            itemResults += ItemAdherence(item = item, takenCount = itemTaken, totalCount = itemExpected)
            matched += itemTaken
            expected += itemExpected

            val times = item.reminderTimesMinutes
            if (times.isEmpty()) {
                splittable = false
                continue
            }
            val morningSlots = times.count { it < NOON_MINUTES }
            val eveningSlots = times.size - morningSlots
            val morningHits = itemDoses.count { minutesOfDay(it.timestamp, zone) < NOON_MINUTES }
            morningTotal += morningSlots
            eveningTotal += eveningSlots
            morningTaken += minOf(morningHits, morningSlots)
            eveningTaken += minOf(itemDoses.size - morningHits, eveningSlots)
        }

        val status = when {
            matched == expected -> AdherenceStatus.Complete
            matched > 0 -> AdherenceStatus.Partial
            else -> AdherenceStatus.Missed
        }

        val halves = if (splittable && morningTotal > 0 && eveningTotal > 0) {
            DayAdherence.Halves(
                morningTaken = morningTaken, morningTotal = morningTotal,
                eveningTaken = eveningTaken, eveningTotal = eveningTotal,
            )
        } else {
            null
        }

        return DayAdherence(
            date = date, status = status, takenCount = matched, totalCount = expected,
            items = itemResults, halves = halves,
        )
    }

    /**
     * The month's days in calendar order, each scored by [dayAdherence].
     *
     * Every day of the month is produced, including days the item is not due on
     * (they read [AdherenceStatus.NoData]) and days that have not happened yet —
     * the two are told apart by the caller, since only the caller knows "now".
     */
    fun monthDays(
        month: Instant,
        entries: List<AdherenceEntry>,
        items: List<AdherenceItem>,
        zone: ZoneId,
    ): List<DayAdherence> {
        val first = localDay(month, zone).withDayOfMonth(1)
        val entriesByDay = entries.groupBy { localDay(it.timestamp, zone) }
        return (1..first.lengthOfMonth()).map { dayOfMonth ->
            val day = first.withDayOfMonth(dayOfMonth)
            dayAdherence(
                date = day.atStartOfDay(zone).toInstant(),
                entries = entriesByDay[day].orEmpty(),
                items = items,
                zone = zone,
            )
        }
    }

    /**
     * The month's doses taken and due over the days that can honestly carry a
     * count: days that had something due ([AdherenceStatus.NoData] excluded) and
     * that have already begun.
     *
     * **Today counts.** A day still in progress is scored with the doses logged
     * so far, so a morning med not yet taken pulls the month's ratio down
     * immediately — which is the point of the number, and why it is not "last
     * completed day". **Future days do not**, or the rest of the month would
     * read as one long miss.
     *
     * Takes no [ZoneId]: it compares absolute instants, which is zone-free.
     * Upstream's Insights model does the same with `$0.date <= .now`.
     */
    fun monthSummary(days: List<DayAdherence>, now: Instant): MonthAdherence {
        val actionable = days.filter { it.status != AdherenceStatus.NoData && it.date <= now }
        val due = actionable.sumOf { it.totalCount }
        val taken = actionable.sumOf { it.takenCount }
        return MonthAdherence(taken = taken, due = due, hasData = due > 0)
    }

    /** [monthSummary] over a month's entries and items in one call. */
    fun monthAdherence(
        month: Instant,
        entries: List<AdherenceEntry>,
        items: List<AdherenceItem>,
        now: Instant,
        zone: ZoneId,
    ): MonthAdherence = monthSummary(monthDays(month, entries, items, zone), now)

    /**
     * The current streak of kept days, walking backwards from today.
     *
     * A missed day breaks it and a completed or partial day extends it; days
     * with nothing due are not days off, they are days that were never asked
     * about, so a weekly prescription keeps a streak across its six off days —
     * but a missed day hiding in one of those gaps still breaks it, which is why
     * the walk re-examines every calendar day it steps over rather than trusting
     * the filtered list.
     *
     * Today is counted when it is already complete or partial, and ignored when
     * it is still missed: the day is not over, and a streak that flickered off
     * at 00:01 every morning would be worse than useless.
     */
    fun streak(days: List<DayStatus>, now: Instant, zone: ZoneId): Int {
        val today = localDay(now, zone).atStartOfDay(zone).toInstant()

        // Only days that had items due (skip NoData days), newest first.
        val actionable = days
            .filter { it.status != AdherenceStatus.NoData }
            .sortedByDescending { it.date }
        val past = actionable.filter { it.date < today }

        var streak = 0
        var cursor = today

        for (day in past) {
            val dayStart = localDay(day.date, zone).atStartOfDay(zone).toInstant()

            // Verify no missed day sits between the cursor and this day.
            var check = localDay(cursor, zone).minusDays(1).atStartOfDay(zone).toInstant()
            var gapOk = true
            while (check > dayStart) {
                if (days.firstOrNull { localDay(it.date, zone) == localDay(check, zone) }?.status == AdherenceStatus.Missed) {
                    gapOk = false
                    break
                }
                check = localDay(check, zone).minusDays(1).atStartOfDay(zone).toInstant()
            }
            if (!gapOk) break

            if (day.status != AdherenceStatus.Complete && day.status != AdherenceStatus.Partial) break
            streak += 1
            cursor = dayStart
        }

        val todayData = actionable.firstOrNull { localDay(it.date, zone) == localDay(today, zone) }
        if (todayData?.status == AdherenceStatus.Complete || todayData?.status == AdherenceStatus.Partial) {
            streak += 1
        }

        return streak
    }

    /** [streak] over whole days, for callers that already have them. */
    fun currentStreak(adherenceData: List<DayAdherence>, now: Instant, zone: ZoneId): Int =
        streak(adherenceData.map { DayStatus(it.date, it.status) }, now, zone)

    /** Minutes from local midnight in [zone] — the coordinate the noon seam is cut at. */
    private fun minutesOfDay(instant: Instant, zone: ZoneId): Int {
        val time = instant.atZone(zone).toLocalTime()
        return time.hour * 60 + time.minute
    }
}

/** The calendar day [instant] falls on, in [zone]. */
private fun localDay(instant: Instant, zone: ZoneId): LocalDate = instant.atZone(zone).toLocalDate()

/**
 * Foundation's weekday numbering (`1` = Sunday … `7` = Saturday) from a
 * [LocalDate]. Kept as Foundation's, not Kotlin's ISO Monday-first numbering,
 * because the stored `frequencyDays` are Foundation's and a schedule has to mean
 * the same thing on both platforms.
 */
private fun foundationWeekday(day: LocalDate): Int = day.dayOfWeek.value % 7 + 1
