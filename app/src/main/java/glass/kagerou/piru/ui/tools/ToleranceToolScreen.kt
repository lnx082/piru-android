package glass.kagerou.piru.ui.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.engine.PDModel
import glass.kagerou.piru.engine.ReceptorClasses
import glass.kagerou.piru.engine.ToleranceReplay
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.components.ScrubReadout
import glass.kagerou.piru.ui.components.ScrubRow
import glass.kagerou.piru.ui.components.interpolateAt
import glass.kagerou.piru.ui.insights.InsightsFilterPill
import glass.kagerou.piru.ui.insights.InsightsSectionCard
import glass.kagerou.piru.ui.insights.UsageTimeRange
import glass.kagerou.piru.ui.labels.CoreLabels
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.TextButton
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.data.entity.DoseEntryEntity

/**
 * The tolerance tool: one card per mechanism class.
 *
 * Ported from `Views/Tools/Tolerance/` — eleven files and about 2,000 lines, of
 * which this draws the card list and its gauge. The data behind it is complete:
 * `ToleranceRepository` replays the log through `ToleranceReplay` and
 * `ToleranceIntegrator`, so everything on screen is the model the tests pin.
 *
 * ## Per mechanism, never per receptor and never one number
 * That is the load-bearing claim of the whole pharmacology axis: tolerance is a
 * property of the *mechanism*, and the app has no "your tolerance is 40 %" figure
 * because no such figure exists. A card per receptor would say the same thing
 * eleven times for one drug.
 *
 * ## The level has no word, on purpose
 * Upstream's note is explicit: "the bar is the whole readout". A gauge with a
 * label beside it invites the label to be quoted and the gauge to be ignored —
 * and a word is a much stronger claim than a fraction. So the bar is the reading,
 * and the numbers behind it sit below the fold for a reader who wants them.
 *
 * ## Safety-critical classes sort first, then severity
 * Not severity alone: an opioid after a break and a sedative dependence are the two
 * the app carries an explicit warning for, and they belong at the top whether or
 * not the user's current shift happens to be the largest.
 *
 * ## The chart is load, and that is deliberate
 * The over-time chart at the top plots each class's **receptor load** as a share of
 * the user's own recent peak — the only quantity on this screen that can be
 * resolved at an arbitrary past or future instant. The cards' shift factor `S` is
 * solved for now alone; asking it for another instant means re-running the whole
 * replay per sample, which is too slow for a rule the user is dragging. The load
 * trail samples the same contributors over time, it is what upstream's "Receptor
 * load over time" draws, and it answers the question the cursor is asking: how hard
 * has this been driven, and when does that let go.
 */
/**
 * The severity below which a class gets no card unless it is safety-critical.
 *
 * iOS's `ToleranceRow.minimumCardSeverity`. See the ordering block for what a card
 * below the floor says that is not true.
 */
private const val MINIMUM_CARD_SEVERITY = 0.10

