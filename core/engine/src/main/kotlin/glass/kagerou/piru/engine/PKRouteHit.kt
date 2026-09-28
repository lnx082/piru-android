package glass.kagerou.piru.engine

import glass.kagerou.piru.model.ConfidenceTier
import glass.kagerou.piru.model.flooredBy
import kotlin.math.pow

/**
 * One `pk_routes` row: a study's measured kinetics for one route of one
 * substance.
 *
 * Ported from `PKRouteHit` in `SubstanceReadModel+Pharmacology.swift`. A plain
 * value with no store behind it, so the coherent-row pick and the interspecies
 * projection below can run off the main thread and be tested directly.
 *
 * Most fields are nullable and most rows carry only some of them — that is the
 * ordinary case, not a gap: a paper that measured an oral half-life without an
 * IV arm has no Vd, and the resolver's whole job is to pick a *coherent* row
 * rather than assemble one from whichever studies happened to fill each field.
 */
data class PKRouteHit(
    val id: Long,
    val route: String,
    val bioavailabilityPct: Double? = null,
    val cmaxNgPerMl: Double? = null,
    val tmaxMin: Double? = null,
    val halfLifeMin: Double? = null,
    val vdLPerKg: Double? = null,
    val clearanceMlPerMinPerKg: Double? = null,
    val proteinBindingPct: Double? = null,
    val doseInStudyMg: Double? = null,
    val subjectN: Int? = null,
    val demographics: String? = null,
    /**
     * Study species (`human`, `rat`, `pig`, …), lowercased, or null when unstated.
     *
     * Drives [InterspeciesScaling.scaledToHuman]: a non-human row keeps its
     * species-invariant Vd/kg but has its confidence floored and its
     * clearance and half-life allometrically scaled to a 70 kg human.
     */
    val species: String? = null,
    val sourceSlug: String = "",
    val doi: String? = null,
    val pmid: Int? = null,
    val notes: String? = null,
    /** Citation-verification grade for this route's values, unverified when ungraded. */
    val confidence: ConfidenceTier = ConfidenceTier.UNVERIFIED,
)

/**
 * Interspecies allometric projection and the reference weights it needs.
 *
 * Ported from `SubstanceStore.scaledToHuman(_:)` and its species table.
 */
object InterspeciesScaling {

    /**
     * Reference body weights (kg) per study species.
     *
     * A species **absent** from this table is left unscaled rather than
     * defaulted: projecting from an unknown body mass would invent a scale
     * factor, and an unscaled animal row badged at its own low confidence is the
     * honest answer.
     */
    val SPECIES_REFERENCE_WEIGHT_KG: Map<String, Double> = mapOf(
        "rat" to 0.25,
        "pig" to 40.0,
        "human" to 70.0,
        "mouse" to 0.02,
        "dog" to 10.0,
        "monkey" to 3.5,
    )

    /**
     * Allometrically project a non-human PK row onto a 70 kg human.
     *
     * ## Which quantities scale, and which do not
     * Volume of distribution **per kg** is species-*invariant* — it reflects
     * tissue partitioning, not body size — so Vd/kg passes through unchanged and
     * only its *confidence* is floored, because a non-human Vd is a class-default
     * proxy and never a human-anchored value.
     *
     * Clearance and half-life **do** scale with body mass, by the classic
     * allometric exponents: `CL ∝ BW^0.75` and `t½ ∝ BW^0.25`. These are the
     * last-resort fill: a measured human half-life or Tmax always wins over a
     * scaled animal one, which is the assembly's job rather than this function's.
     *
     * ## The caveat is load-bearing
     * Single-species scaling systematically **underpredicts cathinone half-life
     * by two to three times** — validated: mephedrone rat-scaled ≈ 65 min against
     * a measured human 129 min; 3-MMC pig-scaled ≈ 55 min against 180 min. So a
     * scaled half-life is a floor-confidence stand-in and never a substitute for
     * the measured human value where one exists.
     *
     * A human or unknown-species row is returned unchanged.
     */
    fun scaledToHuman(row: PKRouteHit): PKRouteHit {
        val species = row.species?.lowercase()
        if (species == null || species == "human") return row
        val bodyWeightKg = SPECIES_REFERENCE_WEIGHT_KG[species] ?: return row
        if (bodyWeightKg <= 0) return row

        val massRatio = 70.0 / bodyWeightKg
        val clearanceScale = massRatio.pow(0.75)
        val halfLifeScale = massRatio.pow(0.25)
        return row.copy(
            halfLifeMin = row.halfLifeMin?.times(halfLifeScale),
            clearanceMlPerMinPerKg = row.clearanceMlPerMinPerKg?.times(clearanceScale),
            // `flooredBy` floors to *at most* `LOW`, whatever grade the source
            // carried: a scaled value is never better than a proxy, so a
            // high-graded animal row cannot arrive looking human-anchored.
            confidence = row.confidence.flooredBy(ConfidenceTier.LOW),
        )
    }

    /** Rebuild [row] with its confidence floored to at most [ceiling]. */
    fun flooringConfidence(row: PKRouteHit, ceiling: ConfidenceTier): PKRouteHit =
        row.copy(confidence = row.confidence.flooredBy(ceiling))
}
