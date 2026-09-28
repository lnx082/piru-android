package glass.kagerou.piru.engine

import glass.kagerou.piru.model.DoseRange
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.SubstanceCategory
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.jupiter.api.Test

/**
 * The body-load trail, swept over a window.
 *
 * Ported from the trail-sampling half of `PiruTests/BodyLevelsTests.swift`. Where
 * the iOS cases run against the real catalog and a manager that owns a clock, these
 * run against hand-built substances and an explicit `now`, which is what lets the
 * window, the grid and the causality rule be pinned one at a time.
 */
class BodyLoadTrailTest {

    private val now: Instant = Instant.ofEpochSecond(1_700_000_000)

    private val caffeine = substance(
        name = "Caffeine",
        category = SubstanceCategory.STIMULANT,
        defaultRoute = RouteOfAdministration.ORAL,
        routes = listOf(route(RouteOfAdministration.ORAL, doses = ladder(), duration = acute())),
        halfLifeMinutes = 300.0,
    )

    private val magnesium = substance(
        name = "Magnesium",
        category = SubstanceCategory.SUPPLEMENT,
        defaultRoute = RouteOfAdministration.ORAL,
        routes = listOf(route(RouteOfAdministration.ORAL, doses = ladder(), duration = acute())),
        halfLifeMinutes = 300.0,
    )

    private val catalog = FakeCatalog(listOf(caffeine, magnesium))

    private fun ladder() = DoseRange(threshold = 20.0, common = 60.0..150.0, heavy = 300.0)

    /** A dose [minutesFromNow] away from [now]; negative is in the past. */
    private fun doseAt(
        name: String,
        minutesFromNow: Double,
        amount: Double = 100.0,
        unit: String = "mg",
    ) = dose(
        substance = name,
        amount = amount,
        unit = unit,
        timestamp = now.plusSeconds((minutesFromNow * 60).toLong()),
    )

    private fun build(
        entries: List<DoseRecord>,
        pastMinutes: Double = 120.0,
        futureMinutes: Double = 120.0,
        stepMinutes: Double = 30.0,
    ) = BodyLoadTrail.build(
        entries = entries,
        colorMap = emptyMap(),
        catalog = catalog,
        fallbackTint = FALLBACK_TINT,
        now = now,
        pastMinutes = pastMinutes,
        futureMinutes = futureMinutes,
        stepMinutes = stepMinutes,
    )

    // MARK: - The shape of one curve

    @Test
    fun `A dose inside the window yields one series that runs the whole grid`() {
        val series = build(listOf(doseAt("Caffeine", -60.0)))
        series.size shouldBe 1
        val it = series.first()
        it.displayName shouldBe "Caffeine"
        it.unit shouldBe "mg"
        // Nine samples: -120 to +120 every 30 minutes, both edges inclusive.
        it.points.size shouldBe 9
        it.points.first().date shouldBe now.minusSeconds(7_200)
        it.points.last().date shouldBe now.plusSeconds(7_200)
    }

    @Test
    fun `Nothing is in the body before the dose, and the peak is the top of the scale`() {
        // The grid is -120, -90, -60, -30, 0, +30, +60, +90, +120 with the dose at
        // -60. The two samples strictly before it are on the floor; the one *at* -60
        // is not, because `fractionRemainingInBody(0, …)` is 1 — at the instant of
        // dosing nothing has been eliminated yet. A sample in the past is only empty
        // while the dose is still in the future.
        val it = build(listOf(doseAt("Caffeine", -60.0))).first()
        it.points.take(2).forEach { point -> point.amount shouldBe 0.0 }
        it.points[2].amount shouldBe 100.0
        // Normalised to its own peak, so the tallest sample is exactly 1.
        (it.points.maxOf { it.fraction }) shouldBe 1.0
        it.peak shouldBe it.points.maxOf { it.amount }
    }

    @Test
    fun `The fraction is the amount over the peak, sample by sample`() {
        val it = build(listOf(doseAt("Caffeine", -60.0))).first()
        for (point in it.points) {
            point.fraction shouldBe (point.amount / it.peak plusOrMinus 1e-12)
        }
    }

    // MARK: - Causality, in both directions

    @Test
    fun `A sample in the past sees only the doses that had already happened`() {
        // Two doses: one before the window opens (at -180, so 60 minutes of decay by
        // the first sample), one inside it. The early samples must match what the
        // first dose alone produces — a whole-log sampling that ignored the sample's
        // own instant would fold the second dose in and read high.
        val both = build(listOf(doseAt("Caffeine", -180.0), doseAt("Caffeine", -30.0))).first()
        val firstOnly = build(listOf(doseAt("Caffeine", -180.0))).first()
        both.points[0].amount shouldBe (firstOnly.points[0].amount plusOrMinus 1e-9)
        (both.points[0].amount > 0) shouldBe true
        // And by the last sample the second dose is in, so the combined trail is the
        // taller of the two — which is what makes the early equality a real claim.
        (both.peak > firstOnly.peak) shouldBe true
    }

