package glass.kagerou.piru.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * Injection Levels — the depot serum curve projected from injectable ester doses.
 *
 * Ported from `Piru/Views/Tools/InjectionLevels/InjectionLevelsView.swift` (419
 * lines), with `InjectionLevelsModel.swift` and `InjectionLevelsCards.swift`.
 *
 * Log-first: it reads qualifying IM/SC estradiol or testosterone doses from the
 * dose log and draws immediately, falling back to a manual schedule when the log
 * has none. It predicts a concentration — never a dose, an interval, or a level
 * to aim for.
 *
 * ## The sibling screen
 * `HormoneLevelsScreen` in Insights draws the same chemistry retrospectively:
 * every logged ester summed into one serum total, calibrated the same way. The
 * tool keeps the hypothetical "if I start now" reasoning — a dose and an interval
 * the user is *considering* rather than ones they already took — which is why two
 * screens exist rather than one with a toggle.
 *
 * ## Recompute, not recompose
 * Every input and both log lists fold into [InjectionLevelsModel.recomputeKey],
 * and the curve is built in a `LaunchedEffect` keyed on it. `refresh()` is
 * O(samples × doses) over a superposition sum; evaluating it in the body would
 * run it on every unrelated state change, which is precisely what upstream's own
 * header warns about — an annotation the port takes at its word rather than
 * treating a 600-point loop as cheap.
 *
 * [navigator] is unused: this screen pushes nothing, and is reached from the tools
 * hub. It is in the signature because the hub's destination table is uniform.
 */
@Composable
fun InjectionLevelsScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val preferences = remember { DepotPreferences(context) }
    val labStore = remember { LabMeasurementStore(context) }

    var model by remember { mutableStateOf<InjectionLevelsModel?>(null) }
    var catalog by remember { mutableStateOf<SubstanceCatalog?>(null) }
    var esters by remember { mutableStateOf(EsterPKIndex.EMPTY) }
    var entries by remember { mutableStateOf<List<DoseEntryEntity>>(emptyList()) }
    var labs by remember { mutableStateOf<List<LabMeasurement>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var showingAddLab by remember { mutableStateOf(false) }

    // The one load: the catalog, the ester table, the log and the labs.
    LaunchedEffect(Unit) {
        val substanceCatalog = app.catalog()
        val index = EsterPKIndexLoader.load(context)
        catalog = substanceCatalog
        esters = index
        entries = app.database.doseEntryDao().all()
        labs = labStore.all()
        val built = InjectionLevelsModel(preferences, index, substanceCatalog)
        built.adoptStoredPreferences()
        model = built
        loaded = true
    }

    // Read the log and the labs into the model, then default the ester and the
    // source. Keyed on the analyte too, because the vial strength is per-analyte
    // and the ester list belongs to the hormone — upstream's
    // `.onChange(of: model.analyte)` branch.
    LaunchedEffect(model, model?.analyte, entries, labs) {
        model?.syncFromLog(entries, labs)
    }

    // The whole point of the key: one recompute per real change, never in a body.
    LaunchedEffect(model?.recomputeKey) {
        model?.refresh()
    }

    val current = model

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = FAB_CLEARANCE),
    ) {
        if (loaded && (current == null || current.availableEsters.isEmpty())) {
            item { NoDataCard() }
            return@LazyColumn
        }
        if (current == null) return@LazyColumn

        item { InputSection(current) }

        val result = current.result
        val ester = current.selectedEster
        if (result != null && ester != null) {
            item {
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        DepotCurveChart(
                            result = result,
                            analyte = current.analyte,
                            referenceLow = current.referenceLow,
                            referenceHigh = current.referenceHigh,
                            chartRange = current.chartRange,
                            onChartRangeChange = {
                                // Tapping a preset drops any pinch override, which
                                // is what makes the menu the way back out of a
                                // zoom rather than a second, competing state.
                                current.chartRange = it
                                current.pinchVisibleDays = null
                            },
                            onPinch = { current.pinchVisibleDays = it },
                        )
                    }
                }
            }
            item { DepotMetricsCard(result, current.analyte) }
            item {
                LabCalibrationSection(
                    model = current,
                    labs = labs.filter { it.analyteKey == current.analyte.key },
                    onAdd = { showingAddLab = true },
                    onToggleExclude = { lab ->
                        labStore.setExcluded(lab.id, !lab.excludedFromCalibration)
                        labs = labStore.all()
                    },
                    onDelete = { lab ->
                        labStore.delete(lab.id)
                        labs = labStore.all()
                    },
                )
            }
            item { ProvenanceCard(ester) }
        }

        item { ExplanationCard() }
    }

    if (showingAddLab && current != null) {
        AddLabResultDialog(
            analyte = current.analyte,
            esterID = current.selectedEsterID,
            onSave = { measurement ->
                labStore.insert(measurement)
                labs = labStore.all()
                showingAddLab = false
            },
            onDismiss = { showingAddLab = false },
        )
    }
}

