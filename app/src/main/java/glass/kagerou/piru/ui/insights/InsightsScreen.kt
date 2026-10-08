package glass.kagerou.piru.ui.insights

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.theme.PiruTheme
import glass.kagerou.piru.ui.tools.EsterPKIndex
import glass.kagerou.piru.ui.tools.EsterPKIndexLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import glass.kagerou.piru.engine.AdherenceCalculator
import glass.kagerou.piru.ui.meds.toAdherenceEntry
import glass.kagerou.piru.ui.meds.toAdherenceItem
import java.time.LocalDate
import java.time.YearMonth

/**
 * Insights.
 *
 * Ported from `Views/Insights/InsightsView.swift` — Apple-Health-shaped: a few **large cards** that draw the
 * headline figure or graph inline and tap through to the full page, then a grid of **compact cards** for the
 * pages that are destinations rather than readings.
 *
 * ## What the flat list got wrong
 * The port had eight rows of title-and-detail, which is a menu. A reader had to open a page to learn whether it
 * had anything to say, so every page's empty state was reachable only by opening it — and the two pages upstream
 * gates behind a condition appeared for everyone.
 *
 * The inline figure is what makes it a hub: "14 entries · 1.0/day" answers the question the Usage page exists to
 * answer, and tapping through is for the detail rather than for the answer.
 *
 * ## What separates this from Tools
 * Both read the user's own log, and the split is not arbitrary. A **tool** is pointed at something — pick two
 * substances, pick an ester, enter a night's drinks — and answers a question about that. An **insight** is not
 * pointed at anything: it reads the whole log and tells the user something they had not asked. That is why two
 * screens appear in both places (body load and tolerance, which the tools hub also offers) and everything else
 * appears once.
 *
 * ## No streaks, no scores, no comparison
 * Upstream's copy draws the line, and it is worth restating because it is the easiest thing in a statistics
 * screen to get wrong: nothing here compares the user to anyone else, nothing counts consecutive days as an
 * achievement, and nothing is coloured red for being high. Adherence in particular reports a fraction and
 * deliberately has no flame icon — for the people this app is for, a streak is a way to feel bad, not a way to do
 * better.
 */
