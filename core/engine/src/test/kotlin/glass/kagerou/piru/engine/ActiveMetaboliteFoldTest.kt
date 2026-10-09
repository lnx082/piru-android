package glass.kagerou.piru.engine

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Folding the catalogue's `metabolism` rows into the active metabolites of a dose.
 *
 * ## The four exclusions, each of which is a way for this to lie
 * A metabolite row can be inactive, unnamed, an excretion row wearing a metabolite field, or a species that only
 * forms while a second drug is onboard. Every one of those must be out, and each has its own case here — because a
 * fold that got three of the four right would look correct on most substances and be wrong on the ones where it
 * matters most (cocaethylene beside norketamine reads as "your body makes this from that dose").
 *
 * ## Grouping
 * Several rows describe one metabolite, one per enzyme. They are one entry: a molecule has one half-life, and the
 * rows are measurements of the same thing. The cases assert that the fold takes the **longest** half-life and the
 * first *stated* mechanism rather than the first row's, since which enzyme the catalogue lists first is curation
 * order and not a fact about the molecule.
 */
class ActiveMetaboliteFoldTest {

    private var nextID = 1L
    private var nextSourceID = 1L

    private fun row(
        enzyme: String = "CYP3A4",
        name: String? = "norketamine",
        active: Boolean? = true,
        halfLife: Double? = null,
        low: Double? = null,
        high: Double? = null,
        potency: Double? = null,
        basis: MetabolitePotencyBasis? = null,
        target: String? = null,
        mechanism: MetaboliteMechanism = MetaboliteMechanism.UNKNOWN,
        substanceName: String? = null,
        conditional: String? = null,
        tmax: Double? = null,
    ) = MetabolismHit(
        id = nextID++,
        enzyme = enzyme,
        metaboliteName = name,
        metaboliteActive = active,
        metaboliteHalfLifeMinutes = halfLife,
        metaboliteHalfLifeLowMinutes = low,
        metaboliteHalfLifeHighMinutes = high,
        metabolitePotencyVsParentPct = potency,
        metabolitePotencyBasis = basis,
        metabolitePotencyTarget = target,
        metaboliteMechanismVsParent = mechanism,
        metaboliteSubstanceName = substanceName,
        conditionalCombinationId = conditional,
        metaboliteTmaxMinutes = tmax,
    )

    // MARK: - The four exclusions

    /** An inactive row is a pathway, not something acting. */
    @Test
    fun `an inactive metabolite is excluded`() {
        ActiveMetaboliteFold.fold(listOf(row(name = "inactive-species", active = false))) shouldBe emptyList()
    }

    /** An active row with no name cannot be drawn or linked, so it is not an entry. */
    @Test
    fun `an unnamed metabolite is excluded`() {
        ActiveMetaboliteFold.fold(listOf(row(name = null))) shouldBe emptyList()
        ActiveMetaboliteFold.fold(listOf(row(name = "   "))) shouldBe emptyList()
    }

    /** A row whose "metabolite" is the parent unchanged names no new molecule. */
    @Test
    fun `an unchanged row is excluded`() {
        ActiveMetaboliteFold.fold(listOf(row(name = "unchanged ketamine"))) shouldBe emptyList()
        // The prefix test is case-insensitive, as upstream's is.
        ActiveMetaboliteFold.fold(listOf(row(name = "Unchanged Ketamine"))) shouldBe emptyList()
    }

    /**
     * A combination-only species is excluded.
     *
     * Cocaethylene forms only while alcohol is onboard. Listing it under a cocaine dose would say the body makes it
     * from that dose, which is false in exactly the case a reader is most likely to misread.
     */
    @Test
    fun `a conditional combination species is excluded`() {
        ActiveMetaboliteFold.fold(listOf(row(name = "cocaethylene", conditional = "cocaine+ethanol"))) shouldBe
            emptyList()
    }

    // MARK: - Grouping and folding

    /** Two rows naming one metabolite produce one entry, with both enzymes recorded. */
    @Test
    fun `rows naming one metabolite fold into one entry`() {
        val folded = ActiveMetaboliteFold.fold(
            listOf(row(enzyme = "CYP3A4"), row(enzyme = "CYP2B6")),
        )
        folded.size shouldBe 1
        folded.single().name shouldBe "norketamine"
        folded.single().enzymes shouldContainExactly listOf("CYP3A4", "CYP2B6")
    }

