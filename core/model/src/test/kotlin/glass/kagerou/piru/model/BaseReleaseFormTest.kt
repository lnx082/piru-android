package glass.kagerou.piru.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Ported from `PiruTests/ReleaseFormDoseTierTests.swift`'s predicate block.
 *
 * One rule, and it decides whether a dose gets a curve at all: only the
 * unspecified product and immediate release are forms the base ladder describes.
 */
class BaseReleaseFormTest {

    @Test
    fun `A missing or empty form is the unspecified product`() {
        BaseReleaseForm.contains(null) shouldBe true
        BaseReleaseForm.contains("") shouldBe true
    }

    @Test
    fun `The unspecified sentinel and immediate release both count`() {
        BaseReleaseForm.contains("0") shouldBe true
        BaseReleaseForm.contains("IR") shouldBe true
        BaseReleaseForm.contains("ir") shouldBe true
    }

    @Test
    fun `Every other release form is unmodeled`() {
        BaseReleaseForm.contains("XR") shouldBe false
        BaseReleaseForm.contains("DEP") shouldBe false
        BaseReleaseForm.contains("ER") shouldBe false
    }
}
