package glass.kagerou.piru.engine

import glass.kagerou.piru.model.ConfidenceTier
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * The pure right-shift integrator: given time-resolved occupancy contributions
 * over a window, advance a tolerance class's four ln-shift layers.
 *
 * Ported from `ToleranceStore.integrateTarget` in `Piru/Data/Tolerance/ToleranceStore.swift`.
 *
 * ## What it computes
 * Each substance-dose contributes an occupancy curve at every target it engages;
 * contributions at a *shared* target combine by competitive Gaddum summation; and
 * that combined occupancy drives the per-class layers, each on its own time
 * constant. The layers sum into the class's shift factor `S`, which is the number
 * everything else reads — `1` is naïve, larger means the same dose does less.
 *
 * ## Why it is near-linear rather than quadratic in the dose history
 * A dose's occupancy decays to nothing within a handful of half-lives, so
 * re-evaluating every dose at every step across a year-long window is wasteful.
 * Each contributor instead carries an **active window** — where its occupancy
 * crosses [OCCUPANCY_PRUNE_EPSILON], from [decayWindowMinutes] — and the
 * integrator fine-steps only inside the merged windows while crossing idle gaps
 * with the **exact closed-form layer decay**. The result agrees with a dense grid
 * to well below the integer percent the UI shows.
 *
 * ## Everything here is a value
 * No store, no database, no clock. The contributors carry absolute minutes and
 * the window is `[0, totalMinutes]`, so the same call on the same inputs gives the
 * same numbers however it is scheduled — which is what makes a year-long replay
 * something a test can pin.
 */
object ToleranceIntegrator {

    /**
     * Occupancy below which a contributor counts as fully decayed and is dropped.
     *
     * At 1e-9 the discarded tail's effect on the ln-shift layers is far below the
     * integer percent the UI displays, while a larger epsilon would start pruning
     * contributors that still contribute visibly.
     */
    const val OCCUPANCY_PRUNE_EPSILON: Double = 1e-9

    /**
     * One substance-dose's occupancy contribution at one target.
     *
     * The fields come in two kinds, and the split matters: the PK ones ([ke], [ka],
     * [prefactorNanomolar], [halfMaxNanomolar]) shape the curve, while [escalation],
     * [suppressesSynthesis] and [intrinsicEfficacy] are facts about the *dose* and
     * are identical across all of that dose's targets.
     */
    data class Contributor(
        /** Minutes from the window's start to this contribution's onset. */
        val onset: Double,
        /** Minutes at which its occupancy has decayed below the prune epsilon — its active window's end. */
        val expiry: Double,
        val ke: Double,
        val ka: Double,
        /**
         * The millimolar-to-nanomolar prefactor that turns the unit PK curve into a
         * concentration: `F·dose·doseScale·fu·1e9 / (Vd·weight·molarMass·1000)`.
         */
        val prefactorNanomolar: Double,
        val halfMaxNanomolar: Double,
        val confidence: ConfidenceTier,
        /**
         * Dose-relative **escalation** `dose ÷ the substance's heavy ceiling` — the
         * deep-layer gate's magnitude signal.
         *
         * `0` when the substance has no reference dose, which keeps the gate closed;
         * that is the conservative direction, since no deep tolerance accrues
         * without evidence of how much constitutes "heavy".
         */
        val escalation: Double,
        /** Whether the source substance suppresses serotonin synthesis — drives the SERT class's slow pool. */
        val suppressesSynthesis: Boolean,
        /** Source substance's intrinsic efficacy in `(0, 1]`, scaling the adaptive and synthesis drive. */
        val intrinsicEfficacy: Double,
        /**
         * Whether this is a **spawned active metabolite** rather than a logged dose.
         *
         * Its onset is the parent's onset plus Tmax, which is not a dosing event, so
         * it must be excluded from any schedule-regularity measure — otherwise a
         * regular daily course reads as irregular with its cadence halved by the
         * interleaved metabolite onsets.
         */
        val isMetabolite: Boolean = false,
    )

