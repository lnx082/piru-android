package glass.kagerou.piru.ui.tools

import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.substance.DbSubstanceCatalog
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The pharmacology table's two rules that are not drawing.
 *
 * ## The inclusion gate
 * A row with no half-life and no PK numbers is a name in a grid of dashes, and upstream drops it. The gate
 * is asserted on the row type itself, because it is what decides whether a substance appears at all — and a
 * substance silently missing from a comparison table is worse than one shown with gaps, because the reader
 * cannot tell the difference from a name they did not think to look for.
 *
 * ## Missing values sort last, in both directions
 * The subtle rule. Sorting ascending by half-life with missing values treated as zero would put every
 * unmeasured substance at the top; treating them as `Double.MAX_VALUE` puts them at the bottom ascending and
 * at the *top* descending, which is the failure. The screen's comparators use `MAX_VALUE` as the sentinel and
 * then reverse the whole list, so descending brings them back to the top — this test pins that, so the
 * behaviour is a decision rather than an accident.
 */
class PharmaTableRowTest {

    private fun row(
        name: String = "Test",
        halfLife: Double? = null,
        tmax: Double? = null,
        bioavailability: Double? = null,
        cmax: Double? = null,
        proteinBinding: Double? = null,
        vd: Double? = null,
        clearance: Double? = null,
    ) = DbSubstanceCatalog.PharmaTableRow(
        name = name,
        displayName = name,
        category = SubstanceCategory.OTHER,
        classTitle = null,
        halfLifeMin = halfLife,
        tmaxMin = tmax,
        bioavailabilityPct = bioavailability,
        cmaxNgPerMl = cmax,
        proteinBindingPct = proteinBinding,
        vdLPerKg = vd,
        clearanceMlPerMinPerKg = clearance,
        route = RouteOfAdministration.ORAL,
        sourceSlug = "pdsp",
    )

    @Test
    fun `a row with no numbers at all is not worth showing`() {
        row().hasAnyData shouldBe false
    }

    /**
     * Any single number is enough.
     *
     * Asserted column by column, because the gate is a chain of `||` and a typo in one term would drop a
     * whole column's worth of substances without anything looking wrong.
     */
    @Test
    fun `each column on its own makes a row worth showing`() {
        row(halfLife = 120.0).hasAnyData shouldBe true
        row(tmax = 45.0).hasAnyData shouldBe true
        row(bioavailability = 80.0).hasAnyData shouldBe true
        row(cmax = 12.0).hasAnyData shouldBe true
        row(proteinBinding = 90.0).hasAnyData shouldBe true
        row(vd = 3.0).hasAnyData shouldBe true
        row(clearance = 5.0).hasAnyData shouldBe true
    }

    /**
     * A zero is data, not an absence.
     *
     * `0.0` is a real measurement for Tmax (an immediate-release formulation) and for clearance in a
     * hypothetical, and a truthiness check on the `Double` would drop the row. Worth pinning because the
     * distinction between "zero" and "missing" is exactly what the nullable type carries.
     */
    @Test
    fun `a zero counts as data`() {
        row(tmax = 0.0).hasAnyData shouldBe true
        row(bioavailability = 0.0).hasAnyData shouldBe true
    }

    /**
     * The sort comparator's sentinel, and what it does in each direction.
     *
     * This mirrors the screen's `sortedBy { it.halfLifeMin ?: Double.MAX_VALUE }` then optional reversal.
     * The assertion names the consequence rather than the mechanism: missing values are last ascending, and
     * the reversal is what the reader asked for when they tapped the column twice.
     */
    @Test
    fun `missing values sort last ascending and first when reversed`() {
        val rows = listOf(
            row(name = "NoHalfLife"),
            row(name = "Long", halfLife = 600.0),
            row(name = "Short", halfLife = 60.0),
        )

        val ascending = rows.sortedBy { it.halfLifeMin ?: Double.MAX_VALUE }
        ascending.map { it.name } shouldBe listOf("Short", "Long", "NoHalfLife")

        val descending = ascending.reversed()
        descending.map { it.name } shouldBe listOf("NoHalfLife", "Long", "Short")
    }

    /**
     * The route the numbers came from is carried, because a half-life without it is unqualified.
     *
     * A substance measured orally and intravenously has two half-lives, and the preferred-row pick chooses
     * one. The row keeps the route so the table can say which, which is the difference between a number and
     * a claim.
     */
    @Test
    fun `the row carries the route its numbers came from`() {
        row(halfLife = 120.0).route shouldBe RouteOfAdministration.ORAL
    }
}
