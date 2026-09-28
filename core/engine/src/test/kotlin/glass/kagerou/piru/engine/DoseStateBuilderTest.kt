package glass.kagerou.piru.engine

import glass.kagerou.piru.model.DoseRange
import glass.kagerou.piru.model.DoseVariant
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.SubstanceCategory
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Turning a logged dose into something drawable, and deciding when there is
 * nothing honest to draw.
 *
 * Ported from `PiruTests/UnmodeledReleaseFormTests.swift`, `PiruTests/UnknownDoseTests.swift`
 * and the curve-building half of `PiruTests/ProductDurationTests.swift`. The
 * release-form predicate itself is pinned in `:core:model`'s `BaseReleaseFormTest`.
 */
class DoseStateBuilderTest {

    private val methylphenidate = substance(
        name = "Methylphenidate",
        category = SubstanceCategory.STIMULANT,
        defaultRoute = RouteOfAdministration.ORAL,
        routes = listOf(route(RouteOfAdministration.ORAL, doses = ladder(), duration = acute())),
        halfLifeMinutes = 150.0,
    )

    private val aripiprazole = substance(
        name = "Aripiprazole",
        category = SubstanceCategory.ANTIPSYCHOTIC,
        defaultRoute = RouteOfAdministration.ORAL,
        // Deliberately *with* an acute profile, unlike the real catalog: the depot
        // gate below must refuse it on the strength of the release form alone.
        routes = listOf(
            route(RouteOfAdministration.ORAL, doses = ladder(), duration = acute()),
            route(RouteOfAdministration.INTRAMUSCULAR, doses = ladder(), duration = acute()),
        ),
        halfLifeMinutes = 4_500.0,
    )

    private val lsd = substance(
        name = "LSD",
        category = SubstanceCategory.PSYCHEDELIC,
        defaultRoute = RouteOfAdministration.ORAL,
        routes = listOf(route(RouteOfAdministration.ORAL, doses = ladder(), duration = acute(onset = 30.0, comeup = 30.0, peak = 180.0, offset = 120.0))),
        halfLifeMinutes = 180.0,
        aliases = listOf("Lysergic Acid Diethylamide"),
    )

    private val vitamin = substance(
        name = "Vitamin D3",
        category = SubstanceCategory.SUPPLEMENT,
        defaultRoute = RouteOfAdministration.ORAL,
        routes = listOf(route(RouteOfAdministration.ORAL, doses = ladder())), // no duration
    )

    private val catalog = FakeCatalog(
        substances = listOf(methylphenidate, aripiprazole, lsd, vitamin),
        productDurations = mapOf(
            "Concerta" to acute(total = 720.0),
            "Adderall XR" to acute(total = 660.0),
        ),
    )

    private fun ladder() = DoseRange(threshold = 5.0, light = 5.0..10.0, common = 10.0..20.0, strong = 20.0..40.0, heavy = 40.0)

    private fun build(
        substance: String,
        amount: Double = 36.0,
        releaseForm: String? = null,
        productName: String? = null,
        route: RouteOfAdministration = RouteOfAdministration.ORAL,
        isUnknownDose: Boolean = false,
        saltForm: String? = null,
    ) = ActiveSubstanceState.from(
        dose(
            substance = substance,
            amount = amount,
            releaseForm = releaseForm,
            productName = productName,
            route = route,
            isUnknownDose = isUnknownDose,
            saltForm = saltForm,
        ),
        tint = TEST_TINT,
        catalog = catalog,
    )

    // MARK: - Suppression

    @Test
    fun `A dose with a number draws, and one of unknown amount does not`() {
        (build("Methylphenidate") != null) shouldBe true
        build("Methylphenidate", isUnknownDose = true).shouldBeNull()
    }

    @Test
    fun `An unmodeled form with no known product draws nothing`() {
        // Methylphenidate's duration rows are all the ~150–240 min immediate-release
        // profile. A dose tagged XR but naming no product we've authored could be
        // any of several formulations (Concerta 12 h, Ritalin LA 8 h…), so we draw
        // nothing rather than the IR curve.
        build("Methylphenidate", releaseForm = "XR").shouldBeNull()
    }

    @Test
    fun `Bare methylphenidate still draws its curve`() {
        // The control. Suppression must be scoped to the form the dose named — a
        // dose that named no form is exactly what the base ladder models.
        val state = build("Methylphenidate")!!
        (state.totalMinutes > 0) shouldBe true
    }

    @Test
    fun `A bare brand names no form, so it keeps the base curve`() {
        // `releaseForm` arrives nil for a bare brand — the PSID `0` sentinel.
        (build("Methylphenidate", productName = "Ritalin") != null) shouldBe true
    }

