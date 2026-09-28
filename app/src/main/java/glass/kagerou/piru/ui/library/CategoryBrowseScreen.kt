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
    var ready by remember(category) { mutableStateOf(false) }

    LaunchedEffect(category) {
        val catalog = withContext(Dispatchers.Default) { app.catalog() }
        substances = withContext(Dispatchers.Default) { catalog.substancesIn(category) }
        ready = true
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

        items(substances, key = { it.id }) { substance ->
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
