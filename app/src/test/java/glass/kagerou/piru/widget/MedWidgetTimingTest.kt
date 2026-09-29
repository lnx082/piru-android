package glass.kagerou.piru.widget

import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Test

/**
 * The widget's refresh timing.
 *
 * The arithmetic is the only part of the widget that is not a composition, and it is the
 * part whose failure would be invisible: a boundary computed wrongly produces a widget
 * that is right when you look at it and wrong ten minutes later, which nobody reports
 * because they assume they misread it.
 */
class MedWidgetTimingTest {

    private val zone = ZoneId.of("UTC")

    /** 08:00, with a 09:00 slot ahead. */
    @Test
    fun `the next slot wins when it comes before midnight`() {
        val now = Instant.parse("2026-09-29T08:00:00Z")
        MedWidgetRefresh.nextBoundaryMinutes(now, zone, listOf(9 * 60, 20 * 60)) shouldBe 60L
    }

    /** Past every slot, the next boundary is the day rolling over. */
    @Test
    fun `midnight wins once every slot has passed`() {
        val now = Instant.parse("2026-09-29T22:00:00Z")
        MedWidgetRefresh.nextBoundaryMinutes(now, zone, listOf(9 * 60, 20 * 60)) shouldBe 120L
    }

    /**
     * A slot exactly now is *not* the next boundary: it is this one, and it was handled by
     * the write that set it. Answering zero would ask WorkManager to run immediately.
     */
    @Test
    fun `a slot at the current minute is not the next boundary`() {
        val now = Instant.parse("2026-09-29T09:00:00Z")
        // The 09:00 slot is now; 10:00 is ahead, and midnight is further still.
        MedWidgetRefresh.nextBoundaryMinutes(now, zone, listOf(9 * 60, 10 * 60)) shouldBe 60L
    }

    @Test
    fun `the answer is never zero minutes`() {
        // 23:59:30, so midnight is under a minute off. WorkManager reads a zero delay as
        // "run now", and a boundary that computes to zero would re-run immediately and
        // then again — a spin rather than a schedule.
        val now = Instant.parse("2026-09-29T23:59:30Z")
        MedWidgetRefresh.nextBoundaryMinutes(now, zone, emptyList()) shouldBeGreaterThanOrEqual 1L
    }

    @Test
    fun `a day with no reminder times still refreshes at midnight`() {
        val now = Instant.parse("2026-09-29T12:00:00Z")
        MedWidgetRefresh.nextBoundaryMinutes(now, zone, emptyList()) shouldBe 12 * 60L
    }
}
