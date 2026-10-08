package glass.kagerou.piru.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * What a search that reads as a call for help answers with.
 *
 * Ported from the library's `helpResourcesSection`. Shown **instead of** the search results, which is the
 * whole design: someone who typed "overdose" into the substance box does not want the pharmacology of
 * naloxone, and a panel below a list of compounds is a panel they will not reach.
 *
 * ## Which numbers, and why they are tapable rather than printed
 * A phone number on a screen is something a person has to retype while distressed. Each row is a
 * `TextButton` that dials, so the whole interaction is one tap. The rows are US numbers because the
 * catalogue and the app's own copy are US-centric, and the panel says so rather than implying it is
 * universal.
 *
 * ## What it does not say
 * No assessment of what the user typed, no reassurance about a specific substance, and no advice. The
 * app cannot know what has been taken or what is happening, and the one thing a screen in this position
 * must not do is guess.
 */
@Composable
fun CrisisPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    PiruCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.shell_crisis_breathe),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(R.string.shell_crisis_alone),
                style = MaterialTheme.typography.bodyMedium,
                color = PiruTheme.colors.secondaryLabel,
            )
            Text(
                stringResource(R.string.shell_crisis_right_now),
                style = MaterialTheme.typography.labelLarge,
            )
            CrisisRow(R.string.shell_crisis_emergency, R.string.shell_crisis_emergency_detail, "tel:911")
            CrisisRow(
                R.string.shell_crisis_poison,
                R.string.shell_crisis_poison_detail,
                "tel:18002221222",
            )
            CrisisRow(R.string.shell_crisis_988, R.string.shell_crisis_988_detail, "tel:988")
            CrisisRow(
                R.string.shell_crisis_text,
                R.string.shell_crisis_text_detail,
                // The `?` is not optional: without it Android reads `&body=HOME` as part of the
                // recipient, which is a message that never sends. This is upstream's `sms:` URL
                // rewritten for the platform's own separator.
                "sms:741741?body=HOME",
            )
            CrisisRow(
                R.string.shell_crisis_samhsa,
                R.string.shell_crisis_samhsa_detail,
                "tel:18006624357",
            )
            Text(
                stringResource(R.string.shell_crisis_us_only),
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}

/**
 * One help line: a title, a detail, and a tap that dials or texts.
 *
 * The intent is resolved by the platform, so a tablet with no dialer, a device in a country where the
 * number does not exist, or a user who has removed the phone app all fall through to `runCatching`
 * rather than crashing the crisis panel. A safety screen that throws is worse than one that shows a
 * number, which is the fallback here.
 */
@Composable
private fun CrisisRow(titleRes: Int, detailRes: Int, uri: String) {
    val context = LocalContext.current
    TextButton(
        onClick = {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri)),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(detailRes),
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}
