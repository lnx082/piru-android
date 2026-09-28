package glass.kagerou.piru.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import glass.kagerou.piru.ui.tools.AddLabResultDialog
import glass.kagerou.piru.ui.tools.Analyte
import glass.kagerou.piru.ui.tools.CalibrationChip
import glass.kagerou.piru.ui.tools.CalibrationControl
import glass.kagerou.piru.ui.tools.Caption
import glass.kagerou.piru.ui.tools.ConfidenceBadge
import glass.kagerou.piru.ui.tools.DepotCalibration
import glass.kagerou.piru.ui.tools.DepotCurveMiniChart
import glass.kagerou.piru.ui.tools.DepotMetricsCard
import glass.kagerou.piru.ui.tools.DepotSectionCard
import glass.kagerou.piru.ui.tools.EsterPKIndex
import glass.kagerou.piru.ui.tools.EsterPKIndexLoader
import glass.kagerou.piru.ui.tools.EsterPKRecord
import glass.kagerou.piru.ui.tools.FieldLabel
import glass.kagerou.piru.ui.tools.HormoneLevelsLog
import glass.kagerou.piru.ui.tools.HormoneLevelsModel
import glass.kagerou.piru.ui.tools.LabMeasurement
import glass.kagerou.piru.ui.tools.LabMeasurementStore
import glass.kagerou.piru.ui.tools.LabRow
import glass.kagerou.piru.ui.tools.MetricTile
import glass.kagerou.piru.ui.tools.NumberField
import glass.kagerou.piru.ui.tools.ReferenceLinesEditor
import glass.kagerou.piru.ui.tools.SectionDivider
import glass.kagerou.piru.ui.tools.SegmentedRow
import glass.kagerou.piru.ui.tools.SeriesSwatch
import glass.kagerou.piru.ui.tools.CompanionMeasurement
import glass.kagerou.piru.ui.tools.DepotPreferences
import glass.kagerou.piru.ui.tools.DepotCurveResult
import glass.kagerou.piru.ui.tools.AssumedDepotLevelsChart
import glass.kagerou.piru.ui.tools.DepotCurveChart
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Hormone Levels — the estimated **serum** hormone the user's logged injections
 * release, summed per ester and calibrated to their own labs.
 *
 * Ported from `Piru/Views/Insights/HormoneLevelsView.swift` (586 lines) and
 * `HormoneLevelsInsightCard.swift` (109 lines).
 *
 * Retrospective → now → a short projection. The prediction tool keeps the
 * hypothetical "if I start now" reasoning; this page is about what the log
 * already puts in the body, which is why it never synthesises a schedule and why
 * it sums esters instead of collapsing to a dominant one.
 *
 * It shows a row per analyte the user actually logs an ester for. It estimates a
 * level; it never suggests a dose or a target — the reference lines are the
 * user's own, and the shaded band is the published male total-T range with its
 * citation attached.
 */