    /** One co-active substance's tolerance-modulation contribution at one affected class. */
    data class ModulatorContributor(
        val onset: Double,
        val expiry: Double,
        val ke: Double,
        val ka: Double,
        val prefactorNanomolar: Double,
        val halfMaxNanomolar: Double,
        /**
         * The factor the adaptive layer's drive is scaled toward while this modulator
         * is aboard: `drive *= 1 − (1 − muFactor)·presence`.
         */
        val muFactor: Double,
    )

    /**
     * The integrator's output: the four ln-shift layers, the safety endpoint's two,
     * the chronicity accumulator, and the per-effect shifts.
     *
     * Deliberately *not* the card's view model. Upstream's `ClassTolerance` also
     * carries the representative occupancy, the sub-target list, the contributing
     * substance names and a cached confidence — all of which are aggregation over a
     * dose log rather than integration over occupancy.
     */
    data class Layers(
        /** Within-session tachyphylaxis — the redose loop. */
        val sAcute: Double,
        /** The days-to-weeks baseline shift people mean by "tolerance". */
        val sAdaptive: Double,
        /** Entrenched, months-scale neuroadaptation. Gated off for therapeutic users. */
        val sDeep: Double,
        /** The slow serotonin-synthesis pool, driven only by the synthesis-suppressing SERT releasers. */
        val sSynthesis: Double,
        /** The differential safety endpoint's acute layer. */
        val sAcuteSafety: Double,
        /** The differential safety endpoint's adaptive layer. */
        val sAdaptiveSafety: Double,
        /**
         * The chronicity duty-cycle accumulator in `[0, 1]` — a leaky time-averaged
         * occupancy that, with the escalation magnitude, gates the deep layer.
         */
        val chronicExposure: Double,
        /** Per-effect ln-shifts, exp'd into [effectShifts]. */
        val effectShiftLayers: Map<ReceptorClasses.EffectAxis, Double> = emptyMap(),
        /** Whether the class has a safety endpoint at all — see [safetyShiftFactor]. */
        val hasSafetyEndpoint: Boolean = false,
    ) {

        /**
         * The total dose-response right-shift `S = exp(sAcute + sAdaptive + sDeep + sSynthesis)`,
         * always at least 1.
         *
         * `1` is naïve; larger means the curve has shifted further right, so the
         * same dose does less. The synthesis layer is what keeps an MDMA-type
         * entactogen toleranced for weeks after the fast pool has relaxed.
         */
        val shiftFactor: Double get() = exp(sAcute + sAdaptive + sDeep + sSynthesis)

        /**
         * The differential safety endpoint's own right-shift, or **null** when the
         * class has no endpoint.
         *
         * Null rather than `1` on purpose: "the endpoint was measured and has not
         * tolerized" and "there is no endpoint to measure" are different claims, and
         * the stimulant cardiovascular endpoint genuinely sits at 1 forever.
         */
        val safetyShiftFactor: Double?
            get() = if (hasSafetyEndpoint) exp(sAcuteSafety + sAdaptiveSafety) else null

        /**
         * The **danger ratio** between the desired effect and the safety endpoint,
         * or null for a class without one.
         *
         * Above 1 means the desired effect is more toleranced than the safety
         * endpoint — the gap that makes a reset dose dangerous, because analgesia
         * has outrun respiratory protection. For the stimulant, where the endpoint
         * does not tolerize, it collapses to [shiftFactor]: how far the high has
         * pulled ahead of the pressor.
         */
        val safetyGap: Double?
            get() = safetyShiftFactor?.let { shiftFactor / max(1.0, it) }

        /** Per-effect right-shift, exp'd and at least 1. Empty for a class with one undifferentiated gauge. */
        val effectShifts: Map<ReceptorClasses.EffectAxis, Double>
            get() = effectShiftLayers.mapValues { exp(it.value) }

        /**
         * The gauge: the fraction of the naïve effect felt at the usual dose under
         * the current right-shift. `1` is full, and it falls as `S` grows.
         */
        fun responseFraction(receptorClass: ReceptorClasses.ReceptorClass, representativeOccupancy: Double): Double =
            PDModel.responseFraction(
                shiftFactor = shiftFactor,
                representativeOccupancy = representativeOccupancy,
                occupancyCap = receptorClass.gaugeOccupancyCap,
            )

        /**
         * The fraction of naïve effect left at the usual dose for one **ladder**
         * effect.
         *
         * The class's primary axis (GABA's sedation) reads the class [shiftFactor];
         * every other effect reads its own layer. Null when the effect is not modeled
         * for this class, so a caller renders no row rather than a made-up 1.
         */
        fun responseFraction(
            axis: ReceptorClasses.EffectAxis,
            params: ReceptorClasses.Parameters,
            receptorClass: ReceptorClasses.ReceptorClass,
            representativeOccupancy: Double,
        ): Double? {
            val shift = when {
                params.primaryEffectAxis == axis -> shiftFactor
                effectShiftLayers.containsKey(axis) -> exp(effectShiftLayers.getValue(axis))
                else -> return null
            }
            return PDModel.responseFraction(
                shiftFactor = shift,
                representativeOccupancy = representativeOccupancy,
                occupancyCap = receptorClass.gaugeOccupancyCap,
            )
        }
    }

