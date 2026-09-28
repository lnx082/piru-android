package glass.kagerou.piru.engine

import glass.kagerou.piru.model.P3Color
import java.time.Duration
import java.time.Instant
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The pure curve-synthesis math behind the timeline graph.
 *
 * Ported from `Shared/Engines/TimelineCurveModel.swift`. Everything here is a
 * pure function of its inputs — the phase-shaped effect curves, the
 * `Hill(Σ magnitude·bell)` redose merge, amplitude compression, tail scanning —
 * with no view state, so it runs off the main thread and is testable directly.
 */
object TimelineCurveModel {

    /** Hard cap on the timeline window, 48 hours. Activity past this is clipped. */
    const val MAX_DISPLAY_MINUTES: Double = 48 * 60.0

    /** Target number of time labels on the x-axis. */
    const val TARGET_TICK_COUNT: Int = 8

    /**
     * The US standard drink, in grams of pure ethanol.
     *
     * Duplicated from the by-volume dosing module, which has not been ported yet.
     * It is a single physical constant rather than logic, but the duplication is
     * real and should collapse when that module lands.
     */
    private const val US_STANDARD_DRINK_GRAMS = 14.0

    /**
     * All input-derived geometry that does not depend on zoom, pan or scrub —
     * computed exactly once so the draw closure and the span accessors read O(1)
     * stored values instead of re-walking the curves on every property access.
     */
    data class Derived(
        val earliestDose: Instant,
        val maxDoseBySubstance: Map<String, Double>,
        val stackedGroups: List<List<ActiveSubstanceState>>,
        val peakCurveValue: Double,
        val yNormalization: Double,
        /** [renderedTail] at threshold 0.04 — the full scrollable extent. */
        val rawDataTail: Double,
        /** [renderedTail] at threshold 0.20 with framing — the labeled-active window. */
        val rawActivityTail: Double,
    )

    /** One lane per distinct substance, in first-dose order, carrying every dose of that substance. */
    data class LaneGroup(
        val name: String,
        val tint: P3Color,
        val doses: List<ActiveSubstanceState>,
    )

    /** A duration-less substance rendered as its own lane: a label plus a baseline row of dots. */
    data class MarkerLane(
        val name: String,
        val tint: P3Color,
        val markers: List<DoseMarker>,
    )

    /** Pick a clean tick interval that yields about [TARGET_TICK_COUNT] labels for the span. */
    fun intervalForSpan(span: Double): Double {
        val candidates = listOf(15.0, 30.0, 60.0, 120.0, 240.0, 480.0, 720.0, 1_440.0)
        val ideal = span / TARGET_TICK_COUNT
        return candidates.firstOrNull { it >= ideal } ?: 1_440.0
    }

    /**
     * Build the entire [Derived] model in dependency order — once.
     *
     * Every helper it calls is pure precisely so this can run before anything
     * that reads it is ready.
     */
    fun computeDerived(
        substances: List<ActiveSubstanceState>,
        markers: List<DoseMarker>,
        stackRedoses: Boolean,
        dayBounded: Boolean,
        currentTime: Instant,
    ): Derived {
        val earliest = (substances.map { it.doseTimestamp } + markers.map { it.timestamp })
            .minOrNull() ?: currentTime

        val maxDose = mutableMapOf<String, Double>()
        for (s in substances) {
            val key = s.substanceName.lowercase()
            maxDose[key] = max(maxDose[key] ?: 0.0, s.amount)
        }

        val groups = if (stackRedoses) stackedGroups(substances) else emptyList()
        val peak = peakCurveValue(substances, groups, earliest, maxDose, stackRedoses)
        val yNorm = min(1.0 / peak, 20.0)
        val displayCap = if (dayBounded) 24 * 60.0 else MAX_DISPLAY_MINUTES

        val dataTail = renderedTail(
            0.04, framing = false, substances = substances, stackedGroups = groups,
            earliestDose = earliest, yNorm = yNorm, maxDose = maxDose,
            stackRedoses = stackRedoses, displayCap = displayCap,
        )
        val activityTail = renderedTail(
            0.20, framing = true, substances = substances, stackedGroups = groups,
            earliestDose = earliest, yNorm = yNorm, maxDose = maxDose,
            stackRedoses = stackRedoses, displayCap = displayCap,
        )

        return Derived(earliest, maxDose, groups, peak, yNorm, dataTail, activityTail)
    }

