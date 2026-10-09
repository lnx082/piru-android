package glass.kagerou.piru.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * The medication-reminder preference.
 *
 * ## What this screen offers, after two controls were removed from it
 * One switch: whether scheduled-medication reminders are delivered. `NotificationPreferencesStore` already gates them
 * by `NotificationType.ROUTINE`, so this is a second surface over one decision — the honest description is that the
 * notifications screen owns it and this screen repeats it under a name a reader is looking for.
 *
 * ## What it deliberately does not offer, and why
 * I first shipped this with two more controls, and **both were inert**:
 *
 * - **A quick-log dock switch.** The journal has no chip row to hide. `QuickLogSheet` is presented from a
 *   `FloatingActionButton`, and the port's own note records that the dock's collapsed presentation was not ported.
 * - **A reminder-delay field.** `DailyDoseItemEntity.reminderTimesJson` is read by `MedReminderScheduler` and written
 *   by **nothing in the UI** — only by an import. An offset applied to times a user cannot set is a second knob on a
 *   dial that does not exist.
 *
 * Both store accessors are kept and exported, with the reason recorded beside them. A preference with no consumer is
 * not the same as a preference with no reader *yet*: the dock needs its collapsed presentation, and the times need a
 * per-item editor. **An inert toggle is worse than a missing feature, because it says "this is controllable" about
 * something that is not.**
 */
@Composable
fun LogPreferencesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // `AppSettingsStore(context)`, which is how the settings hub builds it: the application exposes a `profile()`
    // accessor and **no** settings one, so the store is constructed where it is used. I wrote `app.settings()` first,
    // which does not exist — the same guess-before-reading this project keeps re-learning.
    val settings = remember { glass.kagerou.piru.data.AppSettingsStore(context) }

    var reminders by remember { mutableStateOf(settings.adherenceRemindersEnabled()) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Text(
                stringResource(R.string.log_prefs_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        item {
            Text(
                stringResource(R.string.log_prefs_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = PiruTheme.colors.secondaryLabel,
            )
        }

        item {
            Text(
                stringResource(R.string.med_times_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.med_times_reminders),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Switch(
                            checked = reminders,
                            onCheckedChange = {
                                reminders = it
                                settings.setAdherenceRemindersEnabled(it)
                            },
                        )
                    }
                    Text(
                        stringResource(R.string.med_times_reminders_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }
    }
}