@Composable
fun HormoneLevelsScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val preferences = remember { DepotPreferences(context) }
    val labStore = remember { LabMeasurementStore(context) }

    var model by remember { mutableStateOf<HormoneLevelsModel?>(null) }
    var catalog by remember { mutableStateOf<SubstanceCatalog?>(null) }
    var esters by remember { mutableStateOf(EsterPKIndex.EMPTY) }
    var entries by remember { mutableStateOf<List<DoseEntryEntity>>(emptyList()) }
    var labs by remember { mutableStateOf<List<LabMeasurement>>(emptyList()) }
    var loggedAnalytes by remember { mutableStateOf<List<Analyte>?>(null) }
    var showingAddLab by remember { mutableStateOf(false) }
    var addingCompanion by remember { mutableStateOf<CompanionMeasurement?>(null) }

    LaunchedEffect(Unit) {
        val substanceCatalog = app.catalog()
        val index = EsterPKIndexLoader.load(context)
        catalog = substanceCatalog
        esters = index
        entries = app.database.doseEntryDao().all()
        labs = labStore.all()
        val built = HormoneLevelsModel()
        built.personalMultiplier = preferences.personalMultiplier
        built.autoCalibrateFromLabs = preferences.autoCalibrate
        built.fitRates = preferences.fitRates
        model = built
    }

    // Read the per-ester grouping out of the log. Keyed on the analyte as well,
    // because the grouping is per analyte rather than global.
    LaunchedEffect(model, model?.analyte, entries, labs) {
        val current = model ?: return@LaunchedEffect
        val substanceCatalog = catalog ?: return@LaunchedEffect
        val concentration = preferences.volumeConcentration(current.analyte)
        val grouped = HormoneLevelsLog.grouped(
            entries = entries,
            analyte = current.analyte,
            volumeConcentrationMgPerML = concentration,
            esters = esters,
            catalog = substanceCatalog,
        )
        val measurements = labs
            .filter { it.analyteKey == current.analyte.key && !it.excludedFromCalibration }
            .map { DepotCalibration.Measurement(it.date, it.value) }
        current.sync(grouped, measurements)
    }

    // The analytes this user actually injects — the rows the page shows.
    LaunchedEffect(model, entries, esters, catalog) {
        val substanceCatalog = catalog ?: return@LaunchedEffect
        val withData = esters.analytesWithData()
        loggedAnalytes = withData.mapNotNull { Analyte.fromKey(it) }.filter { analyte ->
            val grouped = HormoneLevelsLog.grouped(
                entries = entries,
                analyte = analyte,
                volumeConcentrationMgPerML = preferences.volumeConcentration(analyte),
                esters = esters,
                catalog = substanceCatalog,
            )
            grouped.hasModelableInjections ||
                grouped.volumeLoggedCount > 0 ||
                grouped.catalogOnlyMarkers.isNotEmpty()
        }
    }

    LaunchedEffect(model?.recomputeKey) {
        model?.refresh()
    }

    val current = model
    val analytes = loggedAnalytes

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = FAB_CLEARANCE),
    ) {
        if (analytes != null && analytes.isEmpty()) {
            item { NoDataCard() }
            return@LazyColumn
        }
        if (current == null || analytes == null) return@LazyColumn

        if (analytes.size > 1) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FieldLabel("Hormone")
                    SegmentedRow(
                        options = analytes,
                        selected = current.analyte,
                        label = { it.displayName },
                        onSelect = { current.analyte = it },
                    )
                }
            }
        }

        val cautions = cautionsOf(current)
        if (cautions.isNotEmpty()) {
            item { EsterCautionCard(cautions) }
        }

        val result = current.result
        if (result != null) {
            item { SerumCurveCard(current, result) }
            item { DepotMetricsCard(result, current.analyte) }
            if (current.perEster.size > 1) {
                item { AssumedDepotLevelsCard(current.analyte, current.perEster) }
            }
        }

        if (current.catalogMarkers.isNotEmpty()) {
            item { CatalogEsterNote(current.catalogMarkers) }
        }

        val analyteLabs = labs.filter { it.analyteKey == current.analyte.key }
        for (companion in visibleCompanions(current.analyte, labs)) {
            item {
                CompanionSeriesCard(
                    measurement = companion,
                    points = companionPoints(companion, labs),
                    onAdd = { addingCompanion = companion },
                )
            }
        }

        item {
            HormoneLabCalibrationCard(
                model = current,
                labs = analyteLabs,
                onAdd = { showingAddLab = true },
                onToggleExclude = { lab ->
                    labStore.setExcluded(lab.id, !lab.excludedFromCalibration)
                    labs = labStore.all()
                },
                onDelete = { lab ->
                    labStore.delete(lab.id)
                    labs = labStore.all()
                },
                onAutoCalibrateChange = {
                    current.autoCalibrateFromLabs = it
                    preferences.autoCalibrate = it
                },
                onFitRatesChange = {
                    current.fitRates = it
                    preferences.fitRates = it
                },
                onMultiplierChange = {
                    current.personalMultiplier = it
                    preferences.personalMultiplier = it
                },
            )
        }

        item { ProvenanceCard(current.analyte, current.perEster.map { it.first }) }
        item { ExplanationCard() }
    }

    if (showingAddLab && current != null) {
        AddLabResultDialog(
            analyte = current.analyte,
            esterID = null,
            onSave = { measurement ->
                labStore.insert(measurement)
                labs = labStore.all()
                showingAddLab = false
            },
            onDismiss = { showingAddLab = false },
        )
    }

    val companion = addingCompanion
    if (companion != null) {
        AddCompanionMeasurementDialog(
            measurement = companion,
            onSave = { measurement ->
                labStore.insert(measurement)
                labs = labStore.all()
                addingCompanion = null
            },
            onDismiss = { addingCompanion = null },
        )
    }
}

