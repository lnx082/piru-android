package glass.kagerou.piru.ui.meds

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.JsonLists
import glass.kagerou.piru.data.entity.DailyDoseItemEntity
import glass.kagerou.piru.model.DoseFrequency
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.doseFormatted
import glass.kagerou.piru.substance.DbSubstanceCatalog
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.labels.CoreLabels
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The per-med detail screen, pushed from the My Meds hub.
 *
 * Ported from `Piru/Views/Journal/DailyDose/MedDetailView.swift` (417 lines).
 *
 * ## Everything is editable in place
 * Upstream binds straight to the `@Model`, so there is no separate Edit modal —
 * dose, schedule, times, reminders and the quiet tier all edit where they sit.
 * The substance itself is fixed once created (to change it, delete and re-add),
 * so its identity — and the "done today" join — never drifts out from under
 * logged doses.
 *
 * ## One divergence the storage forces
 * SwiftData makes every keystroke a write. Room does not, and
 * `DailyDoseItemDao` has no update statement, so this screen holds a working
 * copy and commits it with the header's check — the same commit shape
 * [MedFormScreen] has, and for the same reason. The *screen* is unchanged:
 * there is still no second modal to open, and the check is the only place a
 * change becomes a fact.
 *
 * The reminder resync upstream does in `onDisappear` lives in [MedsStore.save]
 * instead, which is strictly better here: `onDisappear` does not run on every
 * way this screen can leave the composition.
 */
