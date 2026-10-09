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
import glass.kagerou.piru.data.AppSettingsStore
import androidx.compose.runtime.rememberCoroutineScope
import glass.kagerou.piru.notifications.CheckInScheduler
import kotlinx.coroutines.launch
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import glass.kagerou.piru.ui.insights.SessionShareImage
import glass.kagerou.piru.ui.insights.ShareImage
import glass.kagerou.piru.ui.labels.CoreLabels
import glass.kagerou.piru.engine.InteractionChecker
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.data.TimelineDisplay

/**
 * A session: its span, its curves, and the doses inside it.
 *
 * Ported from `Journal/Session/` —twenty-one files and about 3,700 lines across
 * six sections (timeline, check-ins, entries, body load, safety, recovery). This
 * draws three of them: the header, the effect graph, and the entry list. The
 * remaining three are each a subsystem of their own —check-ins need the
 * notification scheduler, body load needs the depot path, and the safety section
 * reads the interaction checker.
 *
 * ## What a session is, and what it is not
 * Upstream's framing is worth carrying: a session is **an analysis artifact**.
 * Time is continuous and doses are logged when they are taken; the app groups
 * them afterwards, by clustering, and a session is that grouping —never a thing
 * the user has to open before they are allowed to log. So this screen renders a
 * *reading* of the log, and the log is the truth.
 */
