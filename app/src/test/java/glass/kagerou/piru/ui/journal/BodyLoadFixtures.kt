package glass.kagerou.piru.ui.journal

import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.model.DoseRange
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.model.SubstanceRoute
import java.util.Date
import java.util.UUID

/**
 * The fixtures the body-load tests need.
 *
 * A local `SubstanceCatalog` rather than the engine's `FakeCatalog`: that one is `internal` to `:core:engine` and the
 * app module cannot see it. The interface is six methods and five of them are not used here.
 */
internal object BodyLoadFixtures {

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
        override fun esters(parentUID: String): List<glass.kagerou.piru.engine.EsterRecord> = emptyList()
        override fun productDuration(productName: String): glass.kagerou.piru.model.DurationProfile? = null
    }

    /**
     * A dose [minutesAgo] before [now].
     *
     * `now` defaults to the **real** clock, because that is what `ActiveSubstanceCalculator` measures elapsed time
     * against. Dating these from a fixed epoch made every dose years old to the calculator, which dropped all of
     * them — see this file's own header note.
     */
    fun entry(
        substance: String,
        amount: Double = 100.0,
        unit: String = "mg",
        minutesAgo: Long = 0,
        productName: String? = null,
        isUnknownDose: Boolean = false,
        now: java.time.Instant = java.time.Instant.now(),
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
