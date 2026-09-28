package glass.kagerou.piru.engine

import glass.kagerou.piru.model.BindingAction
import glass.kagerou.piru.model.ConfidenceTier
import glass.kagerou.piru.model.ReceptorTargetKey
import glass.kagerou.piru.model.flooredBy

/**
 * What a `pk_reference` pointer resolved to, ready for the borrow decision.
 *
 * The reader fills the three flags from its own reads; every *decision* about
 * them is taken here. That split is deliberate — a caller that had to remember to
 * enforce single-hop before calling would be one refactor away from a transitive
 * borrow chain, and upstream's comment about it says exactly that.
 */
data class ReferenceCandidate(
    val reference: PKReference,
    /** True when the pointer names the subject itself, which is a no-op. */
    val isSelf: Boolean = false,
    /** True when the reference substance itself carries a `pk_reference` — the single-hop gate. */
    val hasOnwardPointer: Boolean = false,
    /** The reference substance's own PK rows. Empty when it carries none. */
    val rows: List<PKRouteHit> = emptyList(),
)

/** One dose-ladder row as [PharmacologyAssembly.referenceDoseMg] reads it. */
data class ReferenceDoseRow(
    val route: String,
    val isomer: String? = null,
    val commonUpper: Double? = null,
    val strongUpper: Double? = null,
    val heavy: Double? = null,
)

/**
 * The pharmacology derivation and assembly layer: interspecies scaling (in
 * [InterspeciesScaling]), the reference-substance borrow, the coherent-row pick,
 * and the construction of a [PharmacologyParameters].
 *
 * Ported from `SubstanceStore+Pharmacology.swift`.
 *
 * Everything here is pure — the SQL lives in the read layer, and what a value
 * snapshot could not own (caches, indexes, main-actor snapshots) does not exist
 * in this port.
 */
object PharmacologyAssembly {

    // MARK: - Reference borrow

    /**
     * Apply the reference-substance PK borrow.
     *
     * If the subject has a `pk_reference` and the referenced fields are absent,
     * merge the surrogate's rows: a **whole-row borrow** when the subject has zero
     * PK rows of its own (the 2-MMC case), otherwise a **per-field top-up** of the
     * individually-null borrowable fields. Every borrowed field's confidence is
     * floored to the weaker of the reference row's grade and the pointer's.
     *
     * Single-hop: a reference that itself carries a `pk_reference` is refused, so
     * there is no transitive chain. The subject's own rows come back unchanged when
     * no borrow applies.
     */
    fun applyPKReference(
        ownRows: List<PKRouteHit>,
        candidate: ReferenceCandidate?,
    ): List<PKRouteHit> {
        val candidate = candidate ?: return ownRows
        val reference = candidate.reference
        // Self-reference is a no-op, and an onward pointer means the surrogate has
        // no PK of its own to lend — only another pointer, which this refuses to
        // follow.
        if (candidate.isSelf || candidate.hasOnwardPointer) return ownRows
        val referenceRows = candidate.rows
        if (referenceRows.isEmpty()) return ownRows

        if (ownRows.isEmpty()) {
            // Whole-row borrow: take the surrogate's rows, each floored to the
            // pointer's ceiling.
            return referenceRows.map { InterspeciesScaling.flooringConfidence(it, reference.confidence) }
        }

        // Per-field top-up: fill only the listed borrowable fields that are null on
        // the subject's own coherent (Vd-first) row, from the surrogate's.
        val referencePrimary = referenceRows.firstOrNull { it.vdLPerKg != null } ?: referenceRows.first()
        val subjectPrimary = ownRows.firstOrNull { it.vdLPerKg != null } ?: ownRows.first()
        val takeVd = "vd" in reference.fields && subjectPrimary.vdLPerKg == null && referencePrimary.vdLPerKg != null
        val takeF = "bioavailability" in reference.fields &&
            subjectPrimary.bioavailabilityPct == null && referencePrimary.bioavailabilityPct != null
        val takeTmax = "tmax" in reference.fields &&
            subjectPrimary.tmaxMin == null && referencePrimary.tmaxMin != null
        val takeHalfLife = "half_life" in reference.fields &&
            subjectPrimary.halfLifeMin == null && referencePrimary.halfLifeMin != null
        if (!takeVd && !takeF && !takeTmax && !takeHalfLife) return ownRows

        val ceiling = referencePrimary.confidence.flooredBy(reference.confidence)
        val merged = subjectPrimary.copy(
            bioavailabilityPct = if (takeF) referencePrimary.bioavailabilityPct else subjectPrimary.bioavailabilityPct,
            tmaxMin = if (takeTmax) referencePrimary.tmaxMin else subjectPrimary.tmaxMin,
            halfLifeMin = if (takeHalfLife) referencePrimary.halfLifeMin else subjectPrimary.halfLifeMin,
            vdLPerKg = if (takeVd) referencePrimary.vdLPerKg else subjectPrimary.vdLPerKg,
            // A borrowed Vd carries the surrogate's species flag, so the
            // allometric scaling downstream floors it correctly rather than
            // treating an animal Vd as the subject's own.
            species = if (takeVd) referencePrimary.species else subjectPrimary.species,
            confidence = subjectPrimary.confidence.flooredBy(ceiling),
        )
        return ownRows.map { if (it.id == subjectPrimary.id) merged else it }
    }