    /**
     * Find where the rendered envelope returns toward baseline: the latest minute
     * at which the tallest drawn curve still exceeds [threshold] of full height.
     *
     * When [framing] is true, curves that never decay below [threshold] within the
     * window — persistent long-acting background like a daily maintenance med —
     * are dropped from the envelope so they do not drag the default frame out to
     * days and crush the acute action. They still count toward the full scrollable
     * extent. If every curve is persistent, none are dropped, so a
     * long-acting-only day still shows its curve.
     */
    fun renderedTail(
        threshold: Double,
        framing: Boolean,
        substances: List<ActiveSubstanceState>,
        stackedGroups: List<List<ActiveSubstanceState>>,
        earliestDose: Instant,
        yNorm: Double,
        maxDose: Map<String, Double>,
        stackRedoses: Boolean,
        displayCap: Double,
    ): Double {
        val curves = mutableListOf<(Double) -> Double>()
        var upper = 1.0

        if (stackRedoses) {
            for (group in stackedGroups) {
                for (dose in group) {
                    val offset = minutesSince(earliestDose, dose.doseTimestamp)
                    upper = max(upper, offset + curveExtent(dose))
                }
                curves += { t -> stackedIntensity(t, group, earliestDose) * yNorm }
            }
        } else {
            for (s in substances) {
                val offset = minutesSince(earliestDose, s.doseTimestamp)
                val scale = heightScale(s, substances, maxDose)
                upper = max(upper, offset + curveExtent(s))
                curves += { t ->
                    val local = t - offset
                    if (local >= 0) intensity(local, s) * scale * yNorm else 0.0
                }
            }
        }
        upper = min(upper, displayCap)

        var contributing: List<(Double) -> Double> = curves
        if (framing) {
            val transient = curves.filter { it(upper) < threshold }
            if (transient.isNotEmpty()) contributing = transient
        }

        val steps = 240
        var lastActive = 0.0
        for (i in 0..steps) {
            val t = i.toDouble() / steps * upper
            var h = 0.0
            for (curve in contributing) {
                h = max(h, curve(t))
                if (h >= threshold) break
            }
            if (h >= threshold) lastActive = t
        }
        return max(lastActive, 1.0)
    }

    /**
     * Single-dose height: the saturating Hill link applied to this dose's
     * magnitude, so a lone dose and a stacked group of the same total agree on
     * height.
     *
     * The *unclamped* magnitude already encodes relative dose size, so no separate
     * multi-dose multiplier is needed. The two list parameters are retained for
     * call-site symmetry with the stacked path.
     */
    @Suppress("UNUSED_PARAMETER")
    fun heightScale(
        substance: ActiveSubstanceState,
        substances: List<ActiveSubstanceState>,
        maxDose: Map<String, Double>,
    ): Double = max(0.0001, hill(substance.doseMagnitude))

    /**
     * Highest curve peak across all substances and groups, used to normalize the
     * y-axis so the tallest curve fills the height — a lone low dose then reaches
     * the top instead of rendering as a flat sliver.
     */
    fun peakCurveValue(
        substances: List<ActiveSubstanceState>,
        stackedGroups: List<List<ActiveSubstanceState>>,
        earliestDose: Instant,
        maxDose: Map<String, Double>,
        stackRedoses: Boolean,
    ): Double {
        if (stackRedoses) {
            var maxV = 0.0
            for (group in stackedGroups) {
                val (s, e) = stackedGroupRange(group, earliestDose)
                if (e <= s) continue
                val steps = 48
                for (i in 0..steps) {
                    val t = s + i.toDouble() / steps * (e - s)
                    maxV = max(maxV, stackedIntensity(t, group, earliestDose))
                }
            }
            return max(maxV, 0.0001)
        }
        return max(
            substances.maxOfOrNull { heightScale(it, substances, maxDose) } ?: 1.0,
            0.0001,
        )
    }

    // MARK: - The heavy-dose threshold region

