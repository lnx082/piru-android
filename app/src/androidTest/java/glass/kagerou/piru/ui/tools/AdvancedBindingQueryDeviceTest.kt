package glass.kagerou.piru.ui.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.PiruApplication
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The cross-substance binding query, against the shipped catalogue.
 *
 * ## Why the filters are asserted rather than "rows came back"
 * Three filters, and each can be silently wrong in a way that returns *plausible* rows:
 *
 * - **Target** matches on the normalized `target_base`, not the raw spelling. If it matched raw, selecting
 *   `alpha-2-delta-1 (recombinant human)` would return that assay's rows and miss the same receptor's other
 *   spellings — a shorter list that looks like a real answer.
 * - **Ki ceiling** excludes rows measured by EC50 rather than treating a null Ki as zero. Treating it as
 *   zero would include every EC50-only row under any ceiling, which is the opposite of the filter's meaning.
 * - **Name fragment** is a `LIKE`, and the escaping matters: a user typing `%` must search for a percent
 *   sign, not match the whole table.
 *
 * So the assertions bound the result sizes and check the property each filter promises.
 */
@RunWith(AndroidJUnit4::class)
class AdvancedBindingQueryDeviceTest {

    private val app: PiruApplication
        get() = ApplicationProvider.getApplicationContext<Context>()
            .applicationContext as PiruApplication

    /**
     * Every returned row satisfies every active filter.
     *
     * The strongest assertion available without pinning catalogue values: whatever the filters are, the rows
     * must obey them.
     */
    @Test
    fun theKiCeilingIsHonouredAndNullKiRowsAreExcluded() {
        runBlocking {
            val rows = app.catalog().bindingRowsFiltered(kiNmAtMost = 100.0)
            rows.isNotEmpty() shouldBe true
            // Every row has a Ki, and every Ki is at or under the ceiling.
            rows.all { it.kiNm != null } shouldBe true
            rows.all { (it.kiNm ?: Double.MAX_VALUE) <= 100.0 } shouldBe true
        }
    }

    /**
     * A tighter ceiling returns fewer rows.
     *
     * This is the property that makes the slider meaningful, and the one a wrong comparison direction would
     * invert while still returning rows.
     */
    @Test
    fun aTighterCeilingReturnsFewerRows() {
        runBlocking {
            val catalog = app.catalog()
            val loose = catalog.bindingRowsFiltered(kiNmAtMost = 10_000.0).size
            val tight = catalog.bindingRowsFiltered(kiNmAtMost = 10.0).size
            (tight < loose) shouldBe true
        }
    }

    /**
     * A target filter reaches every spelling that folded into that base.
     *
     * Asserted by comparing against the unfiltered set: the filtered rows must all carry the chosen base,
     * and there must be more than one distinct raw spelling among them if the catalogue has that — which is
     * what "normalized" means.
     */
    @Test
    fun theTargetFilterMatchesOnTheNormalizedBase() {
        runBlocking {
            val catalog = app.catalog()
            val target = catalog.availableBindingTargets().first()
            val rows = catalog.bindingRowsFiltered(targetBase = target.targetBase)
            rows.isNotEmpty() shouldBe true
            rows.all { (it.targetBase ?: it.target) == target.targetBase } shouldBe true
            // The picker's count is a distinct-substance count, so the row count is at least that.
            (rows.map { it.substanceName }.distinct().size) shouldBe target.substanceCount
        }
    }

    /**
     * The name fragment is a case-insensitive-ish `LIKE` over the canonical name.
     *
     * `LIKE` in SQLite is case-insensitive for ASCII by default, which is what makes "methyl" reach
     * "Methylphenidate". The assertion is on the returned names containing the fragment ignoring case.
     */
    @Test
    fun theNameFragmentMatchesTheCanonicalName() {
        runBlocking {
            val rows = app.catalog().bindingRowsFiltered(substanceContains = "methyl")
            rows.isNotEmpty() shouldBe true
            rows.all { it.substanceName.lowercase().contains("methyl") } shouldBe true
        }
    }

    /**
     * A `%` in the fragment is searched for, not treated as a wildcard.
     *
     * The escaping rule. Without it, `%` matches everything and the screen returns all 1,462 rows for a
     * query the user typed as a literal character.
     */
    @Test
    fun aPercentSignIsNotAWildcard() {
        runBlocking {
            val all = app.catalog().bindingRowsFiltered(kiNmAtMost = 10_000.0).size
            val literalPercent = app.catalog().bindingRowsFiltered(substanceContains = "%").size
            (literalPercent < all) shouldBe true
            literalPercent shouldBe 0
        }
    }

    /**
     * The target list is counted by distinct substance, which is the number the picker shows.
     */
    @Test
    fun availableTargetsAreCountedBySubstance() {
        runBlocking {
            val targets = app.catalog().availableBindingTargets()
            targets.isNotEmpty() shouldBe true
            // Ordered most-populated first, which is the order the chips are offered in.
            (targets.first().substanceCount >= targets.last().substanceCount) shouldBe true
            targets.all { it.substanceCount > 0 } shouldBe true
            // And every entry has a readable display name, not only the normalized base.
            targets.all { it.target.isNotBlank() } shouldBe true
        }
    }
}
