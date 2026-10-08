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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.model.Combination
import glass.kagerou.piru.model.CompoundDisplayClass
import glass.kagerou.piru.model.MechanismOfAction
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.model.DurationRange
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.labels.CoreLabels
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    // The user's answer to "how much detail?", which until now nothing could read back.
    // Composition, not data: the same substance is a short card at the casual tier and a
    // reference page at the curious one, which is the whole point of asking.
    val tier = remember(name) { app.profile().disclosureTier() }

    LaunchedEffect(name) {
        // Off the main thread. `catalog()` copies and verifies an 18 MB asset on first run
        // and builds the whole identity index; every other caller wraps it, and this one
        // used to run it inline in a `LaunchedEffect`, which is the composition thread — so
        // a cold push from the library's own search results blocked the first frame.
        val catalog = withContext(Dispatchers.IO) { app.catalog() }
        // `resolveFull`, not `lookup`: this is the detail path, and the batch
        // projection deliberately omits the mechanism, the bindings and the
        // curated blobs that are the whole point of this screen.
        substance = catalog.resolveFull(name)
        failed = substance == null
    }

    val resolved = substance
    when {
        failed -> CenteredMessage(stringResource(R.string.shell_substance_not_found, name))
        resolved == null -> CenteredMessage(stringResource(R.string.shell_substance_loading))
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

            // The plausibility gate is **OTC-only**, which is what the model documents:
            // "OTC additionally requires a plausible duration — gate on
            // `Substance.durationImplausible` at the call site". Applying it to every
            // class, as this line did, silently dropped the duration card for the 32
            // substances flagged implausible outside OTC — 31 recreational and 1
            // dual-use — whose durations iOS renders. It is the same condition
            // `DoseDurationSection.swift` states:
            //
            //     !(displayClass == .otc && substance.durationImplausible)
            val showDuration = resolved.displayClass.showsDuration &&
                !(resolved.displayClass == CompoundDisplayClass.OTC && resolved.durationImplausible)
            if (showDuration) {
                item { DurationsCard(resolved) }
            }

            resolved.mechanismOfAction?.let { item { MechanismCard(it) } }

            // The reference half of the page, which the casual tier asks not to see. The
            // distinction is the reason the tier exists: "what it is and what it does" versus
            // "adds the pharmacology reference sections".
            if (tier.showsReferenceSections()) {
                if (resolved.effects.isNotEmpty()) {
                    item { EffectsCard(resolved.effects) }
                }

                for (myth in resolved.misconceptions) {
                    item { MisconceptionCard(myth.claim, myth.correction) }
                }
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
        // Hoisted: `CoreLabels.category` is a composable read and `buildString`'s
        // lambda is not composable.
        val category = CoreLabels.category(substance.category)
        Text(
            buildString {
                append(category)
                substance.tags.firstOrNull()?.let { append(" · $it") }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = PiruTheme.colors.secondaryLabel,
        )
        // The chemical identity line, when the catalog carries any of it. The
        // formula, the mass and the CAS number are the catalog's data; only the
        // two labels around them are copy.
        listOfNotNull(
            substance.formula?.let { stringResource(R.string.shell_substance_formula, it) },
            substance.molarMass?.let { stringResource(R.string.shell_substance_molar_mass, it) },
            substance.cas?.let { stringResource(R.string.shell_substance_cas, it) },
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
            SectionTitle(stringResource(R.string.shell_section_dosage))
            for (route in substance.routes.filter { it.doses.hasAnyValue }) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${CoreLabels.route(route.route)} · ${route.unit}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    TierRow(stringResource(R.string.shell_dose_tier_threshold), route.doses.threshold?.let { format(it) })
                    TierRow(stringResource(R.string.shell_dose_tier_light), route.doses.light?.let { "${format(it.start)}–${format(it.endInclusive)}" })
                    TierRow(stringResource(R.string.shell_dose_tier_common), route.doses.common?.let { "${format(it.start)}–${format(it.endInclusive)}" })
                    TierRow(stringResource(R.string.shell_dose_tier_strong), route.doses.strong?.let { "${format(it.start)}–${format(it.endInclusive)}" })
                    TierRow(stringResource(R.string.shell_dose_tier_heavy), route.doses.heavy?.let { format(it) })
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
            SectionTitle(stringResource(R.string.shell_section_dosage))
            Text(
                when (substance.displayClass) {
                    CompoundDisplayClass.MEDICAL_RX -> stringResource(R.string.shell_dose_withheld_rx)
                    else -> stringResource(R.string.shell_dose_withheld_other)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}

@Composable
private fun DurationsCard(substance: Substance) {
    // The four spans are formatted through the resources rather than concatenated:
    // "1 h 30 m" is 1 小时 30 分钟 in Chinese, and neither half reorders on its own.
    val context = LocalContext.current
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle(stringResource(R.string.shell_section_duration))
            for (route in substance.routes.filter { it.duration != null }) {
                val profile = route.duration!!
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(CoreLabels.route(route.route), style = MaterialTheme.typography.labelLarge)
                    // Read the profile's own phases rather than its cumulative boundaries.
                    //
                    // `phaseBoundaries` substitutes `0.0` for every phase the data does not
                    // carry, which is right for drawing a curve and wrong for reporting one:
                    // 80 route-groups in the shipped catalogue have `total` as their only
                    // phase, and this line told the reader "Onset 0 m · Come-up to 0 m · Peak
                    // to 0 m" about three phases that are simply absent. iOS prints an em
                    // dash for an absent phase and falls back the same way — Peak from
                    // come-up, Total from the offset phase.
                    Text(
                        stringResource(
                            R.string.shell_duration_trio,
                            phaseSpan(context, profile.onset),
                            phaseSpan(context, profile.peak ?: profile.comeup),
                            phaseSpan(context, profile.total ?: profile.offset),
                        ),
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
            SectionTitle(stringResource(R.string.shell_section_mechanism))
            if (mechanism.summary.isNotEmpty()) {
                Text(mechanism.summary, style = MaterialTheme.typography.bodyMedium)
            }
            for (binding in mechanism.bindings) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(binding.target, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        // The tier prints as words, not dots: a dot's meaning is a
                        // legend away, and this screen has no legend. Both halves are
                        // `:core:` vocabulary — `BindingAction` carries a `wireValue`
                        // and `BindingAffinity` a tier, and both are storage, which is
                        // how "reuptakeInhibitor · significant" got on screen.
                        "${CoreLabels.bindingAction(binding.action)} · " +
                            CoreLabels.bindingAffinity(binding.affinity),
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
            SectionTitle(stringResource(R.string.shell_section_effects))
            Text(effects.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MisconceptionCard(claim: String, correction: String) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.shell_substance_quote, claim),
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
            SectionTitle(stringResource(R.string.shell_section_combinations))
            for (combination in substance.combinations) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${combination.name} · ${CoreLabels.severity(combination.severity)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = when (combination.severity) {
                            Combination.Severity.DANGER -> PiruTheme.colors.dangerText
                            Combination.Severity.CAUTION -> PiruTheme.colors.cautionText
                            Combination.Severity.NOTE -> PiruTheme.colors.secondaryLabel
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
            SectionTitle(stringResource(R.string.shell_section_water_heat))
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
            stringResource(R.string.shell_not_medical_advice),
            style = MaterialTheme.typography.labelLarge,
            color = PiruTheme.colors.secondaryLabel,
        )
        if (substance.sources.isNotEmpty()) {
            Text(
                stringResource(R.string.shell_substance_sources, substance.sources.joinToString(", ")),
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

/**
 * Minutes as a readable span: "1 h 30 m", "45 m" — and 1 小时 30 分钟 in Chinese.
 *
 * A `Context` rather than a `@Composable` read: it is called four times inside one
 * `stringResource` argument list, where a nested composable call would be legal
 * but would read four resources to build one sentence.
 */
/**
 * A phase's span, or an em dash when the profile does not carry that phase.
 *
 * Null rather than `0.0` is the distinction that matters: a phase the data omits is not a
 * phase of zero length, and printing one as the other states something about the substance
 * that the catalogue does not say.
 */
@Composable
private fun phaseSpan(context: android.content.Context, range: DurationRange?): String =
    if (range == null) {
        stringResource(R.string.shell_duration_absent)
    } else {
        minutes(context, range.midpoint)
    }

private fun minutes(context: android.content.Context, value: Double): String {
    val total = value.toLong()
    val hours = total / 60
    val mins = total % 60
    return when {
        hours > 0 && mins > 0 -> context.getString(R.string.shell_duration_hours_minutes, hours, mins)
        hours > 0 -> context.getString(R.string.shell_duration_hours, hours)
        else -> context.getString(R.string.shell_duration_minutes, mins)
    }
}
