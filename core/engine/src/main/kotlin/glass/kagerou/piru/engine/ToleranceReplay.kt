package glass.kagerou.piru.engine

import glass.kagerou.piru.model.ConfidenceTier
import glass.kagerou.piru.model.ReceptorTargetKey
import glass.kagerou.piru.model.flooredBy

/**
 * The tolerance replay: a dose log in, one right-shift card per mechanism class
 * out.
 *
 * Ported from `ToleranceStore`'s replay path in
 * `Piru/Data/Tolerance/ToleranceStore.swift` — the part that turns doses into
 * [ToleranceIntegrator.Contributor]s, integrates them, and packs the result into
 * the values a card renders.
 *
 * ## What is deliberately not here
 * The store around it: fetching the dose log, resolving each substance's
 * pharmacology, the signature gate that skips an unchanged recompute, the
 * observable state, and persistence. All of that is I/O, so all of it belongs to
 * the caller; what is left here is deterministic and takes no clock — a caller
 * passes `nowMinutes` rather than reaching for one.
 *
 * ## One hidden input, made explicit
 * Upstream's dose loop reads the modulation edges from a process-global table
 * installed at store init. That is the only reason its signature is not honestly
 * pure, and the failure is silent: with the table empty every class develops
 * tolerance unmodulated and nothing says so. [modulations] is a parameter here.
 */
object ToleranceReplay {

    /**
     * One logged dose, reduced to what the replay reads.
     *
     * [amountMg] is already converted and non-null: an unknown dose has no
     * concentration to replay, and a dose in a unit that is not a mass has no
     * milligram equivalent — both are dropped before they get here, at the one
     * place that can tell.
     */
    data class SimDose(
        val substance: String,
        val amountMg: Double,
        /** Absolute minutes on whatever axis the caller uses. Only differences matter. */
        val timestampMinutes: Double,
    )

    /** While [affectedClass]'s modulator is present, scale its tolerance development by [muFactor]. */
    data class ModulationEdge(val affectedClass: ReceptorClasses.ReceptorClass, val muFactor: Double)

    // MARK: - The card model

