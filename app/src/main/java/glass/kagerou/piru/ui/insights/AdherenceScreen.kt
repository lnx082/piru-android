package glass.kagerou.piru.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.entity.DailyDoseItemEntity
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.AdherenceCalculator
import glass.kagerou.piru.engine.AdherenceEntry
import glass.kagerou.piru.engine.AdherenceItem
import glass.kagerou.piru.engine.AdherenceStatus
import glass.kagerou.piru.engine.DayAdherence
import glass.kagerou.piru.engine.MonthAdherence
import glass.kagerou.piru.model.doseFormatted
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.labels.CoreLabels
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.nav.SheetRoute
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale


/**
 * Whether the scheduled meds got taken.
 *
 * Ported from `Views/Insights/AdherenceView.swift` (678 lines) and
 * `AdherenceModel.swift`. The arithmetic is not here — it is
 * `engine/AdherenceCalculator`, which also carries the month roll-up upstream
 * keeps in its Insights layer, so a day and a month of days are scored by one
 * piece of code.
 *
 * ## Why the calendar has halves
 * A two-a-day routine turns on *which* half of the day went missing, and a
 * single circle cannot say that. So a day whose meds fall either side of noon
 * draws two half-discs, each in its own status colour, and a glance at the month
 * says which end of the day slips.
 *
 * ## No streak, and no flame
 * This is the design decision the iOS source states outright, and it is kept
 * here rather than re-litigated: **there is deliberately no consecutive-days
 * counter and no fire icon on this screen.** A streak that a single missed day
 * resets to zero is a scoreboard, and this screen is read by people for whom a
 * scoreboard is a shame vector rather than a nudge. The month's count only ever
 * goes up, and that is the only figure offered.
 *
 * The engine does carry `AdherenceCalculator.streak` — it is ported and tested,
 * because the widget and the notification copy need it — but nothing on this
 * screen reads it.
 *
 * ## Two things this build cannot do yet
 * The meds editor (upstream's `.myMeds` route) and the reminder settings screen
 * are not in the port. The empty state says what would fill it and the reminders
 * row says what it needs, rather than rendering a button that goes nowhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdherenceScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication

    var entries by remember { mutableStateOf<List<DoseEntryEntity>>(emptyList()) }
    var items by remember { mutableStateOf<List<AdherenceItem>>(emptyList()) }
    var itemById by remember { mutableStateOf<Map<String, DailyDoseItemEntity>>(emptyMap()) }
    var displayedMonth by remember { mutableStateOf(YearMonth.now()) }
    var monthDays by remember { mutableStateOf<List<DayAdherence>>(emptyList()) }
    var today by remember { mutableStateOf<DayAdherence?>(null) }
    var selectedDay by remember { mutableStateOf<DayAdherence?>(null) }
    var loaded by remember { mutableStateOf(false) }

    val zone = remember { ZoneId.systemDefault() }

    LaunchedEffect(navigator.dataVersion) {
        loaded = false
        entries = app.database.doseEntryDao().all()
        val daily = app.database.dailyDoseItemDao().all()
        items = daily.map { it.toAdherenceItem() }
        itemById = daily.associateBy { it.substance + it.sortOrder.toString() }
        val now = Instant.now()
        today = AdherenceCalculator.dayAdherence(
            date = now,
            entries = entries.toAdherenceEntries(),
            items = items,
            zone = zone,
        )
        loaded = true
    }

    // Recomputed whenever the user pages the calendar, so browsing back through
    // the year costs a month's arithmetic rather than the whole log's.
    LaunchedEffect(loaded, displayedMonth, navigator.dataVersion) {
        if (!loaded) return@LaunchedEffect
        val monthAnchor = displayedMonth.atDay(1).atStartOfDay(zone).toInstant()
        monthDays = AdherenceCalculator.monthDays(
            month = monthAnchor,
            entries = entries.toAdherenceEntries(),
            items = items,
            zone = zone,
        )
    }

    val monthSummary: MonthAdherence = remember(monthDays) {
        AdherenceCalculator.monthSummary(monthDays, Instant.now())
    }
    val byDate = remember(monthDays) { monthDays.associateBy { it.date } }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.toolsb_adherence_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.toolsb_adherence_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        if (loaded && items.isEmpty()) {
            item {
                InsightsEmptyPanel(
                    stringResource(R.string.toolsb_adherence_empty_title),
                    stringResource(R.string.toolsb_adherence_empty_detail),
                )
            }
        }

        if (items.isNotEmpty()) {
            val current = today
            if (current != null && current.status != AdherenceStatus.NoData) {
                item {
                    TodayCard(
                        today = current,
                        itemById = itemById,
                        onLogDose = { navigator.present(SheetRoute.QuickLog) },
                    )
                }
            }

            item {
                CalendarCard(
                    displayedMonth = displayedMonth,
                    onMonth = { displayedMonth = it },
                    byDate = byDate,
                    zone = zone,
                    onSelectDay = { selectedDay = it },
                )
            }

            item { MonthCard(monthSummary, displayedMonth) }

            item { RemindersRow(onClick = { navigator.push(PushRoute.NotificationSettings) }) }
        }
    }

    selectedDay?.let { day ->
        ModalBottomSheet(onDismissRequest = { selectedDay = null }) {
            DayDetailSheet(day, zone, itemById)
        }
    }
}

// MARK: - Reading the med list

private fun DailyDoseItemEntity.toAdherenceItem(): AdherenceItem = AdherenceItem(
    substance = substance,
    identityKey = identityKey,
    route = route,
    isAsNeeded = isAsNeeded,
    startDate = startDate.toInstant(),
    frequency = frequency,
    frequencyDays = frequencyDays,
    reminderTimesMinutes = reminderTimesMinutes,
    sortOrder = sortOrder,
)

private fun List<DoseEntryEntity>.toAdherenceEntries(): List<AdherenceEntry> = map { entry ->
    AdherenceEntry(
        substance = entry.substance,
        identityKey = entry.identityKey,
        route = entry.route,
        timestamp = entry.timestamp.toInstant(),
    )
}

// MARK: - Today

/**
 * Today, med by med.
 *
 * Today is computed independently of the displayed month, so browsing back
 * through the calendar does not take today's strip with it.
 */
