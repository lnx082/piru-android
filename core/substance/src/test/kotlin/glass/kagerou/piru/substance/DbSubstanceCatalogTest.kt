package glass.kagerou.piru.substance

import glass.kagerou.piru.engine.ActiveSubstanceCalculator
import glass.kagerou.piru.engine.ActiveSubstanceState
import glass.kagerou.piru.engine.DoseRecord
import glass.kagerou.piru.engine.PKResolver
import glass.kagerou.piru.engine.from
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.SubstanceCategory
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import kotlin.math.ln
import org.junit.jupiter.api.Test

/**
 * The read layer wired to the engine, against the shipped 18 MB catalog.
 *
 * This is the end-to-end proof the port was building toward: a dose the user
 * logged goes in, and a drawable curve with real duration data and a real
 * half-life comes out — through the same code path the app runs, not a fixture.
 *
 * Every expectation below was read out of the real database with `sqlite3` before
 * it was written down, so a failure means the adapter is wrong, not the assertion.
 */
class DbSubstanceCatalogTest {

    private val now: Instant = Instant.ofEpochSecond(1_700_000_000)
    private val tint = P3Color(red = 0.917, green = 0.200, blue = 0.139)

    /** The shipped source order, highest priority first, as the app would load it. */
    private fun catalog(db: SubstanceDb, language: ContentLanguage = ContentLanguage.EN) =
        DbSubstanceCatalog.open(
            db = db,
            order = db.query("SELECT slug FROM sources ORDER BY default_priority, slug")
                .mapNotNull { it.string("slug") },
            language = language,
        )

    private fun dose(
        substance: String,
        amount: Double = 10.0,
        route: RouteOfAdministration = RouteOfAdministration.ORAL,
        releaseForm: String? = null,
        productName: String? = null,
        saltForm: String? = null,
    ) = DoseRecord(
        substance = substance,
        amount = amount,
        unit = "mg",
        route = route,
        timestamp = now,
        releaseForm = releaseForm,
        productName = productName,
        saltForm = saltForm,
    )

    // MARK: - Substance resolution

    @Test
    fun `A canonical name resolves to a substance with its real ladders and kinetics`() {
        val catalog = catalog(openBundledSubstanceDb())
        val caffeine = catalog.lookup("Caffeine")!!
        caffeine.name shouldBe "Caffeine"
        caffeine.category shouldBe SubstanceCategory.STIMULANT
        // The catalog's measured value, not a stand-in.
        caffeine.halfLifeMinutes shouldBe 300.0
        caffeine.substanceUID shouldBe "RYYVLZVUVIJVGH"
        // Oral is one of the three routes the catalog carries dose data for, and
        // the enum's order puts it first.
        caffeine.defaultRoute shouldBe RouteOfAdministration.ORAL
        (caffeine.doseRange(RouteOfAdministration.ORAL) != null) shouldBe true
    }

    @Test
    fun `An alias resolves to the same substance`() {
        // "1,3,7-trimethylxanthine" is the row the catalog stores, and the alias
        // index is case-insensitive.
        val catalog = catalog(openBundledSubstanceDb())
        catalog.lookup("1,3,7-Trimethylxanthine")!!.name shouldBe "Caffeine"
    }

    @Test
    fun `An unknown name resolves to nothing rather than throwing`() {
        val catalog = catalog(openBundledSubstanceDb())
        catalog.lookup("Unobtainium").shouldBeNull()
    }

    @Test
    fun `A substance's PSID family comes back through the identity index`() {
        val catalog = catalog(openBundledSubstanceDb())
        catalog.substanceUID("Estradiol") shouldBe "3HCSGEEKIHDFOM"
        catalog.substanceUID("Unobtainium").shouldBeNull()
    }

    // MARK: - Depot index

    @Test
    fun `The ester index resolves a family's modelable and catalog-only esters`() {
        val catalog = catalog(openBundledSubstanceDb())
        val esters = catalog.esters("3HCSGEEKIHDFOM")
        val byLabel = esters.associateBy { it.label }
        // Valerate's terminal release rate is the shipped one, ~3 d.
        byLabel.getValue("Valerate").terminalRatePerDay shouldBe 0.236
        // Undecylate is real and loggable but ships no validated curve — it must be
        // present *and* carry no rate, because the depot heuristic has to be able
        // to tell "not an ester" from "an ester we can't model".
        (byLabel.containsKey("Undecylate")) shouldBe true
        byLabel.getValue("Undecylate").terminalRatePerDay.shouldBeNull()
    }

