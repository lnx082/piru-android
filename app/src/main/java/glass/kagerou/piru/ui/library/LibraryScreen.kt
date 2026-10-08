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
import androidx.compose.material3.OutlinedTextField
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
import glass.kagerou.piru.substance.SubstanceMatch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import glass.kagerou.piru.data.entity.FavoriteSubstanceEntity
import kotlinx.coroutines.launch

/**
 * The library: browse by category, or search the whole catalog, then in.
 *
 * Ported from `Library/SubstanceLibraryView` and `LibraryBrowseView` — 1,188
 * lines that are mostly the browse grid. When the query is blank this draws the
 * category browse: a card per class with its count, most populated first,
 * opening [CategoryBrowseScreen]. Type anything and it becomes the search the
 * substance detail needs to be reachable from.
 *
 * The tag row, the "Common" cut, favorites and the user's own substances are the
 * parts of `LibraryBrowseView` that read user data (Room) rather than the
 * read-only catalog, so they are not here yet; the category grid is the whole of
 * the catalog-backed browse.
 */
@Composable
fun LibraryScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    // The matches, not just the substances: `SubstanceMatch.matchedAlias` is what the
    // row needs in order to say what the query actually named.
    var results by remember { mutableStateOf<List<SubstanceMatch<Substance>>>(emptyList()) }

    // Which substances are starred, lowercased for the same reason the DAO matches that way.
    // Held here rather than queried per row: the star's state is what the row draws, and a
    // per-row read would be one query per result on every keystroke.
    var favoriteNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) {
        favoriteNames = app.database.favoriteSubstanceDao().all()
            .mapTo(HashSet()) { it.substance.lowercase() }
    }
    var categories by remember { mutableStateOf<List<Pair<SubstanceCategory, Int>>>(emptyList()) }
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val catalog = withContext(Dispatchers.Default) { app.catalog() }
        categories = withContext(Dispatchers.Default) { catalog.categorySummary() }
        ready = true
    }

    LaunchedEffect(query, ready) {
        if (!ready) return@LaunchedEffect
        val catalog = withContext(Dispatchers.Default) { app.catalog() }
        results = withContext(Dispatchers.Default) {
            if (query.isBlank()) emptyList() else catalog.search(query, limit = 60)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.shell_library_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.shell_library_field_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = ready,
        )

        if (!ready) {
            Text(
                stringResource(R.string.shell_library_opening),
                style = MaterialTheme.typography.bodyMedium,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(top = 16.dp),
            )
        } else if (query.isBlank()) {
            CategoryGrid(categories, navigator)
        } else if (results.isEmpty()) {
            Text(
                stringResource(R.string.shell_library_no_matches, query),
                style = MaterialTheme.typography.bodyMedium,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(top = 16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(results, key = { it.substance.id }) { match ->
                    val substance = match.substance
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
                                // What the query named, when it named an alias.
                                //
                                // This used to be `substance.aliases.firstOrNull()` — an
                                // arbitrary entry from a list ordered by the catalogue, not by
                                // the query. Searching "Concerta" could label the row with a
                                // different brand of the same drug, which is a worse answer
                                // than no subtitle at all. `matchedAlias` is null when the
                                // canonical name matched or the match was only loose, and then
                                // there is nothing to explain.
                                match.matchedAlias?.let { alias ->
                                    Text(
                                        alias,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = PiruTheme.colors.secondaryLabel,
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    CoreLabels.category(substance.category),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PiruTheme.colors.secondaryLabel,
                                )
                                // The writer for `favorite_substances`, which had a DAO, an
                                // export round trip and — until now — nothing in the app that
                                // ever inserted a row. The favourites screen could only ever be
                                // empty without this.
                                val starred = substance.name.lowercase() in favoriteNames
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            val dao = app.database.favoriteSubstanceDao()
                                            if (starred) {
                                                dao.byName(substance.name)?.let { dao.delete(it) }
                                            } else {
                                                dao.insert(
                                                    FavoriteSubstanceEntity(
                                                        substance = substance.name,
                                                        substanceUID = substance.substanceUID,
                                                    ),
                                                )
                                            }
                                            favoriteNames = dao.all()
                                                .mapTo(HashSet()) { it.substance.lowercase() }
                                        }
                                    },
                                ) {
                                    Icon(
                                        if (starred) Icons.Filled.Star else Icons.Outlined.Star,
                                        contentDescription = stringResource(
                                            if (starred) {
                                                R.string.shell_library_unfavorite
                                            } else {
                                                R.string.shell_library_favorite
                                            },
                                        ),
                                        tint = if (starred) {
                                            PiruTheme.colors.accent
                                        } else {
                                            PiruTheme.colors.secondaryLabel
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The blank-query half of the library: a card per category, most populated first.
 *
 * Each card names the class and its count and opens the members list. The sort is
 * the catalog's own ([DbSubstanceCatalog.categorySummary]): by count descending,
 * then enum order — so the list does not reshuffle between two builds of the same
 * catalog.
 */
@Composable
private fun CategoryGrid(
    categories: List<Pair<SubstanceCategory, Int>>,
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        // The user's own rows first: a favourites list nobody can find is the same as no
        // favourites list, which is what this was while `favorite_substances` had a DAO and no
        // reader.
        item {
            PiruCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { navigator.push(PushRoute.LibraryFavorites) },
            ) {
                Text(
                    stringResource(R.string.shell_library_favorites),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }

        item {
            Text(
                stringResource(R.string.shell_library_categories),
                style = MaterialTheme.typography.labelLarge,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        items(categories, key = { it.first.wireValue }) { (category, count) ->
            PiruCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { navigator.push(PushRoute.LibraryCategory(category)) },
            ) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(CoreLabels.category(category), style = MaterialTheme.typography.titleSmall)
                    Text(
                        pluralStringResource(R.plurals.shell_library_category_count, count, count),
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }
    }
}