@Composable
fun SessionDetailScreen(sessionId: String, navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication

    // The journal preference, read once. This screen and the day view draw with the same
    // stacking rule, and this used to be a literal `true` here while the setting existed and
    // wrote to nothing that read it — so the preference reached one graph and not the other.
    val stackRedoses = remember { AppSettingsStore(context).stackRedoses() }
    val display = remember { TimelineDisplay.read(context) }

    var session by remember(sessionId) { mutableStateOf<SessionEntity?>(null) }
    var doses by remember(sessionId) { mutableStateOf<List<DoseEntryEntity>>(emptyList()) }
    var states by remember(sessionId) { mutableStateOf<List<ActiveSubstanceState>>(emptyList()) }
    var markers by remember(sessionId) { mutableStateOf<List<DoseMarker>>(emptyList()) }
    var tints by remember(sessionId) { mutableStateOf<Map<String, P3Color>>(emptyMap()) }
    var vitals by remember(sessionId) { mutableStateOf(SessionVitals.empty) }
    var loading by remember(sessionId) { mutableStateOf(true) }

    // The session's interaction warnings, folded by rule. Resolved here rather than in the card because the
    // checker needs the catalogue, which is a disk read.
    var safety by remember(sessionId) { mutableStateOf<List<InteractionGrouping.Group>>(emptyList()) }

    // What is still on board at the session's end. Built beside the timeline states, which need the same catalogue
    // and tints.
    var bodyLoad by remember(sessionId) { mutableStateOf(SessionBodyLoadModel.Result()) }

    // A scope for the export: it is a suspend render plus a share intent.
    val scope = rememberCoroutineScope()

    // Whether an export is being made, so the button cannot be tapped twice into two share sheets.
    var sharing by remember(sessionId) { mutableStateOf(false) }

    // The route labels, resolved in the composition because a resource read is `@Composable` and the renderer is
    // not. Built through `routeRes` rather than `CoreLabels.route`, because `route` is itself a composable and
    // cannot be called from a `remember` block — the ids can, and `stringResource` resolves them here.
    val routeLabels = glass.kagerou.piru.model.RouteOfAdministration.entries.associateWith { route ->
        stringResource(CoreLabels.routeRes(route))
    }

    // Bumped by the check-in cadence card, so the screen re-reads the session after the schedule
    // changes. A counter rather than a boolean because two changes in a row must each be a change.
    var checkInRevision by remember(sessionId) { mutableStateOf(0) }

    LaunchedEffect(sessionId, checkInRevision) {
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

        // The interaction warnings for this session's substances. Computed from the same checker the
        // interactions screen and the PDF use, so the three cannot disagree about a pair.
        // The body load, from the same doses the timeline just drew. `Instant.now()` is passed rather than
        // defaulted so the reading is taken once, with the same instant the timeline was framed against.
        bodyLoad = runCatching {
            val resolved = app.catalog()
            SessionBodyLoadModel.make(
                entries = doses,
                catalog = resolved,
                tintFor = { name -> tints[name.lowercase()] ?: P3Color.NEUTRAL },
                fallbackTint = P3Color.NEUTRAL,
                customNameFor = { canonical, product -> product ?: canonical },
                // The port's catalogue has no active-metabolite accessor, so this is stated rather than defaulted:
                // a silent `false` would read the same as "this substance has none".
                hasActiveMetabolite = { false },
            )
        }.getOrDefault(SessionBodyLoadModel.Result())

        safety = runCatching {
            val resolved = app.catalog()
            val checker = InteractionChecker(resolved, resolved)
            val names = doses.map { it.substance }.distinct()
            val records = doses.map { it.toDoseRecordForSession() }
            InteractionGrouping.group(checker.checkBatch(names, records))
        }.getOrDefault(emptyList())
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

            // The export. It renders off the data rather than capturing the screen, so it works for a session
            // read back a week later and does not depend on what is currently scrolled into view.
            if (doses.isNotEmpty()) {
                // Every resource read and every context-dependent value resolved **here**, in the composition.
                // `stringResource` cannot be called from the coroutine below, and the label map is built from a
                // `@Composable` accessor, so both have to be hoisted before the launch.
                val shareDateText = SessionShareImage.dateText(loaded.startDate.toInstant(), zone)
                // The share sheet's subject: the session's own title, or its date line when it has none.
                val shareSubject = loaded.title ?: shareDateText
                val shareTitle = loaded.title.orEmpty()
                val shareEntries = doses.sortedBy { it.timestamp.time }
                val shareId = loaded.id.toString()
                item {
                    TextButton(
                        enabled = !sharing,
                        onClick = {
                            sharing = true
                            scope.launch {
                                val bitmap = withContext(Dispatchers.Default) {
                                    runCatching {
                                        SessionShareImage.render(
                                            context = context,
                                            title = shareTitle,
                                            dateText = shareDateText,
                                            entries = shareEntries,
                                            tintFor = { name -> tints[name.lowercase()] ?: P3Color.NEUTRAL },
                                            routeLabelFor = { route -> routeLabels.getValue(route) },
                                        )
                                    }.getOrNull()
                                }
                                sharing = false
                                if (bitmap != null) {
                                    runCatching {
                                        ShareImage.share(
                                            context = context,
                                            bitmap = bitmap,
                                            name = "piru-session-$shareId",
                                            subject = shareSubject,
                                        )
                                    }
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.journal_session_share))
                    }
                }
            }

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
                            // merges redoses — the same rule the day view uses, and now the
                            // same *setting*: this was hardcoded `true`, so the journal
                            // preference reached the day view and not this one.
                            stackRedoses = stackRedoses,
                            display = display,
                            dayBounded = false,
                            // The only graph that carries the cardio lane. The day
                            // view shows today's doses, and a session is the unit a
                            // heart-rate response belongs to —see the lane's own note.
                            vitals = vitals,
                        )
                    }
                }
            }

            // The session's interaction warnings, above the doses: a warning below a long list is a
            // warning nobody reads.
            item {
                SessionSafetyCard(
                    groups = safety,
                    onOpenPair = { a, b -> navigator.push(PushRoute.InteractionTimeline(a, b)) },
                )
            }

            item {
                // What is left in the body. Below the warnings a reader needs first, above the notes about what
                // happened.
                SessionBodyLoadCard(
                    result = bodyLoad,
                    onOpenSubstance = { name -> navigator.push(PushRoute.Substance(name)) },
                )
            }

            item {
                // The check-in cadence, which had no writer at all.
                //
                // `CheckInScheduler.sync` and `shouldOffer` had zero callers, and
                // `SessionEntity.checkInIntervalMinutes` was written only by an import — so a session
                // created in this app could never have a check-in schedule, and the read-only
                // display in `SessionNotesSection` could only ever be populated by a file from iOS.
                CheckInCadenceCard(
                    session = loaded,
                    onChanged = { checkInRevision++ },
                )
            }

                // The session's own summary, above the note timeline: it is the sentence a reader sees first
                // and the one an export prints, and it had no reader or writer anywhere before this.
                item {
                    SessionSummaryCard(
                        sessionId = loaded.id,
                        // The screen re-reads the session so the header and the notes agree about it.
                        onChanged = { checkInRevision++ },
                    )
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
    // The language this screen's strings resolved to —the app's, not the
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
            // 9月28日星期一 rather than "星期一, 28 九月".
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
                    append(" – ")
                    append(last.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")))
                    if (durationText != null) {
                    append("  ·  ")
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
 * Permission is raised in exactly two places —the onboarding health step and the
 * health settings screen —and never here. Upstream documents the reason and it is
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
 * ended —a long-acting dose is still climbing hours later. Reading only to that
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


/**
 * The session's check-in cadence.
 *
 * Ported from `SessionCheckInSection.swift` and `CheckInScheduleEditor.swift`.
 *
 * ## What this fixes
 * `CheckInScheduler` was complete — it plans fire dates from the cadence and the session's own
 * offsets, respects quiet hours, words a medication session differently from a substance one, and
 * cancels by prefix when the schedule shrinks. **Nothing called it.** `sync` and `shouldOffer` had
 * zero callers in `app/src/main`, and `SessionEntity.checkInIntervalMinutes` was written only by an
 * import, so a session created in this app could never have a check-in schedule and the read-only
 * line in `SessionNotesSection` could only ever be populated by a file from iOS.
 *
 * Three choices, which are the ones upstream offers: off, hourly, or the session's own times.
 */
@Composable
private fun CheckInCadenceCard(
    session: SessionEntity,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val scope = rememberCoroutineScope()

    // The stored value is the cadence's own sentinel, so this is a read of what is there rather
    // than a parallel piece of state that could disagree with it.
    val current = CheckInScheduler.Cadence.fromStoredMinutes(session.checkInIntervalMinutes)

    PiruCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.journal_check_in_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.journal_check_in_detail),
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val options: List<Pair<CheckInScheduler.Cadence?, Int>> = listOf(
                    null to R.string.journal_check_in_off,
                    CheckInScheduler.Cadence.EVERY_HOUR to R.string.journal_check_in_hourly,
                    CheckInScheduler.Cadence.CUSTOM to R.string.journal_check_in_custom,
                )
                for (option in options) {
                    val (cadence, labelRes) = option
                    // Resolved here, not inside the lambda: `stringResource` is composable and an
                    // `onClick` is not.
                    val label = stringResource(labelRes)
                    FilterChip(
                        selected = current == cadence,
                        onClick = {
                            scope.launch {
                                // The row is written first and the schedule synced from it, because
                                // `CheckInScheduler.sync` plans from the stored value rather than
                                // from an argument — syncing first would arm the cadence the user
                                // just left.
                                app.setSessionCheckInCadence(session, cadence)
                                onChanged()
                            }
                        },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
}
