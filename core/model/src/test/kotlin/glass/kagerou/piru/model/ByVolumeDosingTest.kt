package glass.kagerou.piru.model

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Ported from `PiruTests/ByVolumeDosingTests.swift`.
 *
 * `ByVolumeDosingDBTests` — which asserts the hardcoded density and
 * standard-drink mass against their `by_volume_dosing` column — travels with the
 * read layer that loads those rows.
 */
class ByVolumeDosingTest {

    // MARK: - Worked examples

    @Test
    fun `330 mL can at 5 percent ABV is about 13 g ethanol`() {
        val grams = ByVolumeDosing.grams(volumeML = 330.0, abv = 5.0)
        grams shouldBe (330.0 * 0.05 * ByVolumeDosing.ETHANOL_DENSITY_GRAMS_PER_ML) plusOrMinus 1e-9
        grams shouldBe (13.02 plusOrMinus 0.01)
    }

    @Test
    fun `175 mL wine at 13 percent ABV is about 17 point 9 g ethanol`() {
        ByVolumeDosing.grams(volumeML = 175.0, abv = 13.0) shouldBe (17.95 plusOrMinus 0.01)
    }

    @Test
    fun `44 mL shot at 40 percent ABV is about 13 point 9 g ethanol`() {
        ByVolumeDosing.grams(volumeML = 44.0, abv = 40.0) shouldBe (13.89 plusOrMinus 0.01)
    }

    @Test
    fun `568 mL pint at 5 point 2 percent ABV is about 23 point 3 g ethanol`() {
        ByVolumeDosing.grams(volumeML = 568.0, abv = 5.2) shouldBe (23.30 plusOrMinus 0.01)
    }

    // MARK: - Density override

    @Test
    fun `density override changes the result proportionally`() {
        ByVolumeDosing.grams(volumeML = 100.0, abv = 10.0) shouldBe (7.89 plusOrMinus 1e-9)
        ByVolumeDosing.grams(volumeML = 100.0, abv = 10.0, densityGramsPerML = 1.0) shouldBe (10.0 plusOrMinus 1e-9)
    }

    // MARK: - Guards

    @Test
    fun `non-positive and non-finite inputs yield zero`() {
        // The guard covers NaN and infinity explicitly rather than relying on the
        // comparison: `NaN > 0` is false, but `-Infinity` would then slip through
        // the ordering test and poison every downstream number.
        ByVolumeDosing.grams(volumeML = 0.0, abv = 5.0) shouldBe 0.0
        ByVolumeDosing.grams(volumeML = 330.0, abv = 0.0) shouldBe 0.0
        ByVolumeDosing.grams(volumeML = -100.0, abv = 5.0) shouldBe 0.0
        ByVolumeDosing.grams(volumeML = 330.0, abv = -5.0) shouldBe 0.0
        ByVolumeDosing.grams(volumeML = Double.NaN, abv = 5.0) shouldBe 0.0
        ByVolumeDosing.grams(volumeML = 330.0, abv = Double.POSITIVE_INFINITY) shouldBe 0.0
        ByVolumeDosing.grams(volumeML = 330.0, abv = 5.0, densityGramsPerML = 0.0) shouldBe 0.0
    }

    // MARK: - Inverse

    @Test
    fun `volume inverts grams at a held ABV`() {
        val grams = ByVolumeDosing.grams(volumeML = 330.0, abv = 5.0)
        ByVolumeDosing.volumeML(grams = grams, abv = 5.0) shouldBe (330.0 plusOrMinus 1e-6)
    }

    @Test
    fun `volume round-trips a range of drinks`() {
        for ((volume, abv) in listOf(150.0 to 13.0, 44.0 to 40.0, 500.0 to 8.0)) {
            val grams = ByVolumeDosing.grams(volumeML = volume, abv = abv)
            ByVolumeDosing.volumeML(grams = grams, abv = abv) shouldBe (volume plusOrMinus 1e-6)
        }
    }

