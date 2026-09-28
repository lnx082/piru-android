package glass.kagerou.piru.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.SessionDay
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.labels.CoreLabels
import glass.kagerou.piru.ui.labels.appLocale
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.DayOfWeek
import java.time.Instant
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the log looks like over time.
 *
 * Ported from `Views/Insights/UsageStatsView.swift` (140 lines, the coordinator)
 * and the eight section views under `Views/Insights/Usage/` — about 1,800 lines
 * of layout, with the corresponding arithmetic in `InsightsAnalytics.kt` beside
 * this file.
 *
 * ## Eight cards, and the order is the argument
 * The period summary leads, then *when* you use (heatmap and hour), then *how
 * much* (trends), then which days, then where on the ladder those doses sat,
 * then what is used together, how regular it is, and by which route. Each card
 * answers one question and the next only makes sense after the one before it.
 *
 * ## The metric lens is global and deliberately not part of the recompute key
 * Common doses against entries is a re-reading of numbers already computed, so
 * flipping it re-labels the charts and re-sorts the ranking without touching the
 * aggregation. That is why it is absent from the effect key below and present in
 * everything the charts draw.
 *
 * ## Two day-boundary semantics meet here, and only one of them is used twice
 * Every day-bucketed card goes through [InsightsCalendar.sessionDayStart], the
 * configurable 4 AM cut. The Day-of-week card is the exception and reads the raw
 * timestamp's weekday, because Monday 01:00 is Monday to anyone reading a bar
 * chart. The porting spec pins both call sites in
 * `UsageAnalytics.swift`; they are not unified and must not be.
 *
 * ## What is not here
 * The toolbar filter menu. This screen is pushed onto a tab rather than wrapped
 * in a navigation bar, so the range, the metric and the substance filter are a
 * strip of chips at the top of the list instead of a menu in a toolbar. The
 * substance picker is still a modal sheet, which is what upstream makes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication

    var range by rememberSaveable { mutableStateOf(UsageTimeRange.THIRTY_DAYS) }
    var metric by rememberSaveable { mutableStateOf(UsageRankMetric.COMMON_DOSES) }
    var selectedSubstances by remember { mutableStateOf(emptySet<String>()) }
    var showingSubstanceSheet by remember { mutableStateOf(false) }

    var entries by remember { mutableStateOf<List<DoseEntryEntity>>(emptyList()) }
    var snapshots by remember { mutableStateOf<List<UsageEntrySnapshot>>(emptyList()) }
    var refs by remember { mutableStateOf<List<UsageSubstanceRef>>(emptyList()) }
    var tints by remember { mutableStateOf<Map<String, P3Color>>(emptyMap()) }
    var result by remember { mutableStateOf<UsageAnalyticsResult?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    val calendar = remember {
        // The stored day-boundary hour lives in preferences under the key the
        // engine names, and `InsightsCalendar.ambient` validates it. Nothing
        // writes it yet — the settings screen has not landed — so this reads
        // whatever is there and falls back to 4 AM.
        val prefs = context.getSharedPreferences("piru_settings", android.content.Context.MODE_PRIVATE)
        val stored = if (prefs.contains(SessionDay.DAY_BOUNDARY_HOUR_KEY)) {
            prefs.getInt(SessionDay.DAY_BOUNDARY_HOUR_KEY, SessionDay.DEFAULT_BOUNDARY_HOUR)
        } else {
            null
        }
        InsightsCalendar.ambient(stored)
    }

    LaunchedEffect(navigator.dataVersion) {
        loaded = false
        failure = null
        // The failure is kept and shown rather than swallowed: an empty chart
        // that says "nothing logged" is indistinguishable from a read that could
        // not run, and only one of those is worth the user acting on.
        runCatching {
            val log = app.database.doseEntryDao().all()
            val catalog = app.catalog()
            val resolved = UsageResolver.resolve(log, catalog)
            tints = app.palette().tintsFor(log.map { it.substance }.distinct())
            entries = log
            snapshots = resolved.first
            refs = resolved.second
        }.onFailure { failure = it::class.simpleName + ": " + it.message }
        loaded = true
    }

    val filterKey = selectedSubstances.sorted().joinToString("\u0001")

    LaunchedEffect(loaded, range, filterKey) {
        if (!loaded) return@LaunchedEffect
        if (failure != null) {
            result = null
            return@LaunchedEffect
        }
        // Narrow the snapshots to the chosen substances (empty = all), but hand
        // `compute` the **full** substance list: `result.substances` is what
        // populates the filter sheet, so it must keep every substance even when
        // the charts are showing a subset.
        val allowed: Set<Int>? = if (selectedSubstances.isEmpty()) {
            null
        } else {
            refs.withIndex().filter { selectedSubstances.contains(it.value.name) }.map { it.index }.toSet()
        }
        val payload = if (allowed == null) snapshots else snapshots.filter { allowed.contains(it.substanceIndex) }
        val substanceRefs = refs
        val now = Instant.now()
        result = withContext(Dispatchers.Default) {
            UsageAnalytics.compute(payload, substanceRefs, range, calendar, now)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.toolsb_usage_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.toolsb_usage_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        item {
            UsageFilterBar(
                range = range,
                onRange = { range = it },
                metric = metric,
                onMetric = { metric = it },
                substanceCount = selectedSubstances.size,
                offersSubstances = refs.size > 1,
                onSubstances = { showingSubstanceSheet = true },
            )
        }

        failure?.let { message ->
            item {
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.toolsb_usage_error_title), style = MaterialTheme.typography.titleSmall)
                        Text(message, style = MaterialTheme.typography.bodySmall, color = PiruTheme.colors.dangerText)
                    }
                }
            }
        }

        if (failure == null && !loaded) {
            item { LoadingLine(stringResource(R.string.toolsb_usage_loading_entries)) }
        }
        if (failure == null && loaded && entries.isEmpty()) {
            item {
                InsightsEmptyPanel(
                    stringResource(R.string.toolsb_no_logged_entries),
                    stringResource(R.string.toolsb_usage_empty_detail),
                )
            }
        }

        val shown = result
        if (failure == null && loaded && entries.isNotEmpty() && shown == null) {
            item { LoadingLine(stringResource(R.string.toolsb_usage_loading_analysis)) }
        }
        if (shown != null && shown.isEmpty) {
            item {
                InsightsEmptyPanel(
                    stringResource(R.string.toolsb_usage_empty_range_title),
                    stringResource(R.string.toolsb_usage_empty_range_detail),
                )
            }
        }

        if (shown != null && !shown.isEmpty) {
            item { UsageSections(shown, tints, metric) }
        }
    }

    if (showingSubstanceSheet) {
        ModalBottomSheet(onDismissRequest = { showingSubstanceSheet = false }) {
            SubstanceFilterSheetContent(
                substances = refs,
                selection = selectedSubstances,
                onSelection = { selectedSubstances = it },
            )
        }
    }
}

/**
 * The eight cards, in order.
 *
 * One `item` rather than eight: the substance style every card shares is a
 * `remember`, and a `remember` cannot live in a `LazyListScope` lambda — that
 * scope is not composable, only the lambdas handed to `item` are.
 */