    // MARK: - Derived inputs

    /**
     * The lowest therapeutic-range threshold in nanomolar, or null when the
     * substance has no convertible row or no molar mass.
     *
     * A TDM reference range is a population consensus rather than a measurement of
     * this drug's own concentration-effect curve, so it carries [ConfidenceTier.MEDIUM]
     * rather than a measured grade.
     */
    fun therapeuticHalfMax(
        ranges: List<TherapeuticRangeHit>,
        molarMass: Double?,
    ): TherapeuticHalfMax? {
        val mass = molarMass ?: return null
        if (mass <= 0) return null
        for (range in ranges) {
            // A fatal or whole-blood row must never become an occupancy constant —
            // the same refusal the dose conversion makes, for the same reason.
            if (!DoseEquivalent.isConvertible(range.effect)) continue
            val mgPerL = DoseEquivalent.milligramsPerLitre(range.thresholdValue, range.concentrationUnit) ?: continue
            if (mgPerL <= 0) continue
            // mg/L ÷ (g/mol) = mmol/L; × 1e6 → nmol/L.
            val nanomolar = mgPerL / mass * 1e6
            return TherapeuticHalfMax(
                nanomolar = nanomolar,
                confidence = ConfidenceTier.MEDIUM,
                sourceSlug = range.sourceSlug,
                citationKey = range.doi?.let { "doi:$it" } ?: range.pmid?.let { "pmid:$it" },
            )
        }
        return null
    }

    /** The therapeutic floor a flagged class's targets engage on, with its provenance. */
    data class TherapeuticHalfMax(
        val nanomolar: Double,
        val confidence: ConfidenceTier,
        val sourceSlug: String,
        val citationKey: String?,
    )

    /**
     * Fraction unbound from the best available protein binding across all PK rows.
     *
     * Prefers a human row, then any row, and returns `1.0` when none carries the
     * field — no binding correction, which is the neutral answer rather than an
     * invented one.
     *
     * The floor at 0.001 is not cosmetic: a row stating 100% bound would otherwise
     * yield `fu = 0`, and every occupancy downstream would be zero — a
     * plausible-looking "no engagement" for a drug that is merely almost entirely
     * bound.
     */
    fun resolveFractionUnbound(pk: List<PKRouteHit>): Double {
        val pct = pk.firstOrNull { it.species == "human" && it.proteinBindingPct != null }?.proteinBindingPct
            ?: pk.firstOrNull { it.proteinBindingPct != null }?.proteinBindingPct
            ?: return 1.0
        return maxOf(0.001, 1 - pct / 100)
    }