/**
 * The Insights landing card: a legend-less three-month serum preview that pushes
 * to [HormoneLevelsScreen].
 *
 * Ported from `HormoneLevelsInsightCard`. It renders only for someone who logs an
 * injectable estradiol or testosterone ester — the parent mounts it on that
 * condition, per upstream's own note that a `.task` on an empty group never
 * fires. The shell always renders, so the card's own load always runs.
 */
@Composable
fun HormoneLevelsInsightCard(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val preferences = remember { DepotPreferences(context) }
    val labStore = remember { LabMeasurementStore(context) }
    val tint = Color(0xFFEC407A)
    val colors = PiruTheme.colors

    var model by remember { mutableStateOf<HormoneLevelsModel?>(null) }
    var esters by remember { mutableStateOf(EsterPKIndex.EMPTY) }
    var catalog by remember { mutableStateOf<SubstanceCatalog?>(null) }
    var entries by remember { mutableStateOf<List<DoseEntryEntity>>(emptyList()) }
    var labs by remember { mutableStateOf<List<LabMeasurement>>(emptyList()) }

    LaunchedEffect(Unit) {
        val substanceCatalog = app.catalog()
        val index = EsterPKIndexLoader.load(context)
        catalog = substanceCatalog
        esters = index
        entries = app.database.doseEntryDao().all()
        labs = labStore.all()
        val built = HormoneLevelsModel()
        built.personalMultiplier = preferences.personalMultiplier
        built.autoCalibrateFromLabs = preferences.autoCalibrate
        built.fitRates = preferences.fitRates
        model = built
    }

    // The card previews the analyte whose log has the most recent injection — the
    // one the person is actively on — falling back to any analyte with a curve.
    LaunchedEffect(model, entries, labs, esters, catalog) {
        val current = model ?: return@LaunchedEffect
        val substanceCatalog = catalog ?: return@LaunchedEffect
        val candidates = esters.analytesWithData()
            .mapNotNull { Analyte.fromKey(it) }
            .map { analyte ->
                analyte to HormoneLevelsLog.grouped(
                    entries = entries,
                    analyte = analyte,
                    volumeConcentrationMgPerML = preferences.volumeConcentration(analyte),
                    esters = esters,
                    catalog = substanceCatalog,
                )
            }
            .filter { it.second.hasModelableInjections }

        val chosen = candidates.maxByOrNull { it.second.allInjections.lastOrNull()?.date ?: Instant.EPOCH }
        if (chosen == null) {
            current.sync(HormoneLevelsLog.Grouped(), emptyList())
            return@LaunchedEffect
        }
        val (analyte, grouped) = chosen
        current.analyte = analyte
        val measurements = labs
            .filter { it.analyteKey == analyte.key && !it.excludedFromCalibration }
            .map { DepotCalibration.Measurement(it.date, it.value) }
        current.sync(grouped, measurements)
    }

    LaunchedEffect(model?.recomputeKey) { model?.refresh() }

    // Upstream makes the whole card a `NavigationLink` into the insight. That
    // route does not exist in this build's `PushRoute` yet, so the card is inert
    // and says so rather than pushing the user somewhere unrelated. [navigator]
    // is taken now so wiring it later is a one-line change at the call site.
    val current = model
    val result = current?.result
    PiruCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Hormone Levels", style = MaterialTheme.typography.titleSmall, color = tint)
                if (result != null && current != null) {
                    Text(
                        "${result.trough.toInt()}–${result.peak.toInt()} ${current.analyte.canonicalUnit}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.secondaryLabel,
                    )
                }
            }
            if (result != null && current != null) {
                DepotCurveMiniChart(
                    result = result,
                    analyte = current.analyte,
                    tint = tint,
                    referenceLow = current.referenceLow,
                    referenceHigh = current.referenceHigh,
                    referenceBand = current.analyte.referenceRegion,
                )
            } else {
                Box(Modifier.fillMaxWidth().height(76.dp))
            }
        }
    }
}