    @Test
    fun `An injected estradiol ester is a depot with its terminal half-life`() {
        val catalog = catalog(openBundledSubstanceDb())
        val entry = dose(
            "Estradiol",
            route = RouteOfAdministration.INTRAMUSCULAR,
            saltForm = "Valerate",
        )
        PKResolver.isDepot(entry, catalog) shouldBe true
        // ln2/k1 days → minutes, from the shipped k1 of 0.236/day (~2.94 d).
        val minutes = PKResolver.depotHalfLifeMinutes(entry, catalog)!!
        minutes shouldBe (ln(2.0) / 0.236 * 24 * 60) plusOrMinus 1e-9
    }

    @Test
    fun `A catalog-only ester still reads as a depot, on the default half-life`() {
        // The distinction that a single nullable rate would have collapsed.
        val catalog = catalog(openBundledSubstanceDb())
        val entry = dose(
            "Estradiol",
            route = RouteOfAdministration.INTRAMUSCULAR,
            saltForm = "Undecylate",
        )
        PKResolver.isDepot(entry, catalog) shouldBe true
        PKResolver.depotHalfLifeMinutes(entry, catalog) shouldBe
            PKResolver.DEFAULT_DEPOT_HALF_LIFE_DAYS * 24 * 60
    }

    @Test
    fun `The same ester swallowed is not a depot`() {
        val catalog = catalog(openBundledSubstanceDb())
        PKResolver.isDepot(
            dose("Estradiol", route = RouteOfAdministration.ORAL, saltForm = "Valerate"),
            catalog,
        ) shouldBe false
    }

    // MARK: - Product envelopes

    @Test
    fun `An extended-release product resolves its authored envelope`() {
        val catalog = catalog(openBundledSubstanceDb())
        // Concerta is authored at 660–720 minutes total.
        val concerta = catalog.productDuration("Concerta")!!
        concerta.total!!.midpoint shouldBe 690.0
        // Lookup normalizes, so the spacing and casing a user types do not matter.
        catalog.productDuration("  adderall xr ").shouldNotBeNull()
        // An immediate-release brand has no envelope — it draws the parent's curve.
        catalog.productDuration("Ritalin").shouldBeNull()
    }

    // MARK: - End to end: a logged dose becomes a curve

    @Test
    fun `A real dose resolves a drawable curve`() {
        val catalog = catalog(openBundledSubstanceDb())
        val state = ActiveSubstanceState.from(dose("Methylphenidate"), tint, catalog)!!
        state.substanceName shouldBe "Methylphenidate"
        (state.totalMinutes > 0) shouldBe true
        // The phase boundaries came from the catalog's own duration rows, so the
        // curve is the immediate-release profile rather than a synthesized stand-in.
        (state.peakEndMinutes > state.onsetEndMinutes) shouldBe true
        (state.offsetEndMinutes > state.peakEndMinutes) shouldBe true
    }

    @Test
    fun `A modeled ER product draws its own long curve, not the parent's`() {
        val catalog = catalog(openBundledSubstanceDb())
        val concerta = ActiveSubstanceState.from(
            dose("Methylphenidate", amount = 36.0, releaseForm = "XR", productName = "Concerta"),
            tint,
            catalog,
        )!!
        val plain = ActiveSubstanceState.from(dose("Methylphenidate", amount = 36.0), tint, catalog)!!
        // The catalog's offset phases sum to 735 minutes, against the ~150–240 of
        // the immediate-release row — and the IR figure is the one that invites a
        // redose when it is drawn under an extended-release product.
        (concerta.totalMinutes >= 660) shouldBe true
        (concerta.totalMinutes > plain.totalMinutes) shouldBe true
    }

    @Test
    fun `An XR naming no known product draws nothing`() {
        val catalog = catalog(openBundledSubstanceDb())
        ActiveSubstanceState.from(
            dose("Methylphenidate", releaseForm = "XR"),
            tint,
            catalog,
        ).shouldBeNull()
    }

    @Test
    fun `A depot draws no acute curve even though the substance has one`() {
        val catalog = catalog(openBundledSubstanceDb())
        ActiveSubstanceState.from(
            dose("Estradiol", route = RouteOfAdministration.INTRAMUSCULAR, saltForm = "Valerate"),
            tint,
            catalog,
        ).shouldBeNull()
    }

    @Test
    fun `A dose of unknown amount draws nothing`() {
        val catalog = catalog(openBundledSubstanceDb())
        ActiveSubstanceState.from(
            dose("Methylphenidate").copy(isUnknownDose = true),
            tint,
            catalog,
        ).shouldBeNull()
    }

