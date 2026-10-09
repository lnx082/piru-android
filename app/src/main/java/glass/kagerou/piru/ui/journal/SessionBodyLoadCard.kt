package glass.kagerou.piru.ui.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import glass.kagerou.piru.ui.theme.toComposeColor

/**
 * What is still on board at the session's end, and what has left.
 *
 * Ported from `SessionBodyLoadSection`. The session page showed what was **taken** and nothing about what
 * **remained**, which is the question a session's last dose raises.
 *
 * ## The three things the row has to say without being read wrong
 *
 * **A cleared row is either "wore off" or "cannot be modelled".** `unmodeled` is the flag for the second, and the
 * wording differs: "no half-life to model" is not a claim that the substance has left the body, and printing the
 * clearance sentence for it would be manufacturing a number the app does not have. This is the same distinction
 * `EntryContext.judged` makes on the entry card, and for the same reason.
 *
 * **An active row carries both its session total and its remaining amount.** They answer different questions — "how
 * much did I take" and "how much is left" — and a single number would answer neither.
 *
 * **A cleared row with an active metabolite says so.** "Cleared" is about the parent compound, and a metabolite
 * outlasting it is a real thing a reader needs told rather than a footnote.
 *
 * ## What is deliberately not drawn
 * Upstream's per-row progress bar and its "clears at 21:40" projection. The projection is computed
 * (`SessionBodyLoadModel.clearAt`) and its description is written (`describeClear`), but the section is useful
 * without a second number on every row, and a bar that encodes a percentage the text already states is decoration.
 * Naming that is better than drawing something I would then have to justify.
 */
@Composable
internal fun SessionBodyLoadCard(
    result: SessionBodyLoadModel.Result,
    onOpenSubstance: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (result.isEmpty) return

    PiruCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.journal_session_body_load),
                style = MaterialTheme.typography.titleSmall,
            )

            for (row in result.active) {
                BodyLoadRow(
                    colour = row.active.tint.toComposeColor(),
                    name = row.displayName,
                    // "150 mg · 2 doses" — the total, and how many doses it is spread over.
                    detail = stringResource(
                        R.string.journal_session_body_load_total,
                        SessionBodyLoadModel.formatAmount(row.sessionTotal),
                        row.unit,
                        row.count,
                    ),
                    // And what is left, which is the row's whole reason for being here.
                    status = stringResource(
                        R.string.journal_session_body_load_remaining,
                        SessionBodyLoadModel.formatAmount(row.remaining),
                        row.unit,
                    ),
                    onOpen = { onOpenSubstance(row.active.name) },
                )
            }

            for (row in result.cleared) {
                BodyLoadRow(
                    colour = row.colour.toComposeColor(),
                    name = row.displayName,
                    detail = stringResource(
                        R.string.journal_session_body_load_total,
                        SessionBodyLoadModel.formatAmount(row.total),
                        row.unit,
                        row.count,
                    ),
                    // The two reasons, worded apart. A cleared row that is really "unmodelled" must not claim the
                    // substance has left the body.
                    status = when {
                        row.unmodeled -> stringResource(R.string.journal_session_body_load_unmodeled)
                        row.hasActiveMetabolite ->
                            stringResource(R.string.journal_session_body_load_metabolite)
                        else -> stringResource(R.string.journal_session_body_load_cleared)
                    },
                    onOpen = { onOpenSubstance(row.displayName) },
                )
            }
        }
    }
}

/** One substance: a colour dot, its name and totals, and where it stands. */
@Composable
private fun BodyLoadRow(
    colour: Color,
    name: String,
    detail: String,
    status: String,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // The dot, so a reader can match a row to the substance's colour elsewhere. The same device the timeline and
        // the dose rows use.
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(colour),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
        TextButton(onClick = onOpen) {
            Text(stringResource(R.string.journal_session_body_load_open))
        }
    }
}
