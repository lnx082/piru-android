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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.labels.CoreLabels
import glass.kagerou.piru.ui.labels.appLocale
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import glass.kagerou.piru.substance.SubstanceMatch

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
    // The language these rows resolved to — not the device's. This is the one
    // date site that passed no locale at all, so it took the device default by
    // accident; it names a month, so it needs the app's language.
    val dateLocale = appLocale()

    var query by remember { mutableStateOf("") }
    // The matches, so the row can say which alias the query named.
    var substances by remember { mutableStateOf<List<SubstanceMatch<Substance>>>(emptyList()) }
    var doses by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { app.catalog() }
        ready = true
    }

    LaunchedEffect(query, ready, dateLocale) {
        if (!ready) return@LaunchedEffect
        if (query.isBlank()) {
            substances = emptyList()
            doses = emptyList()
            return@LaunchedEffect
        }
        val catalog = withContext(Dispatchers.Default) { app.catalog() }
        substances = withContext(Dispatchers.Default) {
            catalog.search(query, limit = 20)
        }
        // The log half is a plain substring match over the name as it was written,
        // case-insensitively. The catalog's ranked cascade is for finding a
        // *substance* in it; what was logged is searched as logged.
        val needle = query.lowercase()
        // The pattern is read here, before the background hop: reading a resource
        // off the main thread is not what it is for, and the format itself is the
        // one thing about this row a translation changes. The locale travels with
        // it, because a pattern carries the field order and the locale the names.
        val pattern = DateTimeFormatter.ofPattern(
            context.getString(R.string.shell_search_log_date_pattern),
            dateLocale,
        )
        doses = withContext(Dispatchers.Default) {
            app.database.doseEntryDao().all()
                .filter { it.substance.lowercase().contains(needle) }
                .take(20)
                .map {
                    it.substance to it.timestamp.toInstant().atZone(zone).format(pattern)
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
                Text(stringResource(R.string.shell_search_title), style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.shell_search_field_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = ready,
                )
            }
        }

        if (query.isBlank()) {
            item {
                Text(
                    stringResource(R.string.shell_search_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            if (doses.isNotEmpty()) {
                item {
                    SectionLabel(stringResource(R.string.shell_search_in_log))
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
                    SectionLabel(stringResource(R.string.shell_search_in_library))
                }
                items(substances, key = { it.substance.id.toString() }) { match ->
                    val substance = match.substance
                    PiruCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { navigator.push(PushRoute.Substance(substance.name)) },
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    substance.displayTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                // What the query named, when it named an alias. A search for
                                // "Concerta" returns Methylphenidate, which is right, but the
                                // typed string is what tells the reader why this row is here.
                                match.matchedAlias?.let { alias ->
                                    Text(
                                        alias,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = PiruTheme.colors.secondaryLabel,
                                    )
                                }
                            }
                            Text(
                                CoreLabels.category(substance.category),
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
                        stringResource(R.string.shell_search_no_matches, query),
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

