package glass.kagerou.piru.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.PKResolver
import glass.kagerou.piru.engine.SteadyStateModel
import glass.kagerou.piru.model.DoseUnit
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.doseFormatted
import glass.kagerou.piru.substance.DbSubstanceCatalog
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.toComposeColor
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import glass.kagerou.piru.ui.tools.Caption
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Steady State Projection — the plateau each regularly-dosed substance in the log
 * is heading for.
 *
 * Ported from `Piru/Views/Insights/SteadyStateProjection.swift` (264 lines):
 * `SteadyStateProjectionCard` and the `SteadyStateProjectionBuilder` that mines
 * each substance's median dose and interval out of the log and feeds
 * [SteadyStateModel].
 *
 * Upstream renders the card inside "In Your Body"'s steady-state section. Here it
 * is its own screen, because that section is not ported — the projection is a
 * reading of the log on its own terms and does not need the body-load chart above
 * it.
 *
 * ## Only a regular cadence yields a projection
 * Steady state is meaningless for one-off or bursty use, and a median over three
 * doses is not a schedule. The builder's thresholds — five doses minimum, an
 * interval coefficient of variation under 0.8, a median interval inside 3–96
 * hours — are all that stand between this card and a confident-looking plateau
 * derived from noise. They are not tunable knobs.
 *
 * [navigator] is unused: the cards push nothing.
 */
@Composable
fun SteadyStateProjectionScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication

    var projections by remember { mutableStateOf<List<SteadyStateProjection>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val catalog = app.catalog()
        val entries = app.database.doseEntryDao().all()
        val palette = app.palette()
        val names = entries.map { it.substance }
        val tints = palette.tintsFor(names)
        projections = SteadyStateProjectionBuilder.compute(entries, catalog, tints)
        loaded = true
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Steady state", style = MaterialTheme.typography.headlineSmall)
                Caption(
                    "Where each substance you take on a rhythm is heading. Only a regular " +
                        "cadence is projected — a burst of doses is not a schedule.",
                )
            }
        }

        if (loaded && projections.isEmpty()) {
            item {
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("No regular cadence yet", style = MaterialTheme.typography.titleSmall)
                        Caption(
                            "A projection needs at least five doses of one substance, spread at a " +
                                "steady interval. Log a few more on the same rhythm and it will appear here.",
                        )
                    }
                }
            }
        }

        items(projections, key = { it.id }) { projection ->
            SteadyStateProjectionCard(projection)
        }
    }
}

/**
 * One substance's accumulation curve to plateau.
 *
 * Ported from `SteadyStateProjectionCard`. "Buildup" is the trough-based
 * accumulation ratio — how many single doses' worth the plateau holds — and it is
 * reported only when it is meaningfully above one; otherwise the card says the
 * substance clears between doses, which is a different and equally useful answer.
 */
@Composable
private fun SteadyStateProjectionCard(projection: SteadyStateProjection) {
    val colors = PiruTheme.colors
    val accumulates = projection.result.accumulationRatio >= 1.15

    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // A filled square — the port's `LegendDot`, at the size the
                    // header uses. It carries the substance's own colour, which is
                    // the same one its curve wears.
                    Box(Modifier.size(12.dp).background(projection.color))
                    Text(projection.displayName, style = MaterialTheme.typography.titleSmall)
                }
                Caption(
                    "${doseFormatted(projection.medianDose)} ${projection.unit} · ${projection.cadenceText}",
                )
            }

            SteadyStateProjectionChart(projection)

            Row(modifier = Modifier.fillMaxWidth()) {
                StatColumn("Plateau", "${doseFormatted(projection.result.averageAmount)} ${projection.unit}")
                StatColumn("Peak", "${doseFormatted(projection.result.peakAmount)} ${projection.unit}")
                if (accumulates) {
                    StatColumn(
                        "Buildup",
                        "%.1f×".format(Locale.ROOT, projection.result.accumulationRatio),
                    )
                    StatColumn("Reaches", projection.daysToSteadyText)
                } else {
                    StatColumn("Between doses", "clears, no buildup")
                }
            }

            Caption(projection.summary)
            Caption("Predicted from a model, not measured. Not medical advice.")
        }
    }
}

