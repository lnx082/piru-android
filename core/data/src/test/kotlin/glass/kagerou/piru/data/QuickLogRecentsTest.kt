package glass.kagerou.piru.data

import glass.kagerou.piru.data.entity.QuickLogDoseEntity
import glass.kagerou.piru.model.RouteOfAdministration
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.Date
import org.junit.jupiter.api.Test

/**
 * The quick log's dock rules.
 *
 * ## Why this needed a service at all
 * `quick_log_doses` has had a table, a DAO, an export and an import since the port began and **nothing ever wrote a
 * row** — so the dock was whatever a seeded file contained, and logging a dose left no trace. BUG #44's shape once
 * more: the persistence complete and no producer.
 *
 * ## The rules, each of which is a decision
 * - **Grouped by identity, not by name.** A Concerta chip and a Ritalin chip share the substance name and must not
 *   merge: a user who logged one did not ask for the other.
 * - **Logging a dose un-suppresses it**, because logging it again is the clearest statement that it belongs.
 * - **Order depends on one preference**: floating moves a used chip to the front of its group, fixed leaves it.
 * - **The cap evicts the least recently used**, not the last position — position is what the user arranged, and
 *   staleness is what should go.
 */
class QuickLogRecentsTest {

    private val t0 = Date(1_000_000L)

    private fun chip(
        rowId: Long,
        substance: String = "Caffeine",
        route: RouteOfAdministration = RouteOfAdministration.ORAL,
        amount: Double = 80.0,
        unit: String = "mg",
        sortOrder: Double = 0.0,
        lastUsedAt: Date = t0,
        substanceUID: String? = null,
        releaseForm: String? = null,
    ) = QuickLogDoseEntity(
        rowId = rowId,
        substance = substance,
        route = route,
        amount = amount,
        unit = unit,
        sortOrder = sortOrder,
        lastUsedAt = lastUsedAt,
        substanceUID = substanceUID,
        releaseForm = releaseForm,
    )

    private fun dose(
        substance: String = "Caffeine",
        route: RouteOfAdministration = RouteOfAdministration.ORAL,
        amount: Double = 80.0,
        unit: String = "mg",
        substanceUID: String? = null,
        releaseForm: String? = null,
    ) = QuickLogRecents.LoggedDose(
        substance = substance,
        route = route,
        amount = amount,
        unit = unit,
        substanceUID = substanceUID,
        releaseForm = releaseForm,
    )

    // MARK: - Adding

    /** A new measurement becomes a chip, marked used now. */
    @Test
    fun `a logged dose becomes a chip`() {
        // `now` is passed rather than defaulted, so the assertion is about the fold and not about how long the test
        // took to run — a `Date()` default would make this pass or fail depending on the clock's resolution.
        val result = QuickLogRecents.fold(
            listOf(dose()), emptyList(), fixedOrder = false, suppressed = emptySet(), now = t0,
        )
        result.rows.size shouldBe 1
        result.rows.single().substance shouldBe "Caffeine"
        result.rows.single().amount shouldBe 80.0
        result.rows.single().lastUsedAt shouldBe t0
    }

    /** An empty batch changes nothing, not even the suppression list. */
    @Test
    fun `an empty batch changes nothing`() {
        val existing = listOf(chip(1))
        val result = QuickLogRecents.fold(emptyList(), existing, fixedOrder = false, suppressed = setOf("x"))
        result.rows shouldContainExactly existing
        result.suppressed shouldBe setOf("x")
    }

    /**
     * The same measurement twice is one chip, refreshed.
     *
     * The property that stops a daily coffee from filling the dock with identical chips.
     */
    @Test
    fun `the same measurement does not duplicate`() {
        val first = QuickLogRecents.fold(listOf(dose()), emptyList(), fixedOrder = false, suppressed = emptySet())
        val later = Date(2_000_000L)
        val second = QuickLogRecents.fold(
            listOf(dose()), first.rows, fixedOrder = false, suppressed = first.suppressed, now = later,
        )
        second.rows.size shouldBe 1
        second.rows.single().lastUsedAt shouldBe later
    }

    /** A different amount of the same substance is a **separate** chip, because it is a different measurement. */
    @Test
    fun `a different amount is its own chip`() {
        val first = QuickLogRecents.fold(listOf(dose(amount = 80.0)), emptyList(), false, emptySet())
        val second = QuickLogRecents.fold(listOf(dose(amount = 160.0)), first.rows, false, first.suppressed)
        second.rows.size shouldBe 2
        second.rows.map { it.amount }.toSet() shouldBe setOf(80.0, 160.0)
    }

