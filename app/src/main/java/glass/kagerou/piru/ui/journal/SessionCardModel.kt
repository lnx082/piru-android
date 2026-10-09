package glass.kagerou.piru.ui.journal

import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.data.entity.SessionEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One session as the journal's list draws it: the derived text and the decisions, computed once.
 *
 * ## Why these live here rather than in the screen
 * Ported from `SessionCard`, whose own doc gives the reason: the text — the time label, the substance summary, the
 * dose count — is "formatted **once here** rather than on every `SessionCardView` body pass". Upstream also makes
 * the card `Equatable` so a re-publish can skip a body pass, which is the same concern one level down.
 *
 * In this port the derived text is a value object for a third reason: three of its rules are decisions rather
 * than formatting, and each is the kind of thing a rewrite drops silently.
 *
 * ## The three decisions
 * - **A maintenance session** — one whose doses are all background meds — is a *compact* row rather than a card.
 *   Drawing a full timeline for a scheduled tablet is noise. Upstream caches this predicate as a flag on the
 *   session row; this port has no such column, so the doses are asked directly.
 * - **The time label** is a single start time until the session spans a minute, and a range after that. A session
 *   with one dose at 22:00 should read "22:00", not "22:00 – 22:00".
 * - **The substance summary** names up to three and then counts the rest, and the count is of **display titles
 *   deduped case-insensitively** — two aliases of one drug are one substance to the reader, which is what makes
 *   "Concerta" and "Methylphenidate" not read as two.
 */
internal data class SessionCard(
    val sessionId: String,
    val isMaintenance: Boolean,
    /** The user's own title, or null. */
    val title: String?,
    /** The clock label: one time, or a start–end range. */
    val timeLabel: String,
    val substanceSummary: String,
    val doseCount: Int,
    val startDate: Instant,
) {
    /** Whether tapping the card has somewhere to go. A straggler with no session is not navigable. */
    val navigable: Boolean get() = sessionId.isNotEmpty()
}

/**
 * A day header plus the sessions that started that day — the unit the list renders as a section.
 *
 * It carries the **date** and not a heading. A heading is three resources and a date pattern, and the pattern has
 * to come from somewhere locale-aware: the locale localizes the month and weekday names a pattern produces but not
 * their order. The screen already has a helper for exactly that, and this model has no business owning a second
 * one — two implementations of a day heading would eventually disagree about the pattern.
 */
internal data class SessionDay(
    val date: java.time.LocalDate,
    val sessions: List<SessionCard>,
)

/** How many substance names the summary lists before it counts the rest. */
internal const val SUBSTANCE_SUMMARY_LIMIT: Int = 3

/**
 * Builds a card for one session.
 *
 * [displayTitleFor] resolves a logged name to the catalogue's display title, and is a parameter rather than a
 * catalogue read: a card is built per session per recomposition, and resolving through the catalogue here would be
 * one lookup per dose per frame. The journal already has the catalogue open.
 */
internal fun sessionCard(
    session: SessionEntity?,
    entries: List<DoseEntryEntity>,
    zone: ZoneId,
    displayTitleFor: (String) -> String,
): SessionCard {
    val start = entries.minByOrNull { it.timestamp.time }?.timestamp?.toInstant()
        ?: session?.startDate?.toInstant()
        ?: Instant.now()
    val end = entries.maxByOrNull { it.timestamp.time }?.timestamp?.toInstant()

    // A single start time until the session spans a minute, then a range. Upstream's rule is the same
    // sixty-second threshold: two doses a second apart did not happen over a span, and "22:00 – 22:00" is noise.
    val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    val timeLabel = if (end != null && !end.isBefore(start.plusSeconds(60))) {
        val from = start.atZone(zone).format(formatter)
        val to = end.atZone(zone).format(formatter)
        "$from – $to"
    } else {
        start.atZone(zone).format(formatter)
    }

    // Maintenance: every dose is a background med. Upstream keeps the same predicate as a flag on the session row
    // and falls back to asking the doses for an ungrouped straggler; this port has no such column, so the doses
    // are the only authority — which is the right one anyway, since the flag upstream is a cache of exactly this.
    //
    // A session with no doses is not maintenance: there is nothing scheduled about it, and calling it so would
    // draw an empty compact row.
    val isMaintenance = entries.isNotEmpty() && entries.all { it.isBackgroundMed }

    // Display titles, deduped case-insensitively, in first-seen order. Two aliases of one drug collapse to one
    // entry, which a name-keyed dedup would not do.
    val seen = LinkedHashMap<String, String>()
    for (entry in entries) {
        val shown = displayTitleFor(entry.substance)
        seen.putIfAbsent(shown.lowercase(), shown)
    }
    val display = seen.values.toList()
    val substanceSummary = if (display.size <= SUBSTANCE_SUMMARY_LIMIT) {
        display.joinToString(", ")
    } else {
        display.take(SUBSTANCE_SUMMARY_LIMIT).joinToString(", ") + " +" + (display.size - SUBSTANCE_SUMMARY_LIMIT)
    }

    return SessionCard(
        sessionId = session?.id?.toString().orEmpty(),
        isMaintenance = isMaintenance,
        title = session?.title,
        timeLabel = timeLabel,
        substanceSummary = substanceSummary,
        doseCount = entries.size,
        startDate = start,
    )
}

