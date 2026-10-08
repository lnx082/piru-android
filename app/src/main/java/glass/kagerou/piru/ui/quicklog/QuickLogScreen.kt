package glass.kagerou.piru.ui.quicklog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.labels.CoreLabels
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import glass.kagerou.piru.substance.SubstanceMatch

/**
 * One dose, staged and not yet committed.
 *
 * A staged dose is deliberately **not** a `DoseEntryEntity`: nothing the user is
 * still typing should be able to reach the store, and a row that exists is a row
 * the tolerance replay will read.
 */
data class StagedDose(
    val substance: String,
    val amountText: String,
    val unit: String,
    val route: RouteOfAdministration,
    val atMinutesAgo: Int,
    /**
     * Whether the amount is an estimate rather than a measurement.
     *
     * Read from the staged dose and written to `DoseEntryEntity.isApproximate`, which the journal
     * draws as a `~` before the figure. Nothing set that flag before this — the entity, the
     * export and the row's rendering all supported it while no screen could produce one, so an
     * estimated dose was stored indistinguishably from a measured one.
     */
    val isApproximate: Boolean = false,
) {
    val amount: Double? get() = amountText.trim().toDoubleOrNull()

    /** A dose is committable when it names something. An amount of zero is allowed — see the commit path. */
    val isCommittable: Boolean get() = substance.isNotBlank()
}

