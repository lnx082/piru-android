package glass.kagerou.piru.ui.search

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
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Search, as its own tab.
 *
 * ## Why it is a tab and not a field in the Library
 * Upstream's split is deliberate and this keeps it: looking something up
 * mid-session must not cost the user their place in the journal. A search field
 * buried in the Library is one you reach by leaving what you were doing; a tab is
 * one you reach from anywhere.
 *
 * ## It searches both halves
 * The catalog, and the user's own log. A search that only covered substances would
 * be the Library under a different title; what someone typing a word here usually
 * wants is *either* — "what is this" or "when did I take it" — and the two are
 * cheap to answer together.
 */
@Composable
fun SearchScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val zone = remember { ZoneId.systemDefault() }

    var query by remember { mutableStateOf("") }
    var substances by remember { mutableStateOf<List<Substance>>(emptyList()) }
    var doses by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { app.catalog() }
        ready = true
    }

    LaunchedEffect(query, ready) {
        if (!ready) return@LaunchedEffect
        if (query.isBlank()) {
            substances = emptyList()
            doses = emptyList()
            return@LaunchedEffect
        }
        val catalog = withContext(Dispatchers.Default) { app.catalog() }
        substances = withContext(Dispatchers.Default) {
            catalog.search(query, limit = 20).map { it.substance }
        }
        // The log half is a plain substring match over the name as it was written,
        // case-insensitively. The catalog's ranked cascade is for finding a
        // *substance* in it; what was logged is searched as logged.
        val needle = query.lowercase()
        doses = withContext(Dispatchers.Default) {
            app.database.doseEntryDao().all()
                .filter { it.substance.lowercase().contains(needle) }
                .take(20)
                .map {
                    it.substance to it.timestamp.toInstant().atZone(zone)
                        .format(DateTimeFormatter.ofPattern("d MMM HH:mm"))
                }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Search", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Substances and your log") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = ready,
                )
            }
        }

        if (query.isBlank()) {
            item {
                Text(
                    "Search by name, by an alias, or by a brand. The same field finds what " +
                        "the library holds and what you have logged.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            if (doses.isNotEmpty()) {
                item {
                    SectionLabel("In your log")
                }
                items(doses, key = { it.first + it.second }) { (name, when_) ->
                    PiruCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { navigator.push(PushRoute.Substance(name)) },
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                when_,
                                style = MaterialTheme.typography.bodySmall,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                        }
                    }
                }
            }

            if (substances.isNotEmpty()) {
                item {
                    SectionLabel("In the library")
                }
                items(substances, key = { it.id.toString() }) { substance ->
                    PiruCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { navigator.push(PushRoute.Substance(substance.name)) },
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(substance.displayTitle, style = MaterialTheme.typography.titleSmall)
                            Text(
                                substance.category.wireValue,
                                style = MaterialTheme.typography.bodySmall,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                        }
                    }
                }
            }

            if (doses.isEmpty() && substances.isEmpty()) {
                item {
                    Text(
                        "Nothing matches “$query”.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = PiruTheme.colors.secondaryLabel,
        modifier = Modifier.padding(top = 8.dp),
    )
}
