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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.BuildConfig
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.theme.PiruTheme

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
                "Settings",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        item {
            SectionCard("Appearance") {
                Text(
                    "Piru ships one skin in this build. The alternate skins and the " +
                        "light/dark override arrive with the appearance screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        item {
            PiruCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { navigator.push(PushRoute.SubstanceColors) },
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Substance colours", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Every substance has a colour from its class. Set your own for the " +
                            "ones you log.",
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
                    Text("Notifications", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Which reminders and alerts Piru may send, switch by switch, and " +
                            "the hours it stays quiet.",
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
                    Text("Health data", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Show your heart rate, blood pressure and weight next to your " +
                            "doses. Read-only, and it never leaves the phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        item {
            SectionCard("About") {
                Text(
                    "Not medical advice.",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Piru is a dose log and a reference. It does not diagnose, it does not " +
                        "recommend a dose, and it cannot know what you took.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Text(
                    "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
                Text(
                    "A dose is not a confession — the app records what you tell it and " +
                        "assumes you know what you are doing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        item {
            SectionCard("Sources") {
                Text(
                    "Substance data is assembled from substance.wiki (LGPL-2.1), " +
                        "PsychonautWiki and FreeOD (CC BY-SA 4.0), dose.wiki (CC0), TripSit, " +
                        "DailyMed, PubChem and Wikidata. Each substance names its own " +
                        "contributing sources on its detail screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Text(
                    "Piru is free software under the GPLv3, from the work of pharmacykitty " +
                        "and @kageroumado.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
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