// MARK: - Input

/**
 * The hormone, ester, vial strength and schedule inputs.
 *
 * Ported from `InjectionLevelsInputSection`. The order is load-bearing: the ester
 * picker is scoped by the hormone above it, and the schedule fields only appear
 * when the log is not already supplying the injections.
 */
@Composable
private fun InputSection(model: InjectionLevelsModel) {
    DepotSectionCard(title = "Curve inputs") {
        val analytes = model.availableAnalytes
        if (analytes.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldLabel("Hormone")
                SegmentedRow(
                    options = analytes,
                    selected = model.analyte,
                    label = { it.displayName },
                    onSelect = { model.analyte = it },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FieldLabel("Ester")
            SegmentedRow(
                options = model.availableEsters,
                selected = model.selectedEster,
                label = { it.label },
                onSelect = { model.selectedEsterID = it.esterID },
            )
        }

        if (model.volumeLoggedCount > 0) {
            NumberField(
                label = "Vial concentration",
                value = model.volumeConcentrationMgPerML,
                unit = "mg/mL",
                onValueChange = { model.persistVolumeConcentration(it) },
            )
            Caption(
                if ((model.volumeConcentrationMgPerML ?: 0.0) > 0) {
                    "${model.volumeLoggedCount} mL injections converted at this strength"
                } else {
                    "${model.volumeLoggedCount} injections are in mL. Enter the vial strength to include them."
                },
            )
        }

        if (model.hasLogHistory) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldLabel("Source")
                SegmentedRow(
                    options = listOf(true, false),
                    selected = model.useLogHistory,
                    label = { if (it) "From your log" else "Manual schedule" },
                    onSelect = { model.useLogHistory = it },
                )
            }
        }

        if (model.useLogHistory && model.hasLogHistory) {
            Caption("${model.injectionCount} injections from your log")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(
                    label = "Dose each time",
                    value = model.doseMg,
                    unit = "mg",
                    onValueChange = { model.doseMg = it },
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    label = "Every",
                    value = model.intervalDays,
                    unit = "days",
                    onValueChange = { model.intervalDays = it },
                    modifier = Modifier.weight(1f),
                )
            }
            if (model.hasLogHistory) {
                ToggleRow(
                    label = "Start from your log",
                    checked = model.startFromLog,
                    onCheckedChange = { model.startFromLog = it },
                )
            }
            if (model.continuesFromLog) {
                Caption("Starts at today's level from your log. Next dose one interval after your last.")
            } else {
                NumberField(
                    label = "Starting level",
                    value = model.startingLevel,
                    unit = model.analyte.canonicalUnit,
                    onValueChange = { model.startingLevel = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Caption("The level in your body today, if any. First dose today.")
            }
        }
    }
}

// MARK: - Lab calibration

