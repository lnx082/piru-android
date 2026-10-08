package glass.kagerou.piru.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.BuildConfig
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.substance.SubstanceReader
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.theme.PiruTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * About: what the app is, where its data came from, and what it does with yours.
 *
 * Ported from `Views/Settings/About/` — six sections upstream, and the port had one paragraph and a version
 * number. The sections are not decoration: this is the page a reader opens to find out **whether to trust the
 * thing**, and each of the six answers one of those questions.
 *
 * ## The four that need saying rather than assuming
 * - **Disclaimer.** Not medical advice, reference content may be wrong, and Piru does not monitor emergencies.
 *   The last sentence is the one that matters most and the one easiest to omit.
 * - **Data sources.** Every source in the shipped catalogue, by name, with the licence each is used under. The
 *   port already reads this list for the source-priority screen; nothing showed it as provenance.
 * - **Privacy.** Where the journal actually lives. On this device, in whatever backup the user's settings make of
 *   it, and **unencrypted unless they choose otherwise** — which is the fact a user is most likely to be wrong
 *   about.
 * - **AI-assisted content.** Upstream discloses it, and the honest version is narrow: some reference text was
 *   drafted with assistance and reviewed, AI output is not treated as evidence, and quantitative claims are
 *   checked against identified sources. Saying nothing would let a reader assume the prose is all human-written.
 *
 * ## Why the licences are named rather than bundled
 * Upstream ships six full licence texts as bundle resources. The port names each licence, what it covers, and
 * where to read it, and **says that it does not bundle the texts** — a deliberate choice rather than an omission,
 * because the alternative is several hundred kilobytes of the same licences that ship with every Android app. The
 * list is the port's own dependencies, not upstream's: GRDB and swift-collections are not in this APK.
 */
@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    var sources by remember { mutableStateOf<List<SubstanceReader.SourceInfo>>(emptyList()) }

    LaunchedEffect(Unit) {
        sources = withContext(Dispatchers.IO) {
            runCatching { app.catalog().sources() }.getOrDefault(emptyList())
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = FAB_CLEARANCE),
    ) {
        item {
            AboutSection(stringResource(R.string.shell_about_disclaimer)) {
                AboutParagraph(stringResource(R.string.shell_about_disclaimer_1))
                AboutParagraph(stringResource(R.string.shell_about_disclaimer_2))
                AboutParagraph(stringResource(R.string.shell_about_disclaimer_3))
                // The sentence a reader most needs and the easiest to leave out.
                AboutParagraph(
                    stringResource(R.string.shell_about_disclaimer_emergency),
                    emphasis = true,
                )
            }
        }

        item {
            AboutSection(stringResource(R.string.shell_about_sources)) {
                AboutParagraph(stringResource(R.string.shell_about_sources_caption))
                // Every source the catalogue carries, in the priority order the app itself ranks them by.
                for (source in sources) {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(source.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            // The licence the catalogue names in the source's own sentence, or the
                            // catalogue's sentence itself when it names none. Never a blank line, and never a
                            // guessed licence.
                            SourceLicences.licenceIn(source.description)
                                ?: source.description.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.shell_about_source_no_license),
                            style = MaterialTheme.typography.labelSmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                }
                AboutParagraph(stringResource(R.string.shell_about_sources_licenses))
            }
        }

        item {
            AboutSection(stringResource(R.string.shell_about_open_source)) {
                AboutParagraph(stringResource(R.string.shell_about_open_source_body))
            }
        }

        item {
            AboutSection(stringResource(R.string.shell_about_ai)) {
                AboutParagraph(stringResource(R.string.shell_about_ai_body))
            }
        }

        item {
            AboutSection(stringResource(R.string.shell_about_privacy)) {
                // The three places data can be, each answered separately. "Encrypted unless you choose
                // otherwise" is the fact a user is most likely to have wrong, so it is stated rather than
                // implied.
                AboutParagraph(stringResource(R.string.shell_about_privacy_journal))
                AboutParagraph(stringResource(R.string.shell_about_privacy_health))
                AboutParagraph(stringResource(R.string.shell_about_privacy_exports))
            }
        }

        item {
            AboutSection(stringResource(R.string.shell_about_app)) {
                Text(
                    stringResource(
                        R.string.shell_settings_version,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
                AboutParagraph(stringResource(R.string.shell_about_app_catalogue))
            }
        }
    }
}

/**
 * One About section: a heading and its content.
 *
 * A plain heading rather than a card, because this is a reading page: six cards would make it a list of things to
 * tap, and none of them are tappable.
 */
@Composable
private fun AboutSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

/**
 * One paragraph of About prose.
 *
 * [emphasis] is for the sentences that are warnings rather than description — the emergency line, and nothing else
 * so far. Colouring every paragraph would say nothing.
 */
@Composable
private fun AboutParagraph(body: String, emphasis: Boolean = false) {
    Text(
        body,
        style = MaterialTheme.typography.bodyMedium,
        color = if (emphasis) PiruTheme.colors.accent else PiruTheme.colors.secondaryLabel,
    )
}
