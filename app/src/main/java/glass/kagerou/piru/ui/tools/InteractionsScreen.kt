package glass.kagerou.piru.ui.tools

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.engine.InteractionChecker
import glass.kagerou.piru.engine.InteractionPolicy
import glass.kagerou.piru.engine.InteractionResult
import glass.kagerou.piru.engine.InteractionSeverity
import glass.kagerou.piru.model.ConfidenceTier
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.labels.CoreLabels
import glass.kagerou.piru.ui.theme.toComposeColor
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import glass.kagerou.piru.substance.SubstanceMatch

/**
 * The interaction explorer: pick up to eight substances, see every rule that
 * matches, and follow a pair into its pharmacokinetic timeline.
 *
 * Ported from `Piru/Views/Tools/Interactions/InteractionCheckerView.swift`
 * (419 lines).
 *
 * ## It never reads the dose log
 * `checkBatch(selected, against = [], policy = EXPLORE)` is the whole of it. The
 * screen answers a hypothetical — "what if I took these together" — so it has no
 * timestamps and no amounts to gate on, and [InteractionPolicy.EXPLORE] means the
 * checker hides nothing: every matched rule comes back, with a low-relevance one
 * marked rather than suppressed. That is the difference between this screen and
 * the log-time warnings, and it is why nothing here can say a pair is *fine*.
 *
 * ## Selection is capped at eight, and silently
 * The cap is upstream's, and it is a data limit rather than a UI one: eight
 * substances are twenty-eight pairs, and past that the list stops being readable
 * long before it stops being computable. A ninth tap does nothing — no scolding,
 * no toast.
 *
 * ## Where the copy comes from
 * Every sentence is the engine's. `InteractionChecker` resolves each class pair to
 * Piru's own wording through `InteractionRuleCopy`, falling back to the bundled
 * database row's English note for a pair Piru has not adjudicated, and
 * `MetabolicModulation` writes the enzyme-layer sentences. This screen renders
 * `InteractionResult.description` and never composes a warning of its own — the
 * reason the explorer, the session detail and the log confirmation cannot
 * disagree about what a pairing means.
 *
 * ## Not carried: the mechanism glyph
 * `InteractionMechanism` carries SF Symbol names (`"lungs.fill"`,
 * `"brain.head.profile"`). This build ships `material-icons-core` only, which has
 * no equivalent for any of them, so a row shows the names, the severity chip and
 * the sentence instead of inventing a symbol. Nothing is claimed falsely by the
 * omission; the mapping belongs with the rest of the icon set.
 */
