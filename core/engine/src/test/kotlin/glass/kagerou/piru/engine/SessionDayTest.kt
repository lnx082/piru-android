package glass.kagerou.piru.engine

import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.jupiter.api.Test

/**
 * The day boundary: which session a timestamp belongs to.
 *
 * Ported from the `Calendar.sessionDayStart` / `sessionDayEnd` halves of
 * `Shared/CalendarSessionDay.swift`. Every case fixes its zone, because a day
 * boundary that reads the ambient default cannot be tested across one — which is
 * also why the zone is a parameter upstream of here.
 */
class SessionDayTest {

    private val utc: ZoneId = ZoneOffset.UTC

    private fun at(iso: String): Instant = Instant.parse(iso)

    // MARK: - The hour

    @Test
    fun `The stored hour is used inside 0 to 12 and replaced outside it`() {
        // One rule for every way the value can be wrong: a missing key on first
        // launch, a value from an older build, a hand-edited preference.
        SessionDay.boundaryHour(null) shouldBe 4
        SessionDay.boundaryHour(-1) shouldBe 4
        SessionDay.boundaryHour(13) shouldBe 4
        SessionDay.boundaryHour(0) shouldBe 0
        SessionDay.boundaryHour(5) shouldBe 5
        SessionDay.boundaryHour(12) shouldBe 12
        SessionDay.BOUNDARY_HOUR_RANGE shouldBe 0..12
        // The key the settings screen writes and the reader must agree on.
        SessionDay.DAY_BOUNDARY_HOUR_KEY shouldBe "dayBoundaryHour"
    }

    @Test
    fun `An hour outside the range is corrected wherever it comes from`() {
        // The same rule covers a direct call, so there is exactly one place where the
        // boundary is decided rather than two that can drift apart.
        val t = at("2026-09-29T02:00:00Z")
        SessionDay.sessionDayStart(t, utc, dayBoundaryHour = 30) shouldBe SessionDay.sessionDayStart(t, utc, 4)
        SessionDay.sessionDayStart(t, utc, dayBoundaryHour = -3) shouldBe SessionDay.sessionDayStart(t, utc, 4)
    }

    // MARK: - Which session

    @Test
    fun `An early-morning entry belongs to the previous day's session`() {
        // The case the whole idea exists for: 02:00 Tuesday is still Monday night.
        SessionDay.sessionDayStart(at("2026-09-29T02:00:00Z"), utc, 4) shouldBe at("2026-09-28T04:00:00Z")
        // After the boundary it is today's session.
        SessionDay.sessionDayStart(at("2026-09-29T05:00:00Z"), utc, 4) shouldBe at("2026-09-29T04:00:00Z")
        // Exactly on the boundary the day has already turned: the boundary opens the
        // day it names.
        SessionDay.sessionDayStart(at("2026-09-29T04:00:00Z"), utc, 4) shouldBe at("2026-09-29T04:00:00Z")
        // And omitting the hour is the same as asking for the default, so a caller
        // that has no setting to hand cannot accidentally get midnight.
        SessionDay.sessionDayStart(at("2026-09-29T02:00:00Z"), utc) shouldBe at("2026-09-28T04:00:00Z")
    }

    @Test
    fun `A zero boundary degenerates to midnight`() {
        // The classic behaviour, and the reason the comparison is `hour < cutoff`
        // rather than `hour <= cutoff` — with 0 the latter would push every entry
        // back a day.
        SessionDay.sessionDayStart(at("2026-09-29T00:00:00Z"), utc, 0) shouldBe at("2026-09-29T00:00:00Z")
        SessionDay.sessionDayStart(at("2026-09-29T23:59:59Z"), utc, 0) shouldBe at("2026-09-29T00:00:00Z")
    }

    @Test
    fun `The session day is contiguous across a year boundary`() {
        val newYearEarlyHours = at("2026-01-01T02:00:00Z")
        // Still the session that began on New Year's Eve.
        SessionDay.sessionDayStart(newYearEarlyHours, utc, 4) shouldBe at("2025-12-31T04:00:00Z")
        SessionDay.sessionDayEnd(newYearEarlyHours, utc, 4) shouldBe at("2026-01-01T04:00:00Z")
        // And the end of one session is the start of the next, with no hour counted
        // twice and none skipped.
        val end = SessionDay.sessionDayEnd(newYearEarlyHours, utc, 4)
        SessionDay.sessionDayStart(end, utc, 4) shouldBe end
        SessionDay.sessionDayEnd(SessionDay.sessionDayStart(newYearEarlyHours, utc, 4), utc, 4) shouldBe end
    }

    @Test
    fun `The zone decides the day, not the machine's default`() {
        val t = at("2026-09-29T02:00:00Z")
        // 02:00 UTC is before the boundary and lands on the 28th; the same instant is
        // 11:00 in Tokyo, which is well after it and lands on the 29th.
        SessionDay.sessionDayStart(t, utc, 4) shouldBe at("2026-09-28T04:00:00Z")
        SessionDay.sessionDayStart(t, ZoneId.of("Asia/Tokyo"), 4) shouldBe at("2026-09-28T19:00:00Z")
    }

    @Test
    fun `The boundary is a duration, so a spring-forward day shifts it`() {
        // Europe/Berlin loses an hour at 02:00 on 2026-03-29: local midnight is 23:00Z
        // and four hours after that instant is 05:00 local, not 04:00. Upstream adds
        // `hours × 3 600 s` for exactly this reason, and a wall-clock implementation
        // would silently disagree with iOS about where that one day breaks.
        val berlin = ZoneId.of("Europe/Berlin")
        val midday = at("2026-03-29T12:00:00Z")
        SessionDay.sessionDayStart(midday, berlin, 4) shouldBe at("2026-03-29T03:00:00Z")
        // A session day is still exactly 24 hours, so this one ends an hour late by
        // the wall clock — and the *next* day's boundary is back at 04:00 local
        // (02:00Z), which is an hour before this one ends. The two days therefore
        // overlap for that one hour each year. That is upstream's arithmetic, not a
        // transcription slip, and it is harmless for the only thing this is used for:
        // bucketing entries, one bucket at a time.
        SessionDay.sessionDayEnd(midday, berlin, 4) shouldBe at("2026-03-30T03:00:00Z")
        SessionDay.sessionDayStart(at("2026-03-30T12:00:00Z"), berlin, 4) shouldBe at("2026-03-30T02:00:00Z")
    }
}