    /**
     * One mechanism class's tolerance, as a card renders it.
     *
     * Ported from `ClassTolerance`. The four `s*` layers are raw integrator
     * outputs — nothing is clamped on the way out — and the displayed quantities
     * ([shiftFactor], [responseFraction], [severity], [safetyGap]) are derived.
     */
    data class ClassTolerance(
        val receptorClass: ReceptorClasses.ReceptorClass,
        /** Within-session tachyphylaxis — the redose loop. */
        val sAcute: Double,
        /** The days-to-weeks baseline shift people mean by "tolerance". */
        val sAdaptive: Double,
        /** Entrenched, months-scale neuroadaptation. */
        val sDeep: Double,
        /** The slow serotonin-synthesis pool. */
        val sSynthesis: Double,
        /** The chronicity duty-cycle accumulator in `[0, 1]`. */
        val chronicExposure: Double,
        /**
         * The class's **representative** peak occupancy: the upper median of every
         * surviving engagement's single-dose peak in this class.
         *
         * Upper median rather than the mean of two middles — averaging two adjacent
         * peaks would report an occupancy no dose in the log produced. Consumed with
         * the class's [ReceptorClasses.ReceptorClass.gaugeOccupancyCap].
         */
        val representativeOccupancy: Double,
        /** Combined occupancy across the class's contributors right now. Poor as a "still on board" signal; see `ToleranceSimulation.loadTrail`. */
        val occupancyNow: Double,
        /** Weakest link across the contributing substances' inputs and the class kinetics. */
        val confidence: ConfidenceTier,
        /** Canonical sub-targets in this class that some logged dose engaged. */
        val subTargets: List<String>,
        /** Substances still crediting this class, most recent first. */
        val contributors: List<String>,
        /**
         * The differential safety endpoint's own right-shift, or null when the class
         * has no endpoint.
         *
         * Null rather than 1: "measured and not tolerized" and "no endpoint" are
         * different claims, and the cardiovascular endpoint genuinely sits at 1 for
         * its acute layer.
         */
        val safetyShiftFactor: Double?,
        val safetyEndpointKind: ReceptorClasses.SafetyEndpoint.Kind?,
        /** Per-effect right-shift, empty for a class with one undifferentiated gauge. */
        val effectShifts: Map<ReceptorClasses.EffectAxis, Double> = emptyMap(),
    ) {
        /**
         * The total dose-response right-shift `S = exp(Σ layers)`, always at least 1.
         *
         * `1` is naïve and larger means the curve has moved right, so the same dose
         * does less. The synthesis layer is what keeps an MDMA-type entactogen
         * toleranced for weeks after the fast pool has relaxed.
         */
        val shiftFactor: Double
            get() = kotlin.math.exp(sAcute + sAdaptive + sDeep + sSynthesis)

        /**
         * The gauge: the fraction of the naïve effect felt at the usual dose. `1` is
         * full and it falls as [shiftFactor] grows.
         */
        val responseFraction: Double
            get() = PDModel.responseFraction(
                shiftFactor = shiftFactor,
                representativeOccupancy = representativeOccupancy,
                occupancyCap = receptorClass.gaugeOccupancyCap,
            )

        /** One unified "how affected" axis in `[0, 1]` for ranking and the state word. */
        val severity: Double get() = 1 - responseFraction

        /**
         * The danger ratio between the desired effect and the safety endpoint, or
         * null without one.
         *
         * Above 1 means the desired effect has outrun the endpoint — the gap that
         * makes a reset dose dangerous, because analgesia has outlasted respiratory
         * protection. For the stimulant, whose endpoint barely moves, it collapses to
         * [shiftFactor]: how far the high has pulled ahead of the pressor.
         */
        val safetyGap: Double?
            get() = safetyShiftFactor?.let { shiftFactor / maxOf(1.0, it) }

        /**
         * The fraction of naïve effect left at the usual dose for one ladder effect.
         *
         * The class's primary axis (GABA's sedation) reads [shiftFactor]; every other
         * effect reads its own layer. Null when the effect is not modeled here.
         */
        fun responseFractionForEffect(axis: ReceptorClasses.EffectAxis): Double? {
            val params = ReceptorClasses.parametersFor(receptorClass)
            val shift = when {
                params.primaryEffectAxis == axis -> shiftFactor
                effectShifts.containsKey(axis) -> effectShifts.getValue(axis)
                else -> return null
            }
            return PDModel.responseFraction(
                shiftFactor = shift,
                representativeOccupancy = representativeOccupancy,
                occupancyCap = receptorClass.gaugeOccupancyCap,
            )
        }
    }

    // MARK: - Per-class work

    /**
     * Everything one class needs to be integrated, gathered before the fan-out.
     *
     * Built once and then read-only, which is what lets the classes be integrated
     * independently — there is no cross-class state in the replay at all.
     */
    data class ClassWork(
        val receptorClass: ReceptorClasses.ReceptorClass,
        val contributors: List<ToleranceIntegrator.Contributor>,
        val modulators: List<ToleranceIntegrator.ModulatorContributor>,
        val representativeOccupancy: Double,
        val subTargets: List<String>,
        /** Substance names driving this class, most recent first. */
        val contributorSubstances: List<String>,
        /** Each name's most recent onset, in minutes. */
        val contributorOnsets: Map<String, Double>,
        val regularityFactor: Double,
    )

    /** A prepared replay: the per-class work, and the length of the window it spans. */
    data class PreparedReplay(val work: List<ClassWork>, val totalMinutes: Double)

    // MARK: - Entry points