@Composable
fun InsightsScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val zone = remember { ZoneId.systemDefault() }

    var entries by remember { mutableStateOf<List<DoseEntryEntity>>(emptyList()) }
    var usage by remember { mutableStateOf<InsightsHub.UsageSummary?>(null) }
    var adherence by remember { mutableStateOf<InsightsHub.AdherenceSummary?>(null) }
    var showFelt by remember { mutableStateOf(false) }
    var showHormones by remember { mutableStateOf(false) }

    LaunchedEffect(navigator.dataVersion) {
        val log = runCatching { app.database.doseEntryDao().all() }.getOrDefault(emptyList())
        entries = log
        val now = Instant.now()
        usage = InsightsHub.UsageSummary(
            total = InsightsHub.dailyCounts(log, now, zone).sum(),
            averagePerDay = InsightsHub.averagePerDay(log, now, zone),
            dailyCounts = InsightsHub.dailyCounts(log, now, zone),
        )
        // The two gates. Both are computed here rather than inline, because one is a pure function of the notes
        // and the other needs the ester index — and because a gate that is computed silently is a gate that
        // disappears when the screen is rewritten.
        // The adherence figures, from the same calculator the Adherence page uses. Read here rather than in the
        // card so the month is computed once, and so the card's own empty state means "no scheduled meds"
        // rather than "not loaded".
        adherence = runCatching {
            val items = app.database.dailyDoseItemDao().all()
            if (items.isEmpty()) {
                null
            } else {
                val log = log.map { it.toAdherenceEntry() }
                val today = LocalDate.now(zone)
                val month = YearMonth.from(today)
                val days = AdherenceCalculator.monthDays(
                    month = now,
                    entries = log,
                    items = items.map { it.toAdherenceItem() },
                    zone = zone,
                )
                val summary = AdherenceCalculator.monthSummary(days, now)
                val byDate = days.associateBy { it.date.atZone(zone).toLocalDate() }
                InsightsHub.AdherenceSummary(
                    taken = summary.taken,
                    due = summary.due,
                    monthStatuses = InsightsHub.monthGrid(month, today) { date ->
                        byDate[date]?.let { day ->
                            when {
                                day.totalCount == 0 -> null
                                day.takenCount >= day.totalCount ->
                                    InsightsHub.AdherenceSummary.DayStatus.COMPLETE
                                day.takenCount > 0 -> InsightsHub.AdherenceSummary.DayStatus.PARTIAL
                                else -> InsightsHub.AdherenceSummary.DayStatus.MISSED
                            }
                        }
                    },
                )
            }
        }.getOrNull()

        showFelt = InsightsHub.hasRatedNotes(
            runCatching { app.database.sessionNoteDao().all() }.getOrDefault(emptyList()),
        )
        showHormones = withContext(Dispatchers.Default) {
            runCatching {
                val index = EsterPKIndexLoader.load(context)
                val catalog = app.catalog()
                InsightsHub.hasInjectableEster(
                    entries = log,
                    index = index,
                    substanceUIDFor = { name -> catalog.lookup(name)?.substanceUID },
                )
            }.getOrDefault(false)
        }
    }

    /**
     * The compact grid's entries.
     *
     * Built **before** the list rather than inside it: a `LazyColumn` content lambda is a `LazyListScope` builder,
     * not a composable scope, so `stringResource` cannot be read in there — and the first version of this screen
     * declared the list inside the lambda, which meant it was computed and never rendered. The compact cards
     * were simply absent.
     */
    val compact = buildList {
        add(CompactEntry(R.string.toolsb_insights_hub_patterns_title, R.string.toolsb_insights_hub_patterns_detail) { PushRoute.Insight(PushRoute.InsightKind.PATTERNS) })
        add(CompactEntry(R.string.toolsb_insights_hub_steady_state_title, R.string.toolsb_insights_hub_steady_state_detail) { PushRoute.Insight(PushRoute.InsightKind.STEADY_STATE_PROJECTION) })
        // Gated: a reader who never rates a dose should not see a page that compares ratings.
        if (showFelt) {
            add(CompactEntry(R.string.toolsb_insights_hub_felt_title, R.string.toolsb_insights_hub_felt_detail) { PushRoute.Insight(PushRoute.InsightKind.FELT_PATTERNS) })
        }
        add(CompactEntry(R.string.toolsb_insights_hub_receptor_load_title, R.string.toolsb_insights_hub_receptor_load_detail) { PushRoute.Insight(PushRoute.InsightKind.RECEPTOR_LOAD) })
        add(CompactEntry(R.string.toolsb_insights_hub_body_load_title, R.string.toolsb_insights_hub_body_load_detail) { PushRoute.Tool(PushRoute.ToolKind.BODY_LOAD) })
        add(CompactEntry(R.string.toolsb_insights_hub_tolerance_title, R.string.toolsb_insights_hub_tolerance_detail) { PushRoute.Tool(PushRoute.ToolKind.TOLERANCE) })
        add(CompactEntry(R.string.toolsb_insights_hub_reports_title, R.string.toolsb_insights_hub_reports_detail) { PushRoute.Insight(PushRoute.InsightKind.REPORTS) })
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
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

        // --- The large cards: a reading with its own figure -----------------------------

        item {
            LargeCard(
                title = stringResource(R.string.toolsb_insights_hub_usage_title),
                onOpen = { navigator.push(PushRoute.Insight(PushRoute.InsightKind.USAGE)) },
            ) {
                val summary = usage
                if (summary == null || !summary.hasData) {
                    EmptyLine(stringResource(R.string.toolsb_insights_hub_no_entries))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Figures(summary.total.toString(), stringResource(R.string.toolsb_insights_hub_entries))
                            Figures(
                                String.format(java.util.Locale.ROOT, "%.1f", summary.averagePerDay),
                                stringResource(R.string.toolsb_insights_hub_per_day),
                            )
                        }
                        Sparkline(summary.dailyCounts)
                    }
                }
            }
        }

        item {
            LargeCard(
                title = stringResource(R.string.toolsb_insights_hub_adherence_title),
                onOpen = { navigator.push(PushRoute.Insight(PushRoute.InsightKind.ADHERENCE)) },
            ) {
                val summary = adherence
                if (summary == null || !summary.hasData) {
                    EmptyLine(stringResource(R.string.toolsb_insights_hub_no_meds))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Figures(
                            "${summary.taken}/${summary.due}",
                            stringResource(R.string.toolsb_insights_hub_scheduled),
                        )
                        MonthGrid(summary.monthStatuses)
                    }
                }
            }
        }

        item {
            Text(
                stringResource(R.string.toolsb_insights_hub_group_right_now),
                style = MaterialTheme.typography.labelLarge,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        if (showHormones) {
            item {
                LargeCard(
                    title = stringResource(R.string.toolsb_insights_hub_hormone_levels_title),
                    onOpen = { navigator.push(PushRoute.Insight(PushRoute.InsightKind.HORMONE_LEVELS)) },
                ) {
                    EmptyLine(stringResource(R.string.toolsb_insights_hub_hormone_levels_detail))
                }
            }
        }

        item {
            Text(
                stringResource(R.string.toolsb_insights_hub_group_tolerance),
                style = MaterialTheme.typography.labelLarge,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        // --- The compact grid: destinations ---------------------------------------------

        item {
            Text(
                stringResource(R.string.toolsb_insights_hub_group_patterns),
                style = MaterialTheme.typography.labelLarge,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        for (pair in compact.chunked(2)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (entry in pair) {
                        CompactCard(
                            title = stringResource(entry.title),
                            detail = stringResource(entry.detail),
                            onOpen = { navigator.push(entry.destination()) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Keeps a lone card at half width rather than stretching it across the row.
                    if (pair.size == 1) Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** A destination card: title, one line of detail, and a tap. */
private class CompactEntry(
    @StringRes val title: Int,
    @StringRes val detail: Int,
    val destination: () -> PushRoute,
)

/**
 * A large card: the reading's own figure or graph, with the title above it.
 *
 * The whole card is the tap target rather than a chevron at its edge, which is what upstream does and what a
 * large card invites.
 */
@Composable
private fun LargeCard(
    title: String,
    onOpen: () -> Unit,
    content: @Composable () -> Unit,
) {
    PiruCard(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

/** A compact card in the two-column grid. */
@Composable
private fun CompactCard(
    title: String,
    detail: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PiruCard(modifier = modifier, onClick = onOpen) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}

/**
 * A headline number with its unit under it.
 *
 * The figure first and larger, because these cards are read by glancing: a reader who only reads the number has
 * the answer, and the unit is for the reader who wants it.
 */
@Composable
private fun Figures(value: String, unit: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(
            unit,
            style = MaterialTheme.typography.bodySmall,
            color = PiruTheme.colors.secondaryLabel,
        )
    }
}

/** The empty line a card shows instead of an empty chart. */
@Composable
private fun EmptyLine(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = PiruTheme.colors.secondaryLabel,
    )
}

/**
 * Entries per day as bars, oldest first.
 *
 * Hand-drawn rather than a chart library: this is one series of at most fourteen integers, and the app has no
 * charting dependency. The bars are scaled to the **window's own maximum** rather than to a fixed ceiling, which
 * is right here and wrong for the tolerance curves: a bar chart of counts has no absolute scale to be honest
 * about, and a fixed one would draw every quiet fortnight as a flat line.
 */
@Composable
private fun Sparkline(counts: List<Int>) {
    if (counts.isEmpty()) return
    val accent = PiruTheme.colors.accent
    val peak = counts.maxOrNull() ?: 0
    Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            if (peak <= 0) return@Canvas
            val slot = size.width / counts.size
            // A gap of a third of the slot, with a minimum so a fourteen-day chart on a narrow phone still has
            // visible bars rather than hairlines.
            val gap = (slot * 0.33f).coerceAtLeast(1f)
            for ((index, count) in counts.withIndex()) {
                val barHeight = size.height * (count.toFloat() / peak)
                // A day with entries always gets a visible bar; a day with none gets a hairline, so the reader
                // can tell "zero" from "not drawn".
                val height = if (count > 0) barHeight.coerceAtLeast(2f) else 1f
                drawRect(
                    color = if (count > 0) accent else accent.copy(alpha = 0.15f),
                    topLeft = Offset(index * slot + gap / 2f, size.height - height),
                    size = Size(slot - gap, height),
                )
            }
        }
    }
}

/**
 * A month of adherence as a grid of small squares.
 *
 * The dose scale's hues rather than semantic green and red, which is upstream's reasoning: a month of green and
 * red reads as a report card, and this card sits beside readings rather than judging them. Missed days are held
 * back further still, because in a thin month they are most of the grid.
 */
@Composable
private fun MonthGrid(statuses: List<InsightsHub.AdherenceSummary.DayStatus?>) {
    if (statuses.isEmpty()) return
    val complete = PiruTheme.colors.accent
    val partial = PiruTheme.colors.accent.copy(alpha = 0.55f)
    val missed = PiruTheme.colors.accent.copy(alpha = 0.18f)
    val blank = PiruTheme.colors.secondaryLabel.copy(alpha = 0.12f)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (week in statuses.chunked(7)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (status in week) {
                    Canvas(modifier = Modifier.weight(1f).height(14.dp)) {
                        val colour = when (status) {
                            InsightsHub.AdherenceSummary.DayStatus.COMPLETE -> complete
                            InsightsHub.AdherenceSummary.DayStatus.PARTIAL -> partial
                            InsightsHub.AdherenceSummary.DayStatus.MISSED -> missed
                            null -> blank
                        }
                        drawRect(color = colour, size = size)
                    }
                }
                // Pad the final week so its squares keep the grid's width.
                repeat(7 - week.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
