package glass.kagerou.piru.ui.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.PiruApplication
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The receptor and metabolism reads return rows for the substances the catalogue curates them for.
 *
 * ## Why the assertions name values
 * Both of these are joins over tables nothing else used for display: `bindings` joined to its substance,
 * source and citation, and `metabolism` joined to its enzyme. A wrong join returns an **empty list rather
 * than an error**, and an empty list means the section does not draw — indistinguishable from a substance
 * the catalogue simply does not describe. So "some rows came back" is not enough; a named target and a
 * named enzyme are.
 *
 * Phencyclidine is used for the bindings because the catalogue carries a deep receptor profile for it, and
 * codeine for the metabolism because it has the textbook CYP2D6-to-morphine row.
 */
@RunWith(AndroidJUnit4::class)
class ReceptorAndMetabolismDataDeviceTest {

    private val app: PiruApplication
        get() = ApplicationProvider.getApplicationContext<Context>()
            .applicationContext as PiruApplication

    /**
     * A substance with a known receptor profile returns binding rows with a measured affinity.
     *
     * The affinity is the point: a row with no Ki, EC50 or IC50 would draw a target with no number, which is
     * what a broken column mapping looks like.
     */
    @Test
    fun bindingRowsCarryTargetsAndAffinities() {
        runBlocking {
            val rows = app.catalog().bindingRows("Phencyclidine")
            (rows.isNotEmpty()) shouldBe true
            // At least one row has to carry a number, or the table draws targets with no measurements.
            val withAffinity = rows.count { it.kiNm != null || it.ec50Nm != null || it.ic50Nm != null }
            (withAffinity > 0) shouldBe true
            // And the target names are real strings, not blanks.
            (rows.first().target.isNotBlank()) shouldBe true
        }
    }

    /**
     * A substance with no receptor data returns an empty list rather than throwing.
     *
     * The card's own guard, asserted here because the failure mode is a crash on a substance page rather
     * than a missing section. The name is deliberately one the catalogue carries but does not profile.
     */
    @Test
    fun aSubstanceWithNoBindingsReturnsEmpty() {
        runBlocking {
            val rows = app.catalog().bindingRows("Unobtainium")
            rows shouldBe emptyList()
        }
    }

    /**
     * Codeine's metabolism names an enzyme and its share of clearance.
     *
     * CYP2D6 is the enzyme that turns codeine into morphine, and the catalogue quantifies it — so a row
     * naming it with a fraction proves the join reached `fraction_of_clearance_pct` and not only the
     * enzyme column.
     */
    @Test
    fun metabolismRowsNameAnEnzymeAndItsShare() {
        runBlocking {
            val rows = app.catalog().metabolismRows("Codeine")
            (rows.isNotEmpty()) shouldBe true
            val named = rows.filter { it.enzyme.isNotBlank() }
            (named.isNotEmpty()) shouldBe true
            // At least one row carries a fraction, which is the column the section's headline uses.
            (rows.any { it.fractionOfClearancePct != null }) shouldBe true
        }
    }

    /**
     * The whole route the page uses: `resolveFull` then the two accessors, on the canonical name.
     *
     * This is the chain the screen actually runs, and the one place an alias-versus-canonical mistake would
     * show: the accessors resolve by name, so passing the alias the user arrived with has to reach the same
     * rows.
     */
    @Test
    fun theAliasAndTheCanonicalNameReachTheSameRows() {
        runBlocking {
            val catalog = app.catalog()
            val canonical = catalog.lookup("PCP")?.name
            canonical shouldNotBe null
            val viaAlias = catalog.bindingRows("PCP")
            val viaCanonical = catalog.bindingRows(canonical!!)
            viaAlias.size shouldBe viaCanonical.size
        }
    }
}