    /** A different route is its own chip too — the same dose taken two ways is two habits. */
    @Test
    fun `a different route is its own chip`() {
        val first = QuickLogRecents.fold(listOf(dose()), emptyList(), false, emptySet())
        val second = QuickLogRecents.fold(
            listOf(dose(route = RouteOfAdministration.INSUFFLATION)), first.rows, false, first.suppressed,
        )
        second.rows.size shouldBe 2
    }

    // MARK: - Identity, not name

    /**
     * Two products of one substance stay distinct chips.
     *
     * The rule the identity key exists for: Concerta and Ritalin are both "Methylphenidate", and a user who logged
     * one did not ask for the other. Folded on the name they would merge, and the dock would offer the wrong product
     * under the right word.
     */
    @Test
    fun `two release forms of one name stay apart`() {
        val extended = dose(substance = "Methylphenidate", releaseForm = "XR")
        val immediate = dose(substance = "Methylphenidate", releaseForm = "IR")
        val first = QuickLogRecents.fold(listOf(extended), emptyList(), false, emptySet())
        val second = QuickLogRecents.fold(listOf(immediate), first.rows, false, first.suppressed)
        second.rows.size shouldBe 2
        second.rows.map { it.releaseForm }.toSet() shouldBe setOf("XR", "IR")
    }

    // MARK: - Ordering

    /**
     * With a floating order, a used chip moves to the **front of its group**.
     *
     * The dock's whole point: what you reached for last is where your thumb already is.
     */
    @Test
    fun `a used chip floats to the front of its group`() {
        val existing = listOf(chip(1, amount = 40.0, sortOrder = 0.0), chip(2, amount = 80.0, sortOrder = 1.0))
        val result = QuickLogRecents.fold(listOf(dose(amount = 40.0)), existing, fixedOrder = false, suppressed = emptySet())
        val used = result.rows.first { it.rowId == 1L }
        val other = result.rows.first { it.rowId == 2L }
        (used.sortOrder < other.sortOrder) shouldBe true
    }

    /**
     * With a fixed order it stays put.
     *
     * The other setting, and the reason it exists: a user who arranged their dock does not want it rearranging itself
     * under them.
     */
    @Test
    fun `a used chip stays put when the order is fixed`() {
        val existing = listOf(chip(1, amount = 40.0, sortOrder = 0.0), chip(2, amount = 80.0, sortOrder = 1.0))
        val result = QuickLogRecents.fold(listOf(dose(amount = 40.0)), existing, fixedOrder = true, suppressed = emptySet())
        result.rows.first { it.rowId == 1L }.sortOrder shouldBe 0.0
        result.rows.first { it.rowId == 2L }.sortOrder shouldBe 1.0
    }

    /** A new chip appends at the end of its group with a fixed order, and leads it without. */
    @Test
    fun `a new chip goes to the end with a fixed order and the front without`() {
        val existing = listOf(chip(1, amount = 40.0, sortOrder = 0.0))
        val fixed = QuickLogRecents.fold(listOf(dose(amount = 80.0)), existing, fixedOrder = true, suppressed = emptySet())
        (fixed.rows.first { it.amount == 80.0 }.sortOrder > 0.0) shouldBe true

        val floating = QuickLogRecents.fold(listOf(dose(amount = 80.0)), existing, fixedOrder = false, suppressed = emptySet())
        (floating.rows.first { it.amount == 80.0 }.sortOrder < 0.0) shouldBe true
    }

    /** A chip from another group does not move a chip in this one — the group is the scope of the ordering. */
    @Test
    fun `ordering is scoped to the group`() {
        val existing = listOf(
            chip(1, substance = "Caffeine", sortOrder = 0.0),
            chip(2, substance = "Ketamine", sortOrder = 0.0),
        )
        val result = QuickLogRecents.fold(
            listOf(dose(substance = "Caffeine", amount = 200.0)), existing, fixedOrder = false, suppressed = emptySet(),
        )
        // Ketamine's chip is untouched.
        result.rows.first { it.rowId == 2L }.sortOrder shouldBe 0.0
    }

    // MARK: - Suppression

    /**
     * Logging a dose clears its suppression.
     *
     * "Remove from recents" is a statement about a chip the user had stopped wanting; using it again contradicts
     * that, and a permanent removal would make the dock unable to learn.
     */
    @Test
    fun `logging a dose clears its suppression`() {
        val identity = SubstanceIdentity.identityKey(
            substanceUID = null, substance = "Caffeine", isomer = null, releaseForm = null, saltForm = null,
        )
        val result = QuickLogRecents.fold(
            listOf(dose()), emptyList(), fixedOrder = false, suppressed = setOf(identity),
        )
        result.suppressed shouldBe emptySet()
    }

    /** A dose that is *not* logged leaves the suppression list alone. */
    @Test
    fun `an unrelated dose leaves other suppressions alone`() {
        val result = QuickLogRecents.fold(
            listOf(dose(substance = "Caffeine")), emptyList(), fixedOrder = false, suppressed = setOf("someone-else"),
        )
        result.suppressed shouldBe setOf("someone-else")
    }

