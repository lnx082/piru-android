package glass.kagerou.piru.notifications

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * When a reminder fires, given its scheduled time and the user's global delay.
 *
 * ## The rule, and the failure it prevents
 * A plain `scheduled + offset` pushes a late reminder into the **next day**. That breaks two things at once: the
 * satisfaction check compares a fire date against the slot key of the *scheduled* time, so a reminder arriving after
 * midnight is checked against a slot that has already expired; and the "first upcoming slot" arithmetic, which the
 * un-suffixed notification identifier depends on, would see a date belonging to tomorrow.
 *
 * The delay is therefore clamped to the minutes left in the day. The failure mode is invisible on screen — the reminder
 * simply never appears — which is why the rule has a test rather than a comment.
 */
class ReminderOffsetTest {

    private val eightAm = 8 * 60

    /** The ordinary case: the delay is applied exactly. */
    @Test
    fun `an ordinary delay is applied exactly`() {
        ReminderOffset.apply(scheduledMinutes = eightAm, offsetMinutes = 30) shouldBe eightAm + 30
        ReminderOffset.apply(scheduledMinutes = eightAm, offsetMinutes = 0) shouldBe eightAm
    }

    /** A delay that stays inside the day is not clamped, however large. */
    @Test
    fun `a delay that stays inside the day is not clamped`() {
        // 12:00 plus eight hours is 20:00, still today.
        ReminderOffset.apply(scheduledMinutes = 12 * 60, offsetMinutes = 8 * 60) shouldBe 20 * 60
    }

    /**
     * A delay that would cross midnight is clamped to the day's end.
     *
     * The case the clamp exists for: 23:50 with a 30-minute delay would land at 00:20 tomorrow.
     */
    @Test
    fun `a delay past midnight is clamped to the day`() {
        val late = 23 * 60 + 50
        ReminderOffset.apply(scheduledMinutes = late, offsetMinutes = 30) shouldBe ReminderOffset.MINUTES_PER_DAY - 1
        // And it is never a minute of the next day.
        (ReminderOffset.apply(scheduledMinutes = late, offsetMinutes = 600) < ReminderOffset.MINUTES_PER_DAY) shouldBe
            true
    }

    /** The last minute of the day cannot be delayed at all, because there is nothing left to delay into. */
    @Test
    fun `the last minute of the day takes no delay`() {
        val last = ReminderOffset.MINUTES_PER_DAY - 1
        ReminderOffset.apply(scheduledMinutes = last, offsetMinutes = 1) shouldBe last
    }

    /**
     * A negative delay is treated as zero.
     *
     * The store clamps what it holds, so a negative here is a bug rather than a preference — and firing **before** the
     * dose is due is the worse of the two ways to be wrong about it.
     */
    @Test
    fun `a negative delay does not fire early`() {
        ReminderOffset.apply(scheduledMinutes = eightAm, offsetMinutes = -60) shouldBe eightAm
    }

    /** A scheduled minute outside the day is brought inside it rather than producing a fire date in another day. */
    @Test
    fun `an out-of-range scheduled minute is brought into the day`() {
        ReminderOffset.apply(scheduledMinutes = -10, offsetMinutes = 0) shouldBe 0
        ReminderOffset.apply(scheduledMinutes = ReminderOffset.MINUTES_PER_DAY + 10, offsetMinutes = 0) shouldBe
            ReminderOffset.MINUTES_PER_DAY - 1
    }

    /** Midnight itself is a valid scheduled time, and takes the full day's delay. */
    @Test
    fun `midnight takes the whole day of delay`() {
        ReminderOffset.apply(scheduledMinutes = 0, offsetMinutes = ReminderOffset.MINUTES_PER_DAY) shouldBe
            ReminderOffset.MINUTES_PER_DAY - 1
    }
}
