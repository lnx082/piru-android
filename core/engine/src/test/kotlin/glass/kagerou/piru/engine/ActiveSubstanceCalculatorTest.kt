package glass.kagerou.piru.engine

import glass.kagerou.piru.model.DoseRange
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.SubstanceCategory
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.jupiter.api.Test

/**
 * The body-load readout: what is still in the body, and what is explicitly kept
 * out of it.
 *
 * Ported from the body-load halves of `PiruTests/UnknownDoseTests.swift` and
 * `PiruTests/UnmodeledReleaseFormTests.swift`, plus the tier arithmetic from
 * `ActiveSubstanceCalculator`'s own contract. The iOS cases run against the real
 * catalog; these run against hand-built substances, which is what lets the
 * exclusion rules be tested one at a time instead of stacked.
 */
class ActiveSubstanceCalculatorTest {

    private val now: Instant = Instant.ofEpochSecond(1_700_000_000)

    private val caffeine = substance(
        name = "Caffeine",
        category = SubstanceCategory.STIMULANT,
        defaultRoute = RouteOfAdministration.ORAL,
        routes = listOf(route(RouteOfAdministration.ORAL, doses = ladder(), duration = acute())),
        halfLifeMinutes = 300.0,
        aliases = listOf("1,3,7-Trimethylxanthine"),
    )

    private val magnesium = substance(
        name = "Magnesium",
        category = SubstanceCategory.SUPPLEMENT,
        defaultRoute = RouteOfAdministration.ORAL,
        routes = listOf(route(RouteOfAdministration.ORAL, doses = ladder(), duration = acute())),
        halfLifeMinutes = 300.0,
    )

    private val catalog = FakeCatalog(listOf(caffeine, magnesium))

    private fun compute(
        entries: List<DoseRecord>,
        at: Instant = now,
        colorMap: Map<String, glass.kagerou.piru.model.P3Color> = emptyMap(),
    ) = ActiveSubstanceCalculator.compute(entries, colorMap, catalog, FALLBACK_TINT, at)

    private fun ladder() = DoseRange(threshold = 20.0, common = 60.0..150.0, heavy = 300.0)

    /** A dose [minutesAgo] before [now]. */
    private fun doseAgo(
        name: String,
        minutesAgo: Double,
        amount: Double = 100.0,
        unit: String = "mg",
        releaseForm: String? = null,
        productName: String? = null,
    ) = dose(
        substance = name,
        amount = amount,
        unit = unit,
        releaseForm = releaseForm,
        productName = productName,
        timestamp = now.minusSeconds((minutesAgo * 60).toLong()),
    )

    // MARK: - The ordinary case

    @Test
    fun `A recent dose is present with its amount, unit and name`() {
        val active = compute(listOf(doseAgo("Caffeine", 60.0)))
        active.size shouldBe 1
        val it = active.first()
        it.name shouldBe "Caffeine"
        // The unit the user logged, not the ladder's — see the gabapentin note.
        it.unit shouldBe "mg"
        it.totalDosed shouldBe 100.0
        (it.totalRemaining > 0) shouldBe true
        (it.totalRemaining < 100) shouldBe true
        (it.eliminatedFraction > 0) shouldBe true
        (it.eliminatedFraction < 1) shouldBe true
        it.doses.size shouldBe 1
    }

    @Test
    fun `The group is titled by the canonical name, whatever was logged`() {
        // A dose logged under an alias reads under the name the rest of the app
        // uses for it.
        val active = compute(listOf(doseAgo("1,3,7-Trimethylxanthine", 60.0)))
        active.first().name shouldBe "Caffeine"
    }

    @Test
    fun `A dose in the future is not yet in the body`() {
        compute(listOf(doseAgo("Caffeine", -60.0))).shouldBeEmpty()
    }

    @Test
    fun `A dose metabolized past the reporting floor drops out`() {
        // The floor is 3% remaining: below it the row would read "0% eliminated ·
        // clear in 4 minutes", which is noise rather than a reading.
        val halfLife = 300.0
        // Five half-lives leaves 3.125% — still on the card.
        (compute(listOf(doseAgo("Caffeine", halfLife * 5))).size) shouldBe 1
        // Seven half-lives leaves well under 1% — gone.
        compute(listOf(doseAgo("Caffeine", halfLife * 7))).shouldBeEmpty()
    }

    @Test
    fun `A newer dose outranks an older one`() {
        val active = compute(
            listOf(doseAgo("Caffeine", 600.0), doseAgo("Caffeine", 30.0)),
        )
        active.size shouldBe 1
        // Most-remaining first: the whole point of the sort is that the card's top
        // row is the one still doing something.
        active.first().doses.first().timestamp shouldBe now.minusSeconds(1_800)
    }

    // MARK: - What is kept out

