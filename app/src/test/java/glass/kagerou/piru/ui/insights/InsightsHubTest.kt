package glass.kagerou.piru.ui.insights

import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.data.entity.SessionNoteEntity
import glass.kagerou.piru.model.RouteOfAdministration
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Date
import java.util.UUID
import org.junit.jupiter.api.Test

/**
 * The Insights hub's two gates and its inline counting.
 *
 * ## Why the gates are the point
 * Both were conditional in upstream and neither was conditional in this port, and **a card that shows
 * unconditionally still renders** — so losing a gate is invisible in a screenshot, in a build and in a manual
 * pass over the screen. The only way to notice is to assert it.
 *
 * The counting is here for the same reason at one remove: the sparkline is a **fixed-length** series, and a
 * sparse one would draw a fortnight with two entries identically to a fortnight with two entries and twelve
 * blanks.
 */
class InsightsHubTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun entry(daysAgo: Long, at: Instant): DoseEntryEntity = DoseEntryEntity.create(
        substance = "Test",
        amount = 10.0,
        unit = "mg",
        route = RouteOfAdministration.ORAL,
        timestamp = Date.from(at.minusSeconds(daysAgo * 86_400)),
    )

    private fun note(worked: Int?): SessionNoteEntity = SessionNoteEntity(
        id = UUID.randomUUID(),
        timestamp = Date.from(Instant.now()),
        worked = worked,
    )

    // MARK: - The "Did it work?" gate

    /**
     * No rated note, no card.
     *
     * The gate's whole content: a reader who never rates a dose should not be offered a page that compares
     * ratings. An empty list and a list of unrated notes are both "no".
     */
    @Test
    fun `the felt-patterns gate needs a rating`() {
        InsightsHub.hasRatedNotes(emptyList()) shouldBe false
        InsightsHub.hasRatedNotes(listOf(note(null), note(null))) shouldBe false
    }

    /**
     * Any rating opens it, including a "no".
     *
     * `worked` is an `Int?` rather than a `Boolean`, and zero is a real answer — "it did not work" is the answer
     * that page is most useful for, so a truthiness check would hide the card from the readers who need it most.
     */
    @Test
    fun `one rating opens the gate, including a zero`() {
        InsightsHub.hasRatedNotes(listOf(note(1))) shouldBe true
        InsightsHub.hasRatedNotes(listOf(note(0))) shouldBe true
        InsightsHub.hasRatedNotes(listOf(note(null), note(5))) shouldBe true
    }

    // MARK: - The daily counts

    /**
     * The series is exactly the window's length, with zeros for the quiet days.
     *
     * The property the chart depends on. A sparse list would make two very different fortnights draw the same.
     */
    @Test
    fun `the series is fixed length with zeros for quiet days`() {
        val now = Instant.parse("2026-03-14T12:00:00Z")
        val counts = InsightsHub.dailyCounts(listOf(entry(0, now)), now, zone)
        counts.size shouldBe InsightsHub.USAGE_SPARKLINE_DAYS
        counts.last() shouldBe 1
        counts.dropLast(1).all { it == 0 } shouldBe true
    }

    /**
     * The window ends today and reaches back thirteen days, so fourteen days inclusive.
     *
     * Both edges asserted, because an off-by-one here silently drops the oldest day or looks one day into the
     * future — and either way the chart's shape changes without anything erroring.
     */
    @Test
    fun `the window covers today and the previous thirteen days`() {
        val now = Instant.parse("2026-03-14T12:00:00Z")
        val entries = listOf(
            entry(0, now),
            entry(13, now),
            entry(14, now),
        )
        val counts = InsightsHub.dailyCounts(entries, now, zone)
        // Today is the last slot, thirteen days ago the first.
        counts.last() shouldBe 1
        counts.first() shouldBe 1
        // Fourteen days ago is outside the window.
        counts.sum() shouldBe 2
    }

    /**
     * Several entries on one day count together, and order within a day does not matter.
     */
    @Test
    fun `several entries on a day are summed`() {
        val now = Instant.parse("2026-03-14T12:00:00Z")
        val entries = listOf(entry(2, now), entry(2, now.minusSeconds(3_600)), entry(2, now.plusSeconds(3_600)))
        val counts = InsightsHub.dailyCounts(entries, now, zone)
        // The day two back is index 11 of 14.
        counts[11] shouldBe 3
    }

    /**
     * The average is over the window, not over the days that have entries.
     *
     * Dividing by the number of non-empty days would report a reader's heavy days as their average, which is the
     * opposite of what an average per day is for.
     */
    @Test
    fun `the average divides by the window and not by the active days`() {
        val now = Instant.parse("2026-03-14T12:00:00Z")
        val entries = listOf(entry(0, now), entry(1, now), entry(2, now), entry(3, now), entry(4, now), entry(5, now), entry(6, now))
        // Seven entries over fourteen days.
        InsightsHub.averagePerDay(entries, now, zone) shouldBe 0.5
    }

    @Test
    fun `an empty log has no data and a zero average`() {
        val now = Instant.parse("2026-03-14T12:00:00Z")
        InsightsHub.averagePerDay(emptyList(), now, zone) shouldBe 0.0
        val summary = InsightsHub.UsageSummary(0, 0.0, InsightsHub.dailyCounts(emptyList(), now, zone))
        summary.hasData shouldBe false
    }

    /**
     * A degenerate window is empty rather than a crash or a single zero.
     */
    @Test
    fun `a non-positive window has no series`() {
        val now = Instant.parse("2026-03-14T12:00:00Z")
        InsightsHub.dailyCounts(emptyList(), now, zone, days = 0) shouldBe emptyList()
        InsightsHub.dailyCounts(emptyList(), now, zone, days = -3) shouldBe emptyList()
    }

    // MARK: - The month grid

    /**
     * The grid is padded to the month's first weekday, and future days are blank rather than missed.
     *
     * A future day marked missed would be a claim about a day that has not happened, which is the kind of thing
     * that makes an adherence calendar feel like an accusation.
     */
    @Test
    fun `the grid pads the start and blanks the future`() {
        // March 2026 starts on a Sunday, so there is no leading padding.
        val month = YearMonth.of(2026, 3)
        val today = LocalDate.of(2026, 3, 10)
        val grid = InsightsHub.monthGrid(month, today) { InsightsHub.AdherenceSummary.DayStatus.COMPLETE }
        grid.size shouldBe 31
        // Days 1 to 10 are answered, 11 onwards are blank.
        grid.take(10).all { it == InsightsHub.AdherenceSummary.DayStatus.COMPLETE } shouldBe true
        grid.drop(10).all { it == null } shouldBe true
    }

    /**
     * A month starting mid-week gets the leading blanks, so the columns line up.
     *
     * April 2026 starts on a Wednesday, which is three Sunday-first cells: Sunday, Monday, Tuesday.
     */
    @Test
    fun `a mid-week month start is padded to the right weekday`() {
        val grid = InsightsHub.monthGrid(YearMonth.of(2026, 4), LocalDate.of(2026, 4, 30)) {
            InsightsHub.AdherenceSummary.DayStatus.MISSED
        }
        // Three leading nulls, then 30 days.
        grid.take(3).all { it == null } shouldBe true
        grid.drop(3).size shouldBe 30
        grid.drop(3).all { it == InsightsHub.AdherenceSummary.DayStatus.MISSED } shouldBe true
    }

    /** A day with nothing scheduled is blank, not missed. */
    @Test
    fun `a day with no schedule is blank`() {
        val grid = InsightsHub.monthGrid(YearMonth.of(2026, 3), LocalDate.of(2026, 3, 31)) { null }
        // Only the leading padding and the statuses are null; March has none of the former.
        grid.all { it == null } shouldBe true
        grid.size shouldBe 31
    }

    /** The summary's own gate: no scheduled meds means no data, whatever the month grid holds. */
    @Test
    fun `adherence needs something due`() {
        InsightsHub.AdherenceSummary(0, 0, emptyList()).hasData shouldBe false
        InsightsHub.AdherenceSummary(0, 4, emptyList()).hasData shouldBe true
    }
}
