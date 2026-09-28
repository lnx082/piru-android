package glass.kagerou.piru.ui.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.engine.PKModel
import glass.kagerou.piru.model.ByVolumeDosing
import glass.kagerou.piru.model.DrinkPreset
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The drink-by-drink builder, with the zero-order elimination curve alcohol
 * actually follows.
 *
 * Ported from `Views/Tools/Alcohol/` — `ByVolumeDoseInputView.swift` (178 lines),
 * the presets in `DrinkPresetManager.swift` (365 lines) and the zero-order model
 * the timeline runs (`PKModel.zeroOrderBodyContent`).
 *
 * ## Why this is not the generic phase bell
 * Alcohol's clearing enzyme is saturated across its whole normal dose range, so
 * elimination runs at a **fixed mass per minute** rather than halving each
 * half-life. The consequence is the one everybody knows from experience and no
 * fixed-width curve can express: duration scales with dose. Two drinks clear in
 * about two hours, eight in about eight, and the model here draws exactly that
 * `M(t) = F·D·(1 − e^{−ka·t}) − Vmax·t`.
 *
 * ## The curve is normalised, and the screen says so
 * The y axis is the share of this drink's own peak, not a blood-alcohol figure.
 * A concentration needs a volume of distribution, and the catalog's
 * `saturable_kinetics` row is not on the read path — printing a g/dL would mean
 * inventing the one number between the model and the label. So the shape, the
 * peak time and the clear time are shown, and the y axis claims nothing more.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlcoholScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication

    var capability by remember { mutableStateOf<ByVolumeDosing?>(null) }
    var kinetics by remember { mutableStateOf<PKModel.ZeroOrderKinetics?>(null) }
    var loaded by remember { mutableStateOf(false) }

    var volumeText by remember { mutableStateOf("") }
    var strengthText by remember { mutableStateOf("") }
    var nameText by remember { mutableStateOf("") }
    var useFluidOunces by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val catalog = app.catalog()
        capability = catalog.byVolumeDosing("alcohol")
        kinetics = catalog.zeroOrderKinetics(
            substanceName = "alcohol",
            // `Vmax` is linear in body weight, so this is the one curve in the
            // alcohol tool that the user's own weight changes the *rate* of — a
            // heavier person clears a given number of drinks faster, and the flat
            // elimination line tilts accordingly.
            weightKg = app.profile().weightKgOrDefault(),
        )
        loaded = true
    }

    val drink = capability?.let { drinkOf(it, volumeText, strengthText, useFluidOunces) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Alcohol", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Build a drink from a volume and a strength, and see how long the " +
                        "body takes to clear it. Ethanol is cleared at a fixed rate, so " +
                        "twice the drink takes about twice as long.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        if (loaded && capability == null) {
            item {
                Text(
                    "The catalog carries no by-volume capability for alcohol in this " +
                        "build, so there is nothing to convert with.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        val caps = capability
        if (caps != null) {
            if (caps.drinkPresets.isNotEmpty()) {
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (preset in caps.drinkPresets) {
                            PresetChip(preset) {
                                // The preset carries millilitres because that is the
                                // unit the conversion works in; the field shows the
                                // chosen unit, so the value is converted on the way in.
                                volumeText = ByVolumeDosing.formatTrimmed(preset.volumeML)
                                strengthText = ByVolumeDosing.formatTrimmed(preset.defaultABV)
                            }
                        }
                    }
                }
            }

            item {
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        NumberRow(
                            label = "Volume",
                            value = volumeText,
                            onValueChange = { volumeText = it },
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(
                                    selected = !useFluidOunces,
                                    onClick = { useFluidOunces = false },
                                    label = { Text("mL") },
                                )
                                FilterChip(
                                    selected = useFluidOunces,
                                    onClick = { useFluidOunces = true },
                                    label = { Text("fl oz") },
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Strength",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                            Box(modifier = Modifier.weight(1f))
                            OutlinedTextField(
                                value = strengthText,
                                onValueChange = { strengthText = it },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.width(110.dp),
                            )
                            Text(
                                "  % ABV",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Name",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                            Box(modifier = Modifier.weight(1f))
                            OutlinedTextField(
                                value = nameText,
                                onValueChange = { nameText = it },
                                singleLine = true,
                                placeholder = { Text("Optional") },
                                modifier = Modifier.width(170.dp),
                            )
                        }

                        // No placeholder when the fields are empty. A "0 g" sitting
                        // under two blank fields reads as a measurement.
                        if (drink != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(
                                    "${drink.grams.roundToInt()} g",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "ethanol · ≈ ${oneDecimal(drink.standardDrinks)} standard drinks",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PiruTheme.colors.secondaryLabel,
                                )
                            }
                        }
                    }
                }
            }

            val k = kinetics
            if (drink != null && k != null) {
                item {
                    EliminationCard(drink.grams * 1000.0, k)
                }
            }

            item {
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("How this is modelled", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Ethanol is cleared by an enzyme that is already saturated at " +
                                "ordinary drinking levels, so the body removes a roughly " +
                                "constant mass per minute instead of a constant fraction. " +
                                "The curve is F·D·(1 − e^−ka·t) − Vmax·t, with the " +
                                "catalog's own Vmax and absorption rate.",
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                        Text(
                            "The clearance rate is scaled to a 60 kg reference body, " +
                                "because this build has no body-weight setting. Vmax is " +
                                "linear in weight, so a different weight stretches or " +
                                "compresses the whole curve rather than changing its shape.",
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                        Text(
                            "Predicted from a model, not measured. A standard drink is " +
                                "14 g of ethanol by US convention — a gloss for reading a " +
                                "figure, not a target. Not medical advice.",
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                }
            }
        }
    }
}

