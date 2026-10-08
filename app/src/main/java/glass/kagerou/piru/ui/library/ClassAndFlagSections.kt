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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.substance.SubstanceReader
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import androidx.annotation.StringRes

/**
 * Two section cards that were readable in the catalogue and shown nowhere:
 * the class a substance belongs to, and the flags it carries.
 *
 * ## Why the class background is a section of its own
 * `class_contexts` has 50 rows of authored prose and **674 of 1689** substances belong to one — 169 of those
 * reachable through `resolveFull`. The four columns are what the class's members have **in common**, which is the
 * point: a reader arriving at an unfamiliar member benefits more from "what this family does" than from a fifth
 * restatement of the individual's own mechanism.
 *
 * ## Why the flags are worth more than their row count
 * `substance_flags` is 13 rows, and two of its three flags are **harm-reduction facts rather than metadata**:
 * `suppresses-serotonin-synthesis` (MDMA, MDA, MBDB, MDOH, Mdea) and `missold-as-mdma` (three cathinones sold as
 * MDMA). Those are the kind of thing a user needs before rather than after, and the port could answer
 * `hasFlag(flag, id)` for any flag and showed none of them.
 */

/**
 * The class a substance belongs to: what its whole family shares.
 *
 * The four prose columns are drawn in a fixed order — mechanism, pharmacokinetics, safety, then SAR — because a
 * reader comparing two substances of the same class should find the same paragraph in the same place. Any column
 * the catalogue leaves null is skipped rather than shown empty; 49 of the 50 rows carry each of the four.
 */
@Composable
fun ClassContextCard(
    context: SubstanceReader.ClassContext,
    onOpenClass: () -> Unit,
) {
    // A class whose four shared fields are all null has nothing to read, and the section hides. This is the
    // reader's own posture, restated here so an empty card cannot be drawn from data the resolver declined.
    val hasProse = listOf(
        context.sharedMechanism,
        context.sharedPharmacokinetics,
        context.sharedSafety,
        context.sarSummary,
    ).any { !it.isNullOrBlank() }
    if (!hasProse) return

    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.shell_section_class_background, context.title),
                style = MaterialTheme.typography.titleSmall,
            )
            val subtitle = context.subtitle
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }

            // The shared properties, in a fixed order so the same paragraph is in the same place across a class.
            //
            // These four labels already existed for the class write-up page, reachable since BUG #28 was fixed.
            // My first version added near-duplicates of five of them and AAPT rejected the build for duplicate
            // resources, so this uses the page's own labels rather than a second set.
            ClassProse(stringResource(R.string.shell_class_mechanism), context.sharedMechanism)
            ClassProse(stringResource(R.string.shell_class_pharmacokinetics), context.sharedPharmacokinetics)
            ClassProse(stringResource(R.string.shell_class_safety), context.sharedSafety)
            ClassProse(stringResource(R.string.shell_class_sar), context.sarSummary)

            // The class's own write-up, which carries the full text and its citations. A section card is a
            // summary of a page, and the page is one tap away rather than duplicated here.
            if (context.slug.isNotBlank()) {
                TextButton(onClick = onOpenClass) {
                    Text(
                        stringResource(R.string.shell_class_open),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/** One shared-property paragraph, skipped when the catalogue has none. */
@Composable
private fun ClassProse(label: String, body: String?) {
    if (body.isNullOrBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = PiruTheme.colors.secondaryLabel,
        )
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The flags a substance carries.
 *
 * ## Why two of them are shown as cautions
 * `suppresses-serotonin-synthesis` and `missold-as-mdma` are findings a user needs rather than metadata about the
 * catalogue, so they are drawn in the accent colour while `model-calibrated` — which describes how the pipeline
 * produced the numbers — is not. The distinction is [StatusFlags.isHarmReduction], which is tested.
 *
 * ## Why the raw flag is shown beside the sentence
 * The flag is the identifier the export carries and the term other tools use, so a reader who has seen it
 * elsewhere can match it here. The sentence is what makes it readable; neither alone is enough.
 */
@Composable
fun StatusFlagsCard(flags: List<SubstanceReader.SubstanceFlagRow>) {
    if (flags.isEmpty()) return
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.shell_section_flags), style = MaterialTheme.typography.titleSmall)
            for (row in flags) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        // The sentence when this build has one, and the raw identifier otherwise — which is the
                        // term other tools and the export use, so it is readable without being an invention.
                        StatusFlags.sentenceRes(row.flag)?.let { stringResource(it) } ?: row.flag,
                        style = MaterialTheme.typography.bodyMedium,
                        // The harm-reduction flags carry the accent; a provenance flag does not, because
                        // colouring it would put a caution on a note about how a number was produced.
                        color = if (StatusFlags.isHarmReduction(row.flag)) {
                            PiruTheme.colors.accent
                        } else {
                            PiruTheme.colors.secondaryLabel
                        },
                    )
                    // The catalogue's own note, when it carries one. Five of the thirteen rows have none, and the
                    // sentence above is then the whole reading.
                    val notes = row.notes
                    if (!notes.isNullOrBlank()) {
                        Text(
                            notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                    Text(
                        row.flag,
                        style = MaterialTheme.typography.labelSmall,
                        color = PiruTheme.colors.tertiaryLabel,
                    )
                }
            }
        }
    }
}

/**
 * What the flags mean, and which of them are cautions.
 *
 * Separate from the card so the mapping is testable: a flag the catalogue adds would otherwise render as a raw
 * identifier with no sentence, and nothing would say so.
 *
 * ## The sentences are resource ids
 * My first version returned the English text from here, which is the hardcoded-content-language defect — in a file
 * added in the same objective that fixed it, and the second time in this area (`dayTitle` in the journal was the
 * first). [sentenceRes] returns an id and the card resolves it, so the mapping stays testable and the text stays
 * localisable.
 *
 * A flag this build does not know gets **no** resource. That is the honest answer — the reader sees the raw
 * identifier, which is the term other tools use — and it is asserted, because a fallback sentence would be a
 * fabricated explanation of a flag nobody here has read.
 */
internal object StatusFlags {

    /** The flags that are findings about the substance rather than about the catalogue's own process. */
    private val HARM_REDUCTION = setOf(
        "suppresses-serotonin-synthesis",
        "missold-as-mdma",
    )

    /** The flags this build has a sentence for. */
    private val SENTENCES: Map<String, Int> = mapOf(
        "suppresses-serotonin-synthesis" to R.string.shell_flag_suppresses_serotonin,
        "missold-as-mdma" to R.string.shell_flag_missold_as_mdma,
        "model-calibrated" to R.string.shell_flag_model_calibrated,
    )

    /**
     * Whether a flag is a caution.
     *
     * Matched on the exact identifier rather than on a substring: `missold-as-mdma` and a hypothetical
     * `mdma-like` would both contain "mdma", and treating the second as a caution would be a quiet false alarm.
     */
    fun isHarmReduction(flag: String): Boolean = flag in HARM_REDUCTION

    /** The sentence's resource, or null when this build does not know the flag. */
    @StringRes
    fun sentenceRes(flag: String): Int? = SENTENCES[flag]
}
