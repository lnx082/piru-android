package glass.kagerou.piru.engine

import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.jupiter.api.Test

/**
 * Ported from `PiruTests/SessionClusteringTests.swift`.
 *
 * The canonical cases come from the spec the heuristic was written against; the
 * regression cases name the behaviour that was wrong before each was fixed, so a
 * failure here means a real behavioural change rather than a refactor.
 */
class SessionClusteringTest {

    /** A fixed, timezone-stable base instant so hour offsets are exact. */
    private val base: Instant = Instant.ofEpochSecond(1_700_000_000)

    /** A dose [hours] after the base with an effect duration of [effectHours]. */
    private fun dose(hours: Double, effectHours: Double?, background: Boolean = false) =
        SessionClustering.Dose(
            timestamp = base.plusMillis((hours * 3_600_000).toLong()),
            effectDurationMinutes = effectHours?.times(60),
            isBackgroundMed = background,
        )

    private fun every(from: Double, through: Double, by: Double): List<Double> {
        val out = mutableListOf<Double>()
        var v = from
        while (v <= through) {
            out += v
            v += by
        }
        return out
    }

    private fun allIndices(groups: List<List<Int>>): List<Int> = groups.flatten().sorted()

    // MARK: - Canonical cases

    @Test
    fun `9 AM coffee and 3_45 AM next-dose are different sessions`() {
        val doses = listOf(dose(0.0, 5.0), dose(18.75, 4.0))
        SessionClustering.cluster(doses) shouldBe listOf(listOf(0), listOf(1))
    }

    @Test
    fun `All-nighter re-dosing every 2_5 h is one session crossing the clock cutoff`() {
        val doses = listOf(
            dose(0.0, 4.0),
            dose(2.5, 4.0),
            dose(5.0, 4.0),
            dose(7.0, 4.0),
        )
        SessionClustering.cluster(doses) shouldBe listOf(listOf(0, 1, 2, 3))
    }

    @Test
    fun `A short hit dropped into a long trip stays in the trip`() {
        // A 12 h substance at 0, a 1 h one at +1, a 5 h one at +5. The short dose
        // must not collapse the session's effect window.
        val doses = listOf(dose(0.0, 12.0), dose(1.0, 1.0), dose(5.0, 5.0))
        SessionClustering.cluster(doses) shouldBe listOf(listOf(0, 1, 2))
    }

    @Test
    fun `The sleep ceiling splits even a long-acting drug across a quiescent night`() {
        val doses = listOf(dose(0.0, 12.0), dose(10.0, 12.0))
        SessionClustering.cluster(doses) shouldBe listOf(listOf(0), listOf(1))
    }

    /**
     * The reported regression: an 8-hour substance at 19:00 redosed at 01:30 was
     * starting a second session, though its own curve was still on screen until
     * 03:00. The flat six-hour ceiling was the binding constraint, not the effect
     * window.
     */
    @Test
    fun `A redose inside a long-acting dose's own window stays in the session`() {
        SessionClustering.cluster(listOf(dose(0.0, 8.0), dose(6.5, 8.0))) shouldBe listOf(listOf(0, 1))
    }

    /**
     * The duration-awareness is bounded on both sides: a short-acting substance
     * gets no more reach than the flat ceiling ever gave it.
     */
    @Test
    fun `A short-acting substance keeps the plain sleep ceiling`() {
        // A 2 h substance: 1.2 x 2 h is 2.4 h of tail, well under ceilingMax, so a
        // 6.5 h gap must still split.
        SessionClustering.cluster(listOf(dose(0.0, 2.0), dose(6.5, 2.0))) shouldBe
            listOf(listOf(0), listOf(1))
        // Clearing the ceiling is necessary but not sufficient: a 5 h gap is inside
        // the 6 h ceiling yet still past both the always-join floor and this dose's
        // own 2.4 h window, so it splits too.
        SessionClustering.cluster(listOf(dose(0.0, 2.0), dose(5.0, 2.0))) shouldBe
            listOf(listOf(0), listOf(1))
        // Inside the floor it joins, as it always did.
        SessionClustering.cluster(listOf(dose(0.0, 2.0), dose(2.5, 2.0))) shouldBe
            listOf(listOf(0, 1))
    }