    @Test
    fun `volume guards non-positive and non-finite inputs`() {
        ByVolumeDosing.volumeML(grams = 0.0, abv = 5.0) shouldBe 0.0
        ByVolumeDosing.volumeML(grams = 13.0, abv = 0.0) shouldBe 0.0
        ByVolumeDosing.volumeML(grams = Double.NaN, abv = 5.0) shouldBe 0.0
        ByVolumeDosing.volumeML(grams = 13.0, abv = Double.POSITIVE_INFINITY) shouldBe 0.0
    }

    // MARK: - Standard-drink gloss

    @Test
    fun `standard drinks divides grams by 14`() {
        ByVolumeDosing.standardDrinks(14.0) shouldBe (1.0 plusOrMinus 1e-9)
        ByVolumeDosing.standardDrinks(28.0) shouldBe (2.0 plusOrMinus 1e-9)
        ByVolumeDosing.standardDrinks(19.7) shouldBe (1.407 plusOrMinus 0.001)
        ByVolumeDosing.standardDrinks(0.0) shouldBe 0.0
        ByVolumeDosing.standardDrinks(-5.0) shouldBe 0.0
    }

    // MARK: - The mass-per-volume adopter

    @Test
    fun `mass per volume multiplies without a density term`() {
        // An injectable ester in oil: mg = volume × concentration, and the
        // concentration is user-entered rather than shipped.
        ByVolumeDosing.mass(volumeML = 2.0, concentrationPerML = 50.0) shouldBe 100.0 plusOrMinus 1e-9
        ByVolumeDosing.volumeML(mass = 100.0, concentrationPerML = 50.0) shouldBe 2.0 plusOrMinus 1e-9
        ByVolumeDosing.mass(volumeML = 0.0, concentrationPerML = 50.0) shouldBe 0.0
        ByVolumeDosing.mass(volumeML = 2.0, concentrationPerML = Double.NaN) shouldBe 0.0
    }

    @Test
    fun `the capability dispatches on its concentration kind`() {
        val alcohol = ByVolumeDosing(
            concentration = ByVolumeDosing.Concentration.PercentByVolume(
                ByVolumeDosing.ETHANOL_DENSITY_GRAMS_PER_ML,
            ),
            canonicalUnit = "g",
            standardUnitMass = 14.0,
            standardUnitLabel = "drink",
        )
        val ester = ByVolumeDosing(
            concentration = ByVolumeDosing.Concentration.MassPerVolume,
            canonicalUnit = "mg",
            standardUnitMass = 0.0,
            standardUnitLabel = "",
        )
        alcohol.canonicalAmount(volumeML = 330.0, strength = 5.0) shouldBe
            (ByVolumeDosing.grams(330.0, 5.0) plusOrMinus 1e-9)
        ester.canonicalAmount(volumeML = 2.0, strength = 50.0) shouldBe 100.0 plusOrMinus 1e-9

        alcohol.isMassPerVolume shouldBe false
        ester.isMassPerVolume shouldBe true
        alcohol.strengthUnitLabel shouldBe "%"
        ester.strengthUnitLabel shouldBe "mg/mL"
        alcohol.strengthFieldLabel shouldBe "Strength"
        ester.strengthFieldLabel shouldBe "Concentration"

        // The inverse dispatch keeps the volume field consistent when a dose is
        // edited by mass instead of by volume.
        alcohol.volumeML(amount = ByVolumeDosing.grams(330.0, 5.0), strength = 5.0) shouldBe
            (330.0 plusOrMinus 1e-6)
        ester.volumeML(amount = 100.0, strength = 50.0) shouldBe (2.0 plusOrMinus 1e-9)
    }

    // MARK: - Trimmed formatting

    @Test
    fun `formatTrimmed writes a whole number bare and a fraction to one decimal`() {
        ByVolumeDosing.formatTrimmed(330.0) shouldBe "330"
        ByVolumeDosing.formatTrimmed(5.2) shouldBe "5.2"
        ByVolumeDosing.formatTrimmed(5.26) shouldBe "5.3"
        ByVolumeDosing.formatTrimmed(5.04) shouldBe "5.0"
        ByVolumeDosing.formatTrimmed(0.0) shouldBe "0"
        ByVolumeDosing.formatTrimmed(Double.NaN) shouldBe "0"
    }

