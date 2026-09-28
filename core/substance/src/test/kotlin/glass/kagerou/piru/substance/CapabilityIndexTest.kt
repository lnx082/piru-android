package glass.kagerou.piru.substance

import glass.kagerou.piru.engine.PKModel
import glass.kagerou.piru.engine.TimelineCurveModel
import glass.kagerou.piru.model.ByVolumeDosing
import glass.kagerou.piru.model.DrinkPreset
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The capability indexes against the shipped catalog: by-volume dosing and
 * saturable (zero-order) elimination.
 *
 * Ported from `PiruTests/ByVolumeDosingDBTests.swift`. Three of the upstream
 * cases are not here, each because their subject is not ported yet rather than
 * because they were dropped: the drink unit *alias* check (wants
 * `Substance.unitAliases`, which belongs to the custom-unit work), the watch
 * payload check (no watch), and the resolved-bioavailability check, which lands
 * with `pharmacologyParameters`.
 */
class CapabilityIndexTest {

    private fun catalog(db: SubstanceDb) = DbSubstanceCatalog.open(
        db = db,
        order = db.query("SELECT slug FROM sources ORDER BY default_priority, slug")
            .mapNotNull { it.string("slug") },
        language = ContentLanguage.EN,
    )

    private fun alcohol(catalog: DbSubstanceCatalog) = catalog.byVolumeDosing("Alcohol")

    // MARK: - The two declarations must agree

    @Test
    fun `The compiled ethanol density equals the database row`() {
        // The compiled constant is what a widget or watch computes with, having no
        // database; the column is what the app resolves. A drift between them would
        // show the same pour as two different masses depending on which process
        // computed it, with nothing on either screen to reveal it.
        val capability = alcohol(catalog(openBundledSubstanceDb())).shouldNotBeNull()
        val concentration = capability.concentration
            as ByVolumeDosing.Concentration.PercentByVolume
        concentration.densityGramsPerML shouldBe ByVolumeDosing.ETHANOL_DENSITY_GRAMS_PER_ML
    }

    @Test
    fun `The compiled standard-drink mass equals the database row`() {
        // One more consumer than the density: the curve engine converts a dose
        // logged in "drinks" with this, inside the widget, where the row is
        // unreachable.
        val capability = alcohol(catalog(openBundledSubstanceDb())).shouldNotBeNull()
        capability.standardUnitMass shouldBe ByVolumeDosing.US_STANDARD_DRINK_GRAMS
        capability.canonicalUnit shouldBe "g"
        capability.standardUnitLabel shouldBe "drink"
    }

    // MARK: - Capability resolution

    @Test
    fun `A capability resolves through its aliases and nothing else does`() {
        // Keyed by canonical name *and* every alias, so a dose logged as "Ethanol"
        // gets the by-volume panel rather than a bare mass field.
        val catalog = catalog(openBundledSubstanceDb())
        val canonical = alcohol(catalog).shouldNotBeNull()
        catalog.byVolumeDosing("ethanol") shouldBe canonical
        catalog.byVolumeDosing("ETHYL ALCOHOL") shouldBe canonical
        catalog.byVolumeDosing("Caffeine").shouldBeNull()
    }

    @Test
    fun `A capability converts through its own stored density`() {
        // Not the compiled default: that is the property which lets a second
        // adopter land as a row rather than as a code change.
        val capability = alcohol(catalog(openBundledSubstanceDb())).shouldNotBeNull()
        capability.canonicalAmount(volumeML = 500.0, strength = 5.0) shouldBe
            (ByVolumeDosing.grams(500.0, 5.0) plusOrMinus 1e-9)
    }

    @Test
    fun `Alcohol ships an ordered set of known drink presets`() {
        // The presets are what the by-drink panel logs from; an empty list would
        // silently turn it into a bare volume field.
        val capability = alcohol(catalog(openBundledSubstanceDb())).shouldNotBeNull()
        (capability.drinkPresets.isNotEmpty()) shouldBe true
        // One chip per kind, and the order is the catalog's `rank`.
        capability.drinkPresets.map { it.kind } shouldBe listOf(
            DrinkPreset.Kind.BEER,
            DrinkPreset.Kind.WINE,
            DrinkPreset.Kind.SHOT,
            DrinkPreset.Kind.PINT,
        )
        for (preset in capability.drinkPresets) {
            (preset.volumeML > 0) shouldBe true
            (preset.defaultABV > 0) shouldBe true
        }
        // The shipped values, so a rebuild that mangles the table shows up.
        capability.drinkPresets.first().volumeML shouldBe 330.0
        capability.drinkPresets.first().defaultABV shouldBe 5.0
    }

