package glass.kagerou.piru.engine

import glass.kagerou.piru.model.SubstanceCategory
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import org.junit.jupiter.api.Test

/**
 * The check-in offsets: what a valid time is, and how a session's worth of them
 * is derived from the doses that were actually taken.
 *
 * Ported from the contracts of `Shared/CheckInOffsets.swift` and
 * `Piru/Utilities/CheckInLadder.swift`. Every offset here is a whole number of
 * minutes after an anchor, so no case needs a time zone — the one piece of the
 * ladder that does need the session model, `suggestedOffsets(for:)`, is covered
 * through its store-free core.
 */
class CheckInPlanTest {

    private val anchor: Instant = Instant.ofEpochSecond(1_700_000_000)

    private fun state(
        doseTimestamp: Instant = anchor,
        onsetEnd: Double = 30.0,
        comeupEnd: Double = 60.0,
        peakEnd: Double = 180.0,
        offsetEnd: Double = 300.0,
        total: Double = 300.0,
    ) = ActiveSubstanceState(
        substanceName = "Testine",
        tint = TEST_TINT,
        doseTimestamp = doseTimestamp,
        amount = 100.0,
        unit = "mg",
        route = "Oral",
        onsetEndMinutes = onsetEnd,
        comeupEndMinutes = comeupEnd,
        peakEndMinutes = peakEnd,
        offsetEndMinutes = offsetEnd,
        afterglowEndMinutes = null,
        totalMinutes = total,
    )

    // MARK: - CheckInOffsets

    @Test
    fun `normalized is deduped, in range and ascending`() {
        CheckInOffsets.normalized(listOf(10, 5, 5, 1441, 3, 1440)) shouldBe listOf(5, 10, 1440)
        CheckInOffsets.normalized(emptyList()) shouldBe emptyList()
    }

    @Test
    fun `normalized keeps the earliest times when the list is over the cap`() {
        val thirteen = (1..13).map { it * 5 }
        val kept = CheckInOffsets.normalized(thirteen)
        kept shouldBe (1..12).map { it * 5 }
        kept.last() shouldBe 60
    }

    @Test
    fun `canAdd refuses out-of-range, duplicate and full lists`() {
        CheckInOffsets.canAdd(5, emptyList()) shouldBe true
        CheckInOffsets.canAdd(1440, emptyList()) shouldBe true
        CheckInOffsets.canAdd(4, emptyList()) shouldBe false
        CheckInOffsets.canAdd(1441, emptyList()) shouldBe false
        CheckInOffsets.canAdd(30, listOf(5, 30)) shouldBe false

        val full = (1..CheckInOffsets.MAXIMUM_COUNT).map { it * 5 }
        CheckInOffsets.canAdd(900, full) shouldBe false
        CheckInOffsets.canAdd(900, full.drop(1)) shouldBe true
    }

    @Test
    fun `label reads as an interval from the dose`() {
        CheckInOffsets.label(5) shouldBe "+5m"
        CheckInOffsets.label(45) shouldBe "+45m"
        CheckInOffsets.label(60) shouldBe "+1h"
        CheckInOffsets.label(90) shouldBe "+1h 30m"
        CheckInOffsets.label(150) shouldBe "+2h 30m"
        CheckInOffsets.label(1440) shouldBe "+24h"
    }

    // MARK: - Depth

    @Test
    fun `depth follows the substance class`() {
        listOf(
            SubstanceCategory.PSYCHEDELIC,
            SubstanceCategory.DISSOCIATIVE,
            SubstanceCategory.DYSDELIC,
            SubstanceCategory.DELIRIANT,
            SubstanceCategory.EMPATHOGEN,
        ).forEach { CheckInLadder.Depth.of(it) shouldBe CheckInLadder.Depth.Wide }

        listOf(
            SubstanceCategory.OPIOID,
            SubstanceCategory.BENZODIAZEPINE,
            SubstanceCategory.DEPRESSANT,
            SubstanceCategory.CANNABINOID,
            SubstanceCategory.GABAPENTINOID,
            SubstanceCategory.ANALGESIC,
            SubstanceCategory.OREXIN_ANTAGONIST,
            SubstanceCategory.ANTIHISTAMINE,
        ).forEach { CheckInLadder.Depth.of(it) shouldBe CheckInLadder.Depth.Paced }

        // A stimulant is neither: its question is "is it working", not a phase
        // boundary tour, so it gets the light ladder like everything unlisted.
        listOf(
            SubstanceCategory.STIMULANT,
            SubstanceCategory.NOOTROPIC,
            SubstanceCategory.SUPPLEMENT,
            SubstanceCategory.OTHER,
        ).forEach { CheckInLadder.Depth.of(it) shouldBe CheckInLadder.Depth.Light }
    }

