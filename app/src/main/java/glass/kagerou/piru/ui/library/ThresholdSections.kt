package glass.kagerou.piru.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.engine.ConcentrationEffectHit
import glass.kagerou.piru.engine.NeuroimagingHit
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * The threshold section: blood or serum levels at which something happens.
 *
 * ## What the table holds, and what the port was already reading
 * `concentration_effects` has 25 rows over 20 substances in two kinds. The port read **one** of them:
 * `therapeuticRangeRows` filters to `kind = 'therapeutic_range'` because the engine needs a half-maximal
 * concentration to calibrate modelled occupancy against — and its result is shown nowhere. The other **23** rows
 * have `kind` NULL and are the ones a reader wants:
 *
 *   "fatal blood concentration" (3-MeO-PCP)
 *   "respiratory depression (clinically significant)" (Fentanyl)
 *   "QTc prolongation (?msEC)" (Citalopram)
 *   "respiratory rate (ceiling)" (Buprenorphine)
 *
 * Those are harm-reduction findings, not model inputs, and nothing read them at all.
 *
 * ## Why findings come first and are coloured
 * A therapeutic range is context; a fatal concentration is the reason to open the section. So findings lead,
 * carry the accent, and the therapeutic reference follows as plain text. `isFinding` is the model's own
 * distinction rather than a string comparison here.
 *
 * ## Why the unit is printed as the source wrote it
 * The column is free text with five values including `µg/mL` and `ng/mL psilocin`. Normalising them would mean
 * choosing a canonical unit, and the substance that needs the note ("psilocin" rather than the parent) is exactly
 * the case a normaliser would drop.
 */
@Composable
fun ThresholdCard(hits: List<ConcentrationEffectHit>) {
    if (hits.isEmpty()) return
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.shell_section_threshold), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.shell_threshold_blurb),
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
            for (hit in hits) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        hit.effect,
                        style = MaterialTheme.typography.bodyMedium,
                        // A finding is a caution; the therapeutic reference is not, because a range you are
                        // *supposed* to be inside is not a warning.
                        color = if (hit.isFinding) PiruTheme.colors.accent else PiruTheme.colors.secondaryLabel,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val threshold = hit.thresholdValue
                        if (threshold != null) {
                            Text(
                                stringResource(
                                    R.string.shell_threshold_at,
                                    trimConcentration(threshold),
                                    hit.concentrationUnit,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        // The second magnitude, labelled by what it is rather than called a peak: for a threshold
                        // row the source's `peak_effect` is often the dangerous value, and "peak" would read as
                        // "the good part".
                        val peak = hit.peakValue
                        if (peak != null) {
                            Text(
                                stringResource(
                                    R.string.shell_threshold_peak,
                                    trimConcentration(peak),
                                    hit.concentrationUnit,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                        }
                    }
                    SourceLine(hit.sourceSlug, hit.doi, hit.pmid)
                }
            }
        }
    }
}

/**
 * The target-evidence section: what scans of living brains have shown.
 *
 * ## Why this is evidence rather than trivia
 * The binding table says what a substance binds **in a dish**. This says what a living brain did, which is the
 * corroboration a reader weighing a receptor table wants — and the two can disagree, which is the interesting
 * case. 52 rows over 36 substances, with thirteen distinct modality strings; nothing read them before this.
 *
 * ## Why the modality is grouped rather than repeated
 * One study's finding means little without its method, so the rows of a modality are kept together under one
 * heading. The modality strings are free text and some describe the study rather than the machine
 * (`"fMRI BOLD (12 healthy men, 15 µg/kg inhaled vapor)"`), which is why they are shown verbatim rather than
 * normalised into a short list.
 */
@Composable
fun NeuroimagingCard(hits: List<NeuroimagingHit>) {
    if (hits.isEmpty()) return
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.shell_section_neuroimaging), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.shell_neuroimaging_blurb),
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
            // Grouped by modality, and the groups keep the reader's own order — which the reader sorts by modality
            // name, so the list is stable between reads.
            for ((modality, rows) in hits.groupBy { it.modality }) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        modality.ifBlank { stringResource(R.string.shell_neuroimaging_unspecified) },
                        style = MaterialTheme.typography.labelLarge,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                    for (hit in rows) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(hit.finding, style = MaterialTheme.typography.bodySmall)
                            SourceLine(hit.sourceSlug, hit.doi, hit.pmid)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A concentration without a trailing `.0`.
 *
 * The column stores `Double`s, so 10 prints as "10.0" and 0.25 as "0.25". A reader comparing levels across
 * substances wants the digits that carry information and not the ones that do not.
 */
private fun trimConcentration(value: Double): String {
    val rounded = Math.round(value * 1000.0) / 1000.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        String.format(java.util.Locale.ROOT, "%s", rounded.toString().trimEnd('0').trimEnd('.'))
    }
}