    /**
     * Minutes after onset by which a contributor's occupancy has decayed below
     * `epsOcc` — its active-window length.
     *
     * Inverts the Hill isotherm and the one-compartment decay tail: occupancy below
     * ε means `C < ε/(1−ε)·K`, and on the tail the concentration is approximately
     * `prefactor·(ka/(ka−ke))·e^(−ke·dt)`, so `dt = ln(peakAmplitude/Cthreshold)/ke`.
     *
     * Two deliberate over-estimates, both in the safe direction: the window always
     * covers at least the absorption peak, so a contributor is never dropped before
     * it has acted; and the `ka/(ka−ke)` coefficient over-states the amplitude, so
     * the window errs long. A too-long window costs a few extra grid cells; a
     * too-short one silently drops real tolerance.
     */
    fun decayWindowMinutes(
        ke: Double,
        ka: Double,
        prefactorNanomolar: Double,
        halfMaxNanomolar: Double,
        epsOcc: Double = OCCUPANCY_PRUNE_EPSILON,
    ): Double {
        if (ke <= 0 || ka <= 0 || halfMaxNanomolar <= 0 || prefactorNanomolar <= 0) return 0.0
        val tmax = PKModel.tmax(ke, ka)
        val concentrationThreshold = (epsOcc / (1 - epsOcc)) * halfMaxNanomolar
        val coefficient = if (abs(ka - ke) < 1e-10) 1.0 else ka / (ka - ke)
        val peakAmplitude = prefactorNanomolar * max(coefficient, 1e-300)
        val ratio = peakAmplitude / concentrationThreshold
        val tailMinutes = if (ratio > 1) ln(ratio) / ke else 0.0
        return max(tmax * 1.5, tailMinutes)
    }

