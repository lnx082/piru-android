package glass.kagerou.piru.ui.library

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.model.SubstanceRoute
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * The share control: a level picker and a Share button.
 *
 * ## Why the level is chosen here rather than in a sheet
 * Upstream opens a share sheet to pick the level and then render. This port's share is a text body, so the picker is
 * three chips and the button is one tap — a sheet that contained a chip row and a button would be a dialog with one
 * decision in it. The levels are still named and still chosen by the user, which is the part that matters: what is
 * safe to send to a friend is their decision and not the app's.
 */
@Composable
internal fun SubstanceShareRow(
    substance: Substance,
    route: SubstanceRoute?,
    routeChoice: RouteOfAdministration?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var level by remember { mutableStateOf(ShareDetailLevel.STANDARD) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.share_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            // Scrollable, because three level names in another language can exceed a narrow phone.
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (option in ShareDetailLevel.entries) {
                FilterChip(
                    selected = level == option,
                    onClick = { level = option },
                    label = { Text(stringResource(option.resourceId)) },
                )
            }
            TextButton(
                onClick = {
                    shareSubstanceText(
                        context,
                        SubstanceShareText.build(substance, route, routeChoice, level),
                    )
                },
            ) {
                Text(stringResource(R.string.share_button), color = PiruTheme.colors.accent)
            }
        }
    }
}

/**
 * Hands the text to whatever the user picks.
 *
 * `ACTION_SEND` with a plain-text type and a chooser, which is what the app's report and static-content screens already
 * do. No `EXTRA_TITLE`: the substance's name is the first line of the body, and a subject that repeats it is noise.
 */
private fun shareSubstanceText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, null))
}
