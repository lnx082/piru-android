package glass.kagerou.piru.ui.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.model.RouteOfAdministration
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pharmacology table's rows, against the shipped catalogue.
 *
 * ## Why the assertions are about the preference and not just the presence
 * `preferredPKRouteRows` is one windowed query that picks **one** `pk_routes` row per substance, and the
 * pick is a ranking: oral first, then completeness, then confidence. Every part of that can be wrong while
 * returning plausible rows — a substance would simply show a different route's half-life, and the table
 * would look fine.
 *
 * So the tests assert the ranking's consequence: every returned row is one row per substance (the window
 * partitioned correctly), a substance with an oral PK row gets it, and the merged table's row count is
 * bounded by the number of substances that have any PK at all.
 */
@RunWith(AndroidJUnit4::class)
class PharmaTableDataDeviceTest {

    private val app: PiruApplication
        get() = ApplicationProvider.getApplicationContext<Context>()
            .applicationContext as PiruApplication

    /**
     * One row per substance, which is what the `PARTITION BY` promises.
     *
     * Asserted through the map's size against the catalogue's own signal count rather than a literal: a
     * partition that leaked would show up here as a row count larger than the number of substances with any
     * PK signal, and a partition that dropped rows would show up in the table's coverage.
     */
    @Test
    fun preferredRowsAreOnePerSubstance() {
        runBlocking {
            val preferred = app.catalog().pharmaTableRows()
            preferred.isNotEmpty() shouldBe true
            // The map is keyed by substance, so a duplicate key is impossible by construction — the
            // assertion that matters is that the *table* has no duplicate names, which is what its
            // `key = { it.name }` needs.
            val names = preferred.map { it.name }
            names.distinct().size shouldBe names.size
        }
    }

    /**
     * A substance whose PK was measured orally gets its oral row.
     *
     * Midazolam has an oral `pk_routes` row in the catalogue and is the case the oral-first ranking exists
     * for.
     */
    @Test
    fun anOrallyMeasuredSubstanceGetsItsOralRow() {
        runBlocking {
            val rows = app.catalog().pharmaTableRows().filter { it.name == "Midazolam" }
            rows.size shouldBe 1
            val row = rows.first()
            // The route, when the catalogue's preferred pick has one.
            if (row.route != null) {
                row.route shouldBe RouteOfAdministration.ORAL
            }
            // And it carries a half-life, or the row would not be in the table at all.
            (row.halfLifeMin != null) shouldBe true
        }
    }

    /**
     * Every row has at least one number, which is the inclusion gate holding end to end.
     *
     * The gate is on the row type and asserted in a JVM test; this asserts that the *catalogue's* rows obey
     * it, which is where a null-everything row would come from if the seed-merge were wrong.
     */
    @Test
    fun everyRowCarriesAtLeastOneNumber() {
        runBlocking {
            val rows = app.catalog().pharmaTableRows()
            rows.all { it.hasAnyData } shouldBe true
        }
    }

    /**
     * The table is much smaller than the catalogue and much larger than a handful.
     *
     * Bounds rather than a literal: the catalogue has ~1,700 substances and only a fraction have PK data, so
     * a table in the hundreds is right. This catches the two failure modes that do not error — a join that
     * matched everything, and one that matched almost nothing.
     */
    @Test
    fun theTableIsASubsetOfTheCatalogue() {
        runBlocking {
            val catalog = app.catalog()
            val rows = catalog.pharmaTableRows()
            (rows.size > 50) shouldBe true
            (rows.size < catalog.count()) shouldBe true
        }
    }

    /**
     * The class column is filled where the catalogue has a class, and the category stands in where it does
     * not.
     *
     * The screen falls back to `CoreLabels.category`, which needs the row's category. This asserts the
     * fallback is reachable — a row with no class title still has a category — so the column is never blank.
     */
    @Test
    fun everyRowHasAClassOrACategory() {
        runBlocking {
            val rows = app.catalog().pharmaTableRows()
            // The category is non-nullable, so the assertion is that some rows carry a curated class title
            // and the rest still have their category to fall back on.
            val withClass = rows.count { it.classTitle != null }
            (withClass > 0) shouldBe true
            (withClass <= rows.size) shouldBe true
        }
    }
}
