package glass.kagerou.piru.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Ported from the `ReceptorTargetKey` suite in
 * `PiruTests/PharmacologyNameKeyTests.swift`.
 *
 * The upstream suite also covers `PharmacologyNameKey` and `SignatureTarget`,
 * which are separate ports; only the target fold is here.
 */
class ReceptorTargetKeyTest {

    @Test
    fun `display strips qualifiers, enantiomer prefixes and receptor suffixes`() {
        ReceptorTargetKey.display("NMDA receptor (PCP site)") shouldBe "NMDA"
        ReceptorTargetKey.display("MOR (+)-tramadol") shouldBe "MOR"
        ReceptorTargetKey.display("(+)-MOR") shouldBe "MOR"
        ReceptorTargetKey.display("5-HT3 receptor") shouldBe "5-HT3"
        ReceptorTargetKey.display("5-HT2 receptors") shouldBe "5-HT2"
        ReceptorTargetKey.display("Glutamate receptors (NMDA/AMPA/kainate, low-affinity)") shouldBe "Glutamate"
    }

    @Test
    fun `A leading parenthetical is the whole name and is kept`() {
        // Stripping it turned "(prodrug — no direct affinity)" into an empty dedup
        // key, which then matched every other empty key.
        ReceptorTargetKey.display("(prodrug — no direct affinity)") shouldBe "(prodrug — no direct affinity)"
        ReceptorTargetKey.fold("(prodrug — no direct affinity)") shouldBe "(prodrug — no direct affinity)"
    }

    @Test
    fun `The two minus signs in the enantiomer prefixes are both handled`() {
        // "( − )-" is U+2212 MINUS SIGN and "(-)-" is U+002D HYPHEN-MINUS. They
        // look identical on screen, and the catalog carries both spellings, so a
        // table that listed only one would leave the other on the key.
        ReceptorTargetKey.display("(−)-MOR") shouldBe "MOR"
        ReceptorTargetKey.display("(-)-MOR") shouldBe "MOR"
        ReceptorTargetKey.display("(±)-MOR") shouldBe "MOR"
    }

    @Test
    fun `fold lowercases and collapses whitespace`() {
        ReceptorTargetKey.fold("DAT  (release)") shouldBe "dat"
        ReceptorTargetKey.fold("α2δ-1 (porcine cortex)") shouldBe "α2δ-1"
    }

    @Test
    fun `fold splits on Unicode whitespace, not just the ASCII space`() {
        // Kotlin's `\s` is ASCII-only by default, so a `split(Regex("\\s+"))`
        // implementation would leave a no-break space in the key and split one
        // receptor's rows into two groups. The first is a real U+00A0, written
        // as an escape because the two spellings are identical in a source file.
        ReceptorTargetKey.fold("DAT\u00A0uptake") shouldBe "dat uptake"
        ReceptorTargetKey.fold("DAT\tuptake") shouldBe "dat uptake"
        ReceptorTargetKey.fold("  DAT  \n uptake ") shouldBe "dat uptake"
    }

    @Test
    fun `Subunit-specific names stay distinct from the coarse target`() {
        // The reason the fold is not a substring match: GABA-A's subunit names are
        // separate binding rows and must remain separate summary rows.
        val coarse = ReceptorTargetKey.fold("GABA-A")
        val subunit = ReceptorTargetKey.fold("GABA-A α1β2γ2")
        val extrasynaptic = ReceptorTargetKey.fold("GABA-A α4β3δ (extrasynaptic)")
        (coarse != subunit) shouldBe true
        (coarse != extrasynaptic) shouldBe true
        (subunit != extrasynaptic) shouldBe true
    }
}
