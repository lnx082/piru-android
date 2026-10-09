package glass.kagerou.piru.data

import glass.kagerou.piru.data.entity.QuickLogDoseEntity
import glass.kagerou.piru.model.RouteOfAdministration
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Reading the dock's stored chips **in the order the user arranged them**.
 *
 * ## Why this is not `seed`
 * `seed(history, …)` derives chips from the **dose log** and sorts by timestamp; it is the first-run path.
 * `stored(rows)` returns exactly what `fold` wrote, and `fold` is what applies the floating order — a used chip moves
 * to the front of its group. The journal's dock needs the second, because seeding would re-sort the chips by when they
 * were taken and **undo the arrangement**, moving the chip out from under the thumb that was about to press it.
 *
 * The cases pin that difference rather than the mapping, which is mechanical: an order the rows carry is preserved
 * exactly, and the identity is recomputed rather than read.
 */
class QuickLogRecentsStoredTest {

    private var nextRow = 1L

    private fun row(
        substance: String,
        amount: Double = 100.0,
        unit: String = "mg",
        route: RouteOfAdministration = RouteOfAdministration.ORAL,
        productName: String? = null,
        sortOrder: Double = 0.0,
        lastUsedAt: java.util.Date = java.util.Date(0L),
    ) = QuickLogDoseEntity(
        rowId = nextRow++,
        substance = substance,
        route = route,
        amount = amount,
        unit = unit,
        sortOrder = sortOrder,
        lastUsedAt = lastUsedAt,
        productName = productName,
    )

    /** The rows come back in the order they were given, which is the whole contract. */
    @Test
    fun `the stored order is preserved`() {
        val rows = listOf(
            row("Zinc", sortOrder = 0.0),
            row("Aspirin", sortOrder = 1.0),
            row("Caffeine", sortOrder = 2.0),
        )
        QuickLogRecents.stored(rows).map { it.substance } shouldContainExactly
            listOf("Zinc", "Aspirin", "Caffeine")
    }

    /**
     * A floating order that differs from the **timestamp** order survives.
     *
     * The case that fails if the dock seeds instead of reading: the most recently used chip is not the newest one, so a
     * timestamp sort would put them the other way round.
     */
    @Test
    fun `an arrangement that disagrees with recency survives`() {
        val rows = listOf(
            // Used long ago but arranged first, and arranged-first is what the dock must honour.
            row("Old Favourite", lastUsedAt = java.util.Date(1_000L), sortOrder = 0.0),
            row("Just Used", lastUsedAt = java.util.Date(9_000L), sortOrder = 1.0),
        )
        QuickLogRecents.stored(rows).map { it.substance } shouldContainExactly
            listOf("Old Favourite", "Just Used")
    }

    /** Every field the sheet needs comes through, so a chip from the dock and a chip from the sheet are one value. */
    @Test
    fun `every field is carried`() {
        val stored = QuickLogRecents.stored(
            listOf(
                row(
                    substance = "Methylphenidate",
                    amount = 36.0,
                    unit = "mg",
                    route = RouteOfAdministration.ORAL,
                    productName = "Concerta",
                ),
            ),
        ).single()
        stored.substance shouldBe "Methylphenidate"
        stored.amount shouldBe 36.0
        stored.unit shouldBe "mg"
        stored.route shouldBe RouteOfAdministration.ORAL
        stored.productName shouldBe "Concerta"
    }

    /** An empty store has no chips, so the dock draws nothing rather than an empty row. */
    @Test
    fun `no rows means no chips`() {
        QuickLogRecents.stored(emptyList()) shouldBe emptyList()
    }

    /**
     * The unit is part of a chip's identity, so two rows differing only in unit are two chips.
     *
     * Asserted at this layer through the values the read produces rather than through an `identityKey` member, which
     * `LoggedDose` does not have — the identity is computed by `SubstanceIdentity.identityKey(…)` and compared against
     * the entity's **stored** column. My first version of this case named the member and compiled against nothing.
     *
     * The rule is the same one `ActiveSubstance` follows: `mg` and `mL` are not addable, so they are not one chip.
     */
    @Test
    fun `the unit is part of a chip's identity`() {
        val stored = QuickLogRecents.stored(
            listOf(
                row("Caffeine", unit = "mg", route = RouteOfAdministration.ORAL),
                row("Caffeine", unit = "mL", route = RouteOfAdministration.ORAL),
            ),
        )
        stored.size shouldBe 2
        stored.map { it.unit }.toSet() shouldBe setOf("mg", "mL")
        // And a same-unit pair is one identity's worth of rows, so the difference is the unit and not the count.
        val sameUnit = QuickLogRecents.stored(
            listOf(
                row("Caffeine", unit = "mg", amount = 100.0),
                row("Caffeine", unit = "mg", amount = 200.0),
            ),
        )
        sameUnit.size shouldBe 2
        sameUnit.map { it.unit }.toSet() shouldBe setOf("mg")
    }
}