    /**
     * Distil metabolism rows into the tolerance engine's metabolite contributors.
     *
     * Only rows that name a metabolite **and** carry a half-life become
     * contributors: the PK shape needs the half-life, and an enzyme-only row has
     * nothing to fold.
     *
     * Rows are deduplicated by metabolite identity, keeping the strongest evidence.
     * Diazepam carries both a `receptor_affinity` and a `clinical` nordazepam row,
     * and the clinical one — which is also the row carrying the half-life — has to
     * win. Otherwise the entry would be badged on an affinity ratio that is not a
     * clinical equivalence.
     */
    fun metaboliteContributors(hits: List<MetabolismHit>): List<PharmacologyParameters.MetaboliteContributor> {
        /** Higher is stronger evidence to keep on a duplicate metabolite. */
        fun rank(c: PharmacologyParameters.MetaboliteContributor): Int =
            (if (c.canFold) 2 else 0) + (if (c.isClinicalBasis) 1 else 0)

        val byKey = LinkedHashMap<String, PharmacologyParameters.MetaboliteContributor>()
        for (hit in hits) {
            val name = hit.metaboliteName ?: continue
            val halfLife = hit.metaboliteHalfLifeMinutes ?: continue
            val contributor = PharmacologyParameters.MetaboliteContributor(
                metaboliteName = name,
                halfLifeMinutes = halfLife,
                formationFractionPct = hit.formationFractionPct,
                potencyVsParentPct = hit.metabolitePotencyVsParentPct,
                potencyBasis = hit.metabolitePotencyBasis?.wireValue,
                mechanismVsParent = hit.metaboliteMechanismVsParent.wireValue,
            )
            val existing = byKey[name]
            if (existing == null || rank(contributor) > rank(existing)) byKey[name] = contributor
        }
        return byKey.values.toList()
    }

    /**
     * The substance's **reference "heavy" dose** in mg — the escalation denominator
     * for the deep tolerance gate.
     *
     * Resolved from the primary dose ladder: the **oral** route when it carries a
     * usable value, else the first route that does; within a route,
     * `heavy ?? strong.upper ?? common.upper`.
     *
     * The racemic form is preferred so a family's reference is the parent's rather
     * than an arbitrary enantiomer's — and only when no racemic row yields a value
     * does the enantiomer's count.
     */
    fun referenceDoseMg(rows: List<ReferenceDoseRow>): Double? {
        fun value(row: ReferenceDoseRow): Double? = row.heavy ?: row.strongUpper ?: row.commonUpper

        fun firstReference(preferRacemic: Boolean): Double? {
            val candidates = if (preferRacemic) rows.filter { it.isomer == null } else rows
            val oral = candidates.firstOrNull { it.route == "oral" }
            oral?.let { value(it)?.let { v -> return v } }
            for (row in candidates) {
                value(row)?.let { return it }
            }
            return null
        }
        return firstReference(preferRacemic = true) ?: firstReference(preferRacemic = false)
    }

    // MARK: - Assembly