    /**
     * The height, in the same `0…1` units the curves are drawn in, at which the
     * substance's published heavy-dose bound falls — or null when there is no
     * honest place to put it.
     *
     * The y-axis carries **shape, not magnitude**: it is normalized so the tallest
     * curve fills the height, so a height only means something relative to the
     * other curves sharing it. This therefore answers only for a graph showing
     * **one substance**, where "relative to the other curves" collapses to
     * "relative to itself". Mixed graphs get null rather than a line whose
     * position would move when an unrelated dose is logged.
     *
     * It also returns null whenever the answer is off the top of the graph, which
     * is the common case and the important one: a dose below the heavy bound puts
     * the threshold *above* the curve's peak. Nothing is drawn, and the region
     * appears exactly when the modeled level actually reaches it.
     *
     * Two renderers, two arithmetics, because they normalize differently; both
     * give 1.0 — the curve's own crest — for a dose sitting exactly on the bound,
     * which is the shared check that they agree.
     */
    fun heavyThresholdHeight(
        substances: List<ActiveSubstanceState>,
        stackedGroups: List<List<ActiveSubstanceState>>,
        stackRedoses: Boolean,
        yNormalization: Double,
    ): Double? {
        val first = substances.firstOrNull() ?: return null
        val name = first.substanceName.lowercase()
        if (!substances.all { it.substanceName.lowercase() == name }) return null

        // Every dose must agree on the bound: they will, being the same substance
        // and route ladder, but a disagreement means one was scaled off a
        // fallback and the region would be meaningless.
        val thresholds = substances.map { it.heavyThresholdMagnitude }
        val threshold = thresholds.firstOrNull() ?: return null
        if (threshold <= 0) return null
        if (!thresholds.all { it == threshold }) return null

        val height: Double
        if (stackRedoses) {
            if (stackedGroups.size != 1) return null
            height = hill(threshold) * yNormalization
        } else {
            if (substances.size != 1) return null
            height = threshold / max(first.doseMagnitude, 0.0001)
        }
        // Above the plot, or flush with its ceiling where the band would be a
        // hairline nobody can read.
        if (height <= 0 || height > 0.98) return null
        return height
    }

    /**
     * Compression exponent applied to each curve's *amplitude* — the peak height
     * it is scaled to — never to its time-varying shape.
     *
     * Linear makes a threshold dose beside a heavy one collapse to an unreadable
     * sliver; below 1 lifts the low end while pinning the tallest curve at full
     * height and preserving dose ordering. Because only the amplitude is scaled,
     * the phase proportions and relative tail length are untouched.
     */
    const val AMPLITUDE_GAMMA: Double = 0.5

    /** Map a linear normalized amplitude in `[0, 1]` to its display height. */
    fun compressedAmplitude(amplitude: Double): Double =
        min(max(amplitude, 0.0), 1.0).pow(AMPLITUDE_GAMMA)

    // MARK: - Intensity

    /**
     * Normalized `[0, 1]` effect intensity at [minutes] past the dose.
     *
     * Delegates the shape to [effectShape] and, for releasers, crashes the
     * descending limb faster than the listed offset via [toleranceGate]. The curve
     * is built from the duration phases, not a plasma-concentration fit.
     */
    fun intensity(minutes: Double, s: ActiveSubstanceState): Double {
        val shape = effectShape(minutes, s)
        if (shape <= 0) return 0.0
        return shape * toleranceGate(minutes, s)
    }

    /** Zero-order kinetics paired with the logged dose in mg, or null. */
    fun zeroOrderKinetics(s: ActiveSubstanceState): Pair<PKModel.ZeroOrderKinetics, Double>? =
        zeroOrderKinetics(s.zeroOrder, s.amount, s.unit)

    /**
     * The kinetics paired with the dose in milligrams, so the state *builder* can
     * consult the same model the curve uses before a state exists. Null for a
     * first-order substance or an unreadable dose.
     */
    fun zeroOrderKinetics(
        kinetics: PKModel.ZeroOrderKinetics?,
        amount: Double,
        unit: String,
    ): Pair<PKModel.ZeroOrderKinetics, Double>? {
        val k = kinetics ?: return null
        val mg = zeroOrderDoseMilligrams(amount, unit) ?: return null
        return k to mg
    }

