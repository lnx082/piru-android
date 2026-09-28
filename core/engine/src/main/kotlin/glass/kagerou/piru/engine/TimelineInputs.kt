package glass.kagerou.piru.engine

import glass.kagerou.piru.model.P3Color
import java.time.Instant

/**
 * One dose as the timeline curve engine sees it: where its phases end, how tall
 * to draw it, and the pharmacokinetics behind it.
 *
 * Ported from `Shared/PiruActivityAttributes.swift`. It is a **plain value** —
 * immutable, hashable, no reference to a store — which is what lets the curve
 * math run off the main thread and lets a widget or a lock-screen view draw the
 * same curve without a database of its own.
 *
 * This type describes the input; the calculator that *builds* it from a
 * substance and a dose range is a separate concern and is not ported yet.
 */
data class ActiveSubstanceState(
    val substanceName: String,
    val tint: P3Color,
    val doseTimestamp: Instant,
    val amount: Double,
    val unit: String,
    val route: String,

    /** Where each phase ends, in minutes from [doseTimestamp]. */
    val onsetEndMinutes: Double,
    val comeupEndMinutes: Double,
    val peakEndMinutes: Double,
    val offsetEndMinutes: Double,
    val afterglowEndMinutes: Double?,
    val totalMinutes: Double,

    /**
     * Dose intensity used to scale the curve height, already clamped to
     * `[0.05, 1.0]`.
     *
     * Computed as `amount / heavy_reference`, with looser fallbacks when the
     * ladder carries no heavy bound. A substance with no dose-range data falls
     * back to the calculator's neutral 0.60; a sub-threshold dose is floored at
     * 0.05 so it still renders as a visible nub rather than disappearing.
     */
    val doseIntensity: Double = 1.0,

    /**
     * Unclamped dose magnitude — the *same* reference as [doseIntensity], but
     * **without the 1.0 cap**.
     *
     * This is the linear quantity the timeline superposes: stacked doses sum
     * their magnitudes, and a single combined dose of the same total lands
     * identically, so the merged curve passes one saturating Hill link and
     * `4×20 mg ≡ 1×80 mg` falls out for free. That equivalence is the whole
     * reason the cap exists on one field and not the other.
     */
    val doseMagnitude: Double = doseIntensity,

    /**
     * Where the substance's **sourced** heavy-dose bound falls on the magnitude
     * scale, or null when its ladder carries no heavy bound at all.
     *
     * Because the magnitude is `amount / heavy_reference`, this is 1.0 whenever
     * the denominator *is* a published heavy value — so the number carries no new
     * information, but its presence does. It is the difference between a
     * threshold somebody wrote down and one improvised from the strong and common
     * bounds so the curve would have some height. Only the former may be drawn as
     * a marked region.
     */
    val heavyThresholdMagnitude: Double? = null,

    /**
     * True when the substance has no dose ladder to scale this dose against, so
     * [doseIntensity] is the calculator's neutral value rather than a reading of
     * the amount. The timeline draws such a curve at that fixed height and
     * dotted, so it never reads as the substance's strongest dose.
     */
    val doseIsUnscaled: Boolean = false,

    /**
     * Acute-tolerance (tachyphylaxis) strength, `0…1`, from the substance's
     * category. Drives the descending-limb gate: stimulants and empathogens
     * crash faster than their plasma curve, so the felt effect returns to
     * baseline by [totalMinutes] rather than trailing off on the slow elimination
     * tail. Zero leaves the pure offset unchanged.
     */
    val tachyphylaxis: Double = 0.0,

    /**
     * The user's body weight when this state was built. Only zero-order
     * elimination reads it — a heavier body clears a fixed gram dose faster, so
     * the alcohol curve narrows with weight. Riding inside the immutable state
     * keeps the off-main curve math weight-aware without a shared global.
     */
    val bodyWeightKg: Double = PKModel.REFERENCE_BODY_WEIGHT_KG,

    /**
     * Zero-order elimination parameters, already scaled to [bodyWeightKg] —
     * non-null **exactly** for the substances the catalog carries a
     * `zero_order_kinetics` row for, and therefore the switch onto the
     * dose-scaled linear-decline curve.
     */
    val zeroOrder: PKModel.ZeroOrderKinetics? = null,

    /**
     * Widths (max minus min, minutes) of the come-up, peak and offset phase ranges
     * the boundary midpoints came from.
     *
     * The curve uses them to let a "peak 3–5 h" profile draw broader and softer
     * than a "peak 4 h" one: come-up spread delays the full-effect anchor, peak
     * spread broadens the crest dome, offset spread extends the tail landing.
     * Null — an older payload, or a range-less or synthesized phase — means zero
     * spread, the pure midpoint fit.
     */
    val comeupSpreadMinutes: Double? = null,
    val peakSpreadMinutes: Double? = null,
    val offsetSpreadMinutes: Double? = null,
) {
    /**
     * Empty on purpose: it exists so `ActiveSubstanceState.from(...)` — the builder
     * that turns a logged dose into one of these — can be declared as an extension
     * on it, in `ActiveSubstanceCalculator.kt`. That keeps the call site the shape
     * it has upstream without putting 150 lines of dose and ladder arithmetic in a
     * file about the value type.
     */
    companion object
}

/**
 * A duration-less substance rendered as its own lane: a name label plus a
 * baseline row of dots, one per dose.
 *
 * Ported from `Shared/TimelineGraphSupport.swift`.
 */
data class DoseMarker(
    val substanceName: String,
    val timestamp: Instant,
    val tint: P3Color,
    val amount: Double,
    val unit: String,
)
