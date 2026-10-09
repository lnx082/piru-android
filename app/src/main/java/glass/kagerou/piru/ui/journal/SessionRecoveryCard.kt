package glass.kagerou.piru.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.ui.tools.ComedownCategoryRow
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * The recovery tips for the classes this session involved.
 *
 * Ported from `SessionRecoverySection`. Upstream's reason for putting the guidance **on the session screen** rather
 * than leaving a bare "recovery tips" link is the one that matters: it makes the guidance part of the session rather
 * than somewhere the reader has to think to go. A link at the bottom of a page is a link nobody follows at three in
 * the morning.
 *
 * ## The rows are the guide's own
 * Each category is a `ComedownCategoryRow` — the same fold-open row the comedown guide draws, from the same
 * `ComedownCategories.GUIDED` list. A lookalike row here would drift from that one the first time a tip group
 * changed, and the section links to that guide, so the two disagreeing would be visible in one tap.
 *
 * ## Nothing to say means nothing drawn
 * A session of substances the guide does not cover gets no card at all, rather than a heading over an empty list. The
 * guide covers eight classes; a session of, say, a supplement has no recovery advice to offer and saying so with a
 * card would be furniture.
 */
@Composable
internal fun SessionRecoveryCard(
    categories: List<SubstanceCategory>,
    onOpenGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (categories.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.journal_session_recovery),
            style = MaterialTheme.typography.titleSmall,
        )

        for (category in categories) {
            ComedownCategoryRow(category)
        }

        // The link to everything, in the same column as the rows above it so it reads as the end of that list rather
        // than as a separate offer.
        TextButton(onClick = onOpenGuide) {
            Text(
                stringResource(R.string.journal_session_recovery_all),
                color = PiruTheme.colors.accent,
            )
        }
    }
}
