package glass.kagerou.piru.ui.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.ActiveSubstanceState
import glass.kagerou.piru.engine.DoseRecord
import glass.kagerou.piru.engine.DrugClass
import glass.kagerou.piru.engine.InteractionChecker
import glass.kagerou.piru.engine.InteractionPolicy
import glass.kagerou.piru.engine.InteractionResult
import glass.kagerou.piru.engine.InteractionSeverity
import glass.kagerou.piru.engine.PKModel
import glass.kagerou.piru.engine.PKResolver
import glass.kagerou.piru.engine.ReceptorClasses
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.engine.TimelineCurveModel
import glass.kagerou.piru.engine.from
import glass.kagerou.piru.model.BindingAction
import glass.kagerou.piru.model.ConfidenceTier
import glass.kagerou.piru.model.DoseUnit
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.substance.DbSubstanceCatalog
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.labels.appLocale
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One substance pair's pharmacokinetics: the two concentration curves, when they
 * overlap, and what the model makes of the combination.
 *
 * Ported from `Piru/Views/Tools/Interactions/InteractionTimelineView.swift`
 * (665 lines) and the `InteractionTimelineModel` in the same file.
 *
 * ## What it is, and what it is not
 * A **one-compartment oral model with population-average half-lives**. It says
 * when two doses are both present and roughly how much, and it says nothing about
 * the user's own metabolism, tolerance, or route. The footer says so in those
 * words, because a curve is the most persuasive thing this app draws and the
 * least entitled to be read as a measurement.
 *
 * ## The model as state, not as an object
 * Upstream's `@Observable InteractionTimelineModel` exists because SwiftUI needs a
 * reference to recompute against; its actual logic is `recompute()` guarded on
 * "have the inputs changed", and one-shot `autoDetect(entries:)`. Compose gets the
 * same behaviour from `remember` keyed on the inputs — the guard is the key list —
 * so there is no view model here to get out of step with the screen.
 *
 * ## The three readouts below the chart
 * The warning is the checker's own sentence for the pair, resolved the same way
 * the explorer's rows resolve theirs. The **combined depression index** is a
 * weighted sum of the depressant substances' engagement over time — the same
 * absolute-exposure → Hill occupancy the tolerance engine uses, or an
 * effect-curve surrogate where the catalog lacks a Vd, a molar mass or a Kᵢ. The
 * **effect attenuation** is the sign-flipped readout: an SSRI blunting an
 * empathogen is "it will not work as well", not "this is dangerous", and it must
 * never be shown as a safety margin. Both are ports of
 * `Piru/Data/Pharmacology/CombinedDepression.swift` and `EffectAttenuation.swift`,
 * which have no Kotlin home yet — they live in this file because this screen is
 * their only reader.
 *
 * ## Not carried: the exact pair the row was tapped from
 * Upstream's `NavigationLink` hands the timeline a `severity` and a `mechanism`
 * along with the two names, so a pair with two findings shows the one the reader
 * tapped. `PushRoute` has no entry carrying a substance pair, so this screen takes
 * the names and re-derives the pair's worst finding from the checker. When a pair
 * has both a class rule and an enzyme rule, the timeline shows the higher-scoring
 * of the two rather than the row that was tapped.
 *
 * @param nameA the left-hand substance, drawn in blue.
 * @param nameB the right-hand substance, drawn in orange.
 * @param onBack when set, a back affordance is drawn. Null when the host provides
 *   its own chrome — a pushed route, for instance.
 */
@Composable
fun InteractionTimelineScreen(
    nameA: String,
    nameB: String,
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val zone = remember { ZoneId.systemDefault() }

    var catalog by remember { mutableStateOf<DbSubstanceCatalog?>(null) }
    var checker by remember { mutableStateOf<InteractionChecker?>(null) }
    var recent by remember { mutableStateOf<List<DoseRecord>>(emptyList()) }
    var entriesLoaded by remember { mutableStateOf(false) }
    var didAutoDetect by remember { mutableStateOf(false) }

    val now = remember { Instant.now() }
    var ingestTimeA by remember { mutableStateOf(now) }
    var ingestTimeB by remember { mutableStateOf(now) }
    var matchedA by remember { mutableStateOf<MatchedDose?>(null) }
    var matchedB by remember { mutableStateOf<MatchedDose?>(null) }

    LaunchedEffect(Unit) {
        val opened = withContext(Dispatchers.Default) { app.catalog() }
        catalog = opened
        checker = InteractionChecker(opened, opened)
    }

    LaunchedEffect(navigator.dataVersion) {
        entriesLoaded = false
        // The catalog's own store is re-read on a data change too: a substance the
        // user just relabelled resolves differently, and the parameters below are
        // remembered on it.
        catalog = withContext(Dispatchers.Default) { app.catalog() }
        recent = withContext(Dispatchers.Default) {
            val cutoff = Instant.now().minus(Duration.ofHours(48))
            app.database.doseEntryDao().all()
                .filter { it.timestamp.toInstant().isAfter(cutoff) }
                // Newest first, which is what upstream's reverse-sorted `@Query`
                // gives and what the `firstOrNull` below reads as "the last one".
                .sortedByDescending { it.timestamp }
                .map { it.toDoseRecord() }
        }
        entriesLoaded = true
    }

    // Run once, like upstream's `guard !didAutoDetect`. It waits for the entries to
    // have loaded: the port's query is asynchronous where upstream's `@Query` is
    // already populated on the first body pass, and auto-detecting against an empty
    // list would silently pin both doses to "now" for the life of the screen.
    LaunchedEffect(entriesLoaded, recent, checker) {
        if (didAutoDetect || !entriesLoaded || checker == null) return@LaunchedEffect
        didAutoDetect = true
        recent.firstOrNull { it.substance.lowercase() == nameA.lowercase() }?.let { entry ->
            ingestTimeA = entry.timestamp
            matchedA = MatchedDose(entry.amount, entry.unit, entry.route)
        }
        recent.firstOrNull { it.substance.lowercase() == nameB.lowercase() }?.let { entry ->
            ingestTimeB = entry.timestamp
            matchedB = MatchedDose(entry.amount, entry.unit, entry.route)
        }
    }

    val paramsA = remember(catalog, nameA) { catalog?.let { resolveParams(it, nameA) } }
    val paramsB = remember(catalog, nameB) { catalog?.let { resolveParams(it, nameB) } }

    val chartData = remember(paramsA, paramsB, ingestTimeA, ingestTimeB) {
        if (paramsA != null && paramsB != null) {
            generateCurveData(paramsA, paramsB, ingestTimeA, ingestTimeB)
        } else {
            null
        }
    }

    val warning: InteractionResult? = remember(checker, nameA, nameB) {
        checker?.let { checkerForPair(it, nameA, nameB) }
    }

    // The pair's worst finding carries its own names; the screen draws the names it
    // was handed, so the legend, the chart and the warning header cannot disagree.
    val severity = warning?.severity

    val depression = remember(catalog, checker, matchedA, matchedB, ingestTimeA, ingestTimeB, nameA, nameB) {
        val cat = catalog
        val check = checker
        val a = matchedA
        val b = matchedB
        if (cat == null || check == null || a == null || b == null) {
            null
        } else {
            val entries = listOf(
                DoseRecord(nameA, a.amount, a.unit, a.route, ingestTimeA),
                DoseRecord(nameB, b.amount, b.unit, b.route, ingestTimeB),
            )
            // At least two contributors, or the index is one substance's own curve
            // wearing a combination's clothes.
            CombinedDepression.analyze(entries, WEIGHT_KG, cat, check)?.takeIf { it.totalCount >= 2 }
        }
    }

    val attenuations = remember(catalog, checker, matchedA, matchedB, ingestTimeA, ingestTimeB, nameA, nameB) {
        val cat = catalog
        val check = checker
        if (cat == null || check == null) {
            emptyList()
        } else {
            // The two doses as the pair's own record: an amount the log does not
            // carry becomes 1 in whatever unit was logged, which is upstream's
            // "present, magnitude unknown" stand-in.
            val entries = listOf(
                DoseRecord(
                    nameA,
                    matchedA?.amount ?: 1.0,
                    matchedA?.unit ?: "mg",
                    matchedA?.route ?: RouteOfAdministration.ORAL,
                    ingestTimeA,
                ),
                DoseRecord(
                    nameB,
                    matchedB?.amount ?: 1.0,
                    matchedB?.unit ?: "mg",
                    matchedB?.route ?: RouteOfAdministration.ORAL,
                    ingestTimeB,
                ),
            )
            EffectAttenuation.analyze(entries, cat, check)
        }
    }

    val missing = buildList {
        if (paramsA == null) add(nameA)
        if (paramsB == null) add(nameB)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = FAB_CLEARANCE),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    Icon(
                        Icons.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.timeline_back),
                        tint = PiruTheme.colors.secondaryLabel,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable(onClick = onBack),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(stringResource(R.string.timeline_title), style = MaterialTheme.typography.titleLarge)
            }
        }

        if (missing.isNotEmpty()) {
            item {
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = null,
                            tint = PiruTheme.colors.secondaryLabel,
                            modifier = Modifier.size(20.dp),
                        )
                        for (name in missing) {
                            Text(
                                stringResource(R.string.timeline_half_life_unavailable, name),
                                style = MaterialTheme.typography.bodyMedium,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                        }
                        Text(
                            stringResource(R.string.timeline_no_curve_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.tertiaryLabel,
                        )
                    }
                }
            }
        }

        if (chartData != null) {
            item {
                ConcentrationCard(
                    data = chartData,
                    nameA = nameA,
                    nameB = nameB,
                    referenceTime = minOf(ingestTimeA, ingestTimeB),
                    now = now,
                )
            }
            item {
                DetailsCard(
                    data = chartData,
                    nameA = nameA,
                    nameB = nameB,
                    paramsA = paramsA,
                    paramsB = paramsB,
                    ingestTimeA = ingestTimeA,
                    ingestTimeB = ingestTimeB,
                    hasRecentEntryA = recent.any { it.substance.lowercase() == nameA.lowercase() },
                    hasRecentEntryB = recent.any { it.substance.lowercase() == nameB.lowercase() },
                    severity = severity,
                    now = now,
                    zone = zone,
                    onTimeA = { ingestTimeA = it },
                    onTimeB = { ingestTimeB = it },
                )
            }
        }

        item {
            AnalysisCard(
                nameA = nameA,
                nameB = nameB,
                severity = severity,
                mechanism = warning?.description,
                depression = depression,
                attenuations = attenuations,
                referenceTime = minOf(ingestTimeA, ingestTimeB),
                zone = zone,
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.timeline_model_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
                // On the always-drawn footer rather than inside the depression card:
                // a caveat that disappears whenever the model finds nothing to say is
                // a caveat the reader will not have when the model does speak.
                // The disclaimer stays English — see the note in IdentifyScreen.
                Text(
                    stringResource(R.string.timeline_predicted_note) + " Not medical advice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.tertiaryLabel,
                )
            }
        }
    }
}

