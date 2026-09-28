package glass.kagerou.piru.ui.tools

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
import androidx.compose.ui.unit.dp
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
                Text("Tools", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Instruments over your own log and the catalog. None of them " +
                        "recommends a dose — they read what you have already taken.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        for (group in TOOL_GROUPS) {
            item {
                Text(
                    group.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = PiruTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            for (tool in group.tools) {
                item {
                    PiruCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { navigator.push(PushRoute.Tool(tool.kind)) },
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(tool.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                tool.detail,
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
                    Text("Settings", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Substance colours, health data, notifications, and about this build.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }
    }
}

private data class ToolEntry(val kind: PushRoute.ToolKind, val title: String, val detail: String)
private data class ToolGroup(val title: String, val tools: List<ToolEntry>)

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
        "What is in me",
        listOf(
            ToolEntry(
                PushRoute.ToolKind.BODY_LOAD,
                "In your body",
                "How much of each dose is still on board right now, and how much has cleared.",
            ),
            ToolEntry(
                PushRoute.ToolKind.TOLERANCE,
                "Tolerance",
                "One card per mechanism class, replayed from your log.",
            ),
            ToolEntry(
                PushRoute.ToolKind.HALF_LIFE,
                "Half-life",
                "When a dose is half gone, and how it decays from there.",
            ),
            ToolEntry(
                PushRoute.ToolKind.INJECTION_LEVELS,
                "Injection levels",
                "Estimated serum levels from logged esters, against your own lab results.",
            ),
            ToolEntry(
                PushRoute.ToolKind.STEADY_STATE,
                "Steady state",
                "Where a regularly repeated dose settles, from the cadence you actually keep.",
            ),
        ),
    ),
    ToolGroup(
        "What happens if",
        listOf(
            ToolEntry(
                PushRoute.ToolKind.INTERACTIONS,
                "Interactions",
                "Check a combination against the catalog's rules, and see the two curves overlap.",
            ),
            ToolEntry(
                PushRoute.ToolKind.EQUIVALENCE,
                "Equivalence",
                "Opioid milligrams against morphine, and benzodiazepines against diazepam.",
            ),
            ToolEntry(
                PushRoute.ToolKind.ALCOHOL,
                "Alcohol",
                "Build a night drink by drink and watch the curve, which clears at a flat rate.",
            ),
        ),
    ),
    ToolGroup(
        "What have I got",
        listOf(
            ToolEntry(
                PushRoute.ToolKind.INVENTORY,
                "Inventory",
                "What you have on hand, replayed from your doses and your restocks.",
            ),
        ),
    ),
    ToolGroup(
        "Reference",
        listOf(
            ToolEntry(
                PushRoute.ToolKind.DRUG_CLASS,
                "Drug classes",
                "Browse by mechanism — what a class does, and what belongs to it.",
            ),
            ToolEntry(
                PushRoute.ToolKind.IDENTIFY,
                "Identify a pill",
                "Work out what an unmarked tablet or blotter is.",
            ),
            ToolEntry(
                PushRoute.ToolKind.COMEDOWN,
                "Comedown guide",
                "What a come-down is, and what tends to help.",
            ),
            ToolEntry(
                PushRoute.ToolKind.HELP,
                "Help",
                "What to do when something goes wrong, and when to call someone.",
            ),
            ToolEntry(
                PushRoute.ToolKind.EDUCATION,
                "Education cards",
                "Short explanations of the ideas the rest of the app assumes.",
            ),
        ),
    ),
)
