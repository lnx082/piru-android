package glass.kagerou.piru.data

import glass.kagerou.piru.data.RoutineOccurrenceService.EntrySnapshot
import glass.kagerou.piru.data.RoutineOccurrenceService.ItemSnapshot
import glass.kagerou.piru.data.RoutineOccurrenceService.OccurrenceSnapshot
import glass.kagerou.piru.data.RoutineOccurrenceService.Plan
import glass.kagerou.piru.data.entity.DailyDoseItemEntity
import glass.kagerou.piru.data.entity.RoutineOccurrenceEntity.State
import glass.kagerou.piru.model.DoseFrequency
import glass.kagerou.piru.model.RouteOfAdministration
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.UUID
import org.junit.jupiter.api.Test

/**
 * The occurrence reconciler's decision, as a rule rather than a query result.
 *
 * These are the cases upstream's `RoutineOccurrenceTests` pin, run here against
 * the pure [plan] — no store, no device, milliseconds. The half of the reconciler
 * that reads the database is covered by `RoutineOccurrenceServiceStoreTest` in
 * `androidTest`, because SQL behaviour is what that half is.
 *
 * The one that matters most is the first one in the matching section: a logged
 * dose has to make its slot read `logged`, because that state is the entire
 * mechanism by which "still need to log X?" stops for a slot the user has taken.
 * A reconciler that got that wrong would leave the re-ask nagging a user who had
 * already logged, which is the bug this whole service exists to prevent.
 */
class RoutineOccurrenceServiceTest {

    private val zone = ZoneId.of("UTC")

    /** A pinned day, so the nearest-slot arithmetic has a clock it can be read against. */
    private val day = Instant.parse("2026-03-10T00:00:00Z")

    private fun at(hour: Int, minute: Int = 0): Instant =
        day.plusSeconds((hour * 60L + minute) * 60L)

    // MARK: - slotKey

    @Test
    fun `a resolved uid keys the slot, so a relabeled med and dose still agree`() {
        RoutineOccurrenceService.slotKey("Concerta", "psid:mph", RouteOfAdministration.ORAL, 480) shouldBe
            RoutineOccurrenceService.slotKey("Methylphenidate XR", "psid:mph", RouteOfAdministration.ORAL, 480)
    }

    @Test
    fun `an unresolved substance falls back to the lowercased name`() {
        RoutineOccurrenceService.slotKey("Vitamin D3", null, RouteOfAdministration.ORAL, 540) shouldBe
            "vitamin d3|oral|540"
    }

    @Test
    fun `an empty uid is an unresolved uid, not an identity`() {
        RoutineOccurrenceService.slotKey("Vitamin D3", "", RouteOfAdministration.ORAL, 540) shouldBe "vitamin d3|oral|540"
    }

    @Test
    fun `the route and the slot are part of the key`() {
        val base = RoutineOccurrenceService.slotKey("Melatonin", null, RouteOfAdministration.ORAL, 540)
        base shouldBe "melatonin|oral|540"
        (RoutineOccurrenceService.slotKey("Melatonin", null, RouteOfAdministration.SUBLINGUAL, 540) == base) shouldBe false
        (RoutineOccurrenceService.slotKey("Melatonin", null, RouteOfAdministration.ORAL, 541) == base) shouldBe false
    }

    @Test
    fun `an anytime slot keys as any, and is not the slot at midnight`() {
        RoutineOccurrenceService.slotKey("Vitamin D3", null, RouteOfAdministration.ORAL, null) shouldBe "vitamin d3|oral|any"
        (RoutineOccurrenceService.slotKey("Vitamin D3", null, RouteOfAdministration.ORAL, 0) ==
            RoutineOccurrenceService.slotKey("Vitamin D3", null, RouteOfAdministration.ORAL, null)) shouldBe false
    }

    // MARK: - Materialization

    @Test
    fun `a due item with two times gets two pending occurrences`() {
        val result = RoutineOccurrenceService.plan(
            dueItems = listOf(item("Methylphenidate", listOf(8 * 60, 13 * 60))),
            occurrences = emptyList(),
            expired = emptyList(),
            entries = emptyList(),
            zone = zone,
        )

        result.inserts.map { it.slotMinutes } shouldBe listOf(8 * 60, 13 * 60)
        result.inserts.all { it.state == State.PENDING } shouldBe true
        result.deletes.shouldBeEmpty()
        result.updates.shouldBeEmpty()
    }

