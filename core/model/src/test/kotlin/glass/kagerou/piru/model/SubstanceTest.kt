package glass.kagerou.piru.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Ported from `PiruTests/SubstanceTests.swift`.
 *
 * Two of the upstream suite's cases are not here: `displayAliases` (a Library
 * list-presentation concern that travels with the UI) and the `Citation` suite
 * (a metadata type that belongs with the detail page).
 */
class SubstanceTest {

    private val oralRoute = SubstanceRoute(
        route = RouteOfAdministration.ORAL,
        unit = "mg",
        doses = DoseRange(
            threshold = 5.0,
            light = 10.0..20.0,
            common = 20.0..40.0,
            strong = 40.0..80.0,
            heavy = 80.0,
        ),
        duration = DurationProfile(
            onset = DurationRange(15.0, 30.0),
            comeup = DurationRange(15.0, 30.0),
            peak = DurationRange(60.0, 120.0),
            offset = DurationRange(30.0, 60.0),
            afterglow = null,
            total = null,
        ),
    )

    private val nasalRoute = SubstanceRoute(
        route = RouteOfAdministration.INSUFFLATION,
        unit = "mg",
        doses = DoseRange(
            threshold = 3.0,
            light = 5.0..15.0,
            common = 15.0..30.0,
            strong = 30.0..60.0,
            heavy = 60.0,
        ),
        duration = DurationProfile(
            onset = DurationRange(5.0, 10.0),
            comeup = DurationRange(5.0, 10.0),
            peak = DurationRange(30.0, 60.0),
            offset = DurationRange(15.0, 30.0),
            afterglow = null,
            total = null,
        ),
    )

    private val substance = Substance(
        name = "TestSubstance",
        aliases = listOf("TS", "Test Drug"),
        category = SubstanceCategory.STIMULANT,
        defaultRoute = RouteOfAdministration.ORAL,
        routes = listOf(oralRoute, nasalRoute),
    )

    // MARK: - matches

    @Test
    fun `Matches by name case-insensitive`() {
        substance.matches("testsubstance") shouldBe true
        substance.matches("TestSubstance") shouldBe true
        substance.matches("TESTSUBSTANCE") shouldBe true
    }

    @Test
    fun `Matches partial name`() {
        substance.matches("test") shouldBe true
        substance.matches("Substance") shouldBe true
    }

    @Test
    fun `Matches by alias`() {
        substance.matches("TS") shouldBe true
        substance.matches("ts") shouldBe true
        substance.matches("Test Drug") shouldBe true
    }

    @Test
    fun `Matches partial alias`() {
        substance.matches("Test D") shouldBe true
    }

    @Test
    fun `Does not match an unrelated query`() {
        substance.matches("Aspirin") shouldBe false
        substance.matches("xyz") shouldBe false
    }

    @Test
    fun `An empty query does not match`() {
        // Not a triviality: Kotlin's `contains("")` is true for every string, so
        // without an explicit guard every substance would match an empty search
        // box — and Swift answers false here, so the two would disagree on the
        // most common input a search field ever sees.
        substance.matches("") shouldBe false
    }

    // MARK: - Route accessors

    @Test
    fun `Returns the dose range for a matching route`() {
        val range = substance.doseRange(RouteOfAdministration.ORAL)
        range?.threshold shouldBe 5.0
    }

    @Test
    fun `Returns null for a route the substance does not carry`() {
        substance.doseRange(RouteOfAdministration.INTRAVENOUS) shouldBe null
        substance.duration(RouteOfAdministration.INTRAVENOUS) shouldBe null
    }

    @Test
    fun `Returns the unit for a matching route`() {
        substance.unit(RouteOfAdministration.ORAL) shouldBe "mg"
    }

    @Test
    fun `Falls back to the default unit for an unknown route`() {
        substance.unit(RouteOfAdministration.INTRAVENOUS) shouldBe "mg"
    }

    @Test
    fun `Returns the duration for a matching route`() {
        substance.duration(RouteOfAdministration.ORAL)?.onset?.midpoint shouldBe 22.5
    }

