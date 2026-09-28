package glass.kagerou.piru.data

import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.data.entity.InventoryItemEntity
import glass.kagerou.piru.engine.EmptySubstanceCatalog
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import org.junit.jupiter.api.Test

/**
 * The stock replay, pinned.
 *
 * These are the rules a user's number depends on, and every one of them is a
 * choice that could reasonably have gone the other way — so each is asserted with
 * the reason it went this way in the name.
 */
class InventoryMathTest {

    private val catalog = EmptySubstanceCatalog
    private val zone = ZoneId.of("UTC")
    private val t0: Instant = Instant.parse("2026-01-01T12:00:00Z")

    private fun item(
        unit: String = "mg",
        strength: Double? = null,
        events: List<ManualEvent> = emptyList(),
        trackingStart: Instant = Instant.EPOCH,
        quantity: Double = 0.0,
        doseSize: Double? = null,
    ) = InventoryItemEntity(
        substance = "Caffeine",
        unit = unit,
        unitStrengthMG = strength,
        restocksJson = ManualEvents.encode(events),
        trackingStart = trackingStart,
        currentQuantity = quantity,
        doseSize = doseSize,
        createdAt = t0,
    )

    private fun dose(amount: Double, unit: String = "mg", at: Instant = t0, salt: String? = null) =
        // `create` is the factory that enforces the two amount invariants; the salt
        // form is a plain field it does not take, so it is set afterwards rather
        // than duplicating those invariants here.
        DoseEntryEntity.create(
            substance = "Caffeine",
            amount = amount,
            unit = unit,
            timestamp = Date.from(at),
        ).copy(saltForm = salt)

    private fun restock(amount: Double, at: Instant = t0) =
        ManualEvent(kind = ManualEvent.Kind.RESTOCK, amount = amount, date = at)

    private fun initial(amount: Double, at: Instant = t0) =
        ManualEvent(kind = ManualEvent.Kind.INITIAL, amount = amount, date = at)

    private fun adjustment(amount: Double, at: Instant = t0) =
        ManualEvent(kind = ManualEvent.Kind.ADJUSTMENT, amount = amount, date = at)

    private fun snapshots(vararg pairs: Pair<Double, Instant>) =
        pairs.map { (amount, at) -> InventoryMath.DoseSnapshot(amount, "mg", at) }

    // MARK: - Replay

    @Test
    fun `a restock less the doses is the balance`() {
        val total = InventoryMath.replayQuantity(
            unit = "mg",
            unitStrengthMG = null,
            events = listOf(restock(1000.0)),
            doses = snapshots(100.0 to t0.plusSeconds(3600), 150.0 to t0.plusSeconds(7200)),
        )
        total shouldBe 750.0
    }

    @Test
    fun `a dose larger than the stock floors at zero rather than going negative`() {
        // 30 on hand, 50 logged. The log is a record of what happened, so the
        // balance stops at zero — going to -20 would make the next restock read
        // as 20 smaller than it was.
        val total = InventoryMath.replayQuantity(
            unit = "mg",
            unitStrengthMG = null,
            events = listOf(initial(30.0)),
            doses = snapshots(50.0 to t0.plusSeconds(60)),
        )
        total shouldBe 0.0
    }

    @Test
    fun `an adjustment floors but a restock does not`() {
        // The asymmetry is the point: a correction is the user's best guess and
        // may pass through negative, a dose is a fact and may not.
        InventoryMath.replayQuantity(
            unit = "mg",
            unitStrengthMG = null,
            events = listOf(initial(10.0), adjustment(-40.0)),
            doses = emptyList(),
        ) shouldBe 0.0

        InventoryMath.replayQuantity(
            unit = "mg",
            unitStrengthMG = null,
            events = listOf(initial(10.0), restock(-40.0), restock(100.0)),
            doses = emptyList(),
        ) shouldBe 70.0
    }

    @Test
    fun `a dose with no conversion path is skipped, not fatal`() {
        // 5 mL against a milligram stock with no strength: there is no answer, and
        // refusing to produce a total at all would take the whole readout down
        // over one unconvertible row.
        val total = InventoryMath.replayQuantity(
            unit = "mg",
            unitStrengthMG = null,
            events = listOf(initial(500.0)),
            doses = listOf(InventoryMath.DoseSnapshot(5.0, "mL", t0.plusSeconds(60))),
        )
        total shouldBe 500.0
    }

    @Test
    fun `ticks at the same instant apply in a total order`() {
        // Swift's sort is not stable, so upstream left this undefined. Running the
        // same log twice must give the same number here.
        val events = listOf(initial(100.0, at = t0), adjustment(-250.0, at = t0))
        val first = InventoryMath.replayQuantity("mg", null, events, emptyList())
        val second = InventoryMath.replayQuantity("mg", null, events, emptyList())
        first shouldBe second
    }

