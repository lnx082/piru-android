package glass.kagerou.piru.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

/**
 * Cached tolerance state for one mechanism class, derived by replaying the dose
 * log through the pharmacology model.
 *
 * Ported from `Shared/Models/ToleranceState.swift`.
 *
 * The authoritative value is *derived*: the store recomputes the right-shift
 * layers by replaying the dose log, deterministically. This row is the cached
 * result, so the UI and any widget can read current tolerance without
 * re-integrating the whole history on every view.
 *
 * Tolerance is a dose-response right-shift
 * `S = exp(sAcute + sAdaptive + sDeep + sSynthesis)`, each `s` an ln-shift
 * contribution from one timescale. A naive or rested state has every layer at
 * `0`, so `S = 1`.
 *
 * ## Not user data
 * This is a cache. It is deliberately excluded from the "does the store hold
 * anything" count (the iOS `StoreRecovery.countUserRows` tallies only
 * user-authored entities) — a cache row must not make an otherwise-empty store
 * look data-bearing, or a fresh install would be offered a recovery it does not
 * need.
 */
@Entity(tableName = "tolerance_states")
data class ToleranceStateEntity(
    /**
     * Mechanism-class identifier — a receptor-class wire value such as
     * `"muOpioid"` or `"catecholamineStimulant"`. Unique per store: one row per
     * class, and the engine writes by this key.
     */
    @PrimaryKey
    @ColumnInfo(name = "target")
    val target: String,

    /** Acute ln-shift, `>= 0` — within-session tachyphylaxis (τ ≈ hours), the redose loop. */
    @ColumnInfo(name = "s_acute", defaultValue = "0")
    val sAcute: Double = 0.0,

    /** Adaptive ln-shift, `>= 0` — the baseline shift people mean by "tolerance" (τ ≈ days–weeks). */
    @ColumnInfo(name = "s_adaptive", defaultValue = "0")
    val sAdaptive: Double = 0.0,

    /**
     * Deep ln-shift, `>= 0` — entrenched neuroadaptation (τ ≈ months), gated off
     * below an escalation threshold so therapeutic users never accrue it.
     */
    @ColumnInfo(name = "s_deep", defaultValue = "0")
    val sDeep: Double = 0.0,

    /**
     * Synthesis ln-shift, `>= 0` — the slow serotonin-synthesis pool (τ ≈ weeks)
     * that only the synthesis-suppressing SERT releasers drive, so they recover
     * on a weeks clock while the cathinone releasers reset in days. `0` for every
     * class without an active synthesis layer.
     */
    @ColumnInfo(name = "s_synthesis", defaultValue = "0")
    val sSynthesis: Double = 0.0,

    /**
     * Chronicity duty-cycle accumulator in `[0, 1]` — the leaky time-averaged
     * occupancy (τ ≈ 21 days) that, with dose-relative escalation, gates the deep
     * layer.
     *
     * Persisted as part of the slow-layer checkpoint: together with [sDeep],
     * [sSynthesis] and [lastUpdated] it lets the next replay seed the
     * months-scale state that a 90-day window cannot hold, instead of
     * re-integrating it from zero each time.
     */
    @ColumnInfo(name = "chronic_exposure", defaultValue = "0")
    val chronicExposure: Double = 0.0,

    /**
     * When this snapshot was last recomputed — the replay's "now". Doubles as the
     * checkpoint timestamp: the slow accumulators above are decayed forward from
     * here to the next replay's window start before seeding.
     */
    @ColumnInfo(name = "last_updated", defaultValue = "0")
    val lastUpdated: Date = Date(0),
) {
    companion object {
        /**
         * The epoch default for [lastUpdated].
         *
         * The iOS model defaults to `Date.distantPast` so a row created before the
         * field existed reads as "never updated" rather than "just updated". Room
         * cannot carry that as a column default, so a caller that mints a row
         * directly must pass it explicitly; the replay treats any timestamp at or
         * before the epoch as a checkpoint that carries nothing forward.
         */
        val NEVER_UPDATED: Date = Date(0)
    }
}
