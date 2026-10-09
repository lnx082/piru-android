package glass.kagerou.piru.engine

import java.time.Instant

/**
 * The active metabolites of one dose, folded from the catalogue's `metabolism` rows.
 *
 * Ported from `SubstanceDetailModel.foldActiveMetabolites`. "Also Active" answers a different question from the body
 * load: that card says how much is left, this one says **of what**. Someone reading a dose detail wants to know
 * whether the thing still working is the thing they swallowed.
 *
 * ## Four exclusions, and each is a way for this to lie
 *
 * 1. **Inactive rows.** A metabolite recorded as inactive is on the page as a pathway, not as a substance acting.
 * 2. **Rows with no name.** An unnamed metabolite cannot be drawn or linked.
 * 3. **`unchanged …` rows.** These are excretion rows wearing a metabolite field — they name no new molecule. Upstream
 *    detects them by prefix, because the catalogue's own `MetabolismRow` elimination rule does the same.
 * 4. **Combination-only species** (cocaethylene, ethylphenidate) form solely while a second drug is onboard, so they
 *    must not read as an unconditional metabolite of the parent. The column is `metabolism.conditional_combination_id`
 *    and this port was **not reading it at all** until this fold needed it — so the exclusion is new here even though
 *    the data has always been in the catalogue.
 *
 * ## Order and grouping
 * Rows are grouped by lower-cased name in **first-appearance order**, which is the catalogue's curation order rather
 * than alphabetical: the source lists the pathway the way a reader should meet it. Several rows can describe one
 * metabolite — one per enzyme — and they are folded into one entry, with the longest half-life among them and the
 * first non-null potency, because a molecule has one half-life and the rows are measurements of the same thing.
 */
object ActiveMetaboliteFold {

    /** One metabolite, folded from every row that names it. */
    data class Entry(
        /** The catalogue's spelling, from the first row that named it. */
        val name: String,
        /** The metabolite's own substance name when the catalogue carries it as one, for linking to a detail page. */
        val substanceName: String?,
        /** The longest half-life recorded across the rows describing it. */
        val halfLifeMinutes: Double?,
        /** The narrowest recorded range, when the catalogue carries bounds rather than a single figure. */
        val halfLifeLowMinutes: Double?,
        val halfLifeHighMinutes: Double?,
        /** Potency against the parent where recorded, as a percentage. */
        val potencyVsParentPct: Double?,
        /** What that percentage is measured against, so it is never read as clinical strength by default. */
        val potencyBasis: MetabolitePotencyBasis?,
        val potencyTarget: String?,
        /** How this metabolite's mechanism differs from the parent's. `UNKNOWN` where the catalogue does not say. */
        val mechanismVsParent: MetaboliteMechanism,
        val tmaxMinutes: Double?,
        /** Every enzyme row that named it, in catalogue order. */
        val enzymes: List<String>,
    )

    /**
     * Folds [rows] into the active metabolites worth naming.
     *
     * The order is first-appearance, not alphabetical — see this object's own note.
     */
    fun fold(rows: List<MetabolismHit>): List<Entry> {
        val order = mutableListOf<String>()
        val byName = linkedMapOf<String, MutableList<MetabolismHit>>()
        for (row in rows) {
            // All four exclusions, in the order upstream applies them.
            if (row.metaboliteActive != true) continue
            val name = row.metaboliteName?.takeIf { it.isNotBlank() } ?: continue
            if (name.lowercase().startsWith("unchanged")) continue
            if (row.conditionalCombinationId != null) continue
            val key = name.lowercase()
            if (key !in byName) order += key
            byName.getOrPut(key) { mutableListOf() } += row
        }

        return order.mapNotNull { key ->
            val group = byName[key] ?: return@mapNotNull null
            // The catalogue's own spelling, from the first row: a group's rows are the same molecule, so any of them
            // would do for identity, and the first is the one the curator wrote.
            val display = group.first().metaboliteName ?: return@mapNotNull null
            Entry(
                name = display,
                substanceName = group.firstNotNullOfOrNull { it.metaboliteSubstanceName },
                // The **longest** half-life, because a molecule has one and the rows are measurements of it. Taking the
                // first would make the answer depend on which enzyme the catalogue happened to list first.
                halfLifeMinutes = group.mapNotNull { it.metaboliteHalfLifeMinutes }.maxOrNull(),
                halfLifeLowMinutes = group.mapNotNull { it.metaboliteHalfLifeLowMinutes }.minOrNull(),
                halfLifeHighMinutes = group.mapNotNull { it.metaboliteHalfLifeHighMinutes }.maxOrNull(),
                // The first recorded potency: several rows can carry one and they describe the same molecule.
                potencyVsParentPct = group.firstNotNullOfOrNull { it.metabolitePotencyVsParentPct },
                potencyBasis = group.firstNotNullOfOrNull { it.metabolitePotencyBasis },
                potencyTarget = group.firstNotNullOfOrNull { it.metabolitePotencyTarget },
                // The first mechanism that is actually **stated**. The field is non-null and defaults to `UNKNOWN`, so
                // this searches for the first row that said something — `firstNotNullOfOrNull` cannot help when every
                // value is non-null, and taking the first row's value would make the answer depend on which enzyme the
                // catalogue happened to list first.
                mechanismVsParent = group.map { it.metaboliteMechanismVsParent }
                    .firstOrNull { it != MetaboliteMechanism.UNKNOWN }
                    ?: MetaboliteMechanism.UNKNOWN,
                tmaxMinutes = group.firstNotNullOfOrNull { it.metaboliteTmaxMinutes },
                enzymes = group.mapNotNull { it.enzyme }.filter { it.isNotBlank() },
            )
        }
    }

    /**
     * Whether a metabolite outlasts the dose it came from.
     *
     * Ported from `ActiveMetabolite.earnsOwnSection`. This is the section's gate: **only a metabolite that outlives the
     * dose gets its own surface.** A metabolite that clears faster than its parent is a pathway, not a live substance,
     * and saying "also active" about it would send a reader looking for an effect that is not there.
     *
     * Measured against **the longer of** the parent's own half-life and its longest acute duration, because the claim
     * is "this is still going when the parent is not" and the parent is perceptible for its duration, not merely for
     * one half-life. A missing `parentDurationMinutes` leaves the half-life comparison, which is the chronic-medication
     * case: an SSRI carries a half-life and no acute duration table.
     */
    fun outlastsDose(
        metaboliteHalfLifeMinutes: Double?,
        parentHalfLifeMinutes: Double?,
        parentDurationMinutes: Double?,
    ): Boolean {
        val metabolite = metaboliteHalfLifeMinutes ?: return false
        val parentWindow = maxOf(parentHalfLifeMinutes ?: 0.0, parentDurationMinutes ?: 0.0)
        // No parent figure at all means nothing can be claimed, so nothing is. A zero window would make every
        // metabolite "outlast" it.
        if (parentWindow <= 0.0) return false
        return metabolite > parentWindow
    }
}