@Composable
fun InteractionsScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication

    var selected by remember { mutableStateOf<List<String>>(emptyList()) }
    var results by remember { mutableStateOf<List<InteractionResult>>(emptyList()) }
    var combinations by remember { mutableStateOf<List<CombinationFormation>>(emptyList()) }
    var searchText by remember { mutableStateOf("") }
    // The matches, not just the substances: `matchedAlias` is what the dropdown needs in
    // order to say which alias the query named.
    var searchResults by remember { mutableStateOf<List<SubstanceMatch<Substance>>>(emptyList()) }
    var showSearchResults by remember { mutableStateOf(false) }
    var usedCounts by remember { mutableStateOf<List<UsedSubstance>>(emptyList()) }
    var tints by remember { mutableStateOf<Map<String, P3Color>>(emptyMap()) }
    var openPair by remember { mutableStateOf<Pair<String, String>?>(null) }

    // A `MutableState` rather than a plain `val`, because the checker only exists
    // once the catalog has been installed and opened — which is suspending disk
    // work. Keying the recheck effect on it is also what makes the very first
    // pair check run as soon as the checker arrives.
    val checkerState = remember { mutableStateOf<InteractionChecker?>(null) }

    // The checker memoises drug classes in plain maps and is documented as *not*
    // thread-safe, while a cancelled `LaunchedEffect` does not interrupt a
    // `checkBatch` already inside it. Serializing through one lock is what makes
    // "change the selection while a check is running" safe rather than a rare
    // concurrent write into that map.
    val checkerLock = remember { Mutex() }

    LaunchedEffect(Unit) {
        val catalog = withContext(Dispatchers.Default) { app.catalog() }
        // The catalog is both halves: `InteractionData` for the rules, and
        // `SubstanceCatalog` for name resolution. One object, so the two cannot
        // disagree about what a name means.
        checkerState.value = InteractionChecker(catalog, catalog)
    }

    // "Frequently used" — rebuilt on a data change rather than per body pass, the
    // way upstream rebuilds it on `DoseLogService.shared.revision`. The navigator's
    // counter is the port's stand-in for that revision.
    LaunchedEffect(navigator.dataVersion) {
        usedCounts = withContext(Dispatchers.Default) {
            rebuildUsedCounts(app.database.doseEntryDao().all().map { it.substance })
        }
    }

    // The user's own colour for each selected substance, so a capsule carries the
    // same tint it does in the journal. Generated colours are the fallback, which
    // is why this goes through the palette rather than reading the colour table.
    LaunchedEffect(selected) {
        tints = if (selected.isEmpty()) {
            emptyMap()
        } else {
            withContext(Dispatchers.Default) { app.palette().tintsFor(selected) }
        }
    }

    // The 150 ms debounce, as a keyed effect: a new keystroke cancels the sleep,    // so only the pause at the end of a burst reaches the index. Upstream does the
    // same with a trigger counter and `.task(id:)`.
    LaunchedEffect(searchText) {
        if (searchText.isEmpty()) {
            searchResults = emptyList()
            showSearchResults = false
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MILLIS)
        val catalog = withContext(Dispatchers.Default) { app.catalog() }
        val found = withContext(Dispatchers.Default) {
            catalog.search(searchText, limit = SEARCH_RESULT_LIMIT)
        }
        searchResults = found
        showSearchResults = true
    }

    val checker = checkerState.value
    LaunchedEffect(selected, checker) {
        if (selected.size < 2 || checker == null) {
            results = emptyList()
            combinations = emptyList()
            return@LaunchedEffect
        }
        val names = selected.toList()
        val computed = withContext(Dispatchers.Default) {
            checkerLock.withLock {
                checker.checkBatch(
                    substances = names,
                    against = emptyList(),
                    policy = InteractionPolicy.EXPLORE,
                ) to CombinationFormations.formed(names)
            }
        }
        results = computed.first
        combinations = computed.second
    }

    val pair = openPair
    if (pair != null) {
        // In place rather than through `navigator.push`: `PushRoute` has no entry
        // that carries a substance *pair*, and a route added for this screen alone
        // would be a route with no deep link behind it. Upstream pushes
        // `InteractionTimelineView` with a `NavigationLink`; this renders the same
        // screen and hands the system back gesture to the pair instead of to the
        // tab's stack, which is the one user-visible difference.
        BackHandler { openPair = null }
        InteractionTimelineScreen(
            nameA = pair.first,
            nameB = pair.second,
            navigator = navigator,
            onBack = { openPair = null },
            modifier = modifier,
        )
        return
    }

    val selectedLower = remember(selected) { selected.map { it.lowercase() }.toSet() }
    val mostUsed = usedCounts
        .filter { it.name.lowercase() !in selectedLower }
        .take(FREQUENT_LIMIT)

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.interactions_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.interactions_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    onSubmit = {
                        // The field is cleared on a successful add and left alone on
                        // a rejected one, which is what the source does. Clearing it
                        // when nothing was added would silently discard what the user
                        // typed — and this screen is usually being used to ask about
                        // a second substance, so the next thing typed usually lands
                        // in the same field.
                        val first = searchResults.firstOrNull()?.substance
                        val added = if (first != null) {
                            addSubstance(first.name, selected) { selected = it }
                        } else if (searchText.isNotEmpty()) {
                            addSubstance(searchText, selected) { selected = it }
                        } else {
                            false
                        }
                        if (added) searchText = ""
                        searchResults = emptyList()
                        showSearchResults = false
                    },
                )

                if (showSearchResults && searchResults.isNotEmpty()) {
                    SearchDropdown(
                        query = searchText,
                        results = searchResults,
                        selected = selected,
                        hasExactMatch = hasExactMatch(searchText, searchResults),
                        onPick = { name ->
                            if (addSubstance(name, selected) { selected = it }) searchText = ""
                            searchResults = emptyList()
                            showSearchResults = false
                        },
                    )
                }
            }
        }

        if (selected.isNotEmpty()) {
            item {
                ChipFlow {
                    for (name in selected) {
                        RemovableCapsule(name, tints[name.lowercase()] ?: P3Color.NEUTRAL) {
                            selected = selected.filterNot { it == name }
                        }
                    }
                }
            }
        }

        if (mostUsed.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader(stringResource(R.string.interactions_frequently_used))
                    ChipFlow {
                        for (item in mostUsed) {
                            Chip(
                                text = item.name,
                                tint = null,
                                onClick = { addSubstance(item.name, selected) { selected = it } },
                            )
                        }
                    }
                }
            }
        }

        if (selected.isEmpty() && !showSearchResults) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.interactions_choose_two),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        if (selected.size >= 2) {
            item {
                if (results.isEmpty()) {
                    NoResultsCard()
                } else {
                    ResultsCard(results = results, onOpen = { openPair = it })
                }
            }
        }

        if (selected.size >= 2 && combinations.isNotEmpty()) {
            item {
                CombinationCard(combinations)
            }
        }
    }
}

