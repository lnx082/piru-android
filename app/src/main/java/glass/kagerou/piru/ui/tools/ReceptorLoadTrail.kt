package glass.kagerou.piru.ui.tools

import androidx.compose.ui.graphics.Color
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.LoadTrail
import glass.kagerou.piru.engine.ReceptorClasses
import glass.kagerou.piru.engine.ToleranceReplay
import glass.kagerou.piru.model.DoseUnit
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.model.SubstanceColorGenerator
import glass.kagerou.piru.ui.insights.UsageTimeRange
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The receptor-load trails a class plots over a window — the data behind every
 * "how hard has this mechanism been driven" chart.
 *
 * Extracted from `ReceptorLoadScreen`, which owned this while it was the only
 * screen drawing one. Two do now: that screen, and the Tolerance tool's own
 * over-time chart. The extraction is the *loading* only — each screen keeps its own
 * chart, because their gestures differ (the insights screen pans its window and
 * selects on tap; the tolerance chart carries the draggable cursor).
 *
 * Kept in the app module rather than `:core:engine` on purpose: [buildReceptorLoadSeries]
 * reads the database, the catalog and the profile, none of which the pure engine can
 * see. The arithmetic it delegates to — `LoadTrail.loadTrail` over
 * `ToleranceSimulation` — is the engine's, and is tested there.
 */

/** One sampled point of a class's load, dated on the wall clock. */
internal data class LoadPoint(val date: Instant, val load: Double)

/**
 * A class's trail, ready to draw.
 *
 * Carries the class rather than its label: [buildReceptorLoadSeries] runs outside
 * composition, and the label is a resource read, so it is resolved where the row is
 * drawn. [id] stays the wire value — that is what a caller's hidden-series set and
 * draw loop key on, and it must not move when the device language does.
 */
internal data class ReceptorLoadSeries(
    val id: String,
    val receptorClass: ReceptorClasses.ReceptorClass,
    val color: Color,
    val peak: Double,
    val points: List<LoadPoint>,
)

/**
 * A class whose peak over the window is at or below this is dropped.
 *
 * A line pinned to the floor is not a finding, it is noise with a legend entry.
 */
internal const val MINIMUM_PEAK: Double = 0.02

/** The classes drawn at most. Past a handful the plot is a comb and the legend a paragraph. */
internal const val MAXIMUM_SERIES: Int = 6

/**
 * Builds the trails for the most-toleranced classes.
 *
 * The classes come from a full replay (the same one the Tolerance tool runs), so the
 * ordering is by what is actually toleranced rather than by what happens to be in the
 * log — a class the user has never heard of can still be the one carrying the shift.
 *
 * @param futureHorizonMinutes how far past `now` to sample. Zero is the historic
 *   reading the insights screen wants; a forward horizon is what answers "where is
 *   this heading", which is the tolerance chart's question.
 */
