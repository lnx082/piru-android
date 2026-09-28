package glass.kagerou.piru.engine

import glass.kagerou.piru.model.RouteOfAdministration
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.ln
import org.junit.jupiter.api.Test

/**
 * `PKResolver` has no suite of its own upstream — it is covered through the
 * body-load, depot-levels and product-duration suites. These are the rules those
 * suites exercise, pinned directly, with hand-built catalog facts instead of the
 * bundled database.
 *
 * The suite is organised around the two things the resolver exists to stop a call
 * site from re-deriving: which half-life a dose carries, and where `ka` comes from.
 */
class PKResolverTest {

    private val ln2 = ln(2.0)

    private val estradiol = substance(
        name = "Estradiol",
        category = glass.kagerou.piru.model.SubstanceCategory.OTHER,
        defaultRoute = RouteOfAdministration.ORAL,
        routes = listOf(
            route(RouteOfAdministration.ORAL, duration = acute()),
            route(RouteOfAdministration.INTRAMUSCULAR, duration = acute()),
            route(RouteOfAdministration.SUBCUTANEOUS, duration = acute()),
        ),
        halfLifeMinutes = 60.0,
    )

    /** The `ester_pk` index: one modelable ester, one catalog-only. */
    private val catalog = FakeCatalog(
        substances = listOf(estradiol),
        uids = mapOf("Estradiol" to "psid-estradiol"),
        esterFamilies = mapOf(
            "psid-estradiol" to listOf(
                EsterRecord("Cypionate", terminalRatePerDay = ln(2.0) / 8), // ~8 d
                EsterRecord("Undecylate", terminalRatePerDay = null), // no validated curve
            ),
        ),
    )

    private val emptyCatalog = FakeCatalog()

    // MARK: - Half-life from the substance

    @Test
    fun `A substance without a half-life resolves to none`() {
        PKResolver.halfLifeMinutes(null).shouldBeNull()
        PKResolver.halfLifeMinutes(substance("Unmeasured")).shouldBeNull()
    }

    @Test
    fun `A non-positive half-life is treated as none`() {
        // The catalog has rows carrying 0 for "unknown"; `ke` from those is
        // infinite, so they must read as absent rather than as instantaneous
        // clearance.
        PKResolver.halfLifeMinutes(substance("Zeroed", halfLifeMinutes = 0.0)).shouldBeNull()
        PKResolver.halfLifeMinutes(substance("Negative", halfLifeMinutes = -30.0)).shouldBeNull()
    }

    @Test
    fun `A measured half-life passes through in minutes`() {
        PKResolver.halfLifeMinutes(substance("Measured", halfLifeMinutes = 360.0)) shouldBe 360.0
    }

    // MARK: - Depot detection

    @Test
    fun `A depot-flagged formulation is a depot on any route`() {
        // The flag is the catalog's own claim about the product, so it does not
        // depend on the route the user happened to log.
        PKResolver.isDepot(
            dose("Paliperidone", route = RouteOfAdministration.INTRAMUSCULAR, releaseForm = "DEP"),
            emptyCatalog,
        ) shouldBe true
        PKResolver.isDepot(
            dose("Medroxyprogesterone", route = RouteOfAdministration.ORAL, releaseForm = "DEP"),
            emptyCatalog,
        ) shouldBe true
    }

    @Test
    fun `An injected ester of a known family is a depot`() {
        for (route in listOf(RouteOfAdministration.INTRAMUSCULAR, RouteOfAdministration.SUBCUTANEOUS)) {
            PKResolver.isDepot(
                dose("Estradiol", route = route, saltForm = "Cypionate"),
                catalog,
            ) shouldBe true
        }
    }

    @Test
    fun `The same ester swallowed is not a depot`() {
        // Nothing releases it slowly down the gut, so it is an ordinary oral dose
        // and must keep its acute curve and its ordinary half-life.
        PKResolver.isDepot(
            dose("Estradiol", route = RouteOfAdministration.ORAL, saltForm = "Cypionate"),
            catalog,
        ) shouldBe false
        PKResolver.isDepot(
            dose("Estradiol", route = RouteOfAdministration.SUBLINGUAL, saltForm = "Cypionate"),
            catalog,
        ) shouldBe false
    }