    @Test
    fun `Default unit comes from the default route`() {
        substance.defaultUnit shouldBe "mg"
    }

    @Test
    fun `Default unit falls back to the first route`() {
        val s = Substance(
            name = "Test",
            category = SubstanceCategory.STIMULANT,
            defaultRoute = RouteOfAdministration.INTRAVENOUS, // not in routes
            routes = listOf(
                SubstanceRoute(
                    route = RouteOfAdministration.ORAL,
                    unit = "ug",
                    doses = DoseRange(),
                ),
            ),
        )
        s.defaultUnit shouldBe "ug"
    }

    @Test
    fun `Default unit falls back to mg when there are no routes`() {
        val s = Substance(
            name = "Test",
            category = SubstanceCategory.STIMULANT,
            defaultRoute = RouteOfAdministration.ORAL,
            routes = emptyList(),
        )
        s.defaultUnit shouldBe "mg"
    }

    // MARK: - Identity

    @Test
    fun `The id is derived from the name, so it survives reconstruction`() {
        val a = Substance(
            name = "Caffeine",
            category = SubstanceCategory.STIMULANT,
            defaultRoute = RouteOfAdministration.ORAL,
            routes = emptyList(),
        )
        val b = Substance(
            name = "Caffeine",
            category = SubstanceCategory.STIMULANT,
            defaultRoute = RouteOfAdministration.ORAL,
            routes = listOf(oralRoute),
        )
        // Same name, same id — that is what lets a list reuse a row when a search
        // narrows instead of rebuilding the whole collection each keystroke.
        (a.id == b.id) shouldBe true
        (a == b) shouldBe false // but they are not the same value
        Substance.deterministicID("Caffeine") shouldBe a.id
    }

    @Test
    fun `The id is case-insensitive on the name`() {
        Substance.deterministicID("MDMA") shouldBe Substance.deterministicID("mdma")
    }

    @Test
    fun `The derived id is a well-formed version 4 UUID`() {
        val id = Substance.deterministicID("Caffeine")
        id.version() shouldBe 4
        id.variant() shouldBe 2
    }

    // MARK: - Form resolution

    @Test
    fun `An explicit form narrows the ladder, and an absent one falls back`() {
        val magnesium = Substance(
            name = "Magnesium",
            category = SubstanceCategory.SUPPLEMENT,
            defaultRoute = RouteOfAdministration.ORAL,
            routes = listOf(
                SubstanceRoute(
                    route = RouteOfAdministration.ORAL,
                    unit = "mg",
                    doses = DoseRange(common = 200.0..400.0),
                    saltForms = listOf(
                        DoseVariant(saltForm = "Glycinate", unit = "mg", doses = DoseRange(common = 200.0..400.0)),
                        DoseVariant(saltForm = "Citrate", unit = "mg", doses = DoseRange(common = 400.0..600.0)),
                    ),
                ),
            ),
        )
        magnesium.availableSaltForms shouldBe listOf("Glycinate", "Citrate")
        magnesium.defaultSaltForm shouldBe "Glycinate"
        magnesium.doseRange(RouteOfAdministration.ORAL, "Citrate")?.common shouldBe 400.0..600.0
        // A salt nobody carries falls back to the route default rather than null,
        // so a form-unaware caller stays correct.
        magnesium.doseRange(RouteOfAdministration.ORAL, "Oxide")?.common shouldBe 200.0..400.0
        magnesium.doseRange(RouteOfAdministration.ORAL, null)?.common shouldBe 200.0..400.0
    }

