package glass.kagerou.piru.engine

import glass.kagerou.piru.model.DoseRange
import glass.kagerou.piru.model.DoseUnit
import glass.kagerou.piru.model.DurationProfile
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.model.SubstanceCategory
import java.time.Duration
import java.time.Instant

// MARK: - Active substance model

/**
 * One substance still present in the body, with each contributing dose.
 *
 * Ported from `ActiveSubstance` in `Piru/Utilities/ActiveSubstanceCalculator.swift`.
 */
data class ActiveSubstance(
    val name: String,
    val unit: String,
    val tint: P3Color,
    val halfLifeMinutes: Double,
    val totalDosed: Double,
    val totalRemaining: Double,
    val doses: List<DoseInfo>,
) {
    /**
     * Identity carries the unit: a substance logged in both mL and mg yields two
     * rows (they are not addable), and a list needs them to differ.
     */
    val id: String get() = "$name|$unit"

    /** The fraction of everything dosed that has already left the body, `0…1`. */
    val eliminatedFraction: Double get() = 1 - totalRemaining / totalDosed

    /**
     * One contributing dose.
     *
     * Upstream carries `let id = UUID()` purely as a `ForEach` identity. That is
     * deliberately dropped: a fresh random id per computation makes two runs
     * differing only in `now` produce unequal lists for no reason. A value type is
     * equal exactly when its numbers are, and [timestamp] is the part that stays
     * put when a recomputation moves [remaining].
     */
    data class DoseInfo(
        val amount: Double,
        val remaining: Double,
        val timestamp: Instant,
    )
}

/**
 * The two readouts the dose log drives: what is still in the body right now, and
 * what each dose draws on a timeline.
 *
 * Ported from `Piru/Utilities/ActiveSubstanceCalculator.swift`, whose file holds
 * both halves — the body-load calculator and the `ActiveSubstanceState` builders —
 * so they stay one unit here too.
 */
object ActiveSubstanceCalculator {

    // MARK: - Body load

