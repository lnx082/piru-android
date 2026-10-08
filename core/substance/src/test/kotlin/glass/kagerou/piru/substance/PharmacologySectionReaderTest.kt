package glass.kagerou.piru.substance

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The three catalogue tables that had no reader until now.
 *
 * ## Why these checks are against the shipped catalogue
 * `downstream_signalling`, `off_targets` and `pharmacogenetics` are three of the largest tables this port shipped
 * and never read — 678, 209 and 305 rows — so there was nothing to be wrong *about*: the columns could have been
 * mis-mapped, the ordering could have been arbitrary, and no screen would have said so. The counts and fixtures
 * below were read out of `piru-substances.sqlite` with `sqlite3` before they were written down, which is the rule
 * the other reader tests in this module state and follow.
 *
 * The counts are **after the source filter**, which the reader applies and which a raw `COUNT(*)` does not. My
 * first version of this file used raw counts and two fixtures were wrong because of it — Ketamine's
 * pharmacogenetics are 6 rows all from `peer-review-primary`, and the substance with CYP2D6 rows is Amphetamine
 * rather than Methadone.
 *
 * ## What each check is for
 * Not "the reader returns something" — that passes for a reader that returns the wrong column in the right shape.
 * Each one pins the thing the section's argument depends on:
 *
 * - signalling is **prose per source**, and the reader drops blank summaries rather than drawing empty rows;
 * - off-targets are ordered **by affinity, tightest first, with the unmeasured last** — that order is the whole
 *   reason the section is readable, and the catalogue has substances whose rows span both cases;
 * - pharmacogenetics is ordered by **gene**, not by source, because the reader scans for their own gene.
 */
class PharmacologySectionReaderTest {

    private fun catalog(db: SubstanceDb) = DbSubstanceCatalog.open(
        db = db,
        order = db.query("SELECT slug FROM sources ORDER BY default_priority, slug")
            .mapNotNull { it.string("slug") },
        language = ContentLanguage.EN,
    )

    /**
     * Ketamine is the fixture for all three: the catalogue's best-covered substance in this area, with 1
     * signalling row, 5 off-target rows (one without an affinity) and 6 pharmacogenetics rows.
     */
    private val fixture = "Ketamine"

    @Test
    fun `downstream signalling reads prose for the substance`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)
            val rows = resolved.downstreamSignallingRows(fixture)

            rows.size shouldBe 1
            val row = rows.single()
            row.summary.isNotBlank() shouldBe true
            row.sourceSlug.isNotBlank() shouldBe true
            // The summary is prose rather than a target name: that is what makes this section different from
            // the binding table, and a reader that returned the target column here would satisfy a count check.
            (row.summary.length > 40) shouldBe true
        }
    }

    @Test
    fun `downstream signalling is empty for a substance the catalogue has none for`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)
            // Astemizole has no signalling row; the section hides rather than drawing an empty card.
            resolved.downstreamSignallingRows("Astemizole").isEmpty() shouldBe true
        }
    }

    /**
     * The off-target order: affinity ascending, then the affinity-less rows.
     *
     * Asserted as the **property** rather than as the fixture's own numbers, because the numbers are a source's
     * measurements and the ordering is the reader's contract. Ketamine has one row with no affinity, so both
     * halves of `NULLS LAST` are exercised by one substance.
     */
    @Test
    fun `off-target rows are ordered by affinity with the unmeasured last`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)
            val rows = resolved.offTargetRows(fixture)

            rows.size shouldBe 5
            val measured = rows.mapNotNull { it.kiOrIc50Nm }
            // The measured ones are in ascending order.
            measured shouldBe measured.sorted()
            // And they all come before any unmeasured one.
            val firstUnmeasured = rows.indexOfFirst { it.kiOrIc50Nm == null }
            if (firstUnmeasured >= 0) {
                rows.drop(firstUnmeasured).all { it.kiOrIc50Nm == null } shouldBe true
            }
            // The fixture is chosen because it has both, so the assertion above is not vacuous.
            (firstUnmeasured > 0) shouldBe true
            (firstUnmeasured < rows.size) shouldBe true
        }
    }

    /** Every off-target row carries a target, and the section drops a row that does not. */
    @Test
    fun `off-target rows all name a target`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)
            val rows = resolved.offTargetRows(fixture)
            rows.all { it.target.isNotBlank() } shouldBe true
            rows.all { it.sourceSlug.isNotBlank() } shouldBe true
        }
    }

    /**
     * Pharmacogenetics is ordered by gene.
     *
     * The order the reader scans by. A section ordered by source priority would move the gene a reader is looking
     * for up and down between substances, which is worse than an arbitrary order because it looks deliberate.
     *
     * The genes are **not** distinct, and that is the catalogue rather than the reader: Ketamine carries four
     * CYP2B6 rows. My first version asserted distinctness here, which was a wrong assumption about the fixture
     * rather than a fact about the reader — the same mistake as asserting a row count without checking the source
     * filter.
     */
    @Test
    fun `pharmacogenetics is ordered by gene`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)
            val rows = resolved.pharmacogeneticRows(fixture)

            rows.size shouldBe 6
            val genes = rows.map { it.gene }
            genes shouldBe genes.sorted()
            // A gene may repeat; what must not happen is the order wandering.
            genes.toSet() shouldBe setOf("CYP2B6", "CYP3A4", "CYP3A5")
        }
    }

    /** And every row carries the prose that is the section's payload. */
    @Test
    fun `pharmacogenetics rows carry their phenotype prose`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)
            resolved.pharmacogeneticRows(fixture).all { it.phenotypeEffects.isNotBlank() } shouldBe true
        }
    }

    /**
     * The two genetic sections read the same rows, which is what keeps them from disagreeing.
     *
     * The CYP2D6 card filters this list rather than reading the table again. Asserted here as the data property
     * the two cards rely on: a substance with a CYP2D6 row has it in this list under that exact name, so a filter
     * on `"CYP2D6"` finds it.
     *
     * The catalogue writes `"Class effect"` as a phenotype for many substances, which is worth recording: it is a
     * real value meaning "this applies to the class rather than to a measured phenotype", and the card shows it as
     * prose like any other.
     */
    @Test
    fun `a CYP2D6 row is present under its own gene name`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)
            // Amphetamine carries three CYP2D6 rows, all from `peer-review-primary`. Methadone was my first
            // choice and has none — it is a well-known 2D6 substrate in the literature but the catalogue files its
            // pharmacogenetics under other genes, which is exactly the kind of assumption a fixture check is for.
            val rows = resolved.pharmacogeneticRows("Amphetamine")
            rows.shouldNotBeEmpty()
            val cyp = rows.filter { it.gene.equals("CYP2D6", ignoreCase = true) }
            cyp.size shouldBe 3
            cyp.all { it.phenotypeEffects.isNotBlank() } shouldBe true
        }
    }

    /** An unknown substance resolves to nothing rather than throwing — the three sections all call these. */
    @Test
    fun `an unknown substance has no rows in any of the three`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)
            val unknown = "Not A Real Substance Name"
            resolved.downstreamSignallingRows(unknown).isEmpty() shouldBe true
            resolved.offTargetRows(unknown).isEmpty() shouldBe true
            resolved.pharmacogeneticRows(unknown).isEmpty() shouldBe true
        }
    }
}