@Composable
private fun UsageSections(
    result: UsageAnalyticsResult,
    tints: Map<String, P3Color>,
    metric: UsageRankMetric,
) {
    val style = remember(result.substances, tints) { SubstanceStyle(result.substances, tints) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OverviewSection(result.overview, result.range)
        HeatmapSection(result.heatmap, result.hours, result.categories, metric)
        TrendsSection(result.trends, style, result.range, metric)
        WeekdaySection(result.weekdays, metric)
        DoseLevelSection(result.doseLevels, style, result.range.usesWeeklyBuckets)
        CoUseSection(result.coUse, style, result.categories)
        RegularitySection(result.regularity, style)
        RouteSection(result.routes, style, metric)
        Text(
            stringResource(R.string.toolsb_usage_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = PiruTheme.colors.secondaryLabel,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

// MARK: - Substance labelling

/**
 * Resolves a substance index to the name and colour every section needs.
 *
 * A plain class carried by value so passing it down does not widen any
 * sub-composable's invalidation boundary — it changes only when the aggregation
 * or the user's own colour assignments change.
 */
internal class SubstanceStyle(
    private val substances: List<UsageSubstanceRef>,
    private val colorMap: Map<String, P3Color>,
) {
    fun name(index: Int): String = substances.getOrNull(index)?.displayName ?: "—"

    fun category(index: Int): Int = substances.getOrNull(index)?.categoryIndex ?: -1

    fun color(index: Int): Color {
        val ref = substances.getOrNull(index) ?: return neutral
        val tint = colorMap[ref.name.lowercase()] ?: P3Color.NEUTRAL
        return Color(tint.red.toFloat(), tint.green.toFloat(), tint.blue.toFloat(), 1f)
    }

    private val neutral: Color
        get() = Color(P3Color.NEUTRAL.red.toFloat(), P3Color.NEUTRAL.green.toFloat(), P3Color.NEUTRAL.blue.toFloat(), 1f)
}

// MARK: - Chrome

@Composable
private fun LoadingLine(message: String) {
    Text(message, style = MaterialTheme.typography.bodyMedium, color = PiruTheme.colors.secondaryLabel)
}

@Composable
private fun CenteredNote(message: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            message,
            style = MaterialTheme.typography.labelSmall,
            color = PiruTheme.colors.secondaryLabel,
            modifier = Modifier.padding(vertical = 20.dp),
        )
    }
}

/**
 * The filter strip that stands in for upstream's toolbar menu.
 *
 * Three groups in one scrolling row: the window, the measure, and the substance
 * filter. Keeping them together is the point — upstream puts them in one menu
 * for the same reason, so the screen leads with data rather than with controls.
 */
@Composable
private fun UsageFilterBar(
    range: UsageTimeRange,
    onRange: (UsageTimeRange) -> Unit,
    metric: UsageRankMetric,
    onMetric: (UsageRankMetric) -> Unit,
    substanceCount: Int,
    offersSubstances: Boolean,
    onSubstances: () -> Unit,
) {
    val accent = PiruTheme.colors.accent
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(UsageTimeRange.entries.size) { index ->
            val option = UsageTimeRange.entries[index]
            InsightsFilterPill(
                label = stringResource(option.displayNameRes),
                color = accent,
                isSelected = option == range,
                showDot = false,
                onClick = { onRange(option) },
            )
        }
        items(2) { index ->
            val option = if (index == 0) UsageRankMetric.COMMON_DOSES else UsageRankMetric.ENTRIES
            InsightsFilterPill(
                label = if (option == UsageRankMetric.COMMON_DOSES) {
                    stringResource(R.string.toolsb_usage_metric_common_doses)
                } else {
                    stringResource(R.string.toolsb_usage_metric_entries)
                },
                color = accent,
                isSelected = option == metric,
                showDot = false,
                onClick = { onMetric(option) },
            )
        }
        if (offersSubstances) {
            item {
                InsightsFilterPill(
                    label = if (substanceCount > 0) {
                        stringResource(R.string.toolsb_usage_filter_substances_count, substanceCount)
                    } else {
                        stringResource(R.string.toolsb_usage_filter_all_substances)
                    },
                    color = accent,
                    isSelected = substanceCount > 0,
                    showDot = false,
                    onClick = onSubstances,
                )
            }
        }
    }
}

/**
 * The substance filter, ported from `SubstanceFilterSheet.swift`.
 *
 * The invariant is the whole design: **an empty set and a full set both mean
 * "all"**, so the filter's two ends collapse onto one canonical state. That way
 * the strip only reads as filtered for a genuine subset, and there is no
 * separate "show nothing" trap to fall into.
 */
@Composable
private fun SubstanceFilterSheetContent(
    substances: List<UsageSubstanceRef>,
    selection: Set<String>,
    onSelection: (Set<String>) -> Unit,
) {
    // Materialized from `selection` so the parent's "empty = all" state shows as
    // everything checked, then translated back on every change.
    var shown by remember {
        mutableStateOf(if (selection.isEmpty()) substances.map { it.name }.toSet() else selection)
    }
    var query by remember { mutableStateOf("") }

    fun apply(next: Set<String>) {
        shown = next
        onSelection(if (next.isEmpty() || next.size == substances.size) emptySet() else next)
    }

    val allNames = remember(substances) { substances.map { it.name }.toSet() }
    val allSelected = shown.size >= substances.size

    val groups = remember(substances, query) {
        val q = query.trim().lowercase()
        substances
            .filter { q.isEmpty() || it.displayName.lowercase().contains(q) || it.name.lowercase().contains(q) }
            .groupBy { it.categoryIndex }
            .toSortedMap()
            .map { (category, items) -> category to items.sortedBy { it.displayName } }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.toolsb_substances),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { apply(if (allSelected) emptySet() else allNames) }) {
                Text(
                    if (allSelected) {
                        stringResource(R.string.toolsb_deselect_all)
                    } else {
                        stringResource(R.string.toolsb_select_all)
                    },
                )
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.toolsb_usage_filter_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
            for ((categoryIndex, items) in groups) {
                item(key = "header-$categoryIndex") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            CoreLabels.category(UsageAxes.category(categoryIndex)),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        // "All stimulants" in one tap is the reason this is a
                        // sheet rather than an inline menu.
                        val names = items.map { it.name }
                        val allOn = names.all { shown.contains(it) }
                        TextButton(onClick = { apply(if (allOn) shown - names.toSet() else shown + names.toSet()) }) {
                            Text(
                                if (allOn) {
                                    stringResource(R.string.toolsb_usage_filter_none)
                                } else {
                                    stringResource(R.string.toolsb_usage_filter_all)
                                },
                            )
                        }
                    }
                }
                items(items.size) { index ->
                    val substance = items[index]
                    val isOn = shown.contains(substance.name)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { apply(if (isOn) shown - substance.name else shown + substance.name) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            substance.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (isOn) stringResource(R.string.toolsb_usage_filter_included) else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = PiruTheme.colors.accent,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

// MARK: - §1 Overview

/**
 * Four cards answering "what does my usage look like right now, compared to
 * recently?".
 *
 * Two rows of two rather than a lazy grid: a grid inside a `LazyColumn` needs
 * its own nested scroll and buys nothing here, since the tiles are all measured
 * by weight.
 */
@Composable
private fun OverviewSection(overview: UsageOverview, range: UsageTimeRange) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OverviewCard(
                title = stringResource(R.string.toolsb_usage_overview_this_period),
                value = overview.entryCount.toString(),
                caption = changeText(overview, range),
                modifier = Modifier.weight(1f),
                art = { Sparkline(overview.sparkline) },
            )
            OverviewCard(
                title = stringResource(R.string.toolsb_substances),
                value = overview.uniqueSubstances.toString(),
                caption = if (overview.newSubstances > 0) {
                    stringResource(R.string.toolsb_usage_overview_new_substances, overview.newSubstances)
                } else {
                    null
                },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OverviewCard(
                title = stringResource(R.string.toolsb_usage_overview_per_day),
                value = InsightsFormat.oneDecimal(overview.averagePerDay),
                caption = overview.busiestWeekday?.let {
                    stringResource(R.string.toolsb_usage_overview_most_entries, weekdayName(it))
                },
                modifier = Modifier.weight(1f),
            )
            OverviewCard(
                title = stringResource(R.string.toolsb_usage_overview_dose_level),
                value = overview.doseIntensity?.let { InsightsFormat.percent(it) } ?: "—",
                caption = when {
                    overview.doseIntensity == null -> stringResource(R.string.toolsb_usage_overview_no_ladders)
                    overview.heavyCount > 0 ->
                        stringResource(R.string.toolsb_usage_overview_common_or_above_heavy, overview.heavyCount)
                    else -> stringResource(R.string.toolsb_usage_overview_common_or_above)
                },
                badge = overview.doseIntensity?.let { intensityAccent(it) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The change caption, or the reason there is none.
 *
 * The caption keeps the secondary label colour on purpose: neither direction is
 * "good" or "bad" here — this screen is a record, not a scoreboard — so only the
 * arrow carries the direction.
 */
@Composable
private fun changeText(overview: UsageOverview, range: UsageTimeRange): String? {
    val change = overview.percentChange
    if (change == null) {
        return if (overview.previousEntryCount != null) {
            stringResource(R.string.toolsb_usage_overview_no_previous)
        } else {
            null
        }
    }
    val percent = Math.round(abs(change) * 100)
    val arrow = if (change >= 0) "↑" else "↓"
    // The window's own label, resolved rather than `UsageTimeRange.displayName`,
    // which is the English spelling.
    val rangeLabel = stringResource(range.displayNameRes)
    return stringResource(R.string.toolsb_usage_overview_change, arrow, percent, rangeLabel)
}

@Composable
private fun OverviewCard(
    title: String,
    value: String,
    caption: String?,
    modifier: Modifier = Modifier,
    badge: Color? = null,
    art: @Composable () -> Unit = {},
) {
    PiruCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(14.dp).height(96.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.labelSmall, color = PiruTheme.colors.secondaryLabel)
                if (badge != null) InsightsLegendDot(badge, size = 7.dp)
            }
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (caption != null) {
                Text(
                    caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            art()
        }
    }
}

/** Seven equal buckets across the window. Axis-free on purpose: the shape is the reading. */
@Composable
private fun Sparkline(values: List<Int>) {
    if (values.isEmpty()) return
    val accent = PiruTheme.colors.accent
    val peak = max(values.max(), 1)
    Box(modifier = Modifier.fillMaxWidth().height(26.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val steps = values.size
            if (steps < 2) return@Canvas
            val line = Path()
            val area = Path()
            for ((index, value) in values.withIndex()) {
                val x = size.width * index / (steps - 1)
                val y = size.height * (1f - value.toFloat() / peak)
                if (index == 0) {
                    line.moveTo(x, y)
                    area.moveTo(x, y)
                } else {
                    line.lineTo(x, y)
                    area.lineTo(x, y)
                }
            }
            area.lineTo(size.width, size.height)
            area.lineTo(0f, size.height)
            area.close()
            drawPath(area, color = accent.copy(alpha = 0.22f))
            drawPath(line, color = accent, style = Stroke(width = 2.5f))
        }
    }
}

// MARK: - §2 Activity: heatmap and hour histogram

/**
 * "When do I tend to use?" A contribution grid over an hour-of-day histogram.
 *
 * The grid is a grid, not a chart, so it is drawn as rectangles rather than as a
 * line — the same call upstream makes when it draws this one with shapes instead
 * of Swift Charts.
 */
@Composable
private fun HeatmapSection(
    heatmap: UsageHeatmap,
    hours: UsageHourProfile,
    categories: List<UsageCategoryCount>,
    metric: UsageRankMetric,
) {
    var categoryFilter by remember { mutableStateOf<Int?>(null) }
    var selectedDay by remember { mutableStateOf<Instant?>(null) }
    val accent = categoryFilter?.let { categoryAccent(UsageAxes.category(it)) } ?: PiruTheme.colors.accent

    LaunchedEffect(heatmap.weekStarts.firstOrNull()) { selectedDay = null }

    InsightsSectionCard(title = stringResource(R.string.toolsb_usage_section_activity)) {
        if (categories.size > 1) {
            InsightsCategoryFilterBar(categories, categoryFilter) { categoryFilter = it }
        }

        HeatmapGrid(
            heatmap = heatmap,
            categoryFilter = categoryFilter,
            metric = metric,
            accent = accent,
            selectedDay = selectedDay,
            onSelectDay = { selectedDay = it },
        )

        val source = selectedDay?.let { hours.byDay[it] } ?: hours.all
        val bins = if (metric == UsageRankMetric.COMMON_DOSES) {
            source.commonBins(categoryFilter)
        } else {
            source.bins(categoryFilter).map { it.toDouble() }
        }
        HourHistogram(bins, accent, selectedDay) { selectedDay = null }
    }
}

/** The cell edge for one column set, and the columns to draw. */
private class GridLayout(val weekStarts: List<Instant>, val cellPx: Float)

private const val CELL_RADIUS_PX = 4f

/**
 * Resolves the columns and the cell size against the available width.
 *
 * Upstream's arithmetic exactly: how many base-sized cells fit, and — when the
 * range has fewer weeks than that — how many leading blank weeks to add and how
 * wide each cell then becomes, so the block spans the card rather than floating
 * in a corner.
 */
private fun buildLayout(
    actual: List<Instant>,
    widthPx: Float,
    labelWidthPx: Float,
    cellSpacingPx: Float,
    baseCellPx: Float,
): GridLayout {
    if (actual.isEmpty()) return GridLayout(emptyList(), baseCellPx)
    if (widthPx <= labelWidthPx + baseCellPx) return GridLayout(actual, baseCellPx)

    val available = widthPx - labelWidthPx - cellSpacingPx
    val fit = max(1, ((available + cellSpacingPx) / (baseCellPx + cellSpacingPx)).toInt())
    if (actual.size >= fit) return GridLayout(actual, baseCellPx)

    val zone = ZoneId.systemDefault()
    val firstLocal = actual.first().atZone(zone).toLocalDate()
    val padded = (fit - actual.size downTo 1).map { firstLocal.minusWeeks(it.toLong()).atStartOfDay(zone).toInstant() }
    val weekStarts = padded + actual
    val cell = (available - (weekStarts.size - 1) * cellSpacingPx) / weekStarts.size
    return GridLayout(weekStarts, cell)
}

/** The active metric's value for a cell, narrowed to the filtered category when one is active. */
private fun displayValue(cell: UsageHeatmapCell?, categoryFilter: Int?, metric: UsageRankMetric): Double {
    if (cell == null || !cell.inRange) return 0.0
    return when (metric) {
        UsageRankMetric.ENTRIES -> (categoryFilter?.let { cell.byCategory[it] ?: 0 } ?: cell.total).toDouble()
        UsageRankMetric.COMMON_DOSES -> categoryFilter?.let { cell.byCategoryCommon[it] ?: 0.0 } ?: cell.commonTotal
    }
}

/**
 * The contribution grid: one column per week, seven rows per column.
 *
 * GitHub-shaped, and it **fills the card width**: a short range is padded on the
 * left with earlier week columns so the block spans the card, while a long range
 * keeps a fixed cell and scrolls, anchored to the most recent week. Every past
 * day is a square — coloured by intensity when something was logged, grey when
 * nothing was — so a quiet stretch reads as a real absence rather than a hole.
 * Only the rest of the current week stays blank.
 */
@Composable
private fun HeatmapGrid(
    heatmap: UsageHeatmap,
    categoryFilter: Int?,
    metric: UsageRankMetric,
    accent: Color,
    selectedDay: Instant?,
    onSelectDay: (Instant?) -> Unit,
) {
    val emptyCell = PiruTheme.colors.secondaryLabel.copy(alpha = 0.18f)
    val selectionInk = PiruTheme.colors.accent

    val cellSpacingPx = 6f
    val baseCellPx = 32f
    val labelWidthPx = 60f
    val cellSpacingDp = 3.dp

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val widthPx = constraints.maxWidth.toFloat()
        val actual = heatmap.weekStarts
        val layout = remember(widthPx, actual) {
            buildLayout(actual, widthPx, labelWidthPx, cellSpacingPx, baseCellPx)
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(cellSpacingDp)) {
            // The labels are a fixed sidebar *outside* the scrolling grid: the
            // grid opens scrolled to the newest week, and labels riding inside
            // would scroll off the leading edge with it.
            Column(modifier = Modifier.width(30.dp), verticalArrangement = Arrangement.spacedBy(cellSpacingDp)) {
                Spacer(modifier = Modifier.height(11.dp))
                for ((row, weekday) in heatmap.rowWeekdays.withIndex()) {
                    Box(
                        modifier = Modifier.height((layout.cellPx / 3f).dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        // Every other row, so seven names do not crowd the stack.
                        if (row % 2 == 0) {
                            Text(
                                shortWeekday(weekday),
                                style = MaterialTheme.typography.labelSmall,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                        }
                    }
                }
            }

            val listState = rememberLazyListState()
            LaunchedEffect(layout.weekStarts.size) {
                // A year of columns opens on the most recent week; a range that
                // already fills the width does not move.
                if (layout.weekStarts.size > 1) listState.scrollToItem(layout.weekStarts.size - 1)
            }

            LazyRow(
                state = listState,
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(cellSpacingDp),
            ) {
                items(layout.weekStarts.size) { column ->
                    val weekStart = layout.weekStarts[column]
                    val previous = layout.weekStarts.getOrNull(column - 1)
                    val isNewMonth = previous == null || monthOf(previous) != monthOf(weekStart)
                    Column(verticalArrangement = Arrangement.spacedBy(cellSpacingDp)) {
                        Box(modifier = Modifier.height(11.dp)) {
                            if (isNewMonth) {
                                Text(
                                    monthLabel(weekStart),
                                    fontSize = 8.sp,
                                    color = PiruTheme.colors.secondaryLabel,
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .width(layout.cellPx.dp)
                                // Seven cells plus the six gaps between them: any
                                // shorter and the draw scope clips the last row.
                                .height((layout.cellPx * 7 + cellSpacingPx * 6).dp)
                                .pointerInput(column, layout.cellPx) {
                                    detectTapGestures { offset ->
                                        val row = (offset.y / (layout.cellPx + cellSpacingPx)).toInt()
                                        if (row !in 0..6) return@detectTapGestures
                                        val cell = heatmap.cells.getOrNull(column * 7 + row) ?: return@detectTapGestures
                                        val value = displayValue(cell, categoryFilter, metric)
                                        if (value <= 0 || cell.date > heatmap.lastInRange) return@detectTapGestures
                                        onSelectDay(if (selectedDay == cell.date) null else cell.date)
                                    }
                                },
                        ) {
                            Canvas(Modifier.fillMaxSize()) {
                                val peak = max(
                                    if (metric == UsageRankMetric.COMMON_DOSES) heatmap.maxCommon else heatmap.maxCount.toDouble(),
                                    0.0001,
                                )
                                for (row in 0 until 7) {
                                    val cell = heatmap.cells.getOrNull(column * 7 + row)
                                    val value = displayValue(cell, categoryFilter, metric)
                                    val isFuture = cell == null || cell.date > heatmap.lastInRange
                                    val fill = when {
                                        isFuture -> Color.Transparent
                                        value <= 0 -> emptyCell
                                        else -> accent.copy(alpha = 0.28f + 0.72f * min(1.0, value / peak).toFloat())
                                    }
                                    val top = row * (layout.cellPx + cellSpacingPx)
                                    val radius = CornerRadius(CELL_RADIUS_PX)
                                    if (fill != Color.Transparent) {
                                        drawRoundRect(
                                            color = fill,
                                            topLeft = Offset(0f, top),
                                            size = Size(layout.cellPx, layout.cellPx),
                                            cornerRadius = radius,
                                        )
                                    }
                                    if (cell != null && cell.date == selectedDay && value > 0) {
                                        drawRoundRect(
                                            color = selectionInk,
                                            topLeft = Offset(0.75f, top + 0.75f),
                                            size = Size(layout.cellPx - 1.5f, layout.cellPx - 1.5f),
                                            cornerRadius = radius,
                                            style = Stroke(width = 1.5f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Twenty-four bins across the clock.
 *
 * Drawn on a continuous x so the axis can label 0/6/12/18 — the same reason
 * upstream gives the bars a fixed width rather than a ratio.
 */
@Composable
private fun HourHistogram(
    bins: List<Double>,
    accent: Color,
    selectedDay: Instant?,
    onClearDay: () -> Unit,
) {
    if (bins.isEmpty()) return
    val gridInk = PiruTheme.colors.secondaryLabel.copy(alpha = 0.3f)
    val trackInk = PiruTheme.colors.tertiaryLabel

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.toolsb_usage_hour_of_day),
                style = MaterialTheme.typography.labelMedium,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.weight(1f),
            )
            if (selectedDay != null) {
                Text(
                    "${shortDate(selectedDay, LocalContext.current.getString(R.string.datefmt_day_month), appLocale())}  ✕",
                    style = MaterialTheme.typography.labelSmall,
                    color = PiruTheme.colors.secondaryLabel,
                    modifier = Modifier.clickable(onClick = onClearDay),
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(110.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val peak = max(bins.maxOrNull() ?: 0.0, 0.0001)
                val plotHeight = size.height - 2f
                val step = size.width / 24f
                val barWidth = min(14f, step * 0.55f)
                for (hour in 0 until 24) {
                    val value = bins.getOrNull(hour) ?: 0.0
                    val x = step * (hour + 0.5f)
                    drawLine(trackInk, Offset(x, 0f), Offset(x, plotHeight), strokeWidth = 1f)
                    if (value > 0) {
                        val barHeight = (value / peak * plotHeight).toFloat()
                        drawRoundRect(
                            color = accent,
                            topLeft = Offset(x - barWidth / 2f, plotHeight - barHeight),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(3f),
                        )
                    }
                }
                drawLine(gridInk, Offset(0f, plotHeight), Offset(size.width, plotHeight), strokeWidth = 1f)
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            for (hour in listOf(0, 6, 12, 18)) {
                Text(
                    hourLabel(hour),
                    style = MaterialTheme.typography.labelSmall,
                    color = PiruTheme.colors.secondaryLabel,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// MARK: - §3 Trends

/**
 * "How has my use of each substance changed?" One line per substance, in
 * entries per week whatever the window is, so the y-axis reads the same on 7D
 * and on All.
 *
 * In common-dose mode a substance with no common-dose data would draw flat on
 * zero and read as "unused", so it is dropped from both the chart and the legend
 * rather than lying about it.
 */
@Composable
private fun TrendsSection(
    trends: List<UsageTrendSeries>,
    style: SubstanceStyle,
    range: UsageTimeRange,
    metric: UsageRankMetric,
) {
    var hidden by remember { mutableStateOf(emptySet<Int>()) }
    var showsAll by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf<Instant?>(null) }

    LaunchedEffect(range) {
        hidden = emptySet()
        selectedDate = null
    }

    val metricTrends = if (metric == UsageRankMetric.COMMON_DOSES) trends.filter { it.hasCommonDoses } else trends
    val legendTrends = if (showsAll) metricTrends else metricTrends.take(UsageAnalytics.DEFAULT_TREND_SUBSTANCES)
    val visible = legendTrends.filterNot { hidden.contains(it.substanceIndex) }
    // Hiding every line would leave an empty plot with no way back, so the
    // legend can never zero the chart out.
    val shown = visible.ifEmpty { legendTrends }

    InsightsSectionCard(
        title = stringResource(R.string.toolsb_usage_section_trends),
        subtitle = trendsSubtitle(metric, range),
    ) {
        if (trends.isEmpty()) {
            CenteredNote(stringResource(R.string.toolsb_usage_trends_no_history))
        } else if (metricTrends.isEmpty()) {
            CenteredNote(stringResource(R.string.toolsb_usage_trends_no_common_dose))
        } else {
            TrendsChart(
                series = shown,
                style = style,
                perWeek = range.trendPerWeek,
                metric = metric,
                selectedDate = selectedDate,
                onSelect = { selectedDate = it },
            )
            selectedDate?.let { TrendsReadout(shown, style, metric, range.trendPerWeek, it) }
            InsightsChipFlow(spacing = 12.dp) {
                for (item in legendTrends) {
                    val isHidden = hidden.contains(item.substanceIndex)
                    val name = style.name(item.substanceIndex)
                    InsightsFilterPill(
                        label = if (isHidden) stringResource(R.string.toolsb_usage_trends_hidden_suffix, name) else name,
                        color = style.color(item.substanceIndex),
                        isSelected = !isHidden,
                        onClick = {
                            hidden = if (isHidden) hidden - item.substanceIndex else hidden + item.substanceIndex
                        },
                    )
                }
            }
            if (metricTrends.size > UsageAnalytics.DEFAULT_TREND_SUBSTANCES) {
                Text(
                    if (showsAll) {
                        stringResource(R.string.toolsb_usage_trends_show_fewer)
                    } else {
                        stringResource(R.string.toolsb_usage_trends_show_all, metricTrends.size)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = PiruTheme.colors.accent,
                    modifier = Modifier.clickable { showsAll = !showsAll },
                )
            }
        }
    }
}

@Composable
private fun trendsSubtitle(metric: UsageRankMetric, range: UsageTimeRange): String = when {
    metric == UsageRankMetric.ENTRIES && range == UsageTimeRange.SEVEN_DAYS ->
        stringResource(R.string.toolsb_usage_trend_subtitle_entries_per_day)
    metric == UsageRankMetric.COMMON_DOSES && range == UsageTimeRange.SEVEN_DAYS ->
        stringResource(R.string.toolsb_usage_trend_subtitle_common_doses_per_day)
    metric == UsageRankMetric.ENTRIES && range == UsageTimeRange.THIRTY_DAYS ->
        stringResource(R.string.toolsb_usage_trend_subtitle_entries_per_week_7)
    metric == UsageRankMetric.COMMON_DOSES && range == UsageTimeRange.THIRTY_DAYS ->
        stringResource(R.string.toolsb_usage_trend_subtitle_common_doses_per_week_7)
    metric == UsageRankMetric.ENTRIES ->
        stringResource(R.string.toolsb_usage_trend_subtitle_entries_per_week_4)
    else ->
        stringResource(R.string.toolsb_usage_trend_subtitle_common_doses_per_week_4)
}

/**
 * The multi-line chart, windowed and pannable.
 *
 * ## Tap selects, drag pans, and they are deliberately different gestures
 * Upstream layers a scroll view under a chart-selection gesture and lets the
 * scroll view claim the drag. Compose does no such arbitration for us, so the
 * two are split explicitly: a tap puts the scrub readout at the tapped instant,
 * a horizontal drag moves the visible window. One gesture, one job.
 */
@Composable
private fun TrendsChart(
    series: List<UsageTrendSeries>,
    style: SubstanceStyle,
    perWeek: Boolean,
    metric: UsageRankMetric,
    selectedDate: Instant?,
    onSelect: (Instant?) -> Unit,
) {
    val gridInk = PiruTheme.colors.secondaryLabel.copy(alpha = 0.25f)
    val ruleInk = PiruTheme.colors.secondaryLabel.copy(alpha = 0.45f)
    val labelInk = PiruTheme.colors.secondaryLabel
    val measurer = rememberTextMeasurer()

    val dates = series.flatMap { item -> item.points.map { it.date } }
    val first = dates.minOrNull()
    val last = dates.maxOrNull()
    val spanMillis = if (first != null && last != null) (last.toEpochMilli() - first.toEpochMilli()).toDouble() else 1.0
    val viewport = rememberViewportState(spanMillis, USAGE_CHART_WINDOW_SECONDS * 1000)

    val visibleFrom = first?.let {
        Instant.ofEpochMilli(it.toEpochMilli() + (spanMillis * viewport.offsetFraction).toLong())
    }
    val visibleTo = visibleFrom?.let { Instant.ofEpochMilli(it.toEpochMilli() + viewport.visibleMillis.toLong()) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(190.dp)) {
            val widthPx = constraints.maxWidth.toFloat()
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(viewport.scrollable(), series.size) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            viewport.pan(dragAmount.x, widthPx)
                        }
                    }
                    .pointerInput(visibleFrom, visibleTo, series.size) {
                        detectTapGestures { offset ->
                            val from = visibleFrom ?: return@detectTapGestures
                            val to = visibleTo ?: return@detectTapGestures
                            val fraction = (offset.x / widthPx).coerceIn(0f, 1f)
                            val at = from.toEpochMilli() + ((to.toEpochMilli() - from.toEpochMilli()) * fraction).toLong()
                            onSelect(Instant.ofEpochMilli(at))
                        }
                    },
            ) {
                val from = visibleFrom ?: return@Canvas
                val to = visibleTo ?: return@Canvas
                val spanVisible = max(1L, to.toEpochMilli() - from.toEpochMilli()).toDouble()

                val peak = max(
                    series.flatMap { item -> item.points.map { value(it, metric) } }.maxOrNull() ?: 0.0,
                    0.0001,
                )

                fun xFor(date: Instant): Float =
                    (((date.toEpochMilli() - from.toEpochMilli()) / spanVisible) * size.width).toFloat()

                fun yFor(value: Double): Float = size.height * (1f - (value / peak).toFloat())

                drawLine(gridInk, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
                drawText(
                    measurer.measure(InsightsFormat.oneDecimal(peak), TextStyle(fontSize = 10.sp, color = labelInk)),
                    topLeft = Offset(2f, 2f),
                )

                for (item in series) {
                    val points = item.points.filter { it.date >= from && it.date <= to }
                    if (points.isEmpty()) continue
                    val path = Path()
                    var started = false
                    for (point in points) {
                        val x = xFor(point.date)
                        val y = yFor(value(point, metric))
                        if (started) path.lineTo(x, y) else path.moveTo(x, y)
                        started = true
                    }
                    drawPath(path, color = style.color(item.substanceIndex), style = Stroke(width = 3f))
                }

                if (selectedDate != null && selectedDate >= from && selectedDate <= to) {
                    val x = xFor(selectedDate)
                    var y = 0f
                    while (y < size.height) {
                        drawLine(ruleInk, Offset(x, y), Offset(x, min(y + 6f, size.height)), strokeWidth = 1.5f)
                        y += 12f
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                visibleFrom?.let { shortDate(it, LocalContext.current.getString(R.string.datefmt_day_month), appLocale()) } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = PiruTheme.colors.secondaryLabel,
            )
            Text(
                if (perWeek) {
                    stringResource(R.string.toolsb_usage_trends_axis_per_week)
                } else {
                    stringResource(R.string.toolsb_usage_trends_axis_per_day)
                },
                style = MaterialTheme.typography.labelSmall,
                color = PiruTheme.colors.secondaryLabel,
            )
            Text(
                visibleTo?.let { shortDate(it, LocalContext.current.getString(R.string.datefmt_day_month), appLocale()) } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
        Text(
            stringResource(R.string.toolsb_usage_trends_hint),
            style = MaterialTheme.typography.labelSmall,
            color = PiruTheme.colors.secondaryLabel,
        )
    }
}

private fun value(point: UsageTrendPoint, metric: UsageRankMetric): Double =
    if (metric == UsageRankMetric.COMMON_DOSES) point.commonValue else point.value

/** The scrub readout: exact per-substance rates at the nearest sampled bucket. */
@Composable
private fun TrendsReadout(
    series: List<UsageTrendSeries>,
    style: SubstanceStyle,
    metric: UsageRankMetric,
    perWeek: Boolean,
    date: Instant,
) {
    val rows = series.mapNotNull { item ->
        val point = item.points.minByOrNull { abs(it.date.toEpochMilli() - date.toEpochMilli()) }
            ?: return@mapNotNull null
        val value = value(point, metric)
        if (value <= 0) null else item.substanceIndex to value
    }.sortedByDescending { it.second }

    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                shortDate(date, stringResource(R.string.datefmt_day_month), appLocale()),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            for ((index, amount) in rows) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    InsightsLegendDot(style.color(index), size = 7.dp)
                    Text(style.name(index), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    Text(
                        if (perWeek) {
                            stringResource(R.string.toolsb_usage_trends_readout_per_week, InsightsFormat.oneDecimal(amount))
                        } else {
                            stringResource(R.string.toolsb_usage_trends_readout_per_day, InsightsFormat.oneDecimal(amount))
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
            if (rows.isEmpty()) {
                Text(
                    stringResource(R.string.toolsb_usage_trends_readout_empty),
                    style = MaterialTheme.typography.labelSmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
    }
}

// MARK: - §8 Day of week

/**
 * Seven bars, one per weekday, with the per-weekday average underneath.
 *
 * Deliberately a **single colour**, not stacked by class: upstream's class split
 * carried no legend, so a dozen category colours read as noise rather than
 * information — and the question this card asks is "which days", which the total
 * answers directly.
 */
@Composable
private fun WeekdaySection(buckets: List<UsageWeekdayBucket>, metric: UsageRankMetric) {
    val accent = PiruTheme.colors.accent
    val gridInk = PiruTheme.colors.secondaryLabel.copy(alpha = 0.25f)

    fun value(bucket: UsageWeekdayBucket): Double =
        if (metric == UsageRankMetric.COMMON_DOSES) (bucket.commonTotal ?: 0.0) else bucket.total.toDouble()

    fun average(bucket: UsageWeekdayBucket): Double =
        if (metric == UsageRankMetric.COMMON_DOSES) (bucket.commonAverage ?: 0.0) else bucket.average

    InsightsSectionCard(title = stringResource(R.string.toolsb_usage_section_weekday)) {
        Box(modifier = Modifier.fillMaxWidth().height(170.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val peak = max(buckets.maxOfOrNull { value(it) } ?: 0.0, 0.0001)
                val step = size.width / max(buckets.size, 1)
                val barWidth = step * 0.55f
                for ((index, bucket) in buckets.withIndex()) {
                    val barHeight = (value(bucket) / peak * (size.height - 4f)).toFloat()
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(step * index + (step - barWidth) / 2f, size.height - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(4f),
                    )
                }
                drawLine(gridInk, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), strokeWidth = 1f)
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            for (bucket in buckets) {
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        shortWeekday(bucket.weekday),
                        style = MaterialTheme.typography.labelSmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                    Text(
                        InsightsFormat.oneDecimal(average(bucket)),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

// MARK: - §4 Dose levels

/**
 * Where the period's doses sat on each substance's own ladder, over time.
 *
 * Two honesty rules are load-bearing. The footnote always says **how many** of
 * the period's entries could be placed on a ladder — a dose logged in mL against
 * a ladder written in mg is not comparable, and neither is a substance the
 * catalog does not carry, so those entries are excluded rather than guessed at.
 * And below 30% coverage the section demotes itself to a collapsed disclosure,
 * because a chart built from a minority of the data reads as if it described all
 * of it.
 */
@Composable
private fun DoseLevelSection(
    breakdown: UsageDoseLevelBreakdown,
    style: SubstanceStyle,
    weekly: Boolean,
) {
    // Nothing resolved: the whole section is absent rather than empty.
    if (breakdown.resolvedEntries == 0) return

    if (breakdown.isLowCoverage) {
        InsightsCollapsibleCard(
            title = stringResource(R.string.toolsb_usage_section_dose_levels),
            subtitle = stringResource(R.string.toolsb_usage_dose_levels_low_coverage),
            storageKey = "usageSection.doseLevels",
            defaultExpanded = false,
        ) {
            DoseLevelContent(breakdown, style, weekly)
        }
    } else {
        InsightsSectionCard(title = stringResource(R.string.toolsb_usage_section_dose_levels)) {
            DoseLevelContent(breakdown, style, weekly)
        }
    }
}

/**
 * The ladder's own name for a level.
 *
 * `DoseLevel` carries only `wireValue` — "Sub-threshold", "Heavy" — and that
 * spelling is the only name the model has, so the labels are resources here.
 * Upstream translates the same six words, so these are not left in English.
 */
@Composable
private fun doseLevelLabel(level: Int): String = stringResource(
    when (level) {
        0 -> R.string.toolsb_usage_dose_level_sub
        1 -> R.string.toolsb_usage_dose_level_threshold
        2 -> R.string.toolsb_usage_dose_level_light
        3 -> R.string.toolsb_usage_dose_level_common
        4 -> R.string.toolsb_usage_dose_level_strong
        else -> R.string.toolsb_usage_dose_level_heavy
    },
)

@Composable
private fun DoseLevelContent(
    breakdown: UsageDoseLevelBreakdown,
    style: SubstanceStyle,
    weekly: Boolean,
) {
    var selectedSubstance by remember { mutableStateOf<Int?>(null) }
    var highlightedLevel by remember { mutableStateOf<Int?>(null) }

    val buckets = selectedSubstance?.let { breakdown.bySubstance[it].orEmpty() } ?: breakdown.overall
    // Pooled across substances the y-axis has to be a share — one substance's
    // milligrams mean nothing against another's. Narrowed to a single substance,
    // absolute counts are both meaningful and more useful.
    val showsPercentages = selectedSubstance == null

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            InsightsFilterPill(
                label = stringResource(R.string.toolsb_usage_filter_all),
                color = PiruTheme.colors.accent,
                isSelected = selectedSubstance == null,
                showDot = false,
                onClick = { selectedSubstance = null },
            )
            for (index in breakdown.selectableSubstances) {
                InsightsFilterPill(
                    label = style.name(index),
                    color = style.color(index),
                    isSelected = selectedSubstance == index,
                    showDot = false,
                    onClick = { selectedSubstance = if (selectedSubstance == index) null else index },
                )
            }
        }

        DoseLevelChart(buckets, showsPercentages, highlightedLevel, weekly)

        InsightsChipFlow(spacing = 12.dp) {
            for (level in UsageAxes.doseLevelOrder) {
                val count = buckets.sumOf { it.counts[level] ?: 0 }
                if (count == 0) continue
                val dimmed = highlightedLevel != null && highlightedLevel != level
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clickable { highlightedLevel = if (highlightedLevel == level) null else level }
                        .padding(2.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(doseLevelAccent(level).copy(alpha = if (dimmed) 0.4f else 1f)),
                    )
                    Text(doseLevelLabel(level), style = MaterialTheme.typography.labelSmall)
                    Text(
                        count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        Text(
            stringResource(
                R.string.toolsb_usage_dose_levels_coverage,
                breakdown.resolvedEntries,
                breakdown.totalEntries,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = PiruTheme.colors.secondaryLabel,
        )
    }
}

/**
 * Stacked columns, one per bucket. Discrete counts, so nothing is smoothed
 * between them — a stacked area would cross its own bands into a tangle.
 */
@Composable
private fun DoseLevelChart(
    buckets: List<UsageDoseLevelBucket>,
    showsPercentages: Boolean,
    highlightedLevel: Int?,
    weekly: Boolean,
) {
    val gridInk = PiruTheme.colors.secondaryLabel.copy(alpha = 0.25f)
    val labelInk = PiruTheme.colors.secondaryLabel
    val colors = UsageAxes.doseLevelOrder.associateWith { doseLevelAccent(it) }
    val measurer = rememberTextMeasurer()
    // Resolved here, not inside the draw scope: a string read is a composable
    // call and `Canvas { }` is a plain lambda.
    val weeklyLabel = stringResource(R.string.toolsb_usage_dose_levels_axis_weekly)

    val first = buckets.minOfOrNull { it.date }
    val last = buckets.maxOfOrNull { it.date }
    val spanMillis = if (first != null && last != null) (last.toEpochMilli() - first.toEpochMilli()).toDouble() else 1.0
    val viewport = rememberViewportState(spanMillis, USAGE_CHART_WINDOW_SECONDS * 1000)
    val visibleFrom = first?.let {
        Instant.ofEpochMilli(it.toEpochMilli() + (spanMillis * viewport.offsetFraction).toLong())
    }
    val visibleTo = visibleFrom?.let { Instant.ofEpochMilli(it.toEpochMilli() + viewport.visibleMillis.toLong()) }
    val peakTotal = max(buckets.maxOfOrNull { it.total } ?: 1, 1)

    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(170.dp)) {
        val widthPx = constraints.maxWidth.toFloat()
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(viewport.scrollable(), buckets.size) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        viewport.pan(dragAmount.x, widthPx)
                    }
                },
        ) {
            val from = visibleFrom ?: return@Canvas
            val to = visibleTo ?: return@Canvas
            val visibleBuckets = buckets.filter { it.date >= from && it.date <= to }
            if (visibleBuckets.isEmpty()) return@Canvas

            val step = size.width / visibleBuckets.size
            val barWidth = step * 0.7f
            for ((index, bucket) in visibleBuckets.withIndex()) {
                var y = size.height
                for (level in UsageAxes.doseLevelOrder) {
                    val count = bucket.counts[level] ?: 0
                    if (count <= 0) continue
                    // Pooled across substances the column has to be a share;
                    // inside one substance absolute counts are the useful figure.
                    val fraction = if (showsPercentages) {
                        count.toDouble() / max(bucket.total, 1)
                    } else {
                        count.toDouble() / peakTotal
                    }
                    val barHeight = (fraction * size.height).toFloat()
                    val alpha = if (highlightedLevel == null || highlightedLevel == level) 1f else 0.18f
                    drawRect(
                        color = colors.getValue(level).copy(alpha = alpha),
                        topLeft = Offset(step * index + (step - barWidth) / 2f, y - barHeight),
                        size = Size(barWidth, barHeight),
                    )
                    y -= barHeight
                }
            }
            drawLine(gridInk, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
            drawText(
                measurer.measure(
                    if (showsPercentages) "100%" else peakTotal.toString(),
                    TextStyle(fontSize = 10.sp, color = labelInk),
                ),
                topLeft = Offset(2f, 2f),
            )
            if (weekly) {
                drawText(
                    measurer.measure(weeklyLabel, TextStyle(fontSize = 10.sp, color = labelInk)),
                    topLeft = Offset(2f, 16f),
                )
            }
        }
    }
}

// MARK: - §6 Used together

/**
 * Which substances get logged on the same day.
 *
 * A ranked list rather than a matrix: with a handful of substances a matrix is
 * mostly empty cells, and the answer the user wants — "what goes with what, how
 * often" — is a sentence.
 */
@Composable
private fun CoUseSection(
    pairs: List<UsageCoUsePair>,
    style: SubstanceStyle,
    categories: List<UsageCategoryCount>,
) {
    if (pairs.isEmpty()) return
    var categoryFilter by remember { mutableStateOf<Int?>(null) }

    val filtered = pairs.filter { pair ->
        val filter = categoryFilter ?: return@filter true
        // Either side matching keeps a supplement-by-stimulant pair visible under
        // both filters, which is what someone filtering to "Stimulant" means.
        style.category(pair.firstIndex) == filter || style.category(pair.secondIndex) == filter
    }

    InsightsCollapsibleCard(title = stringResource(R.string.toolsb_usage_section_co_use), storageKey = "usageSection.coUse") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (categories.size > 1) {
                InsightsCategoryFilterBar(categories, categoryFilter) { categoryFilter = it }
            }
            if (filtered.isEmpty()) {
                Text(
                    stringResource(R.string.toolsb_usage_co_use_empty),
                    style = MaterialTheme.typography.labelSmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
            for (pair in filtered) CoUseRow(pair, style)
        }
    }
}

@Composable
private fun CoUseRow(pair: UsageCoUsePair, style: SubstanceStyle) {
    val trackInk = PiruTheme.colors.secondaryLabel.copy(alpha = 0.2f)
    val fill = style.color(pair.firstIndex).copy(alpha = 0.75f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(-3.dp)) {
                InsightsLegendDot(style.color(pair.firstIndex), size = 11.dp)
                InsightsLegendDot(style.color(pair.secondIndex), size = 11.dp)
            }
            Text(
                "${style.name(pair.firstIndex)} + ${style.name(pair.secondIndex)}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.toolsb_usage_co_use_days, pair.days),
                style = MaterialTheme.typography.labelSmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(4.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(color = trackInk, cornerRadius = CornerRadius(size.height / 2))
                drawRoundRect(
                    color = fill,
                    size = Size(max(size.width * pair.overlap.toFloat(), 3f), size.height),
                    cornerRadius = CornerRadius(size.height / 2),
                )
            }
        }
    }
}

// MARK: - §7 Regularity

/**
 * "Do I use on a schedule, or sporadically?"
 *
 * The mean gap between the *days* a substance was logged, and the coefficient of
 * variation of those gaps. A low CV means the gaps are all about the same length
 * — a routine. A high one means bursts and droughts.
 */
@Composable
private fun RegularitySection(rows: List<UsageRegularity>, style: SubstanceStyle) {
    if (rows.isEmpty()) return
    InsightsCollapsibleCard(title = stringResource(R.string.toolsb_usage_section_regularity), storageKey = "usageSection.regularity") {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (row in rows) RegularityRow(row, style)
        }
    }
}

@Composable
private fun RegularityRow(row: UsageRegularity, style: SubstanceStyle) {
    val trackInk = PiruTheme.colors.secondaryLabel.copy(alpha = 0.2f)
    val fill = regularityTierAccent(row.tier)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InsightsLegendDot(style.color(row.substanceIndex), size = 9.dp)
            Text(
                style.name(row.substanceIndex),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    // Always one decimal: "every 1.0 days" is upstream's own
                    // wording, and it keeps the phrase grammatical without
                    // inflecting a fractional noun. The figure is still
                    // formatted at `Locale.ROOT` and passed as a string, so the
                    // decimal point does not follow the device's locale.
                    stringResource(
                        R.string.toolsb_usage_regularity_every_days,
                        String.format(Locale.ROOT, "%.1f", row.meanIntervalDays),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
                Text(
                    stringResource(row.tier.displayNameRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(5.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(color = trackInk, cornerRadius = CornerRadius(size.height / 2))
                drawRoundRect(
                    color = fill,
                    size = Size(max(size.width * row.fill.toFloat(), 4f), size.height),
                    cornerRadius = CornerRadius(size.height / 2),
                )
            }
        }
    }
}

// MARK: - §5 Most logged

/**
 * The substance ranking: one bar per substance, segmented and coloured by route,
 * ranked by how *often* it was logged or by total common-dose units.
 *
 * The two rankings genuinely disagree — a gram-dosed botanical logged in small
 * sips tops the entry count while sitting mid-pack once each sip is weighed
 * against a common dose, and a milligram stimulant redosed above common does the
 * reverse — and the movement between them *is* the insight a raw count hides.
 *
 * Drawn with shapes rather than a bar chart, as upstream does: a categorical bar
 * chart reserves no room for long substance names on a leading axis.
 */
@Composable
private fun RouteSection(
    breakdown: UsageRouteBreakdown,
    style: SubstanceStyle,
    metric: UsageRankMetric,
) {
    if (breakdown.rows.isEmpty()) return

    val ranked = if (metric == UsageRankMetric.ENTRIES) {
        breakdown.rows
    } else {
        // Substances with no common-dose value sink below every substance that
        // has one, in their entry-count order, so the "—" rows read as a labelled
        // tail rather than salting the ranking.
        breakdown.rows.sortedWith(
            Comparator { lhs, rhs ->
                val left = lhs.commonTotal
                val right = rhs.commonTotal
                when {
                    left != null && right != null -> right.compareTo(left)
                    left == null && right != null -> 1
                    left != null && right == null -> -1
                    else -> rhs.total.compareTo(lhs.total)
                }
            },
        )
    }.take(UsageAnalytics.MAXIMUM_ROUTE_ROWS)

    val maxValue = max(ranked.maxOfOrNull { routeValue(it, metric) } ?: 1.0, 0.0001)

    InsightsCollapsibleCard(title = stringResource(R.string.toolsb_usage_section_routes), storageKey = "usageSection.routes") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (row in ranked) RouteRow(row, metric, maxValue, breakdown.routesAreMeaningful, style)
            if (breakdown.routesAreMeaningful) {
                InsightsChipFlow(spacing = 12.dp) {
                    for (index in breakdown.distinctRoutes) {
                        val route = UsageAxes.route(index)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(routeAccent(route)),
                            )
                            Text(CoreLabels.route(route), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (metric == UsageRankMetric.COMMON_DOSES) {
                Text(
                    stringResource(
                        R.string.toolsb_usage_routes_common_dose_note,
                        breakdown.commonDoseSubstances,
                        breakdown.rows.size,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
    }
}

private fun routeValue(row: UsageRouteRow, metric: UsageRankMetric): Double =
    if (metric == UsageRankMetric.COMMON_DOSES) (row.commonTotal ?: 0.0) else row.total.toDouble()

@Composable
private fun RouteRow(
    row: UsageRouteRow,
    metric: UsageRankMetric,
    maxValue: Double,
    colorByRoute: Boolean,
    style: SubstanceStyle,
) {
    // True when the active metric is common-dose units and this substance has
    // none: drawn as an empty track and a dash, dimmed, so it reads as "not
    // measurable this way" rather than "never taken".
    val isUnavailable = metric == UsageRankMetric.COMMON_DOSES && row.commonTotal == null
    val substanceColor = style.color(row.substanceIndex)
    val emptyInk = PiruTheme.colors.tertiaryLabel.copy(alpha = 0.5f)

    val segments = row.byRoute.map { slice ->
        slice.routeIndex to (if (metric == UsageRankMetric.COMMON_DOSES) slice.common else slice.count.toDouble())
    }
    // Resolved here, not inside the draw scope: a theme read is a composable
    // call and `Canvas { }` is a plain lambda.
    val routeColors = row.byRoute.associate { it.routeIndex to routeAccent(UsageAxes.route(it.routeIndex)) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                style.name(row.substanceIndex),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                when {
                    metric == UsageRankMetric.ENTRIES -> row.total.toString()
                    row.commonTotal != null -> InsightsFormat.commonDose(row.commonTotal)
                    else -> "—"
                },
                style = MaterialTheme.typography.labelSmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(11.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = CornerRadius(size.height / 2)
                if (isUnavailable) {
                    drawRoundRect(
                        color = emptyInk,
                        size = Size(max(size.width * 0.04f, 4f), size.height),
                        cornerRadius = radius,
                    )
                } else {
                    var x = 0f
                    for ((routeIndex, amount) in segments) {
                        if (amount <= 0) continue
                        val segmentWidth = max(size.width * (amount / maxValue).toFloat(), 2f)
                        drawRect(
                            color = if (colorByRoute) routeColors[routeIndex] ?: substanceColor else substanceColor,
                            topLeft = Offset(x, 0f),
                            size = Size(segmentWidth, size.height),
                        )
                        x += segmentWidth + 1f
                    }
                }
            }
        }
    }
}

// MARK: - Chart viewport

/**
 * The visible window over a time chart, as a pannable fraction of the span.
 *
 * A chart with no more data than one window shows is not scrollable at all,
 * which is upstream's `overflows` test — the alternative is a chart that pans a
 * pixel into nothing. When the span changes the view opens on the **newest**
 * data, which is what a trend chart should lead with.
 */
@Stable
internal class ChartViewportState {
    private var spanMillis: Double = 1.0
    private var windowMillis: Double = 1.0

    /**
     * The left edge, as a fraction of the whole span.
     *
     * Held raw and clamped on read rather than clamped on write: the span is
     * written from composition (a plain field, so no recomposition is triggered)
     * while the offset is only ever moved by a gesture or a reset, and clamping
     * at the read means a stale offset can never point outside the data.
     */
    private var raw by mutableFloatStateOf(0f)

    val visibleMillis: Double get() = min(windowMillis, spanMillis)

    val visibleFraction: Float get() = min(1f, (windowMillis / spanMillis).toFloat())

    val offsetFraction: Float get() = raw.coerceIn(0f, maxOffset())

    fun scrollable(): Boolean = spanMillis > windowMillis * 1.05

    /** Point the window at [span] of data, [window] wide. A plain field write, safe from composition. */
    fun setSpan(span: Double, window: Double) {
        spanMillis = max(1.0, span)
        windowMillis = max(1.0, window)
    }

    /** Open on the newest data, which is what a trend chart should lead with. */
    fun resetToNewest() {
        raw = maxOffset()
    }

    /** A drag of one chart width moves the window by one window, so content tracks the finger. */
    fun pan(deltaPx: Float, widthPx: Float) {
        if (widthPx <= 0f || !scrollable()) return
        raw = (offsetFraction - (deltaPx / widthPx) * visibleFraction).coerceIn(0f, maxOffset())
    }

    private fun maxOffset(): Float = (1f - visibleFraction).coerceAtLeast(0f)
}

@Composable
internal fun rememberViewportState(spanMillis: Double, windowMillis: Double): ChartViewportState {
    val state = remember { ChartViewportState() }
    state.setSpan(spanMillis, windowMillis)
    // The offset itself is reset in an effect, not in composition: writing a
    // snapshot state during composition is what produces a chart that recomposes
    // itself in a loop, and the one frame at the oldest edge before this lands is
    // invisible next to a wrong one.
    LaunchedEffect(spanMillis, windowMillis) { state.resetToNewest() }
    return state
}

// MARK: - Dates

/**
 * A chart label's day. `Locale.ROOT` was wrong here — it pins the month *name* to
 * English, so a Chinese device read "28 Sep" beside Chinese axis text. The names
 * come from the app's own resolved locale, which the caller passes in, and the
 * field order from the resource the caller resolves, because Chinese reads M月d日.
 */
private fun shortDate(instant: Instant, pattern: String, locale: Locale): String =
    DateTimeFormatter.ofPattern(pattern, locale).format(instant.atZone(ZoneId.systemDefault()))

/**
 * Foundation's weekday ladder, `1` = Sunday.
 *
 * The names come from the platform rather than a table in this file: a column
 * header is a word the reader reads, and hardcoding "Mon" would print it on a
 * Chinese device. `MedsFormControls` reads its weekday ladder the same way.
 */
private val WEEKDAYS: List<DayOfWeek> = listOf(
    DayOfWeek.SUNDAY,
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
    DayOfWeek.SATURDAY,
)

private fun monthOf(instant: Instant): Int = instant.atZone(ZoneId.systemDefault()).monthValue

private fun monthLabel(instant: Instant): String =
    Month.of(monthOf(instant)).getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())

private fun hourLabel(hour: Int): String = String.format(Locale.ROOT, "%02d:00", hour)

/** `1` = Sunday, the Foundation numbering the aggregate works in. */
private fun shortWeekday(weekday: Int): String =
    WEEKDAYS.getOrNull(weekday - 1)
        ?.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) ?: "?"

private fun weekdayName(weekday: Int): String =
    WEEKDAYS.getOrNull(weekday - 1)
        ?.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()) ?: "?"
