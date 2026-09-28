package glass.kagerou.piru.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Ported from `PiruTests/PSIDTests.swift`, which mirrors
 * `pipeline/build/tests/test_psid.py`. All three implementations — the Python
 * pipeline, the Swift app and this one — must agree, or a PSID minted by the
 * build fails to validate here.
 */
class PSIDTest {

    private companion object {
        const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

        /**
         * The same linear-congruential generator and seed as the Python suite, so
         * the generated payloads — and thus the exercised bodies — match
         * byte-for-byte.
         */
        class Lcg(private var x: ULong) {
            fun next(): ULong {
                x = ((1_103_515_245uL * x) + 12_345uL) and 0x7FFF_FFFFuL
                return x
            }
        }

        fun payloads(count: Int, length: Int, seed: ULong = 42uL): List<String> {
            val g = Lcg(seed)
            return List(count) {
                buildString { repeat(length) { append(ALPHABET[(g.next() % 36uL).toInt()]) } }
            }
        }
    }

    // MARK: - Check character

    @Test
    fun `Check char re-derivation is stable`() {
        for (body in payloads(400, length = 17)) {
            val chk = PSID.iso7064CheckChar(body)
            (chk != null) shouldBe true
            PSID.iso7064CheckChar(body) shouldBe chk
        }
    }

    @Test
    fun `Every single-character substitution is detected`() {
        var undetected = 0
        for (body in payloads(300, length = 17)) {
            val full = body + PSID.iso7064CheckChar(body)!!
            for (i in full.indices) {
                for (c in ALPHABET) {
                    if (c == full[i]) continue
                    val bad = full.toCharArray().also { it[i] = c }
                    val bodyPart = String(bad, 0, bad.size - 1)
                    if (PSID.iso7064CheckChar(bodyPart) == bad.last()) undetected++
                }
            }
        }
        undetected shouldBe 0
    }

    @Test
    fun `Adjacent-transposition detection is strong`() {
        var undetected = 0
        var total = 0
        for (body in payloads(300, length = 17)) {
            val full = body + PSID.iso7064CheckChar(body)!!
            for (i in 0 until full.length - 1) {
                if (full[i] == full[i + 1]) continue
                total++
                val bad = full.toCharArray().also { val t = it[i]; it[i] = it[i + 1]; it[i + 1] = t }
                val bodyPart = String(bad, 0, bad.size - 1)
                if (PSID.iso7064CheckChar(bodyPart) == bad.last()) undetected++
            }
        }
        (undetected.toDouble() / total.toDouble() < 0.01) shouldBe true
    }

    // MARK: - Family

    @Test
    fun `A real InChIKey block-1 is a well-formed block-1 family`() {
        PSID.isBlock1Family("DUGOZIWVEXMGBE") shouldBe true
        PSID.isWellformedFamily("DUGOZIWVEXMGBE") shouldBe true
        PSID.isNameHashFamily("DUGOZIWVEXMGBE") shouldBe false
    }

    @Test
    fun `Malformed families are rejected`() {
        for (bad in listOf("", "SHORT", "toolongfamilyvalue", "dugoziwvexmgbe", "12345678901234")) {
            PSID.isWellformedFamily(bad) shouldBe false
        }
    }

    @Test
    fun `A name-hash family is a sentinel digit plus 13 uppercase letters`() {
        // A real name-hash sampled from the built DB (leading sentinel digit).
        val fam = "8YKFFRXMFUODYV"
        fam.length shouldBe 14
        (fam[0] in '0'..'9') shouldBe true
        PSID.isNameHashFamily(fam) shouldBe true
        PSID.isWellformedFamily(fam) shouldBe true
        PSID.isBlock1Family(fam) shouldBe false
    }

    // MARK: - Compose / parse

    @Test
    fun `Compose then parse round-trips with default facets`() {
        val p = PSID.compose(family = "DUGOZIWVEXMGBE")!!
        p.startsWith("P1-DUGOZIWVEXMGBE-0-0-0-") shouldBe true
        PSID.isValid(p) shouldBe true
        val parsed = PSID.parse(p)!!
        parsed.family shouldBe "DUGOZIWVEXMGBE"
        parsed.stereo shouldBe "0"
        parsed.salt shouldBe "0"
        parsed.release shouldBe "0"
    }

    @Test
    fun `Compose carries facets through parse`() {
        val p = PSID.compose(family = "DUGOZIWVEXMGBE", stereo = "R", release = "XR")!!
        val parsed = PSID.parse(p)!!
        parsed.stereo shouldBe "R"
        parsed.release shouldBe "XR"
        PSID.isValid(p) shouldBe true
    }

    @Test
    fun `A tampered check character is rejected`() {
        val p = PSID.compose(family = "DUGOZIWVEXMGBE")!!
        val bad = p.dropLast(1) + if (p.last() != 'X') "X" else "Y"
        PSID.isValid(bad) shouldBe false
        PSID.parse(bad) shouldBe null
    }

    @Test
    fun `Structurally malformed strings are rejected`() {
        for (bad in listOf("", "P1-DUGOZIWVEXMGBE-0-0-0", "X1-DUGOZIWVEXMGBE-0-0-0-0", "garbage")) {
            PSID.parse(bad) shouldBe null
        }
    }

    @Test
    fun `Check char matches the pipeline's for a known body`() {
        // `psid.compose("DUGOZIWVEXMGBE")` in the Python suite yields these exact
        // strings, pinned so a divergence in either port trips this test.
        PSID.compose(family = "DUGOZIWVEXMGBE") shouldBe "P1-DUGOZIWVEXMGBE-0-0-0-0"
        PSID.compose(family = "DUGOZIWVEXMGBE", stereo = "R", release = "XR") shouldBe
            "P1-DUGOZIWVEXMGBE-R-0-XR-O"
    }
}
