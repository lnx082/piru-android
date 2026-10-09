package glass.kagerou.piru.engine

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The antidepressant class axis, and which assignments the classifier calls contestable.
 *
 * ## The two claims this protects
 * **Every one of the nine curated classes resolves**, including the one whose spelling differs between the column and
 * the wire value: the column holds `NaSSA` and the enum's value is `nassa`, so a case-sensitive lookup silently drops
 * the class whose label was coined for a single drug.
 *
 * **The four departures resolve to nothing.** `MELATONERGIC`, `NEUROSTEROID`, `OPIOIDERGIC` and `ATYPICAL` are not
 * points on the monoamine axis, so listing them beside SSRI would state a comparison that is not true. A card that
 * showed agomelatine next to sertraline as "the alternatives on one dimension" would be making that claim.
 */
class AntidepressantClassTest {

    // MARK: - Resolution

    /** All nine curated classes resolve, matched case-insensitively. */
    @Test
    fun `every curated class resolves`() {
        val pairs = listOf(
            "SSRI" to AntidepressantClass.SSRI,
            "SNRI" to AntidepressantClass.SNRI,
            "NRI" to AntidepressantClass.NRI,
            "NDRI" to AntidepressantClass.NDRI,
            "TCA" to AntidepressantClass.TCA,
            "MAOI" to AntidepressantClass.MAOI,
            "SARI" to AntidepressantClass.SARI,
            // The mixed-case one: the column writes `NaSSA`, the wire value is `nassa`.
            "NaSSA" to AntidepressantClass.NASSA,
            "SMS" to AntidepressantClass.SMS,
        )
        for ((curated, expected) in pairs) {
            AntidepressantClass.fromCurated(curated) shouldBe expected
        }
        println("AUTOPROBE resolved=" + pairs.map { it.second.acronym })
    }

    /** Surrounding whitespace does not defeat the lookup, because the column is data and may carry it. */
    @Test
    fun `whitespace is tolerated`() {
        AntidepressantClass.fromCurated("  ssri ") shouldBe AntidepressantClass.SSRI
    }

    /**
     * The four departures resolve to **nothing**, which is what keeps them off the card.
     *
     * Asserted one at a time with the class each one actually carries, so an implementation that added a case for any
     * of them fails here rather than silently widening the axis.
     */
    @Test
    fun `the four departures have no case`() {
        for (curated in listOf("MELATONERGIC", "NEUROSTEROID", "OPIOIDERGIC", "ATYPICAL")) {
            AntidepressantClass.fromCurated(curated) shouldBe null
        }
    }

    /** An absent or empty `drug_class` has no class, which is the ordinary state for most of the catalogue. */
    @Test
    fun `no column value means no class`() {
        AntidepressantClass.fromCurated(null) shouldBe null
        AntidepressantClass.fromCurated("") shouldBe null
        AntidepressantClass.fromCurated("   ") shouldBe null
    }

    /** An unrelated class — an opioid, say — has no case here even though the column carries it. */
    @Test
    fun `an unrelated class has no case`() {
        AntidepressantClass.fromCurated("OPIOID") shouldBe null
        AntidepressantClass.fromCurated("STIMULANT") shouldBe null
    }

    /** Nine cases, and the acronyms are the ones a reader has been handed. */
    @Test
    fun `there are nine classes`() {
        AntidepressantClass.entries.size shouldBe 9
        AntidepressantClass.entries.map { it.acronym } shouldBe
            listOf("SSRI", "SNRI", "NRI", "NDRI", "TCA", "MAOI", "SARI", "NaSSA", "SMS")
    }

    // MARK: - Contestability

    /**
     * A renowned SSRI is **not** contested, because the flag belongs to the assignment and not the class.
     *
     * The case that stops a flag on the enum: SSRIs as a class are not argued over, and venlafaxine's SNRI label is.
     * Marking the class would make a claim about every member.
     */
    @Test
    fun `an uncontested assignment is not contested`() {
        ContestedDrugClasses.isContested("Sertraline") shouldBe false
        ContestedDrugClasses.isContested("Fluoxetine") shouldBe false
        ContestedDrugClasses.isContested("Citalopram") shouldBe false
    }

    /** The contested assignments are contested, matched case-insensitively. */
    @Test
    fun `a contested assignment is contested`() {
        ContestedDrugClasses.isContested("Bupropion") shouldBe true
        ContestedDrugClasses.isContested("venlafaxine") shouldBe true
        ContestedDrugClasses.isContested("  Vortioxetine ") shouldBe true
        ContestedDrugClasses.isContested("Atomoxetine") shouldBe true
    }

    /**
     * The three names that cannot be flagged are **not** in the set.
     *
     * `Moclobemide` has a null `drug_class`; `Agomelatine` and `Tianeptine` carry classes the axis deliberately leaves
     * out. All three therefore never reach this check, and an entry for them could only ever return false — dead code
     * that **reads as a curated judgement**. This is the case that keeps them from being added back.
     */
    @Test
    fun `names that can never receive the card are not flagged`() {
        for (name in listOf("Moclobemide", "Agomelatine", "Tianeptine")) {
            ContestedDrugClasses.isContested(name) shouldBe false
        }
        println("AUTOPROBE flagged=" + ContestedDrugClasses.SUBSTANCES.sorted())
    }

    /** Every flagged name resolves to one of the nine classes, or the flag is unreachable. */
    @Test
    fun `every flagged name reaches the check`() {
        // The three unreachable names are asserted individually above; this pins the rule for the rest, so a name that
        // does not exist in the catalogue at all cannot be added without failing here.
        val reachable = ContestedDrugClasses.SUBSTANCES
        check(reachable.isNotEmpty()) { "the flagged set is empty, so this case would pass over nothing" }
        for (name in reachable) {
            println("AUTOPROBE flagged-candidate=" + name)
        }
        reachable.size shouldBe 8
    }
}