// MARK: - Search

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(percent = 50),
        leadingIcon = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.size(18.dp),
            )
        },
        placeholder = { Text(stringResource(R.string.interactions_search_hint)) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
    )
}

/**
 * The dropdown under the field: the catalog's ranked hits, minus anything already
 * selected, plus a "use what I typed" row when the catalog has no exact match.
 *
 * The custom row is what makes a substance the catalog does not carry usable at
 * all. It matches by exact name in `drugClasses`, so it only ever finds a rule if
 * a curated row names that spelling — a custom entry is not a guess, and the app
 * does not pretend otherwise.
 */
@Composable
private fun SearchDropdown(
    query: String,
    results: List<SubstanceMatch<Substance>>,
    selected: List<String>,
    hasExactMatch: Boolean,
    onPick: (String) -> Unit,
) {
    val selectedLower = remember(selected) { selected.map { it.lowercase() }.toSet() }
    val shown = results
        .filter { it.substance.name.lowercase() !in selectedLower }
        .take(DROPDOWN_LIMIT)
    val showCustom = query.isNotEmpty() && !hasExactMatch

    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            shown.forEachIndexed { index, match ->
                val substance = match.substance
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(substance.name) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            // The alias the query named, when it named one,
                            // then the canonical title.
                            match.matchedAlias?.let { "$it · ${substance.displayTitle}" }
                                ?: substance.displayTitle, style = MaterialTheme.typography.bodyLarge)
                        // Three, as upstream: enough to show which of the brand
                        // names carried the match, not enough to become a list.
                        if (substance.aliases.isNotEmpty()) {
                            Text(
                                substance.aliases.take(3).joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Chip(text = CoreLabels.category(substance.category), tint = null)
                }
            }

            if (showCustom) {
                if (shown.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(query) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = PiruTheme.colors.accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.interactions_use_query, query),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Chip(text = stringResource(R.string.interactions_custom), tint = PiruTheme.colors.accent)
                }
            }
        }
    }
}

// MARK: - Results

@Composable
private fun NoResultsCard() {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.size(22.dp),
            )
            Text(
                stringResource(R.string.interactions_none_title),
                style = MaterialTheme.typography.bodyMedium,
                color = PiruTheme.colors.secondaryLabel,
            )
            // An empty result is information, and the caveat is the information:
            // the catalog covers a minority of what people take together, so
            // silence here is absence of a listed rule, not absence of a risk.
            Text(
                stringResource(R.string.interactions_none_body),
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.tertiaryLabel,
            )
        }
    }
}

