package glass.kagerou.piru.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.model.doseFormatted
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.labels.appLocale
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The calibration controls both depot screens share.
 *
 * Ported from `InjectionLevelsCards.swift` (314 lines): `LabCalibrationSection`,
 * `CalibrationControl`, `ReferenceLinesEditor`, `AddLabResultSheet` and the two
 * confidence chips. `DepotCalibrating.swift` is the protocol behind them; Kotlin
 * has no need of it — the two models already expose the same handful of values,
 * and they are passed in as parameters rather than through a shared interface
 * that would exist only to be implemented twice.
 *
 * The rule the whole file obeys, from upstream: the fit scales the estimate to
 * the person. It never recommends a level, and every "reference line" here is one
 * the user drew.
 */

// MARK: - Shared chrome

/**
 * A card with a small heading and an optional trailing chip.
 *
 * Upstream's `.font(.footnote.weight(.semibold)).foregroundStyle(Theme.secondaryLabel)`
 * header, which every card on both screens wears.
 */
@Composable
internal fun DepotSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    PiruCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = PiruTheme.colors.secondaryLabel,
                )
                trailing?.invoke()
            }
            content()
        }
    }
}

/** The uppercase caption over a field or a value. */
@Composable
internal fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = PiruTheme.colors.secondaryLabel,
    )
}

/** Body text at the secondary colour — upstream's `.captionSecondary()`. */
@Composable
internal fun Caption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = PiruTheme.colors.secondaryLabel,
    )
}

/**
 * A row of segmented choices.
 *
 * Upstream uses `Picker(.segmented)`; the port uses the chip row the rest of the
 * build already styles, so the two read as one app rather than as two idioms.
 */
