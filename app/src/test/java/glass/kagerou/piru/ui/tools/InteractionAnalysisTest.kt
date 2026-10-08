package glass.kagerou.piru.ui.tools

import glass.kagerou.piru.engine.InteractionSeverity
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The interaction screen's two calculators, called directly.
 *
 * ## Why these exist
 * Both were file-private `private object`s inside `InteractionTimelineScreen.kt`, so the only way to
 * exercise them was to render a 1,661-line Compose screen, drive its pickers, and read what it drew —
 * which is why nothing exercised them. They are now `internal`, which in Kotlin means "visible in this
 * module", and the tests are in this module.
 *
 * The arithmetic is what is worth pinning. `CombinedDepression.band` decides whether a combination of
 * a serotonergic drug and a monoamine-oxidase inhibitor reads as "caution" or "do not", and
 * `EffectAttenuationResult.reductionRangeText` is the sentence a user reads about taking an empathogen
 * on an SSRI. Both are pure functions of numbers, and both were previously reachable only through
 * pixels.
 */
class InteractionAnalysisTest {

    // MARK: - CombinedDepression.band

    /**
     * The band boundaries, exactly.
     *
     * The three thresholds are `>=` comparisons, so each constant's own value belongs to the band
     * above it. That is the kind of boundary an off-by-one moves silently: a load of exactly
     * `UNSAFE_THRESHOLD` reading as caution is the difference between "be careful" and "do not do
     * this", and nothing in the UI would look wrong.
     */
    @Test
    fun `band puts each threshold at the bottom of its own band`() {
        val caution = CombinedDepression.CAUTION_THRESHOLD
        val unsafe = CombinedDepression.UNSAFE_THRESHOLD
        val dangerous = CombinedDepression.DANGEROUS_THRESHOLD

        CombinedDepression.band(caution) shouldBe InteractionSeverity.CAUTION
        CombinedDepression.band(unsafe) shouldBe InteractionSeverity.UNSAFE
        CombinedDepression.band(dangerous) shouldBe InteractionSeverity.DANGEROUS

        // And the step below each is the band below it.
        CombinedDepression.band(Math.nextDown(caution)) shouldBe null
        CombinedDepression.band(Math.nextDown(unsafe)) shouldBe InteractionSeverity.CAUTION
        CombinedDepression.band(Math.nextDown(dangerous)) shouldBe InteractionSeverity.UNSAFE
    }

    /**
     * Below the lowest threshold there is no band, not a "none" band.
     *
     * Null is what the screen reads as "draw nothing"; a fourth enum value would draw a row saying a
     * combination is fine, which is exactly the claim this readout must never make from absence of
     * data.
     */
    @Test
    fun `a load under the lowest threshold has no band`() {
        CombinedDepression.band(0.0) shouldBe null
        CombinedDepression.band(CombinedDepression.CAUTION_THRESHOLD - 0.001) shouldBe null
        // A negative load is not physical, but it must not fall through to a severity either.
        CombinedDepression.band(-1.0) shouldBe null
    }

    /**
     * The thresholds are ordered, which nothing else states.
     *
     * `band` is a chain of `>=` comparisons from the top down, so an inversion would make the lower
     * bands unreachable — `DANGEROUS_THRESHOLD < UNSAFE_THRESHOLD` would mean a load can never be
     * unsafe. Asserted as a relation rather than as three literals, so retuning the numbers is fine
     * and retuning them *wrongly* is not.
     */
    @Test
    fun `the thresholds ascend`() {
        val ascending = CombinedDepression.CAUTION_THRESHOLD < CombinedDepression.UNSAFE_THRESHOLD &&
            CombinedDepression.UNSAFE_THRESHOLD < CombinedDepression.DANGEROUS_THRESHOLD
        ascending shouldBe true
    }

    /**
     * The timestep is positive and finite.
     *
     * `analyze` divides the span by it to size the grid, so a zero would be a division by zero and a
     * negative would make `max(1, ceil(...))` collapse the whole simulation to a single sample.
     */
    @Test
    fun `the simulation timestep is usable as a divisor`() {
        (CombinedDepression.TIMESTEP_MINUTES > 0.0) shouldBe true
        CombinedDepression.TIMESTEP_MINUTES.isFinite() shouldBe true
    }

    // MARK: - CompetingTransporter

    /**
     * Target matching absorbs the database's qualifying suffixes.
     *
     * The catalogue writes targets as free text — "SERT (serotonin transporter)" and "SERT" are both
     * in it — so the match is substring-based and case-insensitive. A case-sensitive or exact match
     * would silently stop pairing a blocker with its transporter, and the screen would show nothing
     * rather than showing the wrong thing, which is harder to notice.
     */
    @Test
    fun `transporter matching is case-insensitive and tolerates qualifiers`() {
        CompetingTransporter.fromTarget("SERT") shouldBe CompetingTransporter.SERT
        CompetingTransporter.fromTarget("sert") shouldBe CompetingTransporter.SERT
        CompetingTransporter.fromTarget("SERT (serotonin transporter)") shouldBe CompetingTransporter.SERT
        CompetingTransporter.fromTarget("SLC6A4 / serotonin transporter") shouldBe CompetingTransporter.SERT
    }

    /**
     * A target that is not this transporter matches nothing.
     *
     * There is one transporter today, so "no match" is the answer for almost every target in the
     * catalogue — and the interesting near-miss is a *different* transporter, which must not be
     * mistaken for this one by a loose substring.
     */
    @Test
    fun `an unrelated target matches nothing`() {
        CompetingTransporter.fromTarget("DAT") shouldBe null
        CompetingTransporter.fromTarget("NET") shouldBe null
        CompetingTransporter.fromTarget("") shouldBe null
    }

    // MARK: - EffectAttenuationResult

    /**
     * The reduction range renders as whole percents with an en dash.
     *
     * Ported from upstream, which uses `–` rather than `-`. The distinction is not cosmetic in a
     * range: a hyphen reads as a compound word at small sizes, and this string is the headline of a
     * section about a blunted drug.
     */
    @Test
    fun `the reduction range renders as whole percentages with an en dash`() {
        val result = EffectAttenuationResult(
            attenuated = "MDMA",
            blockers = listOf("Sertraline"),
            transporter = CompetingTransporter.SERT,
            reductionLow = 0.30,
            reductionHigh = 0.80,
            confidence = glass.kagerou.piru.model.ConfidenceTier.MEDIUM,
        )
        result.reductionRangeText shouldBe "30–80%"
    }

    /**
     * The id is per attenuated substance *and* per transporter.
     *
     * One substance can be blunted at more than one transporter, so keying on the substance alone
     * would collide in a list — which is what the field's own doc says it is for ("one result per
     * blunted releaser at one transporter").
     */
    @Test
    fun `the identity is per substance and per transporter`() {
        val result = EffectAttenuationResult(
            attenuated = "MDMA",
            blockers = listOf("Sertraline"),
            transporter = CompetingTransporter.SERT,
            reductionLow = 0.30,
            reductionHigh = 0.80,
            confidence = glass.kagerou.piru.model.ConfidenceTier.MEDIUM,
        )
        result.id shouldBe "MDMA|SERT"
    }
}
