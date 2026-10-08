package glass.kagerou.piru.ui.tools

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.jupiter.api.Test

/**
 * The GABA card's two decisions, and the window it reads.
 *
 * ## Why these are worth pinning
 * The card has one sentence that carries real weight — alcohol loads GABA-A at a **different site**, so a summed
 * curve is a total load and not a benzodiazepine-equivalent dose. Whether that sentence appears is decided by a
 * substring test over the log's substance names, which is exactly the kind of rule that stops matching when a
 * name's casing or wording changes and nothing looks wrong.
 *
 * The visibility floor is the other: below it the card hides. A card that always draws trains the reader to
 * scroll past the one card that answers "is this receptor loaded".
 */
class GabaLoadTest {

    private fun point(load: Double, minute: Long = 0) =
        LoadPoint(Instant.ofEpochSecond(minute * 60), load)

    // MARK: - The visibility floor

    @Test
    fun `a trail with no samples is not worth a card`() {
        GabaLoad.worthShowing(emptyList()) shouldBe false
    }

    /**
     * The floor is inclusive, and both sides are asserted.
     *
     * `>=` rather than `>`: a peak exactly at the floor is the smallest curve the card claims to show, and an
     * off-by-one here would silently raise the threshold.
     */
    @Test
    fun `the visibility floor is inclusive`() {
        GabaLoad.worthShowing(listOf(point(GABA_VISIBILITY_FLOOR))) shouldBe true
        GabaLoad.worthShowing(listOf(point(GABA_VISIBILITY_FLOOR - 0.001))) shouldBe false
    }

    /**
     * The peak decides, not the last sample or the first.
     *
     * A trail that has already decayed to near zero still contains the doses that loaded the receptor, and the
     * card exists to show that history. Judging on the current value would hide the card exactly when the
     * forward clearance is the interesting part.
     */
    @Test
    fun `the peak decides and not the endpoints`() {
        val decayed = listOf(point(0.9, 0), point(0.4, 60), point(0.01, 120))
        GabaLoad.worthShowing(decayed) shouldBe true
    }

    // MARK: - The alcohol caption

    /**
     * Each spelling the log can plausibly carry is recognised.
     *
     * Substring and case-insensitive, because the log holds whatever the user typed or an import wrote — and
     * "Alcohol (ethanol)" is a real catalogue-style name rather than a hypothetical.
     */
    @Test
    fun `alcohol and ethanol are both recognised, in any casing`() {
        GabaLoad.includesAlcohol(listOf("Alcohol")) shouldBe true
        GabaLoad.includesAlcohol(listOf("alcohol")) shouldBe true
        GabaLoad.includesAlcohol(listOf("Ethanol")) shouldBe true
        GabaLoad.includesAlcohol(listOf("ALCOHOL")) shouldBe true
        GabaLoad.includesAlcohol(listOf("Alcohol (ethanol)")) shouldBe true
        GabaLoad.includesAlcohol(listOf("Diazepam", "Ethanol")) shouldBe true
    }

    /**
     * A log without alcohol does not claim it.
     *
     * The reverse failure, and the one that would make the card lie in the less harmful direction: a caption
     * mentioning alcohol when there is none is a reader looking for a substance they did not take.
     */
    @Test
    fun `a log without alcohol says so`() {
        GabaLoad.includesAlcohol(emptyList()) shouldBe false
        GabaLoad.includesAlcohol(listOf("Diazepam")) shouldBe false
        GabaLoad.includesAlcohol(listOf("Alprazolam", "Clonazepam", "Pregabalin")) shouldBe false
    }

    /**
     * A name that merely contains the letters is not alcohol.
     *
     * There is no such catalogue name, but the rule is a substring test and the boundary is worth stating:
     * what it actually guards is a *different* substance whose name happens to embed the word, and the honest
     * position is that this heuristic would call it alcohol. Asserted here so the limitation is recorded rather
     * than discovered.
     */
    @Test
    fun `the substring rule is a heuristic and is recorded as one`() {
        // A hypothetical name embedding the word would trip it. That is accepted: a false positive costs a
        // sentence about a different binding site, and a false negative costs the sentence.
        GabaLoad.includesAlcohol(listOf("Alcohol-free tonic")) shouldBe true
    }

    // MARK: - The window

    /**
     * The window constants are upstream's, and their relations are what matter.
     *
     * A forward horizon shorter than the past one would draw a curve that is mostly history; a step coarser than
     * the forward horizon would sample the clearance once. Asserted as relations rather than as literals so the
     * numbers can be retuned without the test becoming a transcription.
     */
    @Test
    fun `the sampling window is coherent`() {
        (GABA_PAST_HORIZON_MINUTES > 0) shouldBe true
        (GABA_FORWARD_HORIZON_MINUTES > 0) shouldBe true
        (GABA_STEP_MINUTES > 0) shouldBe true
        // Enough samples forward to show a clearance curve rather than a line segment.
        (GABA_FORWARD_HORIZON_MINUTES / GABA_STEP_MINUTES > 20) shouldBe true
        // And enough history to show the doses doing the loading.
        (GABA_PAST_HORIZON_MINUTES / GABA_STEP_MINUTES > 20) shouldBe true
    }

    /**
     * The four-day history is four days and the forward window a fortnight, exactly as upstream.
     *
     * Asserted as values here, unlike the relations above, because these two are the ones a reader would notice
     * changing: the card's own caption says "recent", and a fortnight is what makes the clearance legible for a
     * class whose members range from hours to days.
     */
    @Test
    fun `the window is upstream's`() {
        GABA_PAST_HORIZON_MINUTES shouldBe (4 * 1_440.0 plusOrMinus 1e-9)
        GABA_FORWARD_HORIZON_MINUTES shouldBe (14 * 1_440.0 plusOrMinus 1e-9)
        GABA_STEP_MINUTES shouldBe 120.0
        GABA_VISIBILITY_FLOOR shouldBe 0.05
    }
}