    @Test
    fun `An unknown dose contributes nothing and does not suppress its neighbour`() {
        // From UnknownDoseTests. A dose of unknown amount has no number to decay,
        // so it is not a zero — it is absent. And its presence must not take the
        // neighbouring dose down with it.
        val unknown = dose("Caffeine", amount = 0.0, isUnknownDose = true, timestamp = now.minusSeconds(600))
        val known = dose("Caffeine", amount = 100.0, timestamp = now.minusSeconds(900))

        val active = compute(listOf(unknown, known))
        active.size shouldBe 1
        active.first().doses.size shouldBe 1
        active.first().totalDosed shouldBe 100.0
        compute(listOf(unknown)).shouldBeEmpty()
    }

    @Test
    fun `A supplement is left off entirely`() {
        // Supplements and vitamins clear over days-to-weeks, so "0% eliminated ·
        // clear in 5 months" is noise rather than session insight.
        compute(listOf(doseAgo("Magnesium", 60.0))).shouldBeEmpty()
    }

    @Test
    fun `An unmodeled form contributes no estimate, and does not suppress its neighbour`() {
        // The elimination half-life survives the delivery matrix, but the `ka` this
        // readout uses is the immediate-release absorption limb — so a dose
        // released over ~10 h would be modelled as landing at once, and the "clear
        // ~X" it prints would have no basis.
        compute(listOf(doseAgo("Caffeine", 60.0, releaseForm = "XR"))).shouldBeEmpty()

        val active = compute(listOf(doseAgo("Caffeine", 60.0, releaseForm = "XR"), doseAgo("Caffeine", 90.0)))
        active.size shouldBe 1
        active.first().doses.size shouldBe 1
    }

    @Test
    fun `A plain dose still contributes`() {
        (compute(listOf(doseAgo("Caffeine", 60.0))).isEmpty()) shouldBe false
    }

    @Test
    fun `A depot still contributes, exclusions notwithstanding`() {
        // The one exception to the unmodeled-form skip: a depot has no acute form
        // to model, and its slow persistence is exactly what a body-load readout
        // is *for*. `DEP` is an unmodeled release form, so this is a real branch.
        val paliperidone = substance(
            name = "Paliperidone",
            routes = listOf(route(RouteOfAdministration.INTRAMUSCULAR, doses = ladder())),
            halfLifeMinutes = 1_500.0,
        )
        val depotCatalog = FakeCatalog(listOf(paliperidone))
        val entry = dose(
            substance = "Paliperidone",
            amount = 400.0,
            route = RouteOfAdministration.INTRAMUSCULAR,
            releaseForm = "DEP",
            timestamp = now.minusSeconds(3_600),
        )
        val active = ActiveSubstanceCalculator.compute(
            listOf(entry), emptyMap(), depotCatalog, FALLBACK_TINT, now,
        )
        active.size shouldBe 1
        // And it reads as a depot, not as the molecule's fast elimination.
        active.first().halfLifeMinutes shouldBe PKResolver.DEFAULT_DEPOT_HALF_LIFE_DAYS * 24 * 60
    }

    @Test
    fun `A substance the catalog does not carry contributes nothing`() {
        // With no record there is no half-life, and a half-life is what this
        // readout is computed from. Not an error — a custom substance simply has
        // nothing to decay.
        compute(listOf(doseAgo("Unobtainium", 60.0))).shouldBeEmpty()
    }

    // MARK: - Unit families

    @Test
    fun `Mass units group together, converted into whichever arrived first`() {
        // µg/mg/g are one quantity. Summing 1 g with 500 mg as if the numbers were
        // comparable would report 501.
        val active = compute(
            listOf(
                doseAgo("Caffeine", 60.0, amount = 1.0, unit = "g"),
                doseAgo("Caffeine", 30.0, amount = 500.0, unit = "mg"),
            ),
        )
        active.size shouldBe 1
        val it = active.first()
        it.unit shouldBe "g"
        it.totalDosed shouldBe 1.5
        it.doses.size shouldBe 2
    }

    @Test
    fun `A volume dose is a different quantity and gets its own row`() {
        // "5 mL" is not 5 mg of anything, so it must not be summed into a mass
        // total. Two rows, not one.
        val active = compute(
            listOf(
                doseAgo("Caffeine", 60.0, amount = 100.0, unit = "mg"),
                doseAgo("Caffeine", 30.0, amount = 5.0, unit = "mL"),
            ),
        )
        active.size shouldBe 2
        active.map { it.unit }.toSet() shouldBe setOf("mg", "mL")
        // And the identity carries the unit, so a list can tell them apart.
        (active[0].id != active[1].id) shouldBe true
    }

    // MARK: - Presentation

    @Test
    fun `A substance the user has coloured takes that colour`() {
        val tint = glass.kagerou.piru.model.P3Color(red = 0.1, green = 0.2, blue = 0.3)
        val active = compute(listOf(doseAgo("Caffeine", 60.0)), colorMap = mapOf("caffeine" to tint))
        active.first().tint shouldBe tint
    }

    @Test
    fun `An uncoloured substance takes the fallback`() {
        val active = compute(listOf(doseAgo("Caffeine", 60.0)))
        active.first().tint shouldBe FALLBACK_TINT
    }
}
