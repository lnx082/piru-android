package glass.kagerou.piru.ui.journal

import glass.kagerou.piru.engine.InteractionResult
import glass.kagerou.piru.engine.InteractionSeverity
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import glass.kagerou.piru.engine.InteractionMechanism

/**
 * Folding a session's warnings into rows.
 *
 * ## The two rules that make this worth testing
 * **The key is the rule, not the text.** Several distinct rules share boilerplate — "Additive CNS depression —
 * increased sedation and impairment" is the sentence for more than one class pair — so folding on the description
 * would merge two different causes into one row and **assert a single mechanism where there are two**. That is a
 * factual error about the user's combination, and it is invisible: the row reads perfectly.
 *
 * **The order within a severity is first-seen.** The severity sort must be stable, and the first-seen order has to
 * come from the input rather than from a map's iteration. A `HashMap`-backed order would reshuffle the rows between
 * runs, which looks like the app being uncertain.
 */
class InteractionGroupingTest {

    /**
     * A warning. The mechanism is **not** a parameter: `InteractionResult.mechanism` is derived from `ruleKey`,
     * deliberately — the mapping reads the rule's key rather than its prose "so it survives a copy change" — so a
     * test sets the key and reads the mechanism back.
     */
    private fun warning(
        severity: InteractionSeverity,
        a: String,
        b: String,
        ruleKey: String,
        description: String = "Additive CNS depression",
    ) = InteractionResult(
        severity = severity,
        substanceA = a,
        substanceB = b,
        description = description,
        ruleKey = ruleKey,
    )

    /**
     * Warnings from the same rule fold into one row that lists every pair.
     *
     * The case the grouping exists for: a stimulant stack fires one rule for each pair, and five identical
     * sentences read as noise rather than as five facts.
     */
    @Test
    fun `one rule folds into one row listing every pair`() {
        val groups = InteractionGrouping.group(
            listOf(
                warning(InteractionSeverity.CAUTION, "A", "B", "stimulant|stimulant"),
                warning(InteractionSeverity.CAUTION, "A", "C", "stimulant|stimulant"),
                warning(InteractionSeverity.CAUTION, "B", "C", "stimulant|stimulant"),
            ),
        )
        groups.size shouldBe 1
        groups.single().pairs shouldContainExactly listOf("A + B", "A + C", "B + C")
        groups.single().substanceAs shouldContainExactly listOf("A", "A", "B")
        groups.single().substanceBs shouldContainExactly listOf("B", "C", "C")
    }

    /**
     * Two rules with **identical prose** stay two rows.
     *
     * The distinction the key exists for. A naive implementation keys on the description, folds these together and
     * asserts one cause; the row would look entirely normal.
     */
    @Test
    fun `the same sentence from different rules stays apart`() {
        val groups = InteractionGrouping.group(
            listOf(
                warning(
                    InteractionSeverity.CAUTION, "A", "B", "ssri|maoi",
                    description = "Additive CNS depression",
                ),
                warning(
                    InteractionSeverity.CAUTION, "C", "D", "opioid|benzo",
                    description = "Additive CNS depression",
                ),
            ),
        )
        groups.size shouldBe 2
        groups.map { it.id } shouldContainExactly listOf("ssri|maoi", "opioid|benzo")
        // Each row keeps its own pair, and neither absorbed the other.
        groups.flatMap { it.pairs } shouldContainExactly listOf("A + B", "C + D")
    }

    /**
     * A result with no rule key falls back to its description.
     *
     * A hand-made result — from a test or a preview — carries no rule, and for those the text is the only identity
     * there is. Two such results with the same text therefore fold, which is the intended behaviour rather than an
     * oversight.
     */
    @Test
    fun `a result with no rule key folds on its text`() {
        val groups = InteractionGrouping.group(
            listOf(
                warning(InteractionSeverity.CAUTION, "A", "B", "", description = "Made by hand"),
                warning(InteractionSeverity.CAUTION, "C", "D", "", description = "Made by hand"),
            ),
        )
        groups.size shouldBe 1
        groups.single().id shouldBe "Made by hand"
        groups.single().pairs shouldContainExactly listOf("A + B", "C + D")
    }

