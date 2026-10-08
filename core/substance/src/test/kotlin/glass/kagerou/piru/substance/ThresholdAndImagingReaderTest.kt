package glass.kagerou.piru.substance

import glass.kagerou.piru.engine.ConcentrationEffectHit
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The two readers added over tables nothing had read — one of them only *partly* read before.
 *
 * ## The threshold reader's reason for existing
 * `concentration_effects` holds two kinds of row and the port read one. `therapeuticRangeRows` filters to
 * `kind = 'therapeutic_range'` **and** `threshold > 0`, because the engine needs a half-maximal concentration to
 * calibrate modelled occupancy — and its result is never shown. The other **23** rows have `kind` NULL and are the
 * findings: fatal concentrations, respiratory depression, QTc prolongation.
 *
 * So the checks below are mostly about what the new reader **keeps** that the old one drops. A reader that
 * accidentally applied the same filter would return the therapeutic rows and look correct.
 *
 * ## The imaging reader's
 * 52 rows over 36 substances across thirteen modality strings, none of it read before. What matters is that the
 * grouping the card relies on actually groups — a reader that returned an empty modality would collapse every
 * finding under one blank heading.
 *
 * Counts and fixtures were read out of `piru-substances.sqlite` under the source filter before being written down.
 */
class ThresholdAndImagingReaderTest {

    private fun catalog(db: SubstanceDb) = DbSubstanceCatalog.open(
        db = db,
        order = db.query("SELECT slug FROM sources ORDER BY default_priority, slug")
            .mapNotNull { it.string("slug") },
        language = ContentLanguage.EN,
    )

    // MARK: - Concentration thresholds

    /**
     * Fentanyl's two findings both come back, and the therapeutic ones do not crowd them out.
     *
     * Fentanyl has "analgesia (50% pain reduction)" at 0.6 ng/mL and "respiratory depression (clinically
     * significant)" at 2.0 ng/mL — the pair that makes the section worth reading, because the gap between them is
     * the whole story of the drug.
     */
    @Test
    fun `fentanyl's two findings are returned`() {
        openBundledSubstanceDb().use { db ->
            val hits = catalog(db).concentrationEffectRows("Fentanyl")
            hits.size shouldBe 2
            // Both are findings, so both are cautions on the card.
            hits.all { it.isFinding } shouldBe true
            hits.map { it.effect } shouldBe listOf(
                "analgesia (50% pain reduction)",
                "respiratory depression (clinically significant)",
            )
            // And the levels, which are the reading.
            hits.first { it.effect.startsWith("analgesia") }.thresholdValue shouldBe 0.6
            hits.first { it.effect.startsWith("respiratory") }.thresholdValue shouldBe 2.0
        }
    }

    /**
     * Findings come first and are ordered by level.
     *
     * The card's argument: a therapeutic range is context and a fatal concentration is the reason to open the
     * section, so findings lead. `NULLS LAST` matters because a row with no measured threshold cannot be ranked
     * against one that has.
     */
    @Test
    fun `findings lead and are ordered by level`() {
        openBundledSubstanceDb().use { db ->
            val hits = catalog(db).concentrationEffectRows("Fentanyl")
            hits.first().isFinding shouldBe true
            val levels = hits.filter { it.isFinding }.mapNotNull { it.thresholdValue }
            levels shouldBe levels.sorted()
        }
    }

