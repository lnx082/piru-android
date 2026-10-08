package glass.kagerou.piru.ui.insights

import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.data.entity.SessionNoteEntity
import glass.kagerou.piru.ui.tools.EsterPKIndex
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * What the Insights hub shows inline, and which of its cards exist at all.
 *
 * ## Why a two-level hub rather than a list of links
 * Ported from `InsightsView`, which is Apple-Health-shaped: a few **large cards** that draw the headline figure
 * or graph inline and tap through to the full page, then a grid of **compact cards** for the pages that are
 * destinations rather than readings. The port had one flat list of eight rows, which is a menu — a reader had to
 * open a page to learn whether it had anything to say, and every page's empty state was reachable only by
 * opening it.
 *
 * The inline figure is the difference. It is also why this file exists separately: computing it needs the log,
 * the calendar and the ester index, and none of that belongs in a composable.
 *
 * ## The two gates
 * Two cards are **conditional in upstream**, and both are gates rather than empty states:
 *
 * - **Did it work?** appears once there is at least one note carrying a rating. A reader who never rates a dose
 *   should not see a page that compares ratings.
 * - **Hormone levels** appears only when an injectable ester has been logged. It is a depot-PK page; without a
 *   depot dose it has nothing to project, and it is a specialised page to put in front of everyone.
 *
 * Both are pure functions of their inputs, which is what makes them testable — and both are exactly the kind of
 * gate that quietly disappears when a screen is rewritten, because a card that shows unconditionally still
 * renders.
 */
internal object InsightsHub {

    /** How many days of history the Usage card's inline sparkline covers. */
    const val USAGE_SPARKLINE_DAYS: Int = 14

    /** How many active substances the Modeled Levels card lists before it summarises the rest. */
    const val ACTIVE_SUBSTANCES_SHOWN: Int = 3

    /**
     * The Usage card's inline figures.
     *
     * [dailyCounts] is one entry per day, oldest first, covering [USAGE_SPARKLINE_DAYS] — a **fixed-length**
     * series with zeros for the quiet days rather than a sparse list, because the bar chart draws the gaps: a
     * fortnight with two entries in it looks different from a fortnight with two entries and twelve blanks, and
     * only one of those is the truth.
     */
    data class UsageSummary(
        val total: Int,
        val averagePerDay: Double,
        val dailyCounts: List<Int>,
    ) {
        /** Whether there is anything to draw. Upstream shows its empty line instead of an empty chart. */
        val hasData: Boolean get() = total > 0
    }

    /**
     * The Adherence card's inline figures.
     *
     * A fraction and a month's worth of per-day status, which is what the mini calendar draws.
     */
    data class AdherenceSummary(
        val taken: Int,
        val due: Int,
        /** One cell per day of the month in calendar order, with `null` for padding and for future days. */
        val monthStatuses: List<DayStatus?>,
    ) {
        val hasData: Boolean get() = due > 0

        /** How a day went, in the three states the calendar colours. */
        enum class DayStatus { COMPLETE, PARTIAL, MISSED }
    }

    /**
     * Whether the "Did it work?" card exists.
     *
     * One rated note is enough. Upstream's predicate is `worked != nil`, so a note with any answer counts —
     * including a "no", which is the answer that page is most useful for.
     */
    fun hasRatedNotes(notes: List<SessionNoteEntity>): Boolean = notes.any { it.worked != null }

    /**
     * Counts per day over the last [days], oldest first and including today.
     *
     * Fixed length on purpose: see [UsageSummary.dailyCounts]. Days are cut at **local midnight**, not at the
     * configurable session boundary, because this is a chart of "entries recorded per day" and a reader counting
     * bars wants calendar days.
     */
    fun dailyCounts(
        entries: List<DoseEntryEntity>,
        now: Instant,
        zone: ZoneId,
        days: Int = USAGE_SPARKLINE_DAYS,
    ): List<Int> {
        if (days <= 0) return emptyList()
        val today = now.atZone(zone).toLocalDate()
        val first = today.minusDays((days - 1).toLong())
        val counts = IntArray(days)
        for (entry in entries) {
            val day = entry.timestamp.toInstant().atZone(zone).toLocalDate()
            // An array index rather than a map keyed by date: the window is fourteen days and the index is the
            // day's offset from the first, so an entry outside the window costs one comparison.
            val offset = ChronoUnit.DAYS.between(first, day).toInt()
            if (offset in 0 until days) counts[offset]++
        }
        return counts.toList()
    }

    /** The average per day over the same window the sparkline covers, which is what the card prints. */
    fun averagePerDay(
        entries: List<DoseEntryEntity>,
        now: Instant,
        zone: ZoneId,
        days: Int = USAGE_SPARKLINE_DAYS,
    ): Double {
        val counts = dailyCounts(entries, now, zone, days)
        if (counts.isEmpty()) return 0.0
        return counts.sum().toDouble() / counts.size
    }

    /**
     * Whether an injectable ester has ever been logged — the Hormone levels gate.
     *
     * Resolved through the ester index rather than by name, because an injectable is identified by its ester: the
     * index's `parentUID` is the logged substance's own PSID family, which is what [substanceUIDFor] answers.
     *
     * A substance the catalogue does not carry, or one with no family, cannot be an ester — so a missing UID is
     * a `false` rather than a guess. That is the safe direction for a page that projects serum levels.
     */
    fun hasInjectableEster(
        entries: List<DoseEntryEntity>,
        index: EsterPKIndex,
        substanceUIDFor: (String) -> String?,
    ): Boolean = entries.any { entry ->
        val uid = substanceUIDFor(entry.substance) ?: return@any false
        index.forParentUID(uid).isNotEmpty()
    }

    /**
     * The per-day statuses for [month], padded so the calendar grid lines up.
     *
     * `null` entries are the blank cells before the first of the month, so the caller does not have to know the
     * month's first weekday. A day **after** [today] is `null` as well: the grid draws future days as empty
     * rather than as missed, which would be a claim about a day that has not happened.
     */
    fun monthGrid(
        month: YearMonth,
        today: LocalDate,
        statusFor: (LocalDate) -> AdherenceSummary.DayStatus?,
    ): List<AdherenceSummary.DayStatus?> {
        val first = month.atDay(1)
        // Sunday-first, matching the app's own calendar helper's foundation weekday: `DayOfWeek.value` is
        // Monday-based, so Sunday (`7`) folds to zero.
        val leading = first.dayOfWeek.value % 7
        val out = ArrayList<AdherenceSummary.DayStatus?>(leading + month.lengthOfMonth())
        repeat(leading) { out.add(null) }
        for (day in 1..month.lengthOfMonth()) {
            val date = month.atDay(day)
            out.add(if (date.isAfter(today)) null else statusFor(date))
        }
        return out
    }
}