    /**
     * Replay the log and produce one card per driven class.
     *
     * An empty result means the window or the inputs were degenerate — no dose in
     * window, a non-positive body weight, or nothing that survived the gates — not
     * that tolerance is zero. A class that is absent from a non-empty result is one
     * no dose drives.
     */
    fun simulate(
        doses: List<SimDose>,
        params: Map<String, PharmacologyParameters>,
        nowMinutes: Double,
        weightKg: Double,
        modulations: Map<ReceptorClasses.ReceptorClass, List<ModulationEdge>> = emptyMap(),
        timestepMinutes: Double = ToleranceSimulation.DEFAULT_TIMESTEP_MINUTES,
        lookbackDays: Double = ToleranceSimulation.DEFAULT_LOOKBACK_DAYS,
    ): Map<ReceptorClasses.ReceptorClass, ClassTolerance> {
        val prepared = prepare(doses, params, nowMinutes, weightKg, lookbackDays, modulations) ?: return emptyMap()
        // A linked map so the iteration order is the work order, which
        // `prepare` already made deterministic. Upstream builds this from a
        // dictionary, so its order is arbitrary; nothing reads it as a sequence,
        // but a test that prints a failure is far easier to read when it is stable.
        val out = LinkedHashMap<ReceptorClasses.ReceptorClass, ClassTolerance>()
        for (work in prepared.work) {
            out[work.receptorClass] = tolerance(work, prepared.totalMinutes, timestepMinutes)
        }
        return out
    }

    /**
     * Integrate one class's layers over the replay window.
     *
     * Nothing is clamped on the way out: the four ln-shifts and `chronicExposure`
     * are the integrator's raw output, and the derived quantities are accessors on
     * [ClassTolerance] rather than stored values. The safety endpoint's `exp` does
     * happen here, because whether there *is* an endpoint is a class fact and the
     * card needs to say null when there is not.
     */
    fun tolerance(work: ClassWork, totalMinutes: Double, step: Double): ClassTolerance {
        val params = ReceptorClasses.parametersFor(work.receptorClass)
        val state = ToleranceIntegrator.integrate(
            contributors = work.contributors,
            modulators = work.modulators,
            params = params,
            totalMinutes = totalMinutes,
            step = step,
            regularityFactor = work.regularityFactor,
        )
        // The weakest link: no contributors reads unverified, and the class's own
        // kinetics grade is then floored on top.
        val inputConfidence = work.contributors.minOfOrNull { it.confidence } ?: ConfidenceTier.UNVERIFIED
        return ClassTolerance(
            receptorClass = work.receptorClass,
            sAcute = state.sAcute,
            sAdaptive = state.sAdaptive,
            sDeep = state.sDeep,
            sSynthesis = state.sSynthesis,
            chronicExposure = state.chronicExposure,
            representativeOccupancy = work.representativeOccupancy,
            occupancyNow = ToleranceSimulation.combinedOccupancy(work.contributors, totalMinutes),
            confidence = inputConfidence.flooredBy(params.confidence),
            subTargets = work.subTargets,
            contributors = ToleranceSimulation.relevantContributors(
                substancesMostRecentFirst = work.contributorSubstances,
                onsetsBySubstance = work.contributorOnsets,
                params = params,
                totalMinutes = totalMinutes,
            ),
            safetyShiftFactor = params.safetyEndpoint?.let { state.safetyShiftFactor },
            safetyEndpointKind = params.safetyEndpoint?.kind,
            effectShifts = state.effectShifts,
        )
    }

    // MARK: - Window and class inference

    /**
     * The substance standing in for each tolerance class, from the substances that
     * declare themselves representatives.
     *
     * ## A divergence, and it has to be one
     * Upstream iterates an unordered dictionary first-wins, so with two substances
     * claiming one class the answer differs between runs. There is no choice that
     * matches that, so this sorts by substance name and takes the first — a total
     * order, so the same log always yields the same surrogate. The catalog ships
     * seven representatives for seven distinct classes today, which is why the
     * difference is currently unobservable; it becomes real the day two rows claim
     * one class, and a deterministic answer is the only defensible one.
     */
    fun representativeIndex(
        params: Map<String, PharmacologyParameters>,
    ): Map<ReceptorClasses.ReceptorClass, PharmacologyParameters> {
        val out = mutableMapOf<ReceptorClasses.ReceptorClass, PharmacologyParameters>()
        for (candidate in params.entries.sortedBy { it.key.lowercase() }) {
            for (cls in candidate.value.representsClasses) {
                out.putIfAbsent(cls, candidate.value)
            }
        }
        return out
    }