    /**
     * Dose-scaled phase boundaries in minutes for a zero-order substance, derived
     * from its own kinetics so the phase bar, the phase-band coloring, the
     * now-line active window and the elapsed/remaining readout all track the same
     * clock as the curve — which for alcohol *grows with dose* rather than sitting
     * on a fixed profile.
     *
     * The curve is an absorption rise to a peak followed by a long linear decline
     * to clearance, so the whole descending limb maps to a single wide offset and
     * there is no afterglow.
     */
    data class ZeroOrderBoundaries(
        val onsetEnd: Double,
        val comeupEnd: Double,
        val peakEnd: Double,
        val offsetEnd: Double,
        val total: Double,
    )

    fun zeroOrderBoundaries(
        kinetics: PKModel.ZeroOrderKinetics?,
        amount: Double,
        unit: String,
    ): ZeroOrderBoundaries? {
        val (k, doseMg) = zeroOrderKinetics(kinetics, amount, unit) ?: return null
        val peak = PKModel.zeroOrderPeakMinutes(doseMg, k)
        val clear = PKModel.zeroOrderClearMinutes(doseMg, k)
        if (peak <= 0 || clear <= peak) return null
        // A short crest around the peak, clamped so it never crosses into the decline.
        val peakEnd = min(peak * 1.15, (peak + clear) / 2)
        return ZeroOrderBoundaries(
            onsetEnd = peak * 0.35,
            comeupEnd = peak * 0.85,
            peakEnd = peakEnd,
            offsetEnd = clear,
            total = clear,
        )
    }

    /**
     * The logged amount in milligrams of ethanol.
     *
     * Mass units convert directly; colloquial **drink** and **unit** counts
     * convert at the standard-drink mass, so alcohol logged as "2 drinks" still
     * drives the zero-order model instead of falling back to the generic phase
     * bell. A volume unit is not a mass and returns null, so the caller falls back
     * rather than mistaking millilitres for milligrams.
     */
    fun zeroOrderDoseMilligrams(amount: Double, unit: String): Double? {
        if (!amount.isFinite() || amount <= 0) return null
        return when (unit.trim().lowercase()) {
            "g", "gram", "grams" -> amount * 1_000
            "mg", "milligram", "milligrams" -> amount
            "drink", "drinks", "unit", "units", "standard drink", "standard drinks" ->
                amount * US_STANDARD_DRINK_GRAMS * 1_000
            else -> null
        }
    }

    /**
     * The subjective effect-strength curve in `[0, 1]`, built directly from the
     * dose's duration phases rather than a plasma-concentration model. The graph
     * shows *how strong the effects feel*, not blood level.
     *
     * The shape is a **split flat-top generalized Gaussian times a bounded crest
     * dome** — one smooth arc through the phase anchors instead of pieces joined
     * at phase boundaries. Per side of the crest instant μ:
     *
     * ```
     * shape(t) = exp(−½·(|t−μ|/σ)^p) · dome(|t−μ|/S)
     * ```
     *
     * with `(σ, p)` solved in closed form so the curve passes the foot anchor at
     * the end of onset, the crest anchor at the end of come-up, the crest again at
     * the end of peak, and the tail anchor at the end of offset. The dome sags at
     * most [EffectCurveParams.DOME_SAG] far from the crest, so a long peak reads as
     * a gently domed plateau, and the product is C¹ everywhere: monotone rise, one
     * crest, monotone fall, with no false cap or slope break at the crest's edges.
     */
    fun effectShape(minutes: Double, s: ActiveSubstanceState): Double {
        if (minutes < 0) return 0.0
        // A zero-order substance ignores the fixed phase windows: its curve is a
        // dose-scaled linear-decline shape, so duration grows with dose — the
        // defining property the bell cannot show.
        zeroOrderKinetics(s)?.let { (k, doseMg) ->
            PKModel.zeroOrderShape(doseMg, minutes, k)?.let { return it }
        }
        return EffectCurveParams.of(s).valueAt(minutes)
    }