/** One preset chip: the emoji the catalog's kind carries, and its label. */
@Composable
private fun PresetChip(preset: DrinkPreset, onClick: () -> Unit) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = { Text("${preset.kind.emoji}  ${presetName(preset.kind)}") },
    )
}

/** The label for a preset kind — app copy keyed by the case, as upstream keys it. */
private fun presetName(kind: DrinkPreset.Kind): String = when (kind) {
    DrinkPreset.Kind.BEER -> "Beer"
    DrinkPreset.Kind.WINE -> "Wine"
    DrinkPreset.Kind.SHOT -> "Shot"
    DrinkPreset.Kind.PINT -> "Pint"
}

/** A label, a right-aligned numeric field, and whatever trailing control the row carries. */
@Composable
private fun NumberRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = PiruTheme.colors.secondaryLabel,
        )
        Box(modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(110.dp),
        )
        Box(modifier = Modifier.width(8.dp))
        trailing()
    }
}

/** A drink as the fields describe it, or null when they do not describe one yet. */
private data class Drink(val grams: Double, val standardDrinks: Double)

/**
 * The canonical grams for the fields, or null when they hold nothing usable.
 *
 * Parsing is forgiving about a comma decimal separator because a device set to
 * one writes it, and a silently un-parsed field would show no readout at all
 * with no indication why. The unit conversion is exact rather than approximate:
 * a fluid ounce is 29.5735295625 mL by definition.
 */
private fun drinkOf(
    capability: ByVolumeDosing,
    volumeText: String,
    strengthText: String,
    useFluidOunces: Boolean,
): Drink? {
    val volume = volumeText.trim().replace(',', '.').toDoubleOrNull() ?: return null
    val strength = strengthText.trim().replace(',', '.').toDoubleOrNull() ?: return null
    if (volume <= 0 || strength <= 0) return null
    val volumeML = if (useFluidOunces) volume * MILLILITRES_PER_FLUID_OUNCE else volume
    val grams = capability.canonicalAmount(volumeML, strength)
    if (grams <= 0) return null
    return Drink(grams = grams, standardDrinks = ByVolumeDosing.standardDrinks(grams))
}

/** An exact fluid ounce, at 29.5735295625 mL. */
private const val MILLILITRES_PER_FLUID_OUNCE = 29.5735295625

private fun oneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

/**
 * The zero-order elimination curve for one drink, hand-drawn.
 *
 * Ported from the timeline's `zeroOrderShape`, which is this curve normalised to
 * its own peak. Upstream draws it through Swift Charts; the port hand-draws every
 * chart, and this one has to mark two times — the peak, where absorption flux
 * falls to the elimination rate, and the clear, where body content returns to zero.
 */