/**
 * Quick log: stage one or more doses, then commit them together.
 *
 * Ported from `QuickLog/` — the dock, the tray, their models and their sections,
 * about 8,000 lines upstream. This is the **tray**: search, stage, adjust, commit.
 * What is not here: the dock's collapsed presentation and its drag gesture, the
 * by-volume and by-drink input modes, the pill-strength picker, product-name
 * capture, and the tag editor. The staging model and the commit are the parts
 * everything else hangs off, which is why they came first.
 *
 * ## Why a sheet, and why it stages
 * Upstream makes this a modal because the user is mid-something else — the dose
 * is a side errand, and abandoning it must not unwind a navigation stack. It
 * **stages** rather than committing each dose because the common case is logging
 * several at once after the fact, and a store write per keystroke would make the
 * tolerance replay re-run for every one of them.
 *
 * ## An unknown amount is a first-class answer
 * Emptying the amount field does not fail validation; it logs a dose with no
 * number, which the journal then renders as `?`. A dose is a record of something
 * that happened, and "some of it" is a real answer that a required field would
 * throw away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickLogSheet(
    onDismiss: () -> Unit,
    onCommitted: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val staged = remember { mutableStateListOf<StagedDose>() }
    var query by remember { mutableStateOf("") }
    // The sheet is *called* quick log, and on a phone it opens over the journal with the
    // user's attention already on the thing they came to type. Leaving the field unfocused
    // meant the keyboard never appeared and the first characters went nowhere, which reads
    // as "the substance field is empty" rather than as "nothing is listening".
    val substanceFocus = remember { FocusRequester() }
    var results by remember { mutableStateOf<List<SubstanceMatch<Substance>>>(emptyList()) }
    var committing by remember { mutableStateOf(false) }
    var catalogReady by remember { mutableStateOf(false) }

    val catalog = remember { mutableStateOf<glass.kagerou.piru.substance.DbSubstanceCatalog?>(null) }
    LaunchedEffect(Unit) {
        catalog.value = withContext(Dispatchers.Default) { app.catalog() }
        catalogReady = true
    }

    LaunchedEffect(catalogReady) {
        if (!catalogReady) return@LaunchedEffect
        // `enabled = catalogReady` below means a request made before this runs would be
        // made against a disabled field and silently dropped, so the request waits for the
        // same flag the field does. The `runCatching` is because a sheet dismissed in the
        // same frame as the catalogue arriving has no node to focus any more, and that is a
        // race to survive rather than an error to report.
        runCatching { substanceFocus.requestFocus() }
    }

    LaunchedEffect(query) {
        val c = catalog.value ?: return@LaunchedEffect
        results = withContext(Dispatchers.Default) {
            if (query.isBlank()) emptyList() else c.search(query, limit = 20)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.shell_quicklog_title), style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.shell_quicklog_substance_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(substanceFocus),
                enabled = catalogReady,
            )

            if (query.isNotBlank() && results.isEmpty() && catalogReady) {
                Text(
                    stringResource(R.string.shell_quicklog_no_match, query),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }

            for (match in results) {
                val substance = match.substance
                PiruCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        staged.add(
                            0,
                            StagedDose(
                                substance = substance.name,
                                amountText = "",
                                unit = substance.defaultUnit,
                                route = substance.defaultRoute,
                                atMinutesAgo = 0,
                            ),
                        )
                        query = ""
                        results = emptyList()
                    },
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(substance.displayTitle, style = MaterialTheme.typography.titleSmall)
                        Text(
                            // The alias first when the query named one: it is what the user
                            // typed, and for a brand name it is also the product the dose is
                            // being logged as. Then both halves of the reader-facing label —
                            // not the wire value, because a result reading "stimulant · oral"
                            // in the Chinese build is a result half in the wrong language.
                            listOfNotNull(
                                match.matchedAlias,
                                CoreLabels.category(substance.category) +
                                    " · " + CoreLabels.route(substance.defaultRoute),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                }
            }

            if (query.isNotBlank() && results.isEmpty() && catalogReady) {
                Button(
                    onClick = {
                        staged.add(
                            0,
                            StagedDose(query.trim(), "", "mg", RouteOfAdministration.ORAL, 0),
                        )
                        query = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.shell_quicklog_stage_anyway, query.trim())) }
            }

            if (staged.isNotEmpty()) {
                Text(
                    stringResource(R.string.shell_quicklog_staged, staged.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = PiruTheme.colors.secondaryLabel,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(staged.size, key = { staged[it].substance + it }) { index ->
                        StagedDoseEditor(
                            dose = staged[index],
                            onChange = { staged[index] = it },
                            onRemove = { staged.removeAt(index) },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.shell_cancel)) }
                    // The committable set is computed once and used for both the
                    // count and the write. Counting `staged` while committing the
                    // filtered list said "Log 3" and logged 2.
                    val committable = staged.filter { it.isCommittable }
                    Button(
                        enabled = committable.isNotEmpty() && !committing,
                        onClick = {
                            committing = true
                            val doses = committable
                            scope.launch {
                                val now = System.currentTimeMillis()
                                // The session is decided at write time, not on render:
                                // the grouping is a reading of the log, and a dose that
                                // had to wait for one would be a dose the app could
                                // refuse to record.
                                val sessionRepository = app.sessionRepository()
                                for (dose in doses) {
                                    val rowId = app.database.doseEntryDao().insert(
                                        DoseEntryEntity(
                                            id = UUID.randomUUID(),
                                            substance = dose.substance,
                                            // An empty field means "unknown", which the
                                            // entity stores as zero plus the flag — the
                                            // same shape the journal reads back as `?`.
                                            amount = dose.amount ?: 0.0,
                                            unit = dose.unit,
                                            route = dose.route,
                                            timestamp = Date(
                                                Instant.ofEpochMilli(now)
                                                    .minusSeconds(dose.atMinutesAgo * 60L)
                                                    .toEpochMilli(),
                                            ),
                                            isUnknownDose = dose.amount == null,
                                            // The user's own "this is a guess", carried into the
                                            // row the journal reads back as `~`.
                                            isApproximate = dose.isApproximate,
                                        ),
                                    )
                                    sessionRepository.assignSession(rowId)
                                }
                                // The occurrence record, re-derived from the doses just
                                // written. This is what stops "still need to log X?" for a
                                // slot the user has now logged, and what re-arms the
                                // reminders that were materialized against the record as it
                                // stood a moment ago — see `PiruApplication`.
                                app.reconcileRoutineOccurrences()
                                // The home-screen widget shows the same slots this write
                                // just settled, so it is refreshed here rather than on a
                                // timer: a checklist that keeps showing a dose as due
                                // after it was logged is the one failure a medication
                                // widget must not have.
                                glass.kagerou.piru.widget.MedWidgetRefresh.afterWrite(context)
                                committing = false
                                onCommitted()
                                onDismiss()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            if (committing) {
                                stringResource(R.string.shell_quicklog_saving)
                            } else {
                                stringResource(R.string.shell_quicklog_log_count, committable.size)
                            },
                        )
                    }
                }
            } else {
                Text(
                    stringResource(R.string.shell_quicklog_staged_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(bottom = 32.dp),
                )
            }
        }
    }
}

@Composable
private fun StagedDoseEditor(
    dose: StagedDose,
    onChange: (StagedDose) -> Unit,
    onRemove: () -> Unit,
) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(dose.substance, style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onRemove) { Text(stringResource(R.string.shell_quicklog_remove)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = dose.amountText,
                    onValueChange = { onChange(dose.copy(amountText = it)) },
                    label = { Text(stringResource(R.string.shell_quicklog_amount)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = dose.unit,
                    onValueChange = { onChange(dose.copy(unit = it)) },
                    label = { Text(stringResource(R.string.shell_quicklog_unit)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (route in listOf(
                    RouteOfAdministration.ORAL,
                    RouteOfAdministration.INSUFFLATION,
                    RouteOfAdministration.INHALATION,
                )) {
                    FilterChip(
                        selected = dose.route == route,
                        onClick = { onChange(dose.copy(route = route)) },
                        label = { Text(CoreLabels.route(route)) },
                    )
                }
            }
            // The estimate marker. Its own row rather than a sixth time chip: it answers a
            // different question from "when", and putting it beside the route chips would read
            // as a route.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = dose.isApproximate,
                    onClick = { onChange(dose.copy(isApproximate = !dose.isApproximate)) },
                    label = { Text(stringResource(R.string.shell_quicklog_approximate)) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (minutes in listOf(0, 15, 30, 60, 120)) {
                    FilterChip(
                        selected = dose.atMinutesAgo == minutes,
                        onClick = { onChange(dose.copy(atMinutesAgo = minutes)) },
                        label = {
                            Text(
                                if (minutes == 0) {
                                    stringResource(R.string.shell_quicklog_now)
                                } else if (minutes < 60) {
                                    stringResource(R.string.shell_quicklog_minutes_ago, minutes)
                                } else {
                                    stringResource(R.string.shell_quicklog_hours_ago, minutes / 60)
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}