    /**
     * The classes a PK-less substance should be modelled as, via its class
     * representatives.
     *
     * Two routes in: a target it engages that classifies, and its own category. Both
     * need a representative that can actually compute occupancy *and* carries its own
     * heavy-dose reference — a surrogate with nothing to scale against is no use.
     *
     * The rebound-only classes are excluded upstream by construction: `alpha2Agonist`
     * and `betaBlocker` have no representative, because there is no clean dose
     * equivalence to borrow and no tolerance curve to model.
     */
    fun fallbackClasses(
        substanceParams: PharmacologyParameters,
        representatives: Map<ReceptorClasses.ReceptorClass, PharmacologyParameters>,
    ): Set<ReceptorClasses.ReceptorClass> {
        if (substanceParams.referenceDoseMg == null) return emptySet()

        fun hasRepresentative(cls: ReceptorClasses.ReceptorClass): Boolean {
            val rep = representatives[cls] ?: return false
            val reference = rep.referenceDoseMg ?: return false
            return rep.canComputeOccupancy && reference > 0
        }

        val classes = mutableSetOf<ReceptorClasses.ReceptorClass>()
        for (engagement in substanceParams.targets) {
            val cls = ReceptorClasses.ReceptorClass.classify(engagement.target, engagement.action)
            if (cls == ReceptorClasses.ReceptorClass.UNKNOWN) continue
            if (hasRepresentative(cls)) classes += cls
        }
        for (cls in substanceParams.categoryClasses) {
            if (hasRepresentative(cls)) classes += cls
        }
        return classes
    }

    /**
     * Substances whose dose log entry cannot be modelled at all — the diagnostic a
     * card shows so "no tolerance predicted" is not mistaken for "no tolerance".
     *
     * A substance is *incomplete* when nothing can compute its occupancy **and**
     * nothing stands in for it: neither a target that classifies to a class with a
     * representative, nor a category that does. The rebound-warning classes are
     * excluded on purpose — a PK-less clonidine has no tolerance to predict, so
     * listing it as incomplete data would be a claim about a curve that does not
     * exist.
     */
    fun incompleteData(
        doses: List<SimDose>,
        params: Map<String, PharmacologyParameters>,
        representatives: Map<ReceptorClasses.ReceptorClass, PharmacologyParameters>,
    ): Set<String> {
        val out = mutableSetOf<String>()
        for (dose in doses) {
            val p = params[dose.substance] ?: continue
            if (p.canComputeOccupancy) continue
            val hasTargetMechanism = p.targets.any {
                val cls = ReceptorClasses.ReceptorClass.classify(it.target, it.action)
                cls != ReceptorClasses.ReceptorClass.UNKNOWN && !cls.hostsReboundWarningOnly
            }
            if (!hasTargetMechanism && p.categoryClasses.isEmpty()) continue
            if (fallbackClasses(p, representatives).isNotEmpty()) continue
            out += dose.substance
        }
        return out
    }

    // MARK: - Building the work

