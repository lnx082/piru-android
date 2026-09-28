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
import glass.kagerou.piru.data.entity.SessionEntity
import glass.kagerou.piru.engine.ActiveSubstanceState
import glass.kagerou.piru.engine.DoseMarker
import glass.kagerou.piru.engine.PKModel
import glass.kagerou.piru.engine.timeline
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * A session: its span, its curves, and the doses inside it.
 *
 * Ported from `Journal/Session/` — twenty-one files and about 3,700 lines across
 * six sections (timeline, check-ins, entries, body load, safety, recovery). This
 * draws three of them: the header, the effect graph, and the entry list. The
 * remaining three are each a subsystem of their own — check-ins need the
 * notification scheduler, body load needs the depot path, and the safety section
 * reads the interaction checker.
 *
 * ## What a session is, and what it is not
 * Upstream's framing is worth carrying: a session is **an analysis artifact**.
 * Time is continuous and doses are logged when they are taken; the app groups
 * them afterwards, by clustering, and a session is that grouping — never a thing
 * the user has to open before they are allowed to log. So this screen renders a
 * *reading* of the log, and the log is the truth.
 */
@Composable
fun SessionDetailScreen(sessionId: String, navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    var session by remember(sessionId) { mutableStateOf<SessionEntity?>(null) }
    var doses by remember(sessionId) { mutableStateOf<List<DoseEntryEntity>>(emptyList()) }
    var states by remember(sessionId) { mutableStateOf<List<ActiveSubstanceState>>(emptyList()) }
    var markers by remember(sessionId) { mutableStateOf<List<DoseMarker>>(emptyList()) }
    var tints by remember(sessionId) { mutableStateOf<Map<String, P3Color>>(emptyMap()) }
    var loading by remember(sessionId) { mutableStateOf(true) }

    LaunchedEffect(sessionId) {
        val id = runCatching { UUID.fromString(sessionId) }.getOrNull()
        if (id == null) {
            loading = false
            return@LaunchedEffect
        }
        session = app.database.sessionDao().byId(id)
        doses = app.database.doseEntryDao().forSession(id)
        val records = doses.map { it.toDoseRecordForSession() }
        tints = app.palette().tintsFor(doses.map { it.substance }.toSet())
        val inputs = ActiveSubstanceState.timeline(
            entries = records,
            // The same palette the journal draws with, so a substance is one
            // colour everywhere rather than per screen.
            tintFor = { name -> tints[name.lowercase()] ?: P3Color.NEUTRAL },
            catalog = app.catalog(),
            weightKg = app.profile().weightKgOrDefault(),
        )
        states = inputs.states
        markers = inputs.markers
        loading = false
    }

    val zone = ZoneId.systemDefault()
    val loaded = session

    when {
        loading -> Centered("Loading…")
        loaded == null -> Centered("That session is gone.")
        else -> LazyColumn(
            modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = FAB_CLEARANCE),
        ) {
            item { SessionHeader(loaded, doses.size, zone) }

            item {
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        TimelineGraph(
                            states = states,
                            markers = markers,
                            // The session graph is anchored to its *end*, not to
                            // now: a session read back a week later must draw the
                            // same picture it drew the night it happened.
                            currentTime = loaded.lastDoseDate?.toInstant()
                                ?: loaded.startDate.toInstant(),
                            // The session graph keeps distinct routes separate and
                            // merges redoses — the same rule the day view uses.
                            stackRedoses = true,
                            dayBounded = false,
                        )
                    }
                }
            }

            item {
                SessionNotesSection(
                    sessionId = loaded.id,
                    checkInOffsetMinutes = loaded.checkInOffsetMinutes,
                )
            }

            item {
                Text(
                    "Doses",
                    style = MaterialTheme.typography.labelLarge,
                    color = PiruTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            items(doses, key = { it.rowId }) { entry ->
                PiruCard(modifier = Modifier.fillMaxWidth(), onClick = {
                    navigator.push(glass.kagerou.piru.ui.nav.PushRoute.Entry(entry.timestamp.time, entry.id.toString()))
                }) {
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
                        Text(
                            if (entry.isUnknownDose) "?" else "${entry.amount} ${entry.unit}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionHeader(session: SessionEntity, doseCount: Int, zone: ZoneId) {
    val start = session.startDate.toInstant()
    val last = session.lastDoseDate?.toInstant()
    Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            session.title?.takeIf { it.isNotBlank() }
                ?: start.atZone(zone).format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            buildString {
                append(start.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")))
                if (last != null) {
                    append(" – ")
                    append(last.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")))
                    val minutes = Duration.between(start, last).toMinutes()
                    if (minutes >= 60) append("  ·  ${minutes / 60} h ${minutes % 60} m")
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = PiruTheme.colors.secondaryLabel,
        )
        Text(
            "$doseCount dose${if (doseCount == 1) "" else "s"}",
            style = MaterialTheme.typography.bodySmall,
            color = PiruTheme.colors.secondaryLabel,
        )
    }
}

@Composable
private fun Centered(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = PiruTheme.colors.secondaryLabel)
    }
}

/** The session's entries, as the engine reads a dose. Mirrors the journal's mapper. */
internal fun DoseEntryEntity.toDoseRecordForSession() = glass.kagerou.piru.engine.DoseRecord(
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
