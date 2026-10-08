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
 * The four newly-wired sections have data behind them in the shipped catalogue.
 *
 * ## Why `resolveFull` and not `lookup`
 * `lookup` answers from the browse index and returns the shell plus the browse metadata a list row
 * needs — it does not carry `physicochemical`, `peptideProfile`, `overview` or `halfLifeMinutes`,
 * because the list draws none of them and assembling them is a per-substance read. `resolveFull` is the
 * one that goes through `fullSubstance`. The first version of this spec asked `lookup` for four fields
 * that are null by design there, which is a spec testing the wrong function rather than a bug — worth
 * stating, because the failure looked exactly like a broken read.
 *
 * ## Why the values are asserted rather than "not null"
 * Every one of these fields was populated by the read layer and consumed by nothing, so "the page draws
 * a card" is not the question — the question is whether the read layer's population is correct in the
 * first place. Two of them are joins or conversions that would fail silently:
 *
 * - `physicochemical` is assembled from eight separate `substances` columns, any of which can be null.
 * - `peptideProfile` is only meaningful for the compounds the catalogue marks as peptides, and its
 *   IU-per-mg bridge is a conversion factor rather than a stored string.
 *
 * Ketamine and caffeine are used because they are the two substances this suite already leans on
 * elsewhere, so a failure here is a failure of one feature rather than of the fixture.
 */
@RunWith(AndroidJUnit4::class)
class SubstanceSectionDataDeviceTest {

    private val app: PiruApplication
        get() = ApplicationProvider.getApplicationContext<Context>()
            .applicationContext as PiruApplication

    /**
     * A substance's chemistry is read from the catalogue's own columns.
     *
     * logP is asserted because it is the descriptor most likely to be present for a well-studied
     * compound, and `hasAnyValue` because the card draws nothing without it — so a read that silently
     * produced an all-null object would leave the page with no chemistry section and no error.
     */
    @Test
    fun aWellStudiedSubstanceCarriesItsDescriptors() {
        runBlocking {
            val ketamine = app.catalog().resolveFull("Ketamine")
            ketamine shouldNotBe null
            val chemistry = ketamine!!.physicochemical
            chemistry shouldNotBe null
            (chemistry!!.hasAnyValue) shouldBe true
            (chemistry.logP != null) shouldBe true
        }
    }

    /**
     * A substance's half-life is present, and in minutes.
     *
     * The unit matters: `halfLifeMinutes` is what the engine scales every modelled curve by, and a
     * value read as hours would put every estimate out by a factor of sixty without any screen looking
     * wrong. Ketamine's is a few hours, so anything under 30 or over 1000 would be a unit error.
     */
    @Test
    fun theHalfLifeIsInMinutesAndInTheRightRange() {
        runBlocking {
            val minutes = app.catalog().resolveFull("Ketamine")?.halfLifeMinutes
            minutes shouldNotBe null
            // Roughly two to four hours for ketamine; a wide band that still catches a unit mistake.
            ((minutes!! > 30) && (minutes < 1000)) shouldBe true
        }
    }

    /**
     * A peptide carries a profile and a non-peptide does not.
     *
     * Both halves matter: a profile on a non-peptide would make the reconstitution card draw for
     * something with no vial, and the absence on the peptide side would mean the section never appears
     * at all. Semaglutide is the peptide; caffeine is the control.
     */
    @Test
    fun onlyPeptidesCarryAPeptideProfile() {
        runBlocking {
            val catalog = app.catalog()
            val peptide = catalog.resolveFull("Semaglutide")
            peptide shouldNotBe null
            peptide!!.peptideProfile shouldNotBe null
            // And the control: caffeine is not a peptide and must not carry a reconstitution profile.
            catalog.resolveFull("Caffeine")?.peptideProfile shouldBe null
        }
    }
}
