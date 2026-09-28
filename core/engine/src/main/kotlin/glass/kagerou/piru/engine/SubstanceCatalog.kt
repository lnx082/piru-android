package glass.kagerou.piru.engine

import glass.kagerou.piru.model.DurationProfile
import glass.kagerou.piru.model.Substance

/**
 * One ester of a substance family, as the depot heuristic sees it.
 *
 * Ported from the `ester_pk` record in `Piru/Data/SubstanceDB/SubstanceStore+EsterPK.swift`,
 * reduced to what depot resolution actually reads.
 *
 * @param label the user-facing ester name ("Valerate", "Cypionate"), which is what
 *   a logged dose carries on its salt field.
 * @param terminalRatePerDay the terminal release rate constant `k1`, per day, or
 *   null for a **catalog-only** ester (undecylate) that ships no validated curve —
 *   real and loggable, but its depot half-life falls back to the default.
 */
data class EsterRecord(
    val label: String,
    val terminalRatePerDay: Double?,
)

/**
 * Everything the calculation engine reads from outside itself.
 *
 * Ported from the three stores `ActiveSubstanceCalculator` and `PKResolver` reach
 * upstream — `SubstanceLibrary`, `SubstanceStore` and the `ester_pk` index —
 * collapsed into one seam.
 *
 * The engine is a pure JVM module with no database of its own, on purpose: it is
 * what lets the curve math be tested in milliseconds and run off the main thread.
 * The iOS side gets the same property from `nonisolated` functions, but it still
 * reaches live stores through singletons. Injecting the lookups instead means a
 * test can hand the calculator a substance it invented, which is how the ladder
 * fallbacks below are pinned without a catalog of 1,688 rows behind them.
 *
 * Implemented over the real read layer in `:core:data`.
 */
interface SubstanceCatalog {

    /**
     * The catalog entry for [name], matched by canonical name or alias, or null
     * when the catalog does not carry it.
     *
     * Null is an ordinary answer, not an error: a substance the user invented is
     * looked up on every timeline rebuild and simply has no entry.
     */
    fun lookup(name: String): Substance?

    /**
     * The PSID **family** of the substance logged as [name], or null when unknown.
     *
     * Fold-family siblings share this, which is exactly what depot resolution
     * needs: an ester is matched to the family, not to one row, so "Estradiol
     * Valerate" finds the esters of estradiol whichever spelling was logged.
     */
    fun substanceUID(name: String): String?

    /** Every ester of [parentUID]'s family — modelable or not — in stable order. Empty when the family has none. */
    fun esters(parentUID: String): List<EsterRecord>

    /**
     * The authored duration envelope for an extended-release **product** ("Concerta"
     * → ~12 h, "Adderall XR" → ~11 h) from the `product_durations` table, or null
     * for a plain dose or a product the catalog does not carry.
     *
     * Keyed by the product name rather than the release-form code, so it resolves
     * the umbrella `XR` to the specific formulation — which is the difference
     * between drawing a labeled 12-hour curve and drawing nothing.
     */
    fun productDuration(productName: String): DurationProfile?

    /**
     * The dose-scaled zero-order elimination parameters for [substanceName], or
     * null when the substance clears first-order like nearly everything else.
     *
     * Default null so an implementation that has not yet wired the
     * `zero_order_kinetics` read still compiles and still draws every other curve:
     * alcohol falls back to the phase bell, which is where the field started.
     */
    fun zeroOrderKinetics(substanceName: String, weightKg: Double): PKModel.ZeroOrderKinetics? = null
}

/**
 * Whether [label] names an injectable ester of the family [parentUID].
 *
 * Distinguishes an ester on `saltForm` ("Valerate") from a mineral salt, so a
 * title can fold the ester in ("Estradiol Valerate") without touching salts.
 * Ported from `SubstanceStore.isEster(_:forParentUID:)`, including its two guards:
 * an empty label and an unresolved family are both false rather than an error.
 */
fun SubstanceCatalog.isEster(label: String?, parentUID: String?): Boolean {
    if (label.isNullOrEmpty() || parentUID == null) return false
    return esters(parentUID).any { it.label == label }
}

/** A catalog with nothing in it — the default for tests that only exercise the dose-ladder arithmetic. */
object EmptySubstanceCatalog : SubstanceCatalog {
    override fun lookup(name: String): Substance? = null
    override fun substanceUID(name: String): String? = null
    override fun esters(parentUID: String): List<EsterRecord> = emptyList()
    override fun productDuration(productName: String): DurationProfile? = null
}