    @Test
    fun `at one instant the addition lands before the correction`() {
        // 100 on the shelf, then the user says the shelf is 250 short. The answer
        // is zero — the correction is applied to the count that exists. Ordering
        // it the other way floors the correction against an empty balance and
        // then adds the count, which silently discards what the user just said.
        InventoryMath.replayQuantity(
            unit = "mg",
            unitStrengthMG = null,
            events = listOf(initial(100.0, at = t0), adjustment(-250.0, at = t0)),
            doses = emptyList(),
        ) shouldBe 0.0
    }

    @Test
    fun `at one instant a restock lands before a dose`() {
        // The same rule where it costs a dose instead of a correction: 50 mg
        // logged against an empty shelf, restocked 100 the same millisecond.
        // Applying the dose first clamps it to zero and reports 100 — the dose
        // vanishes. Applying the restock first reports 50, which is what is
        // actually left.
        InventoryMath.replayQuantity(
            unit = "mg",
            unitStrengthMG = null,
            events = listOf(restock(100.0, at = t0)),
            doses = snapshots(50.0 to t0),
        ) shouldBe 50.0
    }

    // MARK: - Units

    @Test
    fun `a count converts through the strength of one unit`() {
        // 1000 mg on hand, 10 mg per tablet, 3 tablets logged.
        val total = InventoryMath.replayQuantity(
            unit = "tabs",
            unitStrengthMG = 10.0,
            events = listOf(initial(100.0)),
            doses = listOf(InventoryMath.DoseSnapshot(30.0, "mg", t0.plusSeconds(60))),
        )
        total shouldBe 97.0
    }

    @Test
    fun `a count conversion without a strength has no answer`() {
        InventoryMath.convert(30.0, "mg", "tabs", unitStrengthMG = null).shouldBeNull()
        InventoryMath.convert(30.0, "mg", "tabs", unitStrengthMG = 0.0).shouldBeNull()
    }

    @Test
    fun `count units are matched case and space insensitively`() {
        InventoryMath.isCountUnit("Tabs") shouldBe true
        InventoryMath.isCountUnit(" caps ") shouldBe true
        InventoryMath.isCountUnit("mg") shouldBe false
    }

    // MARK: - Which doses count

    @Test
    fun `a dose before tracking started is not consumption`() {
        val tracked = item(trackingStart = t0)
        val buckets = InventoryMath.bucketDoses(
            listOf(dose(100.0, at = t0.minusSeconds(86_400)), dose(100.0, at = t0.plusSeconds(60))),
            catalog,
        )
        InventoryMath.dosesFor(tracked, buckets, catalog).size shouldBe 1
    }

    @Test
    fun `a different salt form is a different supply`() {
        val freeBase = item().copy(saltForm = null)
        val buckets = InventoryMath.bucketDoses(
            listOf(dose(100.0, salt = "Citrate"), dose(100.0, salt = null)),
            catalog,
        )
        InventoryMath.dosesFor(freeBase, buckets, catalog).size shouldBe 1
    }

    @Test
    fun `an unknown dose subtracts nothing`() {
        val tracked = item()
        val unknown = DoseEntryEntity.create(
            substance = "Caffeine",
            amount = 0.0,
            timestamp = Date.from(t0.plusSeconds(60)),
            isUnknownDose = true,
        )
        InventoryMath.dosesFor(tracked, InventoryMath.bucketDoses(listOf(unknown), catalog), catalog) shouldBe emptyList()
    }

    // MARK: - Run-out

    @Test
    fun `a run-out estimate needs five distinct days of dosing`() {
        // Four days is a rumour, not a rate.
        val fourDays = (0 until 4).flatMap { day ->
            listOf(dose(100.0, at = t0.minusSeconds(day * 86_400L)))
        }
        InventoryMath.runOut(
            item(quantity = 1000.0),
            InventoryMath.bucketDoses(fourDays, catalog),
            catalog,
            now = t0,
            zone = zone,
        ).shouldBeNull()

        val fiveDays = (0 until 5).map { day -> dose(100.0, at = t0.minusSeconds(day * 86_400L)) }
        val runOut = InventoryMath.runOut(
            item(quantity = 1000.0),
            InventoryMath.bucketDoses(fiveDays, catalog),
            catalog,
            now = t0,
            zone = zone,
        )
        // Divided by seven days, not by the five that had a dose — so an
        // occasional user's supply reads as lasting longer, not shorter.
        runOut?.dailyAvg shouldBe 500.0 / 7.0
        runOut?.daysLeft shouldBe 1000.0 / (500.0 / 7.0)
    }

    @Test
    fun `doses left rounds down because a partial dose is not a dose`() {
        val stocked = item(quantity = 25.0, doseSize = 10.0)
        InventoryMath.dosesLeft(stocked, catalog) shouldBe 2
    }

    @Test
    fun `an item with no dose size has no doses-left readout`() {
        InventoryMath.dosesLeft(item(quantity = 25.0), catalog).shouldBeNull()
    }
}
