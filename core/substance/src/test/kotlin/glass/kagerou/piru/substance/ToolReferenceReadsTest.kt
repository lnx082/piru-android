package glass.kagerou.piru.substance

import glass.kagerou.piru.engine.OpioidConvertibility
import glass.kagerou.piru.model.SubstanceCategory
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The three whole-table reads the Tools screens need, against the shipped
 * catalog.
 *
 * Every expectation here was read out of the real database with `sqlite3`
 * before it was written down, so a failure means the resolver is wrong rather
 * than the assertion. The file skips when the database has not been fetched,
 * like the rest of this source set.
 */
class ToolReferenceReadsTest {

    private fun reader(db: SubstanceDb): SubstanceReader = SubstanceReader(
        db = db,
        order = db.query("SELECT slug FROM sources ORDER BY default_priority, slug")
            .mapNotNull { it.string("slug") },
        language = ContentLanguage.EN,
    )

    // MARK: - Opioid MME

    @Test
    fun `the opioid table carries every row, morphine first by rank`() {
        openBundledSubstanceDb().use { db ->
            val rows = reader(db).opioidMmeTable()

            rows.size shouldBe 11
            rows.first().displayName shouldBe "Morphine"
            // The reference standard is 1.0 by definition; a table whose first
            // row is not that has been ranked wrong.
            rows.first().mmePerMg shouldBe 1.0
            rows.first().name shouldBe "morphine"
            rows.last().displayName shouldBe "Buprenorphine"
        }
    }

    @Test
    fun `the un-convertible opioids are present with no factor`() {
        openBundledSubstanceDb().use { db ->
            val rows = reader(db).opioidMmeTable()

            // The whole point of the display read: these three are the ones a
            // reader needs the explanation for, and a converter that dropped
            // them would silently answer "no such opioid".
            val methadone = rows.first { it.displayName == "Methadone" }
            methadone.convertibility shouldBe OpioidConvertibility.NONLINEAR
            methadone.mmePerMg.shouldBeNull()

            val fentanyl = rows.first { it.displayName == "Fentanyl" }
            fentanyl.convertibility shouldBe OpioidConvertibility.TRANSDERMAL
            fentanyl.mmePerMg.shouldBeNull()

            val buprenorphine = rows.first { it.displayName == "Buprenorphine" }
            buprenorphine.convertibility shouldBe OpioidConvertibility.EXCLUDED
            buprenorphine.mmePerMg.shouldBeNull()
        }
    }

    @Test
    fun `the linear factors are the published ones`() {
        openBundledSubstanceDb().use { db ->
            val rows = reader(db).opioidMmeTable().associateBy { it.displayName }

            rows.getValue("Codeine").mmePerMg shouldBe 0.15
            rows.getValue("Oxycodone").mmePerMg shouldBe 1.5
            rows.getValue("Hydromorphone").mmePerMg shouldBe 5.0
            rows.getValue("Tramadol").mmePerMg shouldBe 0.2
            rows.getValue("Tapentadol").mmePerMg shouldBe 0.4
            rows.getValue("Oxymorphone").mmePerMg shouldBe 3.0
        }
    }

    // MARK: - Diazepam equivalents

    @Test
    fun `the benzodiazepine table is name-sorted and carries the parsed pair`() {
        openBundledSubstanceDb().use { db ->
            val rows = reader(db).diazepamEquivalents()

            rows.size shouldBe 32
            rows.map { it.name } shouldBe rows.map { it.name }.sortedBy { it.lowercase() }

            val alprazolam = rows.first { it.name == "Alprazolam" }
            alprazolam.equivalent.doseMg shouldBe 0.5
            alprazolam.equivalent.equivalentDiazepamMg shouldBe 10.0
            alprazolam.diazepamPerMg shouldBe 20.0
            alprazolam.equivalent.displayText shouldBe "Alprazolam - 0.5mg ~=10mg Diazepam."
            alprazolam.equivalent.isCited shouldBe true
        }
    }

    @Test
    fun `the rows Ashton Table 1 omits arrive uncited`() {
        openBundledSubstanceDb().use { db ->
            val rows = reader(db).diazepamEquivalents()

            // Measured on the shipped catalog: **nine** rows carry no citation,
            // not the five the pipeline's own note names — diclazepam,
            // flubromazepam, flubromazolam and pyrazolam are absent from the
            // reference table too and are uncited for the same reason. The
            // converter filters on the flag, so the count is what matters and
            // not the list's membership.
            val uncited = rows.filter { !it.equivalent.isCited }.map { it.name }.sorted()
            uncited shouldContainExactly listOf(
                "Brotizolam", "Diclazepam", "Etizolam", "Flubromazepam",
                "Flubromazolam", "Flutoprazepam", "Midazolam", "Phenazepam", "Pyrazolam",
            )
        }
    }

    // MARK: - Class contexts

    @Test
    fun `a class context resolves by slug and carries its members and citations`() {
        openBundledSubstanceDb().use { db ->
            val context = reader(db).classContext("benzos-z-drugs")

            context.shouldNotBeNull()
            context!!.title shouldBe "Benzodiazepines & Z-drugs"
            context.category shouldBe SubstanceCategory.BENZODIAZEPINE
            context.subtitle shouldBe "classical and designer"
            context.sharedMechanism.shouldNotBeNull()
            context.sharedMechanism!!.isNotBlank() shouldBe true
            context.siblings.size shouldBe 68
            context.references.shouldNotBeEmpty()
        }
    }

    @Test
    fun `a class context resolves by its display name too`() {
        openBundledSubstanceDb().use { db ->
            val context = reader(db).classContext("Arylcyclohexylamines")

            context.shouldNotBeNull()
            context!!.slug shouldBe "arylcyclohexylamines"
            context.category shouldBe SubstanceCategory.DISSOCIATIVE
        }
    }

    @Test
    fun `a name the catalog does not carry resolves to nothing`() {
        openBundledSubstanceDb().use { db ->
            reader(db).classContext("Not A Class").shouldBeNull()
        }
    }

    @Test
    fun `the browse read lists every class, alphabetically by display name`() {
        openBundledSubstanceDb().use { db ->
            val rows = reader(db).classContexts()

            // Pinned to the shipped catalog rather than checked for non-emptiness:
            // the browse screen renders this list whole, so a read that dropped
            // rows would be a class nobody can reach.
            rows.size shouldBe 50
            rows.all { it.title.isNotBlank() } shouldBe true
            rows.first().slug shouldBe "4-substituted-tryptamines-psilocin-family"
            rows.first().title shouldBe "4-Substituted tryptamines"
            // The two Greek-lettered classes sort *last* here: SQLite's `NOCASE`
            // folds ASCII only, so α and β land above every letter. Asserted
            // because it is the one place this ordering is not plain alphabetical.
            rows.last().slug shouldBe "beta-carboline-MAOI"
            rows.map { it.slug }.distinct().size shouldBe 50
        }
    }

    @Test
    fun `a browse row carries what the list renders`() {
        openBundledSubstanceDb().use { db ->
            val row = reader(db).classContexts()
                .first { it.slug == "4-substituted-tryptamines-psilocin-family" }

            row.title shouldBe "4-Substituted tryptamines"
            row.subtitle shouldBe "psilocin family"
            row.category shouldBe SubstanceCategory.PSYCHEDELIC
        }
    }
}