    @Test
    fun `a med with no set times gets a single anytime occurrence`() {
        val result = RoutineOccurrenceService.plan(
            dueItems = listOf(item("Vitamin D3", emptyList())),
            occurrences = emptyList(),
            expired = emptyList(),
            entries = emptyList(),
            zone = zone,
        )

        result.inserts.size shouldBe 1
        result.inserts.first().slotMinutes shouldBe null
    }

    @Test
    fun `an as-needed med is not due and an off-cycle item is not either`() {
        val asNeeded = DailyDoseItemEntity(
            substance = "Ibuprofen",
            amount = 200.0,
            isAsNeeded = true,
            reminderTimesJson = "",
        )
        RoutineOccurrenceService.itemSnapshot(asNeeded, day, zone) shouldBe null

        // Weekly, started three days ago: today is not on the cycle.
        val weekly = DailyDoseItemEntity(
            substance = "B12",
            amount = 1.0,
            frequencyRaw = DoseFrequency.WEEKLY.wireValue,
            startDate = Date(day.minusSeconds(3 * 86_400).toEpochMilli()),
            reminderTimesJson = "[540]",
        )
        RoutineOccurrenceService.itemSnapshot(weekly, day, zone) shouldBe null

        // And on its cycle day it is due, with its one slot.
        val onCycle = weekly.copy(startDate = Date(day.minusSeconds(7 * 86_400).toEpochMilli()))
        RoutineOccurrenceService.itemSnapshot(onCycle, day, zone)?.slots shouldBe listOf(540)
    }

    @Test
    fun `an unchanged day plans nothing to write`() {
        val result = RoutineOccurrenceService.plan(
            dueItems = listOf(item("Vitamin D3")),
            occurrences = listOf(occurrence("Vitamin D3", slotMinutes = 540, state = State.PENDING)),
            expired = emptyList(),
            entries = emptyList(),
            zone = zone,
        )

        result.isEmpty shouldBe true
    }

    // MARK: - Matching

    @Test
    fun `a logged dose satisfies its slot by name`() {
        val entryId = UUID.randomUUID()
        val result = RoutineOccurrenceService.plan(
            dueItems = listOf(item("Vitamin D3")),
            occurrences = listOf(occurrence("Vitamin D3", slotMinutes = 540, state = State.PENDING)),
            expired = emptyList(),
            entries = listOf(entry("vitamin d3", entryId, at(9))),
            zone = zone,
        )

        result.updates[1L] shouldBe Plan.Outcome(State.LOGGED, entryId)
        result.inserts.shouldBeEmpty()
    }

    @Test
    fun `identity matching prefers the uid, so a relabeled dose still logs the slot`() {
        val entryId = UUID.randomUUID()
        val result = RoutineOccurrenceService.plan(
            dueItems = listOf(item("Concerta", substanceUID = "psid:mph")),
            occurrences = listOf(
                occurrence("Concerta", slotMinutes = 540, state = State.PENDING, substanceUID = "psid:mph"),
            ),
            expired = emptyList(),
            entries = listOf(entry("Methylphenidate XR", entryId, at(9), substanceUID = "psid:mph")),
            zone = zone,
        )

        result.updates[1L]?.state shouldBe State.LOGGED
    }

    @Test
    fun `a re-pinned uid still matches by name, so the checklist agrees with the log sheet`() {
        val entryId = UUID.randomUUID()
        val result = RoutineOccurrenceService.plan(
            dueItems = listOf(item("Methylphenidate", substanceUID = "psid:41276")),
            occurrences = listOf(
                occurrence("Methylphenidate", slotMinutes = 540, state = State.PENDING, substanceUID = "psid:41276"),
            ),
            expired = emptyList(),
            // The dose was logged against the family the drug was re-pinned to.
            entries = listOf(entry("Methylphenidate", entryId, at(9), substanceUID = "psid:4158")),
            zone = zone,
        )

        result.updates[1L] shouldBe Plan.Outcome(State.LOGGED, entryId)
    }

    @Test
    fun `a route mismatch does not satisfy the slot`() {
        val result = RoutineOccurrenceService.plan(
            dueItems = listOf(item("Melatonin", route = RouteOfAdministration.ORAL)),
            occurrences = listOf(
                occurrence("Melatonin", slotMinutes = 540, state = State.PENDING, route = RouteOfAdministration.ORAL),
            ),
            expired = emptyList(),
            entries = listOf(entry("Melatonin", UUID.randomUUID(), at(9), route = RouteOfAdministration.SUBLINGUAL)),
            zone = zone,
        )

        result.updates.shouldBeEmpty()
    }