@Composable
fun MedDetailScreen(
    itemRowId: Long,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val zone = remember { ZoneId.systemDefault() }
    val scope = rememberCoroutineScope()

    var item by remember { mutableStateOf<DailyDoseItemEntity?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf<DbSubstanceCatalog?>(null) }
    var expandedTimeId by remember { mutableStateOf<Long?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    /**
     * The amount field's own text.
     *
     * Held apart from the row because a field bound to `doseFormatted(amount)`
     * reformats on every keystroke: `doseFormatted` renders 10–99 with one
     * decimal, so typing "12.55" would parse 12.5, re-render "12.5", and swallow
     * the second 5. The row is the source of truth for everything else; it is
     * not the source of truth for a half-typed number.
     */
    var amountText by remember { mutableStateOf("") }

    LaunchedEffect(itemRowId) {
        catalog = withContext(Dispatchers.Default) { app.catalog() }
        val row = app.database.dailyDoseItemDao().byRowId(itemRowId)
        item = row
        amountText = row?.let { doseFormatted(it.amount) }.orEmpty()
        loaded = true
    }

    val current = item
    if (!loaded) return
    if (current == null) {
        // Deleted from under us — the route's target is gone, so there is
        // nothing to edit and nothing to say about it.
        LaunchedEffect(Unit) { onDismissed() }
        return
    }

    val mutate: ((DailyDoseItemEntity) -> DailyDoseItemEntity) -> Unit = { transform ->
        item = item?.let(transform)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MedsGlyph(
                kind = MedsGlyphKind.CHEVRON_RIGHT,
                tint = PiruTheme.colors.secondaryLabel,
                size = 18.dp,
                modifier = Modifier
                    .clickable(onClick = onDismissed)
                    .padding(6.dp),
            )
            Text(
                displayName(current),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            MedsGlyph(
                kind = MedsGlyphKind.CHECK,
                tint = if (saving) PiruTheme.colors.tertiaryLabel else PiruTheme.colors.accent,
                size = 20.dp,
                modifier = Modifier
                    .clickable(enabled = !saving) {
                        saving = true
                        scope.launch {
                            MedsStore.save(app, current, current)
                            saving = false
                            onDismissed()
                        }
                    }
                    .padding(6.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = FAB_CLEARANCE),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The med's own header: what it is, and how it is scheduled.
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MedAvatar(size = 44.dp)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(displayName(current), style = MaterialTheme.typography.titleSmall)
                        Text(scheduleSummary(current), style = captionSecondaryStyle)
                    }
                }
            }

            FormSection(stringResource(R.string.meds_section_dosage)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { text ->
                            amountText = text
                            // A half-typed "1." is kept in the field and simply
                            // not yet a number — the row keeps its last good
                            // amount until the text parses.
                            text.trim().toDoubleOrNull()?.let { parsed ->
                                mutate { it.copy(amount = parsed) }
                            }
                        },
                        label = { Text(stringResource(R.string.common_amount)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    UnitPicker(
                        units = unitOptions(current, catalog),
                        selected = current.unit,
                        onSelect = { unit -> mutate { it.copy(unit = unit) } },
                    )
                }
                RoutePicker(
                    routes = availableRoutes(current, catalog),
                    selected = current.route,
                    onSelect = { route ->
                        val sub = catalog?.lookup(current.substance)
                        mutate { it.copy(route = route, unit = sub?.unit(route) ?: it.unit) }
                    },
                )
                FormFooter(stringResource(R.string.meds_footer_checked_off))
            }

            FormSection(stringResource(R.string.meds_section_schedule)) {
                SchedulePicker(
                    schedule = if (current.isAsNeeded) {
                        FormSchedule.AsNeeded
                    } else {
                        FormSchedule.Frequency(current.frequency)
                    },
                    onSelect = { choice ->
                        mutate {
                            when (choice) {
                                is FormSchedule.AsNeeded -> it.copy(isAsNeeded = true)
                                is FormSchedule.Frequency -> it.copy(
                                    isAsNeeded = false,
                                    frequencyRaw = choice.value.wireValue,
                                    // A concrete start date only matters for the
                                    // offset cadences; default it forward the
                                    // first time one is picked.
                                    startDate = if (choice.value != DoseFrequency.DAILY &&
                                        choice.value != DoseFrequency.SPECIFIC_DAYS &&
                                        it.startDate.toInstant().toEpochMilli() <= 0
                                    ) {
                                        Date.from(Instant.now())
                                    } else {
                                        it.startDate
                                    },
                                )
                            }
                        }
                    },
                )

                when {
                    current.isAsNeeded -> IntegerStepper(
                        value = current.maxPerDay ?: 0,
                        range = 0..12,
                        label = current.maxPerDay?.let {
                            stringResource(R.string.meds_up_to_daily_limit, it)
                        } ?: stringResource(R.string.meds_no_daily_limit),
                        onChange = { limit -> mutate { it.copy(maxPerDay = if (limit == 0) null else limit) } },
                    )

                    current.frequency == DoseFrequency.SPECIFIC_DAYS -> WeekdayPicker(
                        selected = current.frequencyDays.toSet(),
                        onToggle = { day ->
                            mutate {
                                val days = it.frequencyDays.toMutableSet()
                                if (!days.add(day)) days.remove(day)
                                it.copy(frequencyDaysJson = JsonLists.encode(days.sorted()))
                            }
                        },
                    )

                    current.frequency != DoseFrequency.DAILY -> DateOffsetPicker(
                        startDate = current.startDate.toInstant(),
                        onShift = { days ->
                            mutate {
                                it.copy(
                                    startDate = Date.from(
                                        it.startDate.toInstant().plusSeconds(days * 86_400L),
                                    ),
                                )
                            }
                        },
                        zone = zone,
                    )
                }

                if (current.isAsNeeded) {
                    FormFooter(stringResource(R.string.meds_footer_never_missed))
                } else if (current.frequency == DoseFrequency.SPECIFIC_DAYS && current.frequencyDays.isEmpty()) {
                    FormFooter(stringResource(R.string.meds_footer_select_a_day))
                }
            }

            if (!current.isAsNeeded) {
                FormSection(stringResource(R.string.meds_section_times)) {
                    val times = current.reminderTimesMinutes
                    for (minutes in times) {
                        ReminderTimeRow(
                            // The row id is the slot's own minute value, which is
                            // stable across a re-render and unique inside one item
                            // — the identity upstream gets from an index-free key.
                            time = ReminderTime(minutes.toLong(), minutes),
                            zone = zone,
                            expanded = expandedTimeId == minutes.toLong(),
                            onToggleExpanded = {
                                expandedTimeId = if (expandedTimeId == minutes.toLong()) null else minutes.toLong()
                            },
                            onPick = { picked ->
                                val next = times.map { if (it == minutes) picked else it }
                                expandedTimeId = null
                                mutate {
                                    it.copy(
                                        reminderTimesJson = JsonLists.encode(
                                            next.toSortedSet().toList(),
                                        ),
                                    )
                                }
                            },
                            onDelete = {
                                mutate {
                                    it.copy(
                                        reminderTimesJson = JsonLists.encode(
                                            times.filterNot { m -> m == minutes },
                                        ),
                                    )
                                }
                            },
                            consequence = MedTimeConsequence.resolve(
                                catalog?.lookup(current.substance),
                                current.route,
                            ),
                        )
                    }
                    AddTimeButton(hasTimes = times.isNotEmpty()) {
                        mutate {
                            it.copy(
                                reminderTimesJson = JsonLists.encode(
                                    (times + suggestedNextTime(times)).toSortedSet().toList(),
                                ),
                            )
                        }
                    }
                    if (times.isEmpty()) {
                        FormFooter(stringResource(R.string.meds_footer_no_set_time))
                    }
                }

                FormSection(stringResource(R.string.meds_section_reminders)) {
                    RemindMeToggle(remind = current.remind) { on ->
                        mutate { it.copy(remind = on) }
                    }
                    if (current.remind && timesOf(current).isNotEmpty()) {
                        AskAgainPicker(
                            selected = AskAgainChoice.from(current.askAgainOverrideMinutes),
                            onSelect = { choice ->
                                mutate {
                                    it.copy(askAgainOverrideJson = choice.serialized)
                                }
                            },
                        )
                    }
                    FormFooter(stringResource(R.string.meds_ask_again_footer))
                }
            }

            QuietMedSection(isQuiet = current.isQuiet) { quiet ->
                // Quiet meds are also background meds: they fold into an active
                // session rather than opening one, so the two flags move together.
                mutate { it.copy(isQuiet = quiet, isBackgroundMed = quiet) }
            }

            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { confirmingDelete = true }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        stringResource(R.string.meds_delete_med),
                        style = MaterialTheme.typography.titleSmall,
                        color = PiruTheme.colors.danger,
                    )
                }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.meds_delete_med_question)) },
            text = { Text(stringResource(R.string.meds_delete_med_consequence)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        scope.launch {
                            MedsStore.delete(app, current)
                            onDismissed()
                        }
                    },
                ) {
                    Text(stringResource(R.string.meds_delete_med), color = PiruTheme.colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** The times a row is carrying, as a plain list — a helper the reminders section reads twice. */
private fun timesOf(item: DailyDoseItemEntity): List<Int> = item.reminderTimesMinutes

// MARK: - Ask Again

/**
 * The Ask Again override choices. `nil` = follow the global default; `[]` =
 * opted out.
 *
 * Ported from `MedDetailView.AskAgainChoice`. The declaration order is
 * upstream's `CaseIterable` order and cannot be reordered — Kotlin enums take
 * their `compareTo` from it.
 */
private enum class AskAgainChoice(@androidx.annotation.StringRes val labelRes: Int) {
    GLOBAL_DEFAULT(R.string.meds_ask_again_default),
    OFF(R.string.meds_ask_again_off),
    TEN(R.string.meds_ask_again_ten),
    TEN_THIRTY(R.string.meds_ask_again_ten_thirty),
    ;

    /** The stored override, or the [serialized] form of it. */
    val override: List<Int>?
        get() = when (this) {
            GLOBAL_DEFAULT -> null
            OFF -> emptyList()
            TEN -> listOf(10)
            TEN_THIRTY -> listOf(10, 30)
        }

    /**
     * The column's own encoding.
     *
     * The null-versus-empty distinction is the whole meaning of
     * `ask_again_override_json`: null follows the global default, an empty list
     * has opted out. `JsonLists.encode` writes `""` for an empty list, which
     * reads back as an empty list rather than as null — exactly the distinction
     * the column needs.
     */
    val serialized: String?
        get() = override?.let { JsonLists.encode(it) }

    companion object {
        fun from(override: List<Int>?): AskAgainChoice = when {
            override == null -> GLOBAL_DEFAULT
            override.isEmpty() -> OFF
            override == listOf(10) -> TEN
            override == listOf(10, 30) -> TEN_THIRTY
            else -> GLOBAL_DEFAULT
        }
    }
}

@Composable
private fun AskAgainPicker(selected: AskAgainChoice, onSelect: (AskAgainChoice) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.meds_ask_again_label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { expanded = true }) { Text(stringResource(selected.labelRes)) }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            for (choice in AskAgainChoice.entries) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(choice.labelRes)) },
                    onClick = {
                        onSelect(choice)
                        expanded = false
                    },
                )
            }
        }
    }
}