    /**
     * Computes the active substances at [now] from dose history using a
     * one-compartment PK model.
     *
     * Parameterizing [now] is what lets a body-load readout be produced for any
     * past — or projected future — date, which the "in your body over time" graph
     * samples across a day grid. Only doses at or before [now] contribute
     * (causality).
     *
     * @param colorMap the user's per-substance tints, keyed by lowercased name.
     * @param fallbackTint what to draw a substance the user has not colored —
     *   `Theme.accent` upstream, passed in because the engine carries no theme.
     */
    fun compute(
        entries: List<DoseRecord>,
        colorMap: Map<String, P3Color>,
        catalog: SubstanceCatalog,
        fallbackTint: P3Color,
        now: Instant = Instant.now(),
    ): List<ActiveSubstance> {
        // Batch lookups: cache substance resolution so each unique name is looked
        // up once across a long dose log.
        val substanceCache = mutableMapOf<String, Substance?>()
        fun cachedLookup(name: String): Substance? {
            val key = name.lowercase()
            if (substanceCache.containsKey(key)) return substanceCache[key]
            val result = catalog.lookup(name)
            substanceCache[key] = result
            return result
        }

        // Half-life resolution goes through the shared `PKResolver`; the local cache
        // just avoids re-resolving the same name. Depot doses bypass the cache
        // (their half-life depends on the ester on the entry, not just the name) and
        // the acute duration below, so they read as a slow depot decay.
        val halfLifeCache = mutableMapOf<String, Double>()
        fun resolveHalfLife(substance: Substance?, entry: DoseRecord, isDepot: Boolean): Double? {
            PKResolver.depotHalfLifeMinutes(entry, isDepot, catalog)?.let { return it }
            val key = entry.substance.lowercase()
            if (halfLifeCache.containsKey(key)) return halfLifeCache[key]
            val halfLife = PKResolver.halfLifeMinutes(substance) ?: return null
            halfLifeCache[key] = halfLife
            return halfLife
        }

        val grouped = LinkedHashMap<String, Group>()

        for (entry in entries) {
            val substance = cachedLookup(entry.substance)
            // Supplements and vitamins clear over days-to-weeks, so a body-load
            // readout for them ("0% eliminated · clear in 5 months") is noise, not
            // session insight. Excluded here to match their suppression on the
            // timeline and in the entry-row rail.
            if (substance?.category == SubstanceCategory.SUPPLEMENT) continue

            // A dose naming a form we don't model contributes no body-load estimate
            // either. The elimination half-life *is* a property of the molecule and
            // survives the delivery matrix — but this readout is not pure
            // elimination: `ka` below comes from the route's duration profile, i.e.
            // the immediate-release absorption limb, so a dose released over ~10 h
            // would be modelled as if it landed at once, and "clear ~12:33 PM" states
            // a time we have no basis for. Better to show nothing than something
            // misleading.
            //
            // A per-product envelope (Concerta, Adderall XR) models the extended
            // release, so its dose contributes a body-load estimate with the right
            // absorption limb — unlike a bare unmodeled form, which we still skip.
            //
            // No amount, no body load: 0 remaining of 0 dosed is not a fraction.
            if (entry.isUnknownDose) continue
            val productDuration = entry.productDuration(catalog)
            val isDepot = PKResolver.isDepot(entry, catalog)
            // A depot bypasses the unmodeled-form skip (it has no acute form to
            // model, but its slow persistence is exactly what a body-load readout
            // is for).
            if (!isDepot && productDuration == null && entry.namesUnmodeledForm) continue
            val halfLife = resolveHalfLife(substance, entry, isDepot) ?: continue

            val elapsed = Duration.between(entry.timestamp, now).toMillis() / 60_000.0
            if (elapsed < 0) continue

            // The product envelope wins the absorption limb; otherwise the
            // route/salt/isomer-specific profile. A depot has no acute absorption
            // limb — its slow release IS the rate — so it takes the default `ka`
            // proportional to its (long) `ke`, giving a slow-rise, slow-fall shape.
            val (ke, ka) = PKResolver.rateConstants(
                halfLifeMinutes = halfLife,
                duration = if (isDepot) {
                    null
                } else {
                    productDuration ?: substance?.resolveDuration(
                        entry.route, entry.saltForm, entry.isomer,
                    )
                },
            )
            val remaining = entry.amount * PKModel.fractionRemainingInBody(elapsed, ke, ka)
            val fraction = remaining / entry.amount

            if (!(fraction > 0.03)) continue

            val name = substance?.name ?: entry.substance
            val key = "$name|${unitFamily(entry.unit)}"

            val existing = grouped[key]
            if (existing != null) {
                // Express this dose in the group's established unit. Same family by
                // construction, so the conversion cannot fail; the fallback keeps it
                // honest rather than silently mixing scales.
                val amount = DoseUnit.convert(entry.amount, from = entry.unit, to = existing.unit)
                    ?: entry.amount
                val converted = DoseUnit.convert(remaining, from = entry.unit, to = existing.unit)
                    ?: remaining
                existing.doses += ActiveSubstance.DoseInfo(
                    amount = amount,
                    remaining = converted,
                    timestamp = entry.timestamp,
                )
                existing.totalDosed += amount
                existing.totalRemaining += converted
            } else {
                grouped[key] = Group(
                    name = name,
                    // The unit the user actually logged. Taking it from the
                    // library's default unit instead printed "7.2 mg" under two
                    // 3.6 g doses of gabapentin — right number, wrong suffix.
                    unit = entry.unit,
                    halfLifeMinutes = halfLife,
                    doses = mutableListOf(
                        ActiveSubstance.DoseInfo(
                            amount = entry.amount,
                            remaining = remaining,
                            timestamp = entry.timestamp,
                        ),
                    ),
                    totalDosed = entry.amount,
                    totalRemaining = remaining,
                )
            }
        }

        return grouped.values
            .map { info ->
                ActiveSubstance(
                    name = info.name,
                    unit = info.unit,
                    tint = colorMap[info.name.lowercase()] ?: fallbackTint,
                    halfLifeMinutes = info.halfLifeMinutes,
                    totalDosed = info.totalDosed,
                    totalRemaining = info.totalRemaining,
                    doses = info.doses.sortedByDescending { it.timestamp },
                )
            }
            .sortedBy { it.eliminatedFraction }
    }