/** Profile weight the depressant index is scaled to, until the settings screen carries one. */
private val WEIGHT_KG = PKModel.REFERENCE_BODY_WEIGHT_KG

// MARK: - Curve model

/** Resolved one-compartment oral parameters for one side of the pair. */
private data class PkParams(
    val ke: Double,
    val ka: Double,
    val halfLifeMinutes: Double,
    val timeToPeakMinutes: Double,
)

private data class MatchedDose(
    val amount: Double,
    val unit: String,
    val route: glass.kagerou.piru.model.RouteOfAdministration,
)

private data class CurvePoint(val hours: Double, val concentration: Double)

/**
 * The two curves, their x-axis span, and the hours where both are above 3 % of
 * their own peak.
 *
 * The 3 % floor is what makes "both active" mean something a reader would
 * recognise: the tails of a one-compartment curve are asymptotic and never reach
 * zero, so without a floor every pair would overlap forever.
 */
private data class ChartData(
    val pointsA: List<CurvePoint>,
    val pointsB: List<CurvePoint>,
    val overlapHours: List<Double>,
    val totalHours: Double,
)

/**
 * Resolve a name to its PK parameters, or null when there is nothing to draw.
 *
 * Through `PKResolver` rather than by hand: `rateConstants` is the one home for
 * "ka from the duration profile's time-to-peak, else 4·ke", and a second copy
 * here is how the timeline and the half-life tool would come to disagree about
 * the same substance.
 */
private fun resolveParams(catalog: SubstanceCatalog, name: String): PkParams? {
    val substance = catalog.lookup(name) ?: return null
    val halfLife = PKResolver.halfLifeMinutes(substance) ?: return null
    val (ke, ka) = PKResolver.rateConstants(halfLife, substance.resolveDuration(substance.defaultRoute))
    return PkParams(
        ke = ke,
        ka = ka,
        halfLifeMinutes = halfLife,
        timeToPeakMinutes = PKModel.tmax(ke, ka),
    )
}

/**
 * Sample both curves on a shared grid, each normalized to its own Cmax so the two
 * shapes are comparable regardless of dose and half-life.
 *
 * Normalizing is what lets a 200 mg dose of a 3-hour drug and a 5 mg dose of a
 * 3-day one share an axis. It is also the reason the chart cannot be read as a
 * comparison of *amount*: both curves reach 100 %, and the numbers behind them
 * live in the card underneath.
 */
private fun generateCurveData(
    pA: PkParams,
    pB: PkParams,
    ingestTimeA: Instant,
    ingestTimeB: Instant,
): ChartData {
    val reference = minOf(ingestTimeA, ingestTimeB)
    val offsetAMinutes = minutesBetween(reference, ingestTimeA)
    val offsetBMinutes = minutesBetween(reference, ingestTimeB)

    // To 5 % of peak, seven half-lives out — the same "when is it gone" question
    // the half-life tool asks, and the reason the x-axis is not a fixed width.
    val tailA = PKModel.timeToFraction(0.05, pA.ke, pA.ka, maxMinutes = pA.halfLifeMinutes * 7)
    val tailB = PKModel.timeToFraction(0.05, pB.ke, pB.ka, maxMinutes = pB.halfLifeMinutes * 7)
    val totalMinutes = max(offsetAMinutes + tailA, offsetBMinutes + tailB)
    val totalHours = totalMinutes / 60.0

    val cmaxA = PKModel.cmax(pA.ke, pA.ka)
    val cmaxB = PKModel.cmax(pB.ke, pB.ka)

    val steps = 200
    val pointsA = ArrayList<CurvePoint>(steps + 1)
    val pointsB = ArrayList<CurvePoint>(steps + 1)
    val overlap = ArrayList<Double>()

    for (index in 0..steps) {
        val t = index.toDouble() / steps * totalMinutes
        val hours = t / 60.0

        val elapsedA = t - offsetAMinutes
        val concA = if (elapsedA >= 0 && cmaxA > 0) {
            max(0.0, PKModel.concentration(elapsedA, pA.ke, pA.ka) / cmaxA * 100.0)
        } else {
            0.0
        }

        val elapsedB = t - offsetBMinutes
        val concB = if (elapsedB >= 0 && cmaxB > 0) {
            max(0.0, PKModel.concentration(elapsedB, pB.ke, pB.ka) / cmaxB * 100.0)
        } else {
            0.0
        }

        pointsA += CurvePoint(hours, concA)
        pointsB += CurvePoint(hours, concB)
        if (concA > 3.0 && concB > 3.0) overlap += hours
    }

    return ChartData(pointsA, pointsB, overlap, totalHours)
}

