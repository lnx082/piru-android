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
import androidx.compose.material3.TextButton
import glass.kagerou.piru.ui.nav.PushRoute
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.ui.theme.toComposeColor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import glass.kagerou.piru.data.entity.InventoryItemEntity
import glass.kagerou.piru.engine.SubstanceCatalog
import java.time.Instant
import androidx.compose.foundation.layout.width
import glass.kagerou.piru.engine.BindingHit
import glass.kagerou.piru.engine.MetabolismHit
import glass.kagerou.piru.engine.DownstreamSignallingHit
import glass.kagerou.piru.engine.OffTargetHit
import glass.kagerou.piru.engine.PharmacogeneticHit

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

    /**
     * This substance's own colour.
     *
     * Read from the palette so a colour the user set on the substance-colours screen is what the page
     * shows, and so the page matches the dot the library list drew to get here.
     */
    var tint by remember(name) { mutableStateOf(P3Color.NEUTRAL) }
    var failed by remember(name) { mutableStateOf(false) }

    /** The user's inventory row for this substance, or null when they have none. */
    var inventory by remember(name) { mutableStateOf<InventoryItemEntity?>(null) }

    /**
     * The names of substances still inside their recorded duration, from the user's log.
     *
     * Excludes this substance: the page is already about it, and "ketamine, ketamine" is not a fact.
     */
    var activeNow by remember(name) { mutableStateOf<List<String>>(emptyList()) }

    /**
     * The receptor-affinity measurements and the clearance table.
     *
     * Held as the engine's own row types rather than folded onto `Substance`, because `:core:model` cannot
     * see `:core:engine` — the dependency runs the other way, so the fields would be a cycle. They come
     * through the catalogue's accessors, which is the pattern `classContexts` and `effectGroups` already
     * use.
     */
    var bindings by remember(name) { mutableStateOf<List<BindingHit>>(emptyList()) }
    var signalling by remember { mutableStateOf<List<DownstreamSignallingHit>>(emptyList()) }
    var offTargets by remember { mutableStateOf<List<OffTargetHit>>(emptyList()) }
    var pharmacogenetics by remember { mutableStateOf<List<PharmacogeneticHit>>(emptyList()) }
    var metabolism by remember(name) { mutableStateOf<List<MetabolismHit>>(emptyList()) }

    // The user's answer to "how much detail?", which until now nothing could read back.
    // Composition, not data: the same substance is a short card at the casual tier and a
    // reference page at the curious one, which is the whole point of asking.
    val tier = remember(name) { app.profile().disclosureTier() }

    LaunchedEffect(name) {
        // The palette keys on the canonical name, which is not known until the catalogue answers.
        tint = runCatching {
            val catalogue = app.catalog()
            val tints = app.palette().tintsFor(listOf(catalogue.lookup(name)?.name ?: name))
            tints.values.firstOrNull() ?: P3Color.NEUTRAL
        }.getOrDefault(P3Color.NEUTRAL)
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

        // The inventory row for this substance, if the user has any. One indexed lookup by
        // `(substance, saltForm)`, which the item DAO has always had and nothing asked — the substance
        // page is where a reader who holds a stash looks for the number.
        inventory = runCatching {
            app.database.inventoryDao().byIdentity(catalog.lookup(name)?.name ?: name, null)
        }.getOrNull()

        // What else has not cleared, from the user's own log. Derived here rather than in the card so
        // the window arithmetic — which needs the catalogue, the log and the current time — lives in one
        // place; the card draws a list of names and nothing more.
        activeNow = runCatching { activeSubstanceNames(app, catalog, name) }.getOrDefault(emptyList())

        // The receptor literature and the clearance table. Resolved on the canonical name, because an
        // alias would miss a join keyed on the substance row.
        val canonical = catalog.lookup(name)?.name ?: name
        bindings = runCatching { catalog.bindingRows(canonical) }.getOrDefault(emptyList())
        // Three tables this port shipped and never read: `downstream_signalling` (678 substances),
        // `off_targets` (165) and `pharmacogenetics` (169).
        signalling = runCatching { catalog.downstreamSignallingRows(canonical) }.getOrDefault(emptyList())
        offTargets = runCatching { catalog.offTargetRows(canonical) }.getOrDefault(emptyList())
        pharmacogenetics = runCatching { catalog.pharmacogeneticRows(canonical) }.getOrDefault(emptyList())
        metabolism = runCatching { catalog.metabolismRows(canonical) }.getOrDefault(emptyList())
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
            item { Header(resolved, tint, navigator) }

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
                    item {
                    EffectsCard(
                        effects = resolved.effects,
                        substanceName = resolved.name,
                        navigator = navigator,
                    )
                }
                }

                for (myth in resolved.misconceptions) {
                    item { MisconceptionCard(myth.claim, myth.correction) }
                }
            }

            if (resolved.combinations.isNotEmpty()) {
                item { CombinationsCard(resolved) }
            }

            // The sections below were all populated by the catalogue and read by nothing: the page
            // showed a substance's chemistry only as a formula line, and its identity only as an
            // alias list that existed for searching. Each card owns its own presence check, so a
            // substance the catalogue does not describe loses a card rather than gaining an empty one.
            resolved.overview?.let { item { OverviewCard(it) } }

            resolved.toleranceInfo?.let { item { ToleranceCard(it) } }

            resolved.peptideProfile?.let { item { PeptideCard(it) } }

            resolved.physicochemical?.let { item { PhysicochemicalCard(it) } }

            item { IdentityCard(resolved) }

            resolved.halfLifeMinutes?.let { item { HalfLifeCard(it) } }

            item {
                FormsCard(
                    availableSaltForms = resolved.availableSaltForms,
                    availableIsomers = resolved.availableIsomers,
                )
            }

            item { ReferencesCard(resolved.references) }

            // The pharmacology sections, beside the binding and metabolism tables they extend. Signalling is
            // what happens *after* the receptor, off-targets are what the substance hits besides its mechanism,
            // and the two genetic sections read the same rows.
            item { DownstreamSignallingCard(signalling) }
            item { OffTargetCard(offTargets) }
            // CYP2D6 first, because it is the gene that most often changes an answer at the doses people take
            // and a reader should not have to scan the full list to find it.
            item { Cyp2d6Card(pharmacogenetics) }
            item { PharmacogeneticsCard(pharmacogenetics) }
            item { BindingTableCard(bindings) }

            item { MetabolismCard(metabolism) }

            // The two sections that describe the *user* rather than the compound. Both take a value
            // rather than reading one, so neither card needs the store or the catalogue.
            inventory?.let { item { InventoryCard(it) } }

            item { AlsoActiveCard(activeNow) }

            // Says why the dose card is missing, where the page would otherwise just be missing its
            // most important section with no explanation.
            if (resolved.hasNoDoseData) {
                item { LimitedDataCard() }
            }

            resolved.waterHeat?.let { item { WaterHeatCard(it.headline, it.body) } }

            item { Footer(resolved) }
        }
    }
}