    @Test
    fun `The body-load readout runs against the real half-life`() {
        val catalog = catalog(openBundledSubstanceDb())
        val active = ActiveSubstanceCalculator.compute(
            entries = listOf(dose("Caffeine", amount = 100.0)),
            colorMap = emptyMap(),
            catalog = catalog,
            fallbackTint = tint,
            now = now.plusSeconds(3_600),
        )
        active.size shouldBe 1
        val caffeine = active.first()
        caffeine.name shouldBe "Caffeine"
        caffeine.halfLifeMinutes shouldBe 300.0
        // An hour into a five-hour half-life: most of it is still there, and the
        // point of reading the real value is that this number is the catalog's.
        (caffeine.totalRemaining > 80) shouldBe true
        (caffeine.totalRemaining < 100) shouldBe true
    }

    // MARK: - Naming

    @Test
    fun `A localized title is stamped when the app runs in that language`() {
        // The catalog carries 1,606 localized names; 氯胺酮 is ketamine's zh-Hans.
        val db = openBundledSubstanceDb()
        val id = db.queryOne("SELECT id FROM substances WHERE canonical_name = 'Ketamine'")!!.long("id")!!
        val localized = db.queryOne(
            "SELECT name FROM localized_names WHERE substance_id = ? AND lang = 'zh-Hans'",
            listOf(id),
        )!!.string("name")!!

        val catalog = catalog(db, ContentLanguage.ZH_HANS)
        catalog.lookup("Ketamine")!!.localizedName shouldBe localized
        catalog.lookup("Ketamine")!!.displayTitle shouldBe localized
    }

    @Test
    fun `English keeps the canonical name`() {
        val catalog = catalog(openBundledSubstanceDb(), ContentLanguage.EN)
        val ketamine = catalog.lookup("Ketamine")!!
        ketamine.localizedName.shouldBeNull()
        ketamine.displayTitle shouldBe "Ketamine"
    }

    @Test
    fun `A user's relabel outranks every default`() {
        val catalog = catalog(openBundledSubstanceDb(), ContentLanguage.ZH_HANS)
        val relabelled = catalog.lookup("Ketamine")!!.copy(displayName = "Special K")
        relabelled.displayTitle shouldBe "Special K"
    }

    @Test
    fun `A substance builds with its ladders, aliases and canonical route order`() {
        // The batch read is all-or-nothing: a bad join inside it would leave the
        // whole library empty, and the app would look like an empty catalog rather
        // than a broken one.
        val catalog = catalog(openBundledSubstanceDb())
        val methylphenidate = catalog.lookup("Methylphenidate")!!
        (methylphenidate.routes.isNotEmpty()) shouldBe true
        (methylphenidate.aliases.isNotEmpty()) shouldBe true
        // Route order is the enum's own, so the default route is the most common —
        // without the sort Diazepam would default to IV instead of oral.
        methylphenidate.routes.first().route shouldBe RouteOfAdministration.ORAL
        methylphenidate.defaultRoute shouldBe RouteOfAdministration.ORAL
    }

    @Test
    fun `A substance with no duration rows draws nothing, however long its half-life`() {
        // Aripiprazole carries a 4,500-minute half-life and zero duration rows. The
        // half-life says how much is still in you, not what the effect curve looks
        // like, and deriving one from it drew a flat multi-week plateau over every
        // real curve on the graph. This is the shipped-catalog instance of that.
        val catalog = catalog(openBundledSubstanceDb())
        catalog.lookup("Aripiprazole")!!.halfLifeMinutes shouldBe 4_680.0
        ActiveSubstanceState.from(dose("Aripiprazole"), tint, catalog).shouldBeNull()
    }

    @Test
    fun `A substance with durations but no half-life still draws, and carries no body load`() {
        // Alcohol: 21 duration rows and no `half_lives` row at all — it clears by
        // zero-order kinetics, which the catalog records elsewhere. The two
        // readouts answer different questions and must not gate one another.
        val catalog = catalog(openBundledSubstanceDb())
        val alcohol = catalog.lookup("Alcohol")!!
        alcohol.halfLifeMinutes.shouldBeNull()
        val state = ActiveSubstanceState.from(dose("Alcohol", amount = 28_000.0), tint, catalog)
        state.shouldNotBeNull()
        ActiveSubstanceCalculator.compute(
            entries = listOf(dose("Alcohol", amount = 28_000.0)),
            colorMap = emptyMap(),
            catalog = catalog,
            fallbackTint = tint,
            now = now.plusSeconds(3_600),
        ).isEmpty() shouldBe true
    }
}
