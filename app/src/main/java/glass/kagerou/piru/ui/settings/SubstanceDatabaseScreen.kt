package glass.kagerou.piru.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import glass.kagerou.piru.data.AppSettingsStore
import glass.kagerou.piru.substance.SubstanceReader.SourceInfo
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import glass.kagerou.piru.ui.nav.PushRoute

/**
 * The bundled dataset: what ships, and which sources win when they disagree.
 *
 * Ported from `SubstanceDatabaseView.swift`. Its own footer is the honest framing — "All substance
 * data ships with the app. Reorder sources to choose which one wins when they disagree on a fact."
 * That matters because the catalogue genuinely does disagree: dose ladders come from the community
 * wikis, benzodiazepine equivalences from a cited table, receptor affinities from PDSP, and the same
 * field can have two answers.
 *
 * Deliberately **not** offering an update or a download: the data ships in the APK, and a "check for
 * updates" button would be a control that cannot do anything.
 */
@Composable
fun SubstanceDatabaseScreen(navigator: glass.kagerou.piru.ui.nav.AppNavigator) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication

    var sources by remember { mutableStateOf<List<SourceInfo>>(emptyList()) }
    var substanceCount by remember { mutableStateOf<Int?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val catalog = runCatching { app.catalog() }.getOrNull()
        sources = catalog?.sources().orEmpty()
        substanceCount = catalog?.count()
        loaded = true
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.shell_settings_substance_database),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.shell_substance_db_footer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.shell_substance_db_substances),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        // An em dash rather than a zero while the read is in flight: "0 substances"
                        // is a claim about the catalogue, and a placeholder is not a claim.
                        substanceCount?.toString() ?: "—",
                        style = MaterialTheme.typography.bodyLarge,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.shell_substance_db_source_priority),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        // The count of sources, so the row says what the next screen holds rather
                        // than only naming it.
                        if (loaded) {
                            stringResource(R.string.shell_substance_db_source_count, sources.size)
                        } else {
                            ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                    TextButton(onClick = { navigator.push(PushRoute.SourcePriority) }) {
                        Text(stringResource(R.string.shell_substance_db_reorder))
                    }
                }
            }
        }
    }
}

/**
 * Which source wins when two disagree.
 *
 * Ported from `SourcePriorityView`. The list is the whole model: position *is* priority, so there is
 * nothing to save and nothing to name — an earlier version of this kind of screen elsewhere in the
 * app had a "save" button that wrote the order it already had.
 *
 * ## Why the shipped order is shown, not a copy of it
 * The screen shows the effective order — the user's, appended to by any source they have not ranked —
 * so what is on screen is what the catalogue will actually do. Showing only the user's list would
 * hide the sources that still participate.
 */
@Composable
fun SourcePriorityOrderScreen(navigator: glass.kagerou.piru.ui.nav.AppNavigator) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val settings = remember { AppSettingsStore(context) }

    var order by remember { mutableStateOf<List<String>>(emptyList()) }
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    /**
     * The catalogue's own ranking, kept so the reset can restore it.
     *
     * Not recomputed at the reset site: that would mean a second read of the same table to answer a
     * question already answered when the screen opened.
     */
    var shippedOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val catalog = runCatching { app.catalog() }.getOrNull()
        val shipped = catalog?.sources().orEmpty()
        names = shipped.associate { it.slug to it.displayName }
        val stored = settings.sourceOrder()
        shippedOrder = shipped.map { it.slug }
        // The same composition the catalogue does, so the list is the effective ranking rather than
        // a second opinion about it.
        order = if (stored.isNullOrEmpty()) {
            shippedOrder
        } else {
            val ranked = stored.filter { it in shippedOrder }
            ranked + shippedOrder.filterNot { it in ranked }
        }
        loaded = true
    }

    fun move(from: Int, to: Int) {
        if (to !in order.indices) return
        order = order.toMutableList().apply { add(to, removeAt(from)) }
        settings.setSourceOrder(order)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.shell_substance_db_source_priority),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.shell_source_priority_footer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        items(order, key = { it }) { slug ->
            val index = order.indexOf(slug)
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        // The rank, one-based, so the first row reads "1" rather than "0".
                        (index + 1).toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = PiruTheme.colors.secondaryLabel,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    Text(
                        // The catalogue's display name where it has one, the slug otherwise: the
                        // slug is stable and the name is what a person recognises, and a source the
                        // catalogue does not describe still has to appear.
                        names[slug] ?: slug,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { move(index, index - 1) }, enabled = index > 0) {
                        Icon(
                            Icons.Filled.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.shell_source_priority_up),
                        )
                    }
                    IconButton(
                        onClick = { move(index, index + 1) },
                        enabled = index < order.lastIndex,
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.shell_source_priority_down),
                        )
                    }
                }
            }
        }

        if (loaded) {
            item {
                TextButton(onClick = {
                    settings.setSourceOrder(emptyList())
                    // Re-read rather than reordering in place: clearing means "back to shipped", and
                    // the shipped order belongs to the catalogue. Sorting the slugs here would look
                    // like a reset while producing a *different* ranking — the shipped one is
                    // `default_priority`, which is not alphabetical.
                    order = shippedOrder
                }) {
                    Text(stringResource(R.string.shell_source_priority_reset))
                }
            }
        }
    }
}
