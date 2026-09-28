package glass.kagerou.piru.ui.tools

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.engine.OpioidConvertibility
import glass.kagerou.piru.model.doseFormatted
import glass.kagerou.piru.substance.SubstanceReader
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import glass.kagerou.piru.PiruApplication

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
                Text("Equivalence", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Published factors for reading one substance's amount in another's " +
                        "units. Reference figures, not a dose calculator.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        item {
            SectionHeading("Opioid MME")
        }
        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Verbatim from the reference table's own header.
                    Text(
                        "Published oral morphine milligram equivalent (MME) factors compare " +
                            "amounts across opioids. They must not be used to choose a " +
                            "replacement dose when switching medications.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "CDC 2022 reference factors. Individual response varies.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        if (loaded && opioids.isEmpty()) {
            item { EmptyState("No MME factors are readable from the catalog on this build.") }
        }

        items(opioids, key = { it.name }) { entry ->
            OpioidRow(entry)
        }

        item {
            SectionHeading("Diazepam equivalence", modifier = Modifier.padding(top = 8.dp))
        }
        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Published equivalences are approximate and vary between sources. " +
                            "A prescriber must assess any medication change.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (loaded && benzos.isEmpty()) {
            item { EmptyState("No cited diazepam equivalences are readable from the catalog on this build.") }
        }

        items(benzos, key = { it.name }) { entry ->
            BenzoRow(entry)
        }

        item {
            Text(
                "Both tables are published references, not a model output. Not medical advice.",
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
                        "${doseFormatted(factor)} MME per mg",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
            unconvertibleReason(entry.convertibility)?.let { reason ->
                Text(
                    reason,
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
private fun pickerLabel(entry: SubstanceReader.OpioidMmeRowEntry): String =
    if (entry.convertibility == OpioidConvertibility.TRANSDERMAL) {
        "${entry.displayName} (transdermal)"
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
 */
private fun unconvertibleReason(convertibility: OpioidConvertibility): String? = when (convertibility) {
    OpioidConvertibility.LINEAR -> null
    OpioidConvertibility.NONLINEAR ->
        "Methadone's half-life is long and variable, and its effect on breathing peaks " +
            "later than its pain relief. CDC publishes a single population factor for it; " +
            "Piru shows no figure."
    OpioidConvertibility.TRANSDERMAL ->
        "Transdermal fentanyl is dosed in micrograms per hour, a rate rather than a mass, " +
            "so it has no figure in this mg-based table."
    OpioidConvertibility.EXCLUDED ->
        "CDC excludes buprenorphine from MME."
}

@Composable
private fun BenzoRow(entry: SubstanceReader.BenzoEquivalentEntry) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.displayName, style = MaterialTheme.typography.titleSmall)
            entry.equivalent.displayText?.let { reference ->
                Text(reference, style = MaterialTheme.typography.bodyMedium)
            }
            // The attribution is on every row rather than once at the top: a
            // number copied out of a list carries no header with it.
            Text(
                "Ashton Manual, Table 1",
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
