package glass.kagerou.piru.ui.insights

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
 * Insights.
 *
 * Ported from `Views/Insights/InsightsView.swift` (560 lines) and
 * `InsightGroupView.swift` (199 lines) — twenty-odd files and about 6,200 lines
 * of page code behind this hub.
 *
 * ## What separates this from Tools
 * Both read the user's own log, and the split is not arbitrary. A **tool** is
 * pointed at something — pick two substances, pick an ester, enter a night's
 * drinks — and answers a question about that. An **insight** is not pointed at
 * anything: it reads the whole log and tells the user something they had not
 * asked. That is why two screens appear in both places (body load and tolerance,
 * which the tools hub also offers) and why everything else appears once.
 *
 * ## No streaks, no scores, no comparison
 * Upstream's copy draws the line, and it is worth restating because it is the
 * easiest thing in a statistics screen to get wrong: nothing here compares the
 * user to anyone else, nothing counts consecutive days as an achievement, and
 * nothing is coloured red for being high. Adherence in particular reports a
 * fraction and deliberately has no flame icon — for the people this app is for,
 * a streak is a way to feel bad, not a way to do better.
 */
@Composable
fun InsightsScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Insights", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Readings of your own log. Nothing here compares you to anyone else.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        for (group in INSIGHT_GROUPS) {
            item {
                Text(
                    group.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = PiruTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            for (entry in group.entries) {
                item {
                    PiruCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { navigator.push(entry.destination()) },
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                entry.detail,
                                style = MaterialTheme.typography.bodyMedium,
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
 * One hub row.
 *
 * A row is addressed either as an insight or as a tool, because two of these are
 * the same screens the tools hub offers — body load and tolerance are readings of
 * the log *and* instruments you point at it, and building a second copy of either
 * so the types matched would be the worst of both. The lambda is what keeps that
 * from leaking into the data.
 */
private class InsightEntry(
    val title: String,
    val detail: String,
    val destination: () -> PushRoute,
)

private class InsightGroup(val title: String, val entries: List<InsightEntry>)

private fun insight(kind: PushRoute.InsightKind, title: String, detail: String) =
    InsightEntry(title, detail) { PushRoute.Insight(kind) }

private fun tool(kind: PushRoute.ToolKind, title: String, detail: String) =
    InsightEntry(title, detail) { PushRoute.Tool(kind) }

/**
 * The eight pages, in four readings.
 *
 * Ordering is by how much of the log each one needs to be worth opening. The
 * top group is a snapshot of right now and is useful after a single entry; the
 * bottom group needs weeks before it says anything, and putting it first would
 * greet a new user with seven empty screens.
 */
private val INSIGHT_GROUPS = listOf(
    InsightGroup(
        "Right now",
        listOf(
            tool(
                PushRoute.ToolKind.BODY_LOAD,
                "In your body",
                "What is still on board, per substance, and how much of each dose has cleared.",
            ),
            insight(
                PushRoute.InsightKind.STEADY_STATE_PROJECTION,
                "Steady state",
                "Where a substance you take on a regular cadence settles between doses.",
            ),
            insight(
                PushRoute.InsightKind.HORMONE_LEVELS,
                "Hormone levels",
                "Estimated serum estradiol or testosterone from the esters you logged.",
            ),
        ),
    ),
    InsightGroup(
        "Tolerance and receptors",
        listOf(
            tool(
                PushRoute.ToolKind.TOLERANCE,
                "Modeled tolerance",
                "How far each mechanism has shifted, replayed from your whole log.",
            ),
            insight(
                PushRoute.InsightKind.RECEPTOR_LOAD,
                "Receptor load over time",
                "How hard each mechanism has been driven, relative to your own recent baseline.",
            ),
        ),
    ),
    InsightGroup(
        "Your patterns",
        listOf(
            insight(
                PushRoute.InsightKind.USAGE,
                "Usage",
                "When you log, how much, and how regularly — by day, hour and weekday.",
            ),
            insight(
                PushRoute.InsightKind.ADHERENCE,
                "Adherence",
                "Which scheduled doses you took, and which days they were due at all.",
            ),
            insight(
                PushRoute.InsightKind.PATTERNS,
                "Patterns",
                "Days used, exposure, how your doses have trended, and what you take together.",
            ),
            insight(
                PushRoute.InsightKind.FELT_PATTERNS,
                "Did it work?",
                "What your own answers to \"did it work?\" look like across doses.",
            ),
        ),
    ),
    InsightGroup(
        "Take it with you",
        listOf(
            insight(
                PushRoute.InsightKind.REPORTS,
                "Reports",
                "Summaries you can hand to a clinician, built from the log.",
            ),
        ),
    ),
)
