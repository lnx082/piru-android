package glass.kagerou.piru.model

import kotlinx.serialization.Serializable

/**
 * A phase length written as a range, in minutes. The property names are the
 * JSON keys — the iOS side encodes `{"min": …, "max": …}` verbatim.
 */
@Serializable
data class DurationRange(val min: Double, val max: Double) {

    val midpoint: Double get() = (min + max) / 2
}

/**
 * How long a substance's phases last on one route.
 *
 * Every phase is optional. Endpoint-only data — a `total` with no intermediate
 * phases — is common, which is why [fillingMissingPhases] exists.
 */
@Serializable
data class DurationProfile(
    val onset: DurationRange? = null,
    val comeup: DurationRange? = null,
    val peak: DurationRange? = null,
    val offset: DurationRange? = null,
    val afterglow: DurationRange? = null,
    val total: DurationRange? = null,
) {

    /**
     * The whole span the curve is drawn over.
     *
     * The offset phase boundary — where the acute curve has fully fallen — is
     * the floor. A `total` shorter than the phases preceding it is incoherent
     * source data (kratom oral ships total 120–240 while the offset phase alone
     * ends at ~390); trusting it verbatim reports the dose "over" while its
     * curve is still visibly descending, desyncing the entry-row rail, now-line
     * and active fade — all gated on this value — from what the graph draws,
     * which follows the phase boundaries rather than `total`.
     */
    val estimatedTotalMinutes: Double
        get() {
            val phaseEnd = phaseBoundaries.offsetEnd
            val stated = total?.midpoint
            return if (stated == null) phaseEnd else maxOf(stated, phaseEnd)
        }

    /** Where each phase ends, accumulating from the start of the dose. */
    val phaseBoundaries: PhaseBoundaries
        get() {
            val onsetEnd = onset?.midpoint ?: 0.0
            val comeupEnd = onsetEnd + (comeup?.midpoint ?: 0.0)
            val peakEnd = comeupEnd + (peak?.midpoint ?: 0.0)
            val offsetEnd = peakEnd + (offset?.midpoint ?: 0.0)
            val afterglowEnd = offsetEnd + (afterglow?.midpoint ?: 0.0)
            return PhaseBoundaries(
                onsetEnd = onsetEnd,
                comeupEnd = comeupEnd,
                peakEnd = peakEnd,
                offsetEnd = offsetEnd,
                afterglowEnd = afterglowEnd,
            )
        }

    /**
     * Fill in missing come-up, peak and offset phases when the data carries a
     * real `total` but not the intermediate phases that shape the curve between
     * onset and total — endpoint-only data from a single source.
     *
     * Without this, [phaseBoundaries] sums only the present phases and collapses
     * the curve to roughly the onset length, discarding the stated duration (a
     * ~12 h LSD trip rendered as a ~1 h spike). The real `total` is left intact;
     * only the *unexplained* span (`total` minus the present phases) is
     * distributed across the missing shapers using class-aware proportions from
     * [SubstanceCategory.synthesizedPhaseShape], so any genuine phase is
     * preserved.
     *
     * Returns `this` for complete profiles and for those with no `total` — the
     * latter keep the half-life synthesis fallback.
     *
     * Applied wherever a curve is drawn: the journal timeline and the detail
     * card alike. It used to be journal-only, which is why the same dose drew
     * two different shapes depending on the screen. The detail card's numeric
     * trio and phase disclosure still read the *raw* profile: those are a
     * reference table and must stay verbatim source data.
     */
    fun fillingMissingPhases(category: SubstanceCategory): DurationProfile {
        val stated = total ?: return this
        val totalMin = stated.midpoint
        if (totalMin <= 0) return this

        val shape = category.synthesizedPhaseShape
        val onsetMin = onset?.midpoint ?: (totalMin * shape.onset)
        val presentMiddle = (comeup?.midpoint ?: 0.0) + (peak?.midpoint ?: 0.0) + (offset?.midpoint ?: 0.0)
        val budget = totalMin - onsetMin - presentMiddle

        // A complete profile leaves ~no unexplained span; bail so it is
        // untouched. Likewise bail once every shaper is already present.
        val needsComeup = comeup == null
        val needsPeak = peak == null
        val needsOffset = offset == null
        if (budget <= totalMin * 0.1 || !(needsComeup || needsPeak || needsOffset)) return this

        val wComeup = if (needsComeup) shape.comeup else 0.0
        val wPeak = if (needsPeak) shape.peak else 0.0
        val wOffset = if (needsOffset) shape.offset else 0.0
        val wSum = wComeup + wPeak + wOffset
        if (wSum <= 0) return this

        fun filled(weight: Double): DurationRange? {
            if (weight <= 0) return null
            val v = budget * weight / wSum
            return DurationRange(min = v, max = v)
        }

        return DurationProfile(
            onset = onset ?: DurationRange(min = onsetMin, max = onsetMin),
            comeup = comeup ?: filled(wComeup),
            peak = peak ?: filled(wPeak),
            offset = offset ?: filled(wOffset),
            afterglow = afterglow,
            total = stated,
        )
    }
}

/** Where each phase of a dose ends, in minutes from the dose. */
data class PhaseBoundaries(
    val onsetEnd: Double,
    val comeupEnd: Double,
    val peakEnd: Double,
    val offsetEnd: Double,
    val afterglowEnd: Double,
)
