package glass.kagerou.piru.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.ui.labels.CoreLabels
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * The journal's search field, filter menu and active-filter strip.
 *
 * Ported from `JournalFilterMenu` and `JournalActiveFilterBar`. The journal had no way to narrow itself at all, so
 * on a long log the only way to reach a dose was to scroll.
 *
 * ## The three pieces, and why the strip is separate from the menu
 * The menu is where filters are **applied**; the strip is where the current filter is **visible**. Upstream keeps
 * them apart deliberately, and the reason holds: a menu closes, so a filter applied through it is invisible until
 * reopened — and a list that is quietly missing rows with no indication is the failure this prevents.
 *
 * ## The `#` mode is explained, not discovered
 * The supporting text under the field says that a leading `#` searches tags. A hidden search mode is a mode nobody
 * uses, and the alternative — offering a fourth facet for tags — would duplicate the field's own work.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun JournalFilterBar(
    filter: JournalFilter,
    onFilterChange: (JournalFilter) -> Unit,
    /**
     * Every tag any loaded dose carries, so the menu can offer them.
     *
     * Passed in rather than queried here: the journal already holds these rows, and a second read for a list it can
     * derive would be a read of the same data. Empty for a log with no tags, in which case the section is not drawn
     * at all rather than drawn empty.
     */
    availableTags: List<String>,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = filter.searchText,
            onValueChange = { onFilterChange(filter.copySearch(it)) },
            label = { Text(stringResource(R.string.journal_filter_search)) },
            supportingText = { Text(stringResource(R.string.journal_filter_search_hint)) },
            singleLine = true,
            trailingIcon = {
                if (filter.narrows) {
                    IconButton(onClick = { onFilterChange(JournalFilter()) }) {
                        Text(stringResource(R.string.journal_filter_clear_short))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box {
                TextButton(onClick = { onMenuOpenChange(true) }) {
                    Text(
                        if (filter.isActive) {
                            stringResource(R.string.journal_filter_title_count, filter.activeCount)
                        } else {
                            stringResource(R.string.journal_filter_title)
                        },
                    )
                }
                // The facet menu. One section per facet, each row a toggle, and each section's heading carries its
                // count so an applied filter is visible while the menu is open.
                DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                    if (availableTags.isNotEmpty()) {
                        Text(
                            stringResource(R.string.journal_filter_tags),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        for (tag in availableTags) {
                            FacetRow(
                                label = "#$tag",
                                checked = tag in filter.tags,
                                onToggle = {
                                    onFilterChange(
                                        filter.copyTags(
                                            if (tag in filter.tags) filter.tags - tag else filter.tags + tag,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.journal_filter_category),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    for (category in SubstanceCategory.entries) {
                        FacetRow(
                            label = CoreLabels.category(category),
                            checked = category in filter.categories,
                            onToggle = {
                                onFilterChange(
                                    filter.copyCategories(
                                        if (category in filter.categories) {
                                            filter.categories - category
                                        } else {
                                            filter.categories + category
                                        },
                                    ),
                                )
                            },
                        )
                    }
                    Text(
                        stringResource(R.string.journal_filter_route),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    for (route in RouteOfAdministration.entries) {
                        FacetRow(
                            label = CoreLabels.route(route),
                            checked = route in filter.routes,
                            onToggle = {
                                onFilterChange(
                                    filter.copyRoutes(
                                        if (route in filter.routes) {
                                            filter.routes - route
                                        } else {
                                            filter.routes + route
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }

        // The strip: one removable chip per selected value, plus Clear. Only while a facet is on — a strip with
        // nothing in it is furniture, and the search field is on screen already so it needs no chip.
        if (filter.isActive) {
            // The labels are resolved **before** the loops: a `sortedBy` comparator is not a composable scope, so a
            // `stringResource` inside one does not compile — the same reason `SessionNotesSection` resolves its
            // offset labels first.
            val categoryLabels = filter.categories.associateWith { CoreLabels.category(it) }
            val routeLabels = filter.routes.associateWith { CoreLabels.route(it) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (tag in filter.tags.sorted()) {
                    RemovableChip("#$tag") { onFilterChange(filter.withoutTag(tag)) }
                }
                for (category in filter.categories.sortedBy { categoryLabels.getValue(it) }) {
                    RemovableChip(categoryLabels.getValue(category)) {
                        onFilterChange(filter.withoutCategory(category))
                    }
                }
                for (route in filter.routes.sortedBy { routeLabels.getValue(it) }) {
                    RemovableChip(routeLabels.getValue(route)) { onFilterChange(filter.withoutRoute(route)) }
                }
                TextButton(onClick = { onFilterChange(filter.cleared()) }) {
                    Text(stringResource(R.string.journal_filter_clear))
                }
            }
        }
    }
}

/** One toggle row inside the facet menu. */
@Composable
private fun FacetRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        // A checkbox rather than a checkmark in the text: the menu stays open across toggles, so the state has to be
        // readable per row, and a `DropdownMenuItem` with a trailing checkbox is the shape Compose offers for it.
        trailingIcon = { Checkbox(checked = checked, onCheckedChange = { onToggle() }) },
        onClick = { onToggle() },
    )
}

/** A chip whose tap removes one filter value. */
@Composable
private fun RemovableChip(label: String, onRemove: () -> Unit) {
    FilterChip(
        selected = true,
        onClick = onRemove,
        label = { Text(label) },
    )
}

/** The count of selected values across every facet, for the menu button's badge. */
private val JournalFilter.activeCount: Int
    get() = tags.size + categories.size + routes.size