// MARK: - Reading the catalog

/** The substance's catalog entry, when its name resolves to the canonical one. */
private fun resolvedSubstance(item: DailyDoseItemEntity, catalog: DbSubstanceCatalog?) =
    catalog?.lookup(item.substance)?.takeIf { it.name.equals(item.substance, ignoreCase = true) }

private fun availableRoutes(
    item: DailyDoseItemEntity,
    catalog: DbSubstanceCatalog?,
): List<RouteOfAdministration> =
    resolvedSubstance(item, catalog)?.orderedRoutes ?: RouteOfAdministration.entries.toList()

private fun unitOptions(item: DailyDoseItemEntity, catalog: DbSubstanceCatalog?): List<String> {
    val defaults = listOf("mg", "g", "µg", "mL", "IU", "drops", "puffs")
    val sub = resolvedSubstance(item, catalog) ?: return defaults
    val preferred = sub.unit(item.route)
    return listOf(preferred) + (sub.routes.map { it.unit } + defaults).distinct().filter { it != preferred }
}

/**
 * The header's one-line schedule.
 *
 * A multi-time med reads as "2× daily" rather than listing its times: the times
 * are the section below it, and the header's job is the cadence.
 */
@Composable
private fun scheduleSummary(item: DailyDoseItemEntity): String {
    val dose = stringResource(
        R.string.meds_schedule_summary_dose_route,
        "${doseFormatted(item.amount)} ${item.unit}",
        CoreLabels.route(item.route),
    )
    if (item.isAsNeeded) {
        val limit = item.maxPerDay
        return if (limit != null) {
            stringResource(R.string.meds_row_subtitle_up_to_daily, dose, limit)
        } else {
            stringResource(R.string.meds_row_subtitle_as_needed, dose)
        }
    }
    val count = item.reminderTimesMinutes.size
    if (count > 1) return stringResource(R.string.meds_row_subtitle_daily_count, dose, count)
    return stringResource(R.string.meds_row_subtitle_times, dose, frequencyShortLabel(item.frequency))
}
