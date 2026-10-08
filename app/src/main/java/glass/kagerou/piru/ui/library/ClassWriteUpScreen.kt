package glass.kagerou.piru.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import glass.kagerou.piru.substance.SubstanceReader
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.theme.PiruTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One drug class's write-up: what its members share, and who they are.
 *
 * ## What this fills in
 * `PushRoute.DrugClass` carried a `className`, a key, and nothing that produced it — a search for the
 * route across `app/src` hit only the declaration and the key arm. There **was** a
 * `ui/tools/DrugClassScreen`, reachable from the tools hub as `ToolKind.DRUG_CLASS` with a blank name,
 * which browses every class and opens one by display name. So the write-ups were readable; what was
 * missing was the route, the classification of a substance into a class, and any way to arrive at a
 * class *from a substance*.
 *
 * This is that arrival: `Substance.classContextSlug` is the slug a substance carries, the substance
 * page names its class, and the name is tappable. It resolves through `classContext(slug)` — the slug
 * rather than the display name, because a slug is what the route carries and a slug cannot be
 * mistranslated.
 *
 * ## Siblings are links
 * The member list is the reason this is worth opening from a substance: "what else is like this" is a
 * real question, the catalogue already answered it, and a name that is not tappable would make the
 * reader retype it into search.
 */
@Composable
fun ClassWriteUpScreen(classSlug: String, navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication

    var writeUp by remember(classSlug) { mutableStateOf<SubstanceReader.ClassContext?>(null) }
    var loaded by remember(classSlug) { mutableStateOf(false) }

    LaunchedEffect(classSlug) {
        writeUp = runCatching {
            withContext(Dispatchers.IO) { app.catalog() }.classContext(classSlug)
        }.getOrNull()
        loaded = true
    }

    // Resolved before the list rather than inside it: a `LazyColumn` content lambda is a
    // `LazyListScope` builder, not a composable scope, so a `stringResource` read in there is a type
    // error. Found by the compiler, not by reading.
    val mechanismTitle = stringResource(R.string.shell_class_mechanism)
    val pkTitle = stringResource(R.string.shell_class_pharmacokinetics)
    val safetyTitle = stringResource(R.string.shell_class_safety)
    val sarTitle = stringResource(R.string.shell_class_sar)

    val loadedWriteUp = writeUp
    if (loaded && loadedWriteUp == null) {
        // Named rather than left spinning: a slug the catalogue does not carry is what a stale deep
        // link looks like, and "loading" forever is the one thing it is not.
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.shell_class_gone), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(
                modifier = Modifier.padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    loadedWriteUp?.title ?: stringResource(R.string.shell_class_loading),
                    style = MaterialTheme.typography.headlineSmall,
                )
                loadedWriteUp?.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        // Each shared property is its own card: each answers a different question, and a reader
        // looking for "do these share a mechanism" should not have to read three paragraphs to find
        // out.
        classBlock(loadedWriteUp?.sharedMechanism, mechanismTitle)
        classBlock(loadedWriteUp?.sharedPharmacokinetics, pkTitle)
        classBlock(loadedWriteUp?.sharedSafety, safetyTitle)
        classBlock(loadedWriteUp?.sarSummary, sarTitle)

        val siblings = loadedWriteUp?.siblings.orEmpty()
        if (siblings.isNotEmpty()) {
            item {
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            stringResource(R.string.shell_class_members, siblings.size),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        for (sibling in siblings) {
                            Text(
                                sibling,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { navigator.push(PushRoute.Substance(sibling)) }
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        val references = loadedWriteUp?.references.orEmpty()
        if (references.isNotEmpty()) {
            item {
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            stringResource(R.string.shell_class_references),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        for (reference in references) {
                            // The catalogue stores a reference's parts rather than its prose: a
                            // title and an identifier. Joined here so a reference with only a DOI
                            // still reads as one.
                            Text(
                                listOfNotNull(
                                    reference.title,
                                    reference.doi,
                                    reference.pmid?.let { "PMID $it" },
                                ).joinToString(" · "),
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

/**
 * One shared-property card, drawn only when the catalogue has the text.
 *
 * Takes its title as a resolved string: this extends `LazyListScope`, a builder scope rather than a
 * composable one, so the resource read belongs at the call site.
 */
private fun LazyListScope.classBlock(body: String?, title: String) {
    if (body.isNullOrBlank()) return
    item {
        PiruCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