    @Test
    fun `The widened ceiling never exceeds its cap`() {
        SessionClustering.Constants.freshCeiling(100 * 3_600.0) shouldBe
            SessionClustering.Constants.ceilingDurationMax
        SessionClustering.Constants.freshCeiling(60.0) shouldBe
            SessionClustering.Constants.ceilingMax
        SessionClustering.Constants.freshCeiling(null) shouldBe
            SessionClustering.Constants.ceilingMax
    }

    // MARK: - The decaying ceiling and the day cap

    @Test
    fun `The same 4 h gap joins early in a session but splits once it has run long`() {
        // Early: a 4 h gap on a fresh session, ceiling about 6 h, is one session.
        SessionClustering.cluster(listOf(dose(0.0, 4.0), dose(4.0, 4.0))) shouldBe listOf(listOf(0, 1))

        // Late: fill 18 h with 2 h-spaced doses, then the same 4 h gap. By 18 h in
        // the ceiling has decayed to about 3.4 h, so the gap now starts a new one.
        val doses = every(0.0, 18.0, 2.0).map { dose(it, 4.0) } + dose(22.0, 4.0)
        val groups = SessionClustering.cluster(doses)
        groups.size shouldBe 2
        groups.last() shouldBe listOf(doses.size - 1)
    }

    @Test
    fun `Nonstop redosing is hard-capped at 24 h into separate day sessions`() {
        // A dose every 2 h from 0 to 26 h: no gap ever exceeds the ceiling, but the
        // 24 h hard cap forces a new session for the dose a full day after the
        // first, so days cannot chain.
        val doses = every(0.0, 26.0, 2.0).map { dose(it, 4.0) }
        val groups = SessionClustering.cluster(doses)
        groups.size shouldBe 2
        val firstSpan = java.time.Duration.between(
            doses[groups[0].first()].timestamp,
            doses[groups[0].last()].timestamp,
        ).toMillis() / 1000.0
        (firstSpan < SessionClustering.Constants.horizon) shouldBe true
        (groups[1].contains(doses.size - 1)) shouldBe true
    }

    @Test
    fun `A long-acting tail is clamped so it cannot glue a later dose onto the session`() {
        // A very long-acting dose, then three more. Without the effect-tail clamp
        // the 48 h tail would keep the session "active" indefinitely and absorb
        // every later dose; clamped to effectTailCap, a dose past that window
        // splits off even though the gap itself is comfortably inside the ceiling.
        //
        // The last dose sits at 11 h — past the 9 h clamp, but only 5 h after the
        // previous one, so it is the *clamp* under test here and not the ceiling.
        val doses = listOf(
            dose(0.0, 48.0),
            dose(3.0, 1.0),
            dose(6.0, 1.0),
            dose(11.0, 1.0),
        )
        SessionClustering.cluster(doses) shouldBe listOf(listOf(0, 1, 2), listOf(3))
    }

    // MARK: - Floor and fallback

    @Test
    fun `Unknown-duration doses within the floor group, beyond the effect window split`() {
        // The fallback effect is 4 h, so the scaled end is 4.8 h.
        SessionClustering.cluster(listOf(dose(0.0, null), dose(2.0, null))) shouldBe listOf(listOf(0, 1))
        SessionClustering.cluster(listOf(dose(0.0, null), dose(6.0, null))) shouldBe
            listOf(listOf(0), listOf(1))
    }

    // MARK: - Background medications

    @Test
    fun `A lone background med is its own session`() {
        SessionClustering.cluster(listOf(dose(0.0, null, background = true))) shouldBe listOf(listOf(0))
    }

