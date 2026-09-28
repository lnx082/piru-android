package glass.kagerou.piru.ui.insights

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
                Text(stringResource(R.string.toolsb_insights_hub_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.toolsb_insights_hub_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        for (group in INSIGHT_GROUPS) {
            item {
                Text(
                    stringResource(group.title),
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
                            Text(stringResource(entry.title), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(entry.detail),
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
 *
 * The title and the detail are resource ids rather than resolved text: this table
 * is a top-level `val`, which is not a composable scope, so `stringResource`
 * cannot be read here. The call site resolves them.
 */
private class InsightEntry(
    @StringRes val title: Int,
    @StringRes val detail: Int,
    val destination: () -> PushRoute,
)

private class InsightGroup(@StringRes val title: Int, val entries: List<InsightEntry>)

private fun insight(kind: PushRoute.InsightKind, @StringRes title: Int, @StringRes detail: Int) =
    InsightEntry(title, detail) { PushRoute.Insight(kind) }

private fun tool(kind: PushRoute.ToolKind, @StringRes title: Int, @StringRes detail: Int) =
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
        R.string.toolsb_insights_hub_group_right_now,
        listOf(
            tool(
                PushRoute.ToolKind.BODY_LOAD,
                R.string.toolsb_insights_hub_body_load_title,
                R.string.toolsb_insights_hub_body_load_detail,
            ),
            insight(
                PushRoute.InsightKind.STEADY_STATE_PROJECTION,
                R.string.toolsb_insights_hub_steady_state_title,
                R.string.toolsb_insights_hub_steady_state_detail,
            ),
            insight(
                PushRoute.InsightKind.HORMONE_LEVELS,
                R.string.toolsb_insights_hub_hormone_levels_title,
                R.string.toolsb_insights_hub_hormone_levels_detail,
            ),
        ),
    ),
    InsightGroup(
        R.string.toolsb_insights_hub_group_tolerance,
        listOf(
            tool(
                PushRoute.ToolKind.TOLERANCE,
                R.string.toolsb_insights_hub_tolerance_title,
                R.string.toolsb_insights_hub_tolerance_detail,
            ),
            insight(
                PushRoute.InsightKind.RECEPTOR_LOAD,
                R.string.toolsb_insights_hub_receptor_load_title,
                R.string.toolsb_insights_hub_receptor_load_detail,
            ),
        ),
    ),
    InsightGroup(
        R.string.toolsb_insights_hub_group_patterns,
        listOf(
            insight(
                PushRoute.InsightKind.USAGE,
                R.string.toolsb_insights_hub_usage_title,
                R.string.toolsb_insights_hub_usage_detail,
            ),
            insight(
                PushRoute.InsightKind.ADHERENCE,
                R.string.toolsb_insights_hub_adherence_title,
                R.string.toolsb_insights_hub_adherence_detail,
            ),
            insight(
                PushRoute.InsightKind.PATTERNS,
                R.string.toolsb_insights_hub_patterns_title,
                R.string.toolsb_insights_hub_patterns_detail,
            ),
            insight(
                PushRoute.InsightKind.FELT_PATTERNS,
                R.string.toolsb_insights_hub_felt_title,
                R.string.toolsb_insights_hub_felt_detail,
            ),
        ),
    ),
    InsightGroup(
        R.string.toolsb_insights_hub_group_reports,
        listOf(
            insight(
                PushRoute.InsightKind.REPORTS,
                R.string.toolsb_insights_hub_reports_title,
                R.string.toolsb_insights_hub_reports_detail,
            ),
        ),
    ),
)