    @Test
    fun `A depot draws no acute curve even when the substance has a profile`() {
        // A depot releases over days-to-weeks. Borrowing the parent's acute profile
        // for it — estradiol's IM curve for an Estradiol Valerate depot — would be
        // a curve for something the dose is not.
        build("Aripiprazole", releaseForm = "DEP", productName = "Abilify Maintena", amount = 400.0)
            .shouldBeNull()
    }

    @Test
    fun `A substance with no acute profile draws nothing, however measurable`() {
        // The half-life is not an effect profile. Deriving a curve from elimination
        // kinetics drew a flat multi-week plateau over every real curve beside it
        // (fluoxetine's 16-day t½ → a 69-day "effect", `1,638h left`).
        build("Vitamin D3").shouldBeNull()
    }

    @Test
    fun `A substance the catalog does not carry draws nothing`() {
        build("Unobtainium").shouldBeNull()
    }

    // MARK: - Product envelopes

    @Test
    fun `A modeled ER product draws its own long curve`() {
        // The whole point of the product-duration table: Concerta is XR, which is
        // otherwise unmodeled, but its envelope is authored per product.
        val state = build("Methylphenidate", releaseForm = "XR", productName = "Concerta")!!
        (state.totalMinutes >= 600) shouldBe true
    }

    @Test
    fun `The product envelope keys on the name, case and spacing aside`() {
        (build("Methylphenidate", releaseForm = "XR", productName = "  concerta ") != null) shouldBe true
    }

    @Test
    fun `An XR naming an unauthored product stays a marker`() {
        // "Effexor XR" is a real product the catalog carries no envelope for.
        build("Methylphenidate", releaseForm = "XR", productName = "Ritalin LA").shouldBeNull()
    }

    @Test
    fun `The unmodeled-form predicate agrees with what the builder did`() {
        // `drawsNoAcuteCurve` is the predicate the UI branches on, so it has to
        // agree with `from` rather than being a second opinion about the same dose.
        val modeled = dose("Methylphenidate", releaseForm = "XR", productName = "Concerta")
        modeled.drawsNoAcuteCurve(catalog) shouldBe false
        val unmodeled = dose("Methylphenidate", releaseForm = "XR")
        unmodeled.drawsNoAcuteCurve(catalog) shouldBe true
        // IR is the base form, so it never counts as unmodeled.
        dose("Methylphenidate", releaseForm = "IR").drawsNoAcuteCurve(catalog) shouldBe false
    }

    // MARK: - Naming

    @Test
    fun `A dose logged under an alias is titled canonically`() {
        // So a dose logged as "Lysergic Acid Diethylamide" labels its curve "LSD"
        // like the rest of the app.
        build("Lysergic Acid Diethylamide")!!.substanceName shouldBe "LSD"
    }

    // MARK: - Dose tiers

    @Test
    fun `Intensity saturates at the heavy bound while magnitude does not`() {
        // The distinction the whole superposition rests on: 4×20 mg must equal
        // 1×80 mg, which only works if the stacked quantity is uncapped.
        val atHeavy = ActiveSubstanceCalculator.computeDoseIntensity(40.0, ladder())
        atHeavy shouldBe 1.0
        ActiveSubstanceCalculator.computeDoseMagnitude(40.0, ladder()) shouldBe 1.0

        val overdose = ActiveSubstanceCalculator.computeDoseIntensity(80.0, ladder())
        overdose shouldBe 1.0
        ActiveSubstanceCalculator.computeDoseMagnitude(80.0, ladder()) shouldBe 2.0
    }

    @Test
    fun `A sub-threshold dose keeps a visible nub`() {
        ActiveSubstanceCalculator.computeDoseIntensity(0.4, ladder()) shouldBe
            ActiveSubstanceCalculator.MINIMUM_INTENSITY
        ActiveSubstanceCalculator.computeDoseMagnitude(0.4, ladder()) shouldBe
            ActiveSubstanceCalculator.MINIMUM_INTENSITY
    }

    @Test
    fun `No ladder at all reads as a moderate, unscaled curve`() {
        ActiveSubstanceCalculator.computeDoseIntensity(100.0, null) shouldBe
            ActiveSubstanceCalculator.UNKNOWN_INTENSITY
        ActiveSubstanceCalculator.computeDoseMagnitude(100.0, null) shouldBe
            ActiveSubstanceCalculator.UNKNOWN_INTENSITY
        ActiveSubstanceCalculator.heavyReference(null).shouldBeNull()
    }

