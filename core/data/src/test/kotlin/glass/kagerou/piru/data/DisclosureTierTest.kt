package glass.kagerou.piru.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * The disclosure tier, which nothing could read.
 *
 * The value has been collected at onboarding, persisted, exported, imported and restored since
 * the port began, and there was no getter and no type to return — so every one of those steps
 * moved a preference that could not be consulted. These pin the two things that make it usable:
 * the wire values are the ones already written, and an unknown or retired value still resolves.
 */
class DisclosureTierTest {

    @Test
    fun `the wire values are the ones already stored and written`() {
        // `harm-reduction` is the column's own default and what onboarding writes for the tier
        // the UI calls "Curious"; renaming either would orphan every stored profile.
        DisclosureTier.CURIOUS.wireValue shouldBe "harm-reduction"
        DisclosureTier.CASUAL.wireValue shouldBe "casual"

        // Round trip, which is what the export and the profile column both rely on.
        for (tier in DisclosureTier.entries) {
            DisclosureTier.fromWire(tier.wireValue) shouldBe tier
        }
    }

    @Test
    fun `an unknown or absent value resolves to the default rather than throwing`() {
        // A preference, not an invariant: a profile that predates the enum, or a file from a
        // build that spelled the tiers differently, must still open.
        DisclosureTier.fromWire(null) shouldBe DisclosureTier.DEFAULT
        DisclosureTier.fromWire("") shouldBe DisclosureTier.DEFAULT
        DisclosureTier.fromWire("something-else") shouldBe DisclosureTier.DEFAULT
        DisclosureTier.DEFAULT shouldBe DisclosureTier.CURIOUS
    }

    @Test
    fun `the retired pharma-nerd value maps forward`() {
        // Upstream removed the third tier and its reader maps the old value forward, so a
        // profile or file written by that build does not lose the answer.
        DisclosureTier.isRetiredValue(DisclosureTier.RETIRED_PHARMA_NERD) shouldBe true
        DisclosureTier.fromWire(DisclosureTier.RETIRED_PHARMA_NERD) shouldBe DisclosureTier.CURIOUS

        // And it is not an entry any more, or `fromWire` would have matched it directly and
        // this test would be asserting nothing.
        (DisclosureTier.entries.any { it.wireValue == DisclosureTier.RETIRED_PHARMA_NERD }) shouldBe false
    }

    @Test
    fun `the tier decides which reference sections a page composes`() {
        // The one rule the port can state without inventing curation.
        DisclosureTier.CASUAL.showsReferenceSections() shouldBe false
        DisclosureTier.CURIOUS.showsReferenceSections() shouldBe true

        // And the density is ordered the way the names imply.
        (DisclosureTier.CASUAL.detailFraction < DisclosureTier.CURIOUS.detailFraction) shouldBe true
        DisclosureTier.CURIOUS.detailFraction shouldNotBe 0.0
    }
}
