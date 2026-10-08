package glass.kagerou.piru.ui.journal

import kotlin.math.abs

/**
 * What else was going on around one dose.
 *
 * Ported from `EntryContextSection`, and the question it answers is the one a single entry cannot: a dose read on
 * its own page looks like the only thing that happened, when in practice it was taken into a body that already had
 * something in it.
 *
 * ## Why the window is derived rather than fixed
 * The interesting neighbours are the doses whose **own** time course overlaps this one, and that window is the
 * substance's duration — six hours for one compound, sixteen for another. A fixed window would either miss a long
 * substance's overlap or invent one for a short substance, and both are wrong in the same silent way: the list
 * would still look plausible.
 *
 * ## The two cases that are not "no context"
 * - **This substance has no duration.** Then **nothing can be said about overlap at all**. Falling back to a fixed
 *   window would be inventing a time course, so every neighbour comes back with `overlaps = false` and the card's
 *   wording changes. "Nothing overlapped" and "this cannot be determined" are different statements, and a screen
 *   that prints the first when it means the second is lying quietly.
 * - **A neighbour has no duration.** It cannot be shown to overlap, but it also cannot be shown *not* to. It is
 *   still listed, because a dose logged twenty minutes earlier is a fact about the session whatever its profile.
 */
internal object EntryContext {

    /** A dose that might be a neighbour. */
    data class Candidate(
        val rowId: Long,
        val substance: String,
        /** Minutes from the entry being read. Negative for earlier. */
        val offsetMinutes: Long,
        /** Whether the candidate is in the same session as the entry. */
        val sameSession: Boolean,
    )

    /** One other dose, and why it is in the list. */
    data class Neighbour(
        val rowId: Long,
        val substance: String,
        val offsetMinutes: Long,
        /**
         * Whether the neighbour's course is shown to overlap this dose's window.
         *
         * False for a dose outside the window, and false for one that cannot be judged. [judged] is what tells the
         * two apart — which is why the flag is carried rather than the list being pre-filtered.
         */
        val overlaps: Boolean,
        val sameSession: Boolean,
    )

    /** The most neighbours shown. Past a handful the card is a second copy of the day's list. */
    const val MAXIMUM: Int = 6

    /**
     * The doses to show, nearest first.
     *
     * [windowMinutes] is this dose's own duration, or null when the catalogue has none — in which case nothing can
     * be judged, and [judged] on the result is false so the card can say so rather than implying it looked.
     */
    fun neighbours(
        entrySubstance: String,
        windowMinutes: Double?,
        candidates: List<Candidate>,
    ): Result {
        // Half the window on each side, which is the symmetric reading: a dose taken an hour before this one with
        // a six-hour course is still present, and so is one taken an hour after.
        val halfWindow = windowMinutes?.takeIf { it > 0 }?.div(2.0)

        val neighbours = candidates
            // The same substance at the same moment is this dose, in whatever list the caller assembled.
            .filterNot { it.offsetMinutes == 0L && it.substance.equals(entrySubstance, ignoreCase = true) }
            .map { candidate ->
                Neighbour(
                    rowId = candidate.rowId,
                    substance = candidate.substance,
                    offsetMinutes = candidate.offsetMinutes,
                    // `halfWindow == null` is the unjudgeable case, and it is deliberately not `false`-by-default
                    // without the caller being able to tell: `Result.judged` carries that.
                    overlaps = halfWindow != null && abs(candidate.offsetMinutes) <= halfWindow,
                    sameSession = candidate.sameSession,
                )
            }
            // Nearest first: the dose closest in time is the one most likely to have mattered. Row id breaks a
            // tie so the order is stable between reads.
            .sortedWith(compareBy({ abs(it.offsetMinutes) }, { it.rowId }))
            .take(MAXIMUM)

        return Result(neighbours = neighbours, judged = halfWindow != null)
    }

    /**
     * The neighbours, and whether overlap was judged at all.
     *
     * [judged] false means **the list says nothing about overlap** — this substance has no duration, so no window
     * exists to compare against. The card's wording differs between the two, which is the whole reason this is a
     * pair rather than a list.
     */
    data class Result(val neighbours: List<Neighbour>, val judged: Boolean)

    /**
     * Whether the context card should draw at all.
     *
     * False when nothing was logged nearby — a card headed "Context" with an empty list is furniture. A neighbour
     * that merely fails to overlap still draws, because its being there at all is the fact.
     */
    fun worthShowing(result: Result): Boolean = result.neighbours.isNotEmpty()

    /**
     * The offset as a duration with its direction.
     *
     * "2h 15m before" rather than a signed number, because the sign is the part a reader gets wrong when they are
     * scanning rather than reading.
     */
    fun describeOffset(minutes: Long): String {
        val magnitude = abs(minutes)
        if (magnitude < 1) return "same time"
        val hours = magnitude / 60
        val rest = magnitude % 60
        val body = when {
            hours == 0L -> "${rest}m"
            rest == 0L -> "${hours}h"
            else -> "${hours}h ${rest}m"
        }
        return if (minutes < 0) "$body before" else "$body after"
    }
}
