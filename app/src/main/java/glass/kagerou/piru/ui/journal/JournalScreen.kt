package glass.kagerou.piru.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.ActiveSubstanceState
import glass.kagerou.piru.engine.DoseRecord
import glass.kagerou.piru.engine.PKModel
import glass.kagerou.piru.engine.timeline
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.labels.appLocale
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.meds.MyMedsCard
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import glass.kagerou.piru.data.AppSettingsStore
import androidx.compose.foundation.clickable
import glass.kagerou.piru.data.entity.SessionEntity
import glass.kagerou.piru.engine.SubstanceCatalog
import androidx.compose.ui.graphics.Color
import glass.kagerou.piru.data.TimelineDisplay

/**
 * The journal's root: the day's curves, then the day's doses.
 *
 * Ported from `Journal/EntryListView.swift`, which has three states — an active
 * session hero, a grouped list, and an empty state. The **session hero is not
 * here**: `Session` is a whole subsystem (its detail screen is ~3,700 lines) and
 * the hero is the top of it. This renders the list state and the empty state, and
 * leaves the hero for the session port.
 *
 * ## Where the curves come from
 * The same `ActiveSubstanceState.timeline` the tests exercise: each dose resolves
 * a duration profile from the catalog and becomes a curve, or — when it has none —
 * a marker. That split is the engine's, not this screen's, which is why the list
 * below can say "no curve" about a dose without deciding anything itself.
 */
