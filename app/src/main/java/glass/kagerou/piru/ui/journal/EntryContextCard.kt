package glass.kagerou.piru.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * What else was going on around this dose.
 *
 * ## The sentence this card has to get right
 * `EntryContext.neighbours` returns `judged` alongside the list, and the card's **wording differs** between the two
 * cases:
 *
 * - `judged = true` — the app looked, and these are the doses whose courses it compared.
 * - `judged = false` — this substance has no duration in the catalogue, so **nothing can be said about overlap**.
 *
 * Printing "nothing overlapped" for the second would be a claim about the user's own data that the app cannot
 * support. So the unjudged case says exactly that, and the rows are still listed, because a dose logged twenty
 * minutes earlier is a fact about the session whatever its profile.
 *
 * ## Why the rows are not a second day list
 * The card caps at six and sorts by distance, so it reads as "around this dose" rather than as the day's log again.
 * A row in the same session is marked, because those are the doses the user grouped together deliberately.
 */
@Composable
internal fun EntryContextCard(
    result: EntryContext.Result,
    onOpen: (rowId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!EntryContext.worthShowing(result)) return

    PiruCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.journal_entry_context), style = MaterialTheme.typography.titleSmall)

            if (!result.judged) {
                // The honest version of "this cannot be determined", and the reason the two flags exist.
                Text(
                    stringResource(R.string.journal_entry_context_unjudged),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }

            // Resolved once, outside the loop: a resource read cannot happen inside a `buildString`, and the two
            // labels are the same for every row.
            val sameSessionLabel = stringResource(R.string.journal_entry_context_same_session)
            val overlapLabel = stringResource(R.string.journal_entry_context_overlaps)

            for (neighbour in result.neighbours) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(neighbour.substance, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            buildString {
                                append(EntryContext.describeOffset(neighbour.offsetMinutes))
                                if (neighbour.sameSession) {
                                    append(" · ")
                                    append(sameSessionLabel)
                                }
                                // Only claimed when it was judged: an unjudged row says nothing about overlap.
                                if (result.judged && neighbour.overlaps) {
                                    append(" · ")
                                    append(overlapLabel)
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                    TextButton(onClick = { onOpen(neighbour.rowId) }) {
                        Text(stringResource(R.string.journal_entry_context_open))
                    }
                }
            }
        }
    }
}