    /**
     * All fit parameters for one dose's effect curve — a pure function of the
     * state, cheap enough to compute per call: one closed-form solve per side.
     */
    data class EffectCurveParams(
        val mu: Double,
        val sigmaUp: Double,
        val exponentUp: Double,
        val domeScaleUp: Double,
        val sigmaDown: Double,
        val exponentDown: Double,
        val domeScaleDown: Double,
    ) {
        companion object {
            /** Height where come-up ends and offset begins. */
            const val CREST_HI = 0.92

            /** Height at the end of onset. */
            const val FOOT_LO = 0.03

            /** Height at the end of offset, plus half its spread. */
            const val TAIL_LO = 0.03

            /** Max crest sag: the dome never drops below `1 − DOME_SAG`. */
            const val DOME_SAG = 0.10

            /** The crest instant sits this far into the come-up-end to peak-end window. */
            const val PEAK_BIAS = 0.40

            /**
             * Exponent clamps. The floor keeps every side at least
             * super-Gaussian (C² across the crest); the fall ceiling stops a short
             * offset window from fitting a cliff, and the rise ceiling only bounds
             * genuine sub-minute intravenous walls.
             */
            const val EXPONENT_FLOOR = 1.6
            const val RISE_CEILING = 1_000.0
            const val FALL_CEILING = 6.0

            fun of(s: ActiveSubstanceState): EffectCurveParams {
                val a = s.onsetEndMinutes
                val c = max(s.peakEndMinutes, a + 2)
                val d = max(s.offsetEndMinutes, c + 1)
                val b = effectiveComeupEnd(s, a, c)

                // A phase's spread widens the feature it bounds: come-up spread
                // delays the full-effect anchor, offset spread extends the tail
                // landing. Peak spread broadens the dome scale below instead of
                // moving the C anchor — moving C right compresses the C-to-D
                // descent and manufactures a cliff. The onset spread is
                // deliberately unused: anchoring the foot at the onset range's
                // early bound drew 10-30% effect through the onset window.
                val bAnchor = b + (s.comeupSpreadMinutes ?: 0.0) / 2
                val cAnchor = max(c, bAnchor + 1e-3)
                val dAnchor = max(d + (s.offsetSpreadMinutes ?: 0.0) / 2, cAnchor + 1)
                val mu = bAnchor + PEAK_BIAS * (cAnchor - bAnchor)

                val rUp = max(mu - bAnchor, 1e-3)
                val rDown = max(cAnchor - mu, 1e-3)
                val domeHalf = (s.peakSpreadMinutes ?: 0.0) / 2
                val domeScaleUp = rUp + domeHalf
                val domeScaleDown = rDown + domeHalf

                // The dome's value at a fixed u is a constant, so dividing it out
                // of each anchor target leaves the core alone to solve in closed
                // form — both anchors then hold exactly for the product.
                val (sigmaUp, exponentUp) = fit(
                    near = rUp, far = mu - a,
                    hi = CREST_HI / dome(rUp / domeScaleUp),
                    lo = FOOT_LO / dome((mu - a) / domeScaleUp),
                    ceiling = RISE_CEILING, keepFarOnClamp = false,
                )
                val (sigmaDown, exponentDown) = fit(
                    near = rDown, far = dAnchor - mu,
                    hi = CREST_HI / dome(rDown / domeScaleDown),
                    lo = TAIL_LO / dome((dAnchor - mu) / domeScaleDown),
                    ceiling = FALL_CEILING, keepFarOnClamp = true,
                )

                return EffectCurveParams(
                    mu, sigmaUp, exponentUp, domeScaleUp,
                    sigmaDown, exponentDown, domeScaleDown,
                )
            }

            /**
             * Bounded crest sag: 1 at the crest, easing toward `1 − DOME_SAG` far
             * away — with zero slope at the crest, so the two sides meet C¹.
             */
            fun dome(u: Double): Double = 1 - DOME_SAG * (1 - exp(-0.5 * u * u))

            /**
             * Two-anchor closed-form solve for `(σ, p)`. Both anchors hold exactly
             * while `p` lands inside its clamp; a clamped `p` honors one anchor,
             * chosen by [keepFarOnClamp] — the fall keeps "effects ended" where the
             * data put it, the rise keeps full effect at the come-up end.
             */
            fun fit(
                near: Double,
                far: Double,
                hi: Double,
                lo: Double,
                ceiling: Double,
                keepFarOnClamp: Boolean,
            ): Pair<Double, Double> {
                val rNear = max(near, 1e-3)
                val rFar = max(far, rNear * 1.005)
                val hNear = -2 * ln(min(hi, 0.995))
                val hFar = -2 * ln(min(max(lo, 1e-6), 0.9))
                val exact = ln(hFar / hNear) / ln(rFar / rNear)
                val p = min(max(exact, EXPONENT_FLOOR), ceiling)
                val sigma = if (p != exact && keepFarOnClamp) {
                    rFar / hFar.pow(1 / p)
                } else {
                    rNear / hNear.pow(1 / p)
                }
                return sigma to p
            }
        }

        fun valueAt(minutes: Double): Double {
            val fromCrest = minutes - mu
            val z: Double
            val u: Double
            val p: Double
            if (fromCrest < 0) {
                z = -fromCrest / sigmaUp
                u = -fromCrest / domeScaleUp
                p = exponentUp
            } else {
                z = fromCrest / sigmaDown
                u = fromCrest / domeScaleDown
                p = exponentDown
            }
            val core = if (z > 0) exp(-0.5 * z.pow(p)) else 1.0
            return core * dome(u)
        }
    }

