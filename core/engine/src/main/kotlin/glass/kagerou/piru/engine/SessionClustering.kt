package glass.kagerou.piru.engine

import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/**
 * The duration-aware heuristic that groups doses into sessions by temporal
 * proximity — the replacement for fixed calendar-day bucketing.
 *
 * Ported from `Shared/Engines/SessionClustering.swift`. Pure value-level logic:
 * a caller maps its dose entries to [Dose] values — resolving each dose's modeled
 * effect duration — and gets back groupings. That keeps the heuristic testable
 * with hand-fed durations and reusable wherever the app needs it.
 *
 * ## The rule
 * A session stays open until either its modeled effect ends *or* a quiescent gap
 * wider than the session's *current* sleep ceiling passes, whichever comes first:
 *
 * ```
 * effectEnd = max over the session's non-background doses of (t + k·duration),
 *             each dose's tail clamped to effectTailCap
 * lastTime  = the most recent dose in the session, background included
 * elapsed   = lastTime − sessionStart
 * gap       = newDose.t − lastTime
 * ceiling   = a decaying function of elapsed
 * join  ⇔  newDose.t − sessionStart < horizon
 *          &&  gap ≤ ceiling
 *          &&  (gap ≤ floor  ||  newDose.t ≤ effectEnd)
 * ```
 *
 * - [Constants.floor], three hours: doses this close always group, even for
 *   short-acting or unknown-duration substances.
 * - **The decaying ceiling** is the safety valve that stops a long-half-life
 *   compound, or nonstop redosing, from holding a session open indefinitely.
 *   Instead of a flat window it tightens as the session ages, so a fresh session
 *   tolerates a full night's gap while a day-old one splits on the next real
 *   break. Past [Constants.knee] the ceiling drops below the floor and *becomes*
 *   the binding constraint.
 * - **The hard cap**, [Constants.horizon] at 24 hours: a dose more than a day
 *   after the session's first dose always starts a new session, however tightly
 *   spaced. Sessions are day-scoped, so nothing is allowed to exceed a day; the
 *   user re-attaches days with an explicit merge if they really were one run.
 * - [Constants.k], 1.2: how far past the modeled wear-off still counts as the
 *   same session, because people redose into the comedown and still call it one
 *   session.
 * - [Constants.effectTailCap], nine hours: the per-dose tail used for clustering
 *   is clamped to this so a long-acting compound cannot glue the next day's doses
 *   on. It bounds grouping only — the timeline still draws the full curve.
 *
 * Tracking the session's *peak* effect end rather than the last dose's is what
 * makes a quick short-acting dose dropped into a long trip stay in that trip
 * instead of prematurely closing it.
 *
 * ## Background medications
 * A background-med dose never opens or extends a *recreational* session and never
 * contributes to the effect end. It folds into the current recreational session
 * only while that session is genuinely active; otherwise it clusters with other
 * background meds into a *maintenance* session, or stands alone.
 *
 * ## Decided once, persisted
 * Clustering runs at log time and once over history in a populate pass; it is
 * **not** re-run on every render. The user owns the result afterwards via merge,
 * split and reassign.
 */
object SessionClustering {

    object Constants {

        /** Doses within this gap always group. */
        val floor: Double = 3 * 60 * 60.0

        /** The ceiling a *fresh* session tolerates before the substance's own duration widens it. */
        val ceilingMax: Double = 6 * 60 * 60.0

        /** The ceiling at [knee]; the first decay segment runs [ceilingMax] down to this. */
        val ceilingKnee: Double = 3 * 60 * 60.0

        /**
         * The ceiling as [elapsed] approaches [horizon] — near zero, so the final
         * hours only absorb back-to-back doses. Not literally zero, so the
         * boundary is smooth rather than a cliff.
         */
        val ceilingEnd: Double = 60.0

        /** Where the ceiling's decay slope changes. */
        val knee: Double = 21 * 60 * 60.0

        /** The hard cap: a dose more than this after the session's first dose always starts a new one. */
        val horizon: Double = 24 * 60 * 60.0

        /** Multiplier on a dose's modeled effect duration for the session tail. */
        const val k: Double = 1.2

        /**
         * The per-dose effect tail used for clustering is clamped to this, so a
         * long-acting compound cannot hold a session open across a day.
         *
         * Matched to [ceilingDurationMax] so the two bounds agree; the 24-hour
         * [horizon] remains the guarantee that a session cannot chain days.
         */
        val effectTailCap: Double = 9 * 60 * 60.0

        /** Effect duration assumed for a dose with no modeled curve, in minutes. */
        const val fallbackEffectMinutes: Double = 240.0

        /**
         * Upper bound on a fresh session's ceiling once the substance's own
         * duration widens it. Keeps a very long-acting compound from tolerating an
         * entire night's sleep as "still the same session"; past this, a quiescent
         * gap really is a break.
         */
        val ceilingDurationMax: Double = 9 * 60 * 60.0