/**
 * The checker's worst finding for a specific pair.
 *
 * `checkBatch` returns enzyme-layer results keyed by canonical names as well as
 * class-rule ones, so the match is on the unordered pair of lowercased names. The
 * list arrives sorted by display score, so the first match is the one a reader
 * should be shown.
 */
private fun checkerForPair(checker: InteractionChecker, nameA: String, nameB: String): InteractionResult? {
    val wanted = setOf(nameA.lowercase(), nameB.lowercase())
    return checker
        .checkBatch(listOf(nameA, nameB), against = emptyList(), policy = InteractionPolicy.EXPLORE)
        .firstOrNull { setOf(it.substanceA.lowercase(), it.substanceB.lowercase()) == wanted }
}

private fun minutesBetween(from: Instant, to: Instant): Double =
    Duration.between(from, to).toMillis() / 60_000.0

private fun DoseEntryEntity.toDoseRecord(): DoseRecord = DoseRecord(
    substance = substance,
    amount = amount,
    unit = unit,
    route = route,
    timestamp = timestamp.toInstant(),
    isUnknownDose = isUnknownDose,
    releaseForm = releaseForm,
    productName = productName,
    saltForm = saltForm,
    isomer = isomer,
    substanceUID = substanceUID,
)

// MARK: - Concentration chart

/**
 * The two curves, hand-drawn.
 *
 * `Color.blue` / `Color.orange` upstream, which are the two system colours; the
 * hexes below are what those resolve to, because Compose's `Color.Blue` is a
 * different, darker value and the point of the pair is that they are far apart in
 * hue and identical in weight.
 */
private val SeriesA = Color(0xFF007AFF)
private val SeriesB = Color(0xFFFF9500)

@Composable
private fun ConcentrationCard(
    data: ChartData,
    nameA: String,
    nameB: String,
    referenceTime: Instant,
    now: Instant,
) {
    // Hoisted out of the draw scope: a `@Composable` theme read cannot happen
    // inside `Canvas { … }`.
    val axis = PiruTheme.colors.secondaryLabel
    val nowMark = PiruTheme.colors.secondaryLabel.copy(alpha = 0.5f)
    val grid = PiruTheme.colors.secondaryLabel.copy(alpha = 0.2f)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 9.sp, color = axis)

    // From the same reference the curves are drawn against, so the "now" line
    // cannot drift off the curves it is meant to sit behind. Drawn only when it
    // falls inside the span — a marker pinned to either edge would be a lie about
    // where the moment is.
    val hoursNow = minutesBetween(referenceTime, now) / 60.0
    val showNow = hoursNow > 0.05 && hoursNow < data.totalHours

    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val gutterLeft = 30f
                    val gutterBottom = 14f
                    val plotWidth = size.width - gutterLeft
                    val plotHeight = size.height - gutterBottom
                    if (plotWidth <= 0f || plotHeight <= 0f) return@Canvas

                    fun x(hours: Double) = gutterLeft + plotWidth * (hours / data.totalHours).toFloat()
                    // 0 … 105 %, so the 100 % line is not the frame's own edge.
                    fun y(percent: Double) = plotHeight * (1f - (percent / 105.0).toFloat())

                    for (tick in listOf(0, 25, 50, 75, 100)) {
                        val lineY = y(tick.toDouble())
                        drawLine(grid, Offset(gutterLeft, lineY), Offset(size.width, lineY), strokeWidth = 1f)
                        drawText(
                            textMeasurer = measurer,
                            text = "$tick%",
                            topLeft = Offset(0f, lineY - 6f),
                            style = labelStyle,
                        )
                    }

                    val tickHours = axisTicks(data.totalHours)
                    for (tick in tickHours) {
                        drawText(
                            textMeasurer = measurer,
                            text = "${tick.toInt()}h",
                            topLeft = Offset(x(tick) - 6f, plotHeight + 1f),
                            style = labelStyle,
                        )
                    }

                    if (showNow) {
                        val markX = x(hoursNow)
                        drawLine(
                            nowMark,
                            Offset(markX, 0f),
                            Offset(markX, plotHeight),
                            strokeWidth = 1.5f,
                        )
                    }

                    drawSeries(data.pointsA, { hours -> x(hours) }, { percent -> y(percent) }, SeriesA)
                    drawSeries(data.pointsB, { hours -> x(hours) }, { percent -> y(percent) }, SeriesB)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendItem(SeriesA, nameA)
                LegendItem(SeriesB, nameB)
            }
        }
    }
}

/** One series' polyline. 200 samples, so the join at the peak is not visible. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeries(
    points: List<CurvePoint>,
    x: (Double) -> Float,
    y: (Double) -> Float,
    colour: Color,
) {
    if (points.size < 2) return
    val path = Path()
    points.forEachIndexed { index, point ->
        val px = x(point.hours)
        val py = y(point.concentration)
        if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    drawPath(path, colour, style = Stroke(width = 2.5f))
}

/** Tick hours for the x axis: every hour on a short span, a handful of whole hours on a long one. */
private fun axisTicks(totalHours: Double): List<Double> {
    if (totalHours <= 0) return listOf(0.0)
    val step = max(1.0, ceil(totalHours / 6.0))
    val ticks = ArrayList<Double>()
    var hour = 0.0
    while (hour <= totalHours) {
        ticks += hour
        hour += step
    }
    return ticks
}

@Composable
private fun LegendItem(colour: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .width(12.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(colour),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = PiruTheme.colors.secondaryLabel,
            maxLines = 1,
        )
    }
}

// MARK: - Details card