    /**
     * The come-up boundary the rising shoulder is fit to — synthesizing a
     * plausible climb when the source data carries **no come-up phase**.
     *
     * Many profiles list only onset then peak, so the come-up boundary collapses
     * onto the onset end. When the explicit window is essentially empty this
     * borrows a come-up from the dose's own timing: as long as the onset itself,
     * floored at [COMEUP_FLOOR_MINUTES], but capped at 60% of the onset-to-peak
     * gap so a flat peak still remains.
     *
     * Never floor this at a bare `onsetEnd + 1`: a one-minute window makes the
     * curve shoot up as a near-vertical wall, wrong for an absorbed dose.
     * Symmetrically, never use a flat `onsetEnd * 0.6` floor, which would give
     * every route at least an eight-minute climb and draw an insufflated or
     * intravenous dose with a two-minute onset an absorption shoulder it does not
     * have. The floor is itself capped by the onset, so route falls out of the
     * data.
     */
    fun effectiveComeupEnd(s: ActiveSubstanceState, onsetEnd: Double, peakEnd: Double): Double {
        val explicit = s.comeupEndMinutes - onsetEnd
        val gap = peakEnd - onsetEnd
        val floor = min(COMEUP_FLOOR_MINUTES, max(onsetEnd, 0.0))
        val window = if (explicit > 1) {
            explicit
        } else {
            min(max(onsetEnd * 0.6, floor), gap * 0.5)
        }
        return onsetEnd + max(window, 1e-3)
    }

    /**
     * The longest come-up the model will synthesize, and only for a dose whose
     * onset is at least this long.
     */
    const val COMEUP_FLOOR_MINUTES: Double = 8.0

    /**
     * Acute-tolerance multiplier on the descending limb.
     *
     * For a tachyphylaxis of zero it is identity, so non-tolerant compounds keep
     * the pure offset. For releasers the felt effect crashes faster than plasma:
     * across the offset window we fade the curve by up to the tachyphylaxis value
     * via a smoothstep, so it lands at baseline by the total rather than trailing
     * off on the slow elimination tail. Onset and peak are untouched.
     */
    fun toleranceGate(minutes: Double, s: ActiveSubstanceState): Double {
        val kappa = s.tachyphylaxis
        if (kappa <= 0) return 1.0
        val peakEnd = s.peakEndMinutes
        val end = max(s.totalMinutes, peakEnd + 1)
        if (minutes <= peakEnd) return 1.0
        val x = min(1.0, (minutes - peakEnd) / (end - peakEnd))
        val smooth = x * x * (3 - 2 * x)
        return max(0.0, 1 - kappa * smooth)
    }