    /**
     * Assemble the occupancy-pipeline inputs from already-read rows.
     *
     * ## Coherence is the whole point
     * Vd, F and half-life are read from a **single** PK row, never assembled from
     * whichever studies happened to fill each field. Cross-pairing them silently
     * double-counts F where a stored "Vd" is actually an apparent V/F — MDMA is the
     * case, having no IV arm — because `C = F·dose/((V/F)·wt)` would then embed the
     * F twice. So the pick prefers the highest-confidence row that carries a Vd,
     * `pharmacokinetics` being oral-first, and falls back to the best row for a
     * half-life only when no Vd exists at all.
     */
    fun assemble(
        molarMass: Double?,
        pk: List<PKRouteHit>,
        bindingHits: List<BindingHit>,
        therapeuticRanges: List<TherapeuticRangeHit> = emptyList(),
        doseScale: Double = 1.0,
        doseScaleConfidence: ConfidenceTier = ConfidenceTier.HIGH,
        referenceDoseMg: Double? = null,
        intrinsicEfficacy: Double = 1.0,
        categoryClasses: Set<ReceptorClasses.ReceptorClass> = emptySet(),
        metabolites: List<PharmacologyParameters.MetaboliteContributor> = emptyList(),
        suppressesSerotoninSynthesis: Boolean = false,
        diazepamPerMg: Double? = null,
        opioidMMEPerMg: Double? = null,
        representsClasses: Set<ReceptorClasses.ReceptorClass> = emptySet(),
    ): PharmacologyParameters {
        val vdRows = pk.filter { it.vdLPerKg != null }
        val primaryRow = vdRows.firstOrNull { it.confidence != ConfidenceTier.UNVERIFIED }
            ?: vdRows.firstOrNull()
            ?: pk.firstOrNull { it.confidence != ConfidenceTier.UNVERIFIED }
            ?: pk.firstOrNull()

        // The allometric projection is applied right after the coherent-row pick,
        // so every downstream read sees the human-projected, honestly-badged values.
        val scaledPrimary = primaryRow?.let { InterspeciesScaling.scaledToHuman(it) }
        val pkSpecies = primaryRow?.species
        val primaryIsNonHuman = pkSpecies != null && pkSpecies != "human"
        val vd = scaledPrimary?.vdLPerKg

        // F: the measured value when the coherent row carries one, else 1.0 flagged
        // unverified. Absolute oral F is definitionally underivable without an IV
        // arm for most recreational drugs, and their stored Vd is already an
        // apparent V/F — so F = 1 is the *consistent* reading (the F cancels),
        // never an invented number.
        val measuredF = scaledPrimary?.bioavailabilityPct?.let { it / 100 }
        val f = measuredF ?: 1.0
        val fConfidence = if (measuredF != null) {
            scaledPrimary.confidence
        } else {
            ConfidenceTier.UNVERIFIED
        }

        // A *measured human* half-life from any row always wins over a scaled animal
        // one. Single-species allometric scaling underpredicts cathinone half-life
        // by two to three times — mephedrone rat-scaled 65 min against a measured
        // human 129, 3-MMC pig-scaled 55 against 180 — so the scaled value is only a
        // stand-in where no human measurement exists.
        val humanHalfLife = if (primaryIsNonHuman) {
            pk.firstOrNull { it.species == "human" && it.halfLifeMin != null && it.confidence != ConfidenceTier.UNVERIFIED }
                ?.halfLifeMin
                ?: pk.firstOrNull { it.species == "human" && it.halfLifeMin != null }?.halfLifeMin
        } else {
            null
        }
        val halfLife = humanHalfLife ?: scaledPrimary?.halfLifeMin

        // Tmax, under the same human-wins discipline. Tmax is a rate descriptor
        // independent of the F/Vd apparent-VF coupling, so borrowing it from a
        // different row when the primary lacks one is safe.
        val tmaxRow = (if (primaryIsNonHuman) {
            pk.firstOrNull { it.species == "human" && it.tmaxMin != null }
        } else {
            null
        }) ?: if (primaryRow?.tmaxMin != null) {
            primaryRow
        } else {
            pk.firstOrNull { it.confidence != ConfidenceTier.UNVERIFIED && it.tmaxMin != null }
                ?: pk.firstOrNull { it.tmaxMin != null }
        }
        val tmax = tmaxRow?.tmaxMin
        val tmaxConfidence = if (tmax != null) tmaxRow?.confidence ?: ConfidenceTier.UNVERIFIED else ConfidenceTier.UNVERIFIED

        val therapeuticFloor = therapeuticHalfMax(therapeuticRanges, molarMass)
        val seenTargets = mutableSetOf<String>()
        val targets = bindingHits
            .mapNotNull { b ->
                val action = BindingAction.fromWire(b.action) ?: return@mapNotNull null
                // Kᵢ is preferred because it *is* fractional receptor occupancy;
                // EC₅₀ and IC₅₀ are functional proxies used only when no Kᵢ exists.
                // Do not "promote" EC₅₀ over a present Kᵢ — for LSD that would swap
                // 4 nM for 261 nM, a 65-fold occupancy difference.
                val halfMax = b.kiNm ?: b.ec50Nm ?: b.ic50Nm
                val kind = when {
                    b.kiNm != null -> PharmacologyParameters.HalfMaxKind.KI
                    b.ec50Nm != null -> PharmacologyParameters.HalfMaxKind.EC50
                    b.ic50Nm != null -> PharmacologyParameters.HalfMaxKind.IC50
                    else -> PharmacologyParameters.HalfMaxKind.KI
                }
                if (halfMax == null || halfMax <= 0) return@mapNotNull null
                val targetBase = b.targetBase?.takeIf { it.isNotEmpty() } ?: ReceptorTargetKey.fold(b.target)

                // A row that would have engaged on its constant engages on the
                // therapeutic floor instead. A row with *no* constant stays out: the
                // floor stands in for a constant, it does not mint targets.
                if (therapeuticFloor != null &&
                    ReceptorClasses.parametersFor(
                        ReceptorClasses.ReceptorClass.classify(b.target, action),
                    ).occupancyHalfMaxFromTherapeuticRange
                ) {
                    return@mapNotNull PharmacologyParameters.TargetEngagement(
                        target = b.target,
                        targetBase = targetBase,
                        action = action,
                        halfMaxNanomolar = therapeuticFloor.nanomolar,
                        kind = PharmacologyParameters.HalfMaxKind.THERAPEUTIC_THRESHOLD,
                        confidence = therapeuticFloor.confidence,
                        sourceSlug = therapeuticFloor.sourceSlug,
                        citationKey = therapeuticFloor.citationKey,
                        species = "human",
                    )
                }
                PharmacologyParameters.TargetEngagement(
                    target = b.target,
                    targetBase = targetBase,
                    action = action,
                    halfMaxNanomolar = halfMax,
                    kind = kind,
                    confidence = b.confidence,
                    sourceSlug = b.sourceSlug,
                    citationKey = b.doi?.let { "doi:$it" } ?: b.pmid?.let { "pmid:$it" },
                    species = b.species,
                )
                // Tightest first, and collapse duplicate target+action+kind rows
                // (which a future substance merge could introduce, since `bindings`
                // has no database-level dedup) so each engaged target appears once
                // and `TargetEngagement.id` stays unique for any list rendering.
            }
            .sortedBy { it.halfMaxNanomolar }
            .filter { seenTargets.add(it.id) }

        // Vd with a class-default fallback. A graded Vd is always preferred; with
        // none, a classifiable target stands in with its receptor class's
        // CNS-distribution default — flagged unverified — so occupancy stays
        // computable for the families the evidence run left without a Vd. With
        // neither there is nothing to stand in, and occupancy is correctly
        // uncomputable.
        val resolvedVd: Double?
        val resolvedVdConfidence: ConfidenceTier
        if (vd != null) {
            resolvedVd = vd
            // The scaled row's confidence, floored for a non-human Vd so an
            // allometric default never masquerades as a measured human one.
            resolvedVdConfidence = scaledPrimary?.confidence ?: ConfidenceTier.UNVERIFIED
        } else {
            val primaryTarget = targets.firstOrNull()
            if (primaryTarget != null) {
                // Classified by target *name* only, because this fallback is about
                // CNS distribution rather than tolerance mechanism — a binding
                // direction is irrelevant to it.
                resolvedVd = ReceptorClasses.parametersFor(
                    ReceptorClasses.ReceptorClass.classify(primaryTarget.target),
                ).classDefaultVdLPerKg
                resolvedVdConfidence = ConfidenceTier.UNVERIFIED
            } else {
                resolvedVd = null
                resolvedVdConfidence = ConfidenceTier.UNVERIFIED
            }
        }

        return PharmacologyParameters(
            molarMassGramsPerMole = molarMass,
            vdLPerKg = resolvedVd,
            bioavailabilityFraction = f,
            bioavailabilityConfidence = fConfidence,
            doseScale = doseScale,
            doseScaleConfidence = doseScaleConfidence,
            halfLifeMinutes = halfLife,
            vdConfidence = resolvedVdConfidence,
            referenceDoseMg = referenceDoseMg,
            suppressesSerotoninSynthesis = suppressesSerotoninSynthesis,
            targets = targets,
            tmaxMinutes = tmax,
            tmaxConfidence = tmaxConfidence,
            intrinsicEfficacy = intrinsicEfficacy,
            categoryClasses = categoryClasses,
            pkSpecies = pkSpecies,
            fractionUnbound = resolveFractionUnbound(pk),
            metabolites = metabolites,
            diazepamPerMg = diazepamPerMg,
            opioidMMEPerMg = opioidMMEPerMg,
            representsClasses = representsClasses,
        )
    }
}