@Composable
internal fun <T> SegmentedRow(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (option in options) {
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

/**
 * A numeric text field with a unit suffix.
 *
 * Ported from `labeledField`. The text is the editing state and the parsed value
 * is what flows out, which is the only way a field can hold "5." or "" mid-edit
 * without the value snapping back under the cursor.
 */
@Composable
internal fun NumberField(
    label: String,
    value: Double?,
    unit: String,
    onValueChange: (Double?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var text by remember { mutableStateOf(formatEditable(value)) }
    // Resync only when the outside value genuinely disagrees with what the field
    // currently parses to — otherwise a keystroke that produces the same number
    // ("5." for 5.0) would rewrite the field and move the caret.
    LaunchedEffect(value) {
        if (text.toDoubleOrNull() != value) text = formatEditable(value)
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabel(label)
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                text = raw
                onValueChange(raw.trim().replace(',', '.').toDoubleOrNull())
            },
            singleLine = true,
            enabled = enabled,
            suffix = { Text(unit, style = MaterialTheme.typography.bodySmall) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The editable spelling of [value] — an integer stays an integer. */
private fun formatEditable(value: Double?): String = when {
    value == null -> ""
    value == value.toLong().toDouble() -> value.toLong().toString()
    else -> value.toString()
}

// MARK: - The calibration chip

/**
 * `Uncalibrated` / `1 result` / `Calibrated · N results`.
 *
 * The count is of measurements **included** in the fit, not of rows on screen —
 * which is what makes unticking a point visibly widen the band.
 */
@Composable
internal fun CalibrationChip(includedCount: Int) {
    val colors = PiruTheme.colors
    val label = when (includedCount) {
        0 -> stringResource(R.string.toolsb_depot_calibration_chip_uncalibrated)
        1 -> stringResource(R.string.toolsb_depot_calibration_chip_one_result)
        else -> stringResource(R.string.toolsb_depot_calibration_chip_calibrated, includedCount)
    }
    val tint = if (includedCount == 0) colors.secondaryLabel else colors.accent
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = tint,
        modifier = Modifier
            .background(tint.copy(alpha = 0.10f), MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

// MARK: - The lab rows

/**
 * The draw-date format, `.dateTime.year().month(.abbreviated).day()` upstream.
 *
 * The pattern is passed in rather than written here: the field order is
 * locale-specific (Chinese reads yyyy年M月d日), so it comes from the resources
 * and the composable callers resolve it. The locale travels with it, because the
 * month *name* is a word and has to be the app's language, not the device's.
 * See docs/localization.md, "Dates need two things".
 */
private fun labDateFormat(pattern: String, locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern(pattern, locale)

/**
 * One lab result: its date, its value in the unit the user typed it in, a tick
 * that includes or excludes it from the fit, and a delete.
 *
 * The value is stored canonical and shown in `inputUnit`, so a level entered as
 * 1200 pmol/L still reads back as 1200 pmol/L.
 */
@Composable
internal fun LabRow(
    lab: LabMeasurement,
    analyte: Analyte,
    onToggleExcluded: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PiruTheme.colors
    val zone = remember { ZoneId.systemDefault() }
    val labDatePattern = stringResource(R.string.datefmt_month_day_year)
    val dateLocale = appLocale()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                labDateFormat(labDatePattern, dateLocale).withZone(zone).format(lab.date),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "${"%.1f".format(Locale.ROOT, analyte.fromCanonical(lab.value, lab.inputUnit))} ${lab.inputUnit}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondaryLabel,
            )
        }
        // Upstream keeps the tick and a swipe-to-delete. Android's list idiom for
        // the delete half is a trailing action rather than a gesture, so the two
        // live side by side here instead.
        FilterChip(
            selected = !lab.excludedFromCalibration,
            onClick = onToggleExcluded,
            label = {
                Text(
                    stringResource(
                        if (lab.excludedFromCalibration) {
                            R.string.toolsb_depot_lab_row_excluded
                        } else {
                            R.string.toolsb_depot_lab_row_included
                        },
                    ),
                )
            },
        )
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.toolsb_depot_lab_row_delete),
                tint = colors.secondaryLabel,
            )
        }
    }
}

// MARK: - Personal calibration

/**
 * The amplitude "run high / run low" knob and its lab-driven state.
 *
 * Ported from `CalibrationControl`. When the user has labs and auto-calibration
 * is on, the fit owns the amplitude and this shows it read-only, plus a note when
 * the shape — the terminal rate — was fitted too.
 */
@Composable
internal fun CalibrationControl(
    hasLabs: Boolean,
    isLabDriven: Boolean,
    calibration: DepotCalibration.Result?,
    calibrationMeasurementCount: Int,
    effectiveMultiplier: Double,
    personalMultiplier: Double,
    autoCalibrateFromLabs: Boolean,
    fitRates: Boolean,
    onAutoCalibrateChange: (Boolean) -> Unit,
    onFitRatesChange: (Boolean) -> Unit,
    onMultiplierChange: (Double) -> Unit,
) {
    val colors = PiruTheme.colors
    val multiplierText = "×" + "%.2f".format(Locale.ROOT, effectiveMultiplier)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.toolsb_depot_calibration_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.secondaryLabel,
            )
            Text(
                multiplierText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isLabDriven) colors.accent else colors.secondaryLabel,
            )
        }

        if (hasLabs) {
            ToggleRow(
                label = stringResource(R.string.toolsb_depot_calibration_use_labs),
                checked = autoCalibrateFromLabs,
                onCheckedChange = onAutoCalibrateChange,
            )
        }

        if (isLabDriven) {
            if (calibration?.didFitRate == true) {
                val rate = ratePhrase(calibration.k1Scale)
                Caption(stringResource(R.string.toolsb_depot_calibration_fit_shape_note, rate))
            } else {
                Caption(stringResource(R.string.toolsb_depot_calibration_fit_height_note))
            }
            if (calibrationMeasurementCount >= 2) {
                ToggleRow(
                    label = stringResource(R.string.toolsb_depot_calibration_fit_shape_toggle),
                    checked = fitRates,
                    onCheckedChange = onFitRatesChange,
                )
            }
        } else {
            Slider(
                value = personalMultiplier.toFloat(),
                onValueChange = { onMultiplierChange(it.toDouble()) },
                valueRange = 0.3f..3.0f,
                steps = 53,
            )
            Caption(stringResource(R.string.toolsb_depot_calibration_adjust_note))
        }
    }
}

/**
 * A comparative phrase — "1.30× faster than average".
 *
 * A larger `k1` is a faster terminal release, so the direction reads off the
 * scale directly rather than off a reciprocal nobody can check by eye.
 *
 * A `@Composable` read of two resources, so the phrase is translated as a whole
 * rather than assembled from a translated half and an English one.
 */
@Composable
private fun ratePhrase(k1Scale: Double): String {
    val factor = "%.2f".format(Locale.ROOT, if (k1Scale >= 1) k1Scale else 1 / k1Scale)
    return if (k1Scale >= 1) {
        stringResource(R.string.toolsb_depot_calibration_rate_faster, factor)
    } else {
        stringResource(R.string.toolsb_depot_calibration_rate_slower, factor)
    }
}

/** A labelled switch, upstream's `Toggle` with a subheadline label. */
@Composable
internal fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// MARK: - Reference lines

/**
 * The user's own low and high lines.
 *
 * Ported from `ReferenceLinesEditor`. The closing line is upstream's and is the
 * point of the card: "Your own lines. Piru sets no target."
 */
@Composable
internal fun ReferenceLinesEditor(
    referenceLow: Double?,
    referenceHigh: Double?,
    unit: String,
    onLowChange: (Double?) -> Unit,
    onHighChange: (Double?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(stringResource(R.string.toolsb_depot_reference_lines_title))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField(
                label = stringResource(R.string.toolsb_depot_reference_lines_low),
                value = referenceLow,
                unit = unit,
                onValueChange = onLowChange,
                modifier = Modifier.weight(1f),
            )
            NumberField(
                label = stringResource(R.string.toolsb_depot_reference_lines_high),
                value = referenceHigh,
                unit = unit,
                onValueChange = onHighChange,
                modifier = Modifier.weight(1f),
            )
        }
        Caption(stringResource(R.string.toolsb_depot_reference_lines_note))
    }
}

