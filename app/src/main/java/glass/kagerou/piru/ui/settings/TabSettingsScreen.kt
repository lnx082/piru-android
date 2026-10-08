package glass.kagerou.piru.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.AppSettingsStore
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.AppTab
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * Tabs: which ones the bottom bar shows, in what order, and whether they carry labels.
 *
 * ## Why the audit's question about this is answerable
 * The objective asked whether this belongs in the port and concluded it does, and the screen is what that means.
 * Five tabs on a narrow phone is a bar where every label is truncated; hiding one is the fix, and it is the user's
 * to make rather than the app's to guess.
 *
 * ## The two guards, which the screen cannot be trusted to enforce alone
 * **The last tab cannot be hidden** — a bar with nothing in it has no way back to this screen. **The selected tab
 * cannot be hidden** — hiding the tab you are standing on leaves a screen with no bar item to return to it. Both
 * live in [TabLayout] and are applied there rather than here, because a caller that forgot one would produce a
 * state the user cannot undo.
 *
 * ## Why reordering is two buttons rather than drag-and-drop
 * Five items. A drag gesture would be more machinery than the job needs, and the buttons are reachable by a screen
 * reader, which a drag target is not without more work than this is worth.
 */
@Composable
fun TabSettingsScreen(
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
    onChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val settings = remember { AppSettingsStore(context) }

    // The preference, held as state so a toggle redraws the list without a reload.
    var stored by remember { mutableStateOf(settings.hiddenTabs()) }
    var labels by remember { mutableStateOf(settings.tabLabelsShown()) }

    val visible = TabLayout.visibleTabs(stored, navigator.selectedTab)

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.shell_tabs_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.shell_tabs_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        for (tab in AppTab.entries) {
            item(key = tab.wireValue) {
                val isVisible = tab in visible
                // The selected tab reads as on and cannot be switched off, rather than being switchable and then
                // silently re-added — a control that appears to work and does not is worse than a disabled one.
                val isSelected = tab == navigator.selectedTab
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(tab.labelRes), style = MaterialTheme.typography.titleSmall)
                            if (isSelected) {
                                Text(
                                    stringResource(R.string.shell_tabs_selected_note),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PiruTheme.colors.secondaryLabel,
                                )
                            } else if (isVisible && !TabLayout.canHide(tab, visible)) {
                                Text(
                                    stringResource(R.string.shell_tabs_last_note),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PiruTheme.colors.secondaryLabel,
                                )
                            }
                        }
                        // The reorder pair, only for a visible tab: moving a hidden one is meaningless.
                        if (isVisible) {
                            val index = visible.indexOf(tab)
                            TextButton(
                                enabled = index > 0,
                                onClick = {
                                    // Reordering cannot change the *set*, so it does not go through `toggle`'s
                                    // guards — the visible list it works from already has them applied.
                                    val next = visible.toMutableList()
                                    next.add(index - 1, next.removeAt(index))
                                    stored = next.map { it.wireValue }
                                    settings.setHiddenTabs(next.map { it.wireValue })
                                    onChanged()
                                },
                            ) { Text("\u2190") }
                            TextButton(
                                enabled = index < visible.size - 1,
                                onClick = {
                                    val next = visible.toMutableList()
                                    next.add(index + 1, next.removeAt(index))
                                    settings.setHiddenTabs(next.map { it.wireValue })
                                    stored = next.map { it.wireValue }
                                    onChanged()
                                },
                            ) { Text("\u2192") }
                        }
                        Switch(
                            checked = isVisible,
                            enabled = !isSelected,
                            onCheckedChange = { want ->
                                val next = TabLayout.toggle(
                                    storedHidden = stored,
                                    currentlyVisible = visible,
                                    selected = navigator.selectedTab,
                                    tab = tab,
                                    visible = want,
                                )
                                stored = next
                                settings.setHiddenTabs(next)
                                onChanged()
                            },
                        )
                    }
                }
            }
        }

        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.shell_tabs_labels), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.shell_tabs_labels_detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                    Switch(
                        checked = TabLayout.showsLabels(visible.size, labels),
                        onCheckedChange = {
                            labels = it
                            settings.setTabLabelsShown(it)
                            onChanged()
                        },
                    )
                }
            }
        }
    }
}