@Composable
fun ToleranceToolScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    var cards by remember { mutableStateOf<Map<ReceptorClasses.ReceptorClass, ToleranceReplay.ClassTolerance>>(emptyMap()) }
    var incomplete by remember { mutableStateOf<Set<String>>(emptySet()) }
    var running by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }

    var range by rememberSaveable { mutableStateOf(UsageTimeRange.NINETY_DAYS) }
    var loadSeries by remember { mutableStateOf<List<ReceptorLoadSeries>>(emptyList()) }

    /**
     * The log the cards are readings of.
     *
     * Held because two things need it — the load series and the GABA card — and reading it inside a
     * composable would be a Room query during composition. It is the same list the effect already fetched.
     */
    var entries by remember { mutableStateOf<List<DoseEntryEntity>>(emptyList()) }
    var selected by remember { mutableStateOf<Instant?>(null) }

    LaunchedEffect(navigator.dataVersion) {
        running = true
        failure = null
        // The failure is *kept*, not swallowed. A `runCatching` here would turn
        // "the replay could not run" into "you have nothing logged" — the same
        // empty screen, saying something false.
        val result = runCatching {
            val repository = app.toleranceRepository()
            repository.loadCached()
            cards = repository.states.value
            repository.recompute()
            cards = repository.states.value
            incomplete = repository.incompleteData.value
        }
        failure = result.exceptionOrNull()?.let { throwable ->
            // Kept and shown rather than swallowed. An empty screen saying "nothing
            // logged" is indistinguishable from a replay that could not run, and
            // only one of those is worth the user acting on.
            throwable::class.simpleName + ": " + throwable.message
        }
        running = false
    }

    // The load trail is a second, wider reading of the same log: the cards below
    // answer "where am I now", and this answers "where has this been, and where is
    // it heading". It is a full replay per class plus a year of integration, so it
    // runs on the default dispatcher — `LaunchedEffect` continues on the
    // composition's dispatcher, which is the main one.
    LaunchedEffect(navigator.dataVersion, range) {
        // Read once, held in state, and handed to both consumers: the load series below and the GABA card.
        // Reading it inside a composable would be a Room query during composition.
        val log = app.database.doseEntryDao().all()
        entries = log
        val built = withContext(Dispatchers.Default) {
            buildReceptorLoadSeries(
                app = app,
                entries = log,
                range = range,
                futureHorizonMinutes = TOLERANCE_FUTURE_HORIZON_MINUTES,
            )
        }
        loadSeries = built
        // The cursor starts at the end of the window — the newest sample, which is
        // the chart's own "now" — rather than absent. An absent cursor has nothing
        // to focus, so a screen reader user had no way into the chart at all; the
        // drag these step actions replace is the only other way to move it. It also
        // answers the question the card is read for ("where is this now") without
        // requiring a tap, and "Back to now" is still where a moved cursor returns.
        selected = built.firstOrNull()?.points?.lastOrNull()?.date
    }

    // Safety-critical first, then by how much of a usual dose is gone. The
    // severity is `1 − responseFraction`, so a bigger number is more toleranced.
    //
    // ## The floor, and why a card without one is a false statement
    // A class the replay produced is not the same as a class with something to say. A
    // mechanism the user has rested for months still comes back from the replay with a
    // severity at or near zero, and drawing it produces a full card with an empty gauge
    // and a summary line that recites the layer shares as though they meant something --
    // which, on a screen about tolerance, reads as "you have tolerance here" when the
    // number says the opposite.
    //
    // iOS gates at `ToleranceRow.minimumCardSeverity = 0.10`, keeping a class below the
    // floor only when it is safety-critical:
    //
    //     guard snapshot.severity > ToleranceRow.minimumCardSeverity || row.isSafetyCritical
    //
    // The same two clauses, so the safety-critical exemption applies to the floor rather
    // than replacing it.
    val ordered = cards.values
        .filter { it.severity > MINIMUM_CARD_SEVERITY || it.receptorClass.isSafetyCritical }
        .sortedWith(
            compareByDescending<ToleranceReplay.ClassTolerance> { it.receptorClass.isSafetyCritical }
                .thenByDescending { it.severity },
        )

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.toolsb_tolerance_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.toolsb_tolerance_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(UsageTimeRange.entries.size) { index ->
                    val option = UsageTimeRange.entries[index]
                    InsightsFilterPill(
                        label = stringResource(option.displayNameRes),
                        color = PiruTheme.colors.accent,
                        isSelected = option == range,
                        showDot = false,
                        onClick = { range = option },
                    )
                }
            }
        }

        if (loadSeries.isNotEmpty()) {
            item {
            // The explainer, linked from the tool itself rather than listed on the hub: a reader who is
            // looking at a bar is the one who wants to know what it means, and a second hub row would say
            // "Tolerance" twice.
            TextButton(onClick = { navigator.push(PushRoute.ToleranceExplainer) }) {
                Text(stringResource(R.string.tolerance_explainer_title))
            }
        }

        item {
                InsightsSectionCard(
                    title = stringResource(R.string.toolsb_tolerance_load_title),
                    subtitle = stringResource(R.string.toolsb_tolerance_load_subtitle),
                ) {
                    TrailChart(
                        series = loadSeries.map { item ->
                            TrailChartSeries(
                                id = item.id,
                                tint = item.color,
                                points = item.points.map { TrailChartPoint(it.date, it.load) },
                            )
                        },
                        selected = selected,
                        onSelect = { selected = it },
                        windowFrom = loadSeries.first().points.first().date,
                        windowTo = loadSeries.first().points.last().date,
                        axisCaption = stringResource(R.string.toolsb_receptor_axis_caption),
                        accessibilityLabel = stringResource(R.string.scrub_label_tolerance),
                    )
                    selected?.let { at ->
                        ScrubReadout(
                            title = trailReadoutTitle(at),
                            rows = receptorLoadRows(loadSeries, at),
                            emptyText = stringResource(R.string.toolsb_receptor_readout_empty),
                            onReset = { selected = null },
                        )
                    }
                }
            }
        }

        failure?.let { message ->
            item {
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.toolsb_tolerance_failure_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(message, style = MaterialTheme.typography.bodySmall, color = PiruTheme.colors.dangerText)
                    }
                }
            }
        }

        if (running && ordered.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.toolsb_tolerance_replaying),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        if (!running && failure == null && ordered.isEmpty() && incomplete.isEmpty()) {
            item {
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.toolsb_tolerance_empty_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(R.string.toolsb_tolerance_empty_detail),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                }
            }
        }

        items(ordered, key = { it.receptorClass.wireValue }) { card ->
            ToleranceClassCard(card, entries)
        }

        if (incomplete.isNotEmpty()) {
            item {
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.toolsb_tolerance_incomplete_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(R.string.toolsb_tolerance_incomplete_detail),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                        Text(
                            incomplete.sorted().joinToString(", "),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.toolsb_tolerance_method_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.toolsb_tolerance_method_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                    Text(
                        stringResource(R.string.toolsb_model_disclaimer),
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToleranceClassCard(card: ToleranceReplay.ClassTolerance, entries: List<DoseEntryEntity>) {
    val tint = receptorClassColor(card.receptorClass)
    val params = ReceptorClasses.parametersFor(card.receptorClass)

    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(modifier = Modifier.size(14.dp)) {
                    Canvas(Modifier.fillMaxSize()) { drawCircle(tint) }
                }
                Text(
                    CoreLabels.receptorClass(card.receptorClass),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                ConfidenceCapsule(card)
            }

            if (card.contributors.isNotEmpty()) {
                Text(
                    card.contributors.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = tint,
                )
            }

            if (card.effectShifts.isEmpty()) {
                ToleranceBar(card)
            } else {
                // An effect-selective class — GABA, the gabapentinoids — where one
                // bar cannot say the thing that matters: sedation fades and memory
                // does not.
                EffectLadder(card)
            }

            RecoveryChart(card)

            // The combined load for this class, for GABA only. Upstream hangs it on the benzodiazepine card,
            // which is the one whose members stack on a single receptor — several benzodiazepines plus alcohol
            // plus a lingering metabolite all loading GABA-A at once.
            if (card.receptorClass == ReceptorClasses.ReceptorClass.GABA) {
                GabaLoadingCard(entries)
            }

            SafetyNote(card, params.safetyAxis)
        }
    }
}

/**
 * The segmented bar: how much of a usual dose you would still feel, split by which
 * layer is holding the shift.
 *
 * The segments are the four ln-shift contributions as a share of their sum, so a
 * reader can see *why* the level is where it is — a shift carried by the acute
 * layer recovers overnight, and the same height carried by the deep layer does not.
 */
@Composable
private fun ToleranceBar(card: ToleranceReplay.ClassTolerance) {
    val total = card.sAcute + card.sAdaptive + card.sDeep + card.sSynthesis
    val remaining = card.responseFraction.toFloat()
    val colors = PiruTheme.colors

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.fillMaxWidth().height(18.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                // The empty track is what is left of the response; the fill is what
                // is gone. Drawing the *loss* rather than the level matches the
                // sentence people actually say — "my dose does less than it did".
                drawRoundRect(
                    color = colors.secondaryLabel.copy(alpha = 0.18f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
                )
                val lost = (1f - remaining).coerceIn(0f, 1f)
                drawRoundRect(
                    color = colors.accent,
                    size = androidx.compose.ui.geometry.Size(size.width * lost, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
                )
            }
        }
        Text(
            if (total <= 0) {
                stringResource(R.string.toolsb_tolerance_bar_none)
            } else {
                stringResource(
                    R.string.toolsb_tolerance_bar_summary,
                    (remaining * 100).toInt(),
                    layerShare(card.sAcute, total),
                    layerShare(card.sAdaptive, total),
                    layerShare(card.sDeep, total),
                    layerShare(card.sSynthesis, total),
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = PiruTheme.colors.secondaryLabel,
        )
    }
}

private fun layerShare(value: Double, total: Double): String = "${((value / total) * 100).toInt()}%"

/**
 * The shift factor treated as "recovered".
 *
 * A ratio rather than a duration, because that is what `shiftDecayMinutes` solves
 * for. 1.05 is a 5 % residual — small enough that no screen would show it, and
 * finite, which 1.0 is not.
 */
private const val RECOVERED_SHIFT = 1.05

/**
 * How far past now the receptor-load window reaches: three weeks.
 *
 * The engine's own reference window — `ToleranceSimulation.RECENT_PEAK_WINDOW_MINUTES`,
 * the span over which "how much has this person been taking" is the question being
 * asked — and the horizon the tolerance store defaults to. So the future half of
 * this chart shows exactly the recovery the model already forecasts, and nothing
 * here invents a second timescale.
 */
private const val TOLERANCE_FUTURE_HORIZON_MINUTES: Double = 21 * 24 * 60.0

/**
 * The readout lines for the load chart: every class still being driven at [at],
 * biggest first.
 *
 * Interpolated along each series' own drawn segment rather than snapped to the
 * nearest sample, so the number under the cursor is the value the line passes
 * through at that x — the title names the exact dragged instant, and answering a
 * different moment than the one named is worse than answering coarsely. Half a
 * percent is the floor: below it there is nothing being driven worth a row.
 */
@Composable
private fun receptorLoadRows(series: List<ReceptorLoadSeries>, at: Instant): List<ScrubRow> {
    val rows = mutableListOf<Pair<Double, ScrubRow>>()
    for (item in series) {
        val load = item.points.interpolateAt(at, { it.date }, { it.load }) ?: continue
        if (load <= 0.005) continue
        rows += load to ScrubRow(
            label = CoreLabels.receptorCasualName(item.receptorClass),
            value = "${(load * 100).roundToInt()}%",
            tint = item.color,
        )
    }
    return rows.sortedByDescending { it.first }.map { it.second }
}

@Composable
private fun ConfidenceCapsule(card: ToleranceReplay.ClassTolerance) {
    val label = when {
        card.confidence == glass.kagerou.piru.model.ConfidenceTier.HIGH ->
            stringResource(R.string.toolsb_tolerance_confidence_modeled)
        card.confidence == glass.kagerou.piru.model.ConfidenceTier.MEDIUM ->
            stringResource(R.string.toolsb_tolerance_confidence_modeled)
        card.confidence == glass.kagerou.piru.model.ConfidenceTier.LOW ->
            stringResource(R.string.toolsb_tolerance_confidence_low)
        else -> stringResource(R.string.toolsb_tolerance_confidence_unverified)
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = PiruTheme.colors.secondaryLabel,
    )
}

/**
 * The per-effect ladder for a class whose tolerance is effect-selective.
 *
 * GABA's sedation fades while memory and coordination do not, and that divergence
 * is the escalation trap rather than a detail: the dose goes up because the
 * sedation stopped, and the impairment scales with the new dose.
 */
@Composable
private fun EffectLadder(card: ToleranceReplay.ClassTolerance) {
    val params = ReceptorClasses.parametersFor(card.receptorClass)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (endpoint in params.effectEndpoints) {
            val fraction = card.responseFractionForEffect(endpoint.axis) ?: continue
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(CoreLabels.effectAxis(endpoint.axis), style = MaterialTheme.typography.bodySmall)
                Text(
                    if (fraction > 0.995) {
                        stringResource(R.string.toolsb_tolerance_effect_not_tolerized)
                    } else {
                        stringResource(R.string.toolsb_tolerance_effect_percent_left, (fraction * 100).toInt())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (fraction > 0.995) PiruTheme.colors.cautionText else PiruTheme.colors.secondaryLabel,
                )
            }
        }
    }
}

/**
 * How the class recovers over the next while.
 *
 * `PDModel.shiftDecayMinutes` is the closed form for "when does this fall back to
 * target", so the curve is sampled from it rather than from an integrator — the
 * same function the forecast in the engine is built on. Minutes to full reset is
 * reported for a target of a naive `S = 1`, which is the question people ask.
 */
@Composable
private fun RecoveryChart(card: ToleranceReplay.ClassTolerance) {
    val params = ReceptorClasses.parametersFor(card.receptorClass)
    val layers = PDModel.ToleranceLayers(
        acute = card.sAcute,
        adaptive = card.sAdaptive,
        deep = card.sDeep,
        synthesis = card.sSynthesis,
        tauAcuteMinutes = params.tauAcuteMinutes,
        tauAdaptiveMinutes = params.tauAdaptiveMinutes,
        tauDeepMinutes = params.tauDeepMinutes,
        tauSynthesisMinutes = params.tauSynthesisMinutes,
    )
    if (layers.magnitudes.none { it > 0 }) return

    // Asked about 1.05, not 1.0. The shift is a sum of exponentials, so reaching exactly naive is asymptotic
    // and the honest answer to "when is it zero" is "never" — asking anyway produced "back to baseline in
    // about 493 d" for MDMA, a number that describes a limit nobody reaches. Within 5 % is finite,
    // meaningful, and lands where the model's own documentation says it should: about three weeks for an
    // entactogen, not sixteen months.
    val decayLayers = listOf(
        PDModel.Layer(card.sAcute, params.tauAcuteMinutes),
        PDModel.Layer(card.sAdaptive, params.tauAdaptiveMinutes),
        PDModel.Layer(card.sDeep, params.tauDeepMinutes),
        PDModel.Layer(card.sSynthesis, params.tauSynthesisMinutes),
    ).filter { it.s > 0 }
    val days = PDModel.shiftDecayMinutes(decayLayers, RECOVERED_SHIFT)?.let { it / 1_440.0 }
    // The duration keeps its unit abbreviation ("12 h" / "5 d"), which is a symbol rather than copy, so only
    // the sentence around it is a resource.
    val duration = days?.let { if (it < 1) "${(it * 24).toInt()} h" else "${it.toInt()} d" }

    // The curve, from the model's own gauge. This is what makes the chart and the bar beside it the same
    // quantity: `responseFraction` is saturating and capped per class, and a linear ratio of the raw shift is
    // neither, so a chart drawn from the ratio could say "nearly recovered" where the bar still read high.
    val windowMinutes = maxOf(days ?: 0.0, 1.0) * 1_440.0 * 1.1
    val curve = PDModel.toleranceRecoveryCurve(
        layers = layers,
        representativeOccupancy = card.representativeOccupancy,
        occupancyCap = card.receptorClass.gaugeOccupancyCap,
        windowMinutes = windowMinutes,
    )
    if (curve.isEmpty()) return

    // Read outside the draw scope: a `@Composable` theme read cannot happen inside `Canvas { … }`, which is
    // a plain lambda.
    val accent = PiruTheme.colors.accent

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                var previous = Offset.Zero
                for ((index, point) in curve.withIndex()) {
                    // The y axis is the tolerance percentage, 0 at the bottom and 100 at the top, which is
                    // the same scale the bar reads on. Drawn against the full axis rather than against the
                    // curve's own maximum: a curve autoscaled to its own range hides how little tolerance is
                    // left, which is the one thing the chart is for.
                    val y = size.height * (1f - (point.percent / 100.0).toFloat())
                    val offset = Offset(size.width * index / (curve.size - 1).coerceAtLeast(1), y)
                    if (index > 0) {
                        drawLine(color = accent, start = previous, end = offset, strokeWidth = 2.5f)
                    }
                    previous = offset
                }
            }
        }
        Text(
            if (duration == null) {
                stringResource(R.string.toolsb_tolerance_recovery_no_return)
            } else {
                stringResource(R.string.toolsb_tolerance_recovery_note, duration)
            },
            style = MaterialTheme.typography.bodySmall,
            color = PiruTheme.colors.secondaryLabel,
        )
    }
}