@Composable
private fun DetailsCard(
    data: ChartData,
    nameA: String,
    nameB: String,
    paramsA: PkParams?,
    paramsB: PkParams?,
    ingestTimeA: Instant,
    ingestTimeB: Instant,
    hasRecentEntryA: Boolean,
    hasRecentEntryB: Boolean,
    severity: InteractionSeverity?,
    now: Instant,
    zone: ZoneId,
    onTimeA: (Instant) -> Unit,
    onTimeB: (Instant) -> Unit,
) {
    // 48 hours back covers a dose that could still be onboard; 12 forward lets a
    // reader ask "what if I take it tonight" without turning the control into a
    // date picker.
    val earliest = now.minus(Duration.ofHours(48))
    val latest = now.plus(Duration.ofHours(12))

    val window = overlapWindow(data)

    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            SubstanceDetailRow(
                name = nameA,
                colour = SeriesA,
                time = ingestTimeA,
                hasRecentEntry = hasRecentEntryA,
                params = paramsA,
                earliest = earliest,
                latest = latest,
                zone = zone,
                onChange = onTimeA,
            )

            HorizontalDivider(modifier = Modifier.padding(start = 22.dp))

            SubstanceDetailRow(
                name = nameB,
                colour = SeriesB,
                time = ingestTimeB,
                hasRecentEntry = hasRecentEntryB,
                params = paramsB,
                earliest = earliest,
                latest = latest,
                zone = zone,
                onChange = onTimeB,
            )

            HorizontalDivider(modifier = Modifier.padding(start = 22.dp))

            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Neutral when the curves miss each other. The interaction is
                // documented either way, and a reassuring mark here would read as
                // "safe", which is a claim this model cannot make.
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = if (window != null) {
                        InteractionSeverityPalette.text(severity ?: InteractionSeverity.CAUTION)
                    } else {
                        PiruTheme.colors.secondaryLabel
                    },
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    if (window != null) {
                        stringResource(
                            R.string.timeline_both_active,
                            formatHours(window.first),
                            formatHours(window.second),
                            formatHours(window.second - window.first),
                        )
                    } else {
                        stringResource(R.string.timeline_no_overlap)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
    }
}