    @Test
    fun `The heavy reference loosens through strong, common, light and threshold`() {
        fun reference(range: DoseRange) = ActiveSubstanceCalculator.heavyReference(range)
        reference(DoseRange(heavy = 40.0)) shouldBe 40.0
        reference(DoseRange(strong = 20.0..40.0)) shouldBe 40.0
        reference(DoseRange(common = 10.0..20.0)) shouldBe 30.0
        reference(DoseRange(light = 5.0..10.0)) shouldBe 30.0
        reference(DoseRange(threshold = 5.0)) shouldBe 50.0
        reference(DoseRange()).shouldBeNull()
        // A zero bound is not a reference — it would make every dose infinite.
        reference(DoseRange(heavy = 0.0)).shouldBeNull()
    }

    @Test
    fun `Only a published heavy bound is a drawable threshold line`() {
        // The distinction `heavyReference` erases on purpose: an improvised
        // denominator gives an honest *ordering*, but a marked region names a line,
        // and only a source may draw one.
        ActiveSubstanceCalculator.heavyThresholdMagnitude(DoseRange(heavy = 40.0)) shouldBe 1.0
        ActiveSubstanceCalculator.heavyThresholdMagnitude(DoseRange(strong = 20.0..40.0)).shouldBeNull()
        ActiveSubstanceCalculator.heavyThresholdMagnitude(null).shouldBeNull()
        // And the state carries both sides of that distinction consistently.
        val published = build("Methylphenidate")!!
        published.heavyThresholdMagnitude shouldBe 1.0
        published.doseIsUnscaled shouldBe false
    }

    @Test
    fun `A ladder on another route stands in for the one logged`() {
        // Without this, a dose on a route the library has no data for reads at
        // fixed full height, collapsing the light/common/heavy distinction across
        // the whole journal.
        val sparse = substance(
            name = "Sparse",
            defaultRoute = RouteOfAdministration.ORAL,
            routes = listOf(
                route(RouteOfAdministration.ORAL, doses = DoseRange()),
                route(RouteOfAdministration.INSUFFLATION, doses = ladder()),
            ),
        )
        val resolved = ActiveSubstanceCalculator.resolveDoseRange(sparse, RouteOfAdministration.ORAL)!!
        resolved.heavy shouldBe 40.0
        // A ladder carrying nothing is not a ladder, so the fallback keeps looking.
        ActiveSubstanceCalculator.resolveDoseRange(
            substance("Bare", routes = listOf(route(RouteOfAdministration.ORAL, doses = DoseRange()))),
            RouteOfAdministration.ORAL,
        ).shouldBeNull()
    }

    // MARK: - Form resolution

    @Test
    fun `The curve follows the form actually logged, not the route default`() {
        // A D-isomer dose must not be drawn with the racemic curve the detail card
        // wouldn't show either.
        val dosed = substance(
            name = "Racemate",
            defaultRoute = RouteOfAdministration.ORAL,
            routes = listOf(
                route(
                    RouteOfAdministration.ORAL,
                    doses = ladder(),
                    duration = acute(peak = 90.0),
                    saltForms = listOf(
                        DoseVariant(unit = "mg", doses = ladder(), duration = acute(peak = 30.0), isomer = "D"),
                    ),
                ),
            ),
        )
        val formCatalog = FakeCatalog(listOf(dosed))
        val racemic = ActiveSubstanceState.from(dose("Racemate"), TEST_TINT, formCatalog)!!
        val dexter = ActiveSubstanceState.from(dose("Racemate", isomer = "D"), TEST_TINT, formCatalog)!!
        (dexter.totalMinutes < racemic.totalMinutes) shouldBe true
    }

    // MARK: - Zero-order elimination

