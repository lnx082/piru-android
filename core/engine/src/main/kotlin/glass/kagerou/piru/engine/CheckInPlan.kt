/*
 * Check-in offsets: the rules a custom check-in schedule obeys, and the pure
 * half of deriving one from what was actually taken.
 *
 * Ported from `Shared/CheckInOffsets.swift` and `Piru/Utilities/CheckInLadder.swift`.
 * The ladder's remaining half — `suggestedOffsets(for session: Session)`, which
 * resolves the session's doses and their catalog classes — is deliberately not
 * here: it needs the session store. [CheckInLadder.suggestedOffsets] is its
 * store-free core, taking the modeled doses directly.
 *
 * Everything below is pure and takes no clock: an offset is minutes after an
 * anchor, so nothing here needs a calendar, let alone a time zone.
 */

package glass.kagerou.piru.engine

import glass.kagerou.piru.model.SubstanceCategory
import java.time.Duration
import java.time.Instant

/**
 * The rules a custom check-in schedule obeys, apart from any store or view:
 * what a valid time is, how a set of them is normalized, and how one reads.
 *
 * Here rather than beside the session model because the store that persists a
 * list and the editor that builds it must agree: a list that round-trips
 * through storage has to already satisfy the rules it is read back under.
 */
object CheckInOffsets {
    /**
     * The soonest a check-in may sit after the dose. Below this it lands before
     * most things have started, on a phone still in the hand that logged the dose.
     */
    const val MINIMUM_MINUTES: Int = 5

    /**
     * The furthest out one may sit. A day is past where a session's own anchor
     * means anything, and a prompt beyond it reads as a stray alarm.
     */
    const val MAXIMUM_MINUTES: Int = 24 * 60

    /**
     * How many a session may carry. The platform budget for pending
     * notifications is shared across everything the app schedules, so a single
     * session's share is small on purpose.
     */
    const val MAXIMUM_COUNT: Int = 12

    /**
     * Ascending, unique, in range, and no more than [MAXIMUM_COUNT] — the form
     * the store holds and every reader can assume.
     *
     * Above the cap this keeps the *earliest* times. That is the right rule for
     * one list someone typed, and the wrong one for a multi-dose suggestion,
     * which is why [CheckInLadder.thinned] exists and runs first.
     */
    fun normalized(minutes: List<Int>): List<Int> =
        minutes.filter { it in MINIMUM_MINUTES..MAXIMUM_MINUTES }
            .distinct()
            .sorted()
            .take(MAXIMUM_COUNT)

    /**
     * Whether [minutes] can still be added to a list already holding [existing].
     *
     * Unlabelled, where upstream reads `canAdd(_:to:)` — Kotlin has no argument
     * labels, and the parameter name carries the same meaning at the call site.
     */
    fun canAdd(minutes: Int, existing: List<Int>): Boolean =
        minutes >= MINIMUM_MINUTES &&
            minutes <= MAXIMUM_MINUTES &&
            existing.size < MAXIMUM_COUNT &&
            !existing.contains(minutes)

    /**
     * One offset as a relative label — "+45m", "+1h", "+2h 30m". Read as an
     * interval from the dose, never as a clock time: it is a duration the user
     * chose, and only means a time of day once the anchor is known.
     */
    fun label(minutes: Int): String {
        val hours = minutes / 60
        val remainder = minutes % 60
        if (hours == 0) return "+" + remainder + "m"
        if (remainder == 0) return "+" + hours + "h"
        return "+" + hours + "h " + remainder + "m"
    }
}

/**
 * One dose as the ladder reads it: the modeled phase boundaries plus the class
 * that decides how many moments are worth asking about.
 *
 * The category travels beside the state rather than inside it because the state
 * is a curve, not a taxonomy — the same reason upstream looks the class up from
 * the catalog at the call site.
 */
data class CheckInDose(val state: ActiveSubstanceState, val category: SubstanceCategory)

/**
 * Check-in times derived from what was actually taken, rather than from a fixed
 * ladder someone guessed at.
 *
 * Every offset is read off the dose's own modeled phase boundaries, so a
 * twelve-hour session is asked about seven times and a dose that is finished
 * inside the hour is asked about once or twice. The result is a plain list of
 * minutes after the session's anchor dose, the same shape a hand-built schedule
 * has, so a suggestion and a hand-built schedule are the same kind of thing and
 * run through the same scheduler.
 */
object CheckInLadder {

    /**
     * How many moments a class is worth asking about.
     *
     * The split is about what a prompt can learn, not about how long the drug
     * lasts — duration is already handled by reading the phase boundaries. A
     * psychedelic session changes character several times and each change is
     * worth a note; a medication has one question ("is it working") and one
     * follow-up ("has it worn off"), and asking it five times a day is how a
     * useful prompt becomes one more thing to dismiss.
     */
    enum class Depth {
        /** Every phase boundary and the middle of each phase. */
        Wide,

        /** The four moments where a recreational dose changes character. */
        Paced,

        /** Two: the middle of the plateau, and where it turns. */
        Light,
        ;

        companion object {
            /**
             * The depth a substance's class is asked about at.
             *
             * Everything unnamed falls to [Light]: the list is a set of classes
             * known to warrant more, and a new class is not evidence that it
             * does — a wrong extra prompt is a cost paid every single day.
             */
            fun of(category: SubstanceCategory): Depth = when (category) {
                SubstanceCategory.PSYCHEDELIC,
                SubstanceCategory.DISSOCIATIVE,
                SubstanceCategory.DYSDELIC,
                SubstanceCategory.DELIRIANT,
                SubstanceCategory.EMPATHOGEN -> Wide

                SubstanceCategory.OPIOID,
                SubstanceCategory.BENZODIAZEPINE,
                SubstanceCategory.DEPRESSANT,
                SubstanceCategory.CANNABINOID,
                SubstanceCategory.GABAPENTINOID,
                SubstanceCategory.ANALGESIC,
                SubstanceCategory.OREXIN_ANTAGONIST,
                SubstanceCategory.ANTIHISTAMINE -> Paced

                else -> Light
            }
        }
    }

