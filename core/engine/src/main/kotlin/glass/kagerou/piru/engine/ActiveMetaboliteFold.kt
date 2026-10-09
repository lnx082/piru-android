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
     * What can honestly be said about a metabolite beside its parent.
     *
     * Ported from `ActiveMetabolite.statement(parentName:parentHalfLifeMinutes:parentDurationMinutes:)`. The order of
     * the branches **is** the rule: divergence first, then a duration consequence, then potency, then a bare
     * relationship. Each branch answers a stronger question than the one below it, so reordering them would let a
     * potency ratio speak over a metabolite that simply lasts longer.
     */
    sealed class Statement {
        /** The metabolite is still going after the duration the reader just read. */
        data class OutlastsDuration(val metabolite: String, val parent: String) : Statement()

        /** The same fact where there is no duration to point at — the chronic medications. */
        data class PersistsBeyondParent(val metabolite: String, val parent: String) : Statement()

        /** The only unqualified comparative: dose for dose. */
        data class Comparable(val ratio: Double, val parent: String) : Statement()

        /**
         * A potency ratio between the two **molecules**, where too little of a dose converts for it to be a claim
         * about doses. Codeine → morphine is the case: 10× is right molecule-for-molecule and badly wrong
         * dose-for-dose.
         */
        data class StrongerMolecule(
            val ratio: Double,
            val parent: String,
            val metabolite: String,
            /** The share of a dose that becomes this metabolite, or null when unrecorded. */
            val convertedPct: Double?,
        ) : Statement()

        /** A comparative that must carry its basis and target. */
        data class Qualified(
            val ratio: Double,
            val parent: String,
            val basis: MetabolitePotencyBasis?,
            val target: String?,
        ) : Statement()

        /** Different pharmacology, not a stronger parent. Better described than quantified. */
        data class Divergent(val parent: String) : Statement()

        /** The relationship alone — new information to most readers, and not a failure state. */
        data class RelationshipOnly(val metabolite: String, val parent: String) : Statement()
    }

    /**
     * The formation share at or above which "dose for dose" means something.
     *
     * Upstream's threshold. Below it the same ratio is still true, but about **molecules** rather than doses, which is
     * a different sentence and a different [Statement].
     */
    const val DOSE_EQUIVALENT_FORMATION_PCT: Double = 50.0

    /**
     * Resolves the statement for [entry] beside its parent.
     *
     * [materiallyActive] is the caller's answer to "is this worth speaking about at all" — upstream's
     * `isMateriallyActive`, which weighs the mechanism and the potency rather than the `active` flag alone.
     */
    fun statement(
        entry: Entry,
        parentName: String,
        parentHalfLifeMinutes: Double?,
        parentDurationMinutes: Double?,
        formationFractionPct: Double?,
        materiallyActive: Boolean,
    ): Statement {
        if (entry.mechanismVsParent == MetaboliteMechanism.DIVERGENT) return Statement.Divergent(parentName)

        if (materiallyActive) {
            val mine = entry.halfLifeMinutes
            if (mine != null) {
                if (parentDurationMinutes != null && parentDurationMinutes > 0) {
                    // The duration decides when it exists, and the half-life is **not** consulted: a metabolite that
                    // lasts as long as the parent's duration still outlasts the effect, which is the claim.
                    if (mine >= parentDurationMinutes) {
                        return Statement.OutlastsDuration(entry.name, parentName)
                    }
                } else if (parentHalfLifeMinutes != null && parentHalfLifeMinutes > 0 &&
                    mine >= parentHalfLifeMinutes * 2
                ) {
                    // Twice as long, not merely longer: a metabolite that lingers a little has not earned a sentence
                    // about outlasting anything.
                    return Statement.PersistsBeyondParent(entry.name, parentName)
                }
            }
        }

        val clinical = entry.potencyVsParentPct?.takeIf {
            entry.potencyBasis == MetabolitePotencyBasis.CLINICAL
        }
        if (clinical != null && entry.mechanismVsParent == MetaboliteMechanism.SCALED) {
            val ratio = clinical / 100.0
            // "Dose for dose" is a claim about doses and is only true when most of a dose actually becomes the
            // metabolite. Without that term the ratio still means something — it means it about the molecules.
            if (formationFractionPct != null && formationFractionPct >= DOSE_EQUIVALENT_FORMATION_PCT) {
                return Statement.Comparable(ratio, parentName)
            }
            return Statement.StrongerMolecule(ratio, parentName, entry.name, formationFractionPct)
        }

        if (entry.potencyVsParentPct != null) {
            return Statement.Qualified(
                ratio = entry.potencyVsParentPct / 100.0,
                parent = parentName,
                basis = entry.potencyBasis,
                target = entry.potencyTarget,
            )
        }

        return Statement.RelationshipOnly(entry.name, parentName)
    }

    /**
     * Whether a metabolite **earns a section of its own**.
     *
     * The separate, editorial question, kept apart from [statement] on purpose. Only a **duration** consequence earns
     * one: a metabolite is a normal, expected part of how a drug works, and saying so at section volume overstates it.
     * Oxymorphone is oxycodone's principal pathway and its 10 : 1 ratio is textbook, so promoting it to a headline
     * implies news where there is none — and duplicates the metabolism table directly below.
     *
     * What a reader cannot get anywhere else on the screen is that the dose keeps working after the duration says it
     * stopped. That is the whole warrant for the surface.
     */
    fun earnsOwnSection(
        entry: Entry,
        parentHalfLifeMinutes: Double?,
        parentDurationMinutes: Double?,
        materiallyActive: Boolean,
    ): Boolean = when (
        statement(entry, "", parentHalfLifeMinutes, parentDurationMinutes, null, materiallyActive)
    ) {
        is Statement.OutlastsDuration, is Statement.PersistsBeyondParent -> true
        is Statement.Comparable, is Statement.StrongerMolecule, is Statement.Qualified,
        is Statement.Divergent, is Statement.RelationshipOnly,
        -> false
    }
}