    /**
     * The grouping accumulator.
     *
     * Upstream uses a dictionary of tuples and writes the whole tuple back on every
     * addition; a small mutable class is the same thing without the copy.
     */
    private class Group(
        val name: String,
        val unit: String,
        val halfLifeMinutes: Double,
        val doses: MutableList<ActiveSubstance.DoseInfo>,
        var totalDosed: Double,
        var totalRemaining: Double,
    )

    /**
     * The quantity family a unit belongs to.
     *
     * The group key must carry it, not just the name. Two doses of the same
     * substance in µg/mg/g are one group (converted into whichever unit arrived
     * first); a dose in mL or IU is a *different* quantity and gets its own group
     * rather than being summed into a mass total.
     */
    private fun unitFamily(unit: String): String =
        if (DoseUnit.convert(1.0, from = unit, to = "mg") == null) unit else "mass"

    // MARK: - Dose tiers

    /**
     * Fall back to the substance's default route (then any populated route) when
     * the requested route has no [DoseRange].
     *
     * Without this, a user logging a non-default route (e.g. insufflated when the
     * library only has oral data) gets a dose intensity of 1.0, which makes every
     * such dose render at full graph height regardless of magnitude — collapsing
     * the visual distinction between light, common and heavy across the journal.
     */
    fun resolveDoseRange(substance: Substance, route: RouteOfAdministration): DoseRange? {
        substance.doseRange(route)?.let { exact -> if (exact.hasAnyValue) return exact }
        substance.doseRange(substance.defaultRoute)?.let { def -> if (def.hasAnyValue) return def }
        return substance.routes.firstOrNull { it.doses.hasAnyValue }?.doses
    }

    /**
     * No ladder information at all: rather than pegging the dose at the top of the
     * graph where it would wrongly dominate every other dose, show it as a moderate
     * curve.
     */
    const val UNKNOWN_INTENSITY: Double = 0.60

    /**
     * Floor height so sub-threshold doses still show a visible nub rather than
     * disappearing. Shared by [computeDoseIntensity] and [computeDoseMagnitude] so
     * the capped and uncapped reads agree on where the bottom is.
     */
    const val MINIMUM_INTENSITY: Double = 0.05

    /**
     * Dose intensity (`0.05…1.0`) used to scale timeline curve heights.
     *
     * Uses `amount / heavy_threshold` directly — matches PsychonautWiki's visual
     * behaviour: 17 g alcohol renders at half the height of 34 g, and plat-1 DXM
     * (150 mg / 700 mg heavy ≈ 0.21) renders much shorter than 75 g alcohol
     * (75 / 40 = 1.0 saturated). The ratio naturally captures within-substance
     * proportionality while the cap handles overdose cases.
     *
     * Falls back to looser references (strong upper, common upper × 1.5, …) when
     * heavy isn't defined, so substances with partial data still produce a sensible
     * scale, and to [UNKNOWN_INTENSITY] when there is no ladder at all.
     */
    fun computeDoseIntensity(amount: Double, doseRange: DoseRange?): Double {
        val reference = heavyReference(doseRange) ?: return UNKNOWN_INTENSITY
        return minOf(1.0, maxOf(MINIMUM_INTENSITY, amount / reference))
    }

    /**
     * The **unclamped** dose magnitude — `amount / heavy_threshold` with no 1.0 cap.
     *
     * Single source for the timeline's dose superposition: stacked doses sum their
     * magnitudes and a single combined dose of the same total lands identically, so
     * the merged curve passes one Hill link and `4×20 mg ≡ 1×80 mg`. Still floored
     * at [MINIMUM_INTENSITY] so a sub-threshold dose keeps a visible nub, and at
     * [UNKNOWN_INTENSITY] when no dose-range reference exists (mirroring
     * [computeDoseIntensity]).
     */
    fun computeDoseMagnitude(amount: Double, doseRange: DoseRange?): Double {
        val reference = heavyReference(doseRange) ?: return UNKNOWN_INTENSITY
        return maxOf(MINIMUM_INTENSITY, amount / reference)
    }