@Composable
private fun SubstanceDetailRow(
    name: String,
    colour: Color,
    time: Instant,
    hasRecentEntry: Boolean,
    params: PkParams?,
    earliest: Instant,
    latest: Instant,
    zone: ZoneId,
    onChange: (Instant) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(colour),
            )
            Spacer(Modifier.width(8.dp))
            Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))

            if (hasRecentEntry) {
                // A logged dose already fixes the time. Offering a control that
                // silently disagrees with the log would be worse than offering none.
                Text(
                    formatDateTime(
                        time,
                        zone,
                        LocalContext.current.getString(R.string.datefmt_day_month_year_time),
                        appLocale(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            } else {
                Text(
                    formatDateTime(
                        time,
                        zone,
                        LocalContext.current.getString(R.string.datefmt_day_month_year_time),
                        appLocale(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.accent,
                    modifier = Modifier.clickable { editing = true },
                )
            }
        }

        if (params != null) {
            Row(
                modifier = Modifier.padding(start = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.timeline_half_life_value, formatDuration(params.halfLifeMinutes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
                Text(
                    stringResource(R.string.timeline_peak_value, formatDuration(params.timeToPeakMinutes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        } else {
            Text(
                stringResource(R.string.timeline_no_half_life_row),
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.tertiaryLabel,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }

    if (editing) {
        DoseTimeDialog(
            initial = time,
            earliest = earliest,
            latest = latest,
            zone = zone,
            onDismiss = { editing = false },
            onConfirm = {
                onChange(it)
                editing = false
            },
        )
    }
}

/**
 * The dose-time control.
 *
 * Upstream shows a compact system `DatePicker` bound to a 48 h-back / 12 h-forward
 * range. Compose has no equivalent inline control, so this is a dialog with a
 * day stepper and a quarter-hour stepper, both clamped to the same range — the
 * resolution a dose time actually needs, and no date arithmetic for the user to
 * get lost in.
 */
@Composable
private fun DoseTimeDialog(
    initial: Instant,
    earliest: Instant,
    latest: Instant,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onConfirm: (Instant) -> Unit,
) {
    var value by remember { mutableStateOf(initial.coerceIn(earliest, latest)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.timeline_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { value = (value.minus(Duration.ofDays(1))).coerceIn(earliest, latest) }) {
                        Text(stringResource(R.string.timeline_dialog_minus_day))
                    }
                    Text(
                        formatDate(
                            value,
                            zone,
                            LocalContext.current.getString(R.string.datefmt_day_month_year),
                            appLocale(),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    TextButton(onClick = { value = (value.plus(Duration.ofDays(1))).coerceIn(earliest, latest) }) {
                        Text(stringResource(R.string.timeline_dialog_plus_day))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { value = (value.minus(Duration.ofMinutes(15))).coerceIn(earliest, latest) }) {
                        Text(stringResource(R.string.timeline_dialog_minus_quarter))
                    }
                    Text(formatClock(value, zone), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { value = (value.plus(Duration.ofMinutes(15))).coerceIn(earliest, latest) }) {
                        Text(stringResource(R.string.timeline_dialog_plus_quarter))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = PiruTheme.colors.secondaryLabel,
                        modifier = Modifier.size(16.dp),
                    )
                    TextButton(onClick = { value = Instant.now().coerceIn(earliest, latest) }) {
                        Text(stringResource(R.string.timeline_dialog_now))
                    }
                }
                Text(
                    stringResource(R.string.timeline_dialog_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) {
                Text(stringResource(R.string.timeline_dialog_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.timeline_dialog_cancel))
            }
        },
    )
}

/** `(start, end)` hours of the window where both curves are above 3 % of their peaks. */
private fun overlapWindow(data: ChartData): Pair<Double, Double>? {
    val first = data.overlapHours.firstOrNull() ?: return null
    val last = data.overlapHours.lastOrNull() ?: return null
    return first to last
}

// MARK: - Analysis card

@Composable
private fun AnalysisCard(
    nameA: String,
    nameB: String,
    severity: InteractionSeverity?,
    mechanism: String?,
    depression: CombinedDepressionResult?,
    attenuations: List<EffectAttenuationResult>,
    referenceTime: Instant,
    zone: ZoneId,
) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (severity != null) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = InteractionSeverityPalette.text(severity),
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = PiruTheme.colors.secondaryLabel,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (severity != null) {
                        Text(
                            stringResource(R.string.timeline_severity_pair, severity.label, nameA, nameB),
                            style = MaterialTheme.typography.labelLarge,
                            color = InteractionSeverityPalette.text(severity),
                        )
                        Text(
                            mechanism.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    } else {
                        // Reached only when the checker has no rule for the pair —
                        // a name it cannot resolve, or a pair the database does not
                        // cover. Said plainly rather than left as a blank card.
                        Text(
                            stringResource(R.string.timeline_pair, nameA, nameB),
                            style = MaterialTheme.typography.labelLarge,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                        Text(
                            stringResource(R.string.timeline_no_rule),
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                }
            }

            if (depression != null && depression.hasMeaningfulLoad) {
                HorizontalDivider(modifier = Modifier.padding(start = 36.dp))
                DepressionSection(depression, referenceTime, zone, modifier = Modifier.padding(16.dp))
            }

            for (attenuation in attenuations) {
                HorizontalDivider(modifier = Modifier.padding(start = 36.dp))
                AttenuationSection(attenuation, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

/**
 * The combined-depression index over time, with its peak marked.
 *
 * Every contributor is either real Hill occupancy at a depressant target or the
 * effect-curve surrogate, and the caveat under the chart says how many of each —
 * because "modeled from receptor occupancy" and "estimated from effect curves" are
 * different claims and the reader is entitled to know which one they are reading.
 */
@Composable
private fun DepressionSection(
    d: CombinedDepressionResult,
    referenceTime: Instant,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    val bandColour = InteractionSeverityPalette.text(d.band ?: InteractionSeverity.CAUTION)
    val fill = InteractionSeverityPalette.accent(d.band ?: InteractionSeverity.CAUTION)
    val thresholdMark = InteractionSeverityPalette.accent(InteractionSeverity.DANGEROUS).copy(alpha = 0.5f)
    // No `peakHours` here. It computed `minutesBetween(referenceTime, d.peakDate) / 60.0` and was
    // never read: the chart places its peak mark from `d.peakMinute`, the grid coordinate the
    // replay actually produced, and takes its axis from `lastMinute`. Arithmetic that looks
    // authoritative and is discarded is the kind of line that gets copied into the next screen.
    val yMax = max(CombinedDepression.DANGEROUS_THRESHOLD * 1.1, d.peakLoad * 1.1)
    val measurer = rememberTextMeasurer()
    val axis = PiruTheme.colors.secondaryLabel
    val labelStyle = TextStyle(fontSize = 9.sp, color = axis)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.timeline_depression_peaks, formatClock(d.peakDate, zone)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            d.levelLabel?.let { level ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(fill.copy(alpha = 0.10f))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(stringResource(level), style = MaterialTheme.typography.labelSmall, color = bandColour)
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(100.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val gutterBottom = 12f
                val plotHeight = size.height - gutterBottom
                if (plotHeight <= 0f) return@Canvas

                val points = d.points
                if (points.isEmpty()) return@Canvas
                val lastMinute = points.last().first
                if (lastMinute <= 0.0) return@Canvas

                fun x(minute: Double) = size.width * (minute / lastMinute).toFloat()
                fun y(load: Double) = plotHeight * (1f - (load / yMax).toFloat())

                // The area first, then the line over it: a filled shape alone reads
                // as a magnitude, and the line is what makes the peak locatable.
                val area = Path()
                area.moveTo(x(points.first().first), plotHeight)
                points.forEach { (minute, load) -> area.lineTo(x(minute), y(load)) }
                area.lineTo(x(points.last().first), plotHeight)
                area.close()
                drawPath(area, fill.copy(alpha = 0.18f))

                val line = Path()
                points.forEachIndexed { index, (minute, load) ->
                    val px = x(minute)
                    val py = y(load)
                    if (index == 0) line.moveTo(px, py) else line.lineTo(px, py)
                }
                drawPath(line, bandColour, style = Stroke(width = 2f))

                val thresholdY = y(CombinedDepression.DANGEROUS_THRESHOLD)
                drawLine(
                    thresholdMark,
                    Offset(0f, thresholdY),
                    Offset(size.width, thresholdY),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
                )
                val peakX = x(d.peakMinute.coerceAtMost(lastMinute))
                drawLine(
                    bandColour.copy(alpha = 0.6f),
                    Offset(peakX, 0f),
                    Offset(peakX, plotHeight),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 3f)),
                )

                val tickHours = axisTicks(lastMinute / 60.0)
                for (tick in tickHours) {
                    val tickMinute = tick * 60.0
                    if (tickMinute > lastMinute) continue
                    drawText(
                        textMeasurer = measurer,
                        text = "${tick.toInt()}h",
                        topLeft = Offset(x(tickMinute) - 6f, plotHeight + 1f),
                        style = labelStyle,
                    )
                }
            }
        }

        Text(
            depressionCaveat(d),
            style = MaterialTheme.typography.bodySmall,
            color = PiruTheme.colors.secondaryLabel,
        )
    }
}

/**
 * Ported verbatim from `InteractionTimelineView.depressionCaveat(_:)`. Three
 * sentences, one per honest case: fully modeled, entirely estimated, or a mix.
 */
@Composable
private fun depressionCaveat(d: CombinedDepressionResult): String {
    val confidence = confidenceLabel(d.confidence)
    if (d.isFullyModeled) {
        return stringResource(R.string.timeline_depression_caveat_modeled, confidence)
    }
    if (d.modeledCount == 0) {
        return stringResource(R.string.timeline_depression_caveat_estimated, confidence)
    }
    return stringResource(
        R.string.timeline_depression_caveat_mixed,
        d.modeledCount,
        d.totalCount,
        confidence,
    )
}

/**
 * Ported from `EffectAttenuationSection`. The sign-flipped readout: a blunted
 * empathogen is "it will not work as well", never a safety margin — an SSRI alone
 * *lowers* serotonin-syndrome odds, and the copy must not be readable as "so it is
 * fine to take more".
 */
@Composable
private fun AttenuationSection(a: EffectAttenuationResult, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.timeline_reduced_effect, a.reductionRangeText),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(
                R.string.timeline_blocks,
                joinedList(a.blockers),
                stringResource(a.transporter.displayNameRes),
                a.attenuated,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = PiruTheme.colors.secondaryLabel,
        )
    }
}

// MARK: - Combined depression

/**
 * Ported from `Piru/Data/Pharmacology/CombinedDepression.swift`.
 *
 * A single weighted load over time,
 * `L(t) = Σ wClass · doseWeight · e(t)`, from each depressant substance's
 * engagement, reported as its peak and when. The weights are **ordinal**, not a
 * physiological percentage: μ-opioid agonism anchors the scale at 1.0 because it
 * is the mechanism that stops breathing, and the tier below it is the GABAergic
 * one the leading-cause-of-death pairs are built from.
 *
 * ## Graceful degradation, per contributor
 * Real occupancy needs a graded Vd, a molar mass and a half-life, which only the
 * flagship substances carry. Each contributor independently uses occupancy where
 * it can and the effect-curve surrogate where it cannot, and the result counts how
 * many took which path. The surrogate is deliberately conservative: a known-dose
 * substance is treated as fully engaged at its effect peak, which errs toward
 * warning — the safe direction for a depression readout.
 */
private object CombinedDepression {

    const val CAUTION_THRESHOLD = 0.45
    const val UNSAFE_THRESHOLD = 0.85
    const val DANGEROUS_THRESHOLD = 1.15

    /** 15 minutes resolves the peak time finely without much cost over the bounded window. */
    const val TIMESTEP_MINUTES = 15.0

    /** A cap on the simulated span, so an outlier long-acting dose cannot make the grid unbounded. */
    private const val MAX_SPAN_MINUTES = 72.0 * 60

    /** Dose-presence smoothstep window, mirroring the checker's gate. */
    private const val PRESENCE_LOW = 0.04
    private const val PRESENCE_FULL = 0.25

    fun band(load: Double): InteractionSeverity? = when {
        load >= DANGEROUS_THRESHOLD -> InteractionSeverity.DANGEROUS
        load >= UNSAFE_THRESHOLD -> InteractionSeverity.UNSAFE
        load >= CAUTION_THRESHOLD -> InteractionSeverity.CAUTION
        else -> null
    }

    fun analyze(
        entries: List<DoseRecord>,
        weightKg: Double,
        catalog: DbSubstanceCatalog,
        checker: InteractionChecker,
    ): CombinedDepressionResult? {
        if (weightKg <= 0 || entries.isEmpty()) return null

        val resolved = entries.mapNotNull { resolveContributor(it, weightKg, catalog, checker) }
        if (resolved.isEmpty()) return null

        val gridStart = resolved.minOf { it.start }
        val rawEnd = resolved.maxOf { it.end }
        val span = min(minutesBetween(gridStart, rawEnd), MAX_SPAN_MINUTES)
        if (span <= 0) return null

        val steps = max(1, ceil(span / TIMESTEP_MINUTES).toInt())
        val sampleCount = steps + 1

        val contributors = resolved.map { r ->
            val offset = minutesBetween(gridStart, r.start)
            val engagement = DoubleArray(sampleCount) { index ->
                val sinceDose = index * TIMESTEP_MINUTES - offset
                if (sinceDose >= 0) r.engagement(sinceDose) else 0.0
            }
            Contributor(r.mechanism, r.doseWeight, r.confidence, r.isModeled, engagement)
        }

        return reduce(contributors, TIMESTEP_MINUTES, gridStart)
    }

    private fun reduce(
        contributors: List<Contributor>,
        dtMinutes: Double,
        gridStart: Instant,
    ): CombinedDepressionResult? {
        if (contributors.isEmpty() || dtMinutes <= 0) return null
        val count = contributors.maxOf { it.engagement.size }
        if (count <= 0) return null

        val loads = DoubleArray(count)
        for (contributor in contributors) {
            val weight = contributor.mechanism.weight * contributor.doseWeight
            if (weight <= 0) continue
            for (index in contributor.engagement.indices) {
                loads[index] += weight * max(0.0, contributor.engagement[index])
            }
        }

        var peakIndex = 0
        var peak = 0.0
        loads.forEachIndexed { index, value ->
            if (value > peak) {
                peak = value
                peakIndex = index
            }
        }

        val peakMinute = peakIndex * dtMinutes
        return CombinedDepressionResult(
            sampleLoads = loads.toList(),
            dtMinutes = dtMinutes,
            peakLoad = peak,
            peakMinute = peakMinute,
            peakDate = gridStart.plusMillis((peakMinute * 60_000).toLong()),
            band = band(peak),
            // A derived value is only as trustworthy as its weakest input, and the
            // tier ordering is ascending trust, so `min` is the floor.
            confidence = contributors.minOf { it.confidence },
            modeledCount = contributors.count { it.isModeled },
            totalCount = contributors.size,
        )
    }

    private class Contributor(
        val mechanism: DepressantMechanism,
        val doseWeight: Double,
        val confidence: ConfidenceTier,
        val isModeled: Boolean,
        val engagement: DoubleArray,
    )

    /** An entry resolved to a mechanism plus a closure giving engagement at minutes since its own dose. */
    private class Resolved(
        val mechanism: DepressantMechanism,
        val doseWeight: Double,
        val confidence: ConfidenceTier,
        val isModeled: Boolean,
        val start: Instant,
        val end: Instant,
        val engagement: (Double) -> Double,
    )

    private fun resolveContributor(
        entry: DoseRecord,
        weightKg: Double,
        catalog: DbSubstanceCatalog,
        checker: InteractionChecker,
    ): Resolved? {
        val doseMg = DoseUnit.convert(entry.amount, entry.unit, "mg")
        val params = catalog.pharmacologyParameters(entry.substance)

        // The occupancy path: the most potent engaged target that is itself a
        // depressant mechanism, when the absolute-exposure inputs and a dose are
        // all present.
        if (doseMg != null && doseMg > 0 && params.canComputeOccupancy) {
            val vdPerKg = params.vdLPerKg
            val molarMass = params.molarMassGramsPerMole
            val bioavailability = params.bioavailabilityFraction
            val halfLife = params.halfLifeMinutes
            if (vdPerKg != null && molarMass != null && bioavailability != null && halfLife != null) {
                val engaged = params.targets.firstNotNullOfOrNull { target ->
                    DepressantMechanism
                        .fromReceptorClass(ReceptorClasses.ReceptorClass.classify(target.target, target.action))
                        ?.let { it to target }
                }
                if (engaged != null) {
                    val vd = vdPerKg * weightKg
                    if (vd > 0 && molarMass > 0 && halfLife > 0) {
                        val (mechanism, target) = engaged
                        val ke = PKModel.keFromHalfLifeMinutes(halfLife)
                        val ka = PKModel.defaultKa(ke)
                        // nM, because the catalog stores every half-max in nM —
                        // dropping the 1e9 understates occupancy by a billion into a
                        // plausible-looking small number.
                        val prefactorNanomolar =
                            (bioavailability * doseMg * params.doseScale / vd) / 1_000.0 / molarMass * 1e9
                        val halfMax = target.halfMaxNanomolar
                        val tail = PKModel.timeToFraction(0.03, ke, ka, maxMinutes = halfLife * 8)
                        return Resolved(
                            mechanism = mechanism,
                            doseWeight = 1.0,
                            confidence = minOf(
                                params.vdConfidence,
                                params.bioavailabilityConfidence,
                                params.doseScaleConfidence,
                                target.confidence,
                            ),
                            isModeled = true,
                            start = entry.timestamp,
                            end = entry.timestamp.plusMillis((tail * 60_000).toLong()),
                            engagement = { sinceDose ->
                                val free = prefactorNanomolar * PKModel.concentration(sinceDose, ke, ka)
                                PKModel.occupancy(free, halfMax)
                            },
                        )
                    }
                }
            }
        }

        // The surrogate path: classify by drug class and ride the effect-curve
        // shape. This is what most depressants take.
        val mechanism = checker.drugClasses(entry.substance)
            .firstNotNullOfOrNull { DepressantMechanism.fromDrugClass(it) }
            ?: return null

        val state = ActiveSubstanceState.from(entry, P3Color.NEUTRAL, catalog) ?: return null
        val amountKnown = doseMg != null && entry.amount > 0
        val doseWeight = if (amountKnown) presence(state.doseMagnitude) else 1.0
        val endMinutes = max(state.offsetEndMinutes, state.totalMinutes)
        return Resolved(
            mechanism = mechanism,
            doseWeight = doseWeight,
            confidence = ConfidenceTier.LOW,
            isModeled = false,
            start = entry.timestamp,
            end = entry.timestamp.plusMillis((endMinutes * 60_000).toLong()),
            engagement = { sinceDose -> TimelineCurveModel.effectShape(sinceDose, state) },
        )
    }

    /** Smoothstep over `[PRESENCE_LOW, PRESENCE_FULL]`, matching the checker's gate. */
    private fun presence(magnitude: Double): Double {
        val t = min(1.0, max(0.0, (magnitude - PRESENCE_LOW) / (PRESENCE_FULL - PRESENCE_LOW)))
        return t * t * (3 - 2 * t)
    }
}

/** The additive depression families, each with its relative weight. */
private enum class DepressantMechanism(val weight: Double) {
    /** μ-opioid agonism — brainstem respiratory drive suppression. The killer; anchors the scale. */
    MU_OPIOID(1.0),

    /** GABA-A PAM / GABA-B (benzodiazepines, barbiturates, alcohol, GHB) — CNS depression. */
    GABAERGIC(0.8),

    /** Gabapentinoids — they potentiate opioid and CNS depression. */
    GABAPENTINOID(0.5),

    /** Dissociatives (ketamine, DXM) — additive CNS depression, and they mask overdose signs. */
    DISSOCIATIVE(0.35),

    /** Sedating antihistamines, antipsychotics, α2 agonists — additive sedation. */
    SEDATING_OTHER(0.3),
    ;

    companion object {

        /**
         * The mechanism an interaction drug class drives, for the surrogate path.
         *
         * Barbiturates share the GABAergic weight with benzodiazepines: same
         * mechanism family. The missing ceiling — which is why barbiturate pairings
         * sit a severity tier higher — is carried by the class rules, not here.
         */
        fun fromDrugClass(drugClass: DrugClass): DepressantMechanism? = when (drugClass) {
            DrugClass.OPIOID -> MU_OPIOID
            DrugClass.BENZODIAZEPINE, DrugClass.BARBITURATE, DrugClass.ALCOHOL, DrugClass.GHB -> GABAERGIC
            DrugClass.GABAPENTINOID -> GABAPENTINOID
            DrugClass.DISSOCIATIVE -> DISSOCIATIVE
            DrugClass.ANTIHISTAMINE, DrugClass.ANTIPSYCHOTIC, DrugClass.ALPHA2_AGONIST -> SEDATING_OTHER
            else -> null
        }

        /** The mechanism a tolerance receptor class drives, for the occupancy path. */
        fun fromReceptorClass(receptorClass: ReceptorClasses.ReceptorClass): DepressantMechanism? =
            when (receptorClass) {
                ReceptorClasses.ReceptorClass.MU_OPIOID -> MU_OPIOID
                ReceptorClasses.ReceptorClass.GABA -> GABAERGIC
                ReceptorClasses.ReceptorClass.NMDA_ANTAGONIST -> DISSOCIATIVE
                else -> null
            }
    }
}

/** The combined CNS / respiratory-depression readout over a shared timeline. */
private data class CombinedDepressionResult(
    val sampleLoads: List<Double>,
    val dtMinutes: Double,
    val peakLoad: Double,
    val peakMinute: Double,
    val peakDate: Instant,
    /** Null when the load never reaches [CombinedDepression.CAUTION_THRESHOLD]. */
    val band: InteractionSeverity?,
    val confidence: ConfidenceTier,
    val modeledCount: Int,
    val totalCount: Int,
) {
    /** True when every contributor used real occupancy, with no surrogate fallback. */
    val isFullyModeled: Boolean get() = modeledCount == totalCount

    val hasMeaningfulLoad: Boolean get() = band != null

    /** `(minuteFromStart, load)` samples for charting. */
    val points: List<Pair<Double, Double>>
        get() = sampleLoads.mapIndexed { index, load -> index * dtMinutes to load }

    /**
     * Wording kept distinct from the pair rule's own severity vocabulary, so the two
     * readouts do not visually clash when they disagree.
     */
    val levelLabel: Int?
        get() = when (band) {
            InteractionSeverity.DANGEROUS -> R.string.timeline_level_severe
            InteractionSeverity.UNSAFE -> R.string.timeline_level_high
            InteractionSeverity.CAUTION -> R.string.timeline_level_moderate
            null -> null
        }
}

// MARK: - Effect attenuation

/**
 * Ported from `Piru/Data/Pharmacology/EffectAttenuation.swift`.
 *
 * A releaser (MDMA-type) works by being carried *into* the neuron through a
 * transporter and reversing it. A reuptake blocker (an SSRI) occupies the
 * transporter and physically prevents that — so the releaser's effect is
 * *reduced*. This is a sign-flipped readout, deliberately separate from the
 * danger surfaces, and it must never be presented as a safety margin: SSRI + MDMA
 * alone **lowers** serotonin-syndrome odds, but the documented harm is taking
 * more to compensate.
 *
 * ## Why an evidence-anchored band and not a computed percentage
 * At the default unbound fraction the Hill occupancy of a sub-nanomolar-Kᵢ SSRI at
 * SERT saturates to ~100 %, which would over-predict blunting. So the reduction is
 * the curated band for the transporter, gated only on the two roles being
 * concurrently present.
 *
 * ## Not carried: the `attenuation_bands` read
 * Upstream loads the band from the database. This build's read layer does not
 * carry that table, and it holds exactly one row, so the SERT band below is
 * transcribed from `data/curated/attenuation-bands.json` — 30–80 %, pooled across
 * controlled human studies of SSRI pretreatment before MDMA by the Sarparast 2022
 * systematic review (doi:10.1007/s00213-022-06083-y). A second transporter would
 * need both a row and a value here.
 */
private object EffectAttenuation {

    /**
     * Fraction of peak level below which a blocker counts as no longer onboard.
     * 0.25 ≈ two half-lives: a chronically-dosed SSRI is hours from its last dose
     * and reads ~1, while a one-off weeks ago falls away. This replaces the short
     * subjective-effect window, because an antidepressant is *pharmacologically*
     * present far longer than its acute effects.
     */
    private const val ONBOARD_THRESHOLD = 0.25

    /** Half-life used for a blocker with no resolvable one: a conservative day. */
    private const val DEFAULT_BLOCKER_HALF_LIFE_MINUTES = 1_440.0

    private const val SERT_REDUCTION_LOW = 0.30
    private const val SERT_REDUCTION_HIGH = 0.80

    fun analyze(
        entries: List<DoseRecord>,
        catalog: DbSubstanceCatalog,
        checker: InteractionChecker,
    ): List<EffectAttenuationResult> {
        if (entries.size < 2) return emptyList()

        val roles = entries.map { entry ->
            Role(
                entry = entry,
                releaserTransporters = releaserTransporters(entry.substance, catalog, checker),
                isEmpathogen = checker.drugClasses(entry.substance).contains(DrugClass.EMPATHOGEN),
                blockerTransporters = blockerTransporters(entry.substance, catalog, checker),
                isAntidepressantBlocker = isAntidepressantBlocker(entry.substance, checker),
            )
        }

        val results = mutableListOf<EffectAttenuationResult>()
        for (releaser in roles) {
            if (releaser.releaserTransporters.isEmpty()) continue
            val releaserEnd = releaserEffectEnd(releaser.entry, catalog)
            for (transporter in releaser.releaserTransporters) {
                val blockers = roles.filter { candidate ->
                    candidate.entry.substance.lowercase() != releaser.entry.substance.lowercase() &&
                        candidate.isAntidepressantBlocker &&
                        transporter in candidate.blockerTransporters &&
                        blockerPresence(candidate.entry, releaser.entry.timestamp, releaserEnd, catalog) >=
                        ONBOARD_THRESHOLD
                }
                if (blockers.isEmpty()) continue
                val band = transporter.reductionBand ?: continue

                // De-duplicated, order preserved.
                val seen = mutableSetOf<String>()
                val blockerNames = blockers
                    .map { it.entry.substance }
                    .filter { seen.add(it.lowercase()) }

                results += EffectAttenuationResult(
                    attenuated = releaser.entry.substance,
                    blockers = blockerNames,
                    transporter = transporter,
                    reductionLow = band.first,
                    reductionHigh = band.second,
                    // HIGH for the directly-evidenced empathogen archetype, MEDIUM
                    // for a structurally detected non-empathogen releaser.
                    confidence = if (releaser.isEmpathogen) ConfidenceTier.HIGH else ConfidenceTier.MEDIUM,
                )
            }
        }
        // Most confident first. The tier ordering is ascending trust, so this is a
        // descending sort on trust rather than on ordinal.
        return results.sortedByDescending { it.confidence }
    }

    private class Role(
        val entry: DoseRecord,
        val releaserTransporters: Set<CompetingTransporter>,
        val isEmpathogen: Boolean,
        val blockerTransporters: Set<CompetingTransporter>,
        val isAntidepressantBlocker: Boolean,
    )

    /**
     * A blocker's fraction of peak level at the start of its overlap with the
     * releaser's active window — 0 when it never overlaps. Half-life decay, not the
     * subjective-effect curve: that is what lets a chronic SSRI taken days ago still
     * blunt tonight's dose.
     */
    private fun blockerPresence(
        blocker: DoseRecord,
        releaserStart: Instant,
        releaserEnd: Instant,
        catalog: DbSubstanceCatalog,
    ): Double {
        val overlapStart = maxOf(blocker.timestamp, releaserStart)
        if (overlapStart.isAfter(releaserEnd)) return 0.0
        val halfLife = catalog.lookup(blocker.substance)?.halfLifeMinutes
            ?.takeIf { it > 0 }
            ?: DEFAULT_BLOCKER_HALF_LIFE_MINUTES
        val elapsedMinutes = minutesBetween(blocker.timestamp, overlapStart)
        return 0.5.pow(elapsedMinutes / halfLife)
    }

    /** The end of a releaser's active window: its effect duration, then ~5 half-lives, then 6 h. */
    private fun releaserEffectEnd(entry: DoseRecord, catalog: DbSubstanceCatalog): Instant {
        val state = ActiveSubstanceState.from(entry, P3Color.NEUTRAL, catalog)
        if (state != null) {
            val endMinutes = max(state.offsetEndMinutes, state.totalMinutes)
            return entry.timestamp.plusMillis((endMinutes * 60_000).toLong())
        }
        val halfLife = catalog.lookup(entry.substance)?.halfLifeMinutes?.takeIf { it > 0 } ?: 360.0
        return entry.timestamp.plusMillis((max(halfLife * 5, 360.0) * 60_000).toLong())
    }

    /** Transporters a substance releases at: binding rows, plus the empathogen class fallback. */
    private fun releaserTransporters(
        name: String,
        catalog: DbSubstanceCatalog,
        checker: InteractionChecker,
    ): Set<CompetingTransporter> {
        val transporters = mutableSetOf<CompetingTransporter>()
        catalog.pharmacologyParameters(name).targets
            .filter { it.action == BindingAction.RELEASING_AGENT }
            .forEach { target -> CompetingTransporter.fromTarget(target.target)?.let { transporters += it } }
        if (checker.drugClasses(name).contains(DrugClass.EMPATHOGEN)) transporters += CompetingTransporter.SERT
        return transporters
    }

    /** Transporters a substance blocks reuptake at: binding rows, plus the antidepressant fallback. */
    private fun blockerTransporters(
        name: String,
        catalog: DbSubstanceCatalog,
        checker: InteractionChecker,
    ): Set<CompetingTransporter> {
        val transporters = mutableSetOf<CompetingTransporter>()
        catalog.pharmacologyParameters(name).targets
            .filter { it.action == BindingAction.REUPTAKE_INHIBITOR }
            .forEach { target -> CompetingTransporter.fromTarget(target.target)?.let { transporters += it } }
        if (isAntidepressantBlocker(name, checker)) transporters += CompetingTransporter.SERT
        return transporters
    }

    /**
     * Whether a substance is an antidepressant SERT blocker (SSRI/SNRI/TCA) — the
     * evidence anchor for the reduction band. **MAOIs are excluded**: they are the
     * genuine additive-toxicity edge, not a blunting competitor.
     */
    private fun isAntidepressantBlocker(name: String, checker: InteractionChecker): Boolean {
        val classes = checker.drugClasses(name)
        if (classes.contains(DrugClass.MAOI)) return false
        return classes.contains(DrugClass.SSRI) ||
            classes.contains(DrugClass.SNRI) ||
            classes.contains(DrugClass.TCA)
    }
}

/**
 * A monoamine transporter at which a releaser can be blunted by a co-present
 * reuptake blocker.
 *
 * v1 seeds only SERT — the cleanest evidenced case. DAT and NET are structurally
 * supported by the same role detection but ship no graded reduction band, and a
 * band is not something to improvise.
 */
private enum class CompetingTransporter(
    @StringRes val displayNameRes: Int,
    /** The evidence-anchored fractional-reduction band `(low, high)`, or null when none is curated. */
    val reductionBand: Pair<Double, Double>?,
) {
    SERT(R.string.timeline_transporter_sert, 0.30 to 0.80),
    ;

    companion object {
        /** Case-insensitive and substring-based, to absorb the DB's qualifying suffixes. */
        fun fromTarget(target: String): CompetingTransporter? {
            val lowered = target.lowercase()
            return if (lowered.contains("sert") || lowered.contains("serotonin transporter")) SERT else null
        }
    }
}

private data class EffectAttenuationResult(
    val attenuated: String,
    val blockers: List<String>,
    val transporter: CompetingTransporter,
    val reductionLow: Double,
    val reductionHigh: Double,
    val confidence: ConfidenceTier,
) {
    /** Upstream's `Identifiable` id — one result per blunted releaser at one transporter. */
    val id: String get() = "$attenuated|${transporter.name}"

    /** "30–80%" — an en dash, as upstream. */
    val reductionRangeText: String
        get() = "${(reductionLow * 100).roundToInt()}–${(reductionHigh * 100).roundToInt()}%"
}

// MARK: - Formatting

/**
 * `formatDuration` upstream. Locale.ROOT throughout: Swift's `String(format:)` is
 * locale-independent and Kotlin's is not, so a Turkish device would otherwise
 * render "1,5 h" where the model computed 1.5. The number is still formatted
 * here; only the unit word around it comes from a resource.
 */
@Composable
private fun formatDuration(minutes: Double): String {
    if (minutes < 60) return stringResource(R.string.timeline_duration_minutes, minutes.toInt())
    val hours = minutes / 60.0
    if (hours < 24) {
        return if (hours == hours.toLong().toDouble()) {
            stringResource(R.string.timeline_duration_hours, hours.toLong())
        } else {
            stringResource(
                R.string.timeline_duration_decimal_hours,
                String.format(Locale.ROOT, "%.1f", hours),
            )
        }
    }
    return stringResource(
        R.string.timeline_duration_days,
        String.format(Locale.ROOT, "%.1f", hours / 24.0),
    )
}

@Composable
private fun formatHours(hours: Double): String {
    if (hours < 1) return stringResource(R.string.timeline_hours_minutes, (hours * 60).toInt())
    return if (hours == hours.toLong().toDouble()) {
        stringResource(R.string.timeline_hours_short, hours.toLong())
    } else {
        stringResource(
            R.string.timeline_duration_decimal_hours,
            String.format(Locale.ROOT, "%.1f", hours),
        )
    }
}

/**
 * The dialog's date and clock. `Locale.ROOT` was wrong here: it localizes the
 * *names* to English, so a Chinese device read "28 Sep 2026". The app's own
 * resolved locale supplies the names, which the caller passes in, and the
 * resource supplies the field order — Chinese reads yyyy年M月d日 HH:mm.
 */
private fun formatDateTime(instant: Instant, zone: ZoneId, pattern: String, locale: Locale): String =
    DateTimeFormatter.ofPattern(pattern, locale).format(instant.atZone(zone))

private fun formatDate(instant: Instant, zone: ZoneId, pattern: String, locale: Locale): String =
    DateTimeFormatter.ofPattern(pattern, locale).format(instant.atZone(zone))

private fun formatClock(instant: Instant, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).format(instant.atZone(zone))

/**
 * `ListFormatter.localizedString(byJoining:)`: "A", "A and B", "A, B, and C".
 *
 * The joiners are resources rather than punctuation written here: Chinese
 * separates list items with "、" and closes with "和", so a hard-coded ", " is a
 * typographic error in the translation rather than a style choice.
 */
@Composable
private fun joinedList(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    2 -> stringResource(R.string.timeline_list_pair, names[0], names[1])
    else -> names.dropLast(1).joinToString(stringResource(R.string.timeline_list_separator)) +
        stringResource(R.string.timeline_list_last_separator) + names.last()
}