    // MARK: - The cap

    /**
     * A group at the cap loses its **least recently used** chip.
     *
     * Eviction by staleness rather than by position: position is what the user arranged. A cap that dropped the last
     * row would throw away the chip they put there deliberately.
     */
    @Test
    fun `the cap evicts the least recently used`() {
        val cap = QuickLogDoseEntity.PER_GROUP_LIMIT
        val existing = (1..cap).map { index ->
            chip(
                rowId = index.toLong(),
                amount = index * 10.0,
                sortOrder = index.toDouble(),
                lastUsedAt = Date(t0.time + index * 1000L),
            )
        }
        // The oldest is rowId 1; log a brand-new measurement so the group exceeds the cap by one.
        val result = QuickLogRecents.fold(
            listOf(dose(amount = 9999.0)), existing, fixedOrder = true, suppressed = emptySet(),
        )
        val amounts = result.rows.map { it.amount }
        amounts.size shouldBe cap
        // The oldest went, and the newest arrival plus the recent survivors stayed.
        (10.0 in amounts) shouldBe false
        (9999.0 in amounts) shouldBe true
        (cap * 10.0 in amounts) shouldBe true
    }

    /** A group under the cap loses nothing, which is what makes the cap a bound rather than a target. */
    @Test
    fun `a group under the cap is untouched`() {
        val existing = listOf(chip(1, lastUsedAt = t0), chip(2, amount = 40.0, lastUsedAt = t0))
        val result = QuickLogRecents.fold(
            listOf(dose(amount = 160.0)), existing, fixedOrder = true, suppressed = emptySet(),
        )
        result.rows.size shouldBe 3
    }

    /** Each group gets its own cap, so two substances cannot crowd each other out. */
    @Test
    fun `the cap is per group`() {
        val cap = QuickLogDoseEntity.PER_GROUP_LIMIT
        val caffeine = (1..cap).map { chip(it.toLong(), amount = it * 10.0, sortOrder = it.toDouble()) }
        val ketamine = (1..cap).map {
            chip((100 + it).toLong(), substance = "Ketamine", amount = it * 10.0, sortOrder = it.toDouble())
        }
        val result = QuickLogRecents.fold(
            listOf(dose(amount = 9999.0)), caffeine + ketamine, fixedOrder = true, suppressed = emptySet(),
        )
        // Caffeine's group is at the cap after the new arrival; Ketamine still has all of its own.
        result.rows.count { it.substance == "Ketamine" } shouldBe cap
        result.rows.count { it.substance == "Caffeine" } shouldBe cap
    }

    // MARK: - Seeding

    /**
     * Seeding takes the most recent distinct measurements, capped per group.
     *
     * So a new user is not looking at an empty dock while their journal has a hundred rows in it — and a repeated
     * daily dose becomes **one** chip rather than thirty.
     */
    @Test
    fun `seeding takes recent distinct measurements`() {
        val base = 1_000_000L
        val history = (0 until 20).map { index ->
            glass.kagerou.piru.data.entity.DoseEntryEntity(
                timestamp = Date(base + index * 1000L),
                substance = "Caffeine",
                amount = if (index % 2 == 0) 80.0 else 160.0,
                unit = "mg",
                route = RouteOfAdministration.ORAL,
            )
        }
        val seeded = QuickLogRecents.seed(history)
        // Two distinct measurements, however many times each was logged.
        seeded.size shouldBe 2
        seeded.map { it.amount }.toSet() shouldBe setOf(80.0, 160.0)
    }

    /** An empty history seeds nothing, rather than an empty chip. */
    @Test
    fun `an empty history seeds nothing`() {
        QuickLogRecents.seed(emptyList()) shouldBe emptyList()
    }

    /**
     * Seeding respects the per-group cap.
     *
     * A user with twenty distinct amounts of one substance gets the cap's worth of the most recent, not all twenty.
     */
    @Test
    fun `seeding respects the cap`() {
        val history = (1..20).map { index ->
            glass.kagerou.piru.data.entity.DoseEntryEntity(
                timestamp = Date(1_000_000L + index * 1000L),
                substance = "Caffeine",
                amount = index * 5.0,
                unit = "mg",
                route = RouteOfAdministration.ORAL,
            )
        }
        QuickLogRecents.seed(history).size shouldBe QuickLogDoseEntity.PER_GROUP_LIMIT
        // And the most recent are the ones kept: the last row is amount 100.
        QuickLogRecents.seed(history).map { it.amount } shouldContainExactly listOf(100.0, 95.0, 90.0, 85.0, 80.0, 75.0, 70.0, 65.0)
    }
}
