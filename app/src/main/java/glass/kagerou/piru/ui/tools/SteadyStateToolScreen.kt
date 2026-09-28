package glass.kagerou.piru.ui.tools

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.engine.PKModel
import glass.kagerou.piru.engine.PKResolver
import glass.kagerou.piru.engine.SteadyStateModel
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.model.doseFormatted
import glass.kagerou.piru.substance.DbSubstanceCatalog
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.theme.PiruTheme
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Steady State — where a medicine taken on a fixed schedule settles.
 *
 * Ported from `Piru/Views/Tools/SteadyStateView.swift` (417 lines) and
 * `SteadyStateModel.swift`. The arithmetic is the engine's
 * ([SteadyStateModel.compute]); what lives here is the schedule the user types,
 * the half-life it resolves through [PKResolver], and the chart.
 *
 * Sibling to the half-life tool (one dose decaying); this one superposes the same
 * [PKModel.fractionRemainingInBody] at a fixed interval until intake and
 * clearance balance.
 *
 * ## The two guard rails are load-bearing
 * `compute` floors its window at six intervals and 1.2 × the 97 % time, and caps
 * the superposed dose count at 5,000. Without them a near-zero interval against a
 * long half-life asks for hundreds of thousands of doses at each of ~840 sample
 * points — a multi-second hang on every keystroke. That is why the result is
 * computed in a `LaunchedEffect` keyed on a `RecomputeKey` rather than in the
 * body, and why the key names every input `refresh()` reads.
 */
@Composable
fun SteadyStateToolScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication

    var catalog by remember { mutableStateOf<DbSubstanceCatalog?>(null) }
    var substanceName by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Substance?>(null) }
    var doseAmount by remember { mutableStateOf<Double?>(20.0) }
    var doseUnit by remember { mutableStateOf("mg") }
    var intervalHours by remember { mutableStateOf<Double?>(24.0) }
    var useCustomHalfLife by remember { mutableStateOf(false) }
    var customHalfLifeHours by remember { mutableStateOf<Double?>(null) }
    var route by remember { mutableStateOf(RouteOfAdministration.ORAL) }
    var result by remember { mutableStateOf<SteadyStateModel.Result?>(null) }

    LaunchedEffect(Unit) { catalog = app.catalog() }

    val recomputeKey = SteadyStateKey(
        doseAmount = doseAmount,
        intervalHours = intervalHours,
        useCustomHalfLife = useCustomHalfLife,
        customHalfLifeHours = customHalfLifeHours,
        route = route,
        substanceName = selected?.name,
        substanceHalfLife = selected?.halfLifeMinutes,
    )

    LaunchedEffect(recomputeKey) {
        val dose = doseAmount ?: 0.0
        val hours = intervalHours
        val halfLife = effectiveHalfLife(useCustomHalfLife, customHalfLifeHours, selected)
        if (halfLife == null || halfLife <= 0 || hours == null || hours <= 0 || dose <= 0) {
            result = null
            return@LaunchedEffect
        }
        val (ke, ka) = PKResolver.rateConstants(halfLife, selected?.resolveDuration(route))
        result = SteadyStateModel.compute(
            dose = dose,
            halfLifeMinutes = halfLife,
            intervalMinutes = hours * 60,
            ke = ke,
            ka = ka,
        )
    }

    val missingHalfLife = selected != null && !useCustomHalfLife && selected?.halfLifeMinutes == null

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Steady state", style = MaterialTheme.typography.headlineSmall)
                Caption("Where a fixed schedule settles, and how long it takes to get there.")
            }
        }

        item {
            InputSection(
                catalog = catalog,
                substanceName = substanceName,
                onSubstanceNameChange = { substanceName = it },
                selected = selected,
                onSelect = { substance ->
                    selected = substance
                    substanceName = substance.name
                    doseUnit = substance.defaultUnit
                    route = substance.defaultRoute
                },
                doseAmount = doseAmount,
                onDoseAmountChange = { doseAmount = it },
                doseUnit = doseUnit,
                onDoseUnitChange = { doseUnit = it },
                intervalHours = intervalHours,
                onIntervalHoursChange = { intervalHours = it },
                useCustomHalfLife = useCustomHalfLife,
                onUseCustomHalfLifeChange = { useCustomHalfLife = it },
                customHalfLifeHours = customHalfLifeHours,
                onCustomHalfLifeHoursChange = { customHalfLifeHours = it },
                route = route,
                onRouteChange = { route = it },
            )
        }

        val current = result
        if (current != null) {
            item {
                DepotSectionCard(title = "Modeled level over time") {
                    SteadyStateChart(result = current, unit = doseUnit)
                }
            }
            item { MetricsCard(current, doseUnit) }
        } else if (missingHalfLife) {
            item {
                NoDataCard(
                    substanceName = selected?.name,
                    onUseCustomHalfLife = { useCustomHalfLife = true },
                )
            }
        }

        item {
            DepotSectionCard(title = "About this model") {
                Caption(
                    "On a fixed schedule doses overlap and the level climbs until intake and " +
                        "clearance balance: steady state.",
                )
                Caption("Values are modeled body content in the dose's units.")
                Caption("Predicted from a model, not measured. Not medical advice.")
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Related")
                RelatedCard(
                    title = "Half-Life",
                    detail = "Model a single dose's decay over time",
                    onClick = { navigator.push(PushRoute.Tool(PushRoute.ToolKind.HALF_LIFE)) },
                )
                RelatedCard(
                    title = "In your body",
                    detail = "See the model's estimate of what is still active",
                    onClick = { navigator.push(PushRoute.Tool(PushRoute.ToolKind.BODY_LOAD)) },
                )
            }
        }
    }
}