@Composable
private fun TodayCard(
    today: DayAdherence,
    itemById: Map<String, DailyDoseItemEntity>,
    onLogDose: () -> Unit,
) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.toolsb_adherence_today),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.toolsb_adherence_today_taken, today.takenCount, today.totalCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
            for (item in today.items) {
                val entity = itemById[item.id]
                TodayRow(
                    // The med's own saved label wins over the catalog title: the
                    // user named this item, and that is the name they will look
                    // for in the list.
                    title = entity?.productName ?: item.item.substance,
                    doseText = entity?.let { "${doseFormatted(it.amount)} ${it.unit}" } ?: "",
                    takenCount = item.takenCount,
                    totalCount = item.totalCount,
                )
            }
            if (today.takenCount < today.totalCount) {
                Text(
                    stringResource(R.string.toolsb_adherence_record_entry),
                    style = MaterialTheme.typography.labelLarge,
                    color = PiruTheme.colors.accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onLogDose)
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TodayRow(title: String, doseText: String, takenCount: Int, totalCount: Int) {
    val done = takenCount >= totalCount
    val ink = if (done) PiruTheme.colors.success else PiruTheme.colors.tertiaryLabel
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(modifier = Modifier.size(20.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                if (done) {
                    drawCircle(color = ink, radius = size.minDimension / 2)
                    drawLine(
                        color = Color.White,
                        start = Offset(size.width * 0.28f, size.height * 0.52f),
                        end = Offset(size.width * 0.44f, size.height * 0.70f),
                        strokeWidth = 2f,
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(size.width * 0.44f, size.height * 0.70f),
                        end = Offset(size.width * 0.74f, size.height * 0.32f),
                        strokeWidth = 2f,
                    )
                } else {
                    drawCircle(color = ink, radius = size.minDimension / 2 - 1f, style = Stroke(width = 2f))
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(doseText, style = MaterialTheme.typography.bodySmall, color = PiruTheme.colors.secondaryLabel)
        }
        if (totalCount > 1) {
            Text(
                "$takenCount/$totalCount",
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}

// MARK: - The month

/**
 * The month as a count, under the calendar that already showed its shape.
 *
 * The only figure on the screen that aggregates, and it only ever goes up. See
 * the screen's own note on why there is no streak beside it.
 */
@Composable
private fun MonthCard(summary: MonthAdherence, month: YearMonth) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (summary.hasData) {
                        stringResource(R.string.toolsb_adherence_month_doses, summary.taken, summary.due)
                    } else {
                        stringResource(R.string.toolsb_adherence_month_none_scheduled)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.toolsb_adherence_month_year, monthName(month.monthValue), month.year),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
    }
}

/**
 * A way through to the reminder switches.
 *
 * Made a link once the notification settings screen existed — it was a dead row
 * naming what was missing before that, which is the right thing for a row with
 * nowhere to go and the wrong thing for one that has.
 */
@Composable
private fun RemindersRow(onClick: () -> Unit) {
    PiruCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.toolsb_adherence_reminders_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.toolsb_adherence_reminders_detail),
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarCard(
    displayedMonth: YearMonth,
    onMonth: (YearMonth) -> Unit,
    byDate: Map<Instant, DayAdherence>,
    zone: ZoneId,
    onSelectDay: (DayAdherence) -> Unit,
) {
    var showingMonthPicker by remember { mutableStateOf(false) }
    val today = LocalDate.now(zone)
    val now = Instant.now()

    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onMonth(displayedMonth.minusMonths(1)) }) { Text("‹") }
                Text(
                    stringResource(
                        R.string.toolsb_adherence_month_year,
                        monthName(displayedMonth.monthValue),
                        displayedMonth.year,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showingMonthPicker = true },
                )
                if (atOrAfterCurrentMonth(displayedMonth, zone)) {
                    // Nothing ahead of this month to page to.
                    Text("›", style = MaterialTheme.typography.titleMedium, color = PiruTheme.colors.tertiaryLabel)
                } else {
                    TextButton(onClick = { onMonth(displayedMonth.plusMonths(1)) }) { Text("›") }
                }
            }

            val firstOfMonth = displayedMonth.atDay(1)
            val leading = ((foundationWeekdayOf(firstOfMonth) - firstWeekdayOf()) + 7) % 7
            val cells = buildList<LocalDate?> {
                repeat(leading) { add(null) }
                for (day in 1..displayedMonth.lengthOfMonth()) add(displayedMonth.atDay(day))
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                for (index in 0 until 7) {
                    val weekday = ((firstWeekdayOf() - 1 + index) % 7) + 1
                    Text(
                        shortWeekday(weekday),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = PiruTheme.colors.secondaryLabel,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            for (row in cells.chunked(7)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (date in row) {
                        Box(modifier = Modifier.weight(1f).aspectRatio(1f).padding(2.dp)) {
                            if (date == null) return@Box
                            val dateInstant = date.atStartOfDay(zone).toInstant()
                            val adherence = byDate[dateInstant]
                            val isToday = date == today
                            val isFuture = dateInstant > now
                            val status = if (isFuture) {
                                AdherenceStatus.NoData
                            } else {
                                adherence?.status ?: AdherenceStatus.Missed
                            }
                            CalendarCell(
                                day = date.dayOfMonth,
                                status = status,
                                isToday = isToday,
                                halves = if (isFuture) null else adherence?.halves,
                                onClick = {
                                    if (!isFuture && adherence != null && adherence.status != AdherenceStatus.NoData) {
                                        onSelectDay(adherence)
                                    }
                                },
                            )
                        }
                    }
                    // A trailing partial week keeps the columns aligned with the
                    // header, which a plain sized Row would not.
                    repeat(7 - row.size) { Box(modifier = Modifier.weight(1f).aspectRatio(1f)) }
                }
            }
        }
    }

    if (showingMonthPicker) {
        ModalBottomSheet(onDismissRequest = { showingMonthPicker = false }) {
            MonthPicker(
                displayedMonth = displayedMonth,
                zone = zone,
                onPick = {
                    onMonth(it)
                    showingMonthPicker = false
                },
            )
        }
    }
}

