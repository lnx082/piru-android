package glass.kagerou.piru.data

import glass.kagerou.piru.data.entity.CustomSubstanceRecordEntity
import glass.kagerou.piru.model.DoseRange
import glass.kagerou.piru.model.DurationProfile
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.model.SubstanceRoute
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The personalisation overlay — the one piece of the feature whose correctness is invisible.
 *
 * An overlay that dropped the catalogue's other routes, or that replaced `name` instead of `displayName`, produces
 * a substance that **looks fine** and has lost either the reference data or the key the user's dose history matches
 * on. Nothing on a screen would say so, which is why every rule here is a test.
 *
 * ## The contract, from upstream
 * Lookups get the user's edits; **collection-level APIs stay library-only**. The first half is what makes a
 * corrected duration profile take effect in the timeline and the PK curve; the second is what keeps the library grid
 * the reference a reader expects it to be.
 */
class EntityOverlayTest {

    private fun route(
        route: RouteOfAdministration = RouteOfAdministration.ORAL,
        doses: DoseRange = DoseRange(common = 10.0..20.0),
        duration: DurationProfile? = null,
    ) = SubstanceRoute(route = route, unit = "mg", doses = doses, duration = duration)

    private fun bundled(
        name: String = "2-MMC",
        displayName: String? = null,
        routes: List<SubstanceRoute> = listOf(route()),
        halfLife: Double? = null,
    ) = Substance(
        name = name,
        displayName = displayName,
        category = SubstanceCategory.STIMULANT,
        defaultRoute = RouteOfAdministration.ORAL,
        routes = routes,
        halfLifeMinutes = halfLife,
    )

    private fun entry(
        name: String = "2-MMC",
        displayName: String? = null,
        doses: DoseRange? = null,
        duration: DurationProfile? = null,
        halfLife: Double? = null,
        route: String = "oral",
        unit: String = "mg",
    ) = CustomSubstanceRecordEntity(
        name = name,
        displayName = displayName,
        dosesJson = CustomSubstanceBlobs.encodeDoses(doses),
        durationJson = CustomSubstanceBlobs.encodeDuration(duration),
        halfLifeMinutes = halfLife,
        defaultRouteRaw = route,
        unit = unit,
    )

    // MARK: - The identity rule

    /**
     * **`displayName` is the label; `name` is the identity.**
     *
     * The rule that makes relabelling safe: a user can log "THC" and see it labeled "joint" without breaking a
     * single row of dose history, because every logged row and every export carries `name`. An overlay that set
     * `name` instead would orphan the history — which is why it is asserted directly rather than through any
     * surface.
     */
    @Test
    fun `the label never replaces the identity`() {
        val overlaid = EntityOverlay.apply(bundled(name = "THC"), entry(name = "THC", displayName = "joint"))
        overlaid.name shouldBe "THC"
        overlaid.displayName shouldBe "joint"
        // And the title the UI shows is the label.
        overlaid.displayTitle shouldBe "joint"
    }

    /**
     * A blank label is no label, and the catalogue's own title is kept.
     *
     * A field a user cleared means "no label", not "label it with the empty string" — which would render an entry
     * with no visible name at all.
     */
    @Test
    fun `a blank label leaves the title alone`() {
        val overlaid = EntityOverlay.apply(bundled(displayName = "2-MMC"), entry(displayName = "   "))
        overlaid.displayName shouldBe "2-MMC"
    }

    // MARK: - What an entry can change

    /** A half-life override replaces the catalogue's. */
    @Test
    fun `a half-life override replaces the catalogue's`() {
        val overlaid = EntityOverlay.apply(bundled(halfLife = 120.0), entry(halfLife = 45.0))
        overlaid.halfLifeMinutes shouldBe 45.0
    }

    /** And an entry without one keeps the catalogue's. */
    @Test
    fun `an absent half-life keeps the catalogue's`() {
        EntityOverlay.apply(bundled(halfLife = 120.0), entry()).halfLifeMinutes shouldBe 120.0
    }

    /**
     * A personal ladder replaces **that route's** ladder and leaves the others alone.
     *
     * The rule an overlay gets wrong most easily, in two ways that both look fine: replacing the whole route list
     * loses the catalogue's data for every route the user did not edit, and dropping the other routes is the same
     * loss spelled differently.
     */
    @Test
    fun `a personal ladder replaces one route and keeps the others`() {
        val substance = bundled(
            routes = listOf(
                route(RouteOfAdministration.ORAL, doses = DoseRange(common = 10.0..20.0)),
                route(RouteOfAdministration.INSUFFLATION, doses = DoseRange(common = 5.0..15.0)),
            ),
        )
        val mine = DoseRange(common = 40.0..60.0)
        val overlaid = EntityOverlay.apply(substance, entry(doses = mine, route = "oral"))

        overlaid.routes.size shouldBe 2
        overlaid.routes.first { it.route == RouteOfAdministration.ORAL }.doses.common shouldBe (40.0..60.0)
        // The route the user did not touch keeps the catalogue's own ladder.
        overlaid.routes.first { it.route == RouteOfAdministration.INSUFFLATION }.doses.common shouldBe (5.0..15.0)
    }

