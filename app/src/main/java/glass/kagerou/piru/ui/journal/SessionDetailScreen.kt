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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.data.entity.SessionEntity
import glass.kagerou.piru.engine.ActiveSubstanceState
import glass.kagerou.piru.engine.DoseMarker
import glass.kagerou.piru.engine.PKModel
import glass.kagerou.piru.engine.SessionVitals
import glass.kagerou.piru.engine.timeline
import glass.kagerou.piru.health.HealthConnectVitals
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.labels.appLocale
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.onboarding.OnboardingPrefs
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * A session: its span, its curves, and the doses inside it.
 *
 * Ported from `Journal/Session/` 鈥?twenty-one files and about 3,700 lines across
 * six sections (timeline, check-ins, entries, body load, safety, recovery). This
 * draws three of them: the header, the effect graph, and the entry list. The
 * remaining three are each a subsystem of their own 鈥?check-ins need the
 * notification scheduler, body load needs the depot path, and the safety section
 * reads the interaction checker.
 *
 * ## What a session is, and what it is not
 * Upstream's framing is worth carrying: a session is **an analysis artifact**.
 * Time is continuous and doses are logged when they are taken; the app groups
 * them afterwards, by clustering, and a session is that grouping 鈥?never a thing
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
    var vitals by remember(sessionId) { mutableStateOf(SessionVitals.empty) }
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
        vitals = loadSessionVitals(context, doses)
        loading = false
    }

    val zone = ZoneId.systemDefault()
    val loaded = session

    when {
        loading -> Centered(stringResource(R.string.journal_loading))
        loaded == null -> Centered(stringResource(R.string.journal_session_gone))
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
                            // merges redoses 鈥?the same rule the day view uses.
                            stackRedoses = true,
                            dayBounded = false,
                            // The only graph that carries the cardio lane. The day
                            // view shows today's doses, and a session is the unit a
                            // heart-rate response belongs to 鈥?see the lane's own note.
                            vitals = vitals,
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
                    stringResource(R.string.journal_doses_heading),
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
    // The language this screen's strings resolved to 鈥?the app's, not the
    // phone's, so an English screen never gets a German month name.
    val dateLocale = appLocale()
    // Resolved before the builder: the combined length is the only translated
    // part of the line, and a composable read inside `append` would be a read
    // buried in an expression that is not obviously a composition site.
    val minutes = last?.let { Duration.between(start, it).toMinutes() }
    val durationText = if (minutes != null && minutes >= 60) {
        stringResource(R.string.journal_session_duration, minutes / 60, minutes % 60)
    } else {
        null
    }
    Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            // A title the user wrote wins; the fallback is the day, and its
            // field order comes from the resources so Chinese reads
            // 9鏈?8鏃ユ槦鏈熶竴 rather than "鏄熸湡涓€, 28 涔濇湀".
            session.title?.takeIf { it.isNotBlank() }
                ?: start.atZone(zone).format(
                    DateTimeFormatter.ofPattern(
                        LocalContext.current.getString(R.string.datefmt_full_weekday_day_month),
                        dateLocale,
                    )
                ),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            buildString {
                append(start.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")))
                if (last != null) {
                    append(" 鈥?")
                    append(last.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")))
                    if (durationText != null) {
                        append("  路  ")
                        append(durationText)
                    }
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = PiruTheme.colors.secondaryLabel,
        )
        Text(
            pluralStringResource(R.plurals.journal_session_dose_count, doseCount, doseCount),
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

/**
 * The phone's cardiography over this session, or nothing.
 *
 * Ported from `SessionDetailModel.fetchVitals`.
 *
 * ## It reads; it never prompts
 * Permission is raised in exactly two places 鈥?the onboarding health step and the
 * health settings screen 鈥?and never here. Upstream documents the reason and it is
 * not a style preference: this screen is already a presented sheet, and asking for
 * Health consent from inside it is a double-presentation conflict, so the prompt
 * flashes up, is dismissed instantly, and re-fires every time the screen opens. With
 * no grant the read returns nothing and the lane is absent, which is the same
 * picture as a session with no watch.
 *
 * ## Two gates, both the user's
 * [OnboardingPrefs.showSessionVitals] is the opt-in from the health step, and the
 * granted set is the system's answer. Either being false means no read at all,
 * which matters beyond tidiness: a read with no permission on Android is not an
 * error, so calling it anyway would be a silent no-op that looks like work.
 *
 * ## Why the window is the doses, not the session row
 * A session's `lastDoseDate` is when the last dose was logged, not when its effects
 * ended 鈥?a long-acting dose is still climbing hours later. Reading only to that
 * point would cut the lane off exactly where the question gets interesting, so the
 * window runs from the first dose to the later of the last dose and now, and the
 * engine's own framing decides what to draw.
 */
private suspend fun loadSessionVitals(
    context: android.content.Context,
    doses: List<DoseEntryEntity>,
): SessionVitals {
    if (doses.isEmpty()) return SessionVitals.empty
    if (!OnboardingPrefs.showSessionVitals(context)) return SessionVitals.empty

    val health = HealthConnectVitals(context)
    if (health.availability() != HealthConnectVitals.Availability.AVAILABLE) return SessionVitals.empty
    // Nothing granted means the read below would return empty anyway; skipping it
    // keeps a permission-less session from paying for three Health Connect queries.
    if (health.grantedPermissions().isEmpty()) return SessionVitals.empty

    val start = doses.minOf { it.timestamp.time }.let(java.time.Instant::ofEpochMilli)
    val lastDose = doses.maxOf { it.timestamp.time }.let(java.time.Instant::ofEpochMilli)
    val now = java.time.Instant.now()
    return health.read(from = start, to = if (lastDose.isAfter(now)) lastDose else now)
}
