package glass.kagerou.piru.engine

import glass.kagerou.piru.model.DoseFrequency
import glass.kagerou.piru.model.RouteOfAdministration
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Test

/**
 * The adherence rules, ported from `PiruTests/AdherenceCalculatorTests.swift`.
 *
 * The iOS suite runs against `Calendar.current`; these pin
 * `America/New_York` instead and pass it everywhere, which is the whole reason
 * the port takes a zone — a day boundary, a weekday number and a month's length
 * are only meaningful once you say *where*. Cases the iOS suite could not write
 * (a daylight-saving span, a UTC-vs-local month boundary, a month roll-up) are
 * here because the zone is now an argument rather than an ambient.
 */
class AdherenceCalculatorTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Instant =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant()

    private fun entry(
        substance: String,
        timestamp: Instant,
        identityKey: String = "",
        route: RouteOfAdministration = RouteOfAdministration.ORAL,
    ) = AdherenceEntry(substance = substance, identityKey = identityKey, route = route, timestamp = timestamp)

    private fun item(
        substance: String,
        identityKey: String = "",
        route: RouteOfAdministration = RouteOfAdministration.ORAL,
        isAsNeeded: Boolean = false,
        startDate: Instant = Instant.EPOCH,
        frequency: DoseFrequency = DoseFrequency.DAILY,
        frequencyDays: List<Int> = emptyList(),
        reminderTimesMinutes: List<Int> = emptyList(),
        sortOrder: Int = 0,
    ) = AdherenceItem(
        substance = substance, identityKey = identityKey, route = route, isAsNeeded = isAsNeeded,
        startDate = startDate, frequency = frequency, frequencyDays = frequencyDays,
        reminderTimesMinutes = reminderTimesMinutes, sortOrder = sortOrder,
    )

    // MARK: - Matching

    @Test
    fun `a dose matches a med logged under another spelling of the name`() {
        AdherenceCalculator.matches(entry("Caffeine", at(2026, 6, 10)), item("caffeine")) shouldBe true
    }

    @Test
    fun `different substances do not match`() {
        AdherenceCalculator.matches(entry("Caffeine", at(2026, 6, 10)), item("Melatonin")) shouldBe false
    }

    @Test
    fun `identity keys join a brand-named med to a generic-named dose`() {
        AdherenceCalculator.matches(
            entry("Methylphenidate", at(2026, 6, 10), identityKey = "psid:mph"),
            item("Concerta", identityKey = "psid:mph"),
        ) shouldBe true
    }

    @Test
    fun `the name fallback covers a dose logged before its id resolved`() {
        AdherenceCalculator.matches(
            entry("Caffeine", at(2026, 6, 10), identityKey = ""),
            item("caffeine", identityKey = "psid:caffeine"),
        ) shouldBe true
    }

    @Test
    fun `two absent identity keys never join, whatever the names`() {
        // An empty key is "we don't know", not "these two are the same drug".
        AdherenceCalculator.matches(
            entry("Caffeine", at(2026, 6, 10), identityKey = ""),
            item("Melatonin", identityKey = ""),
        ) shouldBe false
    }

    @Test
    fun `a route mismatch is never adherence credit, even with matching identity`() {
        // An injected dose is a journal entry, not an answer to an oral script.
        AdherenceCalculator.matches(
            entry("Methylphenidate", at(2026, 6, 10), identityKey = "psid:mph", route = RouteOfAdministration.INTRAVENOUS),
            item("Methylphenidate", identityKey = "psid:mph"),
        ) shouldBe false

        val day = AdherenceCalculator.dayAdherence(
            date = at(2026, 6, 10),
            entries = listOf(
                entry("Methylphenidate", at(2026, 6, 10, 12), identityKey = "psid:mph", route = RouteOfAdministration.INTRAVENOUS),
            ),
            items = listOf(item("Methylphenidate", identityKey = "psid:mph")),
            zone = zone,
        )
        day.status shouldBe AdherenceStatus.Missed
        day.takenCount shouldBe 0
    }

    // MARK: - isDue

    @Test
    fun `nothing is due before its start date`() {
        val start = at(2026, 6, 10, 8)
        AdherenceCalculator.isDue(start, DoseFrequency.DAILY, emptyList(), at(2026, 6, 9), zone) shouldBe false
        AdherenceCalculator.isDue(start, DoseFrequency.DAILY, emptyList(), at(2026, 6, 10), zone) shouldBe true
    }

    @Test
    fun `every other day counts calendar days from the start date`() {
        val start = at(2026, 6, 1)
        val due = { day: Int -> AdherenceCalculator.isDue(start, DoseFrequency.EVERY_OTHER_DAY, emptyList(), at(2026, 6, day), zone) }
        due(1) shouldBe true
        due(2) shouldBe false
        due(3) shouldBe true
        due(15) shouldBe true
    }

    @Test
    fun `every other day stays every other day across a daylight-saving change`() {
        // New York springs forward on 2026-03-08, so 03-07 to 03-09 is 47 hours
        // and still two calendar days. A 24-hour-span rule would call 03-09 an
        // odd day and skip it.
        val start = at(2026, 3, 7, 0, 30)
        val target = at(2026, 3, 9, 0, 30)
        ChronoUnit.HOURS.between(start, target) shouldBe 47L

        AdherenceCalculator.isDue(start, DoseFrequency.EVERY_OTHER_DAY, emptyList(), target, zone) shouldBe true
        AdherenceCalculator.isDue(start, DoseFrequency.EVERY_OTHER_DAY, emptyList(), at(2026, 3, 8), zone) shouldBe false
    }

    @Test
    fun `weekly and biweekly are seven and fourteen day multiples`() {
        val start = at(2026, 6, 1)
        val due = { frequency: DoseFrequency, day: Int ->
            AdherenceCalculator.isDue(start, frequency, emptyList(), at(2026, 6, day), zone)
        }
        due(DoseFrequency.WEEKLY, 1) shouldBe true
        due(DoseFrequency.WEEKLY, 7) shouldBe false
        due(DoseFrequency.WEEKLY, 8) shouldBe true
        due(DoseFrequency.BIWEEKLY, 8) shouldBe false
        due(DoseFrequency.BIWEEKLY, 15) shouldBe true
    }

    @Test
    fun `monthly matches the day of the month`() {
        val start = at(2026, 1, 15)
        val due = { month: Int, day: Int ->
            AdherenceCalculator.isDue(start, DoseFrequency.MONTHLY, emptyList(), at(2026, month, day), zone)
        }
        due(1, 15) shouldBe true
        due(2, 15) shouldBe true
        due(2, 14) shouldBe false
        due(2, 16) shouldBe false
    }

    @Test
    fun `monthly from the thirty-first falls to the last day of a short month`() {
        val start = at(2026, 1, 31)
        val due = { year: Int, month: Int, day: Int ->
            AdherenceCalculator.isDue(start, DoseFrequency.MONTHLY, emptyList(), at(year, month, day), zone)
        }
        due(2026, 1, 31) shouldBe true
        due(2026, 2, 28) shouldBe true
        due(2026, 2, 27) shouldBe false
        due(2026, 3, 31) shouldBe true
        due(2026, 4, 30) shouldBe true
        // And the leap year keeps its own last day rather than the 28th.
        AdherenceCalculator.isDue(
            at(2028, 1, 31), DoseFrequency.MONTHLY, emptyList(), at(2028, 2, 29), zone,
        ) shouldBe true
    }

    @Test
    fun `monthly is measured in the caller's zone, not in UTC`() {
        // 2026-01-31 23:30 in New York is already 2026-02-01 in UTC. The start
        // day of the month is the 31st where the person lives.
        val start = at(2026, 1, 31, 23, 30)
        start.atZone(ZoneId.of("UTC")).dayOfMonth shouldBe 1

        AdherenceCalculator.isDue(start, DoseFrequency.MONTHLY, emptyList(), at(2026, 3, 31, 9), zone) shouldBe true
        AdherenceCalculator.isDue(start, DoseFrequency.MONTHLY, emptyList(), at(2026, 3, 30, 9), zone) shouldBe false
    }

    @Test
    fun `specific days uses Foundation's Sunday-first weekday numbers`() {
        LocalDate.of(2026, 6, 1).dayOfWeek shouldBe DayOfWeek.MONDAY
        val monday = at(2026, 6, 1)
        val tuesday = at(2026, 6, 2)
        val sunday = at(2026, 5, 31)

        // 1 = Sunday, 2 = Monday, 7 = Saturday.
        val mondays = item("Methotrexate", frequency = DoseFrequency.SPECIFIC_DAYS, frequencyDays = listOf(2))
        AdherenceCalculator.isDue(mondays, monday, zone) shouldBe true
        AdherenceCalculator.isDue(mondays, tuesday, zone) shouldBe false
        AdherenceCalculator.isDue(mondays, sunday, zone) shouldBe false

        val weekend = item("Methotrexate", frequency = DoseFrequency.SPECIFIC_DAYS, frequencyDays = listOf(1, 7))
        AdherenceCalculator.isDue(weekend, sunday, zone) shouldBe true
        AdherenceCalculator.isDue(weekend, tuesday, zone) shouldBe false
    }

    // MARK: - The day

    @Test
    fun `a day with nothing due reads as no data`() {
        val day = AdherenceCalculator.dayAdherence(at(2026, 6, 10), emptyList(), emptyList(), zone)
        day.status shouldBe AdherenceStatus.NoData
        day.takenCount shouldBe 0
        day.totalCount shouldBe 0
        day.items.shouldBeEmpty()
        day.halves.shouldBeNull()
    }

    @Test
    fun `a day the item is not due on reads as no data`() {
        val weekly = item("Injection", startDate = at(2026, 6, 1), frequency = DoseFrequency.WEEKLY)
        val day = AdherenceCalculator.dayAdherence(at(2026, 6, 2), emptyList(), listOf(weekly), zone)
        day.status shouldBe AdherenceStatus.NoData
        day.totalCount shouldBe 0
    }

    @Test
    fun `every item taken is complete, some is partial, none is missed`() {
        val items = listOf(item("Caffeine"), item("Melatonin"))
        val onlyCaffeine = listOf(entry("Caffeine", at(2026, 6, 10, 12)))

        AdherenceCalculator.dayAdherence(at(2026, 6, 10), onlyCaffeine, items, zone).let {
            it.status shouldBe AdherenceStatus.Partial
            it.takenCount shouldBe 1
            it.totalCount shouldBe 2
        }
        AdherenceCalculator.dayAdherence(
            at(2026, 6, 10),
            onlyCaffeine + entry("Melatonin", at(2026, 6, 10, 12)),
            items,
            zone,
        ).let {
            it.status shouldBe AdherenceStatus.Complete
            it.takenCount shouldBe 2
        }
        AdherenceCalculator.dayAdherence(at(2026, 6, 10), emptyList(), items, zone).let {
            it.status shouldBe AdherenceStatus.Missed
            it.takenCount shouldBe 0
            it.totalCount shouldBe 2
        }
    }

    @Test
    fun `per-item detail carries each med's own slots`() {
        val items = listOf(item("Caffeine", sortOrder = 1), item("Melatonin", sortOrder = 2))
        val day = AdherenceCalculator.dayAdherence(
            at(2026, 6, 10), listOf(entry("Caffeine", at(2026, 6, 10, 12))), items, zone,
        )

        day.items shouldHaveSize 2
        val caffeine = day.items.first { it.item.substance == "Caffeine" }
        caffeine.taken shouldBe true
        caffeine.takenCount shouldBe 1
        caffeine.totalCount shouldBe 1
        caffeine.id shouldBe "Caffeine1"

        val melatonin = day.items.first { it.item.substance == "Melatonin" }
        melatonin.taken shouldBe false
        melatonin.id shouldBe "Melatonin2"
    }

    @Test
    fun `a dose from another day is not counted`() {
        val day = AdherenceCalculator.dayAdherence(
            at(2026, 6, 10),
            listOf(entry("Caffeine", at(2026, 6, 9, 12))),
            listOf(item("Caffeine")),
            zone,
        )
        day.status shouldBe AdherenceStatus.Missed
    }

    @Test
    fun `the day runs from local midnight to local midnight`() {
        // 23:30 in New York is already the next day in UTC, and still counts
        // for the day the person was living; 00:30 the next morning does not.
        val items = listOf(item("Caffeine"))
        val lateEvening = AdherenceCalculator.dayAdherence(
            at(2026, 6, 10), listOf(entry("Caffeine", at(2026, 6, 10, 23, 30))), items, zone,
        )
        lateEvening.status shouldBe AdherenceStatus.Complete

        val afterMidnight = AdherenceCalculator.dayAdherence(
            at(2026, 6, 10), listOf(entry("Caffeine", at(2026, 6, 11, 0, 30))), items, zone,
        )
        afterMidnight.status shouldBe AdherenceStatus.Missed
    }

    // MARK: - Slots

    @Test
    fun `a multi-time med expects one slot per reminder time`() {
        val twiceDaily = item("Methylphenidate", reminderTimesMinutes = listOf(8 * 60, 13 * 60))

        val one = AdherenceCalculator.dayAdherence(
            at(2026, 6, 10), listOf(entry("Methylphenidate", at(2026, 6, 10, 8))), listOf(twiceDaily), zone,
        )
        one.status shouldBe AdherenceStatus.Partial
        one.takenCount shouldBe 1
        one.totalCount shouldBe 2
        one.items.single().taken shouldBe false

        val two = AdherenceCalculator.dayAdherence(
            at(2026, 6, 10),
            listOf(
                entry("Methylphenidate", at(2026, 6, 10, 8)),
                entry("Methylphenidate", at(2026, 6, 10, 13, 5)),
            ),
            listOf(twiceDaily),
            zone,
        )
        two.status shouldBe AdherenceStatus.Complete
        two.takenCount shouldBe 2
    }

    @Test
    fun `extra doses never overcount a slot`() {
        val day = AdherenceCalculator.dayAdherence(
            at(2026, 6, 10),
            (0..2).map { entry("Caffeine", at(2026, 6, 10, 12, it * 5)) },
            listOf(item("Caffeine")),
            zone,
        )
        day.status shouldBe AdherenceStatus.Complete
        day.takenCount shouldBe 1
        day.totalCount shouldBe 1
    }

    // MARK: - As-needed

    @Test
    fun `an as-needed med is never counted and never missed`() {
        val prn = item("Ibuprofen", isAsNeeded = true)
        val day = AdherenceCalculator.dayAdherence(
            at(2026, 6, 10), listOf(entry("Ibuprofen", at(2026, 6, 10, 9))), listOf(prn), zone,
        )
        day.status shouldBe AdherenceStatus.NoData
        day.totalCount shouldBe 0
        day.items.shouldBeEmpty()
    }

    @Test
    fun `an as-needed med drops out beside a scheduled one`() {
        val scheduled = item("Sertraline")
        val prn = item("Ibuprofen", isAsNeeded = true)
        val day = AdherenceCalculator.dayAdherence(
            at(2026, 6, 10), listOf(entry("Sertraline", at(2026, 6, 10, 9))), listOf(scheduled, prn), zone,
        )
        day.status shouldBe AdherenceStatus.Complete
        day.totalCount shouldBe 1
        day.items shouldHaveSize 1
    }

    @Test
    fun `an as-needed med missing altogether cannot make a day read missed`() {
        val scheduled = item("Sertraline", reminderTimesMinutes = listOf(8 * 60, 20 * 60))
        val prn = item("Ibuprofen", isAsNeeded = true, reminderTimesMinutes = listOf(8 * 60))
        val day = AdherenceCalculator.dayAdherence(
            at(2026, 6, 10), listOf(entry("Sertraline", at(2026, 6, 10, 8))), listOf(scheduled, prn), zone,
        )
        // 1 of the scheduled med's 2 slots, and the PRN contributes no slot at all.
        day.status shouldBe AdherenceStatus.Partial
        day.takenCount shouldBe 1
        day.totalCount shouldBe 2
    }

    // MARK: - Halves

    @Test
    fun `a routine on both sides of noon splits, and says which half went missing`() {
        val twiceDaily = item("Testine", reminderTimesMinutes = listOf(8 * 60, 20 * 60))

        val morningOnly = AdherenceCalculator.dayAdherence(
            at(2026, 6, 10), listOf(entry("Testine", at(2026, 6, 10, 8))), listOf(twiceDaily), zone,
        ).halves
        morningOnly?.morning shouldBe AdherenceStatus.Complete
        morningOnly?.evening shouldBe AdherenceStatus.Missed
        morningOnly?.morningTotal shouldBe 1
        morningOnly?.eveningTotal shouldBe 1

        val eveningOnly = AdherenceCalculator.dayAdherence(
            at(2026, 6, 10), listOf(entry("Testine", at(2026, 6, 10, 20))), listOf(twiceDaily), zone,
        ).halves
        eveningOnly?.morning shouldBe AdherenceStatus.Missed
        eveningOnly?.evening shouldBe AdherenceStatus.Complete
    }

    @Test
    fun `a half-day routine gets no split`() {
        val morningOnly = item("Testine", reminderTimesMinutes = listOf(8 * 60, 10 * 60))
        AdherenceCalculator.dayAdherence(at(2026, 6, 10), emptyList(), listOf(morningOnly), zone)
            .halves.shouldBeNull()
    }

    @Test
    fun `one untimed med takes the day's split away without touching the headline`() {
        val timed = item("Testine", reminderTimesMinutes = listOf(8 * 60, 20 * 60))
        val untimed = item("Otherine")
        val day = AdherenceCalculator.dayAdherence(at(2026, 6, 10), emptyList(), listOf(timed, untimed), zone)

        day.halves.shouldBeNull()
        day.status shouldBe AdherenceStatus.Missed
        day.takenCount shouldBe 0
        day.totalCount shouldBe 3
    }

    @Test
    fun `the noon seam is local noon, not UTC noon`() {
        // 11:30 and 12:30 in New York: the same item's morning slot is answered
        // by the first and its evening slot by the second.
        val twiceDaily = item("Testine", reminderTimesMinutes = listOf(8 * 60, 20 * 60))

        AdherenceCalculator.dayAdherence(
            at(2026, 6, 10),
            listOf(entry("Testine", at(2026, 6, 10, 11, 30))),
            listOf(twiceDaily),
            zone,
        ).halves.let {
            it?.morning shouldBe AdherenceStatus.Complete
            it?.evening shouldBe AdherenceStatus.Missed
        }

        AdherenceCalculator.dayAdherence(
            at(2026, 6, 10),
            listOf(entry("Testine", at(2026, 6, 10, 12, 30))),
            listOf(twiceDaily),
            zone,
        ).halves.let {
            it?.morning shouldBe AdherenceStatus.Missed
            it?.evening shouldBe AdherenceStatus.Complete
        }
    }

    // MARK: - Streaks

    @Test
    fun `no days is a zero streak`() {
        AdherenceCalculator.streak(emptyList(), at(2026, 6, 10, 9), zone) shouldBe 0
    }

    @Test
    fun `consecutive kept days count, and a missed day ends the walk`() {
        val kept = { day: Int, status: AdherenceStatus -> DayStatus(at(2026, 6, day), status) }
        AdherenceCalculator.streak(
            listOf(kept(9, AdherenceStatus.Complete), kept(8, AdherenceStatus.Complete), kept(7, AdherenceStatus.Complete)),
            at(2026, 6, 10, 9),
            zone,
        ) shouldBe 3

        AdherenceCalculator.streak(
            listOf(kept(9, AdherenceStatus.Missed), kept(8, AdherenceStatus.Complete)),
            at(2026, 6, 10, 9),
            zone,
        ) shouldBe 0
    }

    @Test
    fun `a partial day counts toward the streak`() {
        AdherenceCalculator.streak(
            listOf(
                DayStatus(at(2026, 6, 9), AdherenceStatus.Partial),
                DayStatus(at(2026, 6, 8), AdherenceStatus.Complete),
            ),
            at(2026, 6, 10, 9),
            zone,
        ) shouldBe 2
    }

    @Test
    fun `a gap of off days does not break the streak`() {
        // A weekly script: kept on the 3rd, nothing due 4th-9th.
        val days = listOf(DayStatus(at(2026, 6, 3), AdherenceStatus.Complete)) +
            (4..9).map { DayStatus(at(2026, 6, it), AdherenceStatus.NoData) }
        AdherenceCalculator.streak(days, at(2026, 6, 10, 9), zone) shouldBe 1
    }

    @Test
    fun `today adds to the streak when kept and not while it is missed`() {
        AdherenceCalculator.streak(
            listOf(
                DayStatus(at(2026, 6, 10), AdherenceStatus.Complete),
                DayStatus(at(2026, 6, 9), AdherenceStatus.Complete),
            ),
            at(2026, 6, 10, 9),
            zone,
        ) shouldBe 2

        AdherenceCalculator.streak(
            listOf(
                DayStatus(at(2026, 6, 10), AdherenceStatus.Missed),
                DayStatus(at(2026, 6, 9), AdherenceStatus.Complete),
            ),
            at(2026, 6, 10, 9),
            zone,
        ) shouldBe 1
    }

    @Test
    fun `a streak walks across a month boundary`() {
        AdherenceCalculator.streak(
            listOf(
                DayStatus(at(2026, 6, 1), AdherenceStatus.Complete),
                DayStatus(at(2026, 5, 31), AdherenceStatus.Complete),
                DayStatus(at(2026, 5, 30), AdherenceStatus.Complete),
            ),
            at(2026, 6, 1, 9),
            zone,
        ) shouldBe 3
    }

    @Test
    fun `currentStreak reads whole days`() {
        val days = listOf(
            DayAdherence(at(2026, 6, 9), AdherenceStatus.Complete, 2, 2, emptyList()),
            DayAdherence(at(2026, 6, 8), AdherenceStatus.Partial, 1, 2, emptyList()),
        )
        AdherenceCalculator.currentStreak(days, at(2026, 6, 10, 9), zone) shouldBe 2
    }

    // MARK: - The month

    @Test
    fun `a month is every day of it, in calendar order`() {
        val days = AdherenceCalculator.monthDays(at(2026, 6, 17), emptyList(), listOf(item("Caffeine")), zone)
        days shouldHaveSize 30
        days.first().date shouldBe at(2026, 6, 1)
        days.last().date shouldBe at(2026, 6, 30)
        days.all { it.status == AdherenceStatus.Missed } shouldBe true

        // February: 29 days in a leap year, 28 otherwise.
        AdherenceCalculator.monthDays(at(2028, 2, 10), emptyList(), emptyList(), zone) shouldHaveSize 29
        AdherenceCalculator.monthDays(at(2026, 2, 10), emptyList(), emptyList(), zone) shouldHaveSize 28
    }

    @Test
    fun `days the med is not due on carry no data`() {
        val weekly = item("Injection", startDate = at(2026, 6, 1), frequency = DoseFrequency.WEEKLY)
        val days = AdherenceCalculator.monthDays(at(2026, 6, 1), emptyList(), listOf(weekly), zone)
        val due = days.filter { it.status != AdherenceStatus.NoData }.map { it.date }
        due shouldBe listOf(at(2026, 6, 1), at(2026, 6, 8), at(2026, 6, 15), at(2026, 6, 22), at(2026, 6, 29))
    }

    @Test
    fun `the month counts today and excludes the days still to come`() {
        val days = AdherenceCalculator.monthDays(
            at(2026, 6, 1),
            listOf(entry("Caffeine", at(2026, 6, 10, 8))),
            listOf(item("Caffeine")),
            zone,
        )
        // 1 taken, 15 due: the 1st through the 15th are counted (today included,
        // so this morning's unlogged doses already pull the ratio down), and the
        // 16th onward are not.
        AdherenceCalculator.monthSummary(days, at(2026, 6, 15, 10)) shouldBe MonthAdherence(taken = 1, due = 15, hasData = true)
    }

    @Test
    fun `the month counts only the days that had something due`() {
        val weekly = item("Injection", startDate = at(2026, 6, 1), frequency = DoseFrequency.WEEKLY)
        val days = AdherenceCalculator.monthDays(
            at(2026, 6, 1),
            listOf(entry("Injection", at(2026, 6, 8, 9))),
            listOf(weekly),
            zone,
        )
        // Due on the 1st, 8th and 15th; the other 12 days are NoData, not misses.
        AdherenceCalculator.monthSummary(days, at(2026, 6, 15, 10)) shouldBe MonthAdherence(taken = 1, due = 3, hasData = true)
    }

    @Test
    fun `a month with nothing due has no data rather than zero percent`() {
        val days = AdherenceCalculator.monthDays(at(2026, 6, 1), emptyList(), emptyList(), zone)
        AdherenceCalculator.monthSummary(days, at(2026, 6, 15, 10)) shouldBe MonthAdherence(taken = 0, due = 0, hasData = false)
    }

    @Test
    fun `the month ends where the next one starts`() {
        val items = listOf(item("Caffeine"))
        val entries = listOf(
            entry("Caffeine", at(2026, 5, 31, 23, 30)),
            entry("Caffeine", at(2026, 6, 1, 0, 30)),
        )
        val may = AdherenceCalculator.monthAdherence(at(2026, 5, 20), entries, items, at(2026, 6, 1, 9), zone)
        val june = AdherenceCalculator.monthAdherence(at(2026, 6, 20), entries, items, at(2026, 6, 1, 9), zone)

        // May is over, so all 31 of its days count: one kept, thirty missed.
        may shouldBe MonthAdherence(taken = 1, due = 31, hasData = true)
        // June has only begun, so only its 1st counts — the late-evening dose
        // belongs to May and the after-midnight one to June, and neither leaks.
        june shouldBe MonthAdherence(taken = 1, due = 1, hasData = true)
    }

    @Test
    fun `the month rolls up per-item slots, not per-day wins`() {
        val twiceDaily = item("Methylphenidate", reminderTimesMinutes = listOf(8 * 60, 20 * 60))
        val days = AdherenceCalculator.monthDays(
            at(2026, 6, 1),
            listOf(
                entry("Methylphenidate", at(2026, 6, 1, 8)),
                entry("Methylphenidate", at(2026, 6, 1, 20)),
            ),
            listOf(twiceDaily),
            zone,
        )
        AdherenceCalculator.monthSummary(days, at(2026, 6, 3, 10)) shouldBe MonthAdherence(taken = 2, due = 6, hasData = true)
    }
}