/**
 * The class's own safety line, where it has one.
 *
 * The differential endpoints are the ones worth stating: opioid respiratory
 * depression tolerizes shallower and recovers faster than analgesia, so after a
 * break the breathing is unprotected while the old dose still feels right — and
 * the stimulant pressor does not tolerize at all, so a redose lands on a fresh
 * spike.
 */
@Composable
private fun SafetyNote(card: ToleranceReplay.ClassTolerance, axis: ReceptorClasses.SafetyAxis) {
    // Read up front: the selection below runs inside `let`, which is a plain
    // lambda, and a `@Composable` read cannot happen in one.
    val respiratory = stringResource(R.string.toolsb_tolerance_safety_respiratory)
    val cardiovascular = stringResource(R.string.toolsb_tolerance_safety_cardiovascular)
    val cognitive = stringResource(R.string.toolsb_tolerance_safety_cognitive)
    val dependence = stringResource(R.string.toolsb_tolerance_safety_dependence)
    val toxicity = stringResource(R.string.toolsb_tolerance_safety_toxicity)
    val resetOverdose = stringResource(R.string.toolsb_tolerance_safety_reset_overdose)

    val note = card.safetyGap?.let { gap ->
        if (gap < 1.15) return@let null
        when (card.safetyEndpointKind) {
            ReceptorClasses.SafetyEndpoint.Kind.RESPIRATORY -> respiratory
            ReceptorClasses.SafetyEndpoint.Kind.CARDIOVASCULAR -> cardiovascular
            ReceptorClasses.SafetyEndpoint.Kind.COGNITIVE_IMPAIRMENT -> cognitive
            null -> null
        }
    } ?: when (axis) {
        ReceptorClasses.SafetyAxis.DEPENDENCE_KINDLING -> dependence
        ReceptorClasses.SafetyAxis.CUMULATIVE_TOXICITY -> toxicity
        ReceptorClasses.SafetyAxis.RESET_OVERDOSE -> resetOverdose
        else -> null
    }

    if (note != null) {
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = PiruTheme.colors.cautionText,
        )
    }
}
