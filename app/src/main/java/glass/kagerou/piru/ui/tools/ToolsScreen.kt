package glass.kagerou.piru.ui.tools

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.theme.PiruTheme

/**
 * The tools hub.
 *
 * Ported from `Views/Tools/ToolsView.swift` (312 lines).
 *
 * ## Grouped by what the user is trying to find out
 * Upstream lists fourteen tools in one flat column, which is a wall. They fall
 * into four questions, and the grouping is the difference between a list you
 * scan and a list you read:
 *
 * - **What is in me** — body load, half-life, injection levels, steady state.
 *   All four answer "how much, and for how long", from different angles.
 * - **What happens if** — interactions, equivalence, alcohol. The
 *   forward-looking ones, and the only group where a wrong answer matters.
 * - **What have I got** — inventory.
 * - **Reference** — drug class, identify, and the three static guides.
 *
 * Order inside a group is by how often the thing is reached for, not
 * alphabetically. Interactions leads its group because it is the one a user
 * opens in a hurry.
 *
 * ## Every row goes somewhere
 * Each entry below opens a screen. That was not true at the start of the port —
 * the hub used to name eleven tools it could not render — and the moment each
 * one landed it moved out of that list and into this one.
 */
@Composable
fun ToolsScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.tools_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.tools_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        for (group in TOOL_GROUPS) {
            item {
                Text(
                    stringResource(group.titleRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = PiruTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            for (tool in group.tools) {
                item {
                    PiruCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { navigator.push(tool.destination) },
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(tool.titleRes), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(tool.detailRes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                        }
                    }
                }
            }
        }

        item {
            PiruCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { navigator.push(PushRoute.Settings) },
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.tools_settings_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.tools_settings_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }
    }
}

/**
 * One hub row.
 *
 * [kind] is null for the entries that are not tools — routes of their own that
 * belong on this list because this is where someone looks for them.
 */
private data class ToolEntry(
    val kind: PushRoute.ToolKind?,
    @StringRes val titleRes: Int,
    @StringRes val detailRes: Int,
    val route: PushRoute? = null,
) {
    val destination: PushRoute get() = route ?: PushRoute.Tool(requireNotNull(kind))
}
private data class ToolGroup(@StringRes val titleRes: Int, val tools: List<ToolEntry>)

/**
 * The fourteen tools, grouped.
 *
 * The one-liners are written as the question the tool answers rather than as a
 * description of its screen — "How much is still on board", not "Body load
 * screen". A hub is read by someone deciding which of fourteen things to open,
 * and the decision is made on the question, not the mechanism.
 */
private val TOOL_GROUPS = listOf(
    ToolGroup(
        R.string.tools_group_in_me,
        listOf(
            ToolEntry(
                PushRoute.ToolKind.BODY_LOAD,
                R.string.tools_body_load_title,
                R.string.tools_body_load_detail,
            ),
            ToolEntry(
                PushRoute.ToolKind.TOLERANCE,
                R.string.tools_tolerance_title,
                R.string.tools_tolerance_detail,
            ),
            ToolEntry(
                PushRoute.ToolKind.HALF_LIFE,
                R.string.tools_half_life_title,
                R.string.tools_half_life_detail,
            ),
            ToolEntry(
                PushRoute.ToolKind.INJECTION_LEVELS,
                R.string.tools_injection_levels_title,
                R.string.tools_injection_levels_detail,
            ),
            ToolEntry(
                PushRoute.ToolKind.STEADY_STATE,
                R.string.tools_steady_state_title,
                R.string.tools_steady_state_detail,
            ),
        ),
    ),
    ToolGroup(
        R.string.tools_group_what_if,
        listOf(
            ToolEntry(
                PushRoute.ToolKind.INTERACTIONS,
                R.string.tools_interactions_title,
                R.string.tools_interactions_detail,
            ),
            ToolEntry(
                PushRoute.ToolKind.EQUIVALENCE,
                R.string.tools_equivalence_title,
                R.string.tools_equivalence_detail,
            ),
            ToolEntry(
                PushRoute.ToolKind.ALCOHOL,
                R.string.tools_alcohol_title,
                R.string.tools_alcohol_detail,
            ),
        ),
    ),
    ToolGroup(
        R.string.tools_group_have_got,
        listOf(
            ToolEntry(
                PushRoute.ToolKind.INVENTORY,
                R.string.tools_inventory_title,
                R.string.tools_inventory_detail,
            ),
        ),
    ),
    ToolGroup(
        R.string.tools_group_take,
        listOf(
            // Not a ToolKind: it is a route of its own, because the meds hub is a
            // destination rather than an instrument over the log. It is listed
            // here anyway — this is where someone looks for it, and the journal
            // card is where it lives, which is not where you would go hunting.
            ToolEntry(
                kind = null,
                titleRes = R.string.tools_my_meds_title,
                detailRes = R.string.tools_my_meds_detail,
                route = PushRoute.MyMeds,
            ),
        ),
    ),
    ToolGroup(
        R.string.tools_group_reference,
        listOf(
            ToolEntry(
                PushRoute.ToolKind.DRUG_CLASS,
                R.string.tools_drug_class_title,
                R.string.tools_drug_class_detail,
            ),
            ToolEntry(
                PushRoute.ToolKind.IDENTIFY,
                R.string.tools_identify_title,
                R.string.tools_identify_detail,
            ),
            ToolEntry(
                PushRoute.ToolKind.COMEDOWN,
                R.string.tools_comedown_title,
                R.string.tools_comedown_detail,
            ),
            ToolEntry(
                PushRoute.ToolKind.HELP,
                R.string.tools_help_title,
                R.string.tools_help_detail,
            ),
            ToolEntry(
                PushRoute.ToolKind.EDUCATION,
                R.string.tools_education_title,
                R.string.tools_education_detail,
            ),
        ),
    ),
)
