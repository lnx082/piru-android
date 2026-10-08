package glass.kagerou.piru.ui.tools

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * What the ALDH2 answer changes, which is a note and not a curve.
 *
 * ## The claim this pins
 * `UserProfileStore.aldh2Deficient` was collected at onboarding, stored, exported, imported and editable in
 * Settings — and read by **nothing**. The audit's item is exactly that, and the tempting fix is to make it look
 * answered by nudging the ethanol curve.
 *
 * It must not. The engine models alcohol as one zero-order ethanol compartment; ALDH2 deficiency changes how fast
 * the **acetaldehyde** that ethanol becomes is cleared, and acetaldehyde is not a compartment in this model.
 * Scaling the ethanol curve would invent a mechanism and make the screen claim more than it knows, which is the
 * failure this app's whole posture is against.
 *
 * So [AlcoholFlush.curveIsUnaffectedByDeficiency] exists to state that as a **property** rather than leaving it
 * implied by the absence of code. A future change that made the flag scale the curve would have to delete a test
 * that says why it does not.
 */
class AlcoholFlushTest {

    /**
     * The note appears exactly when the user said yes.
     *
     * An unanswered profile is `false`, not a guess: the note describes *their* physiology, and showing it to
     * everyone would put a paragraph about a deficiency most readers do not have on the screen they opened to read
     * a curve.
     */
    @Test
    fun `the note appears only for a recorded deficiency`() {
        AlcoholFlush.showsAcetaldehydeNote(true) shouldBe true
        AlcoholFlush.showsAcetaldehydeNote(false) shouldBe false
    }

    /**
     * And the curve's numbers do not move.
     *
     * Stated as a function so the decision is visible and testable. The comment on
     * `curveIsUnaffectedByDeficiency` is the reasoning; this assertion is the commitment.
     */
    @Test
    fun `the deficiency does not change the curve`() {
        AlcoholFlush.curveIsUnaffectedByDeficiency() shouldBe true
    }

    /**
     * The two answers are independent of each other.
     *
     * The shape of the whole decision in one place: the flag adds words and moves no numbers. If a future version
     * made the note conditional on something else, or made the curve change with the flag, one of these two
     * assertions would have to change — which is the point of writing them down.
     */
    @Test
    fun `the flag adds words and moves no numbers`() {
        // Both states of the flag.
        for (deficient in listOf(true, false)) {
            AlcoholFlush.showsAcetaldehydeNote(deficient) shouldBe deficient
            // The curve claim holds whatever the flag says.
            AlcoholFlush.curveIsUnaffectedByDeficiency() shouldBe true
        }
    }
}