    @Test
    fun `A background med taken during an active session folds into it`() {
        val doses = listOf(dose(0.0, 12.0), dose(2.0, null, background = true))
        SessionClustering.cluster(doses) shouldBe listOf(listOf(0, 1))
    }

    @Test
    fun `A background med after the active window does not glue onto the ended session`() {
        // A 2 h substance, window 2.4 h, at 0; the pill at +4 h is within the
        // ceiling but past the window.
        val doses = listOf(dose(0.0, 2.0), dose(4.0, null, background = true))
        SessionClustering.cluster(doses) shouldBe listOf(listOf(0), listOf(1))
    }

    @Test
    fun `Co-administered background meds form one maintenance session`() {
        val doses = listOf(
            dose(0.0, null, background = true),
            dose(0.1, null, background = true),
            dose(0.2, null, background = true),
        )
        SessionClustering.cluster(doses) shouldBe listOf(listOf(0, 1, 2))
    }

    @Test
    fun `Morning and evening meds are separate maintenance sessions`() {
        val doses = listOf(
            dose(0.0, null, background = true),
            dose(12.0, null, background = true),
        )
        SessionClustering.cluster(doses) shouldBe listOf(listOf(0), listOf(1))
    }

    @Test
    fun `A normal dose never absorbs a preceding maintenance session`() {
        val doses = listOf(dose(0.0, null, background = true), dose(1.0, 5.0))
        SessionClustering.cluster(doses) shouldBe listOf(listOf(0), listOf(1))
    }

    // MARK: - Invariants

    @Test
    fun `Every dose lands in exactly one session, nothing orphaned or duplicated`() {
        val doses = listOf(
            dose(0.0, 12.0),
            dose(1.0, 1.0),
            dose(5.0, 5.0),
            dose(20.0, 4.0),
            dose(20.2, null, background = true),
            dose(40.0, 6.0),
            dose(60.0, null, background = true),
        )
        val groups = SessionClustering.cluster(doses)
        allIndices(groups) shouldBe (0 until doses.size).toList()
        groups.flatten().size shouldBe doses.size // no duplicates
    }

    @Test
    fun `Clustering is idempotent over the same doses`() {
        val doses = listOf(
            dose(0.0, 8.0),
            dose(3.0, 4.0),
            dose(15.0, 5.0),
            dose(15.1, null, background = true),
            dose(30.0, 12.0),
        )
        SessionClustering.cluster(doses) shouldBe SessionClustering.cluster(doses)
    }

    @Test
    fun `Empty input produces no sessions`() {
        SessionClustering.cluster(emptyList()) shouldBe emptyList()
    }

    // MARK: - canJoinKeepingTime

    @Test
    fun `A dose inside the session's span can join keeping its time`() {
        val first = base
        val last = base.plusMillis(4 * 3_600_000)
        val inside = base.plusMillis(2 * 3_600_000)
        SessionClustering.canJoinKeepingTime(inside, first, last) shouldBe true
    }

    @Test
    fun `A dose within the ceiling of an edge can join keeping its time`() {
        val first = base
        val last = base.plusMillis(4 * 3_600_000)
        val after = last.plusMillis(6 * 3_600_000)
        SessionClustering.canJoinKeepingTime(after, first, last) shouldBe true
        val before = first.plusMillis(-5 * 3_600_000)
        SessionClustering.canJoinKeepingTime(before, first, last) shouldBe true
    }

    @Test
    fun `A dose beyond the ceiling needs re-timing`() {
        val first = base
        val last = base.plusMillis(4 * 3_600_000)
        val nextDay = last.plusMillis(37 * 3_600_000)
        SessionClustering.canJoinKeepingTime(nextDay, first, last) shouldBe false
        val justPast = last.plusMillis(6 * 3_600_000 + 60_000)
        SessionClustering.canJoinKeepingTime(justPast, first, last) shouldBe false
    }
}