    /**
     * Turn a dose log into per-class contributors, or null when there is nothing to
     * replay.
     *
     * Null means "no result", not "an error": no dose falls inside the window, the
     * body weight is not positive, or every dose was gated out. The caller renders
     * that as an empty card set.
     */
    fun prepare(
        doses: List<SimDose>,
        params: Map<String, PharmacologyParameters>,
        nowMinutes: Double,
        weightKg: Double,
        lookbackDays: Double = ToleranceSimulation.DEFAULT_LOOKBACK_DAYS,
        modulations: Map<ReceptorClasses.ReceptorClass, List<ModulationEdge>> = emptyMap(),
    ): PreparedReplay? {
        val cutoff = nowMinutes - lookbackDays * 1_440.0
        val relevant = doses
            .filter { it.timestampMinutes <= nowMinutes && it.timestampMinutes >= cutoff }
            .sortedBy { it.timestampMinutes }
        val start = relevant.firstOrNull()?.timestampMinutes ?: return null
        if (weightKg <= 0) return null

        val builder = ClassWorkBuilder(weightKg)
        val representatives = representativeIndex(params)

        for (dose in relevant) {
            val p = params[dose.substance] ?: continue
            val doseMg = dose.amountMg
            val onset = dose.timestampMinutes - start
            val reference = p.referenceDoseMg
            // The logged substance's own escalation, in both branches. It is the deep
            // gate's magnitude signal, and it is a fact about the dose rather than
            // about whichever pharmacology resolved for it — a surrogate-modelled
            // substance still gates on its own heavy ceiling.
            val escalation = if (reference != null && reference > 0) doseMg / reference else 0.0

            if (p.canComputeOccupancy) {
                val exposure = builder.appendContributors(
                    sourceParams = p,
                    doseMg = doseMg,
                    escalation = escalation,
                    onset = onset,
                    loggedName = dose.substance,
                    restrictToClass = null,
                    confidenceFloor = ConfidenceTier.HIGH,
                ) ?: continue
                builder.appendModulators(exposure, onset, modulations)
                builder.appendMetaboliteContributors(exposure, p.metabolites, onset, escalation, p)
            } else if (reference != null && reference > 0) {
                for (cls in fallbackClasses(p, representatives)) {
                    val rep = representatives[cls] ?: continue
                    val referenceRep = rep.referenceDoseMg ?: continue
                    if (referenceRep <= 0) continue
                    // Three ways to re-express the dose in the surrogate's terms, best
                    // first. The equivalence factors are validated clinical conversions
                    // and are preferred where they exist; otherwise the dose keeps the
                    // *same fraction of the heavy ceiling*, re-expressed in the
                    // representative's milligrams.
                    val equivalentDoseMg: Double
                    val confidenceFloor: ConfidenceTier
                    val mme = p.opioidMMEPerMg
                    val deq = p.diazepamPerMg
                    if (cls == ReceptorClasses.ReceptorClass.MU_OPIOID && mme != null) {
                        equivalentDoseMg = doseMg * mme
                        confidenceFloor = ConfidenceTier.LOW
                    } else if (cls == ReceptorClasses.ReceptorClass.GABA && deq != null) {
                        equivalentDoseMg = doseMg * deq
                        confidenceFloor = ConfidenceTier.LOW
                    } else {
                        equivalentDoseMg = (doseMg / reference) * referenceRep
                        confidenceFloor = ConfidenceTier.UNVERIFIED
                    }
                    // The returned exposure is discarded: a PK-less modulator cannot be
                    // time-resolved, and a surrogate's metabolites are the surrogate's,
                    // not this dose's.
                    builder.appendContributors(
                        sourceParams = rep,
                        doseMg = equivalentDoseMg,
                        escalation = escalation,
                        onset = onset,
                        loggedName = dose.substance,
                        restrictToClass = cls,
                        confidenceFloor = confidenceFloor,
                    )
                }
            }
        }

        val work = builder.classWork()
        if (work.isEmpty()) return null
        return PreparedReplay(work, nowMinutes - start)
    }

    /**
     * Accumulates contributors, sub-targets and modulation edges across the log,
     * keyed by the class each belongs to.
     *
     * The shared path both replay stages run: a **direct** dose uses the substance's
     * own pharmacology with no class restriction and a high confidence floor, while
     * a **fallback** dose uses a class representative, pins it to the one class being
     * modelled, and floors the confidence so the class badges as predicted rather
     * than measured.
     */
    private class ClassWorkBuilder(private val weightKg: Double) {

        private val contributorsByClass =
            LinkedHashMap<ReceptorClasses.ReceptorClass, MutableList<ToleranceIntegrator.Contributor>>()
        private val subTargetsByClass = LinkedHashMap<ReceptorClasses.ReceptorClass, MutableList<String>>()
        private val peaksByClass = LinkedHashMap<ReceptorClasses.ReceptorClass, MutableList<Double>>()
        private val seenSubTarget = mutableMapOf<ReceptorClasses.ReceptorClass, MutableSet<String>>()
        private val substanceRecency = mutableMapOf<ReceptorClasses.ReceptorClass, MutableMap<String, Double>>()
        private val modulatorsByClass =
            LinkedHashMap<ReceptorClasses.ReceptorClass, MutableList<ToleranceIntegrator.ModulatorContributor>>()