    /**
     * Where a **published** heavy bound sits on the magnitude scale, or null when
     * the ladder has none.
     *
     * The distinction [heavyReference] deliberately erases — it will improvise a
     * denominator from `strong.upperBound`, `common × 1.5`, even `threshold × 10`,
     * because a curve needs *a* height and any monotone reference gives an honest
     * ordering. A marked region on the graph is a different kind of claim: it names
     * a line, so it may only be drawn where a source actually drew one. When `heavy`
     * is present it is the denominator, which puts the threshold at exactly 1.0.
     */
    fun heavyThresholdMagnitude(doseRange: DoseRange?): Double? {
        val heavy = doseRange?.heavy ?: return null
        return if (heavy > 0) 1.0 else null
    }

    /**
     * Resolve the "heavy" reference dose used as the denominator for both intensity
     * and magnitude, with looser fallbacks (strong upper, common upper × 1.5, …)
     * when `heavy` isn't defined. Null when nothing is populated, so callers can
     * substitute [UNKNOWN_INTENSITY].
     */
    fun heavyReference(doseRange: DoseRange?): Double? {
        val range = doseRange ?: return null
        range.heavy?.let { if (it > 0) return it }
        range.strong?.let { if (it.endInclusive > 0) return it.endInclusive }
        // Approximate a heavy threshold when only common is defined.
        range.common?.let { if (it.endInclusive > 0) return it.endInclusive * 1.5 }
        range.light?.let { if (it.endInclusive > 0) return it.endInclusive * 3 }
        range.threshold?.let { if (it > 0) return it * 10 }
        return null
    }
}

// MARK: - Dose state builders

/**
 * Build an [ActiveSubstanceState] from a pre-resolved duration profile and basic
 * dose info. Null when there is no duration to draw.
 *
 * Declared as an extension on [ActiveSubstanceState.Companion] so the call site
 * reads `ActiveSubstanceState.from(...)`, the shape it has upstream. The builders
 * live here rather than beside the data class because upstream keeps the whole
 * readout in one file and the two halves share the dose-tier arithmetic above.
 */
fun ActiveSubstanceState.Companion.from(
    name: String,
    tint: P3Color,
    timestamp: Instant,
    amount: Double,
    unit: String,
    routeDisplayName: String,
    duration: DurationProfile?,
    category: SubstanceCategory? = null,
    doseIntensity: Double = 1.0,
    doseMagnitude: Double? = null,
    heavyThresholdMagnitude: Double? = null,
    doseIsUnscaled: Boolean = false,
    tachyphylaxis: Double = 0.0,
    weightKg: Double = PKModel.REFERENCE_BODY_WEIGHT_KG,
    zeroOrderKinetics: PKModel.ZeroOrderKinetics? = null,
): ActiveSubstanceState? {
    val rawDuration = duration ?: return null
    // Endpoint-only data (a `total` with no come-up/peak/offset) would otherwise
    // collapse the curve to the onset length; synthesize the missing shapers so it
    // spans the real duration. No-op for complete profiles. Curve-only — the detail
    // card keeps the raw phases.
    val filled = if (category != null) rawDuration.fillingMissingPhases(category) else rawDuration
    val boundaries = filled.phaseBoundaries
    // Zero-order substances (alcohol) clear in a dose-scaled time, so the whole
    // readout — phase bar, phase-band coloring, now-line active window,
    // "{elapsed} in · {remaining} left" — must track the same kinetics the curve
    // draws, not the fixed `DurationProfile`. Falls back to the profile below when
    // the dose can't be read as a mass or is too small to form a BAC peak.
    val zeroOrder = TimelineCurveModel.zeroOrderBoundaries(zeroOrderKinetics, amount, unit)
    return ActiveSubstanceState(
        substanceName = name,
        tint = tint,
        doseTimestamp = timestamp,
        amount = amount,
        unit = unit,
        route = routeDisplayName,
        onsetEndMinutes = zeroOrder?.onsetEnd ?: boundaries.onsetEnd,
        comeupEndMinutes = zeroOrder?.comeupEnd ?: boundaries.comeupEnd,
        peakEndMinutes = zeroOrder?.peakEnd ?: boundaries.peakEnd,
        offsetEndMinutes = zeroOrder?.offsetEnd ?: boundaries.offsetEnd,
        afterglowEndMinutes = when {
            zeroOrder != null -> null
            filled.afterglow != null -> boundaries.afterglowEnd
            else -> null
        },
        totalMinutes = zeroOrder?.total ?: filled.estimatedTotalMinutes,
        doseIntensity = doseIntensity,
        doseMagnitude = doseMagnitude ?: doseIntensity,
        heavyThresholdMagnitude = heavyThresholdMagnitude,
        doseIsUnscaled = doseIsUnscaled,
        tachyphylaxis = tachyphylaxis,
        bodyWeightKg = weightKg,
        zeroOrder = zeroOrderKinetics,
        // Phase-range widths for the effect curve's spread-aware fit. Synthesized
        // phases are min == max, so they contribute zero spread automatically;
        // zero-order doses ignore the phase curve.
        comeupSpreadMinutes = filled.comeup?.let { it.max - it.min },
        peakSpreadMinutes = filled.peak?.let { it.max - it.min },
        offsetSpreadMinutes = filled.offset?.let { it.max - it.min },
    )
}