/**
 * The distinct safety cautions carried by any ester the user has logged.
 *
 * Ported from `HormoneLevelsView.cautions`. Deduplicated by ester id, so an ester
 * logged twenty times warns once.
 */
private fun cautionsOf(model: HormoneLevelsModel): List<String> {
    val esters = model.perEster.map { it.first } + model.catalogMarkers.map { it.ester }
    val seen = mutableSetOf<String>()
    val out = mutableListOf<String>()
    for (ester in esters) {
        val caution = ester.caution ?: continue
        if (seen.add(ester.esterID)) out += caution
    }
    return out
}

// MARK: - Serum curve

/**
 * The summed serum estimate with its band and the published reference region.
 *
 * Ported from `SerumCurveCard`. The shaded band is testosterone's only, and the
 * line under it says what it is and where it came from — a reference to read
 * against, not a target the app is proposing.
 */
@Composable
private fun SerumCurveCard(model: HormoneLevelsModel, result: DepotCurveResult) {
    DepotSectionCard(title = serumTitle(model.analyte)) {
        DepotCurveChart(
            result = result,
            analyte = model.analyte,
            referenceLow = model.referenceLow,
            referenceHigh = model.referenceHigh,
            chartRange = model.chartRange,
            onChartRangeChange = {
                model.chartRange = it
                model.pinchVisibleDays = null
            },
            onPinch = { model.pinchVisibleDays = it },
            referenceBand = model.analyte.referenceRegion,
        )
        if (model.analyte.referenceRegion != null) {
            Caption(
                "Shaded: the 300–1000 ng/dL male reference range (FDA label; Wang 2010). " +
                    "A reference, not a target.",
            )
        }
    }
}

private fun serumTitle(analyte: Analyte): String = when (analyte) {
    Analyte.ESTRADIOL -> "Estimated serum estradiol"
    Analyte.TESTOSTERONE -> "Estimated serum testosterone"
}

// MARK: - Per-ester assumed levels

/**
 * One series per logged ester — the assumed depot contribution each ester makes,
 * distinct from the serum sum above.
 *
 * Ported from `AssumedDepotLevelsCard`. This is the display that makes a switch
 * or a mix honest: the serum total is one line, but it is two esters releasing on
 * two different schedules, and the reader is entitled to see that rather than a
 * single shape that happens to fit the sum.
 */
