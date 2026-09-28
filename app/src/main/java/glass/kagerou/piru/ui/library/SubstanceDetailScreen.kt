package glass.kagerou.piru.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.model.CompoundDisplayClass
import glass.kagerou.piru.model.MechanismOfAction
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * A substance's full record.
 *
 * Ported from `Library/SubstanceDetail/` — forty files and 8,742 lines, of which
 * this draws the sections whose data the read layer already resolves: the
 * header, the dose ladder, the durations, the mechanism with its receptor
 * bindings, the effects, and the curated corrections and combinations. Not here:
 * the chemistry card's descriptors, the receptor *literature* disclosure, the
 * brand-formulation chips, the concentration-effects table, and the peptide
 * reconstitution calculator — each is its own screenful over data that
 * `resolveFull` already returns.
 *
 * ## The one rule this screen must not get wrong
 * Whether a **dose ladder may be shown at all** is the catalog's decision, not
 * this screen's, and it is [CompoundDisplayClass.showsDoseLadder]. A prescription
 * medication with no recreational frame gets its mechanism and its warnings and
 * **no numbers** — that is the app declining to be a dosing reference for a
 * doctor's drug, and it is exactly the kind of gate a UI port drops by accident
 * because the data is right there in the model.
 */
@Composable
fun SubstanceDetailScreen(name: String, navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    var substance by remember(name) { mutableStateOf<Substance?>(null) }
    var failed by remember(name) { mutableStateOf(false) }

    LaunchedEffect(name) {
        val catalog = app.catalog()
        // `resolveFull`, not `lookup`: this is the detail path, and the batch
        // projection deliberately omits the mechanism, the bindings and the
        // curated blobs that are the whole point of this screen.
        substance = catalog.resolveFull(name)
        failed = substance == null
    }

    val resolved = substance
    when {
        failed -> CenteredMessage("No entry for \"$name\".")
        resolved == null -> CenteredMessage("Loading…")
        else -> LazyColumn(
            modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = FAB_CLEARANCE),
        ) {
            item { Header(resolved) }

            if (resolved.displayClass.showsDoseLadder) {
                item { DoseLadderCard(resolved) }
            } else {
                item { WithheldCard(resolved) }
            }

            if (resolved.displayClass.showsDuration && !resolved.durationImplausible) {
                item { DurationsCard(resolved) }
            }

            resolved.mechanismOfAction?.let { item { MechanismCard(it) } }

            if (resolved.effects.isNotEmpty()) {
                item { EffectsCard(resolved.effects) }
            }

            for (myth in resolved.misconceptions) {
                item { MisconceptionCard(myth.claim, myth.correction) }
            }

            if (resolved.combinations.isNotEmpty()) {
                item { CombinationsCard(resolved) }
            }

            resolved.waterHeat?.let { item { WaterHeatCard(it.headline, it.body) } }

            item { Footer(resolved) }
        }
    }
}

@Composable
private fun Header(substance: Substance) {
    Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val (title, pictograph) = substance.titleAndPictograph
        Text(
            if (pictograph != null) "$pictograph $title" else title,
            style = MaterialTheme.typography.headlineSmall,
        )
        if (substance.displayTitle != substance.name) {
            Text(
                substance.name,
                style = MaterialTheme.typography.bodyMedium,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
        Text(
            buildString {
                append(substance.category.wireValue)
                substance.tags.firstOrNull()?.let { append(" · $it") }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = PiruTheme.colors.secondaryLabel,
        )
        // The chemical identity line, when the catalog carries any of it.
        listOfNotNull(
            substance.formula?.let { "Formula $it" },
            substance.molarMass?.let { "%.2f g/mol".format(it) },
            substance.cas?.let { "CAS $it" },
        ).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = PiruTheme.colors.secondaryLabel)
        }
    }
}

/**
 * The dose ladder, per route, with the published tiers where they exist.
 *
 * A tier the catalog does not carry prints as a dash rather than as a zero: an
 * absent bound is the absence of a claim, and `0 mg` would be one.
 */