/**
 * One day.
 *
 * A day whose meds fall either side of noon draws two half-discs — morning on
 * the leading side, evening on the trailing one — so the shaded half says which
 * end of the day went unlogged. Every other day is a single circle.
 */
@Composable
private fun CalendarCell(
    day: Int,
    status: AdherenceStatus,
    isToday: Boolean,
    halves: DayAdherence.Halves?,
    onClick: () -> Unit,
) {
    val success = PiruTheme.colors.success
    val caution = PiruTheme.colors.caution
    val danger = PiruTheme.colors.danger
    val emptyFill = PiruTheme.colors.secondaryLabel.copy(alpha = 0.14f)
    val accent = PiruTheme.colors.accent
    val ink = PiruTheme.colors.secondaryLabel

    fun fillOf(value: AdherenceStatus): Color = when (value) {
        AdherenceStatus.Complete -> success.copy(alpha = 0.22f)
        AdherenceStatus.Partial -> caution.copy(alpha = 0.22f)
        AdherenceStatus.Missed -> danger.copy(alpha = 0.22f)
        AdherenceStatus.NoData -> emptyFill
    }

    Box(modifier = Modifier.fillMaxSize().clickable(onClick = onClick)) {
        // The disc is the cell's background and the number and mark sit inside
        // it, which is upstream's ZStack: a circle that the day number floats
        // *outside* of would read as a separate mark rather than as the day.
        Canvas(Modifier.fillMaxSize()) {
            val diameter = minOf(size.width, size.height)
            val left = (size.width - diameter) / 2
            val top = (size.height - diameter) / 2
            val halfSize = androidx.compose.ui.geometry.Size(diameter, diameter)
            if (halves != null) {
                // Two half-discs filling the same square, so the seam lands dead
                // centre and the halves can never drift apart.
                drawArc(
                    color = fillOf(halves.morning),
                    startAngle = 90f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = Offset(left, top),
                    size = halfSize,
                )
                drawArc(
                    color = fillOf(halves.evening),
                    startAngle = 270f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = Offset(left, top),
                    size = halfSize,
                )
            } else {
                drawCircle(color = fillOf(status), radius = diameter / 2, center = center)
            }
            if (isToday) {
                drawCircle(color = accent, radius = diameter / 2 - 1f, style = Stroke(width = 2f))
            }
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(day.toString(), fontSize = 9.sp, color = ink)
            StatusGlyph(status, success, caution, danger)
        }
    }
}

