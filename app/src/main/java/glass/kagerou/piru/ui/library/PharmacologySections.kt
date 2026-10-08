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
import glass.kagerou.piru.engine.DownstreamSignallingHit
import glass.kagerou.piru.engine.OffTargetHit
import glass.kagerou.piru.engine.PharmacogeneticHit
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * What a substance sets off beyond the receptor it binds.
 *
 * Three sections that had no screen at all, each over a catalogue table this port ships and never read:
 * `downstream_signalling` (678 rows, 678 substances), `off_targets` (209 rows, 165 substances) and
 * `pharmacogenetics` (305 rows, 169 substances).
 *
 * ## Why signalling is not part of the binding table
 * The binding table says what a substance **touches**; this says what happens after. Two compounds can share a
 * target and diverge entirely in what the cell does next, which is the fact binding affinities cannot express and
 * the reason upstream keeps the two apart.
 *
 * ## Why off-targets are not either
 * "Off-target" is what separates a substance's pharmacology from its side-effect profile. Merging them would make
 * the mechanism indistinguishable from everything else the compound touches — and the concern level and clinical
 * consequence columns only mean anything for the non-mechanism rows.
 *
 * ## Why pharmacogenetics is prose rather than a direction
 * Each row describes what a phenotype of one gene does, and the interesting cases differ **by gene** rather than
 * by sign: a poor metaboliser of one CYP accumulates the parent, of another accumulates a metabolite. Reducing
 * that to up/down would lose the only part a reader wants.
 */

/** What the substance sets off downstream, one entry per source. */
@Composable
fun DownstreamSignallingCard(hits: List<DownstreamSignallingHit>) {
    if (hits.isEmpty()) return
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader(stringResource(R.string.shell_section_signal_cascade))
            for (hit in hits) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(hit.summary, style = MaterialTheme.typography.bodyMedium)
                    SourceLine(hit.sourceSlug, hit.doi, hit.pmid)
                }
            }
        }
    }
}

/**
 * The targets the substance hits besides its mechanism.
 *
 * Ordered by affinity, tightest first, which is the reader's question — and the concern level is shown beside it
 * rather than used to sort, because it is a source's editorial judgement and sorting by it would put one source's
 * opinion above another's measurement.
 */
@Composable
fun OffTargetCard(hits: List<OffTargetHit>) {
    if (hits.isEmpty()) return
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionHeader(stringResource(R.string.shell_section_off_target))
            Text(
                stringResource(R.string.shell_off_target_blurb),
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
            for (hit in hits) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(hit.target, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            // An em dash when the source reported no affinity, because a blank would read as
                            // "no affinity" rather than "not measured".
                            hit.kiOrIc50Nm?.let { formatNm(it) }
                                ?: stringResource(R.string.shell_value_unknown),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                    // Read into locals: `OffTargetHit` is in `:core:engine` and this is `:app`, and a
                    // cross-module nullable `val` is not stable for smart casting.
                    val concern = hit.concernLevel
                    if (concern != null) {
                        Text(
                            // Capitalised for display: the column holds a lowercase wire value, and it is a
                            // label rather than a term.
                            concern.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = PiruTheme.colors.accent,
                        )
                    }
                    val consequence = hit.clinicalConsequence
                    if (consequence != null) {
                        Text(
                            consequence,
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                    SourceLine(hit.sourceSlug, hit.doi, hit.pmid)
                }
            }
        }
    }
}

/**
 * The genes that change what the substance does.
 *
 * Grouped by gene, because a reader looking for their own genotype scans for the gene rather than reading the
 * list — which is also why the reader sorts by gene name rather than by source.
 */
@Composable
fun PharmacogeneticsCard(hits: List<PharmacogeneticHit>) {
    if (hits.isEmpty()) return
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionHeader(stringResource(R.string.shell_section_pharmacogenomics))
            for ((gene, rows) in hits.groupBy { it.gene }) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(gene, style = MaterialTheme.typography.titleSmall)
                    for (hit in rows) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(hit.phenotypeEffects, style = MaterialTheme.typography.bodySmall)
                            SourceLine(hit.sourceSlug, hit.doi, hit.pmid)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The CYP2D6 section: the pharmacogenetics rows for one gene, called out on its own.
 *
 * Upstream gives CYP2D6 its own section because it is the gene that most often changes an answer at the doses
 * people take, and a reader should not have to scan a list to find it. It reads from the **same** call as the
 * pharmacogenomics card, filtered — two reads would let the two sections disagree about the same gene.
 */
@Composable
fun Cyp2d6Card(hits: List<PharmacogeneticHit>) {
    val rows = hits.filter { it.gene.equals("CYP2D6", ignoreCase = true) }
    if (rows.isEmpty()) return
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader(stringResource(R.string.shell_section_cyp2d6))
            for (hit in rows) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(hit.phenotypeEffects, style = MaterialTheme.typography.bodyMedium)
                    SourceLine(hit.sourceSlug, hit.doi, hit.pmid)
                }
            }
        }
    }
}

/** A section's title, in the one style every detail section uses. */
@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall)
}

/**
 * The attribution line under a sourced statement.
 *
 * The source slug always, and whichever citation identifier the catalogue carries. Both are shown when both exist
 * because they resolve different things — a DOI opens the paper and a PMID opens the abstract — and a reader with
 * one paywalled benefits from the other.
 */
@Composable
internal fun SourceLine(sourceSlug: String, doi: String?, pmid: Int?) {
    val parts = listOfNotNull(
        sourceSlug.takeIf { it.isNotBlank() },
        doi?.takeIf { it.isNotBlank() },
        pmid?.let { "PMID $it" },
    )
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = PiruTheme.colors.secondaryLabel,
    )
}