/**
 * Build from a dose record by looking up substance duration data.
 *
 * **The curve means acute psychoactive effect, and nothing else.** A dose resolves
 * one only from a real, sourced phase profile. That is the right source even when
 * it disagrees with blood half-life (amphetamine's ~10 h t½ far outlasts its
 * subjective effects). With no such profile the answer is null, and the dose falls
 * through to a timestamp marker.
 *
 * A half-life is deliberately **not** a fallback. It answers "how much is still in
 * you", which is [ActiveSubstanceCalculator.compute]'s question, not this one — and
 * the two diverge hardest exactly where a fabricated curve does the most damage.
 * Chronic medication is the whole population that reached the old synthesized tier:
 * SSRIs, antipsychotics, anticonvulsants, thyroid, therapeutic peptides. None has
 * an acute curve to draw, and deriving one from t½ drew a flat multi-week plateau
 * over every real curve on the graph (fluoxetine's 16-day t½ → a 69-day "effect",
 * `1,638h left`). The tier's stated beneficiaries (Memantine, Tadalafil, Bromantane)
 * have all since gained real duration data and resolve above, so it had no honest
 * users left.
 *
 * A substance that genuinely does have acute effects but renders as a bare marker
 * is a **data** gap — fix it by adding durations in the pipeline, not by inferring
 * a shape from elimination kinetics.
 */