    @Test
    fun `A salt has no elemental fraction until one is named`() {
        val route = SubstanceRoute(
            route = RouteOfAdministration.ORAL,
            unit = "mg",
            doses = DoseRange(),
            saltForms = listOf(
                DoseVariant(saltForm = "Citrate", unit = "mg", doses = DoseRange(), elementalFraction = 0.16),
            ),
        )
        val s = Substance(
            name = "Magnesium",
            category = SubstanceCategory.SUPPLEMENT,
            defaultRoute = RouteOfAdministration.ORAL,
            routes = listOf(route),
        )
        s.elementalFraction(RouteOfAdministration.ORAL, null) shouldBe null
        s.elementalFraction(RouteOfAdministration.ORAL, "Citrate") shouldBe 0.16
        s.elementalAmount(500.0, RouteOfAdministration.ORAL, "Citrate") shouldBe 80.0
        s.elementalAmount(500.0, RouteOfAdministration.ORAL, null) shouldBe null
    }

    // MARK: - Timeline duration

    @Test
    fun `A long-acting profile is withheld from the timeline but kept by resolve`() {
        // The distinction the type exists to draw: a detail table shows the raw
        // profile, a graph must not, because a multi-day curve stretches the
        // shared axis and crushes every real curve beside it.
        val longActing = DurationProfile(total = DurationRange(7_200.0, 7_200.0)) // five days
        val s = Substance(
            name = "Memantine",
            category = SubstanceCategory.DISSOCIATIVE,
            defaultRoute = RouteOfAdministration.ORAL,
            routes = listOf(
                SubstanceRoute(
                    route = RouteOfAdministration.ORAL,
                    unit = "mg",
                    doses = DoseRange(),
                    duration = longActing,
                ),
            ),
        )
        s.resolveDuration(RouteOfAdministration.ORAL) shouldBe longActing
        s.timelineDuration(RouteOfAdministration.ORAL) shouldBe null
    }

    @Test
    fun `A route without a profile borrows another route's rather than synthesizing`() {
        // Amphetamine rectal has no profile; borrowing the oral curve is far
        // closer to reality than a half-life synthesis would be, which would
        // stretch a ~10 h half-life into a ~45 h curve.
        val s = Substance(
            name = "Amphetamine",
            category = SubstanceCategory.STIMULANT,
            defaultRoute = RouteOfAdministration.ORAL,
            routes = listOf(oralRoute, SubstanceRoute(
                route = RouteOfAdministration.RECTAL,
                unit = "mg",
                doses = DoseRange(),
            )),
        )
        s.timelineDuration(RouteOfAdministration.RECTAL)?.onset?.midpoint shouldBe 22.5
    }

    // MARK: - Title

    @Test
    fun `The display title prefers a relabel, then a language, then the name`() {
        val plain = Substance(
            name = "Acetaminophen",
            category = SubstanceCategory.ANALGESIC,
            defaultRoute = RouteOfAdministration.ORAL,
            routes = emptyList(),
        )
        plain.displayTitle shouldBe "Acetaminophen"

        plain.copy(localizedName = "Paracetamol").displayTitle shouldBe "Paracetamol"
        plain.copy(regionalName = "Paracetamol").displayTitle shouldBe "Paracetamol"
        // A user relabel outranks every default.
        plain.copy(displayName = "Tylenol", localizedName = "Paracetamol").displayTitle shouldBe "Tylenol"
    }

    @Test
    fun `A leading pictograph is split off the title`() {
        val cake = Substance(
            name = "Cake",
            displayName = "🍰 Cake",
            category = SubstanceCategory.OTHER,
            defaultRoute = RouteOfAdministration.ORAL,
            routes = emptyList(),
        )
        cake.titleAndPictograph shouldBe ("Cake" to "🍰")
    }

    @Test
    fun `An ordinary name passes through the pictograph test unchanged`() {
        Substance.strippingLeadingPictograph("2C-B") shouldBe ("2C-B" to null)
        Substance.strippingLeadingPictograph("氯胺酮") shouldBe ("氯胺酮" to null)
        Substance.strippingLeadingPictograph("") shouldBe ("" to null)
    }

    // MARK: - Ordering

    @Test
    fun `Routes are ordered with the substance's own first`() {
        val ordered = substance.orderedRoutes
        ordered.size shouldBe RouteOfAdministration.entries.size
        ordered[0] shouldBe RouteOfAdministration.ORAL
        ordered[1] shouldBe RouteOfAdministration.INSUFFLATION
    }
}
