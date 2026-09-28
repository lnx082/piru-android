package glass.kagerou.piru.ui.meds

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.model.DoseFrequency
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.labels.CoreLabels
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * The controls the med form and the med detail screen share.
 *
 * Upstream has no equivalent file: `MedFormView.swift` and `MedDetailView.swift`
 * are two independent screens that happen to carry the same pickers, because
 * SwiftUI's `Picker`, `Stepper` and `DatePicker` are one-liners there. Nothing
 * in Compose is, so the two ports would have been two hundred lines of the same
 * dropdown apiece — and the second copy is where a route picker stops agreeing
 * with the first about what "Oral" is called.
 *
 * ## What stays local to each screen
 * Only the state shape: `MedFormDraft` builds a row from scratch and
 * `MedDetailScreen` starts from one that exists. Every control below takes and
 * returns plain values.
 */

// MARK: - Value types

/**
 * The schedule choice: a recurring cadence, or as-needed (PRN) — a separate axis
 * from `DoseFrequency` in the model (`isAsNeeded`), folded into one picker so
 * the screen asks one question.
 */
internal sealed interface FormSchedule {
    data class Frequency(val value: DoseFrequency) : FormSchedule
    data object AsNeeded : FormSchedule
}

/**
 * One reminder time, carried with a stable identity.
 *
 * Upstream keys these by `UUID` and says why in as many words: an index-keyed
 * `ForEach` crashes when a delete shrinks the array before the framework
 * reconciles its children. Compose's list keys have the same requirement for the
 * same reason, so a monotonic id is carried here rather than the list position.
 */
internal data class ReminderTime(val id: Long, val minutes: Int)

// MARK: - Section chrome

/** A titled card of fields, with the explanatory line drawn under it. */
@Composable
internal fun FormSection(title: String?, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = PiruTheme.colors.secondaryLabel)
        }
        PiruCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { content() }
        }
    }
}

/** The quiet explanatory line under a field — upstream's `Section` footer. */
@Composable
internal fun FormFooter(text: String) {
    Text(text, style = captionSecondaryStyle)
}

// MARK: - Pickers

