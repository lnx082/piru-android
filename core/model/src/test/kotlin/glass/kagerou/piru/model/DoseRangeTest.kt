package glass.kagerou.piru.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Ported from `PiruTests/DoseRangeTests.swift`.
 */
class DoseRangeTest {

    private val range = DoseRange(
        threshold = 10.0,
        light = 15.0..30.0,
        common = 30.0..60.0,
        strong = 60.0..100.0,
        heavy = 100.0,
    )

    // MARK: - Level classification

    @Test
    fun `Sub-threshold dose`() {
        range.levelFor(5.0) shouldBe DoseLevel.SUB
    }

    @Test
    fun `Threshold dose`() {
        range.levelFor(10.0) shouldBe DoseLevel.THRESHOLD
    }

    @Test
    fun `Light dose`() {
        range.levelFor(20.0) shouldBe DoseLevel.LIGHT
    }

    @Test
    fun `Common dose`() {
        range.levelFor(45.0) shouldBe DoseLevel.COMMON
    }

    @Test
    fun `Strong dose`() {
        range.levelFor(80.0) shouldBe DoseLevel.STRONG
    }

    @Test
    fun `Heavy dose`() {
        range.levelFor(120.0) shouldBe DoseLevel.HEAVY
    }

    @Test
    fun `Very heavy dose still classifies as heavy`() {
        range.levelFor(250.0) shouldBe DoseLevel.HEAVY
    }

    // MARK: - Boundary values

    @Test
    fun `Exact threshold boundary`() {
        range.levelFor(10.0) shouldBe DoseLevel.THRESHOLD
    }

    @Test
    fun `Exact heavy boundary`() {
        range.levelFor(100.0) shouldBe DoseLevel.HEAVY
    }

    @Test
    fun `Well above heavy is still heavy`() {
        range.levelFor(200.0) shouldBe DoseLevel.HEAVY
    }

    @Test
    fun `Just below threshold`() {
        range.levelFor(9.9) shouldBe DoseLevel.SUB
    }

    @Test
    fun `Light lower bound`() {
        range.levelFor(15.0) shouldBe DoseLevel.LIGHT
    }

    @Test
    fun `Light upper bound`() {
        range.levelFor(30.0) shouldBe DoseLevel.COMMON
    }

    // MARK: - Edge cases

    @Test
    fun `All nil ranges place nothing`() {
        // A ladder with no tiers cannot say where a dose sits. This used to
        // answer SUB, which labeled a custom substance logged with no dose data
        // "sub-threshold" — a claim about the dose, made from the absence of
        // one, and indistinguishable from a real sub-threshold reading.
        val empty = DoseRange()
        empty.levelFor(1_000.0) shouldBe null
    }

    @Test
    fun `Only threshold set`() {
        val partial = DoseRange(threshold = 5.0)
        partial.levelFor(3.0) shouldBe DoseLevel.SUB
        partial.levelFor(5.0) shouldBe DoseLevel.THRESHOLD
        partial.levelFor(100.0) shouldBe DoseLevel.THRESHOLD
    }

    @Test
    fun `Only heavy set`() {
        val partial = DoseRange(heavy = 50.0)
        partial.levelFor(30.0) shouldBe DoseLevel.SUB
        partial.levelFor(50.0) shouldBe DoseLevel.HEAVY
    }

    @Test
    fun `Zero dose`() {
        range.levelFor(0.0) shouldBe DoseLevel.SUB
    }

    @Test
    fun `Negative dose`() {
        range.levelFor(-5.0) shouldBe DoseLevel.SUB
    }

    // MARK: - Dosing precision

    @Test
    fun `Microgram-dosed substances get a critical precision warning`() {
        // An unconvertible unit made dosingPrecision fall through to NONE, so
        // the sub-milligram warning was suppressed on exactly the drugs whose
        // margin is thinnest.
        DoseRange.common(60.0..200.0).dosingPrecision("micrograms") shouldBe
            DoseRange.DosingPrecision.CRITICAL
        DoseRange.common(25.0..50.0).dosingPrecision("μg") shouldBe
            DoseRange.DosingPrecision.CRITICAL
    }

    @Test
    fun `An unconvertible unit yields no precision claim`() {
        // "g (leaf powder)" is not a mass unit, so a gram-dosed botanical must
        // not be flagged as microgram-potent.
        DoseRange.common(2.0..6.0).dosingPrecision("g (leaf powder)") shouldBe
            DoseRange.DosingPrecision.NONE
    }
}

/**
 * Ported from `PiruTests/DoseRangeTests.swift`'s `DoseLevelTests`.
 */
class DoseLevelTest {

    @Test
    fun `Raw values are display strings`() {
        DoseLevel.SUB.wireValue shouldBe "Sub-threshold"
        DoseLevel.HEAVY.wireValue shouldBe "Heavy"
    }
}
