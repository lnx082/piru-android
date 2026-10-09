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
import glass.kagerou.piru.engine.ActiveMetaboliteFold
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * "Also Active": what the body makes from this dose.
 *
 * The card beside "In Your Body". That one says how much is left; this one says **of what** — someone reading a dose
 * detail wants to know whether the thing still working is the thing they swallowed.
 *
 * ## Why it is not gated behind the pharmacology tier
 * Upstream's own note: the library surface is gated because it lives among reference tables, and this is not reference
 * data. Someone tracking an SSRI never opens the pharmacology tier and is **exactly** who needs to hear that a
 * metabolite outlives the dose. A detail-level gate would show it only to the readers who already know.
 *
 * ## The footnote is load-bearing
 * "Not a measured level" is not decoration. The curve-shaped content elsewhere on this screen is a modelled projection
 * from the user's own log, and a sentence about a metabolite outlasting a dose would otherwise read as a measurement
 * of the user's blood. Upstream carries the same footnote for the same reason.
 *
 * ## What is not drawn
 * Upstream's per-metabolite `curves` sparkline, which needs a `MetaboliteCurve` this port has not built. The rows are
 * useful without it, and naming the absence is better than drawing a shape with invented points.
 */
@Composable
internal fun ActiveMetaboliteCard(
    entries: List<ActiveMetaboliteFold.Entry>,
    parentName: String,
    parentHalfLifeMinutes: Double?,
    parentDurationMinutes: Double?,
    accent: Color,
    formationFractionPct: Double?,
    onOpenSubstance: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The gate: only a metabolite that outlives the dose gets this surface. A metabolite is a normal part of how a
    // drug works, and saying so at section volume overstates it.
    val shown = entries.filter {
        ActiveMetaboliteFold.earnsOwnSection(
            entry = it,
            parentHalfLifeMinutes = parentHalfLifeMinutes,
            parentDurationMinutes = parentDurationMinutes,
            materiallyActive = true,
        )
    }
    if (shown.isEmpty()) return

    PiruCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.metabolite_also_active),
                style = MaterialTheme.typography.titleSmall,
            )

            for (entry in shown) {
                val statement = ActiveMetaboliteFold.statement(
                    entry = entry,
                    parentName = parentName,
                    parentHalfLifeMinutes = parentHalfLifeMinutes,
                    parentDurationMinutes = parentDurationMinutes,
                    formationFractionPct = formationFractionPct,
                    materiallyActive = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 5.dp)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(accent),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            metaboliteHeadline(statement),
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                    // Only a metabolite the catalogue carries as its own substance can be opened; the rest are names
                    // with no page behind them, and a button that leads nowhere is worse than no button.
                    entry.substanceName?.let { name ->
                        TextButton(onClick = { onOpenSubstance(name) }) {
                            Text(stringResource(R.string.metabolite_open))
                        }
                    }
                }
            }

            Text(
                stringResource(R.string.metabolite_footnote),
                style = MaterialTheme.typography.labelSmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}