internal suspend fun buildReceptorLoadSeries(
    app: PiruApplication,
    entries: List<DoseEntryEntity>,
    range: UsageTimeRange,
    futureHorizonMinutes: Double = 0.0,
): List<ReceptorLoadSeries> {
    val log = entries.mapNotNull { it.toToleranceSimDose() }
    if (log.isEmpty()) return emptyList()

    val pharmacology = app.catalog()
    val now = Instant.now()
    val nowMinutes = now.toEpochMilli() / 60_000.0
    val weight = app.profile().weightKgOrDefault()

    // The representatives are resolved alongside the logged names, never instead
    // of them: a PK-less substance is modelled as its class representative, and
    // that representative is usually not in the log at all.
    val params = pharmacology.pharmacologyForLog(
        log.map { it.substance }.toSet() + pharmacology.classRepresentativeNames(),
    )

    val cards = ToleranceReplay.simulate(log, params, nowMinutes, weight)
    val classes = cards.values
        .sortedByDescending { it.severity }
        .map { it.receptorClass }
        .take(MAXIMUM_SERIES)

    val pastHorizonSeconds = range.days?.let { it * 86_400.0 }
        ?: (now.toEpochMilli() - (entries.minOf { it.timestamp.time })).toDouble().div(1000.0)
    val stepSeconds = receptorLoadStep(
        forWindowSeconds = pastHorizonSeconds + futureHorizonMinutes * 60.0,
    )

    // The engine's lookback defaults to a year. A range longer than that would
    // sample past the replay window and draw as flat zero, which reads as "no
    // use" rather than "not computed" — so the lookback is widened to cover the
    // requested window.
    val lookbackDays = maxOf(365.0, pastHorizonSeconds / 86_400.0 + 1.0)

    val out = ArrayList<ReceptorLoadSeries>()
    for (receptorClass in classes) {
        val trail = LoadTrail.loadTrail(
            doses = log,
            params = params,
            now = now,
            weightKg = weight,
            receptorClass = receptorClass,
            horizonMinutes = futureHorizonMinutes,
            stepMinutes = stepSeconds / 60.0,
            pastHorizonMinutes = pastHorizonSeconds / 60.0,
            lookbackDays = lookbackDays,
        )
        val peak = trail.maxOfOrNull { it.load } ?: continue
        if (peak <= MINIMUM_PEAK) continue
        out += ReceptorLoadSeries(
            id = receptorClass.wireValue,
            receptorClass = receptorClass,
            color = receptorClassColor(receptorClass),
            peak = peak,
            points = trail.map { LoadPoint(it.date, it.load) },
        )
    }
    return out.sortedByDescending { it.peak }
}

/**
 * Sample spacing for the trail: coarser as the window widens, so a year's trace is
 * not an unreadable comb. Three hours is `loadTrail`'s own default and the spacing a
 * month of data wants.
 */
internal fun receptorLoadStep(forWindowSeconds: Double): Double = when {
    forWindowSeconds < 31 * 86_400.0 -> 3 * 3_600.0
    forWindowSeconds < 91 * 86_400.0 -> 6 * 3_600.0
    forWindowSeconds < 366 * 86_400.0 -> 12 * 3_600.0
    else -> maxOf(12 * 3_600.0, forWindowSeconds / 1_000.0)
}

/**
 * A base colour per mechanism class, drawn through the same generator the
 * substances use but seeded on the class.
 *
 * `OTHER` is the seed because a class is not a substance category: it takes the
 * achromatic branch, which spreads hues evenly around the wheel at low chroma —
 * exactly what a set of a dozen class labels wants. Shared with the Tolerance tool's
 * cards, so a class keeps one colour across both screens.
 */
internal fun receptorClassColor(receptorClass: ReceptorClasses.ReceptorClass): Color {
    val p3: P3Color = SubstanceColorGenerator.displayP3(SubstanceCategory.OTHER, "class:${receptorClass.wireValue}")
    return Color(p3.red.toFloat(), p3.green.toFloat(), p3.blue.toFloat(), 1f)
}

/**
 * A logged dose as the replay sees it.
 *
 * Two ordinary reasons to drop one, both of which are answers rather than errors: a
 * dose of unknown amount has no concentration to compute, and a dose in a unit that
 * is not a mass — millilitres, IU — has no milligram equivalent.
 */
internal fun DoseEntryEntity.toToleranceSimDose(): ToleranceReplay.SimDose? {
    if (isUnknownDose) return null
    val mg = DoseUnit.convert(amount, from = unit, to = "mg") ?: return null
    return ToleranceReplay.SimDose(
        substance = substance,
        amountMg = mg,
        timestampMinutes = timestamp.toInstant().toEpochMilli() / 60_000.0,
    )
}

/**
 * A trail's day label. `Locale.ROOT` was wrong here — it pins the month *name* to
 * English, so a Chinese device read "28 Sep". The app's own resolved locale supplies
 * the names, which the caller passes in; the pattern supplies the field order and
 * comes from the resources, because Chinese reads M月d日.
 */
internal fun shortTrailDate(instant: Instant, pattern: String, locale: Locale): String =
    DateTimeFormatter.ofPattern(pattern, locale).format(instant.atZone(ZoneId.systemDefault()))