@Composable
private fun RowScope.StatColumn(label: String, value: String) {
    Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = PiruTheme.colors.secondaryLabel,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The accumulation curve to plateau, in days, with the plateau as a dashed rule.
 *
 * Upstream draws it with Swift Charts (`AreaMark` + `LineMark` + a `RuleMark` at
 * `averageAmount`). The port draws it by hand, which is the build's general rule
 * for charts — and for a two-series chart over 601 points it costs less code than
 * the adapter would.
 */
@Composable
private fun SteadyStateProjectionChart(projection: SteadyStateProjection) {
    val color = projection.color
    val secondary = PiruTheme.colors.secondaryLabel
    val fillColor = color.copy(alpha = 0.18f)
    val ruleColor = color.copy(alpha = 0.5f)

    Box(Modifier.fillMaxWidth().height(130.dp)) {
        Canvas(Modifier.fillMaxWidth().height(130.dp)) {
            val curve = projection.result.curve
            if (curve.isEmpty()) return@Canvas

            val leftPad = 4.dp.toPx()
            val rightPad = 4.dp.toPx()
            val topPad = 6.dp.toPx()
            val bottomPad = 6.dp.toPx()
            val plotW = size.width - leftPad - rightPad
            val plotH = size.height - topPad - bottomPad
            if (plotW <= 0 || plotH <= 0) return@Canvas

            val xMax = projection.result.totalMinutes
            val yMax = maxOf(curve.maxOf { it.amount } * 1.05, projection.result.dose, 1e-6)

            fun x(minutes: Double) = leftPad + (minutes / xMax).toFloat() * plotW
            fun y(amount: Double) = topPad + plotH - (amount / yMax).toFloat() * plotH

            val fill = Path().apply {
                moveTo(x(0.0), topPad + plotH)
                for (point in curve) lineTo(x(point.minutes), y(point.amount))
                lineTo(x(curve.last().minutes), topPad + plotH)
                close()
            }
            drawPath(fill, fillColor)

            val line = Path().apply {
                curve.forEachIndexed { i, point ->
                    val px = x(point.minutes)
                    val py = y(point.amount)
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
            }
            drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

            // The plateau, as a reference line rather than a second series.
            val plateauY = y(projection.result.averageAmount)
            drawLine(
                color = ruleColor,
                start = Offset(leftPad, plateauY),
                end = Offset(leftPad + plotW, plateauY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
        }
    }
    // The colour is read above the canvas; the caption below keeps the axis
    // readable without a full labelled axis on a 130 dp card.
    Text(
        "days",
        style = MaterialTheme.typography.labelSmall,
        color = secondary,
    )
}

/**
 * One substance's inferred schedule and the plateau it reaches.
 *
 * Ported from `SteadyStateProjection`. `medianDose` is in [unit] and
 * `intervalHours` is the median gap between doses.
 */
internal data class SteadyStateProjection(
    val id: String,
    val displayName: String,
    val color: Color,
    val unit: String,
    val medianDose: Double,
    val intervalHours: Double,
    val result: SteadyStateModel.Result,
) {
    /** Days to about 95 % of steady state. */
    val daysToSteady: Double get() = result.time95 / 1_440

    /** The cadence in words, ported from `cadenceText`. */
    val cadenceText: String
        get() {
            val hours = intervalHours
            if (abs(hours - 24) < 3) return "about daily"
            if (hours in 44.0..52.0) return "about every 2 days"
            if (hours < 36) return "every ~${hours.toInt()} h"
            return "every ~${"%.1f".format(Locale.ROOT, hours / 24)} days"
        }

    /** "~3 days", or "<1 day" — ported from `daysToSteadyText`. */
    val daysToSteadyText: String
        get() = if (daysToSteady < 1) "<1 day" else "~${daysToSteady.toInt()} days"

    /** The one-line reading, ported from `summary`. */
    val summary: String
        get() {
            val plateau = "${doseFormatted(result.averageAmount)} $unit"
            if (result.accumulationRatio >= 1.15) {
                val ratio = "%.1f".format(Locale.ROOT, result.accumulationRatio)
                return "Plateaus around $plateau, ${ratio}× one dose, reached in $daysToSteadyText"
            }
            return "Clears between doses; each peaks around $plateau"
        }
}

/**
 * Mines each regularly-dosed substance's median dose and interval out of the log.
 *
 * Ported from `SteadyStateProjectionBuilder`. The window, the four thresholds and
 * the sort order are upstream's own constants; the only structural change is that
 * the catalog and the colour map arrive as parameters instead of through the
 * singletons upstream reaches for.
 */
internal object SteadyStateProjectionBuilder {

    /**
     * Window over which cadence is inferred — recent enough that a schedule the
     * user has since abandoned does not project a phantom plateau.
     */
    private const val LOOKBACK_DAYS = 120.0

    /** Minimum doses before a cadence is trustworthy. */
    private const val MINIMUM_DOSES = 5

    /** The interval coefficient of variation above which a schedule is too irregular to project. */
    private const val MAXIMUM_INTERVAL_CV = 0.8

    /** A cadence outside human dosing rhythm (hours) is noise, not a schedule. */
    private val INTERVAL_BOUNDS = 3.0..96.0

    fun compute(
        entries: List<DoseEntryEntity>,
        catalog: DbSubstanceCatalog,
        tintMap: Map<String, P3Color>,
        now: Instant = Instant.now(),
    ): List<SteadyStateProjection> {
        val cutoff = now.minusSeconds((LOOKBACK_DAYS * SECONDS_PER_DAY).toLong())

        val groups = mutableMapOf<String, Group>()
        for (entry in entries) {
            if (entry.timestamp.toInstant() < cutoff || entry.isUnknownDose) continue
            val substance = catalog.lookup(entry.substance)
            // An entry that names a release form the catalog has no authored
            // envelope for is not a plain repeated dose — its schedule is not the
            // one to project from.
            if (entry.releaseForm != null && catalog.productDuration(entry.productName.orEmpty()) == null) {
                continue
            }
            val name = substance?.name ?: entry.substance
            val key = name.lowercase()
            val existing = groups[key]
            if (existing != null) {
                val amount = DoseUnit.convert(entry.amount, from = entry.unit, to = existing.unit) ?: continue
                existing.amounts += amount
                existing.timestamps += entry.timestamp.toInstant()
            } else {
                groups[key] = Group(
                    name = name,
                    route = entry.route,
                    unit = entry.unit,
                    amounts = mutableListOf(entry.amount),
                    timestamps = mutableListOf(entry.timestamp.toInstant()),
                )
            }
        }

        val out = mutableListOf<SteadyStateProjection>()
        for (group in groups.values) {
            val projection = build(group, catalog, tintMap) ?: continue
            out += projection
        }
        // Most-accumulating first — the buildup is the headline.
        return out.sortedByDescending { it.result.accumulationRatio }
    }

    private fun build(
        group: Group,
        catalog: DbSubstanceCatalog,
        tintMap: Map<String, P3Color>,
    ): SteadyStateProjection? {
        if (group.timestamps.size < MINIMUM_DOSES) return null
        val sorted = group.timestamps.sorted()
        val intervals = sorted.zipWithNext { earlier, later ->
            (later.toEpochMilli() - earlier.toEpochMilli()) / 3_600_000.0
        }
        val medianInterval = median(intervals) ?: return null
        if (medianInterval !in INTERVAL_BOUNDS) return null
        val cv = coefficientOfVariation(intervals) ?: return null
        if (cv >= MAXIMUM_INTERVAL_CV) return null
        val medianDose = median(group.amounts) ?: return null
        if (medianDose <= 0) return null

        val substance = catalog.lookup(group.name)
        val params = PKResolver.params(substance, group.route) ?: return null
        val result = SteadyStateModel.compute(
            dose = medianDose,
            halfLifeMinutes = params.halfLifeMinutes,
            intervalMinutes = medianInterval * 60,
            ke = params.ke,
            ka = params.ka,
        ) ?: return null

        return SteadyStateProjection(
            id = group.name.lowercase(),
            displayName = substance?.displayTitle ?: group.name,
            color = (tintMap[group.name.lowercase()] ?: P3Color.NEUTRAL).toComposeColor(),
            unit = group.unit,
            medianDose = medianDose,
            intervalHours = medianInterval,
            result = result,
        )
    }

    /** Even-count medians average the middle pair, as upstream's does. */
    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }

    /**
     * The population coefficient of variation.
     *
     * Divided by `n` rather than `n − 1`: this is the spread of the doses actually
     * taken, not an estimate of a wider population's, and the two differ most
     * exactly where the sample is small enough for the gate to matter.
     */
    private fun coefficientOfVariation(values: List<Double>): Double? {
        if (values.size < 2) return null
        val mean = values.sum() / values.size
        if (mean <= 0) return null
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance) / mean
    }

    private class Group(
        val name: String,
        val route: RouteOfAdministration,
        val unit: String,
        val amounts: MutableList<Double>,
        val timestamps: MutableList<Instant>,
    )

    private const val SECONDS_PER_DAY = 86_400.0
}