fun ActiveSubstanceState.Companion.from(
    entry: DoseRecord,
    tint: P3Color,
    catalog: SubstanceCatalog,
    weightKg: Double = PKModel.REFERENCE_BODY_WEIGHT_KG,
): ActiveSubstanceState? {
    // A dose of unknown amount has no intensity to draw; it lands as a timestamp
    // marker. This one guard is what keeps it out of Active Now, the timeline
    // curves, and the session effect models.
    if (entry.isUnknownDose) return null
    val substance = catalog.lookup(entry.substance) ?: return null
    // A depot administration (an injectable ester; a formulation flagged depot)
    // releases over days-to-weeks and has no acute psychoactive curve — the same
    // reasoning as an unmodeled form below, so it shows as a bare marker rather
    // than borrowing the parent's acute profile (estradiol's IM curve for an
    // Estradiol Valerate depot). The body-load readout uses its depot half-life.
    if (PKResolver.isDepot(entry, catalog)) return null
    // An extended-release product we model per-product ("Concerta", "Adderall XR")
    // draws ITS authored envelope — the whole point of the product-duration table —
    // even though its release form is otherwise unmodeled.
    val productDuration = entry.productDuration(catalog)
    // Otherwise a dose that names a form we don't model draws no curve at all. Both
    // fallbacks below would answer with the *base* form's kinetics: a Concerta dose
    // would take Ritalin's 150–240 min profile, and an OxyContin dose oxycodone's
    // ~4 h — the exact number that invites a redose. We know the form and we know we
    // can't model it, so we say when it was taken and stop there. This must precede
    // both tiers.
    if (productDuration == null && entry.namesUnmodeledForm) return null

    val doseRange = ActiveSubstanceCalculator.resolveDoseRange(substance, entry.route)
    val intensity = ActiveSubstanceCalculator.computeDoseIntensity(entry.amount, doseRange)
    val magnitude = ActiveSubstanceCalculator.computeDoseMagnitude(entry.amount, doseRange)
    // Prefer the product envelope; else the form actually logged — a D-isomer dose
    // must not be drawn with the racemic curve the detail card wouldn't show.
    val duration = productDuration ?: substance.timelineDuration(
        entry.route, entry.saltForm, entry.isomer,
    ) ?: return null
    return from(
        // Canonical common name, so a dose logged under an alias (e.g. "Lysergic
        // Acid Diethylamide") labels its curve "LSD" like the rest of the app.
        name = substance.displayTitle,
        tint = tint,
        timestamp = entry.timestamp,
        amount = entry.amount,
        unit = entry.unit,
        routeDisplayName = entry.route.displayName,
        duration = duration,
        category = substance.category,
        doseIntensity = intensity,
        doseMagnitude = magnitude,
        heavyThresholdMagnitude = ActiveSubstanceCalculator.heavyThresholdMagnitude(doseRange),
        doseIsUnscaled = ActiveSubstanceCalculator.heavyReference(doseRange) == null,
        tachyphylaxis = substance.category.acuteToleranceFactor,
        weightKg = weightKg,
        zeroOrderKinetics = catalog.zeroOrderKinetics(substance.name, weightKg),
    )
}

/**
 * Convert dose records into the two inputs the timeline graph consumes: `states`
 * (doses that resolve duration data, drawn as curves) and `markers` (the
 * duration-less remainder, drawn as timestamp diamonds).
 *
 * Single source of truth shared by the day detail and the journal cards.
 *
 * @param tintFor the substance's identity color — the Oklch palette upstream, which
 *   is not ported yet. Taking it as a function keeps that port where it belongs and
 *   lets a test hand back a fixed color.
 */
fun ActiveSubstanceState.Companion.timeline(
    entries: List<DoseRecord>,
    tintFor: (substance: String) -> P3Color,
    catalog: SubstanceCatalog,
    weightKg: Double = PKModel.REFERENCE_BODY_WEIGHT_KG,
): TimelineInputs {
    val states = mutableListOf<ActiveSubstanceState>()
    val markers = mutableListOf<DoseMarker>()
    for (entry in entries) {
        val tint = tintFor(entry.substance)
        val state = from(entry, tint, catalog, weightKg)
        if (state != null) {
            states += state
            continue
        }
        // A dose with no curve normally still lands as a timestamp marker — but a
        // supplement without an acute profile (Vitamin D3, magnesium) has no
        // duration *or* effect to honestly plot, so it stays off the effect graph
        // entirely (it remains in the entries list and the info card). A
        // non-supplement with no data still gets its marker.
        val substance = catalog.lookup(entry.substance)
        if (substance?.category == SubstanceCategory.SUPPLEMENT) continue
        markers += DoseMarker(
            // Canonical name, so the marker's label and its lane matching agree with
            // the curves.
            substanceName = substance?.displayTitle ?: entry.substance,
            timestamp = entry.timestamp,
            tint = tint,
            amount = entry.amount,
            unit = entry.unit,
        )
    }
    return TimelineInputs(states, markers)
}

/** What [ActiveSubstanceState.Companion.timeline] produces: the drawable curves and the bare markers. */
data class TimelineInputs(
    val states: List<ActiveSubstanceState>,
    val markers: List<DoseMarker>,
)