/** Everything `refresh()` reads, in one comparable value the screen watches. */
private data class SteadyStateKey(
    val doseAmount: Double?,
    val intervalHours: Double?,
    val useCustomHalfLife: Boolean,
    val customHalfLifeHours: Double?,
    val route: RouteOfAdministration,
    val substanceName: String?,
    val substanceHalfLife: Double?,
)

/**
 * The half-life in minutes the schedule runs on.
 *
 * Ported from `HalfLifeCalculation.effectiveHalfLife`. A typed value wins when
 * the toggle is on; a custom half-life of zero or less is "not entered" rather
 * than "zero", because every downstream quantity divides by it.
 */
private fun effectiveHalfLife(
    useCustom: Boolean,
    customHours: Double?,
    substance: Substance?,
): Double? = if (useCustom) {
    customHours?.takeIf { it > 0 }?.times(60)
} else {
    PKResolver.halfLifeMinutes(substance)
}

// MARK: - Input

/**
 * The schedule: substance, route, dose, interval, and an optional half-life.
 *
 * Ported from `SteadyStateInputSection`. The route picker appears only when the
 * substance has more than one, because for everything else it is a choice with
 * one option — and that option is already made.
 */
@Composable
private fun InputSection(
    catalog: DbSubstanceCatalog?,
    substanceName: String,
    onSubstanceNameChange: (String) -> Unit,
    selected: Substance?,
    onSelect: (Substance) -> Unit,
    doseAmount: Double?,
    onDoseAmountChange: (Double?) -> Unit,
    doseUnit: String,
    onDoseUnitChange: (String) -> Unit,
    intervalHours: Double?,
    onIntervalHoursChange: (Double?) -> Unit,
    useCustomHalfLife: Boolean,
    onUseCustomHalfLifeChange: (Boolean) -> Unit,
    customHalfLifeHours: Double?,
    onCustomHalfLifeHoursChange: (Double?) -> Unit,
    route: RouteOfAdministration,
    onRouteChange: (RouteOfAdministration) -> Unit,
) {
    DepotSectionCard(title = "Schedule") {
        SubstanceSearchField(
            catalog = catalog,
            query = substanceName,
            onQueryChange = onSubstanceNameChange,
            onSelect = onSelect,
        )

        val substance = selected
        if (substance != null && substance.routes.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldLabel("Route")
                SegmentedRow(
                    options = substance.routes.map { it.route },
                    selected = route,
                    label = { routeLabel(it) },
                    onSelect = onRouteChange,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NumberField(
                    label = "Dose each time",
                    value = doseAmount,
                    unit = doseUnit,
                    onValueChange = onDoseAmountChange,
                )
                UnitMenu(unit = doseUnit, onUnitChange = onDoseUnitChange)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NumberField(
                    label = "Taken every",
                    value = intervalHours,
                    unit = "hours",
                    onValueChange = onIntervalHoursChange,
                )
                IntervalPresetMenu(onChoose = onIntervalHoursChange)
            }
        }

        ToggleRow(
            label = "Custom half-life",
            checked = useCustomHalfLife,
            onCheckedChange = onUseCustomHalfLifeChange,
        )
        if (useCustomHalfLife) {
            NumberField(
                label = "Half-life",
                value = customHalfLifeHours,
                unit = "hours",
                onValueChange = onCustomHalfLifeHoursChange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The dose units the schedule field accepts — upstream's own list. */
private val DOSE_UNITS = listOf("mg", "g", "µg", "mL", "IU", "drops", "puffs")

/** The interval shortcuts, upstream's `intervalPresets`. */
private val INTERVAL_PRESETS: List<Pair<String, Double>> = listOf(
    "Every 4 hours" to 4.0,
    "Every 6 hours" to 6.0,
    "Every 8 hours" to 8.0,
    "Every 12 hours" to 12.0,
    "Once daily" to 24.0,
    "Twice daily" to 12.0,
    "Weekly" to 168.0,
)

@Composable
private fun UnitMenu(unit: String, onUnitChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(unit) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in DOSE_UNITS) {
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onUnitChange(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun IntervalPresetMenu(onChoose: (Double) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("Presets") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((label, hours) in INTERVAL_PRESETS) {
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onChoose(hours)
                    },
                )
            }
        }
    }
}

/**
 * The substance picker: a query field over the catalog's ranked search.
 *
 * Ported from `SubstanceSearchField`. Selecting a result adopts the substance's
 * own default unit and route, which is why the two fields below it change when
 * the pick does — the schedule is stated in the units the substance is dosed in,
 * not in whatever was there before.
 */
@Composable
private fun SubstanceSearchField(
    catalog: DbSubstanceCatalog?,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (Substance) -> Unit,
) {
    val matches = remember(query, catalog) {
        if (query.trim().length < 2 || catalog == null) {
            emptyList()
        } else {
            catalog.search(query.trim(), limit = 8).map { it.substance }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabel("Substance")
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text("Search the catalog") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth(),
        )
        if (matches.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (match in matches) {
                    Text(
                        match.displayTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(match) }
                            .padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RelatedCard(title: String, detail: String, onClick: () -> Unit) {
    PiruCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Caption(detail)
        }
    }
}

// MARK: - Metrics

/**
 * The four plateau readings.
 *
 * Ported from `SteadyStateMetricsCard`. "Steady state by" is the 95 % landmark
 * with the 97 % one beneath it, because the two answer different questions: when
 * the level is close enough to stop changing, and when it has stopped.
 */
@Composable
private fun MetricsCard(result: SteadyStateModel.Result, unit: String) {
    val peakMultiple = if (result.dose > 0) result.peakAmount / result.dose else 1.0
    DepotSectionCard(title = "At steady state") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(
                key = "Steady state by",
                value = formatDays(result.time95 / 1_440),
                sub = "fully settled in ${formatDays(result.time97 / 1_440)}",
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                key = "Accumulation",
                value = "%.1f×".format(Locale.ROOT, peakMultiple),
                sub = "at the peak, vs. one dose",
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(
                key = "Plateau range",
                value = "${doseFormatted(result.troughAmount)}–${doseFormatted(result.peakAmount)}",
                sub = "$unit · trough to peak",
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                key = "Fluctuation",
                value = "${result.fluctuationPercent.toInt()}%",
                sub = fluctuationLabel(result.fluctuationPercent),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** "10 hours" below a day, "3 days" above it — upstream's `formatDays`. */
private fun formatDays(days: Double): String = if (days < 1) {
    "${(days * 24).toInt()} hours"
} else {
    "${days.toInt()} days"
}

/**
 * The swing, described rather than graded.
 *
 * Upstream's three bands, verbatim. "Spiky" is the most judgmental word in the
 * app and it is still about the curve, not about the person taking it.
 */
private fun fluctuationLabel(pct: Double): String = when {
    pct < 40 -> "smooth"
    pct < 120 -> "moderate swing"
    else -> "spiky"
}

// MARK: - No data

@Composable
private fun NoDataCard(substanceName: String?, onUseCustomHalfLife: () -> Unit) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Half-life data not available for ${substanceName ?: "this substance"}.",
                style = MaterialTheme.typography.bodyMedium,
                color = PiruTheme.colors.secondaryLabel,
            )
            Caption("The catalog carries none for this compound, and the model has no default to fall back on.")
            TextButton(onClick = onUseCustomHalfLife) { Text("Use Custom Half-Life") }
        }
    }
}

// MARK: - Chart

/**
 * The climbing-to-plateau body-content curve.
 *
 * Ported from `SteadyStateChart`, which is itself hand-drawn with `Canvas` rather
 * than Swift Charts — so this is a port of a hand-drawn chart, and the geometry
 * (paddings, the 1.12 × peak headroom, the 1/2/5 tick ladder) is upstream's own.
 */
@Composable
private fun SteadyStateChart(result: SteadyStateModel.Result, unit: String) {
    val colors = PiruTheme.colors
    val accent = colors.accent
    val secondary = colors.secondaryLabel
    val measurer = rememberTextMeasurer()

    // Hoisted: a `@Composable` theme read cannot happen inside `Canvas { … }`.
    val axisColor = secondary.copy(alpha = 0.4f)
    val guideColor = accent.copy(alpha = 0.4f)
    val fillColor = accent.copy(alpha = 0.10f)
    val labelStyle = TextStyle(fontSize = 10.sp, color = secondary)
    val valueStyle = TextStyle(fontSize = 11.sp, color = accent, fontWeight = FontWeight.SemiBold)

    Canvas(Modifier.fillMaxWidth().height(230.dp)) {
        if (result.curve.isEmpty()) return@Canvas

        val leftPad = 40.dp.toPx()
        val rightPad = 12.dp.toPx()
        val topPad = 14.dp.toPx()
        val labelArea = 24.dp.toPx()
        val plotW = size.width - leftPad - rightPad
        val plotH = size.height - topPad - labelArea
        if (plotW <= 0 || plotH <= 0) return@Canvas

        val xMax = result.totalMinutes
        val peak = result.curve.maxOf { it.amount }
        val yMax = maxOf(peak * 1.12, result.dose * 1.25, 0.0001)
        val baseline = topPad + plotH

        fun x(minutes: Double) = leftPad + (minutes / xMax).toFloat() * plotW
        fun y(amount: Double) = topPad + plotH - (amount / yMax).toFloat() * plotH

        // Peak and trough guide lines — the only reference marks the graph keeps.
        val peakY = y(result.peakAmount)
        val troughY = y(result.troughAmount)
        for (guideY in listOf(peakY, troughY)) {
            drawLine(
                color = guideColor,
                start = Offset(leftPad, guideY),
                end = Offset(leftPad + plotW, guideY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )
        }

        // Curve fill, then stroke.
        val fill = Path().apply {
            moveTo(x(0.0), baseline)
            for (point in result.curve) lineTo(x(point.minutes), y(point.amount))
            lineTo(x(result.curve.last().minutes), baseline)
            close()
        }
        drawPath(fill, fillColor)

        val stroke = Path().apply {
            result.curve.forEachIndexed { i, point ->
                val px = x(point.minutes)
                val py = y(point.amount)
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
        }
        drawPath(stroke, accent, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))

        // Axis frame.
        drawLine(axisColor, Offset(leftPad, topPad), Offset(leftPad, baseline), 1.dp.toPx())
        drawLine(axisColor, Offset(leftPad, baseline), Offset(leftPad + plotW, baseline), 1.dp.toPx())

        // Peak and trough values as y-axis readings — a number at the height it
        // describes, which is the reading the guide lines are there to make.
        for ((value, atY) in listOf(result.peakAmount to peakY, result.troughAmount to troughY)) {
            val measured = measurer.measure(doseFormatted(value), valueStyle)
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(
                    x = leftPad - 6.dp.toPx() - measured.size.width,
                    y = atY - measured.size.height / 2f,
                ),
            )
        }

        // The unit, at the top of the axis rather than rotated beside it.
        val yTitle = measurer.measure(unit, labelStyle)
        drawText(yTitle, topLeft = Offset(leftPad - yTitle.size.width / 2f, 0f))

        // X ticks: hours for a short window, days for a long one.
        val useDays = xMax > 2 * 1_440
        val unitMinutes = if (useDays) 1_440.0 else 60.0
        val stepUnits = niceStep(xMax / unitMinutes, targetTicks = 5)
        val axisLabelWidth = 34.dp.toPx()
        var tickValue = stepUnits
        while (tickValue * unitMinutes <= xMax) {
            val tx = x(tickValue * unitMinutes)
            if (tx < leftPad + plotW - axisLabelWidth) {
                val label = if (tickValue < 10 && tickValue % 1.0 != 0.0) {
                    "%.1f".format(Locale.ROOT, tickValue)
                } else {
                    tickValue.toInt().toString()
                }
                val measured = measurer.measure(label, labelStyle)
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(
                        x = tx - measured.size.width / 2f,
                        y = baseline + labelArea / 2f + 2.dp.toPx() - measured.size.height / 2f,
                    ),
                )
            }
            tickValue += stepUnits
        }
        val axisLabel = measurer.measure(if (useDays) "days" else "hours", labelStyle)
        drawText(
            textLayoutResult = axisLabel,
            topLeft = Offset(
                x = leftPad + plotW - axisLabel.size.width,
                y = baseline + labelArea / 2f + 2.dp.toPx() - axisLabel.size.height / 2f,
            ),
        )
    }
}

/**
 * A 1/2/5·10ⁿ step covering `span` in roughly `targetTicks` ticks.
 *
 * Ported verbatim from `SteadyStateChart.niceStep`. The brief is not decoration:
 * a linear split of a 168-hour axis puts ticks on 33.6 and 67.2, which nobody
 * reads.
 */
private fun niceStep(span: Double, targetTicks: Int): Double {
    if (span <= 0) return 1.0
    val raw = span / targetTicks
    val magnitude = 10.0.pow(floor(log10(raw)))
    val norm = raw / magnitude
    val stepNorm = when {
        norm < 1.5 -> 1.0
        norm < 3 -> 2.0
        norm < 7 -> 5.0
        else -> 10.0
    }
    return stepNorm * magnitude
}

/** A route's display name — the model's own, not a second table that can drift. */
internal fun routeLabel(route: RouteOfAdministration): String = route.displayName