    /**
     * Minutes after the dose at which the curve has returned to baseline — the
     * point past which nothing remains to draw.
     *
     * The phase curve eases to zero by the end of the offset, so the draw end is
     * simply that point: no long elimination tail to chase, and no flat near-zero
     * skirt stretching the axis. Capped at the display window.
     */
    fun curveExtent(s: ActiveSubstanceState): Double {
        // A zero-order substance clears in a dose-scaled time, not at a fixed offset.
        zeroOrderKinetics(s)?.let { (k, doseMg) ->
            val clear = PKModel.zeroOrderClearMinutes(doseMg, k)
            if (clear > 0) return min(max(clear * 1.04, 1.0), MAX_DISPLAY_MINUTES)
        }
        // The falling limb crosses a threshold at mu + sigmaDown * (-2 ln theta)^(1/q);
        // at 1.5% the curve has just left the tail anchor and lands on the axis
        // instead of being clipped. The dome is ignored — it only lowers the value,
        // so this errs long.
        val params = EffectCurveParams.of(s)
        val end = params.mu + params.sigmaDown * (-2 * ln(0.015)).pow(1 / params.exponentDown)
        return min(max(end, 1.0), MAX_DISPLAY_MINUTES)
    }

    /**
     * Minutes after the dose past which this curve is no longer *visible* beside a
     * curve of magnitude [peerMagnitude] — as opposed to [curveExtent], which asks
     * only when the curve reaches its own baseline.
     *
     * The two differ by a lot. [curveExtent] is amplitude-blind: it ends each
     * curve at one or two percent of *its own* peak. A small or heavily-tolerant
     * dose beside a tall one is drawn against the tall one's scale, so its last
     * hours sit far below one device pixel while still sizing the axis — a
     * long-acting background dose could hold the window open with nothing on it.
     *
     * Solved in closed form rather than by sampling, so it is cheap enough to call
     * from a draw pass, and it never returns more than [curveExtent], so the
     * window can only shrink. The tolerance gate and the crest dome are
     * deliberately ignored: both only pull the value down, so skipping them errs
     * toward keeping pixels that might be visible.
     */
    fun visibleExtent(
        s: ActiveSubstanceState,
        peerMagnitude: Double,
        threshold: Double = 0.02,
    ): Double {
        val full = curveExtent(s)
        // A zero-order tail is not a generalized Gaussian — leave its extent alone.
        if (zeroOrderKinetics(s) != null) return full

        val magnitude = max(s.doseMagnitude, 0.0)
        val peer = max(peerMagnitude, magnitude)
        if (magnitude <= 0 || peer <= 0 || threshold <= 0) return full

        val params = EffectCurveParams.of(s)
        // The shape value at which this dose stops registering against the peer.
        val cutoff = threshold * peer / magnitude
        // Already below the bar at its own crest: let its own peak bound it rather
        // than an invisible tail.
        if (cutoff >= 1) return min(max(params.mu, 1.0), full)
        val t = params.mu + params.sigmaDown * (-2 * ln(cutoff)).pow(1 / params.exponentDown)
        return min(max(t, 1.0), full)
    }

    // MARK: - The stacked merge

    /**
     * Group substance states by lowercased substance name **and route**,
     * preserving first-dose order.
     *
     * The route is part of the key so insufflated and smoked doses of one
     * substance draw as separate curves even when combining repeated entries;
     * doses of the same substance by the same route still stack into one curve.
     */
    fun stackedGroups(substances: List<ActiveSubstanceState>): List<List<ActiveSubstanceState>> {
        val order = mutableListOf<String>()
        val buckets = mutableMapOf<String, MutableList<ActiveSubstanceState>>()
        for (s in substances) {
            val key = "${s.substanceName.lowercase()}|${s.route.lowercase()}"
            if (key !in buckets) {
                order += key
                buckets[key] = mutableListOf()
            }
            buckets.getValue(key).add(s)
        }
        return order.map { buckets.getValue(it) }
    }