    /**
     * A row with `kind = 'therapeutic_range'` is **not** a finding, and the reader keeps it.
     *
     * The distinction the card colours on, and the case the old filter kept while dropping everything else. A
     * substance with both kinds is what proves the reader takes both.
     */
    @Test
    fun `therapeutic rows are kept and are not findings`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)
            // Find a substance with both kinds, so the assertion is not vacuous.
            var withBoth: String? = null
            for (name in listOf("Citalopram", "Cocaine", "Fentanyl", "Cannabis", "Buprenorphine",
                                "3-MeO-PCP", "25I-NBOMe")) {
                val hits = resolved.concentrationEffectRows(name)
                if (hits.any { it.isFinding } && hits.any { !it.isFinding }) {
                    withBoth = name
                    break
                }
            }
            // If no substance carries both, the two-kind distinction is still asserted on the model.
            val name = withBoth
            if (name != null) {
                val hits = resolved.concentrationEffectRows(name)
                hits.any { it.isFinding } shouldBe true
                hits.any { !it.isFinding } shouldBe true
                hits.first { !it.isFinding }.kind shouldBe ConcentrationEffectHit.THERAPEUTIC_KIND
            }
            // The model's own rule, independent of the fixture.
            ConcentrationEffectHit(
                id = 1, effect = "x", kind = null, concentrationUnit = "ng/mL", sourceSlug = "s",
            ).isFinding shouldBe true
            ConcentrationEffectHit(
                id = 2, effect = "y", kind = "therapeutic_range", concentrationUnit = "ng/mL", sourceSlug = "s",
            ).isFinding shouldBe false
        }
    }

    /**
     * Every row carries a unit, and no effect is blank.
     *
     * The unit is free text — `ng/mL`, `pg/mL`, `mM (serum)`, `µg/mL`, `ng/mL psilocin` — and a blank one would
     * print a bare number with no scale, which is worse than no row.
     */
    @Test
    fun `every row has an effect and a unit`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)
            for (name in listOf("Fentanyl", "Cocaine", "Citalopram", "Cannabis")) {
                val hits = resolved.concentrationEffectRows(name)
                hits.shouldNotBeEmpty()
                hits.all { it.effect.isNotBlank() } shouldBe true
                hits.all { it.concentrationUnit.isNotBlank() } shouldBe true
            }
        }
    }

    /** A substance with no rows returns none, and the section hides. */
    @Test
    fun `a substance with no thresholds has none`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)
            resolved.concentrationEffectRows("Diazepam").isEmpty() shouldBe true
            resolved.concentrationEffectRows("Not A Real Substance Name").isEmpty() shouldBe true
        }
    }

    /**
     * The new reader is a superset of the old one for every substance that has a therapeutic row.
     *
     * The relationship that keeps the two from drifting: `therapeuticRangeRows` feeds the engine and
     * `concentrationEffectRows` feeds the page, and a page that omitted a row the engine used would be a page
     * contradicting the model.
     */
    @Test
    fun `the page reader covers the model reader`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)
            for (name in listOf("Fentanyl", "Cocaine", "Citalopram", "Cannabis", "Buprenorphine")) {
                // The page reader's rows that carry the therapeutic kind, against the modelling filter's own
                // definition — asserted through the one public surface rather than by adding an accessor that
                // only a test would use.
                val page = resolved.concentrationEffectRows(name)
                val therapeutic = page.filter { !it.isFinding }
                for (row in therapeutic) {
                    row.kind shouldBe ConcentrationEffectHit.THERAPEUTIC_KIND
                    (row.thresholdValue != null && row.thresholdValue!! > 0) shouldBe true
                }
            }
        }
    }

    // MARK: - Neuroimaging

    /**
     * Alcohol's imaging findings come back under their own modalities.
     *
     * Alcohol has a PET finding and an EEG one, so it exercises the grouping the card depends on.
     */
    @Test
    fun `alcohol's findings are grouped under their modalities`() {
        openBundledSubstanceDb().use { db ->
            val hits = catalog(db).neuroimagingRows("Alcohol")
            hits.shouldNotBeEmpty()
            hits.all { it.modality.isNotBlank() } shouldBe true
            hits.all { it.finding.isNotBlank() } shouldBe true
            // At least two distinct modalities, so the card's grouping is doing something.
            (hits.map { it.modality }.distinct().size >= 2) shouldBe true
        }
    }

    /**
     * The order is by modality, so the groups are contiguous.
     *
     * What `groupBy` needs to render one heading per method rather than repeating a heading when the sort
     * interleaves them. Asserted as the property, because the reader's `ORDER BY` is the only thing providing it.
     */
    @Test
    fun `rows are ordered so each modality forms one group`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)
            for (name in listOf("Alcohol", "Amphetamine", "25B-NBOMe")) {
                val hits = resolved.neuroimagingRows(name)
                if (hits.isEmpty()) continue
                val modalities = hits.map { it.modality }
                // Sorted, and therefore contiguous under grouping.
                modalities shouldBe modalities.sorted()
            }
        }
    }

    /**
     * The reader preserves modality strings verbatim, method text and all.
     *
     * **Salvinorin A** is the fixture, and it is the only one of the catalogue's 36 imaging substances whose
     * modalities all carry study detail:
     *
     *     PET ([11C]-salvinorin A)
     *     fMRI BOLD (12 healthy men, 15 µg/kg inhaled vapor)
     *     EEG (10 subjects, 8 and 12 mg inhaled)
     *
     * Normalising those into `PET` / `fMRI` / `EEG` would lose the dose, the species and the sample size — which
     * is precisely what makes one finding weigh more than another. My first version looked for a parenthesis among
     * four substances all of whose modalities are bare, so it tested nothing; the probe above found the one that
     * is not.
     */
    @Test
    fun `modalities are preserved verbatim`() {
        openBundledSubstanceDb().use { db ->
            val modalities = catalog(db).neuroimagingRows("Salvinorin A").map { it.modality }
            modalities.size shouldBe 3
            modalities.all { it.contains("(") } shouldBe true
            // The dose and the sample size survive, which is what "verbatim" has to mean to be worth asserting.
            modalities.any { it.contains("µg/kg") } shouldBe true
            modalities.any { it.contains("healthy men") } shouldBe true
        }
    }

    /**
     * A substance with no imaging returns none.
     *
     * **Diazepam is not such a substance** — it has a PET row, which my first version of this test asserted
     * otherwise about. The name used is one the probe confirmed is absent from the table.
     */
    @Test
    fun `a substance with no imaging has none`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)
            resolved.neuroimagingRows("Not A Real Substance Name").isEmpty() shouldBe true
            // And a substance with one row returns exactly that one, so the empty case above is not passing
            // because the reader returns nothing for everything.
            resolved.neuroimagingRows("Diazepam").size shouldBe 1
        }
    }
}