    @Test
    fun `A dose that has not happened yet is absent from an earlier sample`() {
        // A future dose inside the window: the samples before it must read zero, and
        // the ones after it must see it. `compute`'s `elapsed < 0` guard is what does
        // this, inherited rather than re-implemented.
        val it = build(listOf(doseAt("Caffeine", 60.0))).first()
        // Grid index 4 is `now`, which is 60 minutes before the dose.
        it.points[4].amount shouldBe 0.0
        (it.points.last().amount > 0) shouldBe true
    }

    // MARK: - What is kept out

    @Test
    fun `A dose cleared before the window opens leaves nothing to draw`() {
        // Seven half-lives back: under 1 % at every sample, so every point is on the
        // floor and the series never existed. Dropped rather than drawn as a flat
        // zero line with a legend entry.
        build(listOf(doseAt("Caffeine", -300.0 * 7))).shouldBeEmpty()
    }

    @Test
    fun `A supplement is never drawn`() {
        // The engine's own exclusion, not this file's: supplements clear over
        // days-to-weeks, so a body-load trace for one is noise.
        build(listOf(doseAt("Magnesium", -60.0))).shouldBeEmpty()
    }

    @Test
    fun `No entries at all yields no trails`() {
        build(emptyList()).shouldBeEmpty()
    }

    // MARK: - Identity and order

    @Test
    fun `A volume dose is its own series, not summed into the mass one`() {
        // "5 mL" is not 5 mg of anything. Two series, matching the two rows the
        // "now" list already shows.
        val series = build(
            listOf(doseAt("Caffeine", -60.0, amount = 100.0, unit = "mg"), doseAt("Caffeine", -30.0, amount = 5.0, unit = "mL")),
        )
        series.size shouldBe 2
        series.map { it.unit }.toSet() shouldBe setOf("mg", "mL")
    }

    @Test
    fun `One substance logged in two mass units is one series, not two`() {
        // The entries arrive newest-first, which is how the DAO returns them.
        // `compute` groups a sample by name and quantity family, but the id it hands
        // back carries the *displayed* unit — whichever dose it met first — so at the
        // early samples only the older gram dose contributes ("g"), and at the later
        // ones the newer milligram dose is met first ("mg"). Keying the trail on that
        // id split one substance into two curves that handed off mid-window, which
        // is the opposite of this file's own "a curve starts and ends on the floor".
        val series = build(
            listOf(
                doseAt("Caffeine", -30.0, amount = 500.0, unit = "mg"),
                doseAt("Caffeine", -180.0, amount = 1.0, unit = "g"),
            ),
        )
        series.size shouldBe 1
        series.first().displayName shouldBe "Caffeine"
        // The window is covered whole — a handoff used to leave a gap in the middle.
        series.first().points.size shouldBe 9
        series.first().points.forEach { (it.amount > 0) shouldBe true }
    }

    @Test
    fun `Series are ordered by peak, biggest first`() {
        val series = build(
            listOf(doseAt("Caffeine", -60.0, amount = 100.0), doseAt("Caffeine", -30.0, amount = 5.0, unit = "mL")),
        )
        series.first().peak shouldBe series.maxOf { it.peak }
        (series[0].peak > series[1].peak) shouldBe true
    }

    // MARK: - The grid

    @Test
    fun `The sampling step coarsens with the window, and is capped by sample count`() {
        BodyLoadTrail.sampleStepMinutes(7 * 1_440.0) shouldBe 15.0
        BodyLoadTrail.sampleStepMinutes(30 * 1_440.0) shouldBe 60.0
        BodyLoadTrail.sampleStepMinutes(90 * 1_440.0) shouldBe 180.0
        BodyLoadTrail.sampleStepMinutes(365 * 1_440.0) shouldBe 720.0
        // A window past the steps' reach is clamped so the total work stays bounded.
        val huge = 10_000 * 1_440.0
        (BodyLoadTrail.sampleStepMinutes(huge) > 720.0) shouldBe true
    }

    @Test
    fun `A degenerate window or step yields no trails`() {
        build(listOf(doseAt("Caffeine", -60.0)), stepMinutes = 0.0).shouldBeEmpty()
        build(listOf(doseAt("Caffeine", -60.0)), pastMinutes = -1.0).shouldBeEmpty()
    }
}
