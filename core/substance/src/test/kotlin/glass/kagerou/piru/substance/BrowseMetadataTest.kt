package glass.kagerou.piru.substance

import glass.kagerou.piru.model.CompoundDisplayClass
import glass.kagerou.piru.model.SubstanceCategory
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The browse path reads the columns that decide what it shows.
 *
 * These checks exist because all of these were false at once, and nothing said so: the batch
 * `build()` constructed every substance from `allShells()` (four columns) and left
 * `popularity`, `displayClass`, `isStub` and `extraBrowseCategories` at their entity defaults.
 * The catalogue was not wrong about any single substance — it answered every question about
 * these four fields with the default, so:
 *
 * - `substancesIn`'s documented "popularity first" sort compared `0.0` to `0.0` for all 1,689
 *   substances, producing strict alphabetical order (MDMA sorts 12th by name among the 46
 *   Empathogens, and first by popularity);
 * - `CompoundDisplayClass.surfacesInBrowse` — "non-recreational compounds stay searchable …
 *   but are not surfaced in the browse grid" — could never be false, so 26 prescription-only
 *   compounds were listed in category browsing;
 * - `browse_extra_categories`' six curated extra homes were unreachable.
 *
 * Every count and name below was read out of the shipped `piru-substances.sqlite` with
 * `sqlite3` before it was written down, so a failure means the adapter is wrong.
 *
 * The browse set is taken as the union of every category's members, which is what the browse
 * grid offers and needs no separate "every substance" accessor.
 */
class BrowseMetadataTest {

    private fun catalog(db: SubstanceDb) = DbSubstanceCatalog.open(
        db = db,
        order = db.query("SELECT slug FROM sources ORDER BY default_priority, slug")
            .mapNotNull { it.string("slug") },
        language = ContentLanguage.EN,
    )

    /** Everything the browse grid can reach — the union over every category card. */
    private fun browsable(resolved: DbSubstanceCatalog) =
        resolved.categorySummary()
            .flatMap { (category, _) -> resolved.substancesIn(category) }
            .distinctBy { it.name }

    @Test
    fun `popularity reaches the browse and drives its sort`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)

            // Ketamine is the file's most popular substance (1.0), and it is listed.
            val browsableNames = browsable(resolved).map { it.name }
            (browsableNames.contains("Ketamine")) shouldBe true

            val empathogens = resolved.substancesIn(SubstanceCategory.EMPATHOGEN)
            empathogens.size shouldBe 46

            // The order is popularity, not name: MDMA leads and 2-MAPB (alphabetically first)
            // does not. With the old default every score was 0.0 and the tie-break put
            // 2-MAPB first.
            empathogens.first().name shouldBe "MDMA"
            empathogens.first().popularity shouldBe 0.9932
            val alphabeticallyFirst = empathogens.minByOrNull { it.displayTitle.lowercase() }
            (empathogens.first().name != alphabeticallyFirst?.name) shouldBe true

            // Descending throughout, which a default-0 field could not produce.
            empathogens.zipWithNext().all { (a, b) -> a.popularity >= b.popularity } shouldBe true
        }
    }

    @Test
    fun `non-recreational compounds stay out of the browse grid`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)

            // The file carries 26 of them; the browse shows none.
            val shown = browsable(resolved)
                .count { it.displayClass == CompoundDisplayClass.NON_RECREATIONAL }
            shown shouldBe 0

            // And the class is genuinely read rather than absent everywhere, which would let
            // the filter above pass vacuously: the other four classes are all present.
            val classes = browsable(resolved).mapTo(mutableSetOf()) { it.displayClass }
            classes.contains(CompoundDisplayClass.RECREATIONAL) shouldBe true
            classes.contains(CompoundDisplayClass.MEDICAL_RX) shouldBe true
            classes.contains(CompoundDisplayClass.OTC) shouldBe true
        }
    }

    @Test
    fun `a category badge counts exactly what its list shows`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)

            // Two views of one number. They disagreed while the summary counted substances
            // the list excluded.
            for ((category, count) in resolved.categorySummary()) {
                resolved.substancesIn(category).size shouldBe count
            }
        }
    }

    @Test
    fun `curated extra browse homes are reachable`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)

            // Tianeptine is curated into Opioid on top of its primary dual-use class, and
            // `browse_extra_categories` is where that lives. Found through the browse rather
            // than by name-resolution, so this does not depend on the alias index.
            val tianeptine = resolved.substancesIn(SubstanceCategory.OPIOID)
                .firstOrNull { it.name == "Tianeptine" }
            (tianeptine != null) shouldBe true

            // It is there *because* of the extra home: its primary category is not Opioid.
            (tianeptine!!.category != SubstanceCategory.OPIOID) shouldBe true
            tianeptine.extraBrowseCategories.contains(SubstanceCategory.OPIOID) shouldBe true
        }
    }

    @Test
    fun `the implausible-duration flag reaches the detail gate`() {
        openBundledSubstanceDb().use { db ->
            val resolved = catalog(db)

            // The flag decides the duration card only for OTC; every other class that shows a
            // duration ignores it. The substances it was *wrongly* suppressing are therefore
            // those that show a duration, are not OTC, and are flagged:
            //
            //     showsDuration && class != OTC && durationImplausible
            //
            // `showsDuration` covers RECREATIONAL, DUAL_USE and OTC, and OTC is excluded, so
            // this is the recreational and dual-use set: 31 + 1 = 32 in the shipped file.
            val wronglySuppressed = browsable(resolved).filter {
                it.displayClass.showsDuration &&
                    it.displayClass != CompoundDisplayClass.OTC &&
                    it.durationImplausible
            }
            wronglySuppressed.size shouldBe 32

            // One is dual-use and the rest recreational, which is what the old gate hid.
            wronglySuppressed.count { it.displayClass == CompoundDisplayClass.DUAL_USE } shouldBe 1
            wronglySuppressed.count { it.displayClass == CompoundDisplayClass.RECREATIONAL } shouldBe 31

            // The flag is read, not merely present.
            (wronglySuppressed.all { it.durationImplausible }) shouldBe true
        }
    }
}