    // MARK: - Moments

    @Test
    fun `wide reads every boundary and every phase's middle`() {
        CheckInLadder.moments(state(), CheckInLadder.Depth.Wide) shouldBe
            listOf(30.0, 45.0, 60.0, 120.0, 180.0, 240.0, 300.0)
    }

    @Test
    fun `paced reads the four moments a recreational dose changes character`() {
        CheckInLadder.moments(state(), CheckInLadder.Depth.Paced) shouldBe listOf(60.0, 120.0, 180.0, 300.0)
    }

    @Test
    fun `light reads the middle of the plateau and where it turns`() {
        CheckInLadder.moments(state(), CheckInLadder.Depth.Light) shouldBe listOf(120.0, 180.0)
    }

    @Test
    fun `the last moment is the later of the offset end and the stated total`() {
        // A profile whose offset phase ends before its own total would otherwise
        // never be asked about the tail it says it has.
        val short = state(onsetEnd = 10.0, comeupEnd = 20.0, peakEnd = 40.0, offsetEnd = 100.0, total = 120.0)
        CheckInLadder.moments(short, CheckInLadder.Depth.Wide).last() shouldBe 120.0
        CheckInLadder.moments(short, CheckInLadder.Depth.Paced).last() shouldBe 120.0

        val long = state(offsetEnd = 400.0, total = 300.0)
        CheckInLadder.moments(long, CheckInLadder.Depth.Light) shouldBe listOf(120.0, 180.0)
        CheckInLadder.moments(long, CheckInLadder.Depth.Wide).last() shouldBe 400.0
    }

    // MARK: - Granularity

    @Test
    fun `granularity is finer for a short dose than for a long one`() {
        CheckInLadder.granularity(30.0) shouldBe 5
        CheckInLadder.granularity(89.0) shouldBe 5
        CheckInLadder.granularity(90.0) shouldBe 15
        CheckInLadder.granularity(359.0) shouldBe 15
        CheckInLadder.granularity(360.0) shouldBe 30
        CheckInLadder.granularity(720.0) shouldBe 30
    }

    // MARK: - Offsets

    @Test
    fun `offsets are the moments rounded to the dose's granularity`() {
        // A 300-minute dose rounds to the quarter hour, so the come-up midpoint
        // of 45 survives — at a half-hour step it would have moved to 60.
        CheckInLadder.offsets(state(), CheckInLadder.Depth.Wide, anchor) shouldBe
            listOf(30, 45, 60, 120, 180, 240, 300)
        CheckInLadder.offsets(state(), CheckInLadder.Depth.Light, anchor) shouldBe listOf(120, 180)
        // A 60-minute dose rounds to five minutes: 41 -> 40, 62 -> 60.
        val short = state(onsetEnd = 5.0, comeupEnd = 20.0, peakEnd = 62.0, offsetEnd = 90.0, total = 60.0)
        CheckInLadder.offsets(short, CheckInLadder.Depth.Light, anchor) shouldBe listOf(40, 60)
    }

    @Test
    fun `an exact half rounds away from zero, not to the even neighbour`() {
        // Light moments 2.5 and 5 at a five-minute step: 2.5/5 is a tie, and it
        // must become 5 (away from zero) rather than 0 (to the even neighbour),
        // which would then be dropped as below the floor.
        val tied = state(onsetEnd = 0.0, comeupEnd = 0.0, peakEnd = 5.0, offsetEnd = 60.0, total = 60.0)
        CheckInLadder.offsets(tied, CheckInLadder.Depth.Light, anchor) shouldBe listOf(5, 5)
    }