        /**
         * The ceiling a *fresh* session tolerates, given the longest modeled effect
         * tail among its doses so far.
         *
         * It must scale with the modeled tail — what [k] always promises, that
         * people redose into the comedown and still call it one session — because a
         * flat ceiling would split a session while its own curve is still being
         * drawn on screen. Bounded on both sides: never tighter than [ceilingMax],
         * never wider than [ceilingDurationMax]. The age decay is unchanged, so a
         * session that has already run long still splits on the next real break.
         */
        fun freshCeiling(peakTail: Double?): Double {
            if (peakTail == null) return ceilingMax
            return min(max(ceilingMax, peakTail), ceilingDurationMax)
        }

        /**
         * The effective sleep ceiling for a hop, given how long the session has
         * already run. Two linear segments: `fresh` down to [ceilingKnee] over
         * `[0, knee]`, then [ceilingKnee] down to [ceilingEnd] over
         * `[knee, horizon]`. Clamped outside that range.
         */
        fun ceiling(elapsed: Double, fresh: Double = ceilingMax): Double {
            val e = max(0.0, elapsed)
            if (e >= horizon) return ceilingEnd
            if (e <= knee) return fresh - (fresh - ceilingKnee) * (e / knee)
            return ceilingKnee - (ceilingKnee - ceilingEnd) * ((e - knee) / (horizon - knee))
        }
    }

    /** One dose's clustering-relevant facts, decoupled from any stored entry. */
    data class Dose(
        val timestamp: Instant,
        /** Modeled total effect duration in minutes; null uses the fallback. */
        val effectDurationMinutes: Double?,
        val isBackgroundMed: Boolean = false,
    ) {
        /**
         * The end of this dose's modeled effect, scaled by [Constants.k] and
         * clamped to [Constants.effectTailCap] so a long-acting compound cannot
         * stretch a session across a day.
         */
        val scaledEffectEnd: Instant get() = timestamp.plusSeconds(scaledTail)

        /** How far past this dose its effect still runs, for clustering purposes. */
        val scaledTail: Double
            get() {
                val minutes = effectDurationMinutes ?: Constants.fallbackEffectMinutes
                return min(Constants.k * minutes * 60, Constants.effectTailCap)
            }
    }

    /**
     * The running bounds of the session currently being extended — the only
     * candidate a newly-logged or swept dose can join, since doses are processed
     * in ascending time order and sessions never overlap.
     */
    class OpenSession private constructor(
        /** Timestamp of the session's *first* dose. Set once; [extend] never moves it. */
        val startTime: Instant,
        effectEnd: Instant?,
        lastTime: Instant,
        isMaintenance: Boolean,
        peakTail: Double?,
    ) {
        /** Latest `t + k·duration` across the session's non-background doses, or null for a maintenance session. */
        var effectEnd: Instant? = effectEnd
            private set

        /** Timestamp of the most recent dose, background included — the anchor for the gap guard. */
        var lastTime: Instant = lastTime
            private set

        /** True while every dose so far is a background medication. */
        var isMaintenance: Boolean = isMaintenance
            private set

        /**
         * Longest scaled effect tail contributed by any non-background dose so far
         * — the session's own sense of how long-acting it is. Null for a
         * maintenance session.
         */
        var peakTail: Double? = peakTail
            private set

        /** The ceiling this session tolerates while fresh, widened by whatever it actually contains. */
        val freshCeiling: Double get() = Constants.freshCeiling(peakTail)

        /** Seed an open session from its first dose. */
        constructor(firstDose: Dose) : this(
            startTime = firstDose.timestamp,
            effectEnd = if (firstDose.isBackgroundMed) null else firstDose.scaledEffectEnd,
            lastTime = firstDose.timestamp,
            isMaintenance = firstDose.isBackgroundMed,
            peakTail = if (firstDose.isBackgroundMed) null else firstDose.scaledTail,
        )

        /**
         * Fold a dose into this session; the caller has already decided it joins.
         */
        fun extend(dose: Dose) {
            lastTime = maxInstant(lastTime, dose.timestamp)
            if (!dose.isBackgroundMed) {
                isMaintenance = false
                val end = dose.scaledEffectEnd
                effectEnd = effectEnd?.let { maxInstant(it, end) } ?: end
                peakTail = peakTail?.let { max(it, dose.scaledTail) } ?: dose.scaledTail
            }
        }

        companion object {
            /**
             * Reconstruct the running state of an existing session from its doses,
             * so a single newly-logged dose can be placed against it without
             * re-clustering history.
             *
             * [doses] must be in ascending time order. Returns null for an empty
             * session.
             */
            fun from(doses: List<Dose>): OpenSession? {
                val first = doses.firstOrNull() ?: return null
                val session = OpenSession(first)
                for (dose in doses.drop(1)) session.extend(dose)
                return session
            }
        }
    }

