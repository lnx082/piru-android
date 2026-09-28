package glass.kagerou.piru.engine

import glass.kagerou.piru.model.ConfidenceTier
import glass.kagerou.piru.model.DoseRange
import glass.kagerou.piru.model.DoseVariant
import glass.kagerou.piru.model.DurationProfile
import glass.kagerou.piru.model.DurationRange
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.model.SubstanceRoute
import java.time.Instant

/**
 * Hand-built substances and a catalog that returns them.
 *
 * The iOS suites for this code call `SubstanceStore.shared.ensureAllLoaded()` and
 * assert against the real 1,688-row catalog. That is the right *end-to-end* check
 * and it lands with `:core:data`, over the driver the reader already has. What a
 * pure-JVM suite can do that the DB-backed one cannot is pin the *rules* —
 * which ladder a route falls back to, which form earns a curve — on substances
 * whose every number the test chose. So these tests do that, and the catalog
 * cases follow later against the real data.
 */
internal val TEST_TINT = P3Color(red = 0.917, green = 0.200, blue = 0.139)

internal val FALLBACK_TINT = P3Color(red = 0.929, green = 0.439, blue = 0.660)

/** A symmetric phase, so its midpoint is the number written here. */
internal fun span(minutes: Double) = DurationRange(minutes, minutes)

/**
 * A typical oral profile: onset 15, come-up 15, peak 90, offset 60 — 180 minutes
 * end to end.
 */
internal fun acute(
    onset: Double = 15.0,
    comeup: Double = 15.0,
    peak: Double = 90.0,
    offset: Double = 60.0,
    afterglow: Double? = null,
    total: Double? = null,
): DurationProfile = DurationProfile(
    onset = span(onset),
    comeup = span(comeup),
    peak = span(peak),
    offset = span(offset),
    afterglow = afterglow?.let { span(it) },
    total = total?.let { span(it) },
)

internal fun route(
    route: RouteOfAdministration = RouteOfAdministration.ORAL,
    unit: String = "mg",
    doses: DoseRange = DoseRange(),
    duration: DurationProfile? = null,
    saltForms: List<DoseVariant>? = null,
): SubstanceRoute = SubstanceRoute(
    route = route,
    unit = unit,
    doses = doses,
    duration = duration,
    saltForms = saltForms,
)

internal fun substance(
    name: String,
    category: SubstanceCategory = SubstanceCategory.STIMULANT,
    defaultRoute: RouteOfAdministration = RouteOfAdministration.ORAL,
    routes: List<SubstanceRoute> = listOf(route(defaultRoute, duration = acute())),
    halfLifeMinutes: Double? = null,
    aliases: List<String> = emptyList(),
    displayName: String? = null,
    substanceUID: String? = null,
): Substance = Substance(
    name = name,
    displayName = displayName,
    aliases = aliases,
    category = category,
    defaultRoute = defaultRoute,
    routes = routes,
    halfLifeMinutes = halfLifeMinutes,
    substanceUID = substanceUID,
)

internal fun dose(
    substance: String,
    amount: Double = 100.0,
    unit: String = "mg",
    route: RouteOfAdministration = RouteOfAdministration.ORAL,
    timestamp: Instant = Instant.ofEpochSecond(1_700_000_000),
    isUnknownDose: Boolean = false,
    releaseForm: String? = null,
    productName: String? = null,
    saltForm: String? = null,
    isomer: String? = null,
    substanceUID: String? = null,
): DoseRecord = DoseRecord(
    substance = substance,
    amount = amount,
    unit = unit,
    route = route,
    timestamp = timestamp,
    isUnknownDose = isUnknownDose,
    releaseForm = releaseForm,
    productName = productName,
    saltForm = saltForm,
    isomer = isomer,
    substanceUID = substanceUID,
)

/**
 * A [SubstanceCatalog] backed by maps.
 *
 * Lookups are case-insensitive on the name and alias, matching the real reader,
 * so a test can log a dose under the spelling a user would type.
 */
internal class FakeCatalog(
    substances: List<Substance> = emptyList(),
    private val uids: Map<String, String> = emptyMap(),
    private val esterFamilies: Map<String, List<EsterRecord>> = emptyMap(),
    private val productDurations: Map<String, DurationProfile> = emptyMap(),
    private val zeroOrder: Map<String, PKModel.ZeroOrderKinetics> = emptyMap(),
) : SubstanceCatalog {

    private val byName: Map<String, Substance> = buildMap {
        for (s in substances) {
            put(s.name.lowercase(), s)
            for (alias in s.aliases) putIfAbsent(alias.lowercase(), s)
        }
    }

    private val uidsByName: Map<String, String> = buildMap {
        for ((name, uid) in uids) put(name.lowercase(), uid)
        for (s in substances) s.substanceUID?.let { put(s.name.lowercase(), it) }
    }

    private val products: Map<String, DurationProfile> = buildMap {
        for ((name, profile) in productDurations) put(name.trim().lowercase(), profile)
    }

    override fun lookup(name: String): Substance? = byName[name.lowercase()]

    override fun substanceUID(name: String): String? = uidsByName[name.lowercase()]

    override fun esters(parentUID: String): List<EsterRecord> = esterFamilies[parentUID].orEmpty()

    override fun productDuration(productName: String): DurationProfile? =
        products[productName.trim().lowercase()]

    override fun zeroOrderKinetics(substanceName: String, weightKg: Double): PKModel.ZeroOrderKinetics? =
        zeroOrder[substanceName]
}

// MARK: - Tolerance contributors

/** The elimination rate constant the tolerance fixtures dose on: a 300-minute half-life. */
internal val TOLERANCE_KE: Double = kotlin.math.ln(2.0) / 300.0

/** Four times [TOLERANCE_KE], the absorption rate when no Tmax is known. */
internal val TOLERANCE_KA: Double = 4 * TOLERANCE_KE

/**
 * A contributor whose *peak* occupancy is [peakOccupancy].
 *
 * The prefactor is solved from the peak rather than written down, so a fixture says
 * "a dose that occupies a third of the target" instead of quoting a nanomolar
 * constant that happens to produce it.
 */
internal fun contributor(
    onset: Double = 0.0,
    peakOccupancy: Double = 0.3,
    halfMaxNanomolar: Double = 100.0,
    escalation: Double = 0.0,
    suppressesSynthesis: Boolean = false,
    intrinsicEfficacy: Double = 1.0,
    isMetabolite: Boolean = false,
    activeMinutes: Double? = null,
): ToleranceIntegrator.Contributor {
    val peakRatio = peakOccupancy / (1 - peakOccupancy)
    val prefactor = peakRatio * halfMaxNanomolar / PKModel.cmax(TOLERANCE_KE, TOLERANCE_KA)
    return ToleranceIntegrator.Contributor(
        onset = onset,
        expiry = onset + (activeMinutes ?: ToleranceIntegrator.decayWindowMinutes(
            TOLERANCE_KE, TOLERANCE_KA, prefactor, halfMaxNanomolar,
        )),
        ke = TOLERANCE_KE,
        ka = TOLERANCE_KA,
        prefactorNanomolar = prefactor,
        halfMaxNanomolar = halfMaxNanomolar,
        confidence = ConfidenceTier.HIGH,
        escalation = escalation,
        suppressesSynthesis = suppressesSynthesis,
        intrinsicEfficacy = intrinsicEfficacy,
        isMetabolite = isMetabolite,
    )
}
