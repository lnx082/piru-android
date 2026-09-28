package glass.kagerou.piru.ui.meds

import android.content.SharedPreferences
import java.time.LocalDate
import kotlin.math.floor

/** The preferences file the dismissed missed-notice day keys live in. */
private const val MEDS_PREFS = "piru_meds"

/** The [SharedPreferences] the dismissal set is stored in, opened once per call site. */
internal fun medsPreferences(context: android.content.Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(MEDS_PREFS, android.content.Context.MODE_PRIVATE)

/**
 * One trailing info line under the My Meds card's slot rows — a fact worth a
 * glance, never an instruction (`Specs/journal-home-rework.md` §3).
 *
 * Ported from the `MyMedsInfoLine` enum and `MissedYesterdayNotice` struct in
 * `Piru/Views/Journal/DailyDose/MyMedsInfo.swift` (lines 1-26). Swift's
 * `enum` with associated values becomes a sealed interface, which is the same
 * exhaustive-`when` shape.
 */
sealed interface MedsInfoLine {

    /** A tracked supply projected to run out inside [MyMedsInfo.RESTOCK_HORIZON_DAYS]. */
    data class Restock(val name: String, val daysLeft: Int, val itemId: String) : MedsInfoLine

    /** The next timed slot today while nothing is due right now. */
    data class NextDue(val name: String, val minutes: Int) : MedsInfoLine

    /** A slot that ended yesterday still unlogged — neutral history, dismissible. */
    data class MissedYesterday(val notice: MissedYesterdayNotice) : MedsInfoLine
}

/**
 * What yesterday's `missed` occurrences add up to. [slotMinutes] names the slot
 * when exactly one was missed; [count] > 1 collapses them.
 */
data class MissedYesterdayNotice(
    val names: List<String>,
    val slotMinutes: Int?,
    val count: Int,
    /** [MissedNoticeDismissals.dayKey] of the missed day. */
    val dayKey: String,
) {
    /** The first name, for the single-med phrasing. */
    val name: String get() = names.firstOrNull() ?: ""
}

/**
 * The pure selection behind the card's info lines: which candidates exist and
 * which two of them show. Every input is a value so the choice is testable
 * without a store.
 *
 * Ported from `Piru/Views/Journal/DailyDose/MyMedsInfo.swift` (lines 31-107).
 */
object MyMedsInfo {

    /** At most this many lines under the slot rows. */
    const val MAX_LINES: Int = 2

    /** A supply projected to last fewer days than this earns a restock line. */
    const val RESTOCK_HORIZON_DAYS: Double = 14.0

    /** One tracked supply's projection, as the inventory arithmetic reports it. */
    data class SupplyProjection(
        val name: String,
        val daysLeft: Double,
        /** The inventory row's id, as a string — it is a route payload. */
        val itemId: String,
    )

    /** One checklist slot reduced to what next-due needs. */
    data class SlotSummary(
        val name: String,
        val minutes: Int?,
        val pending: Boolean,
    )

    /**
     * Priority order restock → next due → missed yesterday, capped at
     * [MAX_LINES]. A dismissed missed notice is dropped before capping.
     */
    fun select(
        restock: MedsInfoLine?,
        nextDue: MedsInfoLine?,
        missed: MedsInfoLine?,
        missedDismissed: Boolean,
    ): List<MedsInfoLine> {
        val lines = ArrayList<MedsInfoLine>(3)
        if (restock != null) lines += restock
        if (nextDue != null) lines += nextDue
        if (missed != null && !missedDismissed) lines += missed
        return lines.take(MAX_LINES)
    }

    /**
     * The soonest-to-run-out supply under the horizon, or null when every
     * tracked med has more than two weeks left. Days are floored so "6 days
     * left" never promises a seventh.
     */
    fun restock(projections: List<SupplyProjection>): MedsInfoLine? {
        val soonest = projections
            .filter { it.daysLeft < RESTOCK_HORIZON_DAYS }
            .minByOrNull { it.daysLeft }
            ?: return null
        return MedsInfoLine.Restock(
            name = soonest.name,
            daysLeft = maxOf(0, floor(soonest.daysLeft).toInt()),
            itemId = soonest.itemId,
        )
    }

    /**
     * The earliest pending timed slot still ahead of [nowMinutes], only while no
     * pending slot has already come due — a due slot is the rows' job, and the
     * line would otherwise point past it.
     */
    fun nextDue(slots: List<SlotSummary>, nowMinutes: Int): MedsInfoLine? {
        val pending = slots.filter { it.pending }
        val dueNow = pending.any { (it.minutes ?: 0) <= nowMinutes }
        if (dueNow) return null
        val next = pending
            .mapNotNull { slot -> slot.minutes?.let { slot.name to it } }
            .filter { it.second > nowMinutes }
            .minByOrNull { it.second }
            ?: return null
        return MedsInfoLine.NextDue(name = next.first, minutes = next.second)
    }

    /**
     * Yesterday's missed slots folded into one notice, keyed by the missed day
     * so a dismissal outlives relaunches but expires with the day.
     */
    fun missedYesterday(missed: List<Pair<String, Int?>>, yesterday: LocalDate): MedsInfoLine? {
        val first = missed.firstOrNull() ?: return null
        return MedsInfoLine.MissedYesterday(
            MissedYesterdayNotice(
                names = missed.map { it.first },
                slotMinutes = if (missed.size == 1) first.second else null,
                count = missed.size,
                dayKey = MissedNoticeDismissals.dayKey(yesterday),
            ),
        )
    }
}

/**
 * The ✕ on a missed-yesterday line, remembered per missed day so the notice
 * never returns for that day. Stored as a small set of ISO day keys in the
 * app's own preferences file, pruned so it never grows past a month of
 * dismissals.
 *
 * Ported from `MissedNoticeDismissals` in `MyMedsInfo.swift` (lines 112-136).
 * Upstream writes the app-group defaults; this build has no app group, so it is
 * a private `SharedPreferences` file — the same lifetime for the one reader.
 */
object MissedNoticeDismissals {

    const val DEFAULTS_KEY: String = "myMedsMissedNoticeDismissedDays"
    const val RETAINED_COUNT: Int = 31

    /**
     * "2026-08-31" — the same key on every launch regardless of when in the day
     * it is computed, which is what makes a dismissal survive a relaunch but not
     * a new day.
     */
    fun dayKey(date: LocalDate): String =
        String.format(
            java.util.Locale.ROOT,
            "%04d-%02d-%02d",
            date.year,
            date.monthValue,
            date.dayOfMonth,
        )

    fun isDismissed(dayKey: String, preferences: SharedPreferences): Boolean =
        storedKeys(preferences).contains(dayKey)

    fun dismiss(dayKey: String, preferences: SharedPreferences) {
        val keys = storedKeys(preferences)
        if (keys.contains(dayKey)) return
        keys += dayKey
        // Pruned oldest-first, which is upstream's `removeFirst(count - retained)`.
        // Kept as an ordered list rather than a `StringSet` precisely because the
        // prune has to know which end is old; a set would keep an arbitrary 31
        // and the cap would stop meaning "a month of dismissals".
        if (keys.size > RETAINED_COUNT) keys.subList(0, keys.size - RETAINED_COUNT).clear()
        preferences.edit().putString(DEFAULTS_KEY, keys.joinToString("\n")).apply()
    }

    /** The stored keys, newest last. Empty for an absent or unreadable entry. */
    private fun storedKeys(preferences: SharedPreferences): MutableList<String> =
        preferences.getString(DEFAULTS_KEY, null)
            ?.split("\n")
            ?.filter { it.isNotEmpty() }
            ?.toMutableList()
            ?: mutableListOf()
}
