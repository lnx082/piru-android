package glass.kagerou.piru.engine

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The class-pair → sentence table.
 *
 * Ported from the same-named suite upstream, which exists for one reason: the
 * sentence is copy and the rule is data, and the two are written in different
 * files by different processes. This is the surface that notices when they drift.
 *
 * The *count* is pinned here rather than derived, because 87 is the number of
 * pairs Piru has adjudicated and the number the bundled `interaction_rules` rows
 * from `piru-curated` is expected to match. The cross-check against the real
 * table — that every one of these keys is a curated row, and that the row's own
 * note is this exact sentence — needs the database and lives in `:core:substance`
 * (`InteractionReadsTest`).
 */
class InteractionRuleCopyTest {

    @Test
    fun `the table holds eighty-seven pairs, all distinct`() {
        // 87 = the curated layer's own count. Adding a pair is a curation act;
        // this number moving should be a decision, not a side effect.
        InteractionRuleCopy.table.size shouldBe 87
    }

    @Test
    fun `the key is order-independent and joins the raw values sorted`() {
        val a = InteractionRuleCopy.key(DrugClass.OPIOID, DrugClass.BENZODIAZEPINE)
        val b = InteractionRuleCopy.key(DrugClass.BENZODIAZEPINE, DrugClass.OPIOID)
        a shouldBe "benzodiazepine|opioid"
        b shouldBe a
        // The camelCase raw values are used verbatim, so the key matches the
        // `interaction_rules` spelling rather than a Kotlin enum name.
        InteractionRuleCopy.key(DrugClass.ALPHA2_AGONIST, DrugClass.OPIOID) shouldBe "alpha2Agonist|opioid"
        InteractionRuleCopy.key(DrugClass.BETA_BLOCKER, DrugClass.STIMULANT) shouldBe "betaBlocker|stimulant"
    }

    @Test
    fun `no sentence is written against a class that participates in no rule`() {
        // `other` and `supplement` are interaction-invisible by definition, so a
        // sentence keyed on either could never be reached.
        val unruledEdges = InteractionRuleCopy.table.keys.filter { key ->
            key.split("|").any { raw -> DrugClass.fromRaw(raw) in DrugClass.unruled }
        }
        unruledEdges shouldBe emptyList()
    }

    @Test
    fun `every sentence is non-empty prose`() {
        InteractionRuleCopy.table.values.forEach { sentence ->
            (sentence.length > 20) shouldBe true
            sentence.isBlank() shouldBe false
        }
    }

    @Test
    fun `a pair Piru has not adjudicated has no sentence`() {
        // Falls through to the database row's own English note; not a gap to fill
        // mechanically.
        InteractionRuleCopy.note(DrugClass.CANNABINOID, DrugClass.GABAPENTINOID) shouldBe null
        InteractionRuleCopy.note(DrugClass.LITHIUM, DrugClass.BARBITURATE) shouldBe null
    }

    @Test
    fun `the opioid and benzodiazepine sentence is the one a reader sees`() {
        InteractionRuleCopy.note(DrugClass.OPIOID, DrugClass.BENZODIAZEPINE) shouldBe
            "Combined respiratory depression — the leading cause of overdose death."
        // Looked up in either order, since the key sorts.
        InteractionRuleCopy.note(DrugClass.BENZODIAZEPINE, DrugClass.OPIOID) shouldBe
            "Combined respiratory depression — the leading cause of overdose death."
    }

    @Test
    fun `a pair written back-to-front still resolves`() {
        // Declared as (.ghb, .benzodiazepine) upstream; the key sorts to
        // `benzodiazepine|ghb`.
        InteractionRuleCopy.note(DrugClass.BENZODIAZEPINE, DrugClass.GHB) shouldBe
            "Severe respiratory depression — both are GABAergic depressants."
    }

    @Test
    fun `a pair the copy table does not name is identified by its own rule key`() {
        // The mechanism and prominence derivations read the key, so a result built
        // from the database's own note still needs a key that names the classes.
        val key = InteractionRuleCopy.key(DrugClass.STIMULANT, DrugClass.STIMULANT)
        key shouldBe "stimulant|stimulant"
        InteractionRuleCopy.note(DrugClass.STIMULANT, DrugClass.STIMULANT) shouldBe
            "Cardiovascular strain — combined stimulants increase heart rate and blood pressure."
    }
}
