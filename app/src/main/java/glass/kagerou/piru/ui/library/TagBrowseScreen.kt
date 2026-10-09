package glass.kagerou.piru.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.theme.PiruTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every substance carrying one tag.
 *
 * ## What this fills in
 * `PushRoute.LibraryTag` carried a `tag`, a key, and nothing that produced it or answered it. The
 * catalogue has a `tags` table with a `hidden` column and a per-source confidence, and
 * `Substance.tags` was rendered as decoration — `Header` draws `substance.tags.firstOrNull()`
 * appended to the category line, and nothing else. So the tags were data the catalogue curated and the
 * app read only to print one of them.
 *
 * A tag is how the catalogue says "these are related" without claiming a receptor class: "stimulant",
 * "dissociative", "research chemical". That is a real browse axis, and it is the one the library's
 * category grid cannot express, because a substance has one category and several tags.
 *
 * ## The gate is the catalogue's, not this screen's
 * `tags.hidden` exists so a curator can suppress a tag without deleting the row, so a hidden tag never
 * reaches here: the read filters it. A screen that showed hidden tags would be showing curation the
 * catalogue decided not to publish.
 */
@Composable
fun TagBrowseScreen(tag: String, navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication

    var substances by remember(tag) { mutableStateOf<List<Substance>>(emptyList()) }
    var loaded by remember(tag) { mutableStateOf(false) }

    LaunchedEffect(tag) {
        val catalog = runCatching {
            withContext(Dispatchers.IO) { app.catalog() }
        }.getOrNull()
        // Resolved off the main thread: `substancesIn` walks the whole catalogue and is the reason
        // `SubstanceDetailScreen` was moved to `Dispatchers.IO` too.
        substances = if (catalog == null) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) { catalog.substancesWithTag(tag) }
        }
        loaded = true
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item(key = "tag-header") {
            Column(
                modifier = Modifier.padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(tag, style = MaterialTheme.typography.headlineSmall)
                Text(
                    // The count, so an empty result reads as an answer rather than as a broken read.
                    // `substances` is empty before the read lands too, so the count only appears once
                    // it has.
                    if (loaded) {
                        stringResource(R.string.shell_tag_count, substances.size)
                    } else {
                        stringResource(R.string.shell_tag_loading)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        // Keyed by **index and id**. The id alone is unique (the catalogue has 1,689 substances and 1,689 distinct
        // ids), but the lazy layout's interval bookkeeping can be momentarily inconsistent with its provider during
        // a measure, and a key that cannot collide turns what was an `IndexOutOfBoundsException` in `getKey` into a
        // re-created row. The header above carries a key for the same reason: a synthesised positional key can
        // collide with it when the counts shift.
        itemsIndexed(substances, key = { index, substance -> "$index:${substance.id}" }) { _, substance ->
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
                        if (substance.displayTitle != substance.name) {
                            Text(
                                substance.name,
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