@Composable
private fun DoseLadderCard(substance: Substance) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Dosage")
            for (route in substance.routes.filter { it.doses.hasAnyValue }) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${route.route.displayName} · ${route.unit}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    TierRow("Threshold", route.doses.threshold?.let { format(it) })
                    TierRow("Light", route.doses.light?.let { "${format(it.start)}–${format(it.endInclusive)}" })
                    TierRow("Common", route.doses.common?.let { "${format(it.start)}–${format(it.endInclusive)}" })
                    TierRow("Strong", route.doses.strong?.let { "${format(it.start)}–${format(it.endInclusive)}" })
                    TierRow("Heavy", route.doses.heavy?.let { format(it) })
                }
                if (route != substance.routes.last()) HorizontalDivider()
            }
        }
    }
}

@Composable
private fun TierRow(label: String, value: String?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = PiruTheme.colors.secondaryLabel)
        Text(value ?: "—", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WithheldCard(substance: Substance) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("Dosage")
            Text(
                when (substance.displayClass) {
                    CompoundDisplayClass.MEDICAL_RX ->
                        "This is a prescription medicine. Piru shows what it does and what to watch " +
                            "for, and leaves the numbers to the label your prescriber gave you."
                    else ->
                        "The catalog carries no recreational dosing frame for this compound, so none " +
                            "is shown."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}

@Composable
private fun DurationsCard(substance: Substance) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Duration")
            for (route in substance.routes.filter { it.duration != null }) {
                val profile = route.duration!!
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(route.route.displayName, style = MaterialTheme.typography.labelLarge)
                    val boundaries = profile.phaseBoundaries
                    Text(
                        "Onset ${minutes(boundaries.onsetEnd)} · Come-up to ${minutes(boundaries.comeupEnd)} · " +
                            "Peak to ${minutes(boundaries.peakEnd)} · Total ${minutes(profile.estimatedTotalMinutes)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun MechanismCard(mechanism: MechanismOfAction) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Mechanism")
            if (mechanism.summary.isNotEmpty()) {
                Text(mechanism.summary, style = MaterialTheme.typography.bodyMedium)
            }
            for (binding in mechanism.bindings) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(binding.target, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        // The tier prints as words, not dots: a dot's meaning is a
                        // legend away, and this screen has no legend.
                        "${binding.action.wireValue} · ${binding.affinity.name.lowercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }
    }
}

@Composable
private fun EffectsCard(effects: List<String>) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("Effects")
            Text(effects.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MisconceptionCard(claim: String, correction: String) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "“$claim”",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = PiruTheme.colors.secondaryLabel,
            )
            Text(correction, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CombinationsCard(substance: Substance) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Combinations")
            for (combination in substance.combinations) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${combination.name} · ${combination.severity.wireValue}",
                        style = MaterialTheme.typography.labelLarge,
                        color = when (combination.severity) {
                            glass.kagerou.piru.model.Combination.Severity.DANGER -> PiruTheme.colors.dangerText
                            glass.kagerou.piru.model.Combination.Severity.CAUTION -> PiruTheme.colors.cautionText
                            glass.kagerou.piru.model.Combination.Severity.NOTE -> PiruTheme.colors.secondaryLabel
                        },
                    )
                    Text(combination.description, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun WaterHeatCard(headline: String, body: String) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("Water & heat")
            Text(headline, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Attribution and the standing disclaimer.
 *
 * "Not medical advice" is required to stay prominent, and it is the reason this
 * footer exists on every substance rather than only on an about screen.
 */
@Composable
private fun Footer(substance: Substance) {
    Column(modifier = Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Not medical advice.",
            style = MaterialTheme.typography.labelLarge,
            color = PiruTheme.colors.secondaryLabel,
        )
        if (substance.sources.isNotEmpty()) {
            Text(
                "Sources: " + substance.sources.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun CenteredMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = PiruTheme.colors.secondaryLabel)
    }
}

/**
 * A dose, trimmed.
 *
 * Integral values print without a decimal point — "80 mg", not "80.0 mg" — which
 * is how every source writes them, and the fourth significant figure of a
 * milligram is not information anybody has.
 */
private fun format(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else "%.2f".format(value).trimEnd('0').trimEnd('.')

/** Minutes as a readable span: "1 h 30 m", "45 m". */
private fun minutes(value: Double): String {
    val total = value.toLong()
    val hours = total / 60
    val mins = total % 60
    return when {
        hours > 0 && mins > 0 -> "${hours} h ${mins} m"
        hours > 0 -> "${hours} h"
        else -> "${mins} m"
    }
}