    @Test
    fun `The free hormone injected is not a depot`() {
        PKResolver.isDepot(dose("Estradiol", route = RouteOfAdministration.INTRAMUSCULAR), catalog) shouldBe false
    }

    @Test
    fun `A salt that is not an ester of the family is not a depot`() {
        // "Estradiol Benzoate" is not in the family's ester index, so the dose is
        // treated as the free hormone rather than being handed a depot half-life.
        PKResolver.isDepot(
            dose("Estradiol", route = RouteOfAdministration.INTRAMUSCULAR, saltForm = "Benzoate"),
            catalog,
        ) shouldBe false
    }

    @Test
    fun `An empty salt and an unknown family are both not a depot`() {
        PKResolver.isDepot(
            dose("Estradiol", route = RouteOfAdministration.INTRAMUSCULAR, saltForm = ""),
            catalog,
        ) shouldBe false
        PKResolver.isDepot(
            dose("Somethingelse", route = RouteOfAdministration.INTRAMUSCULAR, saltForm = "Cypionate"),
            catalog,
        ) shouldBe false
    }

    @Test
    fun `A family captured on the entry is used without a name lookup`() {
        // The stored UID exists so a dose logged under an alias — or a substance
        // since renamed in the catalog — still finds its esters. This catalog can
        // resolve *no* name to a family, so the only way to reach the ester index
        // is through the entry.
        val estersOnly = FakeCatalog(
            esterFamilies = mapOf(
                "psid-estradiol" to listOf(EsterRecord("Cypionate", terminalRatePerDay = ln(2.0) / 8)),
            ),
        )
        estersOnly.substanceUID("E2 injection").shouldBeNull()
        PKResolver.isDepot(
            dose(
                "E2 injection",
                route = RouteOfAdministration.INTRAMUSCULAR,
                saltForm = "Cypionate",
                substanceUID = "psid-estradiol",
            ),
            estersOnly,
        ) shouldBe true
    }

    // MARK: - Depot half-life

    @Test
    fun `A modelable ester reports its terminal release half-life, not the molecule's`() {
        // Cypionate's k1 is ln2/8 per day, so the terminal half-life is 8 days —
        // against estradiol's own 60-minute elimination. Reading the molecule's
        // would show a depot injection clearing the same afternoon.
        val minutes = PKResolver.depotHalfLifeMinutes(
            dose("Estradiol", route = RouteOfAdministration.INTRAMUSCULAR, saltForm = "Cypionate"),
            catalog,
        )
        (minutes!! - 8 * 24 * 60) shouldBe (0.0 plusOrMinus 1e-9)
        PKResolver.halfLifeMinutes(
            dose("Estradiol", route = RouteOfAdministration.INTRAMUSCULAR, saltForm = "Cypionate"),
            estradiol,
            catalog,
        )!! shouldBe minutes
    }

    @Test
    fun `A catalog-only ester falls back to the default depot half-life`() {
        // Undecylate is real and loggable but ships no validated curve. 21 days is
        // a stated lower bound that spans it, so a body-load magnitude stays
        // plausible rather than fabricated from a rate nobody published.
        PKResolver.depotHalfLifeMinutes(
            dose("Estradiol", route = RouteOfAdministration.INTRAMUSCULAR, saltForm = "Undecylate"),
            catalog,
        ) shouldBe PKResolver.DEFAULT_DEPOT_HALF_LIFE_DAYS * 24 * 60
    }

    @Test
    fun `A non-ester depot falls back too`() {
        PKResolver.depotHalfLifeMinutes(
            dose("Paliperidone", route = RouteOfAdministration.INTRAMUSCULAR, releaseForm = "DEP"),
            catalog,
        ) shouldBe PKResolver.DEFAULT_DEPOT_HALF_LIFE_DAYS * 24 * 60
    }

    @Test
    fun `An ordinary dose has no depot half-life`() {
        PKResolver.depotHalfLifeMinutes(
            dose("Estradiol", route = RouteOfAdministration.ORAL, saltForm = "Cypionate"),
            catalog,
        ).shouldBeNull()
    }

