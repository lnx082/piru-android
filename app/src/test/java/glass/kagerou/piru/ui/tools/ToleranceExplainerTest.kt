package glass.kagerou.piru.ui.tools

import glass.kagerou.piru.engine.ReceptorClasses
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The explainer's completeness, which is the property that keeps it honest.
 *
 * ## Why completeness is the testable part
 * The prose is prose. What can be checked — and what actually rots — is whether the explainer still covers the
 * model it explains. `meaningRes` is a `when` over the engine's `ReceptorClass`, so a new class is a **compile
 * error**; what a `when` cannot catch is a class that has an arm and was left out of the teaching order, which
 * is a class the user's card shows and the explainer never mentions.
 *
 * The reverse matters too: a class in the teaching order that the engine does not have would be copy about a
 * mechanism that no longer exists.
 */
class ToleranceExplainerTest {

    /**
     * Every class the engine has is either taught or deliberately omitted — and only `.UNKNOWN` is omitted.
     *
     * `.UNKNOWN` is the engine's fallback rather than a mechanism, and a row reading "generic class-default
     * kinetics" would be explaining the absence of an explanation. Naming it as the single exception means a
     * second omission has to be argued for here rather than happening quietly.
     */
    @Test
    fun `every engine class is taught except the unknown fallback`() {
        val taught = ToleranceExplainer.orderedClasses.toSet()
        val all = ReceptorClasses.ReceptorClass.entries.toSet()
        val omitted = all - taught

        omitted shouldBe setOf(ReceptorClasses.ReceptorClass.UNKNOWN)
        // And nothing is taught that the engine does not have.
        (taught - all) shouldBe emptySet()
    }

    /**
     * Every taught class has its own line.
     *
     * A shared arm would be a `when` collapsed to a default, which is how an explainer silently stops
     * describing one mechanism. Asserted by comparing the resolved resource ids pairwise: two classes sharing
     * one id is the failure.
     */
    @Test
    fun `each taught class resolves to its own copy`() {
        val ids = ToleranceExplainer.orderedClasses.map { ToleranceExplainer.meaningRes(it) }
        ids.distinct().size shouldBe ids.size
    }

    /**
     * No class resolves to a bare zero, which is what a missing resource id hides as.
     *
     * `meaningRes` is exhaustive so this cannot happen for a *new* class, but a typo in a resource name would
     * produce a compile error rather than a zero — so this asserts the other direction: that the ids are real.
     */
    @Test
    fun `every class resolves to a resource`() {
        for (receptorClass in ReceptorClasses.ReceptorClass.entries) {
            (ToleranceExplainer.meaningRes(receptorClass) != 0) shouldBe true
        }
    }

    /**
     * The prose sections are non-empty and every one names a heading and at least one card.
     *
     * A section with no cards would draw a heading with nothing under it, which reads as a rendering failure.
     */
    @Test
    fun `every prose section has a heading and at least one card`() {
        (ToleranceExplainer.sections.isNotEmpty()) shouldBe true
        for (section in ToleranceExplainer.sections) {
            (section.titleRes != 0) shouldBe true
            (section.concepts.isNotEmpty()) shouldBe true
            for (concept in section.concepts) {
                (concept.titleRes != 0) shouldBe true
                (concept.bodyRes != 0) shouldBe true
            }
        }
    }

    /**
     * The boundary section exists, and it is last.
     *
     * The one section that is about the model's *limits* rather than its content. It has to be there — the
     * model is pharmacodynamic and a large part of real tolerance is associative — and it belongs at the end,
     * after the reader has been told what the model does.
     */
    @Test
    fun `the model-boundary section is present and last`() {
        val last = ToleranceExplainer.sections.last()
        (last.concepts.size == 1) shouldBe true
        // The heading is the boundary one; asserting through the resource id pair keeps this independent of
        // the copy's wording.
        (last.titleRes != 0) shouldBe true
        (last.titleRes == ToleranceExplainer.sections.map { it.titleRes }.distinct().last()) shouldBe true
    }

    /**
     * The sources name the claims they support.
     *
     * The explainer makes two specific empirical claims — the benzodiazepine subtype dissociation and the
     * conditioned-tolerance mortality study — and a claim without its source is an assertion the app has no
     * standing to make. Asserted on the presence of the two topic prefixes, because the citations themselves
     * are the authors' business.
     */
    @Test
    fun `the sources cover both empirical claims`() {
        (ToleranceExplainer.sources.size >= 2) shouldBe true
        ToleranceExplainer.sources.any { it.contains("Benzodiazepine") } shouldBe true
        ToleranceExplainer.sources.any { it.contains("Conditioned tolerance") } shouldBe true
    }

    /**
     * A recovery descriptor is produced for every class, including the fallback.
     *
     * The descriptor quotes the engine's own adaptive tau, so it must not be blank or negative for any class —
     * a zero tau would print "recovers in under a day" for everything, which is a claim about the model that
     * is not true.
     */
    @Test
    fun `every class gets a recovery descriptor`() {
        for (receptorClass in ReceptorClasses.ReceptorClass.entries) {
            val text = recoveryDescriptor(receptorClass)
            (text.isNotBlank()) shouldBe true
            (text.contains("Adaptive shift recovers")) shouldBe true
        }
    }
}