    @Test
    fun `Zero-order kinetics replace the phase boundaries when the catalog has them`() {
        // Alcohol clears linearly, in a dose-scaled time, so the whole readout has
        // to track the same kinetics the curve draws rather than the fixed profile.
        val ethanol = substance(
            name = "Alcohol",
            category = SubstanceCategory.DEPRESSANT,
            defaultRoute = RouteOfAdministration.ORAL,
            routes = listOf(route(RouteOfAdministration.ORAL, doses = ladder(), duration = acute())),
            halfLifeMinutes = 300.0,
        )
        val kinetics = PKModel.zeroOrderKinetics(
            vmaxMgPerMin = 100.0,
            referenceWeightKg = 60.0,
            kaPerMin = 0.05,
            bioavailability = 1.0,
            weightKg = 70.0,
        )
        val zeroCatalog = FakeCatalog(listOf(ethanol), zeroOrder = mapOf("Alcohol" to kinetics))

        val entry = dose("Alcohol", amount = 28_000.0)
        val linear = ActiveSubstanceState.from(entry, TEST_TINT, zeroCatalog)!!
        val bell = ActiveSubstanceState.from(entry, TEST_TINT, FakeCatalog(listOf(ethanol)))!!

        // The profile says 180 minutes end to end; the zero-order model says the
        // dose clears in F·D/Vmax ≈ 240.
        bell.totalMinutes shouldBe 180.0
        (linear.totalMinutes > bell.totalMinutes) shouldBe true
        // A linear decline has no afterglow tail to fade into.
        linear.afterglowEndMinutes.shouldBeNull()
        // And the state carries the kinetics, so the off-thread curve math can
        // reproduce the same shape without a database.
        (linear.zeroOrder != null) shouldBe true
        (bell.zeroOrder == null) shouldBe true
    }

    @Test
    fun `A dose too small to out-pace zero-order clearance falls back to the bell`() {
        // F·D·ka ≤ Vmax means there is no real peak to model. The fallback is the
        // phase profile, not a fabricated linear decline.
        val ethanol = substance(
            name = "Alcohol",
            category = SubstanceCategory.DEPRESSANT,
            defaultRoute = RouteOfAdministration.ORAL,
            routes = listOf(route(RouteOfAdministration.ORAL, doses = ladder(), duration = acute())),
            halfLifeMinutes = 300.0,
        )
        val kinetics = PKModel.zeroOrderKinetics(
            vmaxMgPerMin = 100.0,
            referenceWeightKg = 60.0,
            kaPerMin = 0.05,
            bioavailability = 1.0,
            weightKg = 70.0,
        )
        val zeroCatalog = FakeCatalog(listOf(ethanol), zeroOrder = mapOf("Alcohol" to kinetics))
        // F·D·ka = 1.0 × 100 × 0.05 = 5 mg/min, well under Vmax ≈ 117.
        val state = ActiveSubstanceState.from(dose("Alcohol", amount = 100.0), TEST_TINT, zeroCatalog)!!
        state.totalMinutes shouldBe 180.0
    }

    // MARK: - Timeline split

    @Test
    fun `A modeled day keeps its curves and marks the rest`() {
        val inputs = ActiveSubstanceState.timeline(
            entries = listOf(
                dose("Methylphenidate", releaseForm = "XR"),
                dose("Methylphenidate", amount = 10.0),
            ),
            tintFor = { TEST_TINT },
            catalog = catalog,
        )
        inputs.states.size shouldBe 1
        inputs.markers.size shouldBe 1
    }

    @Test
    fun `A supplement with no profile stays off the graph entirely`() {
        // Not even a marker: with no duration there is no *effect* to plot, and a
        // daily vitamin is not an event. It remains in the entries list.
        val inputs = ActiveSubstanceState.timeline(
            entries = listOf(dose("Vitamin D3", amount = 1_000.0)),
            tintFor = { TEST_TINT },
            catalog = catalog,
        )
        inputs.states.isEmpty() shouldBe true
        inputs.markers.isEmpty() shouldBe true
    }

    @Test
    fun `A non-supplement with no data still gets its marker`() {
        // The user took a real psychoactive dose and the timestamp is the whole
        // point of what's left.
        val inputs = ActiveSubstanceState.timeline(
            entries = listOf(dose("Methylphenidate", releaseForm = "XR")),
            tintFor = { TEST_TINT },
            catalog = catalog,
        )
        inputs.states.isEmpty() shouldBe true
        inputs.markers.size shouldBe 1
        // Canonical name, so the marker's lane matching agrees with the curves.
        inputs.markers.first().substanceName shouldBe "Methylphenidate"
    }

    @Test
    fun `A depot lands as a marker, not as a curve`() {
        val inputs = ActiveSubstanceState.timeline(
            entries = listOf(dose("Aripiprazole", route = RouteOfAdministration.INTRAMUSCULAR, releaseForm = "DEP")),
            tintFor = { TEST_TINT },
            catalog = catalog,
        )
        inputs.states.isEmpty() shouldBe true
        inputs.markers.size shouldBe 1
    }

    @Test
    fun `The tint comes from the palette the caller supplies`() {
        val green = P3Color(red = 0.1, green = 0.9, blue = 0.2)
        val inputs = ActiveSubstanceState.timeline(
            entries = listOf(dose("Methylphenidate", amount = 10.0)),
            tintFor = { green },
            catalog = catalog,
        )
        inputs.states.first().tint shouldBe green
    }
}
