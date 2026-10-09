package glass.kagerou.piru.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.BuildConfig
import glass.kagerou.piru.R
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.theme.PiruTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.DisclosureTier
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.OutlinedButton
import glass.kagerou.piru.data.AppSettingsStore

/**
 * Settings.
 *
 * Ported from `Views/Settings/` — sixteen files and about 3,348 lines, of which
 * this draws the sections the port can honour: **substance colours**, **health
 * data**, and **about**. What is not here: appearance and skins (the alternate
 * skins are not ported), journal preferences (the reference body weight, the day
 * boundary, the tally cut-off), and data & backup. Each of those needs the store
 * or the platform service behind it before a switch would do anything, and a
 * settings screen full of inert controls is worse than a short one.
 *
 * ## Every row here goes somewhere
 * A settings list is the easiest place in an app to accumulate controls that
 * write a preference nothing reads. Each entry on this screen opens a screen
 * that does something, and the ones that do not exist yet are named in the
 * Appearance section rather than drawn as dead switches.
 *
 * ## "Not medical advice" lives here, and on every substance
 * The house rule is that the disclaimer stays prominent. It is on the substance
 * detail footer as well, because that is the screen a reader reaches from a
 * search for a drug they have never taken — which is not necessarily a screen
 * they arrived at through Settings.
 */
/** The bounds the engine's own `SessionDay.boundaryHour` clamps to. */
private const val MIN_DAY_BOUNDARY_HOUR = 0
private const val MAX_DAY_BOUNDARY_HOUR = 12

/** A small square button for a bounded integer. Text rather than an icon: the core
 * icon set has no minus, and a `+`/`-` pair reads the same in every language. */
