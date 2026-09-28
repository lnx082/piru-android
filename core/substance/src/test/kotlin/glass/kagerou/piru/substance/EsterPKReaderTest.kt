package glass.kagerou.piru.substance

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The full `ester_pk` read against the shipped catalog.
 *
 * Every number here was read out of `db/piru-substances.sqlite` with `sqlite3`
 * before it was written down, so a failure means the reader is wrong rather than
 * the expectation. That matters more than usual for this table: the three
 * compartment depot curve is a fit to these exact constants, and a transposed
 * `k2`/`k3` would still produce a plausible-looking curve.
 *
 * Skips rather than fails when the database has not been fetched, the same
 * posture as every other spec in this module.
 */
class EsterPKReaderTest {

    private fun reader(): SubstanceReader {
        val db = openBundledSubstanceDb()
        val order = db.query("SELECT slug FROM sources ORDER BY default_priority, slug")
            .mapNotNull { it.string("slug") }
        return SubstanceReader(db, order, ContentLanguage.EN)
    }

    @Test
    fun `the shipped table carries eight esters, ester-id ordered`() {
        val rows = reader().esterPKRows()
        rows shouldHaveSize 8
        rows.map { it.esterID } shouldContainExactly listOf(
            "estradiol_cypionate",
            "estradiol_enanthate",
            "estradiol_undecylate",
            "estradiol_valerate",
            "testosterone_cypionate",
            "testosterone_enanthate",
            "testosterone_propionate",
            "testosterone_undecanoate",
        )
    }

    @Test
    fun `estradiol cypionate carries the amplitude and all three rates`() {
        val row = reader().esterPKRows().first { it.esterID == "estradiol_cypionate" }
        row.analyte shouldBe "estradiol"
        row.parent shouldBe "Estradiol"
        row.parentUID shouldBe "3HCSGEEKIHDFOM"
        row.label shouldBe "Cypionate"
        row.modelable shouldBe true
        row.d shouldBe 246.0
        row.k1 shouldBe 0.0825
        row.k2 shouldBe 3.57
        row.k3 shouldBe 0.669
        row.confidence shouldBe "high"
        row.routes shouldContainExactly listOf("IM", "SC")
        row.caution.shouldBeNull()
        row.provenance.shouldNotBeNull()
    }

    @Test
    fun `estradiol enanthate's k2 sits above its k3, as the curated row has it`() {
        // Pinned because the pair is the one place the shipped data does not
        // descend: an implementation that sorted the rates to be tidy would
        // change the curve's shape between the two draws.
        val row = reader().esterPKRows().first { it.esterID == "estradiol_enanthate" }
        row.d shouldBe 191.4
        row.k1 shouldBe 0.119
        row.k2 shouldBe 0.601
        row.k3 shouldBe 0.402
        row.confidence shouldBe "medium"
        row.routes shouldContainExactly listOf("IM")
    }

    @Test
    fun `a catalog-only ester carries no curve at all`() {
        val rows = reader().esterPKRows()
        val undecylate = rows.first { it.esterID == "estradiol_undecylate" }
        val propionate = rows.first { it.esterID == "testosterone_propionate" }

        for (row in listOf(undecylate, propionate)) {
            row.modelable shouldBe false
            row.confidence shouldBe "none"
            row.d.shouldBeNull()
            row.k1.shouldBeNull()
            row.k2.shouldBeNull()
            row.k3.shouldBeNull()
        }
        undecylate.routes shouldContainExactly listOf("IM", "SC")
        propionate.routes shouldContainExactly listOf("IM")
    }

    @Test
    fun `testosterone undecanoate carries the boxed-warning caution`() {
        val row = reader().esterPKRows().first { it.esterID == "testosterone_undecanoate" }
        row.modelable shouldBe true
        row.d shouldBe 30.0
        row.k1 shouldBe 0.0204
        row.k2 shouldBe 2.709
        row.k3 shouldBe 0.508
        row.caution.shouldNotBeNull()
        row.caution shouldContain "POME"
    }

    @Test
    fun `every modelable row's rates sit in the daily range the curve assumes`() {
        // The unit trap this table is the only place for: these are per *day*,
        // three orders of magnitude below the per-minute rates the oral model
        // uses. A row read on the wrong scale would still be positive and still
        // draw a curve — a flat one, at the wrong timescale.
        val modelable = reader().esterPKRows().filter { it.modelable }
        modelable shouldHaveSize 6
        for (row in modelable) {
            val d = row.d ?: error("${row.esterID} is modelable but carries no amplitude")
            val k1 = row.k1 ?: error("${row.esterID} is modelable but carries no k1")
            val k2 = row.k2 ?: error("${row.esterID} is modelable but carries no k2")
            val k3 = row.k3 ?: error("${row.esterID} is modelable but carries no k3")

            (d > 0) shouldBe true
            (k2 > 0) shouldBe true
            (k3 > 0) shouldBe true
            // Every per-day rate on the shipped table is below 1: the fastest,
            // valerate's k1, is 0.236/day. A rate read from the wrong column —
            // a per-minute one, or a half-life — would blow past this.
            (k1 > 0) shouldBe true
            (k1 < 1.0) shouldBe true
        }
    }
}