/** The status mark, drawn rather than iconed: the port carries no SF Symbols. */
@Composable
private fun StatusGlyph(status: AdherenceStatus, success: Color, caution: Color, danger: Color) {
    Box(modifier = Modifier.size(8.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            when (status) {
                AdherenceStatus.Complete -> {
                    drawLine(
                        color = success,
                        start = Offset(size.width * 0.1f, size.height * 0.55f),
                        end = Offset(size.width * 0.4f, size.height * 0.85f),
                        strokeWidth = 1.8f,
                    )
                    drawLine(
                        color = success,
                        start = Offset(size.width * 0.4f, size.height * 0.85f),
                        end = Offset(size.width * 0.95f, size.height * 0.15f),
                        strokeWidth = 1.8f,
                    )
                }

                AdherenceStatus.Partial -> {
                    drawArc(
                        color = caution,
                        startAngle = 90f,
                        sweepAngle = 180f,
                        useCenter = true,
                        size = size,
                    )
                    drawCircle(color = caution, radius = size.minDimension / 2 - 0.9f, style = Stroke(width = 1.8f))
                }

                AdherenceStatus.Missed -> {
                    drawLine(danger, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = 1.8f)
                    drawLine(danger, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = 1.8f)
                }

                AdherenceStatus.NoData -> Unit
            }
        }
    }
}

// MARK: - Day detail