        /**
         * The PK and occupancy prefactor one dose resolves to, plus its most-potent
         * surviving engagement per class — the modulator-presence driver the direct
         * path reuses.
         */
        data class DoseExposure(
            val ke: Double,
            val ka: Double,
            val prefactorNanomolar: Double,
            val bestTargetByClass: Map<ReceptorClasses.ReceptorClass, PharmacologyParameters.TargetEngagement>,
        )

        /**
         * Build the per-target contributors for one dose.
         *
         * Returns null when the source's PK cannot produce a concentration at all,
         * which drops the dose entirely — including its modulators and metabolites,
         * since neither can be time-resolved without it.
         */
        fun appendContributors(
            sourceParams: PharmacologyParameters,
            doseMg: Double,
            escalation: Double,
            onset: Double,
            loggedName: String,
            restrictToClass: ReceptorClasses.ReceptorClass?,
            confidenceFloor: ConfidenceTier,
        ): DoseExposure? {
            if (!sourceParams.canComputeOccupancy) return null
            val vdPerKg = sourceParams.vdLPerKg ?: return null
            val molarMass = sourceParams.molarMassGramsPerMole ?: return null
            val bioavailability = sourceParams.bioavailabilityFraction ?: return null
            val halfLife = sourceParams.halfLifeMinutes ?: return null
            val vd = vdPerKg * weightKg
            if (vd <= 0 || molarMass <= 0 || halfLife <= 0) return null

            val ke = PKModel.keFromHalfLifeMinutes(halfLife)
            val ka = sourceParams.tmaxMinutes?.let { PKModel.estimateKa(it, ke) } ?: PKModel.defaultKa(ke)
            // A guessed onset contributes no uncertainty: with no measured Tmax the
            // absorption rate is the elimination-derived default, and badging that as
            // unknown would mark nearly every substance low for no reason.
            val onsetConfidence = if (sourceParams.tmaxMinutes == null) {
                ConfidenceTier.HIGH
            } else {
                sourceParams.tmaxConfidence
            }

            val unbound = sourceParams.fractionUnbound
            // fu · F · dose · doseScale · 1e9 / (Vd·weight · 1000 · molarMass).
            // The 1000 turns mg/L into g/L and the 1e9 into nanomolar; `fu` is a
            // leading multiplier, never a divisor.
            val prefactor = unbound * (bioavailability * doseMg * sourceParams.doseScale / vd) /
                1_000.0 / molarMass * 1e9

            // Per dose, not per substance: a dose that engages one receptor through
            // two rows contributes once.
            val seenReceptors = mutableSetOf<String>()
            val bestTargetByClass =
                LinkedHashMap<ReceptorClasses.ReceptorClass, PharmacologyParameters.TargetEngagement>()

            for (engagement in sourceParams.targets) {
                val cls = ReceptorClasses.ReceptorClass.classify(engagement.target, engagement.action)
                if (cls == ReceptorClasses.ReceptorClass.UNKNOWN) continue
                if (restrictToClass != null && cls != restrictToClass) continue
                if (!seenReceptors.add("$cls|${engagement.targetBase}")) continue

                val peak = ToleranceSimulation.peakOccupancy(
                    ke = ke,
                    ka = ka,
                    prefactorNanomolar = prefactor,
                    halfMaxNanomolar = engagement.halfMaxNanomolar,
                )
                // Below this the engagement produces a shift no screen reports, so it
                // neither spawns the class nor appears in the sub-target list.
                if (peak < ToleranceSimulation.MIN_MEANINGFUL_OCCUPANCY) continue

                peaksByClass.getOrPut(cls) { mutableListOf() } += peak
                bestTargetByClass.putIfAbsent(cls, engagement)

                val expiry = onset + ToleranceIntegrator.decayWindowMinutes(
                    ke = ke,
                    ka = ka,
                    prefactorNanomolar = prefactor,
                    halfMaxNanomolar = engagement.halfMaxNanomolar,
                )
                contributorsByClass.getOrPut(cls) { mutableListOf() } += ToleranceIntegrator.Contributor(
                    onset = onset,
                    expiry = expiry,
                    ke = ke,
                    ka = ka,
                    prefactorNanomolar = prefactor,
                    halfMaxNanomolar = engagement.halfMaxNanomolar,
                    confidence = ConfidenceTier.HIGH
                        .flooredBy(sourceParams.vdConfidence)
                        .flooredBy(sourceParams.bioavailabilityConfidence)
                        .flooredBy(sourceParams.doseScaleConfidence)
                        .flooredBy(onsetConfidence)
                        .flooredBy(engagement.confidence)
                        .flooredBy(confidenceFloor),
                    escalation = escalation,
                    suppressesSynthesis = sourceParams.suppressesSerotoninSynthesis,
                    intrinsicEfficacy = sourceParams.intrinsicEfficacy,
                )

                // The span the app *displays*, so a target reading "NMDA receptor (PCP
                // site)" is listed under the same name the mechanism card uses.
                val canonical = ReceptorTargetKey.display(engagement.target)
                if (seenSubTarget.getOrPut(cls) { mutableSetOf() }.add(canonical)) {
                    subTargetsByClass.getOrPut(cls) { mutableListOf() } += canonical
                }
                // The log is walked oldest to newest, so the last write is the most
                // recent onset for that substance.
                substanceRecency.getOrPut(cls) { mutableMapOf() }[loggedName] = onset
            }

            return DoseExposure(ke, ka, prefactor, bestTargetByClass)
        }