/**
 * The user's lab results and the two calibration controls.
 *
 * Ported from `LabCalibrationSection`. The chip counts what is *included* in the
 * fit rather than what exists, so unticking a draw visibly widens the band — the
 * one piece of feedback that makes the exclusion meaningful.
 */
@Composable
private fun LabCalibrationSection(
    model: InjectionLevelsModel,
    labs: List<LabMeasurement>,
    onAdd: () -> Unit,
    onToggleExclude: (LabMeasurement) -> Unit,
    onDelete: (LabMeasurement) -> Unit,
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
            onAutoCalibrateChange = { model.persistAutoCalibrate(it) },
            onFitRatesChange = { model.persistFitRates(it) },
            onMultiplierChange = { model.persistMultiplier(it) },
        )

        ReferenceLinesEditor(
            referenceLow = model.referenceLow,
            referenceHigh = model.referenceHigh,
            unit = model.analyte.canonicalUnit,
            onLowChange = { model.referenceLow = it },
            onHighChange = { model.referenceHigh = it },
        )
    }
}

// MARK: - Provenance

/**
 * Where the numbers come from, and how far to trust them.
 *
 * Ported from `InjectionLevelsProvenanceCard`. The two links are the original's
 * own and are kept because the parameter set here is derived from the first of
 * them: a reader is entitled to the derivation rather than to a badge that says
 * "high confidence" and stops.
 */
@Composable
private fun ProvenanceCard(ester: EsterPKRecord) {
    val colors = PiruTheme.colors
    val uriHandler = LocalUriHandler.current
    DepotSectionCard(title = "Sources", trailing = { ConfidenceBadge(ester.confidence) }) {
        Caption(ester.provenance)
        Caption(
            "Older studies used radioimmunoassay; modern LC-MS/MS reads lower. " +
                "Calibrating to your own results absorbs the difference.",
        )
        Caption(
            "Subcutaneous and intramuscular reach similar levels (196 vs 190 pg/mL " +
                "head-to-head), so one curve serves both (Herndon 2023; Misakian 2025).",
        )
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
}

/** The ester curve's confidence tier, as a chip. */
@Composable
internal fun ConfidenceBadge(confidence: String) {
    val colors = PiruTheme.colors
    val label = when (confidence) {
        "high" -> "High confidence"
        "medium" -> "Medium confidence"
        else -> "Low confidence"
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = colors.accent,
        modifier = Modifier
            .background(colors.accent.copy(alpha = 0.10f), MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

// MARK: - Explanation and empty state

/**
 * What the curve is and is not.
 *
 * Ported from `InjectionLevelsExplanationCard`, verbatim. The middle paragraph is
 * the one that matters: a fit to a handful of lab draws demonstrates agreement
 * with those draws and says nothing about the days between them.
 */
@Composable
internal fun ExplanationCard() {
    DepotSectionCard(title = "About this curve") {
        Caption(
            "An injected ester releases slowly from the oil depot, splits into the free " +
                "hormone, and clears. The curve models that from your doses.",
        )
        Caption(
            "It estimates a level. It never suggests a dose or a target. Your lab results " +
                "fit the model to your measurements, which doesn't establish accuracy between them.",
        )
        Caption(
            "Levels vary a lot between people, so an uncalibrated curve is a starting point, " +
                "not a reading. One blood test fits the height. Two on different days fit the " +
                "shape too. Retest after any change in dose, ester, interval, or site.",
        )
        Caption("Predicted from a model, not measured. Not medical advice.")
    }
}

/**
 * The empty state, which on this build means the catalog shipped no ester rows.
 *
 * Upstream renders it when `availableEsters` is empty for the chosen hormone. The
 * alternative — an empty chart with axes — reads as a broken screen rather than
 * as a build that carries no ester data.
 */
@Composable
private fun NoDataCard() {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("No injectable ester data in this build.", style = MaterialTheme.typography.bodyMedium)
            Caption(
                "The ester table ships with the substance catalog, and this one has no rows " +
                    "for estradiol or testosterone.",
            )
        }
    }
}
