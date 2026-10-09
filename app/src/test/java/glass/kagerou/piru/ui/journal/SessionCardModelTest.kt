package glass.kagerou.piru.ui.journal

import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.data.entity.SessionEntity
import glass.kagerou.piru.model.RouteOfAdministration
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.UUID
import org.junit.jupiter.api.Test
import io.kotest.matchers.collections.shouldContainExactly

/**
 * The session card's derived text, and the three decisions behind it.
 *
 * ## Why the text is worth testing
 * Ported from `SessionCard`, whose own doc says the text is "formatted **once here** rather than on every
 * `SessionCardView` body pass". That makes it a value object, and a value object can be reasoned about — which is
 * the point, because three of its rules are decisions rather than formatting:
 *
 * - a maintenance session draws as a compact row, not a card;
 * - a one-dose session reads as a single time, not "22:00 – 22:00";
 * - two aliases of one drug are one substance in the summary.
 *
 * Each is the kind of rule a rewrite drops silently: a wrong time label still renders, and a summary that counts
 * aliases separately still looks like a summary.
 */
class SessionCardModelTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun entry(
        substance: String,
        at: Instant,
        sessionId: UUID? = null,
        background: Boolean = false,
    ) = DoseEntryEntity.create(
        substance = substance,
        amount = 10.0,
        unit = "mg",
        route = RouteOfAdministration.ORAL,
        timestamp = Date.from(at),
    ).copy(sessionId = sessionId, isBackgroundMed = background)

    private fun session(id: UUID, title: String? = null, start: Instant) =
        SessionEntity(id = id, startDate = Date.from(start), title = title)

    private val at2000 = Instant.parse("2026-03-14T20:00:00Z")

    /** The identity resolver: names resolve to themselves unless a test overrides it. */
    private val identity: (String) -> String = { it }

    // MARK: - The time label

    /**
     * One dose reads as a single time.
     *
     * The threshold case: two doses a second apart did not happen over a span, and "20:00 – 20:00" is noise the
     * reader has to decode.
     */
    @Test
    fun `a default-duration session reads as a single time`() {
        val card = sessionCard(
            session = null,
            entries = listOf(entry("MDMA", at2000)),
            zone = zone,
            displayTitleFor = identity,
        )
        card.timeLabel shouldBe "20:00"
    }

    /** A real span reads as a range. */
    @Test
    fun `a session spanning minutes reads as a range`() {
        val card = sessionCard(
            session = null,
            entries = listOf(
                entry("MDMA", at2000),
                entry("MDMA", at2000.plusSeconds(3 * 3_600)),
            ),
            zone = zone,
            displayTitleFor = identity,
        )
        card.timeLabel shouldBe "20:00 – 23:00"
    }

    /**
     * Exactly sixty seconds is a span; one second less is not.
     *
     * The boundary asserted from both sides, because the threshold is the whole rule and an off-by-one here
     * changes how a session reads without anything failing.
     */
    @Test
    fun `the span threshold is sixty seconds`() {
        val span = sessionCard(
            session = null,
            entries = listOf(entry("MDMA", at2000), entry("MDMA", at2000.plusSeconds(60))),
            zone = zone,
            displayTitleFor = identity,
        )
        span.timeLabel shouldBe "20:00 – 20:01"

        val noSpan = sessionCard(
            session = null,
            entries = listOf(entry("MDMA", at2000), entry("MDMA", at2000.plusSeconds(59))),
            zone = zone,
            displayTitleFor = identity,
        )
        noSpan.timeLabel shouldBe "20:00"
    }

    // MARK: - The substance summary

    /**
     * Up to three substances are named, and no more are mentioned.
     *
     * A summary that listed nine names is a paragraph rather than a summary.
     */
    @Test
    fun `three or fewer substances are all named`() {
        val card = sessionCard(
            session = null,
            entries = listOf(entry("MDMA", at2000), entry("Ketamine", at2000), entry("Caffeine", at2000)),
            zone = zone,
            displayTitleFor = identity,
        )
        card.substanceSummary shouldBe "MDMA, Ketamine, Caffeine"
    }

    /**
     * Past three, the rest are counted.
     *
     * The count is of the substances beyond the three shown, so "A, B, C +2" says five in total — which is what a
     * reader works out, and what a count of *all* substances would get wrong.
     */
    @Test
    fun `more than three are counted`() {
        val card = sessionCard(
            session = null,
            entries = listOf(
                entry("MDMA", at2000),
                entry("Ketamine", at2000),
                entry("Caffeine", at2000),
                entry("Cannabis", at2000),
                entry("Nitrous", at2000),
            ),
            zone = zone,
            displayTitleFor = identity,
        )
        card.substanceSummary shouldBe "MDMA, Ketamine, Caffeine +2"
    }

    /**
     * Two aliases of one drug collapse to one name.
     *
     * The rule that makes the summary honest: a resolver turning "Concerta" and "Methylphenidate" into the same
     * display title must not have them counted twice, because to the reader they are one substance. A name-keyed
     * dedup would miss it, which is why the dedup is on the **resolved** title.
     */
    @Test
    fun `aliases of one drug collapse to one name`() {
        val card = sessionCard(
            session = null,
            entries = listOf(entry("Concerta", at2000), entry("Methylphenidate", at2000)),
            zone = zone,
            displayTitleFor = { "Methylphenidate" },
        )
        card.substanceSummary shouldBe "Methylphenidate"
    }

    /**
     * The collapse is case-insensitive.
     *
     * A logged name is whatever the user typed, and the same substance twice in different casings is one
     * substance.
     */
    @Test
    fun `the dedup ignores casing`() {
        val card = sessionCard(
            session = null,
            entries = listOf(entry("mdma", at2000), entry("MDMA", at2000)),
            zone = zone,
            displayTitleFor = identity,
        )
        card.substanceSummary shouldBe "mdma"
        card.doseCount shouldBe 2
    }

    /**
     * The name shown is the first-seen spelling.
     *
     * The card is a reading of the user's own log, and showing a name they did not type is how a screen stops
     * matching what is in it.
     */
    @Test
    fun `the first spelling wins`() {
        val card = sessionCard(
            session = null,
            entries = listOf(entry("2C-B", at2000), entry("2c-b", at2000)),
            zone = zone,
            displayTitleFor = identity,
        )
        card.substanceSummary shouldBe "2C-B"
    }

    // MARK: - Maintenance

    /**
     * A session whose doses are all background meds is maintenance.
     *
     * The compact-row rule. A full timeline for a scheduled tablet is noise.
     */
    @Test
    fun `all-background doses are a maintenance session`() {
        val card = sessionCard(
            session = null,
            entries = listOf(entry("Estradiol", at2000, background = true), entry("Estradiol", at2000, background = true)),
            zone = zone,
            displayTitleFor = identity,
        )
        card.isMaintenance shouldBe true
    }

    /**
     * One recreational dose makes it a session, not maintenance.
     *
     * The boundary: "all" is the rule, not "any", and a session with one tablet and one recreational dose is a
     * session worth its card.
     */
    @Test
    fun `one non-background dose makes it a session`() {
        val card = sessionCard(
            session = null,
            entries = listOf(entry("Estradiol", at2000, background = true), entry("MDMA", at2000)),
            zone = zone,
            displayTitleFor = identity,
        )
        card.isMaintenance shouldBe false
    }

    /**
     * A session with no doses is **not** maintenance.
     *
     * `all { }` on an empty list is vacuously true, so a naive predicate would call an empty session maintenance
     * and draw it as an empty compact row. Asserted because that is exactly the trap.
     */
    @Test
    fun `an empty session is not maintenance`() {
        val card = sessionCard(
            session = session(UUID.randomUUID(), start = at2000),
            entries = emptyList(),
            zone = zone,
            displayTitleFor = identity,
        )
        card.isMaintenance shouldBe false
    }

    // MARK: - Navigation and the title

    /**
     * A card with no session row is not navigable, and still draws.
     *
     * A dose whose session is missing still belongs on the screen — dropping it would drop an entry from the log —
     * so the card exists and simply does not route.
     */
    @Test
    fun `a sessionless card is not navigable`() {
        val card = sessionCard(
            session = null,
            entries = listOf(entry("MDMA", at2000)),
            zone = zone,
            displayTitleFor = identity,
        )
        card.navigable shouldBe false
        card.doseCount shouldBe 1
    }

    @Test
    fun `a session card is navigable and carries its title`() {
        val id = UUID.randomUUID()
        val card = sessionCard(
            session = session(id, title = "Festival Saturday", start = at2000),
            entries = listOf(entry("MDMA", at2000, sessionId = id)),
            zone = zone,
            displayTitleFor = identity,
        )
        card.navigable shouldBe true
        card.title shouldBe "Festival Saturday"
        card.sessionId shouldBe id.toString()
    }

    // MARK: - Grouping

    /**
     * The log groups into days of sessions, newest first.
     *
     * The list's shape: a day header counts one day, so the sessions under it must not run on into the previous
     * one.
     */
    @Test
    fun `the log groups into days of sessions, newest first`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val early = Instant.parse("2026-03-13T10:00:00Z")
        val late = Instant.parse("2026-03-14T22:00:00Z")
        val days = sessionDays(
            entries = listOf(
                entry("MDMA", early, sessionId = first),
                entry("Ketamine", late, sessionId = second),
            ),
            sessions = mapOf(
                first.toString() to session(first, start = early),
                second.toString() to session(second, start = late),
            ),
            zone = zone,
            today = java.time.LocalDate.of(2026, 3, 14),
            displayTitleFor = identity,
        )
        days.size shouldBe 2
        days.first().date shouldBe java.time.LocalDate.of(2026, 3, 14)
        days.last().date shouldBe java.time.LocalDate.of(2026, 3, 13)
        days.first().sessions.single().substanceSummary shouldBe "Ketamine"
    }

    /**
     * A dose whose session row is missing still appears.
     *
     * The alternative — resolving sessions from a session query and dropping unmatched doses — is a log that
     * silently loses entries, which is the failure mode that matters most here.
     */
    @Test
    fun `a dose with a missing session row still appears`() {
        val orphan = UUID.randomUUID()
        val days = sessionDays(
            entries = listOf(entry("MDMA", at2000, sessionId = orphan)),
            sessions = emptyMap(),
            zone = zone,
            today = java.time.LocalDate.of(2026, 3, 14),
            displayTitleFor = identity,
        )
        days.single().sessions.single().substanceSummary shouldBe "MDMA"
        days.single().sessions.single().navigable shouldBe false
    }

    /**
     * Sessions within a day are newest first.
     *
     * A day's list is read top-down as a timeline, so the latest session is the one the reader wants first.
     */
    @Test
    fun `sessions within a day are newest first`() {
        val morning = UUID.randomUUID()
        val evening = UUID.randomUUID()
        val early = Instant.parse("2026-03-14T09:00:00Z")
        val late = Instant.parse("2026-03-14T22:00:00Z")
        val days = sessionDays(
            entries = listOf(
                entry("Caffeine", early, sessionId = morning),
                entry("MDMA", late, sessionId = evening),
            ),
            sessions = emptyMap(),
            zone = zone,
            today = java.time.LocalDate.of(2026, 3, 14),
            displayTitleFor = identity,
        )
        days.single().sessions.map { it.substanceSummary } shouldBe listOf("MDMA", "Caffeine")
    }

    // MARK: - The continuous (by-day) grouping

    /**
     * A session that crosses midnight is filed under **two** days, one per dose.
     *
     * The case the whole grouping exists for. Under `sessionDays` both doses land on the 14th, because the card is dated
     * by where the session started — so a dose taken at 00:10 on the 15th is shown under the 14th, and a reader asking
     * "what did I take today" is told about a dose from yesterday evening.
     */
    @Test
    fun `a session crossing midnight is filed under both days`() {
        val late = UUID.randomUUID()
        val beforeMidnight = Instant.parse("2026-03-14T23:50:00Z")
        val afterMidnight = Instant.parse("2026-03-15T00:10:00Z")
        val days = sessionDaysByDay(
            entries = listOf(
                entry("MDMA", beforeMidnight, sessionId = late),
                entry("MDMA", afterMidnight, sessionId = late),
            ),
            sessions = mapOf(late.toString() to session(late, start = beforeMidnight)),
            zone = zone,
            today = java.time.LocalDate.of(2026, 3, 15),
            displayTitleFor = identity,
        )

        days.map { it.date } shouldContainExactly listOf(
            java.time.LocalDate.of(2026, 3, 15),
            java.time.LocalDate.of(2026, 3, 14),
        )
        // Each day holds exactly the dose taken on it — one each, not both on either.
        days.all { day -> day.sessions.sumOf { it.doseCount } == 1 } shouldBe true
    }

    /**
     * The session grouping files the same two doses on **one** day, which is the difference stated as a case.
     *
     * Asserted side by side with the case above on purpose: a reader comparing the two sees the whole change, and a
     * future edit that made the groupings identical would fail this pair rather than one of them.
     */
    @Test
    fun `the session grouping files that same session on one day`() {
        val late = UUID.randomUUID()
        val beforeMidnight = Instant.parse("2026-03-14T23:50:00Z")
        val afterMidnight = Instant.parse("2026-03-15T00:10:00Z")
        val days = sessionDays(
            entries = listOf(
                entry("MDMA", beforeMidnight, sessionId = late),
                entry("MDMA", afterMidnight, sessionId = late),
            ),
            sessions = mapOf(late.toString() to session(late, start = beforeMidnight)),
            zone = zone,
            today = java.time.LocalDate.of(2026, 3, 15),
            displayTitleFor = identity,
        )

        days.map { it.date } shouldContainExactly listOf(java.time.LocalDate.of(2026, 3, 14))
    }

    /** A dose with no session still appears, as its own card — a log that silently drops entries is worse. */
    @Test
    fun `a straggler with no session is still its own card`() {
        val day = Instant.parse("2026-03-14T12:00:00Z")
        val days = sessionDaysByDay(
            entries = listOf(entry("Caffeine", day, sessionId = null)),
            sessions = emptyMap(),
            zone = zone,
            today = java.time.LocalDate.of(2026, 3, 14),
            displayTitleFor = identity,
        )

        days.size shouldBe 1
        days.single().sessions.size shouldBe 1
    }

    /** Days come newest first, and a day holds its sessions newest first, so the list reads as a run of time. */
    @Test
    fun `days and their sessions are newest first`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val morning = Instant.parse("2026-03-14T09:00:00Z")
        val evening = Instant.parse("2026-03-14T21:00:00Z")
        val days = sessionDaysByDay(
            entries = listOf(
                entry("MDMA", morning, sessionId = first),
                entry("Ketamine", evening, sessionId = second),
            ),
            sessions = mapOf(
                first.toString() to session(first, start = morning),
                second.toString() to session(second, start = evening),
            ),
            zone = zone,
            today = java.time.LocalDate.of(2026, 3, 14),
            displayTitleFor = identity,
        )

        days.size shouldBe 1
        // One day, two sessions in it, the later one first.
        days.single().sessions.size shouldBe 2
        (days.single().sessions.first().startDate > days.single().sessions.last().startDate) shouldBe true
    }
}