    /** Where the next dose goes relative to the current open session. */
    enum class Placement {
        /** Append to the current session and extend its bounds. */
        JOIN,

        /** Start a fresh session with this dose. */
        NEW_SESSION,
    }

    /**
     * Decide whether [dose] joins [current], the most recent open session, or
     * starts a new one. A null [current] means there is no prior session.
     *
     * The single source of truth for both [cluster] over history and log-time
     * assignment of one new dose.
     */
    fun placement(dose: Dose, current: OpenSession?): Placement {
        if (current == null) return Placement.NEW_SESSION

        // Hard 24-hour cap: a dose more than a day after the session's first dose
        // always starts a new one, however tightly spaced. This is the guarantee
        // that nonstop redosing cannot chain days. An out-of-order insert has a
        // negative span and never trips it.
        if (secondsBetween(current.startTime, dose.timestamp) >= Constants.horizon) {
            return Placement.NEW_SESSION
        }

        val gap = secondsBetween(current.lastTime, dose.timestamp)
        // A quiescent gap wider than the session's *current* sleep ceiling always
        // splits — even for a background med, even mid-effect. The ceiling tightens
        // as the session ages, so a fresh session tolerates a night's gap while a
        // day-old one splits on the next real break. A negative gap, from an
        // out-of-order insert, never trips this and falls through to the windows.
        val ceiling = Constants.ceiling(
            elapsed = secondsBetween(current.startTime, current.lastTime),
            fresh = current.freshCeiling,
        )
        if (gap > ceiling) return Placement.NEW_SESSION

        if (dose.isBackgroundMed) {
            // Fold into the current recreational session only while it is active.
            val end = current.effectEnd
            if (!current.isMaintenance && end != null && dose.timestamp <= end) return Placement.JOIN
            // Cluster co-administered background meds into a maintenance session.
            if (current.isMaintenance && gap <= Constants.floor) return Placement.JOIN
            // Otherwise the med stands as, or starts, its own maintenance session —
            // it never glues onto a recreational session it is not part of.
            return Placement.NEW_SESSION
        }

        // A normal dose only ever extends a recreational session.
        if (current.isMaintenance) return Placement.NEW_SESSION
        if (gap <= Constants.floor) return Placement.JOIN
        val end = current.effectEnd
        if (end != null && dose.timestamp <= end) return Placement.JOIN
        return Placement.NEW_SESSION
    }

    /**
     * Whether a dose at [doseTime] is close enough to an existing session spanning
     * [sessionFirst] to [sessionLast] to be moved into it *keeping its timestamp*,
     * without stretching that session across a long quiescent gap.
     *
     * True when the dose already falls inside the span, or within a fresh
     * session's sleep ceiling of either edge — the widest reach the heuristic ever
     * allows. A move to a farther session must re-time the dose so the session
     * stays a coherent single span; this is the predicate the reassign UI uses to
     * decide whether to ask for a new time.
     */
    fun canJoinKeepingTime(doseTime: Instant, sessionFirst: Instant, sessionLast: Instant): Boolean {
        if (doseTime >= sessionFirst && doseTime <= sessionLast) return true
        val gap = if (doseTime < sessionFirst) {
            secondsBetween(doseTime, sessionFirst)
        } else {
            secondsBetween(sessionLast, doseTime)
        }
        return gap <= Constants.ceilingMax
    }

    /**
     * Cluster time-sorted doses into sessions, returned as groups of indices into
     * the input.
     *
     * [doses] **must** be sorted ascending by timestamp. The result preserves that
     * order and partitions every index exactly once — no dose orphaned or
     * duplicated.
     */
    fun cluster(doses: List<Dose>): List<List<Int>> {
        val groups = mutableListOf<MutableList<Int>>()
        var current: OpenSession? = null

        for ((index, dose) in doses.withIndex()) {
            when (placement(dose, current)) {
                Placement.JOIN -> {
                    groups.last().add(index)
                    current?.extend(dose)
                }
                Placement.NEW_SESSION -> {
                    groups.add(mutableListOf(index))
                    current = OpenSession(dose)
                }
            }
        }
        return groups
    }

    // MARK: - Time helpers

    /**
     * Seconds from [from] to [to], possibly negative.
     *
     * Compared to the millisecond, where the iOS side subtracts two `Date`s as a
     * `Double` second count. Every window here is hours wide, so the difference is
     * far below the resolution the heuristic turns on.
     */
    private fun secondsBetween(from: Instant, to: Instant): Double =
        Duration.between(from, to).toMillis() / 1000.0

    private fun Instant.plusSeconds(seconds: Double): Instant =
        plusMillis((seconds * 1000).toLong())

    private fun maxInstant(a: Instant, b: Instant): Instant = if (a >= b) a else b
}