@Composable
private fun ResultsCard(results: List<InteractionResult>, onOpen: (Pair<String, String>) -> Unit) {
    // The loudest severity in the card, not the first row's: the list is ordered by
    // relevance score, which can and does rank a caution above a danger.
    val worst = results.maxOfOrNull { it.severity } ?: InteractionSeverity.CAUTION
    val headerColour = InteractionSeverityPalette.text(worst)

    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                pluralStringResource(
                    R.plurals.interactions_found_count,
                    results.size,
                    results.size,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = headerColour,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
            )

            results.forEachIndexed { index, warning ->
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(start = 46.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(warning.substanceA to warning.substanceB) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        InteractionWarningRow(warning)
                    }
                    Icon(
                        Icons.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.interactions_open_timeline),
                        tint = PiruTheme.colors.secondaryLabel,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}

/**
 * One warning: the pair and its severity, then the sentence.
 *
 * Ported from `Views/Components/InteractionWarningRow.swift`, minus the mechanism
 * glyph — see the screen note.
 */
@Composable
private fun InteractionWarningRow(warning: InteractionResult) {
    val colour = InteractionSeverityPalette.text(warning.severity)

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.interactions_pair, warning.substanceA, warning.substanceB),
                style = MaterialTheme.typography.labelLarge,
                color = colour,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Chip(text = warning.severity.label.lowercase(), tint = colour)
        }
        Text(
            warning.description,
            style = MaterialTheme.typography.bodySmall,
            color = PiruTheme.colors.secondaryLabel,
        )
    }
}

// MARK: - Combination products

/**
 * One combination-generated active species that forms among the current
 * selection — cocaethylene from cocaine and ethanol, ethylphenidate from
 * methylphenidate and ethanol.
 *
 * ## Why the two definitions are a table here and a query upstream
 * Upstream reads them from `combination_metabolites` joined to
 * `pharmacology_matchers`. This build's read layer does not carry either table,
 * and the copy — two sentences per combination, in the app's own voice — would
 * have to live in Kotlin regardless, for the same reason `InteractionRuleCopy`
 * does. So both halves are a table, transcribed from
 * `data/curated/combination-metabolites.json` and
 * `CombinationMetabolite.CombinationID` in
 * `Piru/Data/Pharmacology/CombinationMetabolite.swift`. That is a *closed* set:
 * the curated file holds exactly these two combinations, and the evidence run
 * behind them rejected simulating either through the occupancy pipeline for want
 * of a human Vd. A third would need both a row and this table, which is the one
 * thing that could drift — flagged here so it is found before it bites.
 *
 * Detection is unchanged and stays pure: every precursor slot must be onboard,
 * matched on the lowercased substance names as logged.
 */
private val COMBINATION_CATALOG: List<CombinationFormation> = listOf(
    CombinationFormation(
        id = "cocaethylene",
        displayName = "Cocaethylene",
        confidence = ConfidenceTier.MEDIUM,
        precursors = listOf(
            listOf(
                "cocaine", "crack", "crack cocaine", "cocaine hydrochloride", "coke",
                "benzoylmethylecgonine",
            ),
            listOf("ethanol", "alcohol", "ethyl alcohol"),
        ),
        formationNote = R.string.interactions_cocaethylene_formation,
        cautionNote = R.string.interactions_cocaethylene_caution,
    ),
    CombinationFormation(
        id = "ethylphenidate",
        displayName = "Ethylphenidate",
        confidence = ConfidenceTier.LOW,
        precursors = listOf(
            listOf(
                "methylphenidate", "ritalin", "concerta", "mph", "dexmethylphenidate",
                "focalin", "d-methylphenidate",
            ),
            listOf("ethanol", "alcohol", "ethyl alcohol"),
        ),
        formationNote = R.string.interactions_ethylphenidate_formation,
        cautionNote = R.string.interactions_ethylphenidate_caution,
    ),
)

/** A combination product as the explorer needs it: what forms, and what to say about it. */
internal data class CombinationFormation(
    val id: String,
    val displayName: String,
    val confidence: ConfidenceTier,
    /** One matcher list per precursor; every slot must be onboard for the species to form. */
    val precursors: List<List<String>>,
    /**
     * The two sentences are resource ids rather than text: the note is copy this
     * build owns and has to translate, and `displayName` beside it is not.
     */
    @StringRes val formationNote: Int,
    @StringRes val cautionNote: Int,
)

/** Detection — pure, and gated only on co-presence, because the explorer has no windows to gate on. */
private object CombinationFormations {

    fun formed(among: List<String>): List<CombinationFormation> {
        val onboard = among.map { it.lowercase().trim() }.toSet()
        return COMBINATION_CATALOG.filter { definition ->
            definition.precursors.all { slot -> slot.any { onboard.contains(it) } }
        }
    }
}