    @Test
    fun `formatTrimmed writes a dot decimal whatever the device locale`() {
        // The breadcrumb it produces is parsed back by a pattern that expects a
        // dot, so a comma-decimal locale must not reach the string.
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            ByVolumeDosing.formatTrimmed(5.26) shouldBe "5.3"
            ByVolumeBreadcrumb.make(volumeML = 568.0, abv = 5.2) shouldBe "568 mL · 5.2% ABV"
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    // MARK: - Breadcrumb codec

    @Test
    fun `breadcrumb formats canonical mL and ABV`() {
        ByVolumeBreadcrumb.make(volumeML = 330.0, abv = 5.0) shouldBe "330 mL · 5% ABV"
        ByVolumeBreadcrumb.make(volumeML = 568.0, abv = 5.2) shouldBe "568 mL · 5.2% ABV"
    }

    @Test
    fun `breadcrumb with a name prefixes it`() {
        ByVolumeBreadcrumb.make(name = "IPA", volumeML = 568.0, abv = 6.0) shouldBe "IPA · 568 mL · 6% ABV"
        ByVolumeBreadcrumb.make(name = "  ", volumeML = 330.0, abv = 5.0) shouldBe "330 mL · 5% ABV"
        ByVolumeBreadcrumb.make(name = null, volumeML = 330.0, abv = 5.0) shouldBe "330 mL · 5% ABV"
    }

    @Test
    fun `breadcrumb round-trips through parse`() {
        val parsed = ByVolumeBreadcrumb.parse(ByVolumeBreadcrumb.make(volumeML = 175.0, abv = 13.0))!!
        parsed.name shouldBe null
        parsed.volumeML shouldBe 175.0
        parsed.abv shouldBe 13.0
    }

    @Test
    fun `named breadcrumb round-trips with its name`() {
        val parsed = ByVolumeBreadcrumb.parse(
            ByVolumeBreadcrumb.make(name = "IPA", volumeML = 568.0, abv = 6.0),
        )!!
        parsed.name shouldBe "IPA"
        parsed.volumeML shouldBe 568.0
        parsed.abv shouldBe 6.0
    }

    @Test
    fun `parse finds the breadcrumb as the leading line above a user note`() {
        val parsed = ByVolumeBreadcrumb.parse("330 mL · 5% ABV\nfelt relaxed, #chill")!!
        parsed.volumeML shouldBe 330.0
        parsed.abv shouldBe 5.0
        // The user's own text is not mistaken for the drink name.
        parsed.name shouldBe null
    }

    @Test
    fun `parse is case-insensitive and whitespace-tolerant`() {
        val parsed = ByVolumeBreadcrumb.parse("500 ml·12.5 % abv")!!
        parsed.volumeML shouldBe 500.0
        parsed.abv shouldBe 12.5
    }

    @Test
    fun `parse tolerates a non-breaking space`() {
        // The pattern is compiled Unicode-aware on purpose: Kotlin's `\s` is
        // ASCII-only, so a plain `Regex` would stop matching the moment the notes
        // field carried a pasted no-break space — and the breadcrumb is how a
        // by-volume dose remembers what it was.
        val parsed = ByVolumeBreadcrumb.parse("330\u00A0mL\u00A0·\u00A05% ABV")!!
        parsed.volumeML shouldBe 330.0
        parsed.abv shouldBe 5.0
    }

    @Test
    fun `parse returns null for notes without a breadcrumb`() {
        ByVolumeBreadcrumb.parse("just a normal note").shouldBeNull()
        ByVolumeBreadcrumb.parse("").shouldBeNull()
    }

    @Test
    fun `strip removes the breadcrumb line and keeps the user note`() {
        ByVolumeBreadcrumb.strip("330 mL · 5% ABV\nfelt relaxed") shouldBe "felt relaxed"
    }

    @Test
    fun `strip yields empty when the breadcrumb is the only content`() {
        ByVolumeBreadcrumb.strip("330 mL · 5% ABV") shouldBe ""
    }
}
