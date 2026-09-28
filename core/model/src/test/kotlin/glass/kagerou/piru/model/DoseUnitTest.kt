package glass.kagerou.piru.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Ported from `PiruTests/DoseUnitTests.swift`. The assertions are unchanged —
 * these pin the engine math, not the implementation.
 */
class DoseUnitTest {

    // MARK: - Same unit

    @Test
    fun `Same unit returns amount unchanged`() {
        DoseUnit.convert(100.0, "mg", "mg") shouldBe 100.0
        DoseUnit.convert(50.0, "g", "g") shouldBe 50.0
        DoseUnit.convert(200.0, "µg", "µg") shouldBe 200.0
    }

    // MARK: - mg conversions

    @Test
    fun `mg to g`() {
        DoseUnit.convert(1_000.0, "mg", "g") shouldBe 1.0
    }

    @Test
    fun `mg to µg`() {
        DoseUnit.convert(1.0, "mg", "µg") shouldBe 1_000.0
    }

    // MARK: - g conversions

    @Test
    fun `g to mg`() {
        DoseUnit.convert(1.0, "g", "mg") shouldBe 1_000.0
    }

    @Test
    fun `g to µg`() {
        DoseUnit.convert(1.0, "g", "µg") shouldBe 1_000_000.0
    }

    // MARK: - µg conversions

    @Test
    fun `µg to mg`() {
        DoseUnit.convert(500.0, "µg", "mg") shouldBe 0.5
    }

    @Test
    fun `µg to g`() {
        DoseUnit.convert(1_000_000.0, "µg", "g") shouldBe 1.0
    }

    // MARK: - Non-mass units

    @Test
    fun `Non-mass from-unit returns null`() {
        DoseUnit.convert(10.0, "mL", "mg") shouldBe null
    }

    @Test
    fun `Non-mass to-unit returns null`() {
        DoseUnit.convert(10.0, "mg", "IU") shouldBe null
    }

    @Test
    fun `Both non-mass returns null`() {
        DoseUnit.convert(10.0, "mL", "IU") shouldBe null
    }

    // MARK: - Edge cases

    @Test
    fun `Zero amount`() {
        DoseUnit.convert(0.0, "mg", "g") shouldBe 0.0
    }

    @Test
    fun `Very small amount preserves precision`() {
        DoseUnit.convert(0.001, "mg", "µg") shouldBe 1.0
    }

    // MARK: - Spelling of the micro prefix

    @Test
    fun `Greek mu converts exactly like the micro sign`() {
        // U+03BC GREEK SMALL LETTER MU vs U+00B5 MICRO SIGN. The catalog holds
        // both because upstreams type them differently, and matching only the
        // second meant LSD, all three fentanyl routes and sufentanil IV
        // converted to nothing at all.
        DoseUnit.convert(1_000.0, "μg", "mg") shouldBe 1.0
        DoseUnit.convert(1.0, "mg", "μg") shouldBe 1_000.0
        DoseUnit.convert(50.0, "μg", "µg") shouldBe 50.0
    }

    @Test
    fun `The two micro spellings are distinct codepoints`() {
        // Guards the port itself. The conversion tests above would still pass if
        // an editor or a formatter ever re-encoded DoseUnit.kt so both spellings
        // collapsed into one key — the surviving key would still convert — while
        // the catalog's other spelling silently stopped matching. Asserting the
        // codepoints themselves fails loudly instead.
        "µg"[0].code shouldBe 0x00B5
        "μg"[0].code shouldBe 0x03BC
        DoseUnit.canonical("µg") shouldBe DoseUnit.canonical("μg")
    }

    @Test
    fun `Written-out and abbreviated mass units convert`() {
        for (spelling in listOf("mcg", "ug", "micrograms", "MCG")) {
            DoseUnit.convert(1_000.0, spelling, "mg") shouldBe 1.0
        }
        DoseUnit.convert(1.0, "grams", "mg") shouldBe 1_000.0
        DoseUnit.convert(5.0, "mgs", "mg") shouldBe 5.0
    }

    @Test
    fun `A qualified unit is not folded onto the bare one`() {
        // "mg (freebase)" states a basis. Treating it as plain mg would let a
        // freebase amount be compared against a salt amount as though the
        // qualifier were decoration.
        DoseUnit.convert(10.0, "mg (freebase)", "mg") shouldBe null
        DoseUnit.convert(10.0, "mg (salt)", "µg") shouldBe null
    }

    @Test
    fun `A rate is not a mass`() {
        DoseUnit.convert(25.0, "µg/hr", "µg") shouldBe null
        DoseUnit.convert(25.0, "mcg/hr (patch)", "mg") shouldBe null
        DoseUnit.convert(5.0, "mg/kg", "mg") shouldBe null
    }

    @Test
    fun `Identity holds for units that are not masses at all`() {
        // The inventory replay converts a stock unit to itself; it must not
        // start failing just because mL is not convertible to a mass.
        DoseUnit.convert(30.0, "mL", "mL") shouldBe 30.0
        DoseUnit.convert(2.0, "seeds", "seeds") shouldBe 2.0
    }
}
