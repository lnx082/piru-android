package glass.kagerou.piru.engine

import java.time.Instant
import java.time.ZoneId

/**
 * Where the user's day breaks, and which session a timestamp belongs to.
 *
 * Ported from `Shared/CalendarSessionDay.swift`.
 *
 * ## Why a day boundary at all
 * `startOfDay` always answers midnight, which is the wrong day for anyone who is
 * still up at 02:00: a dose logged then belongs to the evening they are still in,
 * not to the morning that has not started. Rolling the day at a configurable hour
 * (4 AM by default) keeps one night's entries in one session, which is what every
 * day-bucketed reading of the log — totals, adherence, patterns — then inherits.
 *
 * ## The hour comes from settings, and is validated here
 * The stored hour is user-facing configuration and can be anything: a value from an
 * older build, a hand-edited preference, a missing key on first launch. One rule
 * covers all of them — inside `0..12` it is used, outside it the default applies —
 * and it is applied to the stored value *and* to any hour passed in directly, so
 * there is exactly one place where the boundary is decided.
 *
 * ## Zone and duration, not wall-clock arithmetic
 * The zone is a parameter rather than the system default, because the caller may be
 * rendering a widget or a test in a fixed zone, and because an app that reads the
 * ambient default is untestable around a day break. Within a zone the boundary is
 * computed as *one instant plus N hours* — the same thing
 * `addingTimeInterval(hours * 3_600)` does upstream — which means on a
 * daylight-saving transition day the boundary lands an hour away from its nominal
 * wall-clock hour, exactly as it does on iOS.
 */
object SessionDay {

    /** Settings key holding the day-boundary hour. Upstream's `dayBoundaryHour`. */
    const val DAY_BOUNDARY_HOUR_KEY: String = "dayBoundaryHour"

    /** 4 AM: late enough that a long night stays in one session, early enough to be asleep. */
    const val DEFAULT_BOUNDARY_HOUR: Int = 4

    /**
     * The hours a user may choose.
     *
     * Capped at noon: a boundary later than that would put the middle of the day in
     * the previous day's session, which is not a day any reading of the log wants.
     */
    val BOUNDARY_HOUR_RANGE: IntRange = 0..12

    /** A day is a day: the end is always exactly 24 hours after the start. */
    private const val SECONDS_PER_DAY: Long = 86_400L

    /**
     * The boundary hour to actually use, given what was read from settings.
     *
     * Null covers "the key is absent", which is every first launch.
     */
    fun boundaryHour(stored: Int?): Int =
        if (stored != null && stored in BOUNDARY_HOUR_RANGE) stored else DEFAULT_BOUNDARY_HOUR

    /**
     * Start of the session day containing [date].
     *
     * Before the boundary hour the day starts on the **previous** calendar day, after
     * it on this one. At exactly the boundary hour it starts today, which is the
     * reading that makes the boundary belong to the day it opens rather than to the
     * one it closes.
     *
     * A boundary of 0 degenerates to midnight, the classic behaviour — that case is
     * why the comparison is `hour < cutoff` rather than `hour <= cutoff`.
     */
    fun sessionDayStart(
        date: Instant,
        zone: ZoneId,
        dayBoundaryHour: Int = DEFAULT_BOUNDARY_HOUR,
    ): Instant {
        val cutoff = boundaryHour(dayBoundaryHour)
        val local = date.atZone(zone)
        val midnight = local.toLocalDate().atStartOfDay(zone)
        // Seconds rather than hours: this is a duration on the instant line, which is
        // what the upstream `addingTimeInterval` adds and what a DST day needs to
        // behave the same way here.
        val boundaryToday = midnight.plusSeconds(cutoff * 3_600L)
        val start = if (local.hour < cutoff) boundaryToday.minusSeconds(SECONDS_PER_DAY) else boundaryToday
        return start.toInstant()
    }

    /** End of the session day containing [date] — the start of the next one. */
    fun sessionDayEnd(
        date: Instant,
        zone: ZoneId,
        dayBoundaryHour: Int = DEFAULT_BOUNDARY_HOUR,
    ): Instant = sessionDayStart(date, zone, dayBoundaryHour).plusSeconds(SECONDS_PER_DAY)
}
