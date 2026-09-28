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
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.theme.PiruTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The library: search across the whole catalog, then in.
 *
 * Ported from `Library/SubstanceLibraryView` and `LibraryBrowseView` — 1,188
 * lines that are mostly the browse grid: a category card per class with its
 * count, a tag row, the "Common" cut across categories, favorites, and the
 * user's own substances. This draws the **search** half, which is the half the
 * substance detail needs to be reachable at all.
 *
 * The browse grid is a screen of its own over `categorySummary()`, which the
 * reader already provides — it is layout work rather than data work, which is why
 * it can follow without touching anything below it.
 */
@Composable
fun LibraryScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Substance>>(emptyList()) }
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { app.catalog() }
        ready = true
    }

    LaunchedEffect(query, ready) {
        if (!ready) return@LaunchedEffect
        val catalog = withContext(Dispatchers.Default) { app.catalog() }
        results = withContext(Dispatchers.Default) {
            if (query.isBlank()) emptyList() else catalog.search(query, limit = 60).map { it.substance }
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            "Library",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search substances") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = ready,
        )

        if (!ready) {
            Text(
                "Opening the catalog…",
                style = MaterialTheme.typography.bodyMedium,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(top = 16.dp),
            )
        } else if (query.isBlank()) {
            Text(
                "Search by name or by any of the names a substance is known under. " +
                    "The catalog carries 1,689 substances and every alias they ship with.",
                style = MaterialTheme.typography.bodyMedium,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(top = 16.dp),
            )
        } else if (results.isEmpty()) {
            Text(
                "Nothing in the catalog matches “$query”.",
                style = MaterialTheme.typography.bodyMedium,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(top = 16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(results, key = { it.id }) { substance ->
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
                            Text(
                                substance.category.wireValue,
                                style = MaterialTheme.typography.bodySmall,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                        }
                    }
                }
            }
        }
    }
}