@Composable
fun JournalScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // The journal preference. Read here rather than defaulted, so the setting reaches the
    // graph it names.
    val stackRedoses = remember { AppSettingsStore(context).stackRedoses() }

    // The rest of the display options, read as one value. The preferences screen writes them and this is a separate
    // destination, so the read is keyed on the revision the screen already bumps — a stale copy here would make the
    // settings appear to do nothing until the app restarted.
    var display by remember { mutableStateOf(TimelineDisplay.read(context)) }
    val app = context.applicationContext as PiruApplication

    var entries by remember { mutableStateOf<List<DoseEntryEntity>>(emptyList()) }
    var states by remember { mutableStateOf<List<ActiveSubstanceState>>(emptyList()) }
    var markers by remember { mutableStateOf<List<glass.kagerou.piru.engine.DoseMarker>>(emptyList()) }
    var tints by remember { mutableStateOf<Map<String, P3Color>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }

    // The session rows, for the cards' titles and maintenance flags. Keyed by id string because that is what a
    // dose carries, and a map rather than a query per card.
    var sessionsById by remember { mutableStateOf<Map<String, SessionEntity>>(emptyMap()) }

    // The catalogue, for the cards' display titles. Held rather than opened per card: `sessionDays` resolves one
    // title per dose, and a lookup against a freshly opened catalogue per dose would be a read per frame.
    var catalog by remember { mutableStateOf<SubstanceCatalog?>(null) }

    // One load, not an observation yet. The observation wiring — a `Flow` from the
    // DAO through a view model — arrives with the quick-log sheet, which is what
    // makes a reload necessary without a relaunch.
    // Reloads on the invalidation counter rather than observing the log: the Flow
    // wiring arrives with the view-model layer, and until then a screen that only
    // loaded once would keep showing a dose the user just deleted.
    LaunchedEffect(navigator.dataVersion) {
        // The session rows and the catalogue, alongside the log. Both are read once here rather than per card,
        // because `sessionDays` resolves a title per dose and a session per card.
        sessionsById = runCatching {
            app.database.sessionDao().all().associateBy { it.id.toString() }
        }.getOrDefault(emptyMap())
        catalog = runCatching { app.catalog() }.getOrNull()
        loading = true
        // Group any session-less dose before reading, so a store that predates the
        // session model — or one recovered from a backup — has a grouping by the
        // time this screen renders it. Safe on every load: it only touches doses no
        // session owns.
        runCatching { app.ensureSessionsPopulated() }
        val all = app.database.doseEntryDao().all()
        entries = all
        val catalog = app.catalog()
        val records = all.mapNotNull { it.toDoseRecord() }
        // Resolved once for the day's substances. The generated colour is a gamut
        // search per substance, so asking per dose would repeat it thirty times over.
        tints = app.palette().tintsFor(all.map { it.substance }.toSet())
        val inputs = ActiveSubstanceState.timeline(
            entries = records,
            tintFor = { name -> tints[name.lowercase()] ?: P3Color.NEUTRAL },
            catalog = catalog,
            // The user's own weight, which scales the concentration every curve is
            // drawn from. It used to be the engine's 60 kg reference for everyone.
            weightKg = app.profile().weightKgOrDefault(),
        )
        states = inputs.states
        markers = inputs.markers
        loading = false
    }

    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    // The language these strings resolved to, hoisted out of the `item { … }`
    // below for the heading's weekday name. Not the device's language: this
    // app ships two, so a third-language phone gets English screens and would
    // otherwise get that language's month names inside them.
    val dateLocale = appLocale()
    val dayStart = today.atStartOfDay(zone).toInstant()
    val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant()
    val todaysEntries = entries.filter { it.timestamp.toInstant() in dayStart..dayEnd }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp)) {
                // The pattern is a resource, not a literal: it localizes the
                // *field order*, which the locale cannot. Chinese reads
                // 9月28日星期一 where the English pattern gives
                // "Monday, 28 September"; the names come from `dateLocale`,
                // the app's own resolved language rather than the device's.
                Text(
                    today.format(
                        DateTimeFormatter.ofPattern(
                            context.getString(R.string.datefmt_full_weekday_day_month),
                            dateLocale,
                        )
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    // The way into the continuous timeline. `PushRoute.Timeline`'s own declaration
                    // says it is "pushed from the journal's day header", and this heading is that
                    // header — so the link is the date the reader is already looking at. The day
                    // view shows one day; the timeline shows the log, which is what a pattern
                    // spanning weeks needs.
                    modifier = Modifier.clickable { navigator.push(PushRoute.Timeline) },
                )
                Text(
                    stringResource(R.string.journal_logged_today, todaysEntries.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        // Above the loading and empty branches, not after the timeline graph.
        // The graph renders only when there are doses, so a card placed there
        // would disappear on an empty log — which is exactly the fresh install
        // this card exists for. `MyMedsCard` draws nothing when no med is due, so
        // it costs nothing on the days it has nothing to say.
        item {
            MyMedsCard(
                navigator = navigator,
                onOpenMyMeds = { navigator.push(PushRoute.MyMeds) },
                onOpenMed = { navigator.push(PushRoute.MedDetail(it.rowId)) },
                onOpenRestock = { id -> navigator.push(PushRoute.InventoryItemForm(id = id)) },
            )
        }

        // The state surface, between the plan above and the log below — the order upstream's own comment
        // describes: plan → state → log. It draws nothing when nothing is active, so it costs nothing on the
        // days it has nothing to say, and it is placed before the loading branch because "what is in effect"
        // is the one reading that does not depend on today having entries.
        item {
            ActiveNowCard(
                states = states,
                onOpen = {
                    // Today's session, which is what the card is a reading of. `Timeline` is the continuous log,
                    // so the card opens the session rather than the day view it is already sitting in.
                    val today = entries.firstOrNull { it.sessionId != null }?.sessionId
                    if (today != null) {
                        navigator.push(PushRoute.Session(today.toString()))
                    } else {
                        navigator.push(PushRoute.Timeline)
                    }
                },
            )
        }

        if (loading) {
            item { Centered(stringResource(R.string.journal_loading)) }
        } else if (entries.isEmpty()) {
            item {
                PiruCard {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.journal_nothing_logged_yet),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.journal_nothing_logged_blurb),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                }
            }
        } else {
            item {
                PiruCard {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.journal_today),
                            style = MaterialTheme.typography.labelLarge,
                            color = PiruTheme.colors.secondaryLabel,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        // The window is the engine's, not the screen's: the graph
                        // frames itself from the states it is given, so a day whose
                        // doses all landed before noon is not drawn as fifteen hours
                        // of flat line.
                        //
                        // `states` is passed whole rather than filtered to the day,
                        // which is what makes a curve outlive midnight. A dose taken at
                        // 22:00 yesterday with a sixteen-hour profile is still climbing
                        // all this morning, and `TimelineCurveModel.dayBounded` exists to
                        // carry exactly that dose into today's frame. Pre-filtering by
                        // `doseTimestamp in dayStart..dayEnd` removed it before the
                        // engine ever saw it, defeating the mechanism and hiding the
                        // morning's curve; the screen's own comment above described the
                        // right behaviour while the line below it did the opposite.
                        //
                        // Markers stay day-scoped deliberately: a marker is a note at a
                        // moment, not a curve with a tail, so yesterday's does not belong
                        // in today's window.
                        TimelineGraph(
                            states = states,
                            markers = markers.filter { it.timestamp in dayStart..dayEnd },
                            currentTime = Instant.now(),
                            // The journal preference. This took the default `true` before, which
                            // happened to match the setting's own default — so the switch worked
                            // only in the direction of turning stacking *off*, and only on the
                            // session screen, which read it.
                            stackRedoses = stackRedoses,
                            display = display,
                        )
                    }
                }
            }

            // Grouped into days of **sessions**, which is the shape the journal actually has: the header counts
            // one day, so the list beneath it must not run on into the previous one, and a day's doses belong to
            // the sessions they were read as rather than to the day flat.
            //
            // The grouping is `sessionDays` rather than an inline `groupBy` because three of its rules are
            // decisions — a maintenance session draws as a compact row, a one-dose session reads as a single time
            // rather than a range, and two aliases of one drug collapse to one name in the summary.
            // Re-read on every pass that rebuilds the list, so a change made on the preferences screen is
            // reflected on return rather than on the next launch.
            display = TimelineDisplay.read(context)

            val days = sessionDays(
                entries = entries,
                sessions = sessionsById,
                zone = zone,
                today = today,
                displayTitleFor = { name -> catalog?.lookup(name)?.displayTitle ?: name },
            )
            for (day in days) {
                item(key = "day-${day.date}") {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Text(
                            // The day's heading. Resolved here rather than in the model because a heading is
                            // three resources and a pattern, and the pattern has to come from somewhere
                            // locale-aware — the locale localizes the month and weekday *names* a pattern
                            // produces but not their *order*. This is the same helper the flat day list used, kept
                            // rather than reimplemented.
                            dayLabel(day.date, today),
                            style = MaterialTheme.typography.labelLarge,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                        // The weekday beside the date, which the day list needs: "3 March" says when, and
                        // "Tuesday" says what kind of day it was.
                        Text(
                            day.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()),
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                }
                for (card in day.sessions) {
                    item(key = "session-${card.sessionId}-${card.startDate}") {
                        SessionRow(
                            card = card,
                            onOpen = {
                                if (card.navigable) {
                                    navigator.push(PushRoute.Session(card.sessionId))
                                }
                            },
                        )
                    }
                    // The session's own doses beneath it, so the card is the reading and the rows are what it was
                    // read from. A maintenance session is a compact row and its doses are the scheduled ones
                    // already shown by `MyMedsCard`, so they are not repeated here.
                    if (!card.isMaintenance) {
                        val doses = entries.filter { it.sessionId?.toString().orEmpty() == card.sessionId }
                        items(doses, key = { "dose-${it.rowId}" }) { entry ->
                            DoseRow(entry, zone, onOpen = {
                                val sessionId = entry.sessionId
                                if (sessionId != null) navigator.push(PushRoute.Session(sessionId.toString()))
                                else navigator.push(PushRoute.Entry(entry.timestamp.time, entry.id.toString()))
                            })
                        }
                    }
                }
            }
        }
    }
}