    /**
     * The combined intensity of a group at a global time, in minutes since
     * [earliestDose] — linear dose superposition passed through one saturating
     * Hill link.
     *
     * Each dose contributes `magnitude × bell`; summing the *unclamped*
     * magnitudes means a genuine four-times stack reaches four times the input,
     * and a single combined dose of the same total lands identically — so
     * `4×20 mg ≡ 1×80 mg` falls out for free. Hill then saturates the sum, so
     * overlapping crests flatten while doses spaced wider than their bells stay
     * distinct humps.
     */
    fun stackedIntensity(
        global: Double,
        group: List<ActiveSubstanceState>,
        earliestDose: Instant,
    ): Double {
        var sum = 0.0
        for (dose in group) {
            val offset = minutesSince(earliestDose, dose.doseTimestamp)
            val local = global - offset
            if (local < 0) continue
            sum += dose.doseMagnitude * intensity(local, dose)
        }
        return hill(sum)
    }

    /**
     * Half-saturation point of the effect link, in dose-magnitude units.
     *
     * Pinned *above* a typical single dose so single doses live in Hill's
     * near-linear region — their come-up stays uncompressed — while stacks push
     * into saturation and flatten. The `4×20 ≡ 1×80` superposition invariant is
     * what this value and [HILL_EXPONENT] were tuned against.
     */
    const val HILL_EC50: Double = 0.78

    /** Hill exponent: saturation sharpness and stacking dynamic range. */
    const val HILL_EXPONENT: Double = 1.4

    /**
     * The saturating dose-to-height link `Cʰ / (EC50ʰ + Cʰ)`, with Emax 1.
     *
     * Applied once to the superposed dose magnitude, so a single dose and a stack
     * of the same total render identically and overlapping crests saturate flat.
     */
    fun hill(magnitude: Double): Double {
        if (magnitude <= 0) return 0.0
        val m = magnitude.pow(HILL_EXPONENT)
        return m / (HILL_EC50.pow(HILL_EXPONENT) + m)
    }

    /** The first and last minute a stacked group draws at, relative to [earliestDose]. */
    fun stackedGroupRange(
        group: List<ActiveSubstanceState>,
        earliestDose: Instant,
    ): Pair<Double, Double> {
        var start = Double.MAX_VALUE
        var end = 0.0
        for (dose in group) {
            val offset = minutesSince(earliestDose, dose.doseTimestamp)
            start = min(start, offset)
            end = max(end, offset + curveExtent(dose))
        }
        return start to end
    }

    // MARK: - Lane layout

    /**
     * One lane per distinct substance, in first-dose order, carrying every dose of
     * that substance so redoses share a lane.
     */
    fun laneGroups(substances: List<ActiveSubstanceState>): List<LaneGroup> {
        val order = mutableListOf<String>()
        val doses = mutableMapOf<String, MutableList<ActiveSubstanceState>>()
        val colorOf = mutableMapOf<String, P3Color>()
        for (s in substances) {
            val key = s.substanceName.lowercase()
            if (key !in doses) {
                order += key
                colorOf[key] = s.tint
            }
            doses.getOrPut(key) { mutableListOf() }.add(s)
        }
        return order.map { key ->
            val list = doses.getValue(key)
            LaneGroup(name = list.first().substanceName, tint = colorOf.getValue(key), doses = list)
        }
    }

    /**
     * Distinct marker substances with no curve lane, in first-dose order — each
     * becomes its own labeled lane so a logged dose never floats unattached.
     */
    fun markerOnlyLanes(
        curveLanes: List<LaneGroup>,
        markers: List<DoseMarker>,
    ): List<MarkerLane> {
        val curveNames = curveLanes.map { it.name.lowercase() }.toSet()
        val order = mutableListOf<String>()
        val byKey = mutableMapOf<String, MutableList<DoseMarker>>()
        val meta = mutableMapOf<String, Pair<String, P3Color>>()
        for (marker in markers) {
            val key = marker.substanceName.lowercase()
            if (key in curveNames) continue
            if (key !in byKey) {
                order += key
                meta[key] = marker.substanceName to marker.tint
            }
            byKey.getOrPut(key) { mutableListOf() }.add(marker)
        }
        return order.map { key ->
            MarkerLane(
                name = meta.getValue(key).first,
                tint = meta.getValue(key).second,
                markers = byKey.getValue(key),
            )
        }
    }

    // MARK: - Time

    /** Minutes from [earlier] to [later], possibly negative. */
    private fun minutesSince(earlier: Instant, later: Instant): Double =
        Duration.between(earlier, later).toMillis() / 60_000.0
}
