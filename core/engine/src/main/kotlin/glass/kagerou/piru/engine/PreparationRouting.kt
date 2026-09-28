package glass.kagerou.piru.engine

import glass.kagerou.piru.model.ConfidenceTier

/**
 * Routes a logged **preparation** to the compound whose pharmacology it should be
 * read from, with a content fraction and the confidence in that fraction.
 *
 * Ported from `SubstanceStore.preparationRouting` / `routePreparation(_:)`.
 *
 * A preparation is dosed as plant or product mass, but its pharmacology lives in
 * an active constituent — and almost nothing downstream can be computed from the
 * plant's own row, which typically has no molar mass, no Vd and no receptor
 * targets. Routing is what turns "5 g of kratom" into "75 mg of mitragynine" for
 * the occupancy math while the dose row keeps saying Kratom.
 *
 * The logged substance keeps its own name — the "driven by" chip still says
 * Kratom — and only the pharmacology rows and the dose scale come from the active
 * compound, badged at the routing's confidence because the content fraction is an
 * estimate.
 */
object PreparationRouting {

    /** Where a preparation's pharmacology is read from, and how much of it there is. */
    data class Route(
        /** The active compound's name. */
        val activeName: String,
        /** mg of active per mg of preparation. */
        val scale: Double,
        /** Confidence in [scale] — a content fraction varies by strain and by species. */
        val confidence: ConfidenceTier,
    )

    /**
     * Curated routing, keyed by lowercased logged name.
     *
     * The three entries are the ones where the active constituent's share of the
     * preparation is documented well enough to state a number:
     * - **Cannabis** is already logged in mg of Δ9-THC, so the logged mass *is*
     *   active mass at fraction 1.0. Medium rather than high because whole-plant
     *   entourage and CBD are ignored by the modelled terms.
     * - **Mushrooms** run about 0.8% psilocybin by dry weight (P. cubensis, a
     *   0.5–1.0% range). Low, because potency varies widely by species and by
     *   specimen.
     * - **Kratom** runs about 15 mg/g mitragynine in dried leaf (a 12–21 mg/g
     *   range), and extract products run higher. Low, for the same reason.
     */
    private val ROUTING: Map<String, Route> = mapOf(
        "cannabis" to Route("THC", 1.0, ConfidenceTier.MEDIUM),
        "mushrooms" to Route("Psilocybin", 0.008, ConfidenceTier.LOW),
        "kratom" to Route("Mitragynine", 0.015, ConfidenceTier.LOW),
    )

    /**
     * Route a logged name to the compound its pharmacology should be read from.
     *
     * A pure compound routes to itself at scale 1.0, flagged high — there is no
     * estimate involved, so nothing is degraded.
     */
    fun route(name: String): Route =
        ROUTING[name.lowercase()] ?: Route(activeName = name, scale = 1.0, confidence = ConfidenceTier.HIGH)
}