/**
 * One session as a row: the time it happened, what was in it, and how many doses.
 *
 * A **maintenance** session draws as a compact line — a scheduled tablet is not a session to read — and a normal
 * one as a full card. The distinction is `SessionCardModel`'s, where it is tested; this only draws the two
 * shapes.
 *
 * A non-navigable card is a dose whose session row is missing. It still draws, because dropping it would drop an
 * entry from the log, and it does not route, because there is nothing to open.
 */
@Composable
private fun SessionRow(card: SessionCard, onOpen: () -> Unit) {
    if (card.isMaintenance) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                card.timeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
            Text(
                card.substanceSummary.ifEmpty { stringResource(R.string.journal_maintenance) },
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
        return
    }

    PiruCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = if (card.navigable) onOpen else null,
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // The user's own title wins, because a session they named is one they want to find again.
                if (card.title != null) {
                    Text(card.title, style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    card.timeLabel,
                    style = if (card.title != null) {
                        MaterialTheme.typography.bodySmall
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                    color = if (card.title != null) PiruTheme.colors.secondaryLabel else Color.Unspecified,
                )
                Text(
                    card.substanceSummary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                stringResource(R.string.journal_dose_count, card.doseCount),
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}

@Composable
private fun DoseRow(entry: DoseEntryEntity, zone: ZoneId, onOpen: () -> Unit) {
    PiruCard(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(entry.substance, style = MaterialTheme.typography.titleSmall)
                Text(
                    entry.timestamp.toInstant().atZone(zone)
                        .format(DateTimeFormatter.ofPattern("HH:mm")),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
            // Through the entity's own readout, which carries **both** markers: `?` for an absent amount — the log
            // does not claim a zero — and `~` for an estimate. Nothing rendered the second before this, so an
            // estimated dose and a measured one printed identically.
            Text(
                "${entry.amountDisplay} ${entry.unit}",
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@Composable
private fun Centered(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = PiruTheme.colors.secondaryLabel)
    }
}

/**
 * A day's heading: "Today" and "Yesterday" by name, because that is how someone
 * reads their own log, and the date otherwise.
 *
 * `@Composable` for the two names and the pattern: a name is a resource, and so
 * is the pattern — the locale localizes the month and weekday *names* a pattern
 * produces but not their *order*, so the order has to come from somewhere
 * locale-aware, which is the resources. The locale itself is the app's own
 * resolved language, not the device's.
 */
@Composable
private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> stringResource(R.string.journal_today)
    today.minusDays(1) -> stringResource(R.string.journal_yesterday)
    else -> day.format(
        DateTimeFormatter.ofPattern(
            LocalContext.current.getString(R.string.datefmt_full_weekday_day_month),
            appLocale(),
        )
    )
}

private fun DoseEntryEntity.toDoseRecord(): DoseRecord = DoseRecord(
    substance = substance,
    amount = amount,
    unit = unit,
    route = route,
    timestamp = timestamp.toInstant(),
    isUnknownDose = isUnknownDose,
    releaseForm = releaseForm,
    productName = productName,
    saltForm = saltForm,
    isomer = isomer,
    substanceUID = substanceUID,
)