    @Test
    fun `A caller that already knows the answer can skip the check`() {
        // The two-argument-overload exists for a per-entry loop: `isDepot` reads the
        // route, form and salt and resolves the family, so asking once per entry
        // rather than twice is the point of the overload.
        val entry = dose("Estradiol", route = RouteOfAdministration.INTRAMUSCULAR, saltForm = "Cypionate")
        PKResolver.depotHalfLifeMinutes(entry, isDepot = true, catalog) shouldBe
            PKResolver.depotHalfLifeMinutes(entry, catalog)
        PKResolver.depotHalfLifeMinutes(entry, isDepot = false, catalog).shouldBeNull()
    }

    // MARK: - Rate constants

    @Test
    fun `ka is fitted to the profile's time to peak`() {
        // Onset 30 + come-up 30 is a 60-minute time to peak. The suite pins *which*
        // number was fed to the fit — the two phase midpoints summed — rather than
        // the fit's own arithmetic, which `PKModelTest` already covers.
        val ke = PKModel.keFromHalfLifeMinutes(360.0)
        val (gotKe, gotKa) = PKResolver.rateConstants(360.0, acute(onset = 30.0, comeup = 30.0))
        gotKe shouldBe ke
        gotKa shouldBe PKModel.estimateKa(60.0, ke)
    }

    @Test
    fun `A profile with no phase data takes the default ka`() {
        // Endpoint-only data — a bare `total`, which is common in the catalog —
        // gives a time to peak of zero. Fitting to zero has no solution, so the
        // default proportional-to-ke relationship stands in.
        val ke = PKModel.keFromHalfLifeMinutes(360.0)
        val (_, gotKa) = PKResolver.rateConstants(360.0, glass.kagerou.piru.model.DurationProfile(peak = span(90.0)))
        gotKa shouldBe PKModel.defaultKa(ke)
    }

    @Test
    fun `No profile at all takes the default ka`() {
        val ke = PKModel.keFromHalfLifeMinutes(360.0)
        PKResolver.rateConstants(360.0, null) shouldBe (ke to PKModel.defaultKa(ke))
    }

    @Test
    fun `A slower absorption limb means a smaller ka`() {
        // Sanity on the direction, so a reversed argument order would fail here
        // rather than only in the curve's shape.
        val (_, fast) = PKResolver.rateConstants(360.0, acute(onset = 5.0, comeup = 5.0))
        val (_, slow) = PKResolver.rateConstants(360.0, acute(onset = 60.0, comeup = 60.0))
        (slow < fast) shouldBe true
    }

    // MARK: - Full resolution

    @Test
    fun `params resolves the route's own profile off the substance`() {
        val withProfile = substance("Profiled", halfLifeMinutes = 120.0)
        val params = PKResolver.params(withProfile, RouteOfAdministration.ORAL)!!
        params.halfLifeMinutes shouldBe 120.0
        params.ke shouldBe PKModel.keFromHalfLifeMinutes(120.0)
        // acute() defaults sum to a 30-minute time to peak.
        params.ka shouldBe PKModel.estimateKa(30.0, params.ke)
    }

    @Test
    fun `params is null when no half-life can be resolved`() {
        // Not a zero-filled Params: `ke` from a missing half-life is infinite, and
        // a caller that can't tell "unknown" from "instantaneous" would draw a
        // vertical line.
        PKResolver.params(substance("Unmeasured"), acute()).shouldBeNull()
        PKResolver.params(null, acute()).shouldBeNull()
        PKResolver.params(substance("Unmeasured"), RouteOfAdministration.ORAL).shouldBeNull()
    }

    @Test
    fun `params accepts a caller-resolved duration`() {
        // A per-product envelope or a salt-specific profile arrives through this
        // overload rather than being re-resolved off the substance.
        val profiled = substance("Profiled", halfLifeMinutes = 120.0)
        val ke = PKModel.keFromHalfLifeMinutes(120.0)
        // A 6-minute absorption limb needs a ka far above the default 4·ke, which
        // is the whole point of fitting to time-to-peak.
        val fast = PKResolver.params(profiled, acute(onset = 3.0, comeup = 3.0))!!
        (fast.ka > PKModel.defaultKa(ke)) shouldBe true
        // And the profile really is what was read: the route's own 30-minute limb
        // gives a different answer from the 6-minute one.
        (fast.ka != PKResolver.params(profiled, RouteOfAdministration.ORAL)!!.ka) shouldBe true
    }
}
