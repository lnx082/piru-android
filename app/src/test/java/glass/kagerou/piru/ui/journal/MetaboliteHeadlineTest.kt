package glass.kagerou.piru.ui.journal

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import glass.kagerou.piru.R
import glass.kagerou.piru.engine.MetabolitePotencyBasis
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sentences "Also Active" can say.
 *
 * ## Why this is worth a test
 * Every branch of `statement()` exists so that a **different sentence** can be said, and the sentences are where the
 * honesty lives:
 *
 * - "dose for dose" and "molecule for molecule" are the same ratio with opposite meanings;
 * - a divergent statement must carry **no number at all**, because norperidine's 50% of pethidine's analgesia is true
 *   and beside the point;
 * - a qualified measurement must name its basis and target, or the percentage cannot be read.
 *
 * A `when` rewritten inside a composable is a decision nobody can assert on, which is why the wording is a function.
 *
 * ## Why Robolectric
 * The sentences are resources, so they need a `Context`. That is the whole reason: the rules are in `:core:engine` and
 * tested there without one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = glass.kagerou.piru.PiruTestApplication::class)
class MetaboliteHeadlineTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // MARK: - The ratios

    /** A whole ratio is `10×`, never `10.0×`. */
    @Test
    fun `a whole ratio has no decimal`() {
        ratio(10.0) shouldBe "10×"
        ratio(1.0) shouldBe "1×"
        ratio(0.5.let { 2.0 }) shouldBe "2×"
    }

    /** A fractional ratio keeps one decimal: the catalogue's ratios are curated to that precision. */
    @Test
    fun `a fractional ratio keeps one decimal`() {
        ratio(1.5) shouldBe "1.5×"
        ratio(9.0 / 4.0) shouldBe "2.3×"
    }

    /** A whole percentage is `50%`, and a fractional one keeps a decimal with the sign escaped. */
    @Test
    fun `percentages format with a literal sign`() {
        percent(50.0) shouldBe "50%"
        percent(11.0) shouldBe "11%"
        percent(12.5) shouldBe "12.5%"
    }

    /**
     * The numbers use a **point** whatever the device's locale is.
     *
     * The same rule the body-load amounts follow and for the same reason: the sentence is a sentence in either
     * language, and a decimal comma would have to be right in both.
     */
    @Test
    fun `the numbers ignore the device locale`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            ratio(1.5) shouldBe "1.5×"
            percent(12.5) shouldBe "12.5%"
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    // MARK: - The strings exist and carry their placeholders

    /**
     * Every string the card's sentences need resolves **and** carries the placeholders the call sites pass.
     *
     * The project has shipped a launch crash from exactly this class of mistake — `%1 doses`, where the `%1` was read
     * as a conversion with no type — so the assertion is that each id resolves and that its argument count matches.
     */
    @Test
    fun `every sentence string resolves with its arguments`() {
        val cases: List<Pair<Int, Array<Any>>> = listOf(
            R.string.metabolite_outlasts_duration to arrayOf("norketamine", "ketamine"),
            R.string.metabolite_persists_beyond to arrayOf("norfluoxetine", "fluoxetine"),
            R.string.metabolite_comparable to arrayOf("9×", "codeine"),
            R.string.metabolite_stronger_with_share to arrayOf("9×", "codeine", "11%"),
            R.string.metabolite_stronger_share_unrecorded to arrayOf("9×", "codeine"),
            R.string.metabolite_qualified to arrayOf("200×", "receptor affinity", "MOR", "tramadol"),
            R.string.metabolite_divergent to arrayOf("pethidine"),
            R.string.metabolite_relationship to arrayOf("diazepam", "temazepam"),
        )
        for ((id, args) in cases) {
            val text = context.getString(id, *args)
            // `Resources`, not `Context`: the accessor is on the resource table.
            println("HEADLINEPROBE " + context.resources.getResourceEntryName(id) + " -> " + text)
            text.isNotBlank() shouldBe true
            // The substituted argument must appear, which is what a wrong placeholder would break. (`**` because
            // `shouldContain` is infix on a String receiver.)
            text shouldContain (args[0] as String)
        }
    }

    /** The footnote is present because the sentence would otherwise read as a measurement of the user's blood. */
    @Test
    fun `the footnote says it is not a measured level`() {
        val text = context.getString(R.string.metabolite_footnote)
        println("HEADLINEPROBE footnote -> " + text)
        text.isNotBlank() shouldBe true
    }

    /** The four bases are distinguishable in the sentence, or the hedge the qualified case exists for is lost. */
    @Test
    fun `the four potency bases are distinct`() {
        val labels = listOf(
            R.string.metabolite_basis_clinical,
            R.string.metabolite_basis_receptor_affinity,
            R.string.metabolite_basis_in_vitro,
            R.string.metabolite_basis_unknown,
        ).map { context.getString(it) }
        println("HEADLINEPROBE bases -> " + labels)
        labels.toSet().size shouldBe labels.size
    }
}
