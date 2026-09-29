package glass.kagerou.piru.ui.insights

import android.content.Intent
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.core.content.FileProvider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.entity.DailyDoseItemEntity
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.data.entity.SessionEntity
import glass.kagerou.piru.data.entity.SessionNoteEntity
import glass.kagerou.piru.data.report.ReportBuilder
import glass.kagerou.piru.engine.report.TripReport
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.labels.appLocale
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Choosing what a report would cover, and seeing what that scope holds.
 *
 * Ported from `Views/Insights/ReportsView.swift` (570 lines) and
 * `ReportsModel.swift` (286 lines) — the scope half of them.
 *
 * ## The export half: text wired, images not
 * Upstream's five exports are a UIKit `UIGraphicsPDFRenderer`, a
 * `UIGraphicsImageRenderer` twice over, and two Markdown strings, all handed to
 * a `UIActivityViewController`. The two Markdown exports — the session snapshot
 * and the trip report — are wired here to an `ACTION_SEND` intent carrying
 * `EXTRA_TEXT`. The three image exports are not: they need a `FileProvider` URI
 * for a rendered bitmap, and a row that looks like it will share something and
 * does not is worse than one that says it is not connected yet.
 *
 * What *is* here is the part that decides what a report would contain: the mode,
 * the session or date scope, the substance filter, and a summary of the result.
 * That is the half worth landing first — it is the half whose correctness a
 * clinician's report depends on, and it is the half that can be read on screen.
 *
 * ## The substance filter's two ends collapse
 * An empty set and a full set both mean "all", exactly as in the Usage screen's
 * sheet and for the same reason: the filter should read as *filtered* only for a
 * genuine subset.
 *
 * ## One upstream quirk not carried
 * `ReportsModel.deselectAllSubstances()` writes `Set([""])` — a sentinel that
 * matches nothing — because the model had no other way to say "none". It is a
 * real state here instead, which removes the sentinel without changing what the
 * user sees.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication

    var mode by rememberSaveable { mutableStateOf(ReportMode.LATEST) }
    var selectedSessions by remember { mutableStateOf(emptySet<UUID>()) }
    var customStart by remember { mutableStateOf(LocalDate.now().minusDays(30)) }
    var customEnd by remember { mutableStateOf(LocalDate.now()) }
    var filterExpanded by remember { mutableStateOf(false) }
    // `null` is "all" and an empty set is "none" — two real states, rather than
    // upstream's sentinel string that matches nothing.
    var substanceFilter by remember { mutableStateOf<Set<String>?>(null) }

    var sessions by remember { mutableStateOf<List<SessionEntity>>(emptyList()) }
    var dosesBySession by remember { mutableStateOf<Map<UUID, List<DoseEntryEntity>>>(emptyMap()) }
    var allEntries by remember { mutableStateOf<List<DoseEntryEntity>>(emptyList()) }
    var allNotes by remember { mutableStateOf<List<SessionNoteEntity>>(emptyList()) }
    var notesBySession by remember { mutableStateOf<Map<UUID, List<SessionNoteEntity>>>(emptyMap()) }
    var sessionsWithNotes by remember { mutableStateOf<Set<UUID>>(emptySet()) }
    var dailyDoseItems by remember { mutableStateOf<List<DailyDoseItemEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    val zone = remember { ZoneId.systemDefault() }

    LaunchedEffect(navigator.dataVersion) {
        loaded = false
        sessions = app.database.sessionDao().all()
        val doses = app.database.doseEntryDao().all()
        dosesBySession = doses.filter { it.sessionId != null }.groupBy { it.sessionId!! }
        allEntries = doses
        val notes = app.database.sessionNoteDao().all()
        allNotes = notes
        notesBySession = notes.filter { it.sessionId != null }.groupBy { it.sessionId!! }
        // A session "has notes" when at least one of them carries something —
        // the same test the trip report itself runs before it builds anything.
        sessionsWithNotes = notes
            .filter { it.hasContent && it.sessionId != null }
            .mapNotNull { it.sessionId }
            .toSet()
        // The PDF's Current Medications section is the daily-dose list, which is not a
        // session's and so is not reached by anything else on this screen.
        dailyDoseItems = app.database.dailyDoseItemDao().all()
        loaded = true
    }

    // Hard cap of 15, upstream's own: the picker is a shortcut into recent
    // history, not a session browser.
    val summaries = remember(sessions, dosesBySession) {
        sessions.take(15).mapNotNull { session ->
            val doses = dosesBySession[session.id].orEmpty().sortedBy { it.timestamp.time }
            if (doses.isEmpty()) return@mapNotNull null
            val start = doses.first().timestamp.toInstant()
            val end = doses.last().timestamp.toInstant()
            val timeLabel = if (end.toEpochMilli() - start.toEpochMilli() >= 60_000) {
                "${clockTime(start, zone)} – ${clockTime(end, zone)}"
            } else {
                clockTime(start, zone)
            }
            val displayNames = LinkedHashMap<String, String>()
            for (dose in doses) {
                val shown = dose.displayNameSnapshot ?: dose.substance
                displayNames.putIfAbsent(shown.lowercase(), shown)
            }
            val names = displayNames.values.toList()
            val summary = if (names.size <= 3) {
                names.joinToString(", ")
            } else {
                context.getString(
                    R.string.toolsb_reports_session_more,
                    names.take(3).joinToString(", "),
                    names.size - 3,
                )
            }
            SessionSummary(
                id = session.id,
                startDate = session.startDate.toInstant(),
                timeLabel = timeLabel,
                title = session.title,
                substanceSummary = summary,
                doseCount = doses.size,
            )
        }
    }

    val scopeEntries = remember(mode, selectedSessions, customStart, customEnd, allEntries, dosesBySession) {
        when (mode) {
            ReportMode.LATEST -> if (selectedSessions.isEmpty()) {
                emptyList()
            } else {
                selectedSessions.flatMap { dosesBySession[it].orEmpty() }
            }

            ReportMode.BY_DATE -> {
                val from = customStart.atStartOfDay(zone).toInstant()
                // The end date is inclusive, so the window runs to the start of
                // the day after it.
                val to = customEnd.plusDays(1).atStartOfDay(zone).toInstant()
                allEntries.filter { it.timestamp.toInstant() >= from && it.timestamp.toInstant() < to }
            }
        }
    }

    /** The instants the scope selection resolves to, for the report's Period line. */
    val doseTimestamps = remember(scopeEntries) { scopeEntries.map { it.timestamp.toInstant() } }

    // Notes belonging to the scope: the selected sessions' notes, or every note
    // whose timestamp falls in the date range.
    val scopeNotes = remember(mode, selectedSessions, customStart, customEnd, allNotes, notesBySession) {
        when (mode) {
            ReportMode.LATEST -> selectedSessions.flatMap { notesBySession[it].orEmpty() }
            ReportMode.BY_DATE -> {
                val from = customStart.atStartOfDay(zone).toInstant()
                val to = customEnd.plusDays(1).atStartOfDay(zone).toInstant()
                allNotes.filter { it.timestamp.toInstant() >= from && it.timestamp.toInstant() < to }
            }
        }
    }

    /**
     * The window the report covers, as two instants.
     *
     * Derived from the same scope selection the entries come from rather than carried
     * separately: a PDF whose Period line disagreed with its own contents would be worse
     * than one with no period at all. The latest-sessions mode takes the span of the
     * doses actually selected — which is what the user picked — and the date mode takes
     * the calendar days, inclusive of the end date the picker shows.
     */
    val scopeStart = remember(mode, selectedSessions, customStart, doseTimestamps) {
        when (mode) {
            ReportMode.LATEST -> doseTimestamps.minOrNull() ?: customStart.atStartOfDay(zone).toInstant()
            ReportMode.BY_DATE -> customStart.atStartOfDay(zone).toInstant()
        }
    }
    val scopeEnd = remember(mode, selectedSessions, customEnd, doseTimestamps) {
        when (mode) {
            ReportMode.LATEST -> doseTimestamps.maxOrNull() ?: customEnd.plusDays(1).atStartOfDay(zone).toInstant()
            // Inclusive of the end date the picker shows, so the window runs to the
            // start of the day after it — the same rule `scopeEntries` filters on.
            ReportMode.BY_DATE -> customEnd.plusDays(1).atStartOfDay(zone).toInstant()
        }
    }

    // The sessions a trip report can be built for: they have notes, and their
    // entries are in scope. LATEST reads the picker; BY_DATE reads the range.
    val tripSessions = remember(mode, selectedSessions, customStart, customEnd, sessions, dosesBySession, sessionsWithNotes) {
        when (mode) {
            ReportMode.LATEST -> sessions.filter {
                selectedSessions.contains(it.id) && sessionsWithNotes.contains(it.id)
            }
            ReportMode.BY_DATE -> {
                val from = customStart.atStartOfDay(zone).toInstant()
                val to = customEnd.plusDays(1).atStartOfDay(zone).toInstant()
                sessions.filter { s ->
                    sessionsWithNotes.contains(s.id) &&
                        dosesBySession[s.id].orEmpty().any {
                            it.timestamp.toInstant() >= from && it.timestamp.toInstant() < to
                        }
                }
            }
        }
    }

    val substancesInScope = remember(scopeEntries) {
        scopeEntries.map { it.substance }.toSet().sorted()
    }
    val entryCountInScope = scopeEntries.size
    val hasScope = when (mode) {
        ReportMode.LATEST -> selectedSessions.isNotEmpty()
        ReportMode.BY_DATE -> entryCountInScope > 0
    }

    // The filter heals itself: anything no longer in scope is dropped, and a
    // filter that has become the whole set collapses back to "all".
    LaunchedEffect(substancesInScope) {
        val current = substanceFilter ?: return@LaunchedEffect
        val next = current.filter { substancesInScope.contains(it) }.toSet()
        substanceFilter = if (next.size == substancesInScope.size) null else next
    }

    val includedCount = substanceFilter?.size ?: substancesInScope.size

    // The share half of the export. `catalog()` is suspending, so the report is
    // built in a coroutine when the row is tapped, then handed to the system
    // share sheet as plain text. `locale` and `zone` are resolved here — not in
    // the coroutine — because `appLocale()` reads Compose state.
    val locale = appLocale()
    val shareScope = rememberCoroutineScope()

    fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.toolsb_reports_share_chooser)),
        )
    }

    val onShareMarkdown: () -> Unit = {
        shareScope.launch {
            val catalog = app.catalog()
            val weightKg = app.profile().weightKgOrDefault()
            val resolver = { id: String ->
                catalog.descriptorConcept(id)?.let { TripReport.Descriptor(it.id, it.name, it.domain) }
            }
            val export = ReportBuilder.sessionStateExport(
                entries = scopeEntries,
                notes = scopeNotes,
                catalog = catalog,
                checker = catalog.interactionChecker,
                weightKg = weightKg,
                descriptorResolver = resolver,
            )
            export?.markdown(locale, zone)?.let { shareText(it) }
        }
    }

    val onShareTrip: () -> Unit = {
        shareScope.launch {
            val catalog = app.catalog()
            val weightKg = app.profile().weightKgOrDefault()
            val resolver = { id: String ->
                catalog.descriptorConcept(id)?.let { TripReport.Descriptor(it.id, it.name, it.domain) }
            }
            val reports = tripSessions.map { session ->
                ReportBuilder.tripReport(
                    session = session,
                    doses = dosesBySession[session.id].orEmpty(),
                    notes = notesBySession[session.id].orEmpty(),
                    catalog = catalog,
                    weightKg = weightKg,
                    descriptorResolver = resolver,
                ).markdown(locale, zone)
            }
            if (reports.isNotEmpty()) {
                shareText(reports.joinToString("\n\n---\n\n"))
            }
        }
    }

    /**
     * The PDF report: the one export that leaves as a file rather than as text.
     *
     * It goes through `FileProvider` because a share intent cannot hand another app a
     * `file://` URI — that throws since API 24 — so the permission to read this one file
     * is granted to the chosen app through the intent's flags and nothing else can reach
     * the directory.
     *
     * A failure here is reported rather than swallowed. The user has just asked to hand
     * a document to a clinician, and "nothing happened" is indistinguishable from a
     * report that was produced and then lost.
     */
    val onSharePdf: () -> Unit = {
        shareScope.launch {
            val file = runCatching {
                ReportPdfExport.write(
                    context = context,
                    app = app,
                    entries = scopeEntries,
                    dailyDoseItems = dailyDoseItems,
                    start = scopeStart,
                    end = scopeEnd,
                )
            }.getOrNull()
            if (file == null) {
                Toast.makeText(
                    context,
                    context.getString(R.string.toolsb_reports_pdf_failed),
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                // `EXTRA_SUBJECT` is what an email client uses for the subject line, and
                // the report's own name is the right one: the user does not have to type
                // it and the recipient sees a document called what it is.
                putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.toolsb_reports_share_chooser)),
            )
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.toolsb_reports_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.toolsb_reports_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (option in ReportMode.entries) {
                    InsightsFilterPill(
                        label = stringResource(option.labelRes),
                        color = PiruTheme.colors.accent,
                        isSelected = option == mode,
                        showDot = false,
                        onClick = { mode = option },
                    )
                }
            }
        }

        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (mode) {
                        ReportMode.LATEST -> LatestScope(
                            selectedCount = selectedSessions.size,
                            totalCount = summaries.size,
                            entryCount = entryCountInScope,
                            summaries = summaries,
                            selected = selectedSessions,
                            onToggle = { id ->
                                selectedSessions = if (selectedSessions.contains(id)) {
                                    selectedSessions - id
                                } else {
                                    selectedSessions + id
                                }
                            },
                            onAll = {
                                selectedSessions = if (selectedSessions.size == summaries.size) {
                                    emptySet()
                                } else {
                                    summaries.map { it.id }.toSet()
                                }
                            },
                        )

                        ReportMode.BY_DATE -> DateScope(
                            start = customStart,
                            end = customEnd,
                            onStart = { customStart = it },
                            onEnd = { customEnd = it },
                            entryCount = entryCountInScope,
                            substanceCount = substancesInScope.size,
                        )
                    }
                }
            }
        }

        if (hasScope) {
            item {
                InsightsSectionCard(title = stringResource(R.string.toolsb_reports_scope_title)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ScopeLine(stringResource(R.string.toolsb_reports_scope_entries), entryCountInScope.toString())
                        ScopeLine(
                            stringResource(R.string.toolsb_substances),
                            if (substanceFilter == null) {
                                substancesInScope.size.toString()
                            } else {
                                stringResource(
                                    R.string.toolsb_reports_filter_count,
                                    includedCount,
                                    substancesInScope.size,
                                )
                            },
                        )
                        ScopeLine(
                            stringResource(R.string.toolsb_reports_scope_sessions),
                            if (mode == ReportMode.LATEST) selectedSessions.size.toString() else "—",
                        )
                    }
                }
            }

            item {
                ExportList(
                    mode = mode,
                    selectedSessionCount = selectedSessions.size,
                    entriesInScope = scopeEntries,
                    sessionCountWithNotes = tripSessions.size,
                    onSharePdf = onSharePdf,
                    onShareMarkdown = onShareMarkdown,
                    onShareTrip = onShareTrip,
                )
            }

            item {
                SubstanceFilter(
                    expanded = filterExpanded,
                    onExpanded = { filterExpanded = it },
                    substances = substancesInScope,
                    filter = substanceFilter,
                    includedCount = includedCount,
                    onToggle = { substance ->
                        val current = substanceFilter
                        substanceFilter = when {
                            // "All" is not a set yet, so unchecking one substance
                            // means materializing the full list minus it.
                            current == null -> substancesInScope.toSet() - substance
                            current.contains(substance) -> current - substance
                            else -> {
                                val next = current + substance
                                // Selecting the last one collapses back to "all":
                                // a filter over everything is not a filter.
                                if (next.size == substancesInScope.size) null else next
                            }
                        }
                    },
                    onAll = { substanceFilter = null },
                    onNone = { substanceFilter = emptySet() },
                )
            }
        }
    }
}