    /**
     * A personal ladder for a route the catalogue does not carry gets a row of its own.
     *
     * The case where the entry's route is real but unlisted: dropping it would silently discard the one thing the
     * user added.
     */
    @Test
    fun `a personal ladder for an unlisted route is added as a row`() {
        val substance = bundled(routes = listOf(route(RouteOfAdministration.ORAL)))
        val overlaid = EntityOverlay.apply(
            substance,
            entry(doses = DoseRange(common = 1.0..2.0), route = "insufflation"),
        )
        overlaid.routes.size shouldBe 2
        overlaid.routes.first { it.route == RouteOfAdministration.INSUFFLATION }.doses.common shouldBe (1.0..2.0)
        // And the original is untouched.
        overlaid.routes.first { it.route == RouteOfAdministration.ORAL }.doses.common shouldBe (10.0..20.0)
    }

    /** The entry's own unit comes with its ladder, because that is what the numbers are in. */
    @Test
    fun `a new route row uses the entry's unit`() {
        val overlaid = EntityOverlay.apply(
            bundled(routes = listOf(route(RouteOfAdministration.ORAL))),
            entry(doses = DoseRange(common = 1.0..2.0), route = "insufflation", unit = "µg"),
        )
        overlaid.routes.first { it.route == RouteOfAdministration.INSUFFLATION }.unit shouldBe "µg"
    }

    // MARK: - Net-new substances

    /**
     * A name the catalogue does not carry becomes a new substance, with the entry's own fields.
     *
     * Every catalogue-shaped field stays at its default, and that is deliberate: the reference sections read the
     * bundled database **by name**, find nothing and hide. A fabricated value in any of them would be worse than an
     * absent section.
     */
    @Test
    fun `an unknown name becomes a new substance`() {
        val overlaid = EntityOverlay.apply(null, entry(name = "My Compound", displayName = "mine", halfLife = 90.0))
        overlaid.name shouldBe "My Compound"
        overlaid.displayTitle shouldBe "mine"
        overlaid.halfLifeMinutes shouldBe 90.0
        overlaid.routes.size shouldBe 1
        // Nothing invented for the reference fields.
        overlaid.aliases shouldBe emptyList()
    }

    /** A net-new substance with no half-life has none, rather than a zero that would draw a flat line. */
    @Test
    fun `a net-new substance without a half-life has none`() {
        EntityOverlay.apply(null, entry(name = "X")).halfLifeMinutes shouldBe null
    }

    // MARK: - The list

    /**
     * `applyAll` overlays by name, case-insensitively, and prepends the invented ones.
     *
     * Case-insensitivity is what makes an override work at all: a user who typed "thc" means the "THC" the
     * catalogue has, and a case-sensitive match would produce a **duplicate substance** instead of an override —
     * which looks like the edit did nothing and the list grew by one.
     */
    @Test
    fun `the list overlays by name and keeps every substance`() {
        val catalogue = listOf(bundled(name = "THC"), bundled(name = "Ketamine"))
        val overlaid = EntityOverlay.applyAll(
            catalogue,
            listOf(entry(name = "thc", displayName = "joint"), entry(name = "My Compound")),
        )
        // Two catalogue substances, no duplicates, plus the invented one.
        overlaid.size shouldBe 3
        overlaid.count { it.name.equals("THC", ignoreCase = true) } shouldBe 1
        overlaid.first { it.name == "THC" }.displayTitle shouldBe "joint"
        (overlaid.any { it.name == "My Compound" }) shouldBe true
    }

    /**
     * No entries returns **the same list**, not a copy.
     *
     * Upstream's own note: the early return is what makes this free when the user has no customs, which is the
     * overwhelming case. Asserted by identity, because a copy would still pass every other test here.
     */
    @Test
    fun `no entries is free`() {
        val catalogue = listOf(bundled())
        (EntityOverlay.applyAll(catalogue, emptyList()) === catalogue) shouldBe true
    }

    // MARK: - The display-name accessor

    /**
     * `displayName` falls back to the name for anything the user has not touched.
     *
     * The accessor a display surface needs, so no call site has to know whether an entry exists. Upstream's doc
     * records the bug it prevents: the library list and search showed "Mephedrone" while the detail screen showed
     * "4-MMC", because only the detail screen re-resolved the personal name.
     */
    @Test
    fun `the display name falls back to the logged name`() {
        val entries = listOf(entry(name = "THC", displayName = "joint"))
        EntityOverlay.displayName(entries, name = "THC", fallback = "THC") shouldBe "joint"
        EntityOverlay.displayName(entries, name = "thc", fallback = "THC") shouldBe "joint"
        // An entry with no label, and a name with no entry, both fall through.
        EntityOverlay.displayName(listOf(entry(name = "Ketamine")), "Ketamine", "Ketamine") shouldBe "Ketamine"
        EntityOverlay.displayName(entries, "Ketamine", "Ketamine") shouldBe "Ketamine"
        EntityOverlay.displayName(emptyList(), "THC", "THC") shouldBe "THC"
    }
}