@Composable
private fun AssumedDepotLevelsCard(
    analyte: Analyte,
    perEster: List<Pair<EsterPKRecord, List<DepotCurveResult.Point>>>,
) {
    DepotSectionCard(title = "Assumed depot levels") {
        AssumedDepotLevelsChart(analyte = analyte, perEster = perEster)

        // The legend carries the line style as well as the colour, because the
        // styles are always differentiated here — see `DepotCharts`.
        for ((index, series) in perEster.withIndex()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SeriesSwatch(index)
                Text(
                    series.first.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
        Caption("Each ester's own release, before they sum to the serum estimate above.")
    }
}

// MARK: - Caution and catalog-only esters

/**
 * A safety caution carried by any ester the user has logged.
 *
 * Ported from `EsterCautionCard`. Only testosterone undecanoate has one on the
 * shipped catalog — the Aveed/Nebido boxed warning for pulmonary oil
 * microembolism — and it is shown verbatim rather than summarised.
 */
@Composable
private fun EsterCautionCard(cautions: List<String>) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (caution in cautions) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("!", color = PiruTheme.colors.caution, fontWeight = FontWeight.Bold)
                    Text(caution, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * Esters the user logged that ship no validated curve.
 *
 * Ported from `CatalogEsterNote`. Undecylate is real and loggable; what it does
 * not have is a release model anyone has measured, so it is counted and named
 * rather than drawn — a fabricated curve here would be indistinguishable from a
 * fitted one.
 */
@Composable
private fun CatalogEsterNote(markers: List<HormoneLevelsLog.Marker>) {
    DepotSectionCard(title = "Logged, not modeled") {
        val byEster = markers.groupBy { it.ester.esterID }
        for ((_, rows) in byEster.toSortedMap()) {
            val ester = rows.first().ester
            Caption(
                "${rows.size} ${ester.label} injections logged. No serum curve is drawn for it " +
                    "— no validated release data.",
            )
        }
    }
}

// MARK: - Companion measured series

/**
 * The companion axes to show for [analyte]: the primary ones always, plus
 * hemoglobin only once it has a point.
 *
 * Ported from `visibleCompanions`. Hemoglobin rides on the same panel as
 * hematocrit, so an empty one would be a card about nothing.
 */
private fun visibleCompanions(analyte: Analyte, labs: List<LabMeasurement>): List<CompanionMeasurement> =
    CompanionMeasurement.companions(analyte).filter { companion ->
        companion != CompanionMeasurement.HEMOGLOBIN || companionPoints(companion, labs).isNotEmpty()
    }

private fun companionPoints(
    companion: CompanionMeasurement,
    labs: List<LabMeasurement>,
): List<Pair<Instant, Double>> = labs
    .filter { it.analyteKey == companion.key }
    .sortedBy { it.date }
    .map { it.date to it.value }

/**
 * One companion series: measured points connected over time, a plain
 * increased/flat/decreased trend, and its framing line.
 *
 * Ported from `CompanionSeriesCard`. Every claim on this card is about the
 * user's own measurements; nothing here is modelled, which is the whole reason
 * the axis exists — aromatisation and HPG suppression are person-specific with no
 * citable conversion to hard-code.
 */
@Composable
private fun CompanionSeriesCard(
    measurement: CompanionMeasurement,
    points: List<Pair<Instant, Double>>,
    onAdd: () -> Unit,
) {
    val colors = PiruTheme.colors
    val measurer = rememberTextMeasurer()
    val zone = remember { ZoneId.systemDefault() }
    val dateFormat = remember { DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()) }
    val labelStyle = TextStyle(fontSize = 10.sp, color = colors.secondaryLabel)

    DepotSectionCard(title = measurement.title, trailing = { TrendLabel(measurement, points) }) {
        when {
            points.size >= 2 -> {
                Box(Modifier.fillMaxWidth().height(120.dp)) {
                    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                        val leftPad = 8.dp.toPx()
                        val rightPad = 8.dp.toPx()
                        val topPad = 8.dp.toPx()
                        val bottomPad = 20.dp.toPx()
                        val plotW = size.width - leftPad - rightPad
                        val plotH = size.height - topPad - bottomPad
                        if (plotW <= 0 || plotH <= 0) return@Canvas

                        val start = points.first().first
                        val end = points.last().first
                        val spanMillis = (end.toEpochMilli() - start.toEpochMilli()).coerceAtLeast(1L).toDouble()
                        val minValue = points.minOf { it.second }
                        val maxValue = points.maxOf { it.second }
                        val span = (maxValue - minValue).coerceAtLeast(1e-6)

                        fun x(date: Instant) =
                            leftPad + ((date.toEpochMilli() - start.toEpochMilli()) / spanMillis).toFloat() * plotW

                        fun y(value: Double) =
                            topPad + plotH - ((value - minValue) / span).toFloat() * plotH

                        val path = Path()
                        points.forEachIndexed { i, (date, value) ->
                            val px = x(date)
                            val py = y(value)
                            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        }
                        drawPath(path, measurement.tint.copy(alpha = 0.8f), style = Stroke(width = 2.dp.toPx()))

                        for ((date, value) in points) {
                            drawCircle(measurement.tint, radius = 3.dp.toPx(), center = Offset(x(date), y(value)))
                        }

                        val firstLabel = measurer.measure(dateFormat.withZone(zone).format(start), labelStyle)
                        drawText(firstLabel, topLeft = Offset(leftPad, topPad + plotH + 2.dp.toPx()))
                        val lastLabel = measurer.measure(dateFormat.withZone(zone).format(end), labelStyle)
                        drawText(
                            lastLabel,
                            topLeft = Offset(
                                leftPad + plotW - lastLabel.size.width,
                                topPad + plotH + 2.dp.toPx(),
                            ),
                        )
                    }
                }
            }
            points.size == 1 -> {
                val (date, value) = points.first()
                Text(
                    "${"%.1f".format(Locale.ROOT, value)} ${measurement.unit} on " +
                        dateFormat.withZone(zone).format(date),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Caption(measurement.framing)
        TextButton(onClick = onAdd) { Text("Add ${measurement.title}") }
    }
}

/** Plain first-to-last trend — the only claim that is honest off a few points. */
@Composable
private fun TrendLabel(measurement: CompanionMeasurement, points: List<Pair<Instant, Double>>) {
    val first = points.firstOrNull()?.second ?: return
    val last = points.lastOrNull()?.second ?: return
    if (first <= 0 || points.size < 2) return
    val change = (last - first) / first
    val label = when {
        change > 0.05 -> "Increased"
        change < -0.05 -> "Decreased"
        else -> "Flat"
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = measurement.tint,
    )
}

/**
 * Enter one companion measurement.
 *
 * Ported from `AddCompanionMeasurementSheet`. A blood count is stored with
 * `excludedFromCalibration` set, so it can never enter a depot fit — the
 * exclusion is by construction rather than by remembering to filter later.
 */
@Composable
private fun AddCompanionMeasurementDialog(
    measurement: CompanionMeasurement,
    onSave: (LabMeasurement) -> Unit,
    onDismiss: () -> Unit,
) {
    var date by remember { mutableStateOf(Instant.now()) }
    var value by remember { mutableStateOf<Double?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(measurement.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(
                    label = "Level",
                    value = value,
                    unit = measurement.unit,
                    onValueChange = { value = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { date = date.minusSeconds(86_400) }) { Text("−1 day") }
                    TextButton(onClick = { date = date.plusSeconds(86_400) }) { Text("+1 day") }
                }
                Caption(measurement.framing)
            }
        },
        confirmButton = {
            TextButton(
                enabled = (value ?: 0.0) > 0,
                onClick = {
                    val raw = value
                    if (raw == null || raw <= 0) return@TextButton
                    onSave(
                        LabMeasurement(
                            date = date,
                            analyteKey = measurement.key,
                            value = raw,
                            inputUnit = measurement.unit,
                            excludedFromCalibration = !measurement.isHormone,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// MARK: - Lab calibration

/**
 * The lab results, the calibration controls, the guideline-range shortcut and the
 * draw-timing note.
 *
 * Ported from `HormoneLabCalibrationCard`. The draw-timing line is hormone
 * specific and is the one piece of clinical framing on the page: a level without
 * its draw time is a number, and which time matters differs between cypionate and
 * undecanoate.
 */
@Composable
private fun HormoneLabCalibrationCard(
    model: HormoneLevelsModel,
    labs: List<LabMeasurement>,
    onAdd: () -> Unit,
    onToggleExclude: (LabMeasurement) -> Unit,
    onDelete: (LabMeasurement) -> Unit,
    onAutoCalibrateChange: (Boolean) -> Unit,
    onFitRatesChange: (Boolean) -> Unit,
    onMultiplierChange: (Double) -> Unit,
) {
    DepotSectionCard(
        title = "Lab calibration",
        trailing = { CalibrationChip(labs.count { !it.excludedFromCalibration }) },
    ) {
        if (labs.isEmpty()) {
            Caption("Add a blood test to fit the curve to you. The band narrows.")
        } else {
            for (lab in labs) {
                LabRow(
                    lab = lab,
                    analyte = model.analyte,
                    onToggleExcluded = { onToggleExclude(lab) },
                    onDelete = { onDelete(lab) },
                )
            }
        }

        TextButton(onClick = onAdd) { Text("Add lab result") }

        SectionDivider()
        CalibrationControl(
            hasLabs = model.hasLabs,
            isLabDriven = model.isLabDriven,
            calibration = model.calibration,
            calibrationMeasurementCount = model.calibrationMeasurementCount,
            effectiveMultiplier = model.effectiveMultiplier,
            personalMultiplier = model.personalMultiplier,
            autoCalibrateFromLabs = model.autoCalibrateFromLabs,
            fitRates = model.fitRates,
            onAutoCalibrateChange = onAutoCalibrateChange,
            onFitRatesChange = onFitRatesChange,
            onMultiplierChange = onMultiplierChange,
        )
        ReferenceLinesEditor(
            referenceLow = model.referenceLow,
            referenceHigh = model.referenceHigh,
            unit = model.analyte.canonicalUnit,
            onLowChange = { model.referenceLow = it },
            onHighChange = { model.referenceHigh = it },
        )

        val goal = model.analyte.labeledGoal
        if (goal != null) {
            TextButton(
                onClick = {
                    model.referenceLow = goal.start
                    model.referenceHigh = goal.endInclusive
                },
            ) {
                Text(
                    "Add the guideline reference range " +
                        "(${goal.start.toInt()}–${goal.endInclusive.toInt()} ${model.analyte.canonicalUnit})",
                )
            }
            Caption(
                "The Endocrine Society / WPATH SOC8 monitoring range for adults on " +
                    "masculinizing testosterone, drawn as reference lines. The range from your " +
                    "clinician or laboratory report takes precedence.",
            )
        }

        Caption(drawTimingNote(model.analyte))
    }
}

private fun drawTimingNote(analyte: Analyte): String = when (analyte) {
    Analyte.TESTOSTERONE ->
        "A level only means something with its draw time: for cypionate and enanthate, measure " +
            "midway between injections; for undecanoate, measure at trough, just before the next dose."
    Analyte.ESTRADIOL ->
        "Note the time since your last injection when you draw — a peak and a trough tell " +
            "different stories, and the curve reads both against your dose times."
}

// MARK: - Provenance

/**
 * Where each ester's numbers come from.
 *
 * Ported from `HormoneLevelsProvenanceCard`. The estradiol footer links the two
 * sources the parameters actually come from; the testosterone one says plainly
 * that there is no community PK simulator for those esters, which is why their
 * band opens wide.
 */
@Composable
private fun ProvenanceCard(analyte: Analyte, esters: List<EsterPKRecord>) {
    val colors = PiruTheme.colors
    val uriHandler = LocalUriHandler.current
    DepotSectionCard(title = "Sources") {
        for (ester in esters) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        ester.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    ConfidenceBadge(ester.confidence)
                }
                Caption(ester.provenance)
            }
        }
        Caption(
            "Older studies used radioimmunoassay; modern LC-MS/MS reads lower. " +
                "Calibrating to your own results absorbs the difference.",
        )

        when (analyte) {
            Analyte.ESTRADIOL -> {
                Text(
                    "Parameters from estrannaise.js (MIT), checked against the literature",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.accent,
                    modifier = Modifier.padding(vertical = 2.dp).clickable {
                        uriHandler.openUri("https://github.com/WHSAH/estrannaise.js")
                    },
                )
                Text(
                    "More on injectable estradiol dosing (diyhrt.info)",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.accent,
                    modifier = Modifier.padding(vertical = 2.dp).clickable {
                        uriHandler.openUri("https://diyhrt.info/transfem/dosing/")
                    },
                )
            }
            Analyte.TESTOSTERONE -> Caption(
                "Testosterone ester curves are fit from label and primary-literature half-lives " +
                    "— there is no community PK simulator for them, so the band stays wide until " +
                    "your lab results calibrate the model.",
            )
        }
    }
}

// MARK: - Explanation and empty state

@Composable
private fun ExplanationCard() {
    DepotSectionCard(title = "About this estimate") {
        Caption(
            "An injected ester releases slowly from the oil depot, splits into the free " +
                "hormone, and clears. This curve sums your logged esters into an illustrative " +
                "serum estimate. It is not a laboratory result.",
        )
        Caption(
            "It estimates a level. It never suggests a dose or a target. Your lab results fit " +
                "the model to your measurements, which doesn't establish accuracy between them. " +
                "The reference lines are your own.",
        )
        Caption(
            "Levels vary a lot between people, so an uncalibrated curve is a starting point, " +
                "not a reading. Retest after any change in dose, ester, interval, or site.",
        )
        Caption("Predicted from a model, not measured. Not medical advice.")
    }
}

/**
 * The empty state: no injectable ester has ever been logged.
 *
 * Ported from `HormoneLevelsNoDataCard`, and it says what would fill it rather
 * than showing a blank chart.
 */
@Composable
private fun NoDataCard() {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Log an injectable estradiol or testosterone ester to see your estimated " +
                    "hormone levels here.",
                style = MaterialTheme.typography.bodyMedium,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}