    @Test
    fun `A mass-per-volume capability carries no density and no unit gloss`() {
        // Estradiol's injectable ester: mg = volume × concentration, and the
        // concentration is user-entered per dose rather than shipped — so the row
        // has no density and no standard unit, and both default to nothing rather
        // than to alcohol's numbers.
        val capability = catalog(openBundledSubstanceDb()).byVolumeDosing("Estradiol").shouldNotBeNull()
        capability.concentration shouldBe ByVolumeDosing.Concentration.MassPerVolume
        capability.canonicalUnit shouldBe "mg"
        capability.standardUnitMass shouldBe 0.0
        capability.standardUnitLabel shouldBe ""
        capability.isMassPerVolume shouldBe true
        capability.drinkPresets.isEmpty() shouldBe true
    }

    // MARK: - Zero-order elimination

    @Test
    fun `Alcohol resolves zero-order kinetics and other substances do not`() {
        // The switch onto the dose-scaled linear-decline curve is the presence of a
        // `zero_order_kinetics` row — which replaces a `case "alcohol", "ethanol"`
        // string comparison in the curve engine. This is the test that the
        // replacement actually engages, and that it engages through an alias too.
        val catalog = catalog(openBundledSubstanceDb())
        val row = catalog.zeroOrderRow("Alcohol").shouldNotBeNull()
        row.vmaxMgPerMin shouldBe 95.0
        row.referenceWeightKg shouldBe 60.0
        row.kaPerMin shouldBe 0.026
        catalog.zeroOrderRow("ethanol") shouldBe row
        catalog.zeroOrderRow("Caffeine").shouldBeNull()
    }

    @Test
    fun `The raw row scales to a person through the engine's own model`() {
        // Elimination throughput tracks lean and liver mass, so Vmax scales
        // linearly with weight — the property that makes the same dose draw a
        // narrower curve on a heavier person. Held at the band edges rather than
        // extrapolated: a clearance projected from a 5 kg body is arithmetic, not
        // physiology.
        val row = catalog(openBundledSubstanceDb()).zeroOrderRow("Alcohol").shouldNotBeNull()
        fun scale(weightKg: Double) = PKModel.zeroOrderKinetics(
            vmaxMgPerMin = row.vmaxMgPerMin,
            referenceWeightKg = row.referenceWeightKg,
            kaPerMin = row.kaPerMin,
            bioavailability = 1.0,
            weightKg = weightKg,
        )

        val at60 = scale(60.0)
        val at120 = scale(120.0)
        at60.vmaxMgPerMin shouldBe (95.0 plusOrMinus 1e-9)
        at120.vmaxMgPerMin shouldBe (2 * at60.vmaxMgPerMin plusOrMinus 1e-9)
        scale(5.0) shouldBe scale(PKModel.MINIMUM_MODELED_WEIGHT_KG)
        scale(999.0) shouldBe scale(PKModel.MAXIMUM_MODELED_WEIGHT_KG)
    }

    @Test
    fun `A dose in drinks converts at the standard-drink mass`() {
        // A dose logged as "2 drinks" must reach the zero-order model as a mass
        // rather than falling through to the phase bell.
        TimelineCurveModel.zeroOrderDoseMilligrams(2.0, "drinks") shouldBe
            2 * ByVolumeDosing.US_STANDARD_DRINK_GRAMS * 1_000
        TimelineCurveModel.zeroOrderDoseMilligrams(2.0, "g") shouldBe 2_000.0
        // A volume is not a mass: millilitres of a drink must fall back rather than
        // be read as milligrams.
        TimelineCurveModel.zeroOrderDoseMilligrams(330.0, "mL").shouldBeNull()
    }
}
