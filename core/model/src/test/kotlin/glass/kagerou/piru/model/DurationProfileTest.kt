package glass.kagerou.piru.model

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import org.junit.jupiter.api.Test

/**
 * Ported from `PiruTests/DurationProfileTests.swift`.
 */
class DurationProfileTest {

    // MARK: - Estimated total minutes

    @Test
    fun `Uses total when provided`() {
        val profile = DurationProfile(
            onset = DurationRange(10.0, 20.0),
            comeup = DurationRange(15.0, 25.0),
            peak = DurationRange(60.0, 120.0),
            offset = DurationRange(30.0, 60.0),
            afterglow = null,
            total = DurationRange(180.0, 300.0),
        )
        profile.estimatedTotalMinutes shouldBe 240.0 // (180+300)/2
    }

    @Test
    fun `Sums phase midpoints when no total`() {
        val profile = DurationProfile(
            onset = DurationRange(10.0, 20.0), // midpoint 15
            comeup = DurationRange(20.0, 30.0), // midpoint 25
            peak = DurationRange(60.0, 120.0), // midpoint 90
            offset = DurationRange(30.0, 60.0), // midpoint 45
            afterglow = DurationRange(60.0, 120.0),
            total = null,
        )
        profile.estimatedTotalMinutes shouldBe 175.0 // 15+25+90+45
    }

    @Test
    fun `Afterglow is not included in estimated total`() {
        val profile = DurationProfile(
            onset = DurationRange(10.0, 10.0),
            comeup = DurationRange(10.0, 10.0),
            peak = DurationRange(10.0, 10.0),
            offset = DurationRange(10.0, 10.0),
            afterglow = DurationRange(1_000.0, 2_000.0),
            total = null,
        )
        profile.estimatedTotalMinutes shouldBe 40.0
    }

    @Test
    fun `Some phases nil`() {
        val profile = DurationProfile(
            onset = DurationRange(10.0, 20.0), // midpoint 15
            comeup = null,
            peak = DurationRange(60.0, 120.0), // midpoint 90
            offset = null,
            afterglow = null,
            total = null,
        )
        profile.estimatedTotalMinutes shouldBe 105.0 // 15+90
    }

    @Test
    fun `All phases nil returns zero`() {
        DurationProfile().estimatedTotalMinutes shouldBe 0.0
    }

    // MARK: - Phase boundaries

    @Test
    fun `Phase boundaries accumulate correctly`() {
        val profile = DurationProfile(
            onset = DurationRange(10.0, 20.0), // 15
            comeup = DurationRange(20.0, 30.0), // 25
            peak = DurationRange(60.0, 120.0), // 90
            offset = DurationRange(30.0, 60.0), // 45
            afterglow = DurationRange(60.0, 120.0), // 90
            total = null,
        )
        val b = profile.phaseBoundaries
        b.onsetEnd shouldBe 15.0
        b.comeupEnd shouldBe 40.0
        b.peakEnd shouldBe 130.0
        b.offsetEnd shouldBe 175.0
        b.afterglowEnd shouldBe 265.0
    }

    @Test
    fun `Phase boundaries with nil phases`() {
        val profile = DurationProfile(peak = DurationRange(60.0, 120.0)) // midpoint 90
        val b = profile.phaseBoundaries
        b.onsetEnd shouldBe 0.0
        b.comeupEnd shouldBe 0.0
        b.peakEnd shouldBe 90.0
        b.offsetEnd shouldBe 90.0
        b.afterglowEnd shouldBe 90.0
    }

    @Test
    fun `All nil phases produce zero boundaries`() {
        val b = DurationProfile().phaseBoundaries
        b.onsetEnd shouldBe 0.0
        b.comeupEnd shouldBe 0.0
        b.peakEnd shouldBe 0.0
        b.offsetEnd shouldBe 0.0
        b.afterglowEnd shouldBe 0.0
    }

    // MARK: - Filling missing phases (endpoint-only data → renderable curve)