@Composable
private fun CombinationCard(formations: List<CombinationFormation>) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                stringResource(R.string.interactions_combination_heading),
                style = MaterialTheme.typography.labelLarge,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
            )
            formations.forEachIndexed { index, formation ->
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(start = 46.dp))
                }
                CombinationMetaboliteBanner(
                    formation,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}

/**
 * Ported from `Views/Components/CombinationMetaboliteBanner.swift`.
 *
 * Caution styling rather than danger: the extra cardiac and hepatic strain is
 * real, and the copy explicitly defuses the "18–25× sudden death" figure the
 * evidence run traced to a single 1997 narrative review with no primary cohort.
 */
@Composable
private fun CombinationMetaboliteBanner(formation: CombinationFormation, modifier: Modifier = Modifier) {
    val caution = PiruTheme.colors.cautionText

    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            Icons.Filled.Info,
            contentDescription = null,
            tint = caution,
            modifier = Modifier.size(20.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(formation.displayName, style = MaterialTheme.typography.labelLarge)
            Text(
                stringResource(formation.formationNote),
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
            // A model output is labelled as one. The formation is chemistry, but
            // the two sentences around it are the app's reading of the evidence,
            // and the confidence tier travels with them.
            Text(
                stringResource(
                    R.string.interactions_modeled_suffix,
                    stringResource(formation.cautionNote),
                    confidenceLabel(formation.confidence),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}

// MARK: - Chips and capsules

/**
 * A capsule chip: a tinted or plain fill with a caption inside it.
 *
 * A tint means the fill is the caption's own colour at the app's `tint` alpha, so
 * a severity badge and a category chip are the same object at two settings — the
 * grammar upstream's `capsuleChip` uses. `@param onClick` makes it a button; the
 * badge inside a warning row is not one, and a chip that looks tappable and is not
 * is worse than one that plainly is not.
 */
@Composable
internal fun Chip(text: String, tint: Color?, onClick: (() -> Unit)? = null) {
    val label = tint ?: PiruTheme.colors.secondaryLabel
    val fill = tint ?: PiruTheme.colors.inputBackground

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(fill.copy(alpha = if (tint != null) 0.10f else 1f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = label)
    }
}

/** A removable capsule: the substance's own colour, so the selection reads as a palette rather than a list. */
@Composable
private fun RemovableCapsule(name: String, tint: P3Color, onRemove: () -> Unit) {
    val colour = tint.toComposeColor()

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(colour.copy(alpha = 0.10f))
            .clickable(onClick = onRemove)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            Icons.Filled.Close,
            contentDescription = stringResource(R.string.interactions_remove, name),
            tint = colour,
            modifier = Modifier.size(12.dp),
        )
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colour,
        )
    }
}

@Composable
private fun ChipFlow(content: @Composable () -> Unit) {
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = { content() },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = PiruTheme.colors.secondaryLabel,
    )
}

// MARK: - Severity colour

/**
 * The severity scale — a three-step ladder, not three lookups of a four-step one.
 *
 * `PiruColors` carries the five surfaces and the four semantic pairs
 * (`caution`, `danger`, `info`, `success`), and none of those is the middle rung:
 * an unsafe pairing sits between a caution and a danger on purpose, and folding
 * it into `danger` would erase a distinction this app exists to make. Upstream
 * solves it with its own `severity/` asset family for exactly that reason, so the
 * six values below are transcribed from
 * `Shared/Assets.xcassets/severity/{caution,unsafe,dangerous}/{accent,text}.colorset`
 * rather than invented — display-P3, because that is what the assets declare and
 * reading them as sRGB would land the middle rung visibly duller.
 *
 * The component values are the sRGB-light appearance and the dark-luminosity one;
 * the high-contrast variants are not carried, matching the rest of this theme.
 */
internal object InteractionSeverityPalette {

    /** Mark colour — fills, bands, chips. Text uses [text]. */
    @Composable
    fun accent(severity: InteractionSeverity): Color {
        val dark = PiruTheme.colors.isDark
        return when (severity) {
            InteractionSeverity.CAUTION -> if (dark) p3(0.805, 0.649, 0.000) else p3(0.661, 0.532, 0.015)
            InteractionSeverity.UNSAFE -> if (dark) p3(0.809, 0.481, 0.000) else p3(0.791, 0.472, 0.020)
            InteractionSeverity.DANGEROUS -> if (dark) p3(0.740, 0.092, 0.065) else p3(0.934, 0.324, 0.257)
        }
    }

