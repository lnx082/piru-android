package glass.kagerou.piru.ui.tools

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.engine.OpioidConvertibility
import glass.kagerou.piru.model.doseFormatted
import glass.kagerou.piru.substance.SubstanceReader
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.substance.BenzoEquivalence

/**
 * The two published equivalence tables: oral morphine-milligram equivalents for
 * opioids, and the Ashton diazepam equivalents for benzodiazepines.
 *
 * Ported from `Views/Tools/Equivalence/OpioidReferenceView.swift` (43 lines) and
 * `DiazepamReferenceView.swift` (37 lines). Upstream pushes them as two screens
 * from the hub; this build renders both in one, because the two tables answer one
 * question — "what is this amount, in the unit everyone else uses" — and a reader
 * who has one open usually wants the other.
 *
 * ## Both tables are reference, not conversion
 * Neither screen takes a dose and returns a dose. That is deliberate and it is
 * the whole safety posture of this tool: mixing a milligram of one opioid with a
 * milligram of another is exactly the arithmetic nobody should do by hand from a
 * table, and a screen that did it for them would be answering with a number the
 * user would then act on. The published factor is shown; the multiplication is not.
 *
 * ## The un-convertible opioids are the point, not an omission
 * Methadone, transdermal fentanyl and buprenorphine sit in the table with no
 * figure and a sentence saying why. A table that quietly dropped them would read
 * as "Piru has nothing on methadone", when the answer is that a single
 * population factor is the wrong tool for it — see [SubstanceReader.opioidMmeTable].
 */
@Composable
fun EquivalenceScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as PiruApplication }
    var opioids by remember { mutableStateOf<List<SubstanceReader.OpioidMmeRowEntry>>(emptyList()) }
    var benzos by remember { mutableStateOf<List<SubstanceReader.BenzoEquivalentEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Alphabetical, as upstream sorts them in the view rather than by the
        // query's curated rank: a reader arrives knowing the name they want.
        val catalog = app.catalog()
        opioids = catalog.opioidMmeTable().sortedBy { it.displayName.lowercase() }
        // Cited rows only. The flag is the pipeline's record that the shipped
        // number agrees with the table it is attributed to, and nine of the
        // thirty-two rows have no table behind them at all.
        benzos = catalog.diazepamEquivalents()
            .filter { it.equivalent.isCited }
            .sortedBy { it.displayName.lowercase() }
        loaded = true
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.equivalence_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.equivalence_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        item {
            SectionHeading(stringResource(R.string.equivalence_opioid_heading))
        }
        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Verbatim from the reference table's own header.
                    Text(
                        stringResource(R.string.equivalence_opioid_note),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.equivalence_opioid_source),
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        if (loaded && opioids.isEmpty()) {
            item { EmptyState(stringResource(R.string.equivalence_opioid_empty)) }
        }

        items(opioids, key = { it.name }) { entry ->
            OpioidRow(entry)
        }

        item {
            SectionHeading(
                stringResource(R.string.equivalence_benzo_heading),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.equivalence_benzo_note),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (loaded && benzos.isEmpty()) {
            item { EmptyState(stringResource(R.string.equivalence_benzo_empty)) }
        }

        items(benzos, key = { it.name }) { entry ->
            BenzoRow(entry)
        }

        item {
            // The disclaimer stays English — see the note in IdentifyScreen.
            Text(
                stringResource(R.string.equivalence_footer) + " Not medical advice.",
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun OpioidRow(entry: SubstanceReader.OpioidMmeRowEntry) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(pickerLabel(entry), style = MaterialTheme.typography.titleSmall)
                entry.mmePerMg?.let { factor ->
                    Text(
                        stringResource(R.string.equivalence_mme_per_mg, doseFormatted(factor)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
            unconvertibleReason(entry.convertibility)?.let { reason ->
                Text(
                    stringResource(reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
    }
}

/**
 * The name the table lists a row under.
 *
 * Transdermal fentanyl shares one substance row with every other fentanyl
 * route, so the route it is dosed by is what the label has to disambiguate —
 * otherwise the row reads as an oral factor that does not exist.
 */
@Composable
private fun pickerLabel(entry: SubstanceReader.OpioidMmeRowEntry): String =
    if (entry.convertibility == OpioidConvertibility.TRANSDERMAL) {
        stringResource(R.string.equivalence_transdermal_label, entry.displayName)
    } else {
        entry.displayName
    }

/**
 * Why this opioid carries no figure, verbatim from
 * `OpioidEquivalence.unconvertibleReason`.
 *
 * UI copy rather than a database column on purpose: it is a sentence the reader
 * sees, so it is written once here where it can be translated, and a row can
 * never ship an untranslated reason. Null for a linear row, which is shown with
 * its factor instead.
 *
 * A resource id rather than the sentence itself: it is resolved at the call
 * site, which is the only place a `stringResource` read can happen.
 */
@StringRes
private fun unconvertibleReason(convertibility: OpioidConvertibility): Int? = when (convertibility) {
    OpioidConvertibility.LINEAR -> null
    OpioidConvertibility.NONLINEAR -> R.string.equivalence_unconvertible_methadone
    OpioidConvertibility.TRANSDERMAL -> R.string.equivalence_unconvertible_fentanyl
    OpioidConvertibility.EXCLUDED -> R.string.equivalence_unconvertible_buprenorphine
}

@Composable
private fun BenzoRow(entry: SubstanceReader.BenzoEquivalentEntry) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.displayName, style = MaterialTheme.typography.titleSmall)

            // The ratio, which this row was missing: it printed the dataset's sentence and never a converted figure,
            // so the converter listed equivalences without converting anything. `ratio` is the tested form of the same
            // arithmetic the entry carries — see `BenzoEquivalenceTest` for the case that keeps the two agreeing.
            val ratio = BenzoEquivalence.ratio(entry.equivalent)
            if (ratio != null) {
                Text(
                    stringResource(
                        R.string.equivalence_ratio,
                        entry.displayName,
                        ratioText(ratio.diazepamPerMg),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                // A row whose prose did not parse says so, rather than showing nothing: a blank line under a heading
                // reads as a failure of the screen rather than of the source data.
                Text(
                    stringResource(R.string.equivalence_ratio_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }

            entry.equivalent.displayText?.let { reference ->
                Text(reference, style = MaterialTheme.typography.bodySmall)
            }
            // The attribution is on every row rather than once at the top: a
            // number copied out of a list carries no header with it.
            Text(
                stringResource(R.string.equivalence_ashton_source),
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}

@Composable
private fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(top = 4.dp),
    )
}

@Composable
private fun EmptyState(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = PiruTheme.colors.secondaryLabel,
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}

/**
 * A ratio as a reader should see it: `20`, `0.5`, `2.4`.
 *
 * At most one decimal, because the dataset's figures are curated to that precision and printing `19.60` would claim two
 * figures the source never stated. `Locale.ROOT` because the number sits inside a sentence.
 */
private fun ratioText(value: Double): String = when {
    value == value.toLong().toDouble() -> value.toLong().toString()
    else -> String.format(java.util.Locale.ROOT, "%.1f", value)
}
