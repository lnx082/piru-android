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

    // MARK: - The outlasts gate

    /**
     * A metabolite outlives the dose when it lasts longer than the parent's window.
     *
     * The window is the **longer** of the parent's half-life and its longest acute duration, because the claim is
     * "this is still going when the parent is not" and a parent is perceptible for its duration, not merely for one
     * half-life.
     */
    @Test
    fun `a metabolite outlives the dose`() {
        // Parent: 180-minute half-life, 360-minute duration. The window is 360.
        ActiveMetaboliteFold.outlastsDose(
            metaboliteHalfLifeMinutes = 600.0,
            parentHalfLifeMinutes = 180.0,
            parentDurationMinutes = 360.0,
        ) shouldBe true

        // Shorter than the window: a pathway, not a live substance.
        ActiveMetaboliteFold.outlastsDose(
            metaboliteHalfLifeMinutes = 120.0,
            parentHalfLifeMinutes = 180.0,
            parentDurationMinutes = 360.0,
        ) shouldBe false
    }

    /**
     * With no duration profile the half-life decides.
     *
     * The chronic-medication case: an SSRI carries a half-life and no acute duration table, and requiring a duration
     * would silence the block for exactly the drugs whose metabolites matter most.
     */
    @Test
    fun `with no duration the half-life decides`() {
        ActiveMetaboliteFold.outlastsDose(
            metaboliteHalfLifeMinutes = 1_440.0,
            parentHalfLifeMinutes = 900.0,
            parentDurationMinutes = null,
        ) shouldBe true
        ActiveMetaboliteFold.outlastsDose(
            metaboliteHalfLifeMinutes = 400.0,
            parentHalfLifeMinutes = 900.0,
            parentDurationMinutes = null,
        ) shouldBe false
    }

    /**
     * With nothing known about the parent, nothing is claimed.
     *
     * A zero window would make every metabolite "outlast" it, which would put the block on every dose of every
     * substance the catalogue knows nothing about — the opposite of the gate's purpose.
     */
    @Test
    fun `an unknown parent claims nothing`() {
        ActiveMetaboliteFold.outlastsDose(
            metaboliteHalfLifeMinutes = 600.0,
            parentHalfLifeMinutes = null,
            parentDurationMinutes = null,
        ) shouldBe false
        ActiveMetaboliteFold.outlastsDose(
            metaboliteHalfLifeMinutes = 600.0,
            parentHalfLifeMinutes = 0.0,
            parentDurationMinutes = 0.0,
        ) shouldBe false
    }

    /** A metabolite with no recorded half-life cannot be claimed to outlast anything. */
    @Test
    fun `a metabolite with no half-life claims nothing`() {
        ActiveMetaboliteFold.outlastsDose(
            metaboliteHalfLifeMinutes = null,
            parentHalfLifeMinutes = 180.0,
            parentDurationMinutes = 360.0,
        ) shouldBe false
    }
}