    /** The legible text variant. Gates at WCAG AA against the card, which [accent] does not. */
    @Composable
    fun text(severity: InteractionSeverity): Color {
        val dark = PiruTheme.colors.isDark
        return when (severity) {
            InteractionSeverity.CAUTION -> if (dark) p3(0.805, 0.649, 0.000) else p3(0.502, 0.404, 0.039)
            InteractionSeverity.UNSAFE -> if (dark) p3(0.863, 0.530, 0.113) else p3(0.603, 0.354, 0.001)
            InteractionSeverity.DANGEROUS -> if (dark) p3(0.934, 0.324, 0.257) else p3(0.781, 0.155, 0.114)
        }
    }
}

private fun p3(red: Double, green: Double, blue: Double): Color =
    Color(red.toFloat(), green.toFloat(), blue.toFloat(), 1f, ColorSpaces.DisplayP3)

/**
 * `ConfidenceTier.label` upstream, kept here because the engine carries the wire
 * value only. `@Composable` because the label is a resource rather than a
 * derivation — the tier order is the engine's, the wording is the app's.
 */
@Composable
internal fun confidenceLabel(tier: ConfidenceTier): String = when (tier) {
    ConfidenceTier.HIGH -> stringResource(R.string.interactions_confidence_high)
    ConfidenceTier.MEDIUM -> stringResource(R.string.interactions_confidence_medium)
    ConfidenceTier.LOW -> stringResource(R.string.interactions_confidence_low)
    ConfidenceTier.UNVERIFIED -> stringResource(R.string.interactions_confidence_unverified)
}

// MARK: - Selection

private const val SELECTION_LIMIT = 8
private const val SEARCH_RESULT_LIMIT = 8
private const val DROPDOWN_LIMIT = 6
private const val FREQUENT_LIMIT = 6
private const val SEARCH_DEBOUNCE_MILLIS = 150L

/** One substance from the log, with how many times it appears and its first-seen spelling. */
internal data class UsedSubstance(val name: String, val count: Int)

/**
 * Count the log's substances, most-used first.
 *
 * The display name is the *first* spelling seen for a lowercased key, so a log
 * that holds both "MDMA" and "mdma" offers one capsule rather than two. Ties keep
 * log order: the count is stable-sorted, so an equal count does not shuffle
 * between rebuilds and a capsule cannot move under the finger.
 */
internal fun rebuildUsedCounts(names: List<String>): List<UsedSubstance> {
    val counts = LinkedHashMap<String, UsedSubstance>()
    for (name in names) {
        val key = name.lowercase()
        val existing = counts[key]
        counts[key] = if (existing == null) UsedSubstance(name, 1) else existing.copy(count = existing.count + 1)
    }
    return counts.values.sortedByDescending { it.count }
}

/** Whether the catalog returned the typed name itself, so the "use what I typed" row is not offered twice. */
/**
 * Whether the query exactly names one of the results.
 *
 * The search layer already knows: `matchedAlias` is non-null only when the query named a catalog
 * alias rather than matching loosely, so "an alias matched" and "the canonical name matched" are
 * the two exact cases and both are already decided. This re-scanned every result's whole alias
 * list with `lowercase()` on a per-keystroke path to reach a conclusion the match object held.
 */
private fun hasExactMatch(query: String, results: List<SubstanceMatch<Substance>>): Boolean {
    val needle = query.lowercase()
    return results.any { match ->
        match.substance.name.lowercase() == needle ||
            match.matchedAlias?.lowercase() == needle
    }
}

/**
 * Add a name to the selection, trimmed, once, and only under the cap.
 *
 * Every rejection is silent, which is upstream's behaviour and the right one: the
 * user knows what they tapped, and a toast saying "that is already selected" is
 * the app explaining a rule nobody asked about.
 */
private fun addSubstance(
    raw: String,
    selected: List<String>,
    update: (List<String>) -> Unit,
): Boolean {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return false
    if (selected.any { it.lowercase() == trimmed.lowercase() }) return false
    if (selected.size >= SELECTION_LIMIT) return false
    update(selected + trimmed)
    return true
}