        /**
         * Register one dose as a modulator for any class it modulates.
         *
         * Presence is the occupancy of its most-potent target *of the modulating
         * class*, so an edge fires only while the modulator is genuinely onboard
         * rather than for as long as some trace of it remains.
         */
        fun appendModulators(
            exposure: DoseExposure,
            onset: Double,
            modulations: Map<ReceptorClasses.ReceptorClass, List<ModulationEdge>>,
        ) {
            for ((modulatorClass, best) in exposure.bestTargetByClass) {
                for (edge in modulations[modulatorClass].orEmpty()) {
                    val expiry = onset + ToleranceIntegrator.decayWindowMinutes(
                        ke = exposure.ke,
                        ka = exposure.ka,
                        prefactorNanomolar = exposure.prefactorNanomolar,
                        halfMaxNanomolar = best.halfMaxNanomolar,
                    )
                    modulatorsByClass.getOrPut(edge.affectedClass) { mutableListOf() } +=
                        ToleranceIntegrator.ModulatorContributor(
                            onset = onset,
                            expiry = expiry,
                            ke = exposure.ke,
                            ka = exposure.ka,
                            prefactorNanomolar = exposure.prefactorNanomolar,
                            halfMaxNanomolar = best.halfMaxNanomolar,
                            muFactor = edge.muFactor,
                        )
                }
            }
        }

