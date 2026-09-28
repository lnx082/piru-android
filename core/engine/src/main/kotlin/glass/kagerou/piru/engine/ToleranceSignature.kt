package glass.kagerou.piru.engine

import kotlin.math.floor

/**
 * A cheap fingerprint of everything a tolerance replay depends on, so an unchanged
 * log skips the work.
 *
 * Ported from `ToleranceStore.signature(entries:weightKg:now:)`.
 *
 * Navigating back into the tool is the common case and the replay is the expensive
 * one, so the gate is what makes that free.
 *
 * ## Order-independent, on purpose
 * The two callers hand the log over in different orders — a journal query returns it
 * newest-first while a background refresh fetches in store order — and both must
 * produce the *same* signature so they dedupe against each other's work. So the
 * per-dose hashes are combined with XOR, which is commutative, rather than
 * sequentially. The count is carried alongside because XOR alone cannot see an
 * add/remove pair that cancels.
 *
 * ## What is hashed, and one deliberate difference
 * Only doses **inside the lookback window**, because that is exactly the set the
 * replay integrates.
 *
 * Upstream hashes each dose's `(substance, amount, unit, timestamp)`. Here the dose
 * has already been converted to milligrams — the engine's `SimDose` carries no unit
 * — so the amount is hashed as a mass. That makes the signature change exactly when
 * the *replay's input* changes rather than when its spelling does: re-logging 1000 mg
 * as 1 g is a different signature upstream and the same one here, and the replay
 * genuinely produces the same numbers for both. Strictly fewer needless recomputes,
 * and no case where a changed input goes unnoticed.
 *
 * ## The hourly bucket
 * `now` enters only as an hour bucket, so time-decay refreshes at most hourly rather
 * than on every visit. A same-hour navigation therefore reuses the last result,
 * which is the intent — the layers move on scales of hours to months.
 */
object ToleranceSignature {

    /**
     * @param nowMinutes the replay's "now", on the same axis as each dose's
     *   [ToleranceReplay.SimDose.timestampMinutes]. The axis itself is the caller's,
     *   but it must be stable across calls: two runs that place the same log on
     *   different origins would produce different signatures for identical input.
     */
    fun of(
        doses: List<ToleranceReplay.SimDose>,
        weightKg: Double,
        nowMinutes: Double,
        lookbackDays: Double = ToleranceSimulation.DEFAULT_LOOKBACK_DAYS,
    ): String {
        val cutoff = nowMinutes - lookbackDays * 1_440.0
        var combined = 0L
        var count = 0
        for (dose in doses) {
            if (dose.timestampMinutes > nowMinutes || dose.timestampMinutes < cutoff) continue
            count++
            var hash = 17L
            hash = 31 * hash + dose.substance.hashCode()
            hash = 31 * hash + dose.amountMg.hashCode()
            hash = 31 * hash + dose.timestampMinutes.hashCode()
            combined = combined xor hash
        }
        val hourBucket = floor(nowMinutes / 60.0).toLong()
        return "$count|$combined|$hourBucket|$weightKg"
    }
}
