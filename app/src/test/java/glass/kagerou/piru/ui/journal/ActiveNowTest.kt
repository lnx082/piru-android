package glass.kagerou.piru.ui.journal

import glass.kagerou.piru.engine.ActiveSubstanceState
import glass.kagerou.piru.model.P3Color
import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.jupiter.api.Test

/**
 * The Active Now card's shape and its visibility.
 *
 * ## Why the graph rule is the one worth testing
 * The card draws either a phase bar or a timeline window, and the choice keys on **distinct substances** rather
 * than on the number of active states. That is the subtle part: a single substance redosed three times produces
 * three states and is still one compound, so a rule that counted states would draw three overlapping bands of the
 * same substance — a graph nobody can read, and one that looks deliberate rather than wrong.
 *
 * The visibility rule is the other half. An empty state list is the ordinary case, and a card that always draws
 * teaches the reader to scroll past the one surface whose point is that it changes on its own.
 */
class ActiveNowTest {

    private fun state(
        substance: String,
        doseAt: Instant = Instant.parse("2026-03-14T20:00:00Z"),
    ) = ActiveSubstanceState(
        substanceName = substance,
        tint = P3Color.NEUTRAL,
        doseTimestamp = doseAt,
        amount = 100.0,
        unit = "mg",
        route = "oral",
        onsetEndMinutes = 30.0,
        comeupEndMinutes = 60.0,
        peakEndMinutes = 120.0,
        offsetEndMinutes = 240.0,
        afterglowEndMinutes = null,
        totalMinutes = 300.0,
    )

    // MARK: - Visibility

    @Test
    fun `nothing active means no card`() {
        ActiveNow.worthShowing(emptyList()) shouldBe false
        ActiveNow.headline(emptyList()) shouldBe null
    }

    @Test
    fun `one state is worth a card`() {
        ActiveNow.worthShowing(listOf(state("MDMA"))) shouldBe true
    }

    // MARK: - The graph rule

    /**
     * One substance, however many times it was redosed, is one curve.
     *
     * The case the rule exists for, and the one a state count would get wrong.
     */
    @Test
    fun `a redosed single substance does not get a graph`() {
        val redosed = listOf(
            state("MDMA", Instant.parse("2026-03-14T20:00:00Z")),
            state("MDMA", Instant.parse("2026-03-14T22:00:00Z")),
            state("MDMA", Instant.parse("2026-03-15T00:00:00Z")),
        )
        ActiveNow.distinctSubstanceCount(redosed) shouldBe 1
        ActiveNow.showsGraph(redosed) shouldBe false
        // And it is still worth showing: three states are three phase bars.
        ActiveNow.worthShowing(redosed) shouldBe true
    }

    /** Two substances overlap, and overlapping curves need the picture. */
    @Test
    fun `two substances get a graph`() {
        val both = listOf(state("MDMA"), state("Ketamine"))
        ActiveNow.distinctSubstanceCount(both) shouldBe 2
        ActiveNow.showsGraph(both) shouldBe true
    }

    /**
     * Casing does not make two substances.
     *
     * The catalogue's spellings are not normalised and a logged name is whatever the user typed, so "MDMA" and
     * "mdma" are one compound. A case-sensitive rule would draw a graph for a user who logged one substance twice
     * with different capitalisation, which is exactly the unreadable band pair the rule exists to prevent.
     */
    @Test
    fun `casing does not make two substances`() {
        val mixed = listOf(state("MDMA"), state("mdma"))
        // The count and the graph rule agree, because both go through the same case-insensitive count. My first
        // implementation lowercased in the graph rule only, so these two lines contradicted each other and the
        // test caught it.
        ActiveNow.distinctSubstanceCount(mixed) shouldBe 1
        ActiveNow.showsGraph(mixed) shouldBe false
        ActiveNow.headline(mixed) shouldBe ActiveNow.Headline.Single("MDMA")
    }

    // MARK: - The headline

    /**
     * One substance is named; several are counted.
     *
     * Two different resources, and the choice is a decision: "MDMA" answers the reader's question, and "3
     * substances" is the honest answer when there are three.
     */
    @Test
    fun `one substance is named and several are counted`() {
        ActiveNow.headline(listOf(state("MDMA"))) shouldBe ActiveNow.Headline.Single("MDMA")
        ActiveNow.headline(listOf(state("MDMA"), state("Ketamine"))) shouldBe
            ActiveNow.Headline.Multiple(2)
    }

    /**
     * A redosed single substance is still named, not counted.
     *
     * The headline follows the same rule as the graph, and for the same reason: three states of one compound is
     * one substance to the reader.
     */
    @Test
    fun `a redosed single substance is still named`() {
        val redosed = listOf(state("MDMA"), state("MDMA"), state("MDMA"))
        ActiveNow.headline(redosed) shouldBe ActiveNow.Headline.Single("MDMA")
    }

    /**
     * The name is the one the user logged, not a canonicalised one.
     *
     * The card is a reading of the user's own log, and showing a name they did not type is how a screen stops
     * matching what is in it.
     */
    @Test
    fun `the headline names the substance as logged`() {
        ActiveNow.headline(listOf(state("2C-B"))) shouldBe ActiveNow.Headline.Single("2C-B")
        ActiveNow.headline(listOf(state("Concerta"))) shouldBe ActiveNow.Headline.Single("Concerta")
    }

    /** Several distinct substances counts the **distinct** ones, not the states. */
    @Test
    fun `the count is of distinct substances`() {
        val mixed = listOf(state("MDMA"), state("MDMA"), state("Ketamine"), state("Ketamine"), state("Caffeine"))
        ActiveNow.headline(mixed) shouldBe ActiveNow.Headline.Multiple(3)
    }
}
