package glass.kagerou.piru.substance

import glass.kagerou.piru.engine.SpectrumLevel
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The strength ladder's reader, over the shipped catalogue.
 *
 * ## Why these checks and not a screenshot
 * Two things here can be wrong while the dial still draws a plausible ladder:
 *
 * 1. **The rung order.** It comes from `band_index`, not from the row order a source happens to have. A ladder
 *    read in the wrong order is still six bars — and a reader who knows the scale from every other substance would
 *    be shown this one upside down, which reads as a substance that gets weaker as the dose rises.
 * 2. **The `freq` ranking.** `top_effects_json` is `{name, freq}` pairs with counts from 1 to 45. Ordered
 *    descending, the band's list says what that strength is *about*; unordered, it is a bag of words with a
 *    number nobody can see.
 *
 * Counts and fixtures were read out of `piru-substances.sqlite` before being written down, after the source
 * filter — the rule this module's reader tests follow, and the mistake that cost the previous reader two fixtures.
 */
class SpectrumLevelReaderTest {

    private fun catalog(db: SubstanceDb) = DbSubstanceCatalog.open(
        db = db,
        order = db.query("SELECT slug FROM sources ORDER BY default_priority, slug")
            .mapNotNull { it.string("slug") },
        language = ContentLanguage.EN,
    )

    /**
     * **Diclazepam** is the fixture: six bands, four of them with warnings, which exercises both JSON columns.
     */
    private val fixture = "Diclazepam"

    /**
     * Every substance with a ladder has the same six rungs, in the same order.
     *
     * The property the dial is built on. Asserted as the literal list rather than as a count, because the *names*
     * are what the reader already knows from every other substance.
     */
    @Test
    fun `the ladder is six rungs in the shared order`() {
        openBundledSubstanceDb().use { db ->
            val levels = catalog(db).spectrumLevels(fixture)
            levels.size shouldBe 6
            levels.map { it.bandName } shouldBe SpectrumLevel.BAND_NAMES
            levels.map { it.bandIndex } shouldBe listOf(0, 1, 2, 3, 4, 5)
        }
    }

    /** The descriptions are the payload and none are blank. */
    @Test
    fun `every rung carries its description`() {
        openBundledSubstanceDb().use { db ->
            catalog(db).spectrumLevels(fixture).all { it.description.isNotBlank() } shouldBe true
        }
    }

    /**
     * The effects are ordered by how often they were reported.
     *
     * The ranking that makes the column worth showing. Asserted as the property — descending — rather than as the
     * fixture's own names, because the names are a source's data and the order is the reader's contract.
     */
    @Test
    fun `effects are ordered by report count`() {
        openBundledSubstanceDb().use { db ->
            val levels = catalog(db).spectrumLevels(fixture)
            val withEffects = levels.filter { it.topEffects.isNotEmpty() }
            withEffects.shouldNotBeEmpty()
            for (level in withEffects) {
                val freqs = level.topEffects.map { it.freq }
                freqs shouldBe freqs.sortedDescending()
            }
        }
    }

    /** The warnings column reaches the model for the rungs that have it, and the upper rungs are marked. */
    @Test
    fun `the upper rungs carry their warnings`() {
        openBundledSubstanceDb().use { db ->
            val levels = catalog(db).spectrumLevels(fixture)
            val upper = levels.filter { it.isUpperRung }
            upper.map { it.bandName } shouldBe listOf("Heavy", "Overdose")
            // Diclazepam has warnings on four of its six rungs, so at least one upper rung has them.
            upper.any { it.warnings.isNotEmpty() } shouldBe true
            // And no warning is blank — a blank line would render as an empty caution.
            levels.flatMap { it.warnings }.all { it.isNotBlank() } shouldBe true
        }
    }

    /**
     * The `isUpperRung` boundary, from both sides.
     *
     * Heavy is the first rung the dial marks. An off-by-one would move the caution colour to Strong — which is a
     * rung people take deliberately — or drop Overdose out of it, which is the one that most needs it.
     */
    @Test
    fun `the upper rungs start at Heavy`() {
        openBundledSubstanceDb().use { db ->
            val levels = catalog(db).spectrumLevels(fixture).associateBy { it.bandName }
            levels.getValue("Strong").isUpperRung shouldBe false
            levels.getValue("Heavy").isUpperRung shouldBe true
            levels.getValue("Overdose").isUpperRung shouldBe true
        }
    }

    /**
     * The six rungs are shared, checked against two more substances.
     *
     * `BAND_NAMES` is a constant in the model rather than a value read from the catalogue, which needs saying: it
     * is not magic data, it is the ladder's *definition* — what makes the dial comparable between substances, and
     * what lets the axis be labelled before anything is loaded. This test is what keeps the definition honest.
     *
     * Alcohol is deliberately not one of the second substances. It is the obvious guess and it has **no** ladder:
     * `spectrum_levels` covers 162 of 1689 substances, chosen by the pipeline rather than by popularity, so every
     * fixture has to be checked like any other.
     */
    @Test
    fun `the shared band names are the catalogue's`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)
            resolved.spectrumLevels("Ketamine").map { it.bandName } shouldBe SpectrumLevel.BAND_NAMES
            resolved.spectrumLevels("MDMA").map { it.bandName } shouldBe SpectrumLevel.BAND_NAMES
        }
    }

    /** A substance with no ladder returns nothing, and the section hides rather than drawing an empty dial. */
    @Test
    fun `a substance with no ladder has no levels`() {
        openBundledSubstanceDb().use { db ->
            catalog(db).spectrumLevels("Not A Real Substance Name") shouldBe emptyList()
        }
    }
}