    @Test
    fun `a moment that rounds below the floor is dropped`() {
        // 2 -> 0 at a five-minute step, so only the 4 -> 5 survives.
        val tiny = state(onsetEnd = 0.0, comeupEnd = 0.0, peakEnd = 4.0, offsetEnd = 60.0, total = 60.0)
        CheckInLadder.offsets(tiny, CheckInLadder.Depth.Light, anchor) shouldBe listOf(5)
    }

    @Test
    fun `a second dose's moments are read off the session's anchor`() {
        val later = state(doseTimestamp = anchor.plusSeconds(30 * 60))
        CheckInLadder.offsets(later, CheckInLadder.Depth.Light, anchor) shouldBe listOf(150, 210)
    }

    @Test
    fun `a dose that finished before the anchor suggests nothing`() {
        val earlier = state(doseTimestamp = anchor.minusSeconds(300 * 60))
        CheckInLadder.offsets(earlier, CheckInLadder.Depth.Light, anchor).shouldBeEmpty()
    }

    @Test
    fun `every offset is a whole multiple of the dose's granularity`() {
        val offsets = CheckInLadder.offsets(state(), CheckInLadder.Depth.Wide, anchor)
        val step = CheckInLadder.granularity(300.0)
        offsets.all { it % step == 0 } shouldBe true
    }

    // MARK: - Suggested schedules

    @Test
    fun `nothing modeled means no suggestion`() {
        CheckInLadder.suggestedOffsets(emptyList(), anchor).shouldBeEmpty()
    }

    @Test
    fun `a session's doses merge, dedupe and normalize`() {
        val first = CheckInDose(state(), SubstanceCategory.STIMULANT)
        val second = CheckInDose(state(doseTimestamp = anchor.plusSeconds(60 * 60)), SubstanceCategory.STIMULANT)

        CheckInLadder.suggestedOffsets(listOf(first), anchor) shouldBe listOf(120, 180)
        // The second dose's 120+60 -> 180 collides with the first's own 180.
        CheckInLadder.suggestedOffsets(listOf(first, second), anchor) shouldBe listOf(120, 180, 240)
    }

    @Test
    fun `the same dose suggests more times the deeper its class is read`() {
        val lightDose = CheckInDose(state(), SubstanceCategory.STIMULANT)
        val wideDose = CheckInDose(state(), SubstanceCategory.PSYCHEDELIC)

        CheckInLadder.suggestedOffsets(listOf(lightDose), anchor).size shouldBe 2
        CheckInLadder.suggestedOffsets(listOf(wideDose), anchor) shouldBe
            listOf(30, 45, 60, 120, 180, 240, 300)
    }

    // MARK: - Thinning

    @Test
    fun `thinning keeps both ends where normalizing keeps the earliest`() {
        val thirteen = (1..13).map { it * 5 }

        val thinned = CheckInLadder.thinned(thirteen)
        val normalized = CheckInOffsets.normalized(thirteen)

        // Same length, different survivors: the middle is what gets dropped.
        thinned.size shouldBe CheckInOffsets.MAXIMUM_COUNT
        thinned shouldBe listOf(5, 10, 15, 20, 25, 30, 40, 45, 50, 55, 60, 65)
        normalized shouldBe (1..12).map { it * 5 }

        thinned.first() shouldBe 5
        thinned.last() shouldBe 65
        normalized.last() shouldBe 60
        thinned shouldNotBe normalized
    }

    @Test
    fun `thinning rounds its stride away from zero`() {
        // Nine items to three is a stride of exactly 4.5, so the middle pick is
        // a tie: 5 (away from zero), not 4 (to the even neighbour).
        CheckInLadder.thinned((0..9).toList(), limit = 3) shouldBe listOf(0, 5, 9)
    }

    @Test
    fun `thinning leaves a short list, and a single-slot budget, alone`() {
        val five = listOf(5, 10, 15, 20, 25)
        CheckInLadder.thinned(five, limit = 12) shouldBe five
        CheckInLadder.thinned(five, limit = 5) shouldBe five
        CheckInLadder.thinned(five, limit = 1) shouldBe five
    }

    @Test
    fun `summary reads the whole ladder on one line`() {
        CheckInLadder.summary(listOf(45, 120, 270, 450)) shouldBe "+45m · +2h · +4h 30m · +7h 30m"
        CheckInLadder.summary(emptyList()) shouldBe ""
    }
}