    /**
     * The minutes after a dose that its class is worth asking about, read off
     * that dose's own phase boundaries.
     *
     * The last moment is `max(offsetEndMinutes, totalMinutes)`: a profile whose
     * offset phase ends short of its stated total would otherwise never be asked
     * about the tail it says it has.
     */
    fun moments(state: ActiveSubstanceState, depth: Depth): List<Double> {
        val onsetEnd = state.onsetEndMinutes
        val comeupEnd = state.comeupEndMinutes
        val peakEnd = state.peakEndMinutes
        val offsetEnd = maxOf(state.offsetEndMinutes, state.totalMinutes)

        fun middle(from: Double, to: Double): Double = (from + to) / 2

        return when (depth) {
            Depth.Wide -> listOf(
                onsetEnd,
                middle(onsetEnd, comeupEnd),
                comeupEnd,
                middle(comeupEnd, peakEnd),
                peakEnd,
                middle(peakEnd, offsetEnd),
                offsetEnd,
            )

            Depth.Paced -> listOf(
                comeupEnd,
                middle(comeupEnd, peakEnd),
                peakEnd,
                offsetEnd,
            )

            Depth.Light -> listOf(
                middle(comeupEnd, peakEnd),
                peakEnd,
            )
        }
    }

    /**
     * Rounding granularity for a dose of this length: fine enough that a short
     * dose keeps its shape, coarse enough that a long one reads as a time
     * somebody chose rather than a computed number.
     */
    fun granularity(totalMinutes: Double): Int = when {
        totalMinutes < 90 -> 5
        totalMinutes < 360 -> 15
        else -> 30
    }

    /**
     * Offsets in minutes after [anchor] for one dose, rounded and in range.
     *
     * The shift is how far this dose sits from the session's anchor, so a
     * second dose's moments land on the session's clock rather than restarting
     * from its own. Rounding happens after the shift, so the same dose in a
     * different session can round to a different minute — which is what keeps
     * the whole session on one set of times.
     */
    fun offsets(state: ActiveSubstanceState, depth: Depth, anchor: Instant): List<Int> {
        val shift = Duration.between(anchor, state.doseTimestamp).toMillis() / 60_000.0
        val step = granularity(state.totalMinutes)
        return moments(state, depth)
            .map { roundHalfAwayFromZero((it + shift) / step) * step }
            .filter { it >= CheckInOffsets.MINIMUM_MINUTES && it <= CheckInOffsets.MAXIMUM_MINUTES }
    }

    /**
     * The suggested schedule for a session: every dose's moments, merged and
     * normalized. Empty when nothing passed in models a curve — a dose with no
     * duration data has no phases to read, and inventing a ladder for it would
     * be the guess this whole type exists to replace.
     *
     * The store-free core of upstream's `suggestedOffsets(for session:)`: the
     * caller resolves each dose to a state and a class, which is the only part
     * that needs the catalog and the session.
     */
    fun suggestedOffsets(doses: List<CheckInDose>, anchor: Instant): List<Int> {
        val merged = ArrayList<Int>()
        for (dose in doses) {
            merged += offsets(dose.state, Depth.of(dose.category), anchor)
        }
        return CheckInOffsets.normalized(thinned(merged.distinct().sorted()))
    }

    /**
     * Reduce a list to at most [limit] by dropping from the middle, keeping the
     * first and the last.
     *
     * [CheckInOffsets.normalized] takes the *earliest* times when a list is over
     * the cap, which on a multi-dose session would spend the whole budget on the
     * first dose's come-up and never ask whether the evening ended. Spacing the
     * survivors keeps both ends. The two are not interchangeable: for the same
     * over-cap input they keep different times, and the caller that wants both
     * ends must run this one first.
     */
    fun thinned(offsets: List<Int>, limit: Int = CheckInOffsets.MAXIMUM_COUNT): List<Int> {
        if (offsets.size <= limit || limit <= 1) return offsets
        val stride = (offsets.size - 1).toDouble() / (limit - 1).toDouble()
        return (0 until limit).map { offsets[roundHalfAwayFromZero(it * stride)] }
    }

    /**
     * The suggestion as one line — "+45m · +2h · +4h · +7h 30m" — for a banner
     * button and an editor's empty state.
     */
    fun summary(offsets: List<Int>): String = offsets.joinToString(" · ") { CheckInOffsets.label(it) }
}

/**
 * Swift's `rounded()`: nearest integer, ties away from zero.
 *
 * Written out rather than taken from the stdlib because neither available
 * rounding matches: `kotlin.math.round` is `Math.rint`, which sends a tie to the
 * even neighbour (4.5 → 4, where the port must give 5), and `Math.round` sends
 * every tie up instead of away from zero. The tie case is reachable — a ten-item
 * list thinned to three has a stride of exactly 4.5 — so the difference is a
 * visible one, not a theoretical one.
 */
private fun roundHalfAwayFromZero(value: Double): Int {
    val rounded = if (value < 0) -Math.floor(-value + 0.5) else Math.floor(value + 0.5)
    return rounded.toInt()
}