    /**
     * The **longest** half-life wins, not the first.
     *
     * A molecule has one half-life and the rows are measurements of the same thing; taking the first would make the
     * answer depend on which enzyme the catalogue listed first.
     */
    @Test
    fun `the longest half-life among the rows wins`() {
        val folded = ActiveMetaboliteFold.fold(
            listOf(row(halfLife = 120.0), row(halfLife = 480.0), row(halfLife = 300.0)),
        )
        folded.single().halfLifeMinutes shouldBe 480.0
    }

    /** A recorded range is carried, with the widest bounds among the rows. */
    @Test
    fun `a recorded range is carried`() {
        val folded = ActiveMetaboliteFold.fold(
            listOf(row(low = 200.0, high = 400.0), row(low = 150.0, high = 500.0)),
        )
        folded.single().halfLifeLowMinutes shouldBe 150.0
        folded.single().halfLifeHighMinutes shouldBe 500.0
    }

    /**
     * The first **stated** mechanism wins, and a group that states none is `UNKNOWN`.
     *
     * The field is non-null and defaults to `UNKNOWN`, so this is a search for the first row that said something. Two
     * cases, because an implementation that took the first row's value would pass the second and fail the first.
     */
    @Test
    fun `the first stated mechanism wins`() {
        val stated = ActiveMetaboliteFold.fold(
            listOf(row(mechanism = MetaboliteMechanism.UNKNOWN), row(mechanism = MetaboliteMechanism.DIVERGENT)),
        )
        stated.single().mechanismVsParent shouldBe MetaboliteMechanism.DIVERGENT

        val silent = ActiveMetaboliteFold.fold(listOf(row(), row()))
        silent.single().mechanismVsParent shouldBe MetaboliteMechanism.UNKNOWN
    }

    /** Potency carries its basis and target, because a percentage without them cannot be read. */
    @Test
    fun `potency carries its basis and target`() {
        val folded = ActiveMetaboliteFold.fold(
            listOf(
                row(
                    potency = 33.0,
                    basis = MetabolitePotencyBasis.RECEPTOR_AFFINITY,
                    target = "MOR",
                ),
            ),
        )
        folded.single().potencyVsParentPct shouldBe 33.0
        folded.single().potencyBasis shouldBe MetabolitePotencyBasis.RECEPTOR_AFFINITY
        folded.single().potencyTarget shouldBe "MOR"
    }

    /** The metabolite's own substance name comes through, so the card can link to a real page. */
    @Test
    fun `the metabolite's own substance name is carried`() {
        val folded = ActiveMetaboliteFold.fold(
            listOf(row(name = "norketamine", substanceName = "Norketamine")),
        )
        folded.single().substanceName shouldBe "Norketamine"
    }

    /** An empty input folds to an empty list rather than to a placeholder. */
    @Test
    fun `no rows fold to nothing`() {
        ActiveMetaboliteFold.fold(emptyList()) shouldBe emptyList()
    }

    /**
     * The order is **first appearance**, not alphabetical.
     *
     * The catalogue's curation order is the order a reader should meet the pathways in, and sorting would replace a
     * decision with an accident of spelling.
     */
    @Test
    fun `the order is first appearance`() {
        val folded = ActiveMetaboliteFold.fold(
            listOf(row(name = "zebra-metabolite"), row(name = "alpha-metabolite")),
        )
        folded.map { it.name } shouldContainExactly listOf("zebra-metabolite", "alpha-metabolite")
    }

    /**
     * One folded entry, built **through the fold** rather than by hand.
     *
     * Going through `fold` keeps this honest: a hand-built `Entry` could carry a combination of fields the fold would
     * never produce, and a statement case written against that would pass while the real pipeline behaved differently.
     */
    private fun entryWith(
        halfLife: Double? = null,
        potency: Double? = null,
        basis: MetabolitePotencyBasis? = null,
        target: String? = null,
        mechanism: MetaboliteMechanism = MetaboliteMechanism.UNKNOWN,
    ): ActiveMetaboliteFold.Entry = ActiveMetaboliteFold.fold(
        listOf(
            row(
                name = "norketamine",
                halfLife = halfLife,
                potency = potency,
                basis = basis,
                target = target,
                mechanism = mechanism,
            ),
        ),
    ).single()

