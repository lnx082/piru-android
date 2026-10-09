package glass.kagerou.piru.ui.journal

import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.EsterRecord
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.model.DoseRange
import glass.kagerou.piru.model.DurationProfile
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.model.SubstanceRoute
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * The fixtures the body-load tests need.
 *
 * ## One clock, and why that needed saying
 * These tests went through three wrong clocks before they were stable, and each failed a different way:
 *
 * 1. **A fixed epoch** (November 2023) made every "minutes ago" a matter of years, so `ActiveSubstanceCalculator`
 *    dropped every dose and the sections came back empty. It measures elapsed time against `Instant.now()`, not
 *    against anything a test supplies.
 * 2. **A real-clock fixture with a separately captured model clock** left two instants that drift by milliseconds. The
 *    calculator's rule is `if (elapsed < 0) continue`, so an entry stamped *after* the model's `now` is a future dose
 *    and contributes nothing — which emptied a row intermittently, depending on how long the test took to reach the
 *    calculator.
 * 3. **A value captured once per class with entries stamped per call** had the same gap.
 *
 * So there is exactly one instant per run, [ANCHOR], and everything derives from it. It sits a minute in the past
 * because the calculator reads the **real** clock and cannot be told otherwise, so a stamp of `ANCHOR` is still safely
 * in that clock's past. The minute is far smaller than any duration asserted on.
 */
internal object BodyLoadFixtures {

    /**
     * The single instant everything in these tests is dated from.
     *
     * A `val` on an object, so it is the same for every case in a run.
     */
    val ANCHOR: Instant = Instant.now().minusSeconds(60)

    fun substance(
        name: String,
        halfLifeMinutes: Double? = 300.0,
        displayName: String? = null,
    ): Substance = Substance(
        name = name,
        displayName = displayName,
        category = SubstanceCategory.STIMULANT,
        defaultRoute = RouteOfAdministration.ORAL,
        routes = listOf(
            SubstanceRoute(
                route = RouteOfAdministration.ORAL,
                unit = "mg",
                doses = DoseRange(common = 1.0..10.0),
            ),
        ),
        halfLifeMinutes = halfLifeMinutes,
    )

    fun catalog(substances: List<Substance>): SubstanceCatalog = object : SubstanceCatalog {
        private val byName = substances.associateBy { it.name.lowercase() }
        override fun lookup(name: String): Substance? = byName[name.lowercase()]
        override fun substanceUID(name: String): String? = null
        override fun esters(parentUID: String): List<EsterRecord> = emptyList()
        override fun productDuration(productName: String): DurationProfile? = null
    }

    /**
     * A dose [minutesAgo] before [now], which defaults to [ANCHOR].
     *
     * The default is the point: an entry dated from a *fresher* instant than the model's `now` is a future dose to the
     * calculator, and is dropped.
     */
    fun entry(
        substance: String,
        amount: Double = 100.0,
        unit: String = "mg",
        minutesAgo: Long = 0,
        productName: String? = null,
        isUnknownDose: Boolean = false,
        now: Instant = ANCHOR,
    ) = DoseEntryEntity(
        id = UUID.randomUUID(),
        timestamp = Date(now.minusSeconds(minutesAgo * 60L).toEpochMilli()),
        substance = substance,
        amount = amount,
        unit = unit,
        route = RouteOfAdministration.ORAL,
        productName = productName,
        isUnknownDose = isUnknownDose,
    )

    val tint: P3Color = P3Color(0.5, 0.5, 0.5)
}