@Composable
private fun EliminationCard(doseMg: Double, kinetics: PKModel.ZeroOrderKinetics) {
    val peakMinutes = PKModel.zeroOrderPeakMinutes(doseMg, kinetics)
    val clearMinutes = PKModel.zeroOrderClearMinutes(doseMg, kinetics)

    // Hoisted: a `@Composable` theme read cannot happen inside `Canvas { … }`.
    val accent = PiruTheme.colors.accent
    val axis = PiruTheme.colors.secondaryLabel.copy(alpha = 0.35f)
    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))

    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Elimination", style = MaterialTheme.typography.titleSmall)

            if (peakMinutes <= 0 || clearMinutes <= 0) {
                // F·D·ka ≤ Vmax: absorption never out-runs elimination, so body
                // content stays near zero and there is no peak to draw. Saying so
                // is the answer; a flat line at zero would look like a bug.
                Text(
                    "At this amount the model produces no peak — absorption does not " +
                        "out-run the clearance rate, so body content never builds up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
                return@Column
            }

            Box(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val leftPad = 8f
                    val rightPad = 8f
                    val topPad = 10f
                    val bottomPad = 26f
                    val plotW = size.width - leftPad - rightPad
                    val plotH = size.height - topPad - bottomPad
                    if (plotW <= 0f || plotH <= 0f) return@Canvas

                    val span = clearMinutes.toFloat()
                    fun x(minutes: Double) = leftPad + (minutes.toFloat() / span) * plotW
                    fun y(share: Double) = topPad + plotH - (share.toFloat() * plotH)

                    // The area under the shape, then the shape itself.
                    val steps = 120
                    var previous: Offset? = null
                    for (index in 0..steps) {
                        val minutes = clearMinutes * index / steps
                        val share = PKModel.zeroOrderShape(doseMg, minutes, kinetics) ?: 0.0
                        val point = Offset(x(minutes), y(share))
                        previous?.let { drawLine(color = accent, start = it, end = point, strokeWidth = 2.5f) }
                        previous = point
                    }

                    // The two times the reader asks about.
                    drawLine(
                        color = axis,
                        start = Offset(x(peakMinutes), topPad),
                        end = Offset(x(peakMinutes), topPad + plotH),
                        strokeWidth = 2f,
                        pathEffect = dash,
                    )
                    drawLine(
                        color = axis,
                        start = Offset(x(clearMinutes), topPad),
                        end = Offset(x(clearMinutes), topPad + plotH),
                        strokeWidth = 2f,
                        pathEffect = dash,
                    )

                    // Axes last, so they are not overdrawn by the curve.
                    drawLine(
                        color = axis,
                        start = Offset(leftPad, topPad),
                        end = Offset(leftPad, topPad + plotH),
                        strokeWidth = 1f,
                    )
                    drawLine(
                        color = axis,
                        start = Offset(leftPad, topPad + plotH),
                        end = Offset(leftPad + plotW, topPad + plotH),
                        strokeWidth = 1f,
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "peak at ${duration(peakMinutes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
                Box(modifier = Modifier.weight(1f))
                Text(
                    "back to zero by ${duration(clearMinutes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                    textAlign = TextAlign.End,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MetricRow("Peak at", duration(peakMinutes))
                MetricRow("Cleared by", duration(clearMinutes))
                MetricRow(
                    "Clearance",
                    "${String.format(Locale.ROOT, "%.1f", kinetics.vmaxMgPerMin)} mg/min " +
                        "at ${PKModel.REFERENCE_BODY_WEIGHT_KG.roundToInt()} kg",
                )
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = PiruTheme.colors.secondaryLabel,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * A span in minutes as a reader would say it.
 *
 * The boundaries are where the rounding stops being useful rather than round
 * numbers: under 90 minutes the figure is still "a few minutes" and an hour
 * reading would be wrong by a visible margin, and past two days the hours run
 * into three digits for no gain.
 */
private fun duration(minutes: Double): String {
    if (minutes <= 0) return "—"
    if (minutes < 90) return "${minutes.roundToInt()} min"
    if (abs(minutes - minutes.roundToInt()) < 0.05 && minutes < 600) {
        val whole = minutes.roundToInt()
        val hours = whole / 60
        val rest = whole % 60
        return if (rest == 0) "$hours h" else "$hours h $rest min"
    }
    return "${String.format(Locale.ROOT, "%.1f", minutes / 60)} h"
}