        /**
         * Spawn contributors for a dose's foldable active metabolites.
         *
         * Each is a delayed, formation- and potency-scaled echo of the parent at the
         * same mechanism class, decaying on the metabolite's own — usually slower —
         * half-life. That is what keeps GABA occupied for days after a diazepam dose
         * has cleared, and without it the parent's ~2-day clock would be the whole
         * story.
         *
         * Only `scaled` metabolites reach here: a `divergent` one (tramadol to M1) is
         * a different drug rather than a tail, and no scalar maps the parent's
         * occupancy onto it.
         */
        fun appendMetaboliteContributors(
            exposure: DoseExposure,
            metabolites: List<PharmacologyParameters.MetaboliteContributor>,
            parentOnset: Double,
            escalation: Double,
            sourceParams: PharmacologyParameters,
        ) {
            if (exposure.bestTargetByClass.isEmpty()) return
            // Deliberately omits the engagement and onset grades the parent carries:
            // this is the strength of the *derivation*, not of the assay behind it.
            val baseConfidence = ConfidenceTier.HIGH
                .flooredBy(sourceParams.vdConfidence)
                .flooredBy(sourceParams.bioavailabilityConfidence)
                .flooredBy(sourceParams.doseScaleConfidence)

            for (metabolite in metabolites) {
                if (!metabolite.canFold) continue
                val keMetabolite = PKModel.keFromHalfLifeMinutes(metabolite.halfLifeMinutes)
                // Formation-rate limited: the metabolite appears as the parent is
                // eliminated, so its absorption rate *is* the parent's elimination rate.
                val kaMetabolite = exposure.ke
                if (keMetabolite <= 0 || kaMetabolite <= 0) continue
                if (kotlin.math.abs(kaMetabolite - keMetabolite) <= 1e-9) continue
                val prefactor = metabolite.foldPrefactor * exposure.prefactorNanomolar
                if (prefactor <= 0) continue
                val onset = parentOnset + PKModel.tmax(exposure.ke, exposure.ka)

                for ((cls, best) in exposure.bestTargetByClass) {
                    val peak = ToleranceSimulation.peakOccupancy(
                        ke = keMetabolite,
                        ka = kaMetabolite,
                        prefactorNanomolar = prefactor,
                        halfMaxNanomolar = best.halfMaxNanomolar,
                    )
                    if (peak < ToleranceSimulation.MIN_MEANINGFUL_OCCUPANCY) continue
                    // A potency ratio measured at a receptor rather than in the clinic
                    // is not an equivalence, so it floors the badge.
                    val confidence = if (metabolite.isClinicalBasis) {
                        baseConfidence.flooredBy(best.confidence)
                    } else {
                        baseConfidence.flooredBy(best.confidence).flooredBy(ConfidenceTier.LOW)
                    }
                    val expiry = onset + ToleranceIntegrator.decayWindowMinutes(
                        ke = keMetabolite,
                        ka = kaMetabolite,
                        prefactorNanomolar = prefactor,
                        halfMaxNanomolar = best.halfMaxNanomolar,
                    )
                    contributorsByClass.getOrPut(cls) { mutableListOf() } += ToleranceIntegrator.Contributor(
                        onset = onset,
                        expiry = expiry,
                        ke = keMetabolite,
                        ka = kaMetabolite,
                        prefactorNanomolar = prefactor,
                        halfMaxNanomolar = best.halfMaxNanomolar,
                        confidence = confidence,
                        escalation = escalation,
                        suppressesSynthesis = sourceParams.suppressesSerotoninSynthesis,
                        intrinsicEfficacy = sourceParams.intrinsicEfficacy,
                        isMetabolite = true,
                    )
                    // Deliberately not recorded in `peaksByClass`, `subTargetsByClass`
                    // or `substanceRecency`: a metabolite must not move the class's
                    // representative occupancy or claim credit as a driver.
                }
            }
        }

        /**
         * Freeze the accumulation into one work item per class.
         *
         * The class order follows first appearance in the log, which makes the
         * result deterministic — upstream maps a dictionary here, so its array order
         * is arbitrary. Nothing reads it as a sequence, but a stable order is what
         * lets a test assert on a list rather than on a set.
         */
        fun classWork(): List<ClassWork> = contributorsByClass.keys.map { cls ->
            val recency = substanceRecency[cls].orEmpty()
            // Most recent first. `sortedByDescending` is stable, so two substances
            // sharing an onset keep the order they were first logged in rather than
            // swapping between runs.
            val names = recency.entries.sortedByDescending { it.value }.map { it.key }
            ClassWork(
                receptorClass = cls,
                contributors = contributorsByClass.getValue(cls),
                modulators = modulatorsByClass[cls].orEmpty(),
                representativeOccupancy = ToleranceSimulation.median(peaksByClass[cls].orEmpty()),
                subTargets = subTargetsByClass[cls].orEmpty(),
                contributorSubstances = names,
                contributorOnsets = recency,
                regularityFactor = ToleranceSimulation.scheduleRegularityFactor(
                    contributorsByClass.getValue(cls),
                ),
            )
        }
    }
}
