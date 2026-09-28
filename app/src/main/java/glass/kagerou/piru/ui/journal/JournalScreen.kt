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
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.ActiveSubstanceState
import glass.kagerou.piru.engine.DoseRecord
import glass.kagerou.piru.engine.PKModel
import glass.kagerou.piru.engine.timeline
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.meds.MyMedsCard
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    val app = context.applicationContext as PiruApplication

    var entries by remember { mutableStateOf<List<DoseEntryEntity>>(emptyList()) }
    var states by remember { mutableStateOf<List<ActiveSubstanceState>>(emptyList()) }
    var markers by remember { mutableStateOf<List<glass.kagerou.piru.engine.DoseMarker>>(emptyList()) }
    var tints by remember { mutableStateOf<Map<String, P3Color>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }

    // One load, not an observation yet. The observation wiring — a `Flow` from the
    // DAO through a view model — arrives with the quick-log sheet, which is what
    // makes a reload necessary without a relaunch.
    // Reloads on the invalidation counter rather than observing the log: the Flow
    // wiring arrives with the view-model layer, and until then a screen that only
    // loaded once would keep showing a dose the user just deleted.
    LaunchedEffect(navigator.dataVersion) {
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
                Text(
                    today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "${todaysEntries.size} logged today",
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

        if (loading) {
            item { Centered("Loading…") }
        } else if (entries.isEmpty()) {
            item {
                PiruCard {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Nothing logged yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "A dose is not a confession. Log one when you want the record, " +
                                "and the curves will follow it.",
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
                            "Today",
                            style = MaterialTheme.typography.labelLarge,
                            color = PiruTheme.colors.secondaryLabel,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        // The window is the engine's, not the screen's: the graph
                        // frames itself from the data's own tail, so a day whose
                        // doses all landed before noon is not drawn as fifteen
                        // hours of flat line. `dayBounded` is what keeps a
                        // long-acting dose from stretching that frame to days.
                        TimelineGraph(
                            states = states.filter { it.doseTimestamp in dayStart..dayEnd },
                            markers = markers.filter { it.timestamp in dayStart..dayEnd },
                            currentTime = Instant.now(),
                        )
                    }
                }
            }

            // Grouped by day, which is the shape the journal actually has: the
            // header counts one day, so the list beneath it must not run on into
            // the previous one. Upstream renders the same grouping, with the day's
            // own session hero above it — that hero belongs to the session port.
            val byDay = entries.groupBy { it.timestamp.toInstant().atZone(zone).toLocalDate() }
            for ((day, doses) in byDay) {
                item(key = "day-$day") {
                    Text(
                        dayLabel(day, today),
                        style = MaterialTheme.typography.labelLarge,
                        color = PiruTheme.colors.secondaryLabel,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(doses, key = { it.rowId }) { entry ->
                    DoseRow(entry, zone, onOpen = {
                        // A grouped dose opens its session — the reading the app
                        // made of it — and an ungrouped one opens the entry itself.
                        // Falling back rather than disabling: a dose with no session
                        // is still a dose, and the entry screen is where it is read.
                        val sessionId = entry.sessionId
                        if (sessionId != null) navigator.push(PushRoute.Session(sessionId.toString()))
                        else navigator.push(PushRoute.Entry(entry.timestamp.time, entry.id.toString()))
                    })
                }
            }
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
            // An unknown dose says so rather than printing "0 mg": the amount is
            // absent, and a zero is a claim the log does not make.
            Text(
                if (entry.isUnknownDose) "?" else "${entry.amount} ${entry.unit}",
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
 */
private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))
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

