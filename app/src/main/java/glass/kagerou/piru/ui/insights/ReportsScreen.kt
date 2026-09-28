package glass.kagerou.piru.ui.insights

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.data.entity.SessionEntity
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
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

/**
 * Choosing what a report would cover, and seeing what that scope holds.
 *
 * Ported from `Views/Insights/ReportsView.swift` (570 lines) and
 * `ReportsModel.swift` (286 lines) — the scope half of them.
 *
 * ## The export half is deliberately not wired
 * Upstream's five exports are a UIKit `UIGraphicsPDFRenderer`, a
 * `UIGraphicsImageRenderer` twice over, and two Markdown strings, all handed to
 * a `UIActivityViewController`. Android's equivalent is an `ACTION_SEND` intent
 * with a `FileProvider` URI for the files and `EXTRA_TEXT` for the strings.
 * **None of that is in this build.** Rather than a card that looks like it will
 * share something and does not, each export row states plainly that it is not
 * connected to the system share sheet yet, and the row is inert.
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
    var sessionsWithNotes by remember { mutableStateOf<Set<UUID>>(emptySet()) }
    var loaded by remember { mutableStateOf(false) }

    val zone = remember { ZoneId.systemDefault() }

    LaunchedEffect(navigator.dataVersion) {
        loaded = false
        sessions = app.database.sessionDao().all()
        val doses = app.database.doseEntryDao().all()
        dosesBySession = doses.filter { it.sessionId != null }.groupBy { it.sessionId!! }
        allEntries = doses
        // A session "has notes" when at least one of them carries something —
        // the same test the trip report itself runs before it builds anything.
        sessionsWithNotes = app.database.sessionNoteDao().all()
            .filter { it.hasContent && it.sessionId != null }
            .mapNotNull { it.sessionId }
            .toSet()
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
                names.take(3).joinToString(", ") + " +${names.size - 3} more"
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

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Reports", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Choose what a report would cover. The exports themselves are not " +
                        "connected yet — this screen shows the scope they would be built from.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (option in ReportMode.entries) {
                    InsightsFilterPill(
                        label = option.label,
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
                InsightsSectionCard(title = "Scope") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ScopeLine("Entries", entryCountInScope.toString())
                        ScopeLine(
                            "Substances",
                            if (substanceFilter == null) {
                                substancesInScope.size.toString()
                            } else {
                                "$includedCount of ${substancesInScope.size}"
                            },
                        )
                        ScopeLine(
                            "Sessions",
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
                    sessionCountWithNotes = selectedSessions.count { sessionsWithNotes.contains(it) },
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
private enum class ReportMode(val label: String) {
    LATEST("Latest"),
    BY_DATE("By date"),
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
                "Select sessions"
            } else {
                "$selectedCount of $totalCount sessions · $entryCount entries"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (selectedCount == 0) PiruTheme.colors.secondaryLabel else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onAll) {
            Text(if (selectedCount == totalCount && totalCount > 0) "Deselect all" else "Select all")
        }
    }
    if (summaries.isEmpty()) {
        Text(
            "No sessions yet. A session is a run of doses close enough together to be one occasion.",
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
                        shortDate(summary.startDate),
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
                if (summary.doseCount == 1) "1 entry" else "${summary.doseCount} entries",
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
    DateRow("From", start, onStart)
    DateRow("To", end, onEnd)
    if (entryCount > 0) {
        Text(
            "$entryCount entries across $substanceCount substances",
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
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { showing = false }) { Text("Cancel") } },
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
 * The five exports, each named and each explicitly not wired up.
 *
 * The shapes and the descriptions are upstream's, because they are what a report
 * *will* be one day and naming them keeps the scope legible. The rows are inert:
 * a card that looks tappable and does nothing is worse than one that says what
 * is missing, and "share a broken file" is the one failure mode this screen must
 * not have.
 */
@Composable
private fun ExportList(
    mode: ReportMode,
    selectedSessionCount: Int,
    entriesInScope: List<DoseEntryEntity>,
    sessionCountWithNotes: Int,
) {
    InsightsSectionCard(title = "Exports", subtitle = "Not connected to the system share sheet yet") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ExportRow(
                title = "Journal summary",
                description = "Your entries, your meds and dose trends, as one document",
            )
            ExportRow(
                title = "Session images",
                description = if (mode == ReportMode.LATEST) {
                    "$selectedSessionCount sessions as individual images"
                } else {
                    "Sessions in this range as individual images"
                },
            )
            ExportRow(
                title = "Stitched image",
                description = "All selected sessions in one tall image",
            )
            ExportRow(
                title = "Markdown",
                description = "Plain-text session data — for notes, AI, or records",
            )
            ExportRow(
                title = "Trip report",
                description = when (sessionCountWithNotes) {
                    0 -> "Notes at their T+ offsets, descriptors by domain — none of the " +
                        "selected sessions has notes yet"
                    1 -> "Notes at their T+ offsets, descriptors by domain — 1 session with notes"
                    else -> "Notes at their T+ offsets, descriptors by domain — " +
                        "$sessionCountWithNotes sessions with notes"
                },
            )
            Text(
                "${entriesInScope.size} entries are in scope.",
                style = MaterialTheme.typography.labelSmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}

@Composable
private fun ExportRow(title: String, description: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PiruTheme.colors.secondaryLabel.copy(alpha = 0.08f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                "Not wired up",
                style = MaterialTheme.typography.labelSmall,
                color = PiruTheme.colors.secondaryLabel,
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
                Text("Substances", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(
                    "$includedCount of ${substances.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (expanded) "▴" else "▾", style = MaterialTheme.typography.labelSmall)
            }
            if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onAll) { Text("Select all") }
                    TextButton(onClick = onNone) { Text("Deselect all") }
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

private val SHORT_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ROOT)

private fun shortDate(instant: Instant): String = SHORT_DAY.format(instant.atZone(ZoneId.systemDefault()))

