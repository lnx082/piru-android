package glass.kagerou.piru.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.engine.AntidepressantClass
import glass.kagerou.piru.engine.ContestedDrugClasses
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * Where this substance sits on the antidepressant axis.
 *
 * Ported from the class half of `AntidepressantClass`. The acronyms are the most-used and least-explained words in this
 * corner of pharmacology: a reader handed one of them knows the letters and not the difference, and **the difference is
 * where the whole side-effect profile comes from.** So the card carries the class rather than leaving the letters
 * unexplained.
 *
 * ## What it refuses to do
 * - **The four departures get no card.** `MELATONERGIC` (agomelatine), `NEUROSTEROID` (brexanolone), `OPIOIDERGIC`
 *   (tianeptine) and `ATYPICAL` are not points on the monoamine axis. Listing one beside SSRI would read as "these are
 *   the alternatives on one dimension" — a comparison that is not true — so their substances show no card, which is
 *   the state they were already in.
 * - **A contested assignment is marked contested.** Bupropion's NDRI label rests on transporter affinities weak enough
 *   that the mechanism is still argued over, NaSSA was coined for one drug, and venlafaxine behaves at a low dose like
 *   the class it is not filed under. Asserting those the way sertraline's SSRI is asserted would overstate what the
 *   field agrees on.
 *
 * ## What is not here
 * The nine **difference lines** — one sentence of mechanism per class — are prose that needs a translated resource
 * each, and the card is useful without them: the acronym is what a reader was handed, and naming it is the first thing
 * they needed. Stated rather than drawn as a placeholder, because a placeholder in a pharmacology card is worse than a
 * shorter card.
 */
@Composable
internal fun AntidepressantClassCard(
    substanceName: String,
    curatedDrugClass: String?,
    modifier: Modifier = Modifier,
) {
    // Null for the whole catalogue that is not an antidepressant, and for the four departures. Drawing nothing is the
    // correct state for both.
    val klass = AntidepressantClass.fromCurated(curatedDrugClass) ?: return

    PiruCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.antidepressant_class_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.antidepressant_acronym, klass.acronym, klass.wireValue.uppercase()),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (ContestedDrugClasses.isContested(substanceName)) {
                Text(
                    stringResource(R.string.antidepressant_contested),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
    }
}