    @Test
    fun `an ad-hoc dose matching nothing claims nothing`() {
        val result = RoutineOccurrenceService.plan(
            dueItems = listOf(item("Vitamin D3")),
            occurrences = listOf(occurrence("Vitamin D3", slotMinutes = 540, state = State.PENDING)),
            expired = emptyList(),
            entries = listOf(entry("Caffeine", UUID.randomUUID(), at(9))),
            zone = zone,
        )

        result.updates.shouldBeEmpty()
    }

    @Test
    fun `one entry claims the nearest slot and leaves the other pending`() {
        val entryId = UUID.randomUUID()
        val result = RoutineOccurrenceService.plan(
            dueItems = listOf(item("Methylphenidate", listOf(8 * 60, 22 * 60))),
            occurrences = listOf(
                occurrence("Methylphenidate", slotMinutes = 8 * 60, state = State.PENDING),
                occurrence("Methylphenidate", slotMinutes = 22 * 60, state = State.PENDING, rowId = 2),
            ),
            expired = emptyList(),
            entries = listOf(entry("Methylphenidate", entryId, at(8, 10))),
            zone = zone,
        )

        // 08:10 is eight minutes from the 08:00 slot and 13:50 from the 22:00 one.
        result.updates[1L] shouldBe Plan.Outcome(State.LOGGED, entryId)
        result.updates.containsKey(2L) shouldBe false
    }

    @Test
    fun `a timed slot is claimed before an anytime slot on the same med-day`() {
        val entryId = UUID.randomUUID()
        val result = RoutineOccurrenceService.plan(
            dueItems = listOf(
                item("Zinc", listOf(8 * 60)),
                item("Zinc", emptyList()),
            ),
            occurrences = listOf(
                occurrence("Zinc", slotMinutes = 8 * 60, state = State.PENDING),
                occurrence("Zinc", slotMinutes = null, state = State.PENDING, rowId = 2),
            ),
            expired = emptyList(),
            entries = listOf(entry("Zinc", entryId, at(8, 5))),
            zone = zone,
        )

        result.updates[1L] shouldBe Plan.Outcome(State.LOGGED, entryId)
        result.updates.containsKey(2L) shouldBe false
    }

    @Test
    fun `two entries fill two slots in timestamp order`() {
        val morning = UUID.randomUUID()
        val night = UUID.randomUUID()
        val result = RoutineOccurrenceService.plan(
            dueItems = listOf(item("Methylphenidate", listOf(8 * 60, 22 * 60))),
            occurrences = listOf(
                occurrence("Methylphenidate", slotMinutes = 8 * 60, state = State.PENDING),
                occurrence("Methylphenidate", slotMinutes = 22 * 60, state = State.PENDING, rowId = 2),
            ),
            expired = emptyList(),
            // Deliberately out of order: the plan sorts by timestamp itself.
            entries = listOf(
                entry("Methylphenidate", night, at(21, 45)),
                entry("Methylphenidate", morning, at(8, 10)),
            ),
            zone = zone,
        )

        result.updates[1L] shouldBe Plan.Outcome(State.LOGGED, morning)
        result.updates[2L] shouldBe Plan.Outcome(State.LOGGED, night)
    }

    // MARK: - Re-derivation

    @Test
    fun `deleting the satisfying dose returns the slot to pending`() {
        val result = RoutineOccurrenceService.plan(
            dueItems = listOf(item("Vitamin D3")),
            occurrences = listOf(
                occurrence(
                    "Vitamin D3",
                    slotMinutes = 540,
                    state = State.LOGGED,
                    satisfyingEntryID = UUID.randomUUID(),
                ),
            ),
            expired = emptyList(),
            // The dose is gone from the store, so it is not here either.
            entries = emptyList(),
            zone = zone,
        )

        result.updates[1L] shouldBe Plan.Outcome(State.PENDING, null)
    }