// MARK: - Add a lab result

/**
 * Enter one blood test.
 *
 * Ported from `AddLabResultSheet`. The level is entered in whatever unit the
 * lab reported and stored canonical, so the fit runs on one scale while the row
 * echoes the user's own numbers back.
 */
@Composable
internal fun AddLabResultDialog(
    analyte: Analyte,
    esterID: String?,
    onSave: (LabMeasurement) -> Unit,
    onDismiss: () -> Unit,
) {
    var date by remember { mutableStateOf(Instant.now()) }
    var value by remember { mutableStateOf<Double?>(null) }
    var unit by remember { mutableStateOf(analyte.canonicalUnit) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.toolsb_depot_add_lab_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DateField(date = date, onDateChange = { date = it })
                NumberField(
                    label = stringResource(R.string.toolsb_depot_add_lab_serum_level),
                    value = value,
                    unit = unit,
                    onValueChange = { value = it },
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FieldLabel(stringResource(R.string.toolsb_depot_add_lab_unit))
                    SegmentedRow(
                        options = analyte.acceptedUnits,
                        selected = unit,
                        label = { it },
                        onSelect = { unit = it },
                    )
                }
                Caption(stringResource(R.string.toolsb_depot_add_lab_unit_note, analyte.canonicalUnit))
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
                            analyteKey = analyte.key,
                            value = analyte.toCanonical(raw, unit),
                            inputUnit = unit,
                            esterID = esterID,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * A draw date, shifted a day at a time.
 *
 * Upstream presents a `DatePicker`. A lab draw is dated, not timed, and the
 * common case is "today" or "a few days ago" — so this is a stepper rather than a
 * calendar, which is one tap instead of four for the case that actually happens.
 */
@Composable
private fun DateField(date: Instant, onDateChange: (Instant) -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val labDatePattern = stringResource(R.string.datefmt_month_day_year)
    val dateLocale = appLocale()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabel(stringResource(R.string.toolsb_depot_add_lab_draw_date))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onDateChange(date.minusSeconds(86_400)) }) {
                Text(stringResource(R.string.toolsb_depot_add_lab_day_minus))
            }
            Text(
                labDateFormat(labDatePattern, dateLocale).withZone(zone).format(date),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { onDateChange(date.plusSeconds(86_400)) }) {
                Text(stringResource(R.string.toolsb_depot_add_lab_day_plus))
            }
        }
    }
}

// MARK: - Metrics

/**
 * Estimated trough, peak and time in range.
 *
 * Ported from `InjectionLevelsMetricsCard`, shared by both depot screens because
 * they read the same [DepotCurveResult] — which is the point of that type: the
 * tool's curve and the insight's summed curve are different inputs to one set of
 * readings, not two implementations that could drift.
 */
@Composable
internal fun DepotMetricsCard(result: DepotCurveResult, analyte: Analyte, modifier: Modifier = Modifier) {
    DepotSectionCard(title = stringResource(R.string.toolsb_depot_metrics_title), modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(
                key = stringResource(R.string.toolsb_depot_metrics_trough),
                value = metricFormat(result.trough),
                sub = "${metricFormat(result.troughLow)}–${metricFormat(result.troughHigh)} ${analyte.canonicalUnit}",
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                key = stringResource(R.string.toolsb_depot_metrics_peak),
                value = metricFormat(result.peak),
                sub = "${metricFormat(result.peakLow)}–${metricFormat(result.peakHigh)} ${analyte.canonicalUnit}",
                modifier = Modifier.weight(1f),
            )
        }
        val tir = result.timeInRange
        if (tir != null) {
            MetricTile(
                key = stringResource(R.string.toolsb_depot_metrics_time_in_range),
                value = "${(tir * 100).toInt()}%",
                sub = stringResource(R.string.toolsb_depot_metrics_time_in_range_sub),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// MARK: - Misc

/** A titled rule, upstream's `Divider()` between the list and the controls. */
@Composable
internal fun SectionDivider() {
    HorizontalDivider(color = PiruTheme.colors.secondaryLabel.copy(alpha = 0.2f))
}

/** The value format the metric tiles use: one decimal below 10, none above. */
internal fun metricFormat(value: Double): String = when {
    value < 10 -> "%.1f".format(Locale.ROOT, value)
    else -> "%.0f".format(Locale.ROOT, value)
}

/** Upstream's `doseFormatted`, reused for the plateau range readout. */
internal fun plateauFormat(value: Double): String = doseFormatted(value)
