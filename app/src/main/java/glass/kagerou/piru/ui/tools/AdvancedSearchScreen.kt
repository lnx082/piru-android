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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.engine.BindingHit
import glass.kagerou.piru.substance.SubstanceReader
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.library.bindingAffinityText
import glass.kagerou.piru.ui.theme.PiruTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Query the catalogue's binding rows by receptor, Ki ceiling and name fragment.
 *
 * Ported from `AdvancedSearchView`. Upstream's own header calls it the "pharma-nerd surface" and hides it
 * behind the detail-level gate, which is the same `DisclosureTier` the substance page's reference sections
 * use — this port gates it at the tools entry point rather than here, so a casual reader never sees the
 * route.
 *
 * ## Why the query is debounced, and why it does not run on open
 * Upstream's reasoning, kept because it is right: dragging the Ki slider produces on the order of a
 * thousand events, and one SQLite scan per step is a thousand scans for one gesture. Every filter change
 * folds into a single `LaunchedEffect` key with a 200 ms delay, so a drag costs one query when the finger
 * stops.
 *
 * And an **unfiltered** query is not run at all. "Every measured binding in the catalogue" is 1,462 rows
 * and not an answer to anything; upstream short-circuits it and so does this. That is also why the results
 * section has its own empty state rather than borrowing the screen's.
 *
 * ## What each row carries
 * The affinity, the target, the assay species, and **the source with its identifier** — the screen's own
 * purpose is that a reader applies their own trust filter on top of the global source priority, and that
 * needs the citation visible per row rather than once at the foot.
 */
@Composable
fun AdvancedSearchScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication

    var targets by remember { mutableStateOf<List<SubstanceReader.BindingTarget>>(emptyList()) }
    var selectedTarget by remember { mutableStateOf<String?>(null) }
    var kiCeilingEnabled by remember { mutableStateOf(false) }
    var kiCeilingNm by remember { mutableStateOf(1_000.0) }
    var substanceQuery by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<BindingHit>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        targets = runCatching {
            withContext(Dispatchers.Default) { app.catalog().availableBindingTargets() }
        }.getOrDefault(emptyList())
        loaded = true
    }

    /**
     * The fields the query depends on, folded into one value so a single effect debounces all of them.
     *
     * The `isActive` rule is upstream's: a target, a ceiling or a fragment, and nothing else counts.
     */
    val kiAtMost = if (kiCeilingEnabled) kiCeilingNm else null
    val fragment = substanceQuery.trim()
    val isActive = selectedTarget != null || kiAtMost != null || fragment.isNotEmpty()

    LaunchedEffect(selectedTarget, kiAtMost, fragment) {
        if (!isActive) {
            // Not just debounced away — cleared, so the empty state explains itself rather than showing
            // stale rows from the last active query.
            results = emptyList()
            return@LaunchedEffect
        }
        // The debounce. A drag that is still moving cancels this effect before the delay elapses, so only
        // the settled value reaches the database.
        delay(200)
        results = runCatching {
            withContext(Dispatchers.Default) {
                app.catalog().bindingRowsFiltered(
                    targetBase = selectedTarget,
                    kiNmAtMost = kiAtMost,
                    substanceContains = fragment.ifEmpty { null },
                )
            }
        }.getOrDefault(emptyList())
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(
                modifier = Modifier.padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.shell_advanced_search),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.shell_advanced_search_footer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        stringResource(R.string.shell_advanced_filters),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    OutlinedTextField(
                        value = substanceQuery,
                        onValueChange = { substanceQuery = it },
                        label = { Text(stringResource(R.string.shell_advanced_substance_contains)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(
                        stringResource(R.string.shell_advanced_target),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // The target picker as wrapping chips rather than a dropdown: 152 targets in a menu is
                    // a scroll with no shape, and the chip row shows the most-populated ones first, which
                    // is the order a reader wants.
                    if (!loaded) {
                        Text(
                            stringResource(R.string.shell_advanced_loading_targets),
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedTarget == null,
                            onClick = { selectedTarget = null },
                            label = { Text(stringResource(R.string.shell_advanced_any)) },
                        )
                    }
                    for (target in targets.take(TARGET_CHIP_LIMIT)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilterChip(
                                selected = selectedTarget == target.targetBase,
                                // The base is what the query matches on; the label is the readable
                                // spelling. Selecting one reaches every assay spelling under it.
                                onClick = {
                                    selectedTarget =
                                        if (selectedTarget == target.targetBase) null else target.targetBase
                                },
                                label = {
                                    Text(
                                        stringResource(
                                            R.string.shell_advanced_target_chip,
                                            target.target,
                                            target.substanceCount,
                                        ),
                                    )
                                },
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(
                                    R.string.shell_advanced_ki_ceiling,
                                    kiCeilingNm.toInt(),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Switch(
                            checked = kiCeilingEnabled,
                            onCheckedChange = { kiCeilingEnabled = it },
                        )
                    }
                    if (kiCeilingEnabled) {
                        Slider(
                            // `Slider` is a `Float` control and Ki is a `Double` everywhere in the
                            // pharmacology code, so the conversion happens here rather than in the state.
                            value = kiCeilingNm.toFloat(),
                            onValueChange = { kiCeilingNm = it.toDouble() },
                            // 1 to 10,000 nM in 10 nM steps, upstream's range. A logarithmic scale would
                            // suit the distribution better and would make the number under the thumb
                            // unpredictable, which matters more on a screen whose whole output is numbers.
                            valueRange = 1f..10_000f,
                            steps = ((10_000 - 1) / 10) - 1,
                        )
                    }
                }
            }
        }

        item {
            Text(
                if (isActive) {
                    stringResource(R.string.shell_advanced_results, results.size)
                } else {
                    stringResource(R.string.shell_advanced_no_filter)
                },
                style = MaterialTheme.typography.labelLarge,
                color = PiruTheme.colors.secondaryLabel,
            )
        }

        if (isActive && results.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.shell_advanced_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        items(results, key = { it.id }) { hit ->
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(hit.substanceName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            bindingAffinityText(hit).substringBefore(" ·").ifEmpty { "—" },
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                    Text(
                        listOfNotNull(
                            hit.target,
                            hit.action,
                            hit.species?.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                    // The attribution, per row. The screen exists so a reader can apply their own trust
                    // filter, and that needs the source and its identifier visible where the number is.
                    Text(
                        listOfNotNull(
                            hit.sourceSlug.takeIf { it.isNotBlank() },
                            hit.pmid?.let { "PMID $it" } ?: hit.doi?.takeIf { it.isNotBlank() }?.let { "DOI" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }
    }
}

/**
 * How many target chips are offered.
 *
 * The catalogue has 152 targets, and a chip per target would be a screen of chips with the results below
 * the fold. The chips are ordered most-populated first, so this is the head of the list a reader is most
 * likely to want; the name field is the way to reach the rest.
 */
private const val TARGET_CHIP_LIMIT = 24
