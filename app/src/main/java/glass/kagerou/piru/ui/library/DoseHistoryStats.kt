package glass.kagerou.piru.ui.library

/**
 * A substance's dose history as the "Your History" card summarises it.
 *
 * Ported from `SubstanceDetailModel.HistoryStats` and `rebuildHistoryStats`. Pure arithmetic over the user's own log
 * rows, so it is testable without a database.
 *
 * ## Three decisions the card rests on
 *
 * **The most common dose is by count, and ties are not broken.** Two doses logged equally often have no most-common
 * one, and picking the larger would be inventing a preference the log does not show. Upstream's `max(by:)` returns
 * whichever the ordering reaches first — this port uses a deterministic tie-break (the lower amount) so the summary
 * does not change between runs, which is a difference worth naming rather than hiding.
 *
 * **The unit is the *first* entry's, not a per-row unit.** The card prints one unit beside every figure, so a log that
 * mixes `mg` and `g` would otherwise print two ranges that are not comparable. The caller passes the unit its rows
 * share; this type never guesses one.
 *
 * **An empty history has no stats, not zero stats.** `min == max == 0` would read as "you logged 0 mg", which is a
 * claim about a dose the user never took. [rebuild] returns null for no rows so the card can decline to draw.
 */
internal object DoseHistoryStats {

    /** The dose range and the most-logged amount, all in the caller's unit. */
    data class Stats(
        val minDose: Double,
        val maxDose: Double,
        val mostCommon: Double,
        /** How many rows share [mostCommon]. Shown nowhere, asserted in tests. */
        val mostCommonCount: Int,
    )

    /**
     * Computes the stats for [amounts], or null when there are none.
     *
     * Frequencies are keyed on the exact amount, as upstream's are: dose amounts from a log are the numbers the user
     * typed, so `100.0` and `100.0` are the same dose and `100.1` is a different one.
     */
    fun rebuild(amounts: List<Double>): Stats? {
        if (amounts.isEmpty()) return null
        val frequencies = mutableMapOf<Double, Int>()
        for (amount in amounts) frequencies[amount] = (frequencies[amount] ?: 0) + 1
        // The most frequent, and on a tie the **lower** amount: a deterministic order, because upstream's tie-break is
        // whichever the comparison happens to reach and a summary that changes between runs is a summary nobody can
        // check.
        val mostCommon = frequencies.entries
            .sortedWith(compareByDescending<Map.Entry<Double, Int>> { it.value }.thenBy { it.key })
            .first()
        return Stats(
            minDose = amounts.min(),
            maxDose = amounts.max(),
            mostCommon = mostCommon.key,
            mostCommonCount = mostCommon.value,
        )
    }
}