    @Test
    fun `removing a reminder time drops its pending occurrence and keeps the other`() {
        val result = RoutineOccurrenceService.plan(
            dueItems = listOf(item("Methylphenidate", listOf(8 * 60))),
            occurrences = listOf(
                occurrence("Methylphenidate", slotMinutes = 8 * 60, state = State.PENDING),
                occurrence("Methylphenidate", slotMinutes = 13 * 60, state = State.PENDING, rowId = 2),
            ),
            expired = emptyList(),
            entries = emptyList(),
            zone = zone,
        )

        result.deletes shouldContainExactly listOf(2L)
        result.inserts.shouldBeEmpty()
    }

    @Test
    fun `a settled row survives the reminder time that produced it going away`() {
        // The 13:00 reminder was removed, but the dose that satisfied it is the
        // day's history: only a *pending* row is dropped for no longer being due,
        // so the record of what happened is not erased by editing the schedule.
        val entryId = UUID.randomUUID()
        val result = RoutineOccurrenceService.plan(
            dueItems = listOf(item("Methylphenidate", listOf(8 * 60))),
            occurrences = listOf(
                occurrence("Methylphenidate", slotMinutes = 13 * 60, state = State.LOGGED, satisfyingEntryID = entryId),
            ),
            expired = emptyList(),
            entries = listOf(entry("Methylphenidate", entryId, at(13, 5))),
            zone = zone,
        )

        result.deletes.shouldBeEmpty()
        result.updates.shouldBeEmpty()
    }

    @Test
    fun `skipped is sticky through a reconcile and through a later dose`() {
        val entryId = UUID.randomUUID()
        val result = RoutineOccurrenceService.plan(
            dueItems = listOf(item("Vitamin D3")),
            occurrences = listOf(occurrence("Vitamin D3", slotMinutes = 540, state = State.SKIPPED)),
            expired = emptyList(),
            entries = listOf(entry("Vitamin D3", entryId, at(9))),
            zone = zone,
        )

        // The dose does not reclaim a slot the user said to stop asking about.
        result.updates.shouldBeEmpty()
        result.inserts.shouldBeEmpty()
        result.deletes.shouldBeEmpty()
    }

    @Test
    fun `past-day pendings expire and settled history does not`() {
        val result = RoutineOccurrenceService.plan(
            dueItems = emptyList(),
            occurrences = emptyList(),
            expired = listOf(7L, 8L),
            entries = emptyList(),
            zone = zone,
        )

        result.expire shouldContainExactly listOf(7L, 8L)
        result.isEmpty shouldBe false
    }

    @Test
    fun `a due item whose only occurrence is logged is not re-inserted`() {
        val entryId = UUID.randomUUID()
        val result = RoutineOccurrenceService.plan(
            dueItems = listOf(item("Vitamin D3")),
            occurrences = listOf(
                occurrence("Vitamin D3", slotMinutes = 540, state = State.LOGGED, satisfyingEntryID = entryId),
            ),
            expired = emptyList(),
            entries = listOf(entry("Vitamin D3", entryId, at(9))),
            zone = zone,
        )

        result.isEmpty shouldBe true
    }

    // MARK: - Builders

    /**
     * One due med's slots. The default is a single 09:00 slot, which is the case
     * almost every test is about; `times = emptyList()` is the med with no set
     * times, whose one slot is the `null` "anytime" one.
     */
    private fun item(
        substance: String,
        times: List<Int> = listOf(9 * 60),
        substanceUID: String? = null,
        route: RouteOfAdministration = RouteOfAdministration.ORAL,
    ): ItemSnapshot = ItemSnapshot(
        substance = substance,
        substanceUID = substanceUID,
        route = route,
        slots = if (times.isEmpty()) listOf(null) else times.map { it as Int? },
    )

    private fun occurrence(
        substance: String,
        slotMinutes: Int?,
        state: State,
        rowId: Long = 1L,
        substanceUID: String? = null,
        route: RouteOfAdministration = RouteOfAdministration.ORAL,
        satisfyingEntryID: UUID? = null,
    ): OccurrenceSnapshot = OccurrenceSnapshot(
        rowId = rowId,
        substance = substance,
        substanceUID = substanceUID,
        route = route,
        slotMinutes = slotMinutes,
        state = state,
        satisfyingEntryID = satisfyingEntryID,
    )

    private fun entry(
        substance: String,
        id: UUID,
        timestamp: Instant,
        substanceUID: String? = null,
        route: RouteOfAdministration = RouteOfAdministration.ORAL,
    ): EntrySnapshot = EntrySnapshot(
        id = id,
        substance = substance,
        substanceUID = substanceUID,
        route = route,
        timestamp = timestamp,
    )
}
