package glass.kagerou.piru.ui.settings

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The licence extractor, and the licence data it reads.
 *
 * ## Why an extractor at all
 * `sources` has no licence column; the licence is inside each row's `description` — `"Community wiki. CC BY-SA
 * 4.0."`, `"Community encyclopedia. CC0."`. A slug-to-licence map would be a second copy of catalogue data, and it
 * would go wrong silently: a missing entry and an unlicensed source both render as "no licence".
 *
 * ## What these pin
 * Two things can be wrong without anything looking wrong on the page:
 *
 * 1. **The order of the patterns.** `CC0` is a substring of nothing here, but `CC BY-SA 4.0` contains `CC`, and a
 *    sloppier pattern list would match a non-commercial variant. Longest-first ordering is the fix and is asserted.
 * 2. **A false negative.** The catalogue names four licences across eighteen sources; if the extractor stops
 *    recognising one, eleven sources and one source look the same — no licence line.
 */
class SourceLicencesTest {

    // MARK: - The extractor

    /** The catalogue's own four licence strings are all recognised. */
    @Test
    fun `the catalogue's licence strings are recognised`() {
        // Verbatim from `sources.description`, read out of the shipped database.
        SourceLicences.licenceIn("Community encyclopedia. CC0.") shouldBe "CC0"
        SourceLicences.licenceIn("Open knowledge base. CC0.") shouldBe "CC0"
        SourceLicences.licenceIn("Community wiki. CC BY-SA 4.0.") shouldBe "CC BY-SA 4.0"
        SourceLicences.licenceIn("Chinese-language community wiki. CC BY-SA 4.0.") shouldBe "CC BY-SA 4.0"
    }

    /**
     * A source that names no licence returns null.
     *
     * Eleven of the eighteen sources are like this — `"Cited journal articles."`, `"Community database."` — and
     * null is the honest answer. A default would put a licence on a source whose terms nobody has checked.
     */
    @Test
    fun `a source with no licence named returns null`() {
        SourceLicences.licenceIn("Cited journal articles.") shouldBe null
        SourceLicences.licenceIn("Community database.") shouldBe null
        SourceLicences.licenceIn("Maintained by Piru's editors.") shouldBe null
    }

    /** Blank and absent descriptions return null rather than throwing. */
    @Test
    fun `a blank description returns null`() {
        SourceLicences.licenceIn(null) shouldBe null
        SourceLicences.licenceIn("") shouldBe null
        SourceLicences.licenceIn("   ") shouldBe null
    }

    /**
     * The match is case-insensitive.
     *
     * The catalogue writes `CC0` in two rows and `CC BY-SA 4.0` in two, and nothing enforces that casing — a
     * future row could write `cc0`, and a case-sensitive test would silently stop naming it.
     */
    @Test
    fun `the match ignores case`() {
        SourceLicences.licenceIn("Community encyclopedia. cc0.") shouldBe "CC0"
        SourceLicences.licenceIn("Community wiki. cc by-sa 4.0.") shouldBe "CC BY-SA 4.0"
    }

    /**
     * The longest identifier wins when two could match.
     *
     * The property the ordering exists for. `"CC0"` and `"CC BY-SA 4.0"` cannot both appear in one description
     * today, but `CC` prefixes a whole family — `"CC BY-NC 4.0"` is a *different* licence from `"CC BY 4.0"`, and
     * a list that matched `"CC BY"` first would report the wrong terms for a source that forbids commercial use.
     *
     * Asserted against a description carrying both, so the rule is checked rather than assumed.
     */
    @Test
    fun `the most specific identifier wins`() {
        // A hypothetical description naming both; the longer identifier is the licence it is under.
        SourceLicences.licenceIn("Text under CC BY-SA 4.0; the code is CC0.") shouldBe "CC BY-SA 4.0"
        // And with only the shorter one present, that is what comes back.
        SourceLicences.licenceIn("All data is CC0.") shouldBe "CC0"
    }

    /** A licence mentioned in passing is still found, which is the point of reading the sentence. */
    @Test
    fun `a licence in a longer sentence is found`() {
        SourceLicences.licenceIn(
            "Chinese-language community wiki. CC BY-SA 4.0. Edited and merged in Piru.",
        ) shouldBe "CC BY-SA 4.0"
    }

    /**
     * The three identifiers the catalogue does not use are recognised anyway.
     *
     * They are in the pattern list because they are the plausible next additions — a relicensed source, or a new
     * one under a public-domain dedication. Recognising them costs nothing and avoids a silent blank line.
     */
    @Test
    fun `the other known identifiers are recognised`() {
        SourceLicences.licenceIn("Released into the public domain.") shouldBe "public domain"
        SourceLicences.licenceIn("Under CC BY 4.0.") shouldBe "CC BY 4.0"
        SourceLicences.licenceIn("Under CC BY-SA 3.0.") shouldBe "CC BY-SA 3.0"
    }

    /** An unknown licence is not guessed at. */
    @Test
    fun `an unknown licence returns null`() {
        SourceLicences.licenceIn("Under the WTFPL.") shouldBe null
        SourceLicences.licenceIn("All rights reserved.") shouldBe null
    }
}