    // MARK: - The gate: what earns a section

    /**
     * A metabolite between **1× and 2×** the parent, with no duration, does **not** earn a section.
     *
     * The case my first version of this rule got wrong, and the only input where "longer than the parent" and "twice
     * the parent" disagree. Its absence is why the error survived a green test run: every case I had written was on
     * one side of the boundary or the other, and none was between them.
     */
    @Test
    fun `a metabolite under twice the parent does not earn a section`() {
        val entry = entryWith(halfLife = 300.0, mechanism = MetaboliteMechanism.SCALED)
        ActiveMetaboliteFold.earnsOwnSection(
            entry = entry,
            parentHalfLifeMinutes = 200.0,
            parentDurationMinutes = null,
            materiallyActive = true,
        ) shouldBe false
    }

    /**
     * A metabolite at **exactly** the parent's duration earns a section.
     *
     * The comparison is `>=`, not `>`: a metabolite that lasts as long as the parent's duration outlasts the *effect*,
     * which is the claim the sentence makes.
     */
    @Test
    fun `a metabolite matching the duration exactly earns a section`() {
        val entry = entryWith(halfLife = 360.0)
        val statement = ActiveMetaboliteFold.statement(
            entry = entry,
            parentName = "Ketamine",
            parentHalfLifeMinutes = 180.0,
            parentDurationMinutes = 360.0,
            formationFractionPct = null,
            materiallyActive = true,
        )
        statement shouldBe ActiveMetaboliteFold.Statement.OutlastsDuration("norketamine", "Ketamine")
        ActiveMetaboliteFold.earnsOwnSection(entry, 180.0, 360.0, materiallyActive = true) shouldBe true
    }

    /**
     * With no duration, **twice** the parent half-life earns a section.
     *
     * The chronic-medication branch: requiring a duration would silence the block for exactly the drugs whose
     * metabolites matter most.
     */
    @Test
    fun `twice the parent half-life earns a section`() {
        val entry = entryWith(halfLife = 1_800.0)
        ActiveMetaboliteFold.statement(
            entry, "Fluoxetine", parentHalfLifeMinutes = 900.0, parentDurationMinutes = null,
            formationFractionPct = null, materiallyActive = true,
        ) shouldBe ActiveMetaboliteFold.Statement.PersistsBeyondParent("norketamine", "Fluoxetine")
    }

    /** With nothing known about the parent, nothing is claimed — a zero parent would let everything through. */
    @Test
    fun `an unknown parent claims nothing`() {
        val entry = entryWith(halfLife = 600.0)
        ActiveMetaboliteFold.earnsOwnSection(entry, null, null, materiallyActive = true) shouldBe false
    }

    /** A metabolite with no half-life cannot outlast anything, however the parent is described. */
    @Test
    fun `a metabolite with no half-life claims nothing`() {
        val entry = entryWith(halfLife = null)
        ActiveMetaboliteFold.earnsOwnSection(entry, 180.0, 360.0, materiallyActive = true) shouldBe false
    }

    // MARK: - The statement resolver's order

    /**
     * **Divergence outranks everything**, including a duration consequence.
     *
     * Norperidine is a convulsant where pethidine is an analgesic, and saying "it outlasts the dose" would bury the
     * one thing that matters about it.
     */
    @Test
    fun `divergence outranks a duration consequence`() {
        val entry = entryWith(halfLife = 10_000.0, mechanism = MetaboliteMechanism.DIVERGENT)
        ActiveMetaboliteFold.statement(
            entry, "Pethidine", parentHalfLifeMinutes = 180.0, parentDurationMinutes = 240.0,
            formationFractionPct = null, materiallyActive = true,
        ) shouldBe ActiveMetaboliteFold.Statement.Divergent("Pethidine")
        // And it earns no section, because only a duration consequence does.
        ActiveMetaboliteFold.earnsOwnSection(entry, 180.0, 240.0, materiallyActive = true) shouldBe false
    }