@Composable
private fun DayDetailSheet(
    day: DayAdherence,
    zone: ZoneId,
    itemById: Map<String, DailyDoseItemEntity>,
) {
    val status = day.status
    val label = when (status) {
        AdherenceStatus.Complete -> stringResource(R.string.toolsb_adherence_day_all_taken)
        AdherenceStatus.Partial -> stringResource(R.string.toolsb_adherence_day_partially_taken)
        AdherenceStatus.Missed -> stringResource(R.string.toolsb_adherence_day_all_missed)
        AdherenceStatus.NoData -> stringResource(R.string.toolsb_adherence_day_nothing_due)
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            day.date.atZone(zone).toLocalDate().toString(),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.toolsb_adherence_day_summary, label, day.takenCount, day.totalCount),
            style = MaterialTheme.typography.bodyMedium,
            color = PiruTheme.colors.secondaryLabel,
        )
        Spacer(modifier = Modifier.height(12.dp))
        for (itemAdherence in day.items) {
            val entity = itemById[itemAdherence.id]
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    if (itemAdherence.taken) {
                        stringResource(R.string.toolsb_adherence_item_taken, itemAdherence.item.substance)
                    } else {
                        stringResource(
                            R.string.toolsb_adherence_item_not_logged,
                            CoreLabels.route(itemAdherence.item.route).lowercase(),
                            itemAdherence.item.substance,
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                val dose = entity?.let { "${doseFormatted(it.amount)} ${it.unit}" } ?: ""
                val frequency = shortFrequency(itemAdherence.item)
                Text(
                    "$dose — $frequency".trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * The cadence a med is on.
 *
 * Upstream prints `DoseFrequency.shortLabel`; the port's `DoseFrequency` carries
 * only the wire value and its long name, so the short form is spelled out here
 * — as resources, since these are words on screen. When the model gains
 * `shortLabel` this is where it plugs in.
 */
@Composable
private fun shortFrequency(item: AdherenceItem): String = when (item.frequency) {
    glass.kagerou.piru.model.DoseFrequency.DAILY -> stringResource(R.string.toolsb_adherence_frequency_daily)
    glass.kagerou.piru.model.DoseFrequency.EVERY_OTHER_DAY ->
        stringResource(R.string.toolsb_adherence_frequency_every_2_days)
    glass.kagerou.piru.model.DoseFrequency.WEEKLY -> stringResource(R.string.toolsb_adherence_frequency_weekly)
    glass.kagerou.piru.model.DoseFrequency.BIWEEKLY -> stringResource(R.string.toolsb_adherence_frequency_biweekly)
    glass.kagerou.piru.model.DoseFrequency.MONTHLY -> stringResource(R.string.toolsb_adherence_frequency_monthly)
    glass.kagerou.piru.model.DoseFrequency.SPECIFIC_DAYS ->
        stringResource(R.string.toolsb_adherence_frequency_custom_days)
}

// MARK: - Month picker

@Composable
private fun MonthPicker(displayedMonth: YearMonth, zone: ZoneId, onPick: (YearMonth) -> Unit) {
    var year by remember { mutableStateOf(displayedMonth.year) }
    val current = YearMonth.now(zone)
    val monthColumns = 3

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { year -= 1 }) { Text("‹") }
            Text(
                year.toString(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (year >= current.year) {
                Text("›", style = MaterialTheme.typography.titleMedium, color = PiruTheme.colors.tertiaryLabel)
            } else {
                TextButton(onClick = { year += 1 }) { Text("›") }
            }
        }
        for (row in (1..12).chunked(monthColumns)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (month in row) {
                    val candidate = YearMonth.of(year, month)
                    val isFuture = candidate > current
                    val isSelected = candidate == displayedMonth
                    Text(
                        monthName(month),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            isFuture -> PiruTheme.colors.tertiaryLabel
                            isSelected -> PiruTheme.colors.accent
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = !isFuture) { onPick(candidate) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

// MARK: - Dates

/**
 * A month's name, read from the platform.
 *
 * The port carries no month table: a heading that said "March" on a Chinese
 * device would be the port's English leaking through, and `MedsFormControls`
 * reads its weekday ladder from the platform for the same reason.
 */
private fun monthName(month: Int): String = Month.of(month).getDisplayName(TextStyle.FULL, Locale.getDefault())

/** Foundation's weekday numbering (`1` = Sunday … `7` = Saturday) from a [LocalDate]. */
private fun foundationWeekdayOf(date: LocalDate): Int = date.dayOfWeek.value % 7 + 1

/**
 * The first day of the week, in the device's own convention.
 *
 * A display preference, so this reads the ambient locale rather than
 * `Locale.ROOT` — only number formatting takes ROOT in this port. `WeekFields`
 * counts Monday as 1 and Foundation counts Sunday as 1, so the two ladders are
 * shifted by one.
 */
private fun firstWeekdayOf(): Int =
    java.time.temporal.WeekFields.of(Locale.getDefault()).firstDayOfWeek.value % 7 + 1

/** Whether [month] is the current month or later — i.e. whether paging forward says anything. */
private fun atOrAfterCurrentMonth(month: YearMonth, zone: ZoneId): Boolean = month >= YearMonth.now(zone)

/**
 * Foundation's weekday ladder, `1` = Sunday … `7` = Saturday.
 *
 * The column headers come from the platform rather than a table in this file:
 * a calendar headed "Sun Mon Tue" on a Chinese device is the port's English
 * leaking through. `MedsFormControls.kt` builds the same ladder the same way.
 */
private val FOUNDATION_WEEKDAYS: List<DayOfWeek> = listOf(
    DayOfWeek.SUNDAY,
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
    DayOfWeek.SATURDAY,
)

private fun shortWeekday(weekday: Int): String =
    FOUNDATION_WEEKDAYS.getOrNull(weekday - 1)
        ?.getDisplayName(TextStyle.SHORT, Locale.getDefault()) ?: "?"