/**
 * Groups a log into days of sessions, newest day first and newest session first.
 *
 * Sessions come from the doses' own `sessionId` rather than from a session query, because a dose whose session is
 * missing still belongs on the screen: the alternative is a log that silently drops entries. A dose with no
 * `sessionId` becomes its own single-dose card, which is what upstream does with a straggler — and the card is
 * non-navigable, because there is no session to open.
 */
/**
 * The journal's list grouped **by day**, with each day's sessions as sections inside it — the continuous timeline.
 *
 * ## How it differs from [sessionDays], and the case that shows it
 * [sessionDays] builds one card per session and dates it by the session's **start**. This builds cards per day and
 * dates each by the doses it actually holds, so a session that crosses midnight becomes **two** cards — the doses taken
 * before midnight under that day, the ones after under the next.
 *
 * That is the whole difference, and it is the reason this exists: under the session grouping a dose at 00:10 is filed
 * under the previous day, so "yesterday" and "today" disagree with the clock and with [glass.kagerou.piru.data.SessionDay]'s
 * own boundary, which the rest of the app uses.
 *
 * ## The one thing that stays the same
 * A dose with no `sessionId` is still its own single-dose card, because a log that silently drops entries is worse than
 * a card with nothing to open. The *splitting* is on the day, not on whether a session exists.
 */
internal fun sessionDaysByDay(
    entries: List<DoseEntryEntity>,
    sessions: Map<String, SessionEntity>,
    zone: ZoneId,
    today: java.time.LocalDate,
    displayTitleFor: (String) -> String,
): List<SessionDay> {
    // Grouped by the **dose's own day first**, so no dose can be filed under a day it was not taken on. Within a day,
    // sessions are the sections — and a session split by midnight therefore appears in both days, which is correct
    // rather than a duplication: the doses themselves are each in exactly one place.
    val byDay = entries.groupBy { it.timestamp.toInstant().atZone(zone).toLocalDate() }
    return byDay.entries
        .sortedByDescending { it.key }
        .map { (day, doses) ->
            val bySession = doses.groupBy { it.sessionId?.toString().orEmpty() }
            SessionDay(
                date = day,
                sessions = bySession
                    .map { (sessionId, sessionDoses) ->
                        sessionCard(sessions[sessionId], sessionDoses, zone, displayTitleFor)
                    }
                    .sortedByDescending { it.startDate },
            )
        }
}

internal fun sessionDays(
    entries: List<DoseEntryEntity>,
    sessions: Map<String, SessionEntity>,
    zone: ZoneId,
    today: java.time.LocalDate,
    displayTitleFor: (String) -> String,
): List<SessionDay> {
    val bySession = entries.groupBy { it.sessionId?.toString().orEmpty() }
    val cards = bySession.map { (sessionId, doses) ->
        sessionCard(sessions[sessionId], doses, zone, displayTitleFor)
    }
    val byDay = cards.groupBy { it.startDate.atZone(zone).toLocalDate() }
    return byDay.entries
        .sortedByDescending { it.key }
        .map { (day, dayCards) ->
            SessionDay(
                date = day,
                sessions = dayCards.sortedByDescending { it.startDate },
            )
        }
}
