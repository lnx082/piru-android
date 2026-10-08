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
 * A substance's class slug reaches `Substance`, which is the link the substance page draws.
 *
 * ## Why this is a device spec rather than a JVM one
 * It reads the bundled catalogue, which the JVM harness does not have. More to the point, the chain it
 * asserts is the one that was broken for the whole life of the port:
 *
 * `substance_classes` -> `BrowseInfo.classContextSlug` -> `Substance.classContextSlug` -> a tappable
 * class name on the page -> `PushRoute.DrugClass` -> the write-up.
 *
 * Every link in that chain is a place the slug can be dropped, and dropping it is silent: the page
 * draws a plain grey category label instead of a link, which looks exactly like a substance that
 * genuinely has no class. So the assertion is that three well-known substances come back with a
 * non-null slug, and that the slug resolves to a write-up.
 */
@RunWith(AndroidJUnit4::class)
class SubstanceClassLinkDeviceTest {

    private val app: PiruApplication
        get() = ApplicationProvider.getApplicationContext<Context>()
            .applicationContext as PiruApplication

    /**
     * Three substances the catalogue classifies come back classified.
     *
     * The values are read out of the shipped catalogue rather than guessed: ketamine is an
     * arylcyclohexylamine, MDMA a methylenedioxy-entactogen, caffeine a nootropic. Asserting the exact
     * slugs rather than "not null" is what makes this catch a mapping wired to the wrong column.
     */
    @Test
    fun classifiedSubstancesCarryTheirClassSlug() {
        // A block body, not = runBlocking { ... }: that form returns the lambda's value, and JUnit 4
        // requires a void test method. The runner refused to instantiate the class rather than failing
        // an assertion, which is at least loud.
        runBlocking {
        val catalog = app.catalog()
        catalog.lookup("Ketamine")?.classContextSlug shouldBe "arylcyclohexylamines"
        catalog.lookup("MDMA")?.classContextSlug shouldBe "methylenedioxy-entactogens"
        catalog.lookup("Caffeine")?.classContextSlug shouldBe "nootropics-mixed"
        }
    }

    /**
     * The slug resolves to a write-up with members, which is what the link leads to.
     *
     * The last link in the chain and the one a user would notice: a slug that reaches a screen saying
     * "not in the catalogue" is a link that should not have been drawn.
     */
    @Test
    fun theClassSlugResolvesToAWriteUpWithMembers() {
        runBlocking {
        val catalog = app.catalog()
        val writeUp = catalog.classContext("arylcyclohexylamines")
        writeUp shouldNotBe null
        // The members come through the join, so a non-empty list proves the join rather than only the
        // class row.
        (writeUp!!.siblings.isNotEmpty()) shouldBe true
        }
    }
}
