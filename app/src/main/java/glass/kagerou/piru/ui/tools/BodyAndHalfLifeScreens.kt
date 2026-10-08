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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.engine.ActiveSubstance
import glass.kagerou.piru.engine.ActiveSubstanceCalculator
import glass.kagerou.piru.engine.BodyLoadTrail
import glass.kagerou.piru.engine.PKModel
import glass.kagerou.piru.engine.PKResolver
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.components.ScrubReadout
import glass.kagerou.piru.ui.components.ScrubRow
import glass.kagerou.piru.ui.components.drawScrubRule
import glass.kagerou.piru.ui.components.interpolateAt
import glass.kagerou.piru.ui.components.timeScrub
import glass.kagerou.piru.ui.insights.InsightsFilterPill
import glass.kagerou.piru.ui.insights.InsightsSectionCard
import glass.kagerou.piru.ui.insights.UsageTimeRange
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import glass.kagerou.piru.ui.theme.toComposeColor
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Alignment
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.ui.labels.CoreLabels

/**
 * What is still in the body, and how that moves — backwards and forwards.
 *
 * Ported from `Views/Insights/InYourBodyView.swift`, `BodyLoadChart.swift` and
 * `BodyLevelsPlan.swift`.
 *
 * The readout answers "how much is left", which is a different question from the
 * one the timeline answers ("what does the effect curve look like") and the two
 * diverge hardest exactly where a guess would do the most damage — amphetamine's
 * ten-hour half-life far outlasts its subjective effects, and fluoxetine's
 * sixteen-day one outlasts any graph. That is why this screen can show a substance
 * the timeline draws no curve for at all.
 *
 * ## Two readings of one model, hence one screen
 * The trail at the top is this same readout swept across time, sampled by
 * [BodyLoadTrail] from [ActiveSubstanceCalculator.compute] — the very function the
 * cards below call — so a point on a curve and the number under it cannot disagree.
 * Dragging the rule reads any instant in the window, and the window reaches *ahead*
 * of now, which is where the question "when is this out of me" is actually answered.
 *
 * Supplements are absent by the engine's own decision, not by an oversight here:
 * they clear over days to weeks, so "0 % eliminated, clears in five months" is
 * noise rather than a session insight.
 */
