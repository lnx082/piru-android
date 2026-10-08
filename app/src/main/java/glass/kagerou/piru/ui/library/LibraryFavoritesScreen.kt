package glass.kagerou.piru.ui.library

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.entity.FavoriteSubstanceEntity
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.labels.CoreLabels
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.theme.PiruTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The user's favourites.
 *
 * Ported from the "Yours" umbrella card in `LibraryBrowseView.swift` and the list behind it.
 *
 * ## The table has been there since v1 with nothing able to reach it
 * `favorite_substances` has had an entity, a DAO and a full round trip through the export since
 * the schema was written, and the only thing in the app that ever *read* it was the row count on
 * the Data & Backup screen. So the count was always zero, the export always wrote `[]`, and
 * `PushRoute.LibraryFavorites` had a destination no screen could navigate to. This is the reader,
 * and the row's star is the writer.
 *
 * ## The row shows the favourite's own record, resolving the catalogue only for a title
 * A favourite stores the name plus whatever identity the user had when they starred it
 * (`substanceUID`, isomer, release form, salt form, product name). The catalogue is consulted for
 * a title and a class when it still carries the substance, and the stored name stands in when it
 * does not — which is what keeps a favourite of a substance the catalogue later dropped, or of a
 * user-defined one, from vanishing out of the list.
 */
@Composable
fun LibraryFavoritesScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val scope = rememberCoroutineScope()

    var favourites by remember { mutableStateOf<List<FavoriteSubstanceEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    // Titles and classes, resolved once for the whole list rather than per row.
    var resolved by remember { mutableStateOf<Map<String, Substance>>(emptyMap()) }

    suspend fun reload() {
        val rows = app.database.favoriteSubstanceDao().all()
        val catalog = withContext(Dispatchers.IO) { app.catalog() }
        resolved = rows.mapNotNull { row ->
            catalog.resolveFull(row.substance)?.let { row.substance.lowercase() to it }
        }.toMap()
        favourites = rows
        loaded = true
    }

    LaunchedEffect(Unit) { reload() }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Text(
                stringResource(R.string.shell_library_favorites),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
        }

        if (loaded && favourites.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.shell_library_favorites_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        items(favourites, key = { it.substance }) { row ->
            val substance = resolved[row.substance.lowercase()]
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            // The whole title block opens the substance, which is the action
                            // the row is for; the star beside it is the other one.
                            .then(
                                if (substance == null) {
                                    Modifier
                                } else {
                                    Modifier.clickable {
                                        navigator.push(PushRoute.Substance(substance.name))
                                    }
                                },
                            ),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            substance?.displayTitle ?: row.productName ?: row.substance,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        // The identity facets, when the user starred a specific form. Kept out
                        // of the title so "Methylphenidate · XR" reads as one substance with a
                        // form rather than as two substances.
                        val facets = listOfNotNull(row.isomer, row.releaseForm, row.saltForm)
                        Text(
                            when {
                                facets.isNotEmpty() -> facets.joinToString(" · ")
                                substance != null -> CoreLabels.category(substance.category)
                                else -> stringResource(R.string.shell_library_favorites_unknown)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                    // Unstarring is the one action this list exists to offer: a favourites list
                    // that cannot be pruned is a list that only grows.
                    IconButton(
                        onClick = {
                            scope.launch {
                                app.database.favoriteSubstanceDao().delete(row)
                                reload()
                            }
                        },
                    ) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = stringResource(R.string.shell_library_unfavorite),
                            tint = PiruTheme.colors.accent,
                        )
                    }
                }
            }
        }
    }
}
