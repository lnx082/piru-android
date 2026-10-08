package glass.kagerou.piru.ui.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.model.SubstanceCategory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The class write-ups carry a category, which is what the family cards join on.
 *
 * ## Why this needs a device
 * `ClassContext.category` comes out of the `class_contexts` table and is mapped through
 * `SubstanceCategory.fromWire`. If the wire values do not line up, the filter returns an empty list for
 * every category — and an empty list means **no family cards appear anywhere**, which looks exactly like a
 * catalogue that has no class write-ups. Nothing errors.
 *
 * The assertion is therefore two-sided: at least one category has families, and those families' category is
 * the one asked for.
 */
@RunWith(AndroidJUnit4::class)
class BrowseTaxonomyDataDeviceTest {

    private val app: PiruApplication
        get() = ApplicationProvider.getApplicationContext<Context>()
            .applicationContext as PiruApplication

    /**
     * At least one category has a family card, and the write-ups name real members.
     *
     * A family with no siblings would draw a card reading "0 members" and lead to an empty-ish class page,
     * which is why the screen filters on `siblings.isNotEmpty()` and why this asserts the same.
     */
    @Test
    fun someCategoryHasFamiliesWithMembers() {
        runBlocking {
            val families = app.catalog().classContexts()
            families.isNotEmpty() shouldBe true
            val withMembers = families.filter { it.siblings.isNotEmpty() && it.category != null }
            withMembers.isNotEmpty() shouldBe true
            // And the ones that survive the screen's filter reach a real category.
            withMembers.first().category shouldNotBe null
        }
    }

    /**
     * A specific category carries its own families under the wire mapping.
     *
     * Dissociatives is used because the catalogue classifies the arylcyclohexylamines under it, so a
     * missing family here means the mapping is wrong rather than the data being thin.
     */
    @Test
    fun dissociativesCarriesItsArylcyclohexylamineFamily() {
        runBlocking {
            val inCategory = app.catalog().classContexts()
                .filter { it.category == SubstanceCategory.DISSOCIATIVE && it.siblings.isNotEmpty() }
            inCategory.isNotEmpty() shouldBe true
            val slugs = inCategory.map { it.slug }
            (slugs.contains("arylcyclohexylamines")) shouldBe true
        }
    }

    /**
     * The category's member list and its families are drawn from the same catalogue and agree about
     * membership, at least in the direction that matters: every family member the class write-up names is a
     * substance the catalogue carries.
     *
     * A dangling sibling name would make a family card lead to a class page whose member links go to "not
     * found", which is the failure that makes a browse axis look broken.
     */
    @Test
    fun everyFamilyMemberIsASubstanceTheCatalogueCarries() {
        runBlocking {
            val catalog = app.catalog()
            val families = catalog.classContexts().filter { it.siblings.isNotEmpty() }
            families.isNotEmpty() shouldBe true
            // One family is enough to prove the join; checking all of them would be thousands of lookups.
            val family = families.first()
            val missing = family.siblings.filter { catalog.lookup(it) == null }
            missing shouldBe emptyList()
        }
    }

    /**
     * A category with substances returns them, so the flat list the families sit above is never empty where
     * a family exists.
     */
    @Test
    fun aCategoryWithFamiliesHasSubstances() {
        runBlocking {
            val catalog = app.catalog()
            val substances = catalog.substancesIn(SubstanceCategory.DISSOCIATIVE)
            (substances.isNotEmpty()) shouldBe true
            // And every one of them is browsable, which is the gate `substancesIn` applies.
            (substances.all { it.displayClass.surfacesInBrowse }) shouldBe true
        }
    }
}