    /**
     * The LSD-oral case: onset + total + afterglow, no come-up/peak/offset.
     * Raw, the curve collapses to about the onset length; filled, it spans the
     * total.
     */
    @Test
    fun `Endpoint-only profile is filled to span the total`() {
        val raw = DurationProfile(
            onset = DurationRange(45.0, 90.0), // midpoint 67.5
            comeup = null,
            peak = null,
            offset = null,
            afterglow = DurationRange(720.0, 1_440.0),
            total = DurationRange(540.0, 840.0), // midpoint 690
        )
        // Raw collapses: offsetEnd ≈ onset length, discarding the 690-min total.
        (raw.phaseBoundaries.offsetEnd < 100) shouldBe true

        val filled = raw.fillingMissingPhases(SubstanceCategory.PSYCHEDELIC)
        // Filled curve ends (offsetEnd) right at the stated total.
        abs(filled.phaseBoundaries.offsetEnd - 690.0) shouldBe (0.0 plusOrMinus 1.0)
        // Onset is preserved, afterglow untouched, all shapers now present.
        filled.onset?.midpoint shouldBe 67.5
        (filled.comeup != null) shouldBe true
        (filled.peak != null) shouldBe true
        (filled.offset != null) shouldBe true
        filled.afterglow?.midpoint shouldBe raw.afterglow?.midpoint
    }

    @Test
    fun `Complete profile is left unchanged`() {
        val complete = DurationProfile(
            onset = DurationRange(15.0, 30.0),
            comeup = DurationRange(45.0, 90.0),
            peak = DurationRange(180.0, 300.0),
            offset = DurationRange(180.0, 300.0),
            afterglow = DurationRange(720.0, 1_440.0),
            total = DurationRange(480.0, 720.0),
        )
        val filled = complete.fillingMissingPhases(SubstanceCategory.PSYCHEDELIC)
        filled.comeup?.midpoint shouldBe complete.comeup?.midpoint
        filled.peak?.midpoint shouldBe complete.peak?.midpoint
        filled.offset?.midpoint shouldBe complete.offset?.midpoint
    }

    @Test
    fun `Profile without a total is left unchanged`() {
        val raw = DurationProfile(
            onset = DurationRange(15.0, 90.0), // midpoint 52.5
            comeup = null,
            peak = null,
            offset = null,
            afterglow = DurationRange(60.0, 360.0),
            total = null,
        )
        val filled = raw.fillingMissingPhases(SubstanceCategory.ANTIDEPRESSANT)
        // No total to anchor the span → no synthesis (the half-life path handles it).
        filled.comeup shouldBe null
        filled.peak shouldBe null
        filled.offset shouldBe null
    }

    @Test
    fun `Partial profile preserves real phases and fills the gaps`() {
        val raw = DurationProfile(
            onset = DurationRange(10.0, 10.0), // 10
            comeup = null,
            peak = DurationRange(60.0, 60.0), // real peak, 60
            offset = null,
            afterglow = null,
            total = DurationRange(240.0, 240.0),
        )
        val filled = raw.fillingMissingPhases(SubstanceCategory.STIMULANT)
        // The genuine peak is preserved; the missing come-up and offset are synthesized.
        filled.peak?.midpoint shouldBe 60.0
        (filled.comeup != null) shouldBe true
        (filled.offset != null) shouldBe true
        abs(filled.phaseBoundaries.offsetEnd - 240.0) shouldBe (0.0 plusOrMinus 1.0)
    }

    // MARK: - Category shape table

    @Test
    fun `Wire values are the stored class names`() {
        // Several differ from the Kotlin constant name, and the bundled database
        // and every export carry the wire value.
        SubstanceCategory.GABAPENTINOID.wireValue shouldBe "GABAergic"
        SubstanceCategory.OREXIN_ANTAGONIST.wireValue shouldBe "OrexinAntagonist"
        SubstanceCategory.AMPAKINE.wireValue shouldBe "AMPAkine"
        SubstanceCategory.fromWire("Stimulant") shouldBe SubstanceCategory.STIMULANT
        SubstanceCategory.fromWire("nonsense") shouldBe null
    }
}
