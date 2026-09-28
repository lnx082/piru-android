package glass.kagerou.piru.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlinx.coroutines.launch

/**
 * One logged dose, read and edited.
 *
 * Ported from `Journal/EntryDetailView` and its read/edit halves.
 *
 * ## The edit is a draft, not a live binding
 * Nothing reaches the store until Save, so an abandoned edit changes nothing —
 * the same posture the quick log's staging takes, and for the same reason: the
 * user is mid-thought, and a keystroke that has already been written is one they
 * cannot take back.
 *
 * ## An unknown amount is a first-class state, not a validation failure
 * Emptying the amount field sets the unknown flag rather than blocking the save.
 * A dose is a record of something that happened; "some of it" is an answer, and a
 * required field would throw it away. The screen says so rather than leaving the
 * user to guess why the button is still enabled.
 */
@Composable
fun EntryDetailScreen(
    timestampEpochMillis: Long,
    idOrNull: String?,
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }

    var entry by remember { mutableStateOf<DoseEntryEntity?>(null) }
    var loaded by remember { mutableStateOf(false) }

    // Amount, unit, route, time and note as *text*, so a half-typed number is a
    // draft rather than a parse failure.
    var amountText by remember { mutableStateOf("") }
    var unitText by remember { mutableStateOf("mg") }
    var route by remember { mutableStateOf(RouteOfAdministration.ORAL) }
    var substanceText by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }
    var isUnknownAmount by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    fun seed(row: DoseEntryEntity) {
        entry = row
        substanceText = row.substance
        amountText = if (row.isUnknownDose) "" else trimNumber(row.amount)
        unitText = row.unit
        route = row.route
        noteText = row.notes.orEmpty()
        isUnknownAmount = row.isUnknownDose
    }

    LaunchedEffect(timestampEpochMillis, idOrNull) {
        // Resolve by id first, then fall back to the timestamp. A route can arrive
        // from an older payload or a deep link with no id at all, and the id is
        // what survives an edit to the time — so it is tried first and the
        // timestamp is the safety net, not the other way round.
        val byId = idOrNull?.let { raw ->
            runCatching { java.util.UUID.fromString(raw) }.getOrNull()?.let { app.database.doseEntryDao().byId(it) }
        }
        val row = byId ?: app.database.doseEntryDao()
            .inRange(Date(timestampEpochMillis - 2_000), Date(timestampEpochMillis + 2_000))
            .minByOrNull { kotlin.math.abs(it.timestamp.time - timestampEpochMillis) }
        row?.let { seed(it) }
        loaded = true
    }

    val current = entry
    when {
        !loaded -> Centered("Loading…")
        current == null -> Centered("That dose is gone.")
        else -> Column(
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (editing) "Edit dose" else current.substance,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    current.timestamp.toInstant().atZone(zone)
                        .format(DateTimeFormatter.ofPattern("EEEE d MMMM, HH:mm")),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
                current.sessionId?.let { sessionId ->
                    TextButton(onClick = { navigator.push(PushRoute.Session(sessionId.toString())) }) {
                        Text("Part of a session")
                    }
                }
            }

            if (editing) {
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = substanceText,
                            onValueChange = { substanceText = it },
                            label = { Text("Substance") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = amountText,
                                onValueChange = {
                                    amountText = it
                                    // Typing a number takes the dose back out of the
                                    // unknown state — the flag follows the field,
                                    // rather than the two being able to disagree.
                                    if (it.isNotBlank()) isUnknownAmount = false
                                },
                                label = { Text("Amount") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = unitText,
                                onValueChange = { unitText = it },
                                label = { Text("Unit") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = isUnknownAmount,
                                onClick = {
                                    isUnknownAmount = !isUnknownAmount
                                    if (isUnknownAmount) amountText = ""
                                },
                                label = { Text("Amount unknown") },
                            )
                        }
                        for (candidate in routeChoices) {
                            FilterChip(
                                selected = route == candidate,
                                onClick = { route = candidate },
                                label = { Text(candidate.displayName) },
                            )
                        }
                        OutlinedTextField(
                            value = noteText,
                            onValueChange = { noteText = it },
                            label = { Text("Note") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { seed(current); editing = false }) { Text("Cancel") }
                    Button(
                        onClick = {
                            val parsed = amountText.trim().toDoubleOrNull()
                            val unknown = isUnknownAmount || parsed == null
                            scope.launch {
                                app.database.doseEntryDao().update(
                                    current.copy(
                                        substance = substanceText.trim().ifEmpty { current.substance },
                                        // An unparseable field is the unknown state, not
                                        // a silent zero: the flag and the number say the
                                        // same thing.
                                        amount = if (unknown) 0.0 else parsed!!,
                                        unit = unitText.trim().ifEmpty { "mg" },
                                        route = route,
                                        notes = noteText.trim().ifEmpty { null },
                                        isUnknownDose = unknown,
                                    ),
                                )
                                // The grouping is a reading of the log, so an edit that
                                // changes what the log says can change the session. The
                                // user's own merges are untouched — the sweep only
                                // looks at session-less doses, and this one has a home.
                                //
                                // The occurrence record is the same kind of reading and is
                                // re-derived for the same reason: a dose retimed onto
                                // another slot's hour, or relabelled onto another
                                // substance, satisfies a different slot than it did.
                                app.reconcileRoutineOccurrences()
                                editing = false
                                saved = true
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Save") }
                }
            } else {
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReadRow("Amount", if (current.isUnknownDose) "Unknown" else "${trimNumber(current.amount)} ${current.unit}")
                        ReadRow("Route", current.route.displayName)
                        current.saltForm?.let { ReadRow("Salt", it) }
                        current.isomer?.let { ReadRow("Isomer", it) }
                        current.releaseForm?.takeIf { it.isNotBlank() }?.let { ReadRow("Release", it) }
                        current.productName?.let { ReadRow("Product", it) }
                    }
                }

                if (!current.notes.isNullOrBlank()) {
                    PiruCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Note", style = MaterialTheme.typography.titleSmall)
                            Text(current.notes!!, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                if (current.isUnknownDose) {
                    PiruCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "This dose was logged without a number. That is a real answer rather " +
                                "than a gap — it is left out of the curves and the body-load " +
                                "estimate, and kept in the record.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PiruTheme.colors.secondaryLabel,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            // Clearing the confirmation on the way back in: it said
                            // "saved" about a *previous* edit, and leaving it up
                            // during a new one reads as if this edit were already
                            // committed.
                            saved = false
                            editing = true
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Edit") }
                    TextButton(onClick = {
                        scope.launch {
                            app.database.doseEntryDao().deleteByRowId(current.rowId)
                            // The session it was in may now be empty or have a
                            // different span, so its bounds are re-derived.
                            current.sessionId?.let { app.database.sessionDao().refreshDoseBounds(it) }
                            // A dose that is gone no longer satisfies its slot, so the
                            // occurrence goes back to pending rather than leaving the
                            // re-ask suppressed by a dose the user deleted.
                            app.reconcileRoutineOccurrences()
                            navigator.invalidate()
                            navigator.pop()
                        }
                    }) { Text("Delete") }
                }
            }

            if (saved) {
                Text(
                    "Saved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.successText,
                )
            }

            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = FAB_CLEARANCE))
        }
    }
}

/** The routes worth offering as chips. The full eleven live in the quick log's picker. */
private val routeChoices = listOf(
    RouteOfAdministration.ORAL,
    RouteOfAdministration.SUBLINGUAL,
    RouteOfAdministration.INSUFFLATION,
    RouteOfAdministration.INHALATION,
    RouteOfAdministration.INTRAVENOUS,
    RouteOfAdministration.INTRAMUSCULAR,
    RouteOfAdministration.SUBCUTANEOUS,
    RouteOfAdministration.RECTAL,
)

@Composable
private fun ReadRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = PiruTheme.colors.secondaryLabel)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Centered(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = PiruTheme.colors.secondaryLabel)
    }
}

/**
 * A dose, trimmed.
 *
 * Integral amounts lose the decimal point — "100 mg", not "100.0 mg" — which is
 * how the user typed it and how every source writes it.
 */
private fun trimNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
