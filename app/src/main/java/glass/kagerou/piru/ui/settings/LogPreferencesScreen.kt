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
 * The logging and medication-time preferences.
 *
 * ## Why these three and not more
 * Each is a switch over something the app already does and had no way to turn off:
 *
 * - **The quick-log dock** is the only thing on the journal that is an *offer* rather than a record. Someone who logs
 *   from the substance page does not want a row of chips over their timeline.
 * - **Reminders** had a per-item `remind` flag and no global switch, so silencing them meant editing every item.
 * - **The reminder delay** is the "med times" figure the settings list asks for: a dose taken at 08:00 that a user
 *   actually takes at 08:30 needs the reminder to follow the habit, and editing every item's time makes the setting
 *   useless.
 *
 * ## The offset is a text field
 * A slider moving in fives cannot express ten minutes, and the store clamps what it holds, so the field does not have
 * to. A value that does not parse is held as typed and **not** written, so a half-typed number never becomes a
 * preference.
 */
@Composable
fun LogPreferencesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // `AppSettingsStore(context)`, which is how the settings hub builds it: the application exposes a `profile()`
    // accessor and **no** settings one, so the store is constructed where it is used. I wrote `app.settings()` first,
    // which does not exist — the same guess-before-reading this project keeps re-learning.
    val settings = remember { glass.kagerou.piru.data.AppSettingsStore(context) }

    var dock by remember { mutableStateOf(settings.showQuickLogDock()) }
    var reminders by remember { mutableStateOf(settings.adherenceRemindersEnabled()) }
    var offset by remember { mutableStateOf(settings.adherenceReminderOffsetMinutes().toString()) }

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
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.log_prefs_dock_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Switch(
                            checked = dock,
                            onCheckedChange = {
                                dock = it
                                settings.setShowQuickLogDock(it)
                            },
                        )
                    }
                    Text(
                        stringResource(R.string.log_prefs_dock_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
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

                    OutlinedTextField(
                        value = offset,
                        onValueChange = { typed ->
                            offset = typed
                            // Written only when it parses, so a half-typed number never becomes a preference. The store
                            // clamps; this does not have to.
                            typed.toIntOrNull()?.let { settings.setAdherenceReminderOffsetMinutes(it) }
                        },
                        label = { Text(stringResource(R.string.med_times_offset)) },
                        suffix = { Text(stringResource(R.string.med_times_offset_unit)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    Text(
                        stringResource(R.string.med_times_offset_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }
    }
}