    /**
     * A clinical ratio with **enough formation** is "dose for dose"; with too little it is molecule-for-molecule.
     *
     * Codeine → morphine: the 10 : 1 ratio is right about the molecules and badly wrong about doses, because only a
     * small part of a codeine dose is ever demethylated. The threshold is 50%.
     */
    @Test
    fun `formation decides between comparable and stronger-molecule`() {
        val entry = entryWith(
            halfLife = 100.0,
            potency = 900.0,
            basis = MetabolitePotencyBasis.CLINICAL,
            mechanism = MetaboliteMechanism.SCALED,
        )

        ActiveMetaboliteFold.statement(
            entry, "Codeine", parentHalfLifeMinutes = 180.0, parentDurationMinutes = null,
            formationFractionPct = 10.0, materiallyActive = true,
        ) shouldBe ActiveMetaboliteFold.Statement.StrongerMolecule(9.0, "Codeine", "norketamine", 10.0)

        ActiveMetaboliteFold.statement(
            entry, "Codeine", parentHalfLifeMinutes = 180.0, parentDurationMinutes = null,
            formationFractionPct = 80.0, materiallyActive = true,
        ) shouldBe ActiveMetaboliteFold.Statement.Comparable(9.0, "Codeine")
    }

    /**
     * A **non-clinical** basis never produces the unqualified comparative.
     *
     * The catalogue has carried receptor-affinity ratios — tramadol to M1 reads 20 000% from a "~200× MOR affinity"
     * source — and printing that as "dose for dose" would be a twenty-thousand-fold overstatement.
     */
    @Test
    fun `a receptor-affinity ratio is qualified, never comparable`() {
        val entry = entryWith(
            halfLife = 100.0,
            potency = 20_000.0,
            basis = MetabolitePotencyBasis.RECEPTOR_AFFINITY,
            target = "MOR",
            mechanism = MetaboliteMechanism.SCALED,
        )
        val statement = ActiveMetaboliteFold.statement(
            entry, "Tramadol", parentHalfLifeMinutes = 360.0, parentDurationMinutes = null,
            formationFractionPct = 90.0, materiallyActive = true,
        )
        statement shouldBe ActiveMetaboliteFold.Statement.Qualified(
            ratio = 200.0,
            parent = "Tramadol",
            basis = MetabolitePotencyBasis.RECEPTOR_AFFINITY,
            target = "MOR",
        )
    }

    /**
     * A **scaled** mechanism is required for any comparative; without it the ratio is merely qualified.
     *
     * A `MECHANISM` of `UNKNOWN` with a clinical ratio is a measurement without a claim, and the card hedges it rather
     * than asserting strength.
     */
    @Test
    fun `an unscaled mechanism qualifies its ratio`() {
        val entry = entryWith(
            halfLife = 100.0,
            potency = 150.0,
            basis = MetabolitePotencyBasis.CLINICAL,
            mechanism = MetaboliteMechanism.UNKNOWN,
        )
        (ActiveMetaboliteFold.statement(
            entry, "Parent", 180.0, null, formationFractionPct = 90.0, materiallyActive = true,
        ) is ActiveMetaboliteFold.Statement.Qualified) shouldBe true
    }

    /** No measurement at all leaves the relationship, which is still new information rather than a failure. */
    @Test
    fun `no measurement leaves the relationship`() {
        val entry = entryWith(halfLife = 100.0)
        ActiveMetaboliteFold.statement(
            entry, "Diazepam", parentHalfLifeMinutes = 4_320.0, parentDurationMinutes = null,
            formationFractionPct = null, materiallyActive = true,
        ) shouldBe ActiveMetaboliteFold.Statement.RelationshipOnly("norketamine", "Diazepam")
    }

    /**
     * A **materially inactive** metabolite gets no duration sentence even when it lasts longer.
     *
     * Upstream's `isMateriallyActive` weighs the mechanism and the potency, not the `active` flag alone. Without this
     * gate a long-lived but trivial species would claim the surface.
     */
    @Test
    fun `a materially inactive metabolite earns nothing`() {
        val entry = entryWith(halfLife = 10_000.0)
        ActiveMetaboliteFold.earnsOwnSection(entry, 180.0, 240.0, materiallyActive = false) shouldBe false
    }
}