@Composable
internal fun UnitPicker(units: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = true }) { Text(selected) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (unit in units) {
                DropdownMenuItem(
                    text = { Text(unit) },
                    onClick = {
                        onSelect(unit)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun RoutePicker(
    routes: List<RouteOfAdministration>,
    selected: RouteOfAdministration,
    onSelect: (RouteOfAdministration) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.common_route), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = { expanded = true }) { Text(CoreLabels.route(selected)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (route in routes) {
                DropdownMenuItem(
                    text = { Text(CoreLabels.route(route)) },
                    onClick = {
                        onSelect(route)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun SchedulePicker(schedule: FormSchedule, onSelect: (FormSchedule) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (schedule) {
        is FormSchedule.AsNeeded -> stringResource(R.string.meds_time_group_as_needed)
        is FormSchedule.Frequency -> frequencyLongLabel(schedule.value)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.meds_section_schedule), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (frequency in DoseFrequency.entries) {
                DropdownMenuItem(
                    text = { Text(frequencyLongLabel(frequency)) },
                    onClick = {
                        onSelect(FormSchedule.Frequency(frequency))
                        expanded = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.meds_time_group_as_needed)) },
                onClick = {
                    onSelect(FormSchedule.AsNeeded)
                    expanded = false
                },
            )
        }
    }
}

/** The full cadence name, as the picker spells it. */
@Composable
@ReadOnlyComposable
internal fun frequencyLongLabel(frequency: DoseFrequency): String = stringResource(
    when (frequency) {
        DoseFrequency.DAILY -> R.string.common_frequency_daily
        DoseFrequency.EVERY_OTHER_DAY -> R.string.meds_frequency_long_every_other_day
        DoseFrequency.WEEKLY -> R.string.common_frequency_weekly
        DoseFrequency.BIWEEKLY -> R.string.meds_frequency_long_every_two_weeks
        DoseFrequency.MONTHLY -> R.string.common_frequency_monthly
        DoseFrequency.SPECIFIC_DAYS -> R.string.meds_frequency_long_specific_days
    },
)

// MARK: - Steppers

/**
 * A stepper over an integer, where 0 renders as the "no limit entered" line.
 *
 * `InventoryStepperRow` is the app's amount stepper, but it is a `Double`
 * control with a unit column and a step basis; this is a bounded zero-to-twelve
 * count, so it stays local rather than being bent into that shape.
 */
@Composable
internal fun IntegerStepper(
    value: Int,
    range: IntRange,
    label: String,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StepArrow("−") { onChange((value - 1).coerceIn(range)) }
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        StepArrow("+") { onChange((value + 1).coerceIn(range)) }
    }
}

@Composable
internal fun StepArrow(symbol: String, onClick: () -> Unit) {
    Text(
        symbol,
        style = MaterialTheme.typography.titleMedium,
        color = PiruTheme.colors.accent,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * A start date, shifted a day at a time.
 *
 * Upstream is a `DatePicker` bound to a `Date`. Material3's date picker is a
 * dialog, and both screens that use this are already dialogs, so the offset
 * cadences get a stepper over the date they actually need — the day the schedule
 * begins, which for a weekly or monthly med is the only thing the field decides.
 */
@Composable
internal fun DateOffsetPicker(startDate: Instant?, onShift: (Long) -> Unit, zone: ZoneId) {
    val label = (startDate ?: Instant.now()).atZone(zone).toLocalDate().toString()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.meds_starting_from), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        StepArrow("‹") { onShift(-1) }
        Text(label, style = MaterialTheme.typography.bodyMedium)
        StepArrow("›") { onShift(1) }
    }
}

// MARK: - Weekdays

/** One weekday, in the numbering `frequencyDays` stores. */
internal data class FoundationWeekday(val index: Int, val short: String)

/**
 * Foundation's weekday ladder: `1` = Sunday … `7` = Saturday, which is what the
 * stored schedule means on both platforms. The short label reads the ambient
 * locale — a display preference, like every other clock and date in this package.
 */
internal val FOUNDATION_WEEKDAYS: List<FoundationWeekday> = listOf(
    DayOfWeek.SUNDAY,
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
    DayOfWeek.SATURDAY,
).mapIndexed { offset, day ->
    FoundationWeekday(
        index = offset + 1,
        short = day.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WeekdayPicker(selected: Set<Int>, onToggle: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.meds_days_label), style = captionSecondaryStyle)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (day in FOUNDATION_WEEKDAYS) {
                FilterChip(
                    selected = selected.contains(day.index),
                    onClick = { onToggle(day.index) },
                    label = { Text(day.short) },
                )
            }
        }
    }
}

// MARK: - One reminder time

/**
 * One reminder time: the row, its inline time picker, and what that time does.
 *
 * The picker expands **inline** rather than in a sheet. Both screens that use
 * this are presented in a window of their own (a `Dialog` and, upstream, a
 * sheet), and a `ModalBottomSheet` inside one is a second window nested in the
 * first — the kind of thing that works until it does not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReminderTimeRow(
    time: ReminderTime,
    zone: ZoneId,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onPick: (Int) -> Unit,
    onDelete: () -> Unit,
    consequence: MedTimeConsequence?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(MedTimeGroup.groupForMinutes(time.minutes).labelRes),
                style = captionSecondaryStyle,
            )
            Text(
                timeText(time.minutes, zone),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable(onClick = onToggleExpanded).weight(1f),
            )
            MedsGlyph(
                kind = MedsGlyphKind.CLOSE,
                tint = PiruTheme.colors.tertiaryLabel,
                size = 14.dp,
                modifier = Modifier.clickable(onClick = onDelete).padding(6.dp),
            )
        }

        if (expanded) {
            val state = rememberTimePickerState(
                initialHour = time.minutes / 60,
                initialMinute = time.minutes % 60,
                is24Hour = DateFormat.is24HourFormat(LocalContext.current),
            )
            TimePicker(state = state)
            TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) {
                Text(stringResource(R.string.common_done))
            }
        }

        // What that time actually does — onset, easing off, and (for the
        // wake-promoting classes) how it sits against sleep. Silent when the med
        // has no acute profile to state.
        if (consequence != null) {
            MedTimeConsequenceLine(consequence = consequence, minutesOfDay = time.minutes, zone = zone)
        }
    }
}

/** The "Remind Me" switch, which both screens carry under their times. */
@Composable
internal fun RemindMeToggle(remind: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.meds_remind_me), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = remind, onCheckedChange = onChange)
    }
}

/** The "Quiet med" switch, with the note that makes its consequences explicit. */
@Composable
internal fun QuietMedSection(isQuiet: Boolean, onChange: (Boolean) -> Unit) {
    FormSection(null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.meds_quiet_med), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = isQuiet, onCheckedChange = onChange)
        }
        FormFooter(stringResource(R.string.meds_quiet_med_footer))
    }
}

/** The "Add a Time" affordance, whose label depends on whether any exist. */
@Composable
internal fun AddTimeButton(hasTimes: Boolean, onClick: () -> Unit) {
    Text(
        stringResource(
            if (hasTimes) R.string.meds_add_another_time else R.string.meds_add_a_time,
        ),
        style = MaterialTheme.typography.labelLarge,
        color = PiruTheme.colors.accent,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 8.dp),
    )
}

/**
 * The next time to append: 9:00 for the first, then 6 hours after the latest
 * existing time (capped to late evening) — a sensible booster gap the user
 * adjusts with one tap.
 */
internal fun suggestedNextTime(existing: List<Int>): Int {
    val latest = existing.maxOrNull() ?: return 9 * 60
    return minOf(latest + 6 * 60, 22 * 60)
}
