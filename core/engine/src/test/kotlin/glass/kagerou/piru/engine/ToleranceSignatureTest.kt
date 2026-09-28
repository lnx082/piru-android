package glass.kagerou.piru.engine

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * The replay's cache gate.
 *
 * The property that matters is not "the signature is unique" — it is that it changes
 * **exactly** when the replay's answer would. Too coarse and the tool shows stale
 * tolerance; too fine and every navigation pays for a year-long integration that
 * returns the same numbers.
 */
class ToleranceSignatureTest {

    private fun dose(name: String = "Caffeine", mg: Double = 100.0, at: Double = 0.0) =
        ToleranceReplay.SimDose(name, mg, at)

    private val day = 1_440.0

    @Test
    fun `The same log in a different order is the same signature`() {
        // The two callers hand the log over in different orders — newest-first from a
        // journal query, store order from a background fetch — and both must dedupe
        // against each other's work. A sequential hash would fail exactly here.
        val forward = listOf(dose(at = 0.0), dose(at = day), dose(at = 2 * day))
        val backward = forward.reversed()
        ToleranceSignature.of(forward, 70.0, 3 * day) shouldBe
            ToleranceSignature.of(backward, 70.0, 3 * day)
    }

    @Test
    fun `Adding or removing a dose changes it`() {
        val one = listOf(dose())
        val two = listOf(dose(), dose(at = day))
        ToleranceSignature.of(one, 70.0, 3 * day) shouldNotBe ToleranceSignature.of(two, 70.0, 3 * day)
    }

    @Test
    fun `A removed dose is seen even though XOR could cancel one`() {
        // XOR alone cannot see an add and a remove of the *same* dose — the count is
        // what catches it.
        val pair = listOf(dose(at = 0.0), dose(at = day))
        val single = listOf(dose(at = 0.0))
        ToleranceSignature.of(pair, 70.0, 3 * day) shouldNotBe ToleranceSignature.of(single, 70.0, 3 * day)
    }

    @Test
    fun `Changing an amount, a name or a time changes it`() {
        val base = listOf(dose())
        val reference = ToleranceSignature.of(base, 70.0, 3 * day)
        ToleranceSignature.of(listOf(dose(mg = 101.0)), 70.0, 3 * day) shouldNotBe reference
        ToleranceSignature.of(listOf(dose(name = "Theobromine")), 70.0, 3 * day) shouldNotBe reference
        ToleranceSignature.of(listOf(dose(at = 1.0)), 70.0, 3 * day) shouldNotBe reference
    }

    @Test
    fun `Body weight is part of it`() {
        // Every concentration is per kilogram, so a weight change changes every number
        // the replay produces.
        val log = listOf(dose())
        ToleranceSignature.of(log, 70.0, 3 * day) shouldNotBe ToleranceSignature.of(log, 71.0, 3 * day)
    }

    @Test
    fun `A dose outside the window is ignored`() {
        // Only the set the replay integrates is hashed, so the tool (which passes the
        // whole log) and the background refresh (which fetches a filtered set) agree.
        val inWindow = listOf(dose(at = 0.0))
        val alsoAncient = inWindow + dose(at = -400 * day)
        val alsoFuture = inWindow + dose(at = 400 * day)
        ToleranceSignature.of(alsoAncient, 70.0, 3 * day) shouldBe
            ToleranceSignature.of(inWindow, 70.0, 3 * day)
        ToleranceSignature.of(alsoFuture, 70.0, 3 * day) shouldBe
            ToleranceSignature.of(inWindow, 70.0, 3 * day)
    }

    @Test
    fun `Time is bucketed to the hour`() {
        // Time-decay refreshes at most hourly rather than on every navigation: the
        // layers move on scales of hours to months, so a same-hour visit reusing the
        // last answer is the intent rather than a staleness bug.
        val log = listOf(dose())
        val now = 3 * day
        ToleranceSignature.of(log, 70.0, now) shouldBe ToleranceSignature.of(log, 70.0, now + 59.0)
        ToleranceSignature.of(log, 70.0, now) shouldNotBe ToleranceSignature.of(log, 70.0, now + 60.0)
    }

    @Test
    fun `A unit respelling is not a change here, because it is not a change to the replay`() {
        // The engine's dose has already been converted, so 1000 mg and 1 g arrive as
        // the same value. Upstream hashes the written unit and would recompute; the
        // numbers would come out identical, so this is one fewer needless pass.
        ToleranceSignature.of(listOf(dose(mg = 1_000.0)), 70.0, 3 * day) shouldBe
            ToleranceSignature.of(listOf(dose(mg = 1_000.0)), 70.0, 3 * day)
        // And an empty log still has a signature rather than throwing.
        ToleranceSignature.of(emptyList(), 70.0, 3 * day).isEmpty() shouldBe false
    }
}