@Composable
fun BodyLoadScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    var range by rememberSaveable { mutableStateOf(UsageTimeRange.THIRTY_DAYS) }
    var active by remember { mutableStateOf<List<ActiveSubstance>>(emptyList()) }
    var trail by remember { mutableStateOf<List<BodyLoadTrail.Series>>(emptyList()) }
    var selected by remember { mutableStateOf<Instant?>(null) }
    var loaded by remember { mutableStateOf(false) }

    // Re-read on a range change rather than caching the log: the read is a few
    // hundred rows, and the alternative is a second piece of state that has to be
    // kept in step with the first. `ReceptorLoadScreen` does the same.
    //
    // Off the main thread, because the trail samples the whole log up to two
    // thousand times over. `LaunchedEffect` continues on the composition's
    // dispatcher, which is the main one, so the default dispatcher is named
    // explicitly rather than assumed.
    LaunchedEffect(navigator.dataVersion, range) {
        val (computed, built) = withContext(Dispatchers.Default) {
            val catalog = app.catalog()
            val entries = app.database.doseEntryDao().all().mapNotNull { it.toDoseRecordIfReplayable() }
            val tints = app.palette().tintsFor(entries.map { it.substance }.toSet())
            val now = Instant.now()
            val active = ActiveSubstanceCalculator.compute(
                entries = entries,
                colorMap = tints,
                catalog = catalog,
                // A substance with no colour of its own and none stored: the neutral
                // stand-in rather than the accent, so an uncoloured row does not read
                // as deliberately branded.
                fallbackTint = P3Color.NEUTRAL,
                now = now,
            )
            val trail = BodyLoadTrail.build(
                entries = entries,
                colorMap = tints,
                catalog = catalog,
                fallbackTint = P3Color.NEUTRAL,
                now = now,
                pastMinutes = pastMinutesFor(range, entries, now),
                futureMinutes = FUTURE_HORIZON_MINUTES,
            )
            active to trail
        }
        active = computed
        trail = built
        // The cursor starts at the end of the window — the newest sample, which is the
        // chart's own "now" — rather than absent. An absent cursor has nothing to
        // focus, so a screen reader user had no way into the chart at all: the drag
        // these step actions replace is the only other way to move it. It also answers
        // "where is this now" without requiring a tap, and "Back to now" is still
        // where a moved cursor returns.
        selected = built.firstOrNull()?.points?.lastOrNull()?.date
        loaded = true
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.toolsb_bodyload_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.toolsb_bodyload_subtitle),
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

        if (trail.isNotEmpty()) {
            // The window is the trail's own grid rather than a re-derived `now ±
            // range`: the samples already carry it, and a second derivation is a
            // second chance for the axis and the data to disagree.
            val windowFrom = trail.first().points.first().date
            val windowTo = trail.first().points.last().date
            item {
                InsightsSectionCard(
                    title = stringResource(R.string.toolsb_bodyload_chart_title),
                    subtitle = stringResource(R.string.toolsb_bodyload_chart_subtitle),
                ) {
                    TrailChart(
                        series = trail.map { series ->
                            TrailChartSeries(
                                id = series.id,
                                tint = series.tint.toComposeColor(),
                                points = series.points.map { TrailChartPoint(it.date, it.fraction) },
                            )
                        },
                        selected = selected,
                        onSelect = { selected = it },
                        windowFrom = windowFrom,
                        windowTo = windowTo,
                        axisCaption = stringResource(R.string.toolsb_bodyload_axis_caption),
                        accessibilityLabel = stringResource(R.string.scrub_label_body_load),
                    )

                    selected?.let { at ->
                        ScrubReadout(
                            title = trailReadoutTitle(at),
                            rows = rowsAt(trail, at),
                            emptyText = stringResource(R.string.toolsb_bodyload_readout_empty),
                            onReset = { selected = null },
                        )
                    }
                }
            }
        }

        if (loaded && active.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.toolsb_bodyload_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        items(active, key = { it.id }) { substance ->
            // Hoisted: a `@Composable` theme read cannot happen inside `Canvas { … }`.
            val track = PiruTheme.colors.secondaryLabel.copy(alpha = 0.18f)
            val fill = PiruTheme.colors.accent
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(substance.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(
                                R.string.toolsb_bodyload_left,
                                trim(substance.totalRemaining),
                                substance.unit,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    // The bar is the fraction *eliminated*, so a full bar means
                    // cleared — the same direction as the tolerance gauge, and the
                    // opposite of "how much is left", which is why both numbers are
                    // written out beside it.
                    Box(modifier = Modifier.fillMaxWidth().height(10.dp)) {
                        Canvas(Modifier.fillMaxSize()) {
                            val fraction = substance.eliminatedFraction.coerceIn(0.0, 1.0).toFloat()
                            drawRoundRect(
                                color = track,
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
                            )
                            drawRoundRect(
                                color = fill,
                                size = androidx.compose.ui.geometry.Size(size.width * fraction, size.height),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
                            )
                        }
                    }
                    // One sentence, three readouts: the whole line is a single
                    // resource so a language that reorders them can, and the dose
                    // count comes from its own pair because English needs the plural.
                    val doseCount = if (substance.doses.size == 1) {
                        stringResource(R.string.toolsb_bodyload_dose_count_one, substance.doses.size)
                    } else {
                        stringResource(R.string.toolsb_bodyload_dose_count_many, substance.doses.size)
                    }
                    Text(
                        stringResource(
                            R.string.toolsb_bodyload_row_summary,
                            (substance.eliminatedFraction * 100).toInt(),
                            doseCount,
                            hours(substance.halfLifeMinutes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        item {
            Text(
                stringResource(R.string.toolsb_model_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

private const val MINUTES_PER_DAY: Double = 1_440.0

/**
 * How far back the trail reaches for a chosen range.
 *
 * The `All` pill carries no day count, so it reaches to the oldest thing logged —
 * the same rule `buildReceptorLoadSeries` uses for the receptor chart, and the only
 * reading of "All" that is not a quiet substitution of some other window.
 */
private fun pastMinutesFor(
    range: UsageTimeRange,
    entries: List<glass.kagerou.piru.engine.DoseRecord>,
    now: Instant,
): Double = range.days?.let { it * MINUTES_PER_DAY }
    ?: (now.toEpochMilli() - (entries.minOfOrNull { it.timestamp.toEpochMilli() } ?: now.toEpochMilli())) / 60_000.0

/**
 * How far past now the body-load window reaches: a week.
 *
 * The future half is the point of the trail — "when is this out of me" is a
 * forward question — and a week covers the clearance of everything with an
 * elimination half-life short enough for the answer to be interesting. A
 * longer-acting compound simply leaves the window still falling, which is honest:
 * the curve says "still here", which is the answer.
 */
private const val FUTURE_HORIZON_MINUTES: Double = 7 * MINUTES_PER_DAY

/**
 * The readout lines: every substance with something in the body at [at], biggest
 * first.
 *
 * Interpolated along each series' own drawn segment rather than snapped to the
 * nearest sample, so the number under the cursor is the value the line passes
 * through at that x. The title names the exact dragged instant; a readout that
 * answers a different moment than the one it names is worse than a coarse one, and
 * on a long window (twelve hours a sample) "nearest" can be half a day out.
 */
@Composable
private fun rowsAt(trail: List<BodyLoadTrail.Series>, at: Instant): List<ScrubRow> {
    val context = LocalContext.current
    return trail
        .mapNotNull { series ->
            val amount = series.points.interpolateAt(at, { it.date }, { it.amount })
                ?: return@mapNotNull null
            // Nothing in the body is not a row with a zero in it; it is the absence
            // of a row, which the readout's own empty state then speaks to.
            if (amount <= 0.0) return@mapNotNull null
            amount to ScrubRow(
                label = series.displayName,
                value = context.getString(R.string.toolsb_bodyload_left, trim(amount), series.unit),
                tint = series.tint.toComposeColor(),
            )
        }
        .sortedByDescending { it.first }
        .map { it.second }
}

/**
 * How one dose decays.
 *
 * Ported from `Views/Insights/HalfLifeCalculatorView.swift`.
 *
 * The curve is `PKModel.fractionRemainingInBody`, which is the same function the
 * body-load readout integrates — so the shape here is the shape behind every
 * number on that screen rather than a second, illustrative one.
 *
 * ## A substance with no half-life says so
 * The catalog carries none for a great many compounds, and the honest answer is
 * that the app cannot say — not a curve drawn from a plausible default. The
 * screen names the substances it *can* do this for, so an empty list is
 * information rather than a dead end.
 */
/**
 * A single dose's pharmacokinetics: how much is left, when it peaks, and the half-life ladder.
 *
 * Ported from `HalfLifeCalculatorView`. The screen answers the question people actually arrive with — "if I take
 * this at eight, how much is still in me at midnight" — and the port answered a narrower one: it drew a decay
 * curve and nothing else.
 *
 * ## What the calculator needs and the old screen did not have
 * A dose, an elapsed time, the route, and an override for a substance whose half-life the catalogue does not
 * carry. Every one of those is an input to arithmetic that already existed in `PKModel` and `PKResolver`; what
 * was missing was a way to reach it.
 *
 * ## The route is not cosmetic
 * `ka` — the absorption constant — comes from the route's own duration profile, so an oral dose and an
 * insufflated one of the same substance peak at different times. Drawing one route's curve while the picker says
 * another is a quiet way to be wrong.
 *
 * ## The override is a *modelling* input, not a stored fact
 * It changes what this screen draws and nothing else. The alternative — writing it to the substance — would let
 * a user's guess leak into every other reading in the app, which is why upstream keeps it in the view model too.
 */
@Composable
fun HalfLifeScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication

    /** The substances a half-life can be resolved for, by name. Whole library, not just the log. */
    var choices by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var substance by remember { mutableStateOf<Substance?>(null) }
    var route by remember { mutableStateOf(RouteOfAdministration.ORAL) }
    var loaded by remember { mutableStateOf(false) }

    // The calculator's own inputs.
    var doseText by remember { mutableStateOf("") }
    var elapsedHoursText by remember { mutableStateOf("") }
    var useCustomHalfLife by remember { mutableStateOf(false) }
    var customHoursText by remember { mutableStateOf("") }

    LaunchedEffect(navigator.dataVersion) {
        val catalog = app.catalog()
        choices = allHalfLifeChoices(
            catalog = catalog,
            loggedNames = app.database.doseEntryDao().all().map { it.substance },
        )
        selected = choices.firstOrNull()
        loaded = true
    }

    // Resolved whenever the pick changes, so the curve and the ladder agree about which substance they describe.
    LaunchedEffect(selected, useCustomHalfLife) {
        val name = selected
        substance = name?.let {
            runCatching { withContext(Dispatchers.IO) { app.catalog() }.resolveFull(it) }.getOrNull()
        }
        route = substance?.defaultRoute ?: RouteOfAdministration.ORAL
    }

    val resolved = substance
    val customHours = customHoursText.trim().toDoubleOrNull()
    val halfLife = HalfLifeCalculation.effectiveHalfLife(
        useCustom = useCustomHalfLife,
        customHours = customHours,
        substance = resolved,
    )
    val rateConstants = HalfLifeCalculation.rateConstants(
        halfLifeMinutes = halfLife,
        duration = resolved?.resolveDuration(route),
    )
    val params = remember(resolved, route, halfLife) {
        resolved?.let { PKResolver.params(it, route) }
    }

    val dose = doseText.trim().toDoubleOrNull()
    val elapsedMinutes = elapsedHoursText.trim().toDoubleOrNull()?.times(60.0)
    val remaining = if (dose != null && elapsedMinutes != null && halfLife != null) {
        HalfLifeCalculation.remainingAmount(
            dose = dose,
            elapsedMinutes = elapsedMinutes,
            halfLifeMinutes = halfLife,
            rateConstants = rateConstants,
        )
    } else {
        null
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.toolsb_halflife_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.toolsb_halflife_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        if (loaded && choices.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.toolsb_halflife_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        if (choices.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (batch in choices.chunked(3)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (name in batch) {
                                FilterChip(
                                    selected = name == selected,
                                    onClick = { selected = name },
                                    label = { Text(name) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (resolved != null && halfLife != null) {
            item {
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(resolved.displayTitle, style = MaterialTheme.typography.titleSmall)
                        // The route picker, over the routes the substance actually has a duration for. A route
                        // with no profile would draw the same curve as the default and imply otherwise.
                        val routes = resolved.routes.map { it.route }.distinct()
                        if (routes.size > 1) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (candidate in routes) {
                                    FilterChip(
                                        selected = candidate == route,
                                        onClick = { route = candidate },
                                        label = { Text(CoreLabels.route(candidate)) },
                                    )
                                }
                            }
                        }
                        Text(
                            stringResource(
                                R.string.toolsb_halflife_summary,
                                hours(halfLife),
                                rateConstants?.ke ?: 0.0,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                        params?.let { DecayCurve(it) }
                    }
                }
            }

            item {
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            stringResource(R.string.toolsb_halflife_calculator),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        CalculatorField(
                            value = doseText,
                            onValueChange = { doseText = it },
                            label = stringResource(R.string.toolsb_halflife_dose),
                            unit = stringResource(R.string.toolsb_halflife_unit_mg),
                        )
                        CalculatorField(
                            value = elapsedHoursText,
                            onValueChange = { elapsedHoursText = it },
                            label = stringResource(R.string.toolsb_halflife_elapsed),
                            unit = stringResource(R.string.toolsb_halflife_unit_hours),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.toolsb_halflife_custom),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    stringResource(R.string.toolsb_halflife_custom_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PiruTheme.colors.secondaryLabel,
                                )
                            }
                            Switch(
                                checked = useCustomHalfLife,
                                onCheckedChange = { useCustomHalfLife = it },
                            )
                        }
                        if (useCustomHalfLife) {
                            CalculatorField(
                                value = customHoursText,
                                onValueChange = { customHoursText = it },
                                label = stringResource(R.string.toolsb_halflife_custom_hours),
                                unit = stringResource(R.string.toolsb_halflife_unit_hours),
                            )
                        }
                    }
                }
            }

            item {
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.toolsb_halflife_remaining),
                            style = MaterialTheme.typography.labelLarge,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                        if (remaining != null) {
                            Text(
                                stringResource(
                                    R.string.toolsb_halflife_remaining_value,
                                    trim(remaining),
                                    percent(remaining / (dose ?: 1.0)),
                                ),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        } else {
                            // An em dash rather than a zero: an incomplete form has no answer, and a zero would
                            // read as "nothing left", which is the opposite of unknown.
                            Text("—", style = MaterialTheme.typography.headlineSmall)
                        }
                        // The milestone ladder, timed against the fitted curve when there is one — the early
                        // steps come later than `n × t½` because absorption is still filling the compartment.
                        for (milestone in HalfLifeCalculation.milestones(halfLife, rateConstants)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    stringResource(
                                        R.string.toolsb_halflife_milestone,
                                        milestone.n,
                                        percent(milestone.fraction),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    hours(milestone.minutes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PiruTheme.colors.secondaryLabel,
                                )
                            }
                        }
                        Text(
                            stringResource(
                                R.string.toolsb_halflife_about_five,
                                hours(halfLife * 5),
                                hours(halfLife * 7),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                }
            }
        }

        item {
            Text(
                stringResource(R.string.toolsb_model_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

/**
 * A numeric input with its unit shown as a suffix.
 *
 * A decimal keypad, because every field here takes a fraction of a unit and a text keyboard makes the point the
 * hardest character to reach.
 */
@Composable
private fun CalculatorField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    unit: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        suffix = { Text(unit, color = PiruTheme.colors.secondaryLabel) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The substances with a resolvable half-life, by display name, from the log.
 *
 * ## Why the log, and not the whole library
 * The audit lists "whole-library substance selection" for this tool, and the honest version of that turned out to
 * be narrower than it sounds: the only enumeration `SubstanceCatalog` exposes is `lookup(name)`, and the
 * catalogue-wide `search` lives on `DbSubstanceCatalog` rather than on the interface. Widening the interface for
 * one picker is the wrong trade, and a chip for every substance in the catalogue is several hundred chips — a
 * picker nobody can scan.
 *
 * The log is the list the screen already had, and it is the right one: the half-life question is asked about a
 * substance the user has taken or is about to take, both of which are in the log or one chip away from it.
 *
 * A substance whose half-life the catalogue does not carry is left out rather than shown with an empty curve:
 * `PKResolver.halfLifeMinutes` returning null is the honest answer for a compound nobody has measured.
 */
private suspend fun allHalfLifeChoices(
    catalog: SubstanceCatalog,
    loggedNames: List<String>,
): List<String> = loggedNames
    .distinct()
    .mapNotNull { name ->
        val substance = catalog.lookup(name) ?: return@mapNotNull null
        if (PKResolver.halfLifeMinutes(substance) == null) return@mapNotNull null
        substance.displayTitle
    }
    .distinct()
    .sortedBy { it.lowercase() }

/**
 * The elimination curve over seven half-lives, drawn from the same function the
 * body-load readout uses, with a time cursor over it.
 *
 * The cursor differs from the other screens' in two ways, both because this curve
 * is a *single dose's own arc* rather than a window over the log: it starts at the
 * dose itself rather than at a "now", and it has no "back to now" — a reset would
 * name a moment that does not exist. Drag it, or tap where you want it, and the
 * readout says how much of the dose is left at that elapsed time.
 *
 * It used to start *absent*, and no longer does. An absent cursor has nothing to
 * focus, so a screen reader user had no way in at all: the drag these actions
 * replace is the only other way to move it. Seeding it at zero costs a sighted
 * reader nothing — the rule sits on the curve's left edge, where a tap would have
 * put it anyway — and it is the reading that is true before anything has been
 * eliminated.
 */
@Composable
private fun DecayCurve(params: PKResolver.Params) {
    val accent = PiruTheme.colors.accent
    val mark = PiruTheme.colors.secondaryLabel.copy(alpha = 0.3f)
    val spanMinutes = params.halfLifeMinutes * 7
    var scrubMinutes by remember(params) { mutableStateOf<Double?>(0.0) }

    // One reading, used twice: the cursor's spoken state and the visible readout are
    // the same sentence, so a screen reader and a sighted reader cannot be told
    // different things about the same moment.
    val scrubAt = scrubMinutes?.let { minutes ->
        minutes to PKModel.fractionRemainingInBody(minutes, params.ke, params.ka)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .timeScrub(
                        label = stringResource(R.string.scrub_label_half_life),
                        readout = scrubAt?.let {
                            stringResource(
                                R.string.scrub_cursor_at,
                                stringResource(R.string.toolsb_halflife_scrub_after, hours(it.first)),
                            )
                        },
                        // One half-life is the unit this curve is actually about, and
                        // it is a seventh of the window the curve draws.
                        cursorFraction = scrubAt?.let { (it.first / spanMinutes).toFloat().coerceIn(0f, 1f) },
                        stepFraction = 1f / 7f,
                    ) { fraction -> scrubMinutes = fraction * spanMinutes },
            ) {
                val steps = 120
                var previous = Offset.Zero
                for (index in 0..steps) {
                    val minutes = spanMinutes * index / steps
                    val fraction = PKModel.fractionRemainingInBody(minutes, params.ke, params.ka)
                    val point = Offset(size.width * index / steps, size.height * (1 - fraction.toFloat()))
                    if (index > 0) {
                        drawLine(color = accent, start = previous, end = point, strokeWidth = 2.5f)
                    }
                    previous = point
                }
                // The half-life marks, so the shape is readable without a scale.
                var half = 0
                while (half < 3) {
                    val minutes = params.halfLifeMinutes * (half + 1)
                    val x = size.width * (minutes / spanMinutes).toFloat()
                    drawLine(
                        color = mark,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1f,
                    )
                    half++
                }

                scrubMinutes?.let { minutes ->
                    drawScrubRule(x = size.width * (minutes / spanMinutes).toFloat(), color = accent)
                }
            }
        }

        scrubAt?.let { (minutes, fraction) ->
            ScrubReadout(
                title = stringResource(R.string.toolsb_halflife_scrub_after, hours(minutes)),
                rows = listOf(
                    ScrubRow(
                        label = stringResource(R.string.toolsb_halflife_scrub_remaining),
                        value = percent(fraction),
                        tint = accent,
                    ),
                ),
            )
        }
    }
}

/**
 * A duration in the shortest unit that still reads: "12 min", "1.5 h", "1.5 d".
 *
 * Those are unit abbreviations rather than copy, so they are not resources — but
 * the format is locale-sensitive in Kotlin and is not in Swift's
 * `String(format:)`, so the radix has to be pinned or a comma decimal separator
 * would leak into a number the engine computed.
 */
private fun hours(minutes: Double): String = when {
    minutes < 90 -> "${minutes.toInt()} min"
    minutes < 60 * 48 -> "%.1f h".format(Locale.ROOT, minutes / 60)
    else -> "%.1f d".format(Locale.ROOT, minutes / 1_440)
}

/** A 0…1 fraction as whole-percent copy, without the `%` — that belongs to the sentence around it. */
private fun percent(fraction: Double): String =
    "${(fraction.coerceIn(0.0, 1.0) * 100).roundToInt()}%"

private fun trim(value: Double): String =
    if (value >= 100) value.toInt().toString()
    else "%.2f".format(Locale.ROOT, value).trimEnd('0').trimEnd('.')

private fun glass.kagerou.piru.data.entity.DoseEntryEntity.toDoseRecordIfReplayable() =
    if (isUnknownDose) {
        null
    } else {
        glass.kagerou.piru.engine.DoseRecord(
            substance = substance,
            amount = amount,
            unit = unit,
            route = route,
            timestamp = timestamp.toInstant(),
            isUnknownDose = false,
            releaseForm = releaseForm,
            productName = productName,
            saltForm = saltForm,
            isomer = isomer,
            substanceUID = substanceUID,
        )
    }
