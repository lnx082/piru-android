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
import glass.kagerou.piru.engine.InteractionSeverity
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import glass.kagerou.piru.ui.tools.InteractionSeverityPalette
import glass.kagerou.piru.ui.tools.Chip

/**
 * The session's interaction warnings, folded into one row per rule.
 *
 * Ported from `SessionSafetySection`. Placed on the session page rather than in the quick-log dock, which is
 * upstream's own decision and the right one: **a warning in a dock arrives after the decision it would inform**, and
 * a dock is for recording what has already been taken.
 *
 * ## The three things the card has to get right
 * 1. **Folding is by rule, not by sentence.** Several distinct rules share boilerplate; merged, the row would
 *    assert one cause where there are two. `InteractionGrouping` owns that rule and is tested against it.
 * 2. **The severity chip is the group's worst member**, because that is the answer to "how bad is this".
 * 3. **The folded row keeps its count.** "3 combinations" is the fact that three pairs fired; drawing only the
 *    first pair would under-report and drawing all of them unbounded would bury the row.
 *
 * ## What is deliberately not here yet
 * The measured-exposure findings and the heart-rate summary that upstream draws below the warnings. Both need the
 * PK interaction layer and the cardio lane wired into this screen, and **dressing a number in the severity ladder
 * would manufacture the one thing a reader leans on hardest** — upstream's own reason for keeping them visually and
 * structurally apart. They belong in their own block rather than being approximated here.
 */
@Composable
internal fun SessionSafetyCard(
    groups: List<InteractionGrouping.Group>,
    onOpenPair: (a: String, b: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (groups.isEmpty()) return

    PiruCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.journal_session_interactions), style = MaterialTheme.typography.titleSmall)

            for (group in groups) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            // The pairs, in the order they fired. One pair is just the pair; several are joined so
                            // the row says which combinations it stands for.
                            group.pairs.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (group.pairs.size > 1) {
                            Text(
                                stringResource(R.string.journal_session_interaction_pairs, group.pairs.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                        }
                        Text(
                            group.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                        if (group.pairs.size > 1) {
                            TextButton(
                                onClick = {
                                    onOpenPair(group.navigationSubstanceA, group.navigationSubstanceB)
                                },
                            ) { Text(stringResource(R.string.journal_session_interaction_timeline)) }
                        }
                    }
                    SeverityChip(group.severity)
                }
            }
        }
    }
}

/**
 * The severity, as a tinted chip.
 *
 * The colour comes from `InteractionSeverityPalette`, which is the app's one severity ladder — a second mapping
 * here is how this card and the interactions screen would start disagreeing about what "unsafe" looks like. The
 * palette's own comment records why it is a three-step scale rather than three lookups of a four-step one.
 *
 * The word comes from the `severity_*` vocabulary strings, which is where the interactions screen reads it too, and
 * it is lowercased to match that screen's chips. **Not** `InteractionSeverity.label`: that one is English by
 * contract — its own doc says "The localized label lives with the app's resources" — so printing it would put
 * English severity words on a Chinese screen.
 */
@Composable
private fun SeverityChip(severity: InteractionSeverity) {
    Chip(
        text = severityLabel(severity).lowercase(),
        tint = InteractionSeverityPalette.text(severity),
    )
}

/** The severity as the app's own word. */
@Composable
private fun severityLabel(severity: InteractionSeverity): String = when (severity) {
    InteractionSeverity.CAUTION -> stringResource(R.string.severity_caution)
    InteractionSeverity.UNSAFE -> stringResource(R.string.severity_unsafe)
    InteractionSeverity.DANGEROUS -> stringResource(R.string.severity_dangerous)
}
