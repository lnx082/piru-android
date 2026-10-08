package glass.kagerou.piru.ui.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Distress detection, and above all its false positives.
 *
 * ## Why the negative cases are the important half
 * Two failure modes, and only one of them is obvious. A miss means someone who typed "overdose" got a
 * list of compounds — bad, and visible. A false positive means the panel appears over an ordinary search,
 * and that is worse than a miss: a warning that fires on "armodafinil" is a warning the reader learns to
 * dismiss, and the one time it matters they will already have stopped reading it. Upstream's own comment
 * names that case, which is why the single-word keywords match on boundaries.
 */
class CrisisKeywordsTest {

    @Test
    fun `the keywords match`() {
        for (query in listOf(
            "help", "emergency", "overdose", "bad trip", "dying", "scared",
            "panic", "ambulance", "hospital", "not okay", "freaking out",
            "call 911", "911", "poisoning", "too much", "od", "can't breathe",
        )) {
            CrisisKeywords.matches(query) shouldBe true
        }
    }

    @Test
    fun `case and surrounding whitespace do not matter`() {
        CrisisKeywords.matches("HELP") shouldBe true
        CrisisKeywords.matches("  Overdose  ") shouldBe true
        CrisisKeywords.matches("Bad Trip") shouldBe true
    }

    /**
     * The case upstream's comment names: a substance whose name merely *contains* a keyword.
     *
     * "armod" contains "od", and a substring match would put the crisis panel over a search for
     * armodafinil. Each of these is a real catalogue name or a plausible fragment of one.
     */
    @Test
    fun `a keyword inside a word is not a match`() {
        CrisisKeywords.matches("armodafinil") shouldBe false
        CrisisKeywords.matches("armod") shouldBe false
        // "help" inside a word: there is no catalogue name for this, but the rule is the rule.
        CrisisKeywords.matches("helper") shouldBe false
        // "od" inside a name: the "od" in "sodium" is the same trap.
        CrisisKeywords.matches("sodium") shouldBe false
        CrisisKeywords.matches("codeine") shouldBe false
    }

    /**
     * A phrase needs both its words, adjacent.
     *
     * "bad" and "trip" are each ordinary on their own, which is why they are only a keyword together.
     */
    @Test
    fun `a phrase does not match on one of its words`() {
        CrisisKeywords.matches("bad") shouldBe false
        CrisisKeywords.matches("trip") shouldBe false
        // And not when the words are separated by something else.
        CrisisKeywords.matches("bad comedown trip") shouldBe false
    }

    @Test
    fun `a word matches whatever punctuation surrounds it`() {
        CrisisKeywords.matches("overdose?") shouldBe true
        CrisisKeywords.matches("help!") shouldBe true
        CrisisKeywords.matches("what do I do, overdose") shouldBe true
        // The apostrophe in "can't breathe" is stripped by the same tokenizer that splits the query, so
        // the phrase is still adjacent after normalisation.
        CrisisKeywords.matches("I can't breathe") shouldBe true
    }

    /**
     * An empty query is never distress.
     *
     * The search box opens empty, and a panel drawn in that state is chrome rather than a response — a
     * reader would learn to scroll past it before they ever needed it.
     */
    @Test
    fun `a blank query is not a match`() {
        CrisisKeywords.matches("") shouldBe false
        CrisisKeywords.matches("   ") shouldBe false
    }

    /**
     * An ordinary substance search is not distress.
     *
     * The control for the whole file: if any of these matched, the feature would be unusable.
     */
    @Test
    fun `ordinary substance searches are not matches`() {
        for (query in listOf(
            "ketamine", "mdma", "caffeine", "lsd", "psilocybin", "diazepam",
            "concerta", "semaglutide", "2c-b", "5-meo-dmt",
        )) {
            CrisisKeywords.matches(query) shouldBe false
        }
    }
}