@Composable
private fun Header(substance: Substance, tint: P3Color, navigator: AppNavigator) {
    Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val (title, pictograph) = substance.titleAndPictograph
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // A bar rather than a dot: the header is the one place with room for the colour to be a
            // recognisable mark rather than an accent. It is the same colour the library list drew to
            // get here, because both read the palette.
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 26.dp)
                    .background(color = tint.toComposeColor(), shape = RoundedCornerShape(2.dp)),
            )
            Text(
                if (pictograph != null) "$pictograph $title" else title,
                style = MaterialTheme.typography.headlineSmall,
            )
        }
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
        // The category, linked to the class write-up when the catalogue places this substance in one.
        // `classContextSlug` is the curated mapping; a substance outside every class keeps plain text
        // rather than a link to a screen that would answer "not in the catalogue".
        val classSlug = substance.classContextSlug
        if (classSlug != null) {
            Text(
                category,
                style = MaterialTheme.typography.bodyMedium,
                color = PiruTheme.colors.accent,
                modifier = Modifier.clickable {
                    navigator.push(PushRoute.DrugClass(classSlug))
                },
            )
        } else {
            Text(
                category,
                style = MaterialTheme.typography.bodyMedium,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
        // Every tag as its own link. This used to be `tags.firstOrNull()` appended to the category
        // with a separator — one arbitrary tag out of however many the catalogue curated, shown as
        // decoration, and the other tags invisible.
        if (substance.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (tag in substance.tags) {
                    Text(
                        tag,
                        style = MaterialTheme.typography.labelMedium,
                        color = PiruTheme.colors.accent,
                        modifier = Modifier
                            .clickable { navigator.push(PushRoute.LibraryTag(tag)) }
                            .padding(vertical = 2.dp),
                    )
                }
            }
        }
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
            // The prose detail, which had no reader: the card drew the one-line summary and then the
            // binding table, so the catalogue's own explanation of *how* was dropped. It is the same
            // field upstream's card body carries.
            if (mechanism.description.isNotEmpty() && mechanism.description != mechanism.summary) {
                Text(
                    mechanism.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
            // The targets, named above the table. `effectivePrimaryTargets` falls back to the bindings'
            // own targets when the curated list is empty, which is upstream's invariant, so a substance
            // with bindings always gets a target line rather than an empty one.
            val targets = mechanism.effectivePrimaryTargets
            if (targets.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.shell_descriptor_primary_targets),
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                        modifier = Modifier.width(112.dp),
                    )
                    Text(
                        targets.joinToString(", "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
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
private fun EffectsCard(
    effects: List<String>,
    substanceName: String,
    navigator: AppNavigator,
) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle(stringResource(R.string.shell_section_effects))
            // A preview, not the whole list. It used to be `joinToString(" · ")` over the entire
            // union, which for the heaviest substances is forty-odd labels in one paragraph — a wall
            // with no way to tell a come-up effect from a side effect. The full page groups them.
            Text(
                effects.take(EFFECT_PREVIEW_COUNT).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (effects.size > EFFECT_PREVIEW_COUNT) {
                TextButton(onClick = { navigator.push(PushRoute.Effects(substanceName)) }) {
                    Text(stringResource(R.string.shell_effects_all_open, effects.size))
                }
            }
        }
    }
}

/**
 * How many labels the substance page previews.
 *
 * Enough to characterise the substance at a glance, few enough to stay one line or two on a phone.
 */
private const val EFFECT_PREVIEW_COUNT = 12

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
internal fun SectionTitle(text: String) {
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

/**
 * The names still inside their recorded duration, most recent first and without [excluding].
 *
 * ## What "active" means here, exactly
 * A dose is inside its window until `takenAt + longestRouteDurationMinutes` has passed. That is a
 * **clearance** statement — the catalogue's own duration, which is the figure the app draws on every
 * graph — and it is deliberately not a claim about what the user can feel. The card says as much.
 *
 * ## Why the longest route
 * A substance taken orally and insufflated has two durations, and the honest window for "is it gone" is
 * the longer one: the shorter route's figure would clear a dose the body has not finished with.
 *
 * ## Why this is a function rather than a card parameter
 * It needs the log, the catalogue and the current time. A card that took all three would be doing
 * window arithmetic inside a composable, and the arithmetic is the part worth reading.
 */
private suspend fun activeSubstanceNames(
    app: PiruApplication,
    catalog: SubstanceCatalog,
    excluding: String,
): List<String> {
    val now = Instant.now()
    // On IO, not on the caller's dispatcher: this is called from a `LaunchedEffect`, which runs on the
    // composition thread, and a Room read there is the exact mistake this file's own catalogue open was
    // fixed for. `allowMainThreadQueries` is deliberately not set anywhere in this app.
    val doses = withContext(Dispatchers.IO) { app.database.doseEntryDao().all() }
    // The most recent dose per substance: an old dose of the same compound cannot extend anything.
    val latest = doses
        .filterNot { it.substance.equals(excluding, ignoreCase = true) }
        .groupBy { it.substance.lowercase() }
        .mapValues { (_, rows) -> rows.maxByOrNull { it.timestamp.time }!! }

    return latest.entries
        .mapNotNull { (key, row) ->
            val duration = catalog.lookup(row.substance)?.longestRouteDurationMinutes ?: return@mapNotNull null
            val endsAt = row.timestamp.toInstant().plusSeconds((duration * 60).toLong())
            if (endsAt.isAfter(now)) row.substance else null
        }
        // Sorted by when each window closes, soonest first: the reader wants to know what is about to
        // be clear as much as what is not.
        .distinct()
}