@Composable
private fun StepperButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(0.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun SettingsScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Text(
                stringResource(R.string.shell_settings_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        item {
            SectionCard(stringResource(R.string.shell_settings_appearance)) {
                Text(
                    stringResource(R.string.shell_settings_appearance_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        // The detail level, and the control that makes a stored preference mean something.
        //
        // The tier has been collected at onboarding since the port began and read by nothing:
        // no getter on the store, no picker here, so the question had no effect on any screen.
        // iOS puts this picker on its Settings page for exactly that reason.
        item {
            val app = LocalContext.current.applicationContext as PiruApplication
            val scope = rememberCoroutineScope()
            var tier by remember { mutableStateOf(app.profile().disclosureTier()) }

            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.shell_settings_detail_level),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    for (option in DisclosureTier.entries) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = option == tier,
                                    role = Role.RadioButton,
                                    onClick = {
                                        tier = option
                                        scope.launch { app.profile().setDisclosureTier(option.wireValue) }
                                    },
                                )
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RadioButton(selected = option == tier, onClick = null)
                            Column {
                                Text(
                                    stringResource(
                                        when (option) {
                                            DisclosureTier.CASUAL ->
                                                R.string.shell_settings_detail_casual
                                            DisclosureTier.CURIOUS ->
                                                R.string.shell_settings_detail_curious
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    stringResource(
                                        when (option) {
                                            DisclosureTier.CASUAL ->
                                                R.string.shell_settings_detail_casual_note
                                            DisclosureTier.CURIOUS ->
                                                R.string.shell_settings_detail_curious_note
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PiruTheme.colors.secondaryLabel,
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.shell_settings_detail_level_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        // Substance names in English.
        //
        // The catalogue carries 1,606 localized names across 605 substances, and the reader
        // supports resolving them — but the app graph passed `ContentLanguage.EN` literally, so
        // `Substance.localizedName` was null for every substance and a Chinese reader saw
        // English titles with no way to ask otherwise. This is the switch that reaches them;
        // iOS only shows its counterpart when the app runs in a language that has them.
        item {
            val app = LocalContext.current.applicationContext as PiruApplication
            val scope = rememberCoroutineScope()
            var englishNames by remember { mutableStateOf(app.usesEnglishNames) }

            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.shell_settings_names_english),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(R.string.shell_settings_names_english_detail),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                    Switch(
                        checked = englishNames,
                        onCheckedChange = { value ->
                            englishNames = value
                            // The catalogue is built with the language and name mode baked in,
                            // so changing either has to rebuild it — see
                            // `PiruApplication.reconfigureContent`.
                            scope.launch { app.setUsesEnglishNames(value) }
                        },
                    )
                }
            }
        }

        // Journal preferences.
        //
        // The day boundary is the one the audit named: `SessionDay.DAY_BOUNDARY_HOUR_KEY` was read
        // by one screen and written by nothing, so it was permanently the engine's 4 AM — and
        // three other calendar call sites did not even read it. It needed a writer before it could
        // mean anything, and this is it. Upstream's control is a 0…12 stepper in
        // `JournalSettingsView`.
        item {
            val context = LocalContext.current
            val settings = remember { AppSettingsStore(context) }
            var boundary by remember { mutableStateOf(settings.dayBoundaryHour()) }
            var stackRedoses by remember { mutableStateOf(settings.stackRedoses()) }

            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.shell_settings_journal),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.shell_settings_day_boundary),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(R.string.shell_settings_day_boundary_value, boundary),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PiruTheme.colors.secondaryLabel,
                            modifier = Modifier.weight(1f),
                        )
                        StepperButton(
                            label = "\u2212",
                            enabled = boundary > MIN_DAY_BOUNDARY_HOUR,
                            onClick = {
                                boundary -= 1
                                settings.setDayBoundaryHour(boundary)
                            },
                        )
                        StepperButton(
                            label = "+",
                            enabled = boundary < MAX_DAY_BOUNDARY_HOUR,
                            onClick = {
                                boundary += 1
                                settings.setDayBoundaryHour(boundary)
                            },
                        )
                    }
                    Text(
                        stringResource(R.string.shell_settings_day_boundary_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.shell_settings_stack_redoses),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                stringResource(R.string.shell_settings_stack_redoses_detail),
                                style = MaterialTheme.typography.bodySmall,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                        }
                        Switch(
                            checked = stackRedoses,
                            onCheckedChange = { value ->
                                stackRedoses = value
                                settings.setStackRedoses(value)
                            },
                        )
                    }
                }
            }
        }

        item {
            PiruCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { navigator.push(PushRoute.SubstanceColors) },
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.shell_settings_substance_colours),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.shell_settings_substance_colours_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        item {
            PiruCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { navigator.push(PushRoute.NotificationSettings) },
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.shell_settings_notifications),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.shell_settings_notifications_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        item {
            PiruCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { navigator.push(PushRoute.HealthData) },
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.shell_settings_health_data),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.shell_settings_health_data_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        item {
            PiruCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { navigator.push(PushRoute.DataStorage) },
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.shell_settings_data_backup),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.shell_settings_data_backup_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        item {
            PiruCard(
                modifier = Modifier.fillMaxWidth(),
                // Above the timeline preferences, because logging is the subject both of them are about: the timeline
                // preferences change how the log is *drawn*, and this changes how it is *written*.
                onClick = { navigator.push(PushRoute.LogPreferences) },
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.shell_settings_logging),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.log_prefs_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        item {
            PiruCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { navigator.push(PushRoute.TimelinePreferences) },
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.journal_timeline_prefs_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.journal_timeline_prefs_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        item {
            PiruCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { navigator.push(PushRoute.CustomSubstances) },
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.shell_custom_substances_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.shell_custom_substances_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        item {
            PiruCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { navigator.push(PushRoute.TabSettings) },
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.shell_tabs_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.shell_settings_tabs_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        item {
            PiruCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { navigator.push(PushRoute.About) },
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.shell_settings_about),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        // The two-paragraph cards that were here said "sources exist" without naming one. The
                        // page names all eighteen, with each one's licence.
                        stringResource(R.string.shell_settings_about_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}