    /**
     * Integrate one class's layers over `[0, totalMinutes]`, fine-stepping only
     * inside the contributors' merged active windows.
     *
     * @param step the grid step inside an active window. Idle spans are crossed
     *   analytically, so a large step costs accuracy only where occupancy is
     *   actually changing.
     * @param regularityFactor the adaptive layer's schedule-regularity gain: the
     *   same exposure on a regular cadence anticipates more than erratic dosing. `1`
     *   for perfectly regular and for fewer than three doses, at most 1 otherwise —
     *   it never boosts above the ceiling.
     */
    fun integrate(
        contributors: List<Contributor>,
        modulators: List<ModulatorContributor> = emptyList(),
        params: ReceptorClasses.Parameters,
        totalMinutes: Double,
        step: Double,
        regularityFactor: Double = 1.0,
    ): Layers {
        var sAcute = 0.0
        var sAdaptive = 0.0
        var sDeep = 0.0
        var sSynthesis = 0.0
        var chronicExposure = 0.0
        var sAcuteSafety = 0.0
        var sAdaptiveSafety = 0.0
        val safetyEndpoint = params.safetyEndpoint
        val effectEndpoints = params.effectEndpoints
        val sEffect = DoubleArray(effectEndpoints.size)

        fun snapshot() = Layers(
            sAcute = sAcute,
            sAdaptive = sAdaptive,
            sDeep = sDeep,
            sSynthesis = sSynthesis,
            sAcuteSafety = sAcuteSafety,
            sAdaptiveSafety = sAdaptiveSafety,
            chronicExposure = chronicExposure,
            effectShiftLayers = effectEndpoints.indices.associate { effectEndpoints[it].axis to sEffect[it] },
            hasSafetyEndpoint = safetyEndpoint != null,
        )

        if (totalMinutes <= 0 || step <= 0 || contributors.isEmpty()) return snapshot()

        val sortedContributors = contributors.sortedBy { it.onset }
        val sortedModulators = modulators.sortedBy { it.onset }

        // Merge contributor windows into the disjoint intervals where *some*
        // contributor is active. Outside them occupancy is zero, so every layer
        // decays analytically — no need to walk the grid.
        val merged = mutableListOf<DoubleArray>() // [lo, hi]
        for (c in sortedContributors) {
            val last = merged.lastOrNull()
            if (last != null && c.onset <= last[1]) {
                last[1] = max(last[1], c.expiry)
            } else {
                merged += doubleArrayOf(c.onset, c.expiry)
            }
        }

        val lastCell = ceil(totalMinutes / step).toInt() - 1
        if (lastCell < 0) return snapshot()

        // Closed-form recovery over an idle span — exact for the linear leaky
        // integrators. The safety endpoint's layers decay on their own constants,
        // which is the source of the reset-after-break gap: the respiratory layer
        // recovers faster than the analgesic adaptive one.
        fun recover(minutes: Double) {
            if (minutes <= 0) return
            sAcute *= exp(-minutes / params.tauAcuteMinutes)
            sAdaptive *= exp(-minutes / params.tauAdaptiveMinutes)
            sDeep *= exp(-minutes / params.tauDeepMinutes)
            sSynthesis *= exp(-minutes / params.tauSynthesisMinutes)
            chronicExposure *= exp(-minutes / ReceptorClasses.TAU_CHRONIC_EXPOSURE_MINUTES)
            safetyEndpoint?.let {
                sAcuteSafety *= exp(-minutes / it.tauAcuteMinutes)
                sAdaptiveSafety *= exp(-minutes / it.tauAdaptiveMinutes)
            }
            for (i in effectEndpoints.indices) {
                sEffect[i] *= exp(-minutes / effectEndpoints[i].tauAdaptiveMinutes)
            }
        }

        val activeContributors = mutableListOf<Contributor>()
        val activeModulators = mutableListOf<ModulatorContributor>()
        var nextContributor = 0
        var nextModulator = 0
        var lastSteppedCell = -1

        for (interval in merged) {
            // Grid cells whose midpoint falls inside this window.
            val firstCell = max(0, ceil(interval[0] / step - 0.5).toInt())
            val lastCellInRun = min(lastCell, floor(interval[1] / step - 0.5).toInt())
            if (firstCell > lastCellInRun) continue

            // Jump the idle cells between the last fine-stepped one and this run.
            if (firstCell > lastSteppedCell + 1) {
                recover((firstCell - lastSteppedCell - 1) * step)
            }

            for (cell in firstCell..lastCellInRun) {
                val cellStart = cell * step
                val cellLength = min(step, totalMinutes - cellStart)
                if (cellLength <= 0) break
                val midpoint = cellStart + cellLength / 2

                while (nextContributor < sortedContributors.size &&
                    sortedContributors[nextContributor].onset <= midpoint
                ) {
                    activeContributors += sortedContributors[nextContributor]
                    nextContributor++
                }
                while (nextModulator < sortedModulators.size &&
                    sortedModulators[nextModulator].onset <= midpoint
                ) {
                    activeModulators += sortedModulators[nextModulator]
                    nextModulator++
                }

                // Combined occupancy by **competitive Gaddum summation**, the correct
                // form for several ligands at one shared site. It reduces exactly to
                // `C/(C+K)` for a single ligand, so single-substance behaviour is
                // unchanged, but it does not over-count co-occupancy the way a
                // probabilistic union would: two half-saturated ligands give 0.667,
                // not 0.75.
                var sumRatio = 0.0
                // The numerator of the occupancy-weighted intrinsic efficacy that
                // scales the adaptive and synthesis drive.
                var sumEfficacyRatio = 0.0
                var maxEscalation = 0.0
                var anySynthesisSuppressor = false
                var writeIndex = 0
                for (readIndex in activeContributors.indices) {
                    val contributor = activeContributors[readIndex]
                    if (contributor.expiry < midpoint) continue
                    if (writeIndex != readIndex) activeContributors[writeIndex] = contributor
                    writeIndex++
                    if (contributor.escalation > maxEscalation) maxEscalation = contributor.escalation
                    if (contributor.suppressesSynthesis) anySynthesisSuppressor = true
                    val concentration = contributor.prefactorNanomolar *
                        PKModel.concentration(midpoint - contributor.onset, contributor.ke, contributor.ka)
                    if (concentration > 0) {
                        val ratio = concentration / contributor.halfMaxNanomolar
                        sumRatio += ratio
                        sumEfficacyRatio += contributor.intrinsicEfficacy * ratio
                    }
                }
                while (activeContributors.size > writeIndex) activeContributors.removeAt(activeContributors.size - 1)

                val occupancy = sumRatio / (1 + sumRatio)
                // Occupancy-weighted intrinsic efficacy: full agonists leave the drive
                // unchanged, so every single-full-agonist case is untouched, while a
                // partial-agonist-dominated class entrenches less. `1` when nothing is
                // bound this cell, which is when no drive accrues anyway.
                val efficacyDrive = if (sumRatio > 0) sumEfficacyRatio / sumRatio else 1.0

                // The tolerance-modulation factor, driving the adaptive layer only.
                var modulation = 1.0
                if (activeModulators.isNotEmpty()) {
                    var modWriteIndex = 0
                    for (readIndex in activeModulators.indices) {
                        val modulator = activeModulators[readIndex]
                        if (modulator.expiry < midpoint) continue
                        if (modWriteIndex != readIndex) activeModulators[modWriteIndex] = modulator
                        modWriteIndex++
                        val freeConcentration = modulator.prefactorNanomolar *
                            PKModel.concentration(midpoint - modulator.onset, modulator.ke, modulator.ka)
                        if (freeConcentration > 0) {
                            val presence = freeConcentration / (modulator.halfMaxNanomolar + freeConcentration)
                            modulation *= (1 - (1 - modulator.muFactor) * presence)
                        }
                    }
                    while (activeModulators.size > modWriteIndex) {
                        activeModulators.removeAt(activeModulators.size - 1)
                    }
                }

                // The chronicity accumulator: a leaky integrator toward the current
                // occupancy, so many doses a day read high, a once-daily therapeutic
                // pattern about 0.1–0.2, and occasional use about zero.
                chronicExposure = PDModel.stepShift(
                    current = chronicExposure, shiftMax = 1.0, occupancy = occupancy, drive = 1.0,
                    dtMinutes = cellLength, tauMinutes = ReceptorClasses.TAU_CHRONIC_EXPOSURE_MINUTES,
                )

                // The deep layer's drive is the product of two smoothsteps: how heavy
                // dosing is against how sustained it is. Neither alone suffices — a
                // heavy one-off binge and a therapeutic daily dose both stay dark.
                // Magnitude keys on escalation rather than occupancy because saturating
                // occupancy makes therapeutic and heavy dosing look identical at the
                // receptor.
                sAcute = PDModel.stepShift(
                    current = sAcute, shiftMax = params.acuteShiftMax, occupancy = occupancy,
                    drive = 1.0, dtMinutes = cellLength, tauMinutes = params.tauAcuteMinutes,
                )
                val magnitude = PDModel.smoothstepGate(
                    maxEscalation,
                    threshold = ReceptorClasses.DEEP_MAGNITUDE_THRESHOLD,
                    width = ReceptorClasses.DEEP_MAGNITUDE_WIDTH,
                )
                val chronicity = PDModel.smoothstepGate(
                    chronicExposure,
                    threshold = ReceptorClasses.DEEP_CHRONICITY_THRESHOLD,
                    width = ReceptorClasses.DEEP_CHRONICITY_WIDTH,
                )
                val gate = magnitude * chronicity
                sAdaptive = PDModel.stepShift(
                    current = sAdaptive, shiftMax = params.adaptiveShiftMax, occupancy = occupancy,
                    drive = modulation * efficacyDrive * regularityFactor,
                    dtMinutes = cellLength, tauMinutes = params.tauAdaptiveMinutes,
                )
                sDeep = PDModel.stepShift(
                    current = sDeep, shiftMax = params.deepShiftMax, occupancy = occupancy,
                    drive = gate, dtMinutes = cellLength, tauMinutes = params.tauDeepMinutes,
                )
                // The synthesis pool: same occupancy, driven only while a
                // synthesis-suppressing contributor is active — so the entactogens
                // build it while the cathinone releasers never do. Inert for every
                // class whose ceiling is 0, whatever the drive.
                sSynthesis = PDModel.stepShift(
                    current = sSynthesis, shiftMax = params.synthesisShiftMax, occupancy = occupancy,
                    drive = (if (anySynthesisSuppressor) 1.0 else 0.0) * efficacyDrive,
                    dtMinutes = cellLength, tauMinutes = params.tauSynthesisMinutes,
                )

                // The safety endpoint's two layers: same occupancy, its own kinetics,
                // no deep layer and no escalation gate. The acute layer is undamped;
                // the adaptive layer shares the primary's modulation.
                safetyEndpoint?.let {
                    sAcuteSafety = PDModel.stepShift(
                        current = sAcuteSafety, shiftMax = it.acuteShiftMax, occupancy = occupancy,
                        drive = 1.0, dtMinutes = cellLength, tauMinutes = it.tauAcuteMinutes,
                    )
                    sAdaptiveSafety = PDModel.stepShift(
                        current = sAdaptiveSafety, shiftMax = it.adaptiveShiftMax, occupancy = occupancy,
                        drive = modulation, dtMinutes = cellLength, tauMinutes = it.tauAdaptiveMinutes,
                    )
                }

                // The effect ladder: adaptive-only, same occupancy and the primary's
                // modulation, each on its own kinetics. A zero ceiling — anxiolysis,
                // memory, coordination — never accrues, so its shift stays 1.
                for (i in effectEndpoints.indices) {
                    sEffect[i] = PDModel.stepShift(
                        current = sEffect[i], shiftMax = effectEndpoints[i].adaptiveShiftMax,
                        occupancy = occupancy, drive = modulation,
                        dtMinutes = cellLength, tauMinutes = effectEndpoints[i].tauAdaptiveMinutes,
                    )
                }
            }
            lastSteppedCell = lastCellInRun
        }

        // The final idle tail, which also covers a partial last cell exactly.
        recover(totalMinutes - (lastSteppedCell + 1) * step)
        return snapshot()
    }
}