// MARK: - Scope

/** The two scopes a report can be built over. */
private enum class ReportMode(@StringRes val labelRes: Int) {
    LATEST(R.string.toolsb_reports_mode_latest),
    BY_DATE(R.string.toolsb_reports_mode_by_date),
}

private data class SessionSummary(
    val id: UUID,
    val startDate: Instant,
    val timeLabel: String,
    val title: String?,
    val substanceSummary: String,
    val doseCount: Int,
)

@Composable
private fun LatestScope(
    selectedCount: Int,
    totalCount: Int,
    entryCount: Int,
    summaries: List<SessionSummary>,
    selected: Set<UUID>,
    onToggle: (UUID) -> Unit,
    onAll: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (selectedCount == 0) {
                stringResource(R.string.toolsb_reports_select_sessions)
            } else {
                stringResource(
                    R.string.toolsb_reports_selection_summary,
                    selectedCount,
                    totalCount,
                    entryCount,
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (selectedCount == 0) PiruTheme.colors.secondaryLabel else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onAll) {
            Text(
                if (selectedCount == totalCount && totalCount > 0) {
                    stringResource(R.string.toolsb_deselect_all)
                } else {
                    stringResource(R.string.toolsb_select_all)
                },
            )
        }
    }
    if (summaries.isEmpty()) {
        Text(
            stringResource(R.string.toolsb_reports_no_sessions),
            style = MaterialTheme.typography.bodySmall,
            color = PiruTheme.colors.secondaryLabel,
        )
    }
    for (summary in summaries) {
        val isSelected = selected.contains(summary.id)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle(summary.id) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (isSelected) "●" else "○",
                color = if (isSelected) PiruTheme.colors.accent else PiruTheme.colors.secondaryLabel,
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    summary.title?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium)
                        InsightsMiddot()
                    }
                    Text(
                        shortDate(
                            summary.startDate,
                            LocalContext.current.getString(R.string.datefmt_short_weekday_day_month),
                            appLocale(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (summary.title == null) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
                Text(
                    "${summary.timeLabel} · ${summary.substanceSummary}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
            Text(
                if (summary.doseCount == 1) {
                    stringResource(R.string.toolsb_reports_entry_count_one)
                } else {
                    stringResource(R.string.toolsb_reports_entry_count_many, summary.doseCount)
                },
                style = MaterialTheme.typography.labelSmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateScope(
    start: LocalDate,
    end: LocalDate,
    onStart: (LocalDate) -> Unit,
    onEnd: (LocalDate) -> Unit,
    entryCount: Int,
    substanceCount: Int,
) {
    DateRow(stringResource(R.string.toolsb_reports_date_from), start, onStart)
    DateRow(stringResource(R.string.toolsb_reports_date_to), end, onEnd)
    if (entryCount > 0) {
        Text(
            stringResource(R.string.toolsb_reports_entries_across, entryCount, substanceCount),
            style = MaterialTheme.typography.labelSmall,
            color = PiruTheme.colors.secondaryLabel,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRow(label: String, date: LocalDate, onDate: (LocalDate) -> Unit) {
    var showing by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            date.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = PiruTheme.colors.accent,
            modifier = Modifier.clickable { showing = true },
        )
    }
    if (showing) {
        // The picker works in UTC by contract, so the selected millis are read
        // back as a UTC date rather than through the device's zone — the
        // alternative is a date that shifts by a day west of Greenwich.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showing = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showing = false
                }) { Text(stringResource(R.string.toolsb_reports_date_set)) }
            },
            dismissButton = {
                TextButton(onClick = { showing = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun ScopeLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = PiruTheme.colors.secondaryLabel)
    }
}

// MARK: - Exports

/**
 * The five exports. The two text exports — Markdown and the trip report — are
 * wired to the system share sheet; the three image exports stay inert and say
 * so. A card that looks tappable and does nothing is worse than one that names
 * what is missing, and "share a broken file" is the one failure mode this screen
 * must not have.
 */
@Composable
private fun ExportList(
    mode: ReportMode,
    selectedSessionCount: Int,
    entriesInScope: List<DoseEntryEntity>,
    sessionCountWithNotes: Int,
    onSharePdf: () -> Unit,
    onShareMarkdown: () -> Unit,
    onShareTrip: () -> Unit,
) {
    InsightsSectionCard(
        title = stringResource(R.string.toolsb_reports_exports_title),
        subtitle = stringResource(R.string.toolsb_reports_exports_subtitle),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ExportRow(
                title = stringResource(R.string.toolsb_reports_export_journal_title),
                description = stringResource(R.string.toolsb_reports_export_journal_desc),
                // Wired only when there is something to put in the report: an export
                // that produces an empty document is the "looks tappable and does
                // nothing" failure this list is written to avoid.
                onClick = if (entriesInScope.isNotEmpty()) onSharePdf else null,
            )
            ExportRow(
                title = stringResource(R.string.toolsb_reports_export_session_images_title),
                description = if (mode == ReportMode.LATEST) {
                    stringResource(R.string.toolsb_reports_export_session_images_count, selectedSessionCount)
                } else {
                    stringResource(R.string.toolsb_reports_export_session_images_range)
                },
            )
            ExportRow(
                title = stringResource(R.string.toolsb_reports_export_stitched_title),
                description = stringResource(R.string.toolsb_reports_export_stitched_desc),
            )
            ExportRow(
                title = stringResource(R.string.toolsb_reports_export_markdown_title),
                description = stringResource(R.string.toolsb_reports_export_markdown_desc),
                onClick = if (entriesInScope.isNotEmpty()) onShareMarkdown else null,
            )
            ExportRow(
                title = stringResource(R.string.toolsb_reports_export_trip_title),
                description = when (sessionCountWithNotes) {
                    0 -> stringResource(R.string.toolsb_reports_export_trip_none)
                    1 -> stringResource(R.string.toolsb_reports_export_trip_one)
                    else -> stringResource(R.string.toolsb_reports_export_trip_many, sessionCountWithNotes)
                },
                onClick = if (sessionCountWithNotes > 0) onShareTrip else null,
            )
            Text(
                stringResource(R.string.toolsb_reports_export_in_scope, entriesInScope.size),
                style = MaterialTheme.typography.labelSmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}

@Composable
private fun ExportRow(
    title: String,
    description: String,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PiruTheme.colors.secondaryLabel.copy(alpha = 0.08f))
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                if (onClick != null) stringResource(R.string.toolsb_reports_share)
                else stringResource(R.string.toolsb_reports_not_wired_up),
                style = MaterialTheme.typography.labelSmall,
                color = if (onClick != null) PiruTheme.colors.accent else PiruTheme.colors.secondaryLabel,
            )
        }
        Text(description, style = MaterialTheme.typography.labelSmall, color = PiruTheme.colors.secondaryLabel)
    }
}

// MARK: - Substance filter

@Composable
private fun SubstanceFilter(
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    substances: List<String>,
    filter: Set<String>?,
    includedCount: Int,
    onToggle: (String) -> Unit,
    onAll: () -> Unit,
    onNone: () -> Unit,
) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onExpanded(!expanded) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.toolsb_substances),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.toolsb_reports_filter_count, includedCount, substances.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (expanded) "▴" else "▾", style = MaterialTheme.typography.labelSmall)
            }
            if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onAll) { Text(stringResource(R.string.toolsb_select_all)) }
                    TextButton(onClick = onNone) { Text(stringResource(R.string.toolsb_deselect_all)) }
                }
                for (substance in substances) {
                    val included = filter == null || filter.contains(substance)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(substance) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            if (included) "●" else "○",
                            color = if (included) PiruTheme.colors.accent else PiruTheme.colors.secondaryLabel,
                        )
                        Text(substance, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

// MARK: - Helpers

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

private fun clockTime(instant: Instant, zone: ZoneId): String =
    CLOCK.withLocale(Locale.getDefault()).format(instant.atZone(zone))

/**
 * A report row's day. `Locale.ROOT` was wrong here — it pins the month and
 * weekday *names* to English, so a Chinese device read "Mon 28 Sep". The names
 * now come from the app's own resolved locale, which the caller passes in, and
 * the field order from the resource, because Chinese reads M月d日 EEE.
 */
private fun shortDate(instant: Instant, pattern: String, locale: Locale): String =
    DateTimeFormatter.ofPattern(pattern, locale).format(instant.atZone(ZoneId.systemDefault()))

