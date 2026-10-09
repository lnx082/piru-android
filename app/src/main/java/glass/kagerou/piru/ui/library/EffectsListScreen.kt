package glass.kagerou.piru.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import glass.kagerou.piru.model.EffectGroup
import glass.kagerou.piru.model.SubjectiveEffect
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every effect a substance is reported to produce, grouped by category.
 *
 * Ported from `EffectsAndIntensityView.swift`.
 *
 * ## What this fills in
 * `SubstanceReader.effectGroups` documents itself as feeding "the 'All effects' screen, which is the
 * only caller" — and it had **no caller**, because the screen did not exist. The substance page
 * showed `Substance.effects` as one `joinToString(" · ")` line, which for the heaviest substances is
 * forty-odd labels in a single paragraph: a wall of words with no way to tell a come-up effect from
 * a side effect.
 *
 * Two things the flat list cannot do and this does:
 *
 * - **Group**, because the categories are curated and "visual" versus "cognitive" is the axis a
 *   reader actually scans by.
 * - **Describe**, because `SubjectiveEffect` carries the sentence that says what the effect is. The
 *   flat union is names only.
 */
@Composable
fun EffectsListScreen(name: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication

    var groups by remember(name) { mutableStateOf<List<EffectGroup>>(emptyList()) }
    var described by remember(name) { mutableStateOf<List<SubjectiveEffect>>(emptyList()) }
    var loaded by remember(name) { mutableStateOf(false) }

    LaunchedEffect(name) {
        val catalog = withContext(Dispatchers.IO) { app.catalog() }
        groups = withContext(Dispatchers.Default) { catalog.effectGroups(name) }
        described = withContext(Dispatchers.Default) { catalog.subjectiveEffects(name) }
        loaded = true
    }

    // The descriptions, keyed by name so a grouped label can find its sentence. Not every label has
    // one — the grouped query reads the `effects` table and this reads `subjective_effects`, and the
    // two do not carry the same set.
    val descriptionOf = remember(described) {
        described.associate { it.name.lowercase() to it.description }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.shell_effects_all_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        if (loaded && groups.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.shell_effects_all_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        // Keyed on the **position** as well as the category, because the category is not a key.
        //
        // The uncategorised bucket's category is the empty string — the heading substitutes a name for it below — so
        // two groups sharing a category produce **duplicate keys**, and Compose's lazy-list interval bookkeeping
        // disagrees with its provider during a measure. That is not hypothetical: it is the
        // `IndexOutOfBoundsException: Index 1, size 1` this screen threw in `SubstancePagesDeviceTest`, the same defect
        // the tag screen had, and the same fix (`itemsIndexed` with the index in the key).
        itemsIndexed(groups, key = { index, group -> "$index:${group.category}" }) { _, group ->
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        // The category is a curated label, not a wire value, so the empty one — the
                        // bucket for effects the catalogue did not classify — is named rather than
                        // left as a blank heading.
                        group.category.ifBlank {
                            stringResource(R.string.shell_effects_all_uncategorised)
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    for (effect in group.effects) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(effect, style = MaterialTheme.typography.bodyMedium)
                            descriptionOf[effect.lowercase()]?.takeIf { it.isNotBlank() }?.let { text ->
                                Text(
                                    text,
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
}