    /**
     * A group's severity is its **worst** member.
     *
     * A folded row stands for several pairs, and a reader scanning needs the chip to answer "how bad is this" —
     * which is the worst of them, not the first. Taking the first would under-report a dangerous pair that happened
     * to be listed second.
     */
    @Test
    fun `a group takes its worst severity`() {
        val groups = InteractionGrouping.group(
            listOf(
                warning(InteractionSeverity.CAUTION, "A", "B", "r"),
                warning(InteractionSeverity.DANGEROUS, "A", "C", "r"),
                warning(InteractionSeverity.CAUTION, "B", "C", "r"),
            ),
        )
        groups.single().severity shouldBe InteractionSeverity.DANGEROUS
    }

    /**
     * Most severe first, and **first-seen order within a severity**.
     *
     * Both halves are asserted: the severities must descend, and the two `CAUTION` rules must keep the order they
     * arrived in rather than an arbitrary one.
     */
    @Test
    fun `rows are most severe first and stable within a severity`() {
        val groups = InteractionGrouping.group(
            listOf(
                warning(InteractionSeverity.CAUTION, "A", "B", "first"),
                warning(InteractionSeverity.UNSAFE, "C", "D", "worst"),
                warning(InteractionSeverity.CAUTION, "E", "F", "second"),
                warning(InteractionSeverity.CAUTION, "G", "H", "mildest"),
            ),
        )
        groups.map { it.id } shouldContainExactly listOf("worst", "first", "second", "mildest")
    }

    /**
     * The mechanism and description come from the **first** member of the group.
     *
     * Within one rule key they are the same — that is what the key being the identity means — so taking the first is
     * both correct and what upstream does. The mechanism is read back rather than passed, because the result derives
     * it from the rule key.
     */
    @Test
    fun `a group keeps its first member's values`() {
        val groups = InteractionGrouping.group(
            listOf(
                warning(InteractionSeverity.CAUTION, "A", "B", "opioid|benzo", description = "first sentence"),
                warning(InteractionSeverity.CAUTION, "C", "D", "opioid|benzo", description = "second sentence"),
            ),
        )
        // The key is the identity, so within a group the description really is the same one; the first wins.
        groups.single().description shouldBe "first sentence"
        groups.single().mechanism shouldBe InteractionMechanism.RESPIRATORY_DEPRESSION
    }

    /**
     * The mechanism is the rule's, not the row's position.
     *
     * Two groups with different keys keep their own mechanisms, which is what makes the folded row's icon mean
     * something rather than being whatever arrived first.
     */
    @Test
    fun `a group's mechanism follows its rule key`() {
        val groups = InteractionGrouping.group(
            listOf(
                warning(InteractionSeverity.CAUTION, "A", "B", "opioid|benzo"),
                warning(InteractionSeverity.CAUTION, "C", "D", "enzyme:cyp2d6|x"),
            ),
        )
        groups.first { it.id == "opioid|benzo" }.mechanism shouldBe InteractionMechanism.RESPIRATORY_DEPRESSION
        groups.first { it.id == "enzyme:cyp2d6|x" }.mechanism shouldBe InteractionMechanism.ENZYME_METABOLIC
    }

    /** Nothing in, nothing out — the card's own gate is `worthShowing`, not this. */
    @Test
    fun `no warnings is no rows`() {
        InteractionGrouping.group(emptyList()) shouldBe emptyList()
    }

    /**
     * A folded row carries the first pair's substances for its timeline link.
     *
     * A row stands for several pairs and the timeline can only be opened for one, so the choice is the first — and
     * an empty list yields empty strings rather than throwing, because a group with no pairs cannot occur but the
     * accessor must not be the thing that crashes if it ever did.
     */
    @Test
    fun `navigation takes the first pair`() {
        val groups = InteractionGrouping.group(
            listOf(
                warning(InteractionSeverity.CAUTION, "First", "Second", "r"),
                warning(InteractionSeverity.CAUTION, "Third", "Fourth", "r"),
            ),
        )
        groups.single().navigationSubstanceA shouldBe "First"
        groups.single().navigationSubstanceB shouldBe "Second"
    }
}
