package glass.kagerou.piru.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.labels.CoreLabels
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.theme.PiruTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.FilterChip
import glass.kagerou.piru.substance.SubstanceReader

/**
 * One category's members, pushed from the library's category grid.
 *
 * The header names the class and its count; each row opens the substance's
 * detail. The members are [DbSubstanceCatalog.substancesIn]'s answer — popularity
 * first, then alphabetical — so this screen is a projection of the catalog rather
 * than its own sort.
 */
@Composable
fun CategoryBrowseScreen(
    category: SubstanceCategory,
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    var substances by remember(category) { mutableStateOf<List<Substance>>(emptyList()) }
    var families by remember(category) { mutableStateOf<List<SubstanceReader.ClassContext>>(emptyList()) }
    var ready by remember(category) { mutableStateOf(false) }

    // The order, remembered per category: browsing two categories in a row should not reset the choice,
    // and it is a display preference rather than data, so it does not belong in a store.
    var order by remember { mutableStateOf(BrowseOrder.POPULARITY) }

    LaunchedEffect(category) {
        val catalog = withContext(Dispatchers.Default) { app.catalog() }
        substances = withContext(Dispatchers.Default) { catalog.substancesIn(category) }
        // The class write-ups whose own category is this one. Read here rather than per-row: it is one
        // query for the whole screen, and a family card needs the member list to be worth drawing.
        families = withContext(Dispatchers.Default) {
            catalog.classContexts().filter { it.category == category && it.siblings.isNotEmpty() }
        }
        ready = true
    }

    // The three orders, applied to what the catalogue returned rather than re-queried: the list is already
    // in hand and sorting it is a comparison per element.
    val ordered = remember(substances, order) {
        when (order) {
            // The catalogue's own order, which is popularity then name. `substancesIn` already did it, so
            // this is the identity rather than a re-sort that could disagree with it.
            BrowseOrder.POPULARITY -> substances
            BrowseOrder.NAME -> substances.sortedBy { it.displayTitle.lowercase() }
            // Families first, then the rest alphabetically. Deliberately grouped rather than interleaved:
            // the family cards above already say what the families are, and a reader who opened a family
            // is looking for its members together.
            BrowseOrder.FAMILY -> substances.sortedWith(
                compareBy({ it.classContextSlug == null }, { it.classContextSlug ?: "" }, {
                    it.displayTitle.lowercase()
                }),
            )
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(CoreLabels.category(category), style = MaterialTheme.typography.headlineSmall)
                // Gated on `ready` rather than drawn empty: the count is already
                // known from the grid's badge, so flashing "0" for the frame the
                // load takes is a regression the grid never had.
                if (ready) {
                    Text(
                        pluralStringResource(
                            R.plurals.shell_library_category_count,
                            substances.size,
                            substances.size,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        if (!ready) {
            item {
                Text(
                    stringResource(R.string.shell_library_opening),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        // The families, before the flat list. A category holds several chemical families and the
        // catalogue's own write-ups say which is which; a reader scanning for "the arylcyclohexylamines"
        // should not have to know the names to find them.
        if (families.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        stringResource(R.string.shell_library_families),
                        style = MaterialTheme.typography.labelLarge,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
            items(families, key = { it.slug }) { family ->
                PiruCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { navigator.push(PushRoute.DrugClass(family.slug)) },
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(family.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.shell_library_family_members, family.siblings.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.shell_library_all_in_category),
                    style = MaterialTheme.typography.labelLarge,
                    color = PiruTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (ready && substances.size > 1) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (candidate in BrowseOrder.entries) {
                        FilterChip(
                            selected = order == candidate,
                            onClick = { order = candidate },
                            label = { Text(stringResource(candidate.labelRes)) },
                        )
                    }
                }
            }
        }

        items(ordered, key = { it.id }) { substance ->
            PiruCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { navigator.push(PushRoute.Substance(substance.name)) },
            ) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(substance.displayTitle, style = MaterialTheme.typography.titleSmall)
                        val alias = substance.aliases.firstOrNull()
                        if (alias != null) {
                            Text(
                                alias,
                                style = MaterialTheme.typography.bodySmall,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                        }
                    }
                    // A multi-class compound appears here through an extra browse
                    // category, and its primary home is the one thing the row would
                    // otherwise leave unstated.
                    if (substance.category != category) {
                        Text(
                            CoreLabels.category(substance.category),
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                }
            }
        }
    }
}

/**
 * How a category list is ordered.
 *
 * Three, and the third is the one this port was missing: `substancesIn` sorts by popularity and then by
 * name, which puts the long tail in an order the reader cannot predict. Someone who knows the name they
 * want is scanning, not browsing, and alphabetical is the only order that helps them.
 */
enum class BrowseOrder(val labelRes: Int) {
    /** The catalogue's own order: popularity first, then name. */
    POPULARITY(R.string.shell_library_order_popularity),

    /** Alphabetical by display title. */
    NAME(R.string.shell_library_order_name),

    /** Grouped by drug class, then alphabetical within it. */
    FAMILY(R.string.shell_library_order_family),
}
