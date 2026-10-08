package glass.kagerou.piru.ui.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The flag-to-sentence mapping, and which flags are cautions.
 *
 * ## Why this matters more than three string lookups
 * The catalogue's flags are **data**, so a future pipeline run can add a fourth one. The card falls back to the raw
 * identifier for a flag it does not know, which is the honest answer — but it means a new harm-reduction flag would
 * appear as a bare identifier with no caution colour and nothing would say so.
 *
 * `isHarmReduction` decides the accent colour, and it is matched on the **exact identifier**: `missold-as-mdma` and
 * a hypothetical `mdma-like` both contain "mdma", and treating the second as a caution would be a quiet false
 * alarm on a substance nobody has flagged.
 */
class StatusFlagsTest {

    /**
     * Every flag the catalogue carries has a sentence.
     *
     * The three names are read from `substance_flags` itself: `suppresses-serotonin-synthesis` (5 substances),
     * `missold-as-mdma` (3) and `model-calibrated` (5). If a fourth appears, this test is where the omission shows
     * up rather than on a screen.
     */
    @Test
    fun `the catalogue's three flags all have sentences`() {
        for (flag in listOf("suppresses-serotonin-synthesis", "missold-as-mdma", "model-calibrated")) {
            (StatusFlags.sentenceRes(flag) != null) shouldBe true
        }
    }

    /**
     * An unknown flag has no sentence, rather than a fabricated one.
     *
     * The fallback is the raw identifier. Inventing a sentence for a flag this build has not read would be a
     * claim about the data dressed as a translation.
     */
    @Test
    fun `an unknown flag has no sentence`() {
        StatusFlags.sentenceRes("some-new-flag") shouldBe null
        StatusFlags.sentenceRes("") shouldBe null
    }

    /**
     * The two harm-reduction flags are cautions; the provenance flag is not.
     *
     * `suppresses-serotonin-synthesis` and `missold-as-mdma` are findings about the **substance**;
     * `model-calibrated` describes how the pipeline produced a number. Colouring the third would put a caution on
     * a note about methodology.
     */
    @Test
    fun `harm-reduction flags are cautions and provenance is not`() {
        StatusFlags.isHarmReduction("suppresses-serotonin-synthesis") shouldBe true
        StatusFlags.isHarmReduction("missold-as-mdma") shouldBe true
        StatusFlags.isHarmReduction("model-calibrated") shouldBe false
    }

    /**
     * The match is on the whole identifier, not a substring.
     *
     * The case the exact match exists for. `missold-as-mdma` contains "mdma", so a substring rule would treat
     * anything mdma-shaped as a caution — and `mdma-like`, `mdma-adjacent` and `not-mdma` would all light up.
     */
    @Test
    fun `a flag that merely contains mdma is not a caution`() {
        StatusFlags.isHarmReduction("mdma-like") shouldBe false
        StatusFlags.isHarmReduction("not-mdma") shouldBe false
        StatusFlags.isHarmReduction("mdma") shouldBe false
        // And the empty string, which a malformed row can produce.
        StatusFlags.isHarmReduction("") shouldBe false
    }

    /** A near-miss on the serotonin flag is likewise not a caution. */
    @Test
    fun `a near-miss on the serotonin flag is not a caution`() {
        StatusFlags.isHarmReduction("suppresses-serotonin") shouldBe false
        StatusFlags.isHarmReduction("suppresses-serotonin-synthesis-possibly") shouldBe false
    }
}
