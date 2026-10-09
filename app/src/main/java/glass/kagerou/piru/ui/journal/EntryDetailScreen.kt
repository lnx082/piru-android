package glass.kagerou.piru.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.widget.MedWidgetRefresh
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.labels.CoreLabels
import glass.kagerou.piru.ui.labels.appLocale
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.theme.PiruTheme
import glass.kagerou.piru.engine.SubstanceCatalog
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlinx.coroutines.launch
import glass.kagerou.piru.engine.TagExtractor
import glass.kagerou.piru.engine.Enzyme
import glass.kagerou.piru.engine.InteractionData
import androidx.compose.material3.Surface
import glass.kagerou.piru.ui.meds.LocationPickerScreen
import glass.kagerou.piru.ui.meds.PickedLocation
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.engine.ActiveMetaboliteFold

/**
 * One logged dose, read and edited.
 *
 * Ported from `Journal/EntryDetailView` and its read/edit halves.
 *
 * ## The edit is a draft, not a live binding
 * Nothing reaches the store until Save, so an abandoned edit changes nothing —
 * the same posture the quick log's staging takes, and for the same reason: the
 * user is mid-thought, and a keystroke that has already been written is one they
 * cannot take back.
 *
 * ## An unknown amount is a first-class state, not a validation failure
 * Emptying the amount field sets the unknown flag rather than blocking the save.
 * A dose is a record of something that happened; "some of it" is an answer, and a
 * required field would throw it away. The screen says so rather than leaving the
 * user to guess why the button is still enabled.
 */
@Composable
fun EntryDetailScreen(
    timestampEpochMillis: Long,
    idOrNull: String?,
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    // The language this screen's own strings resolved to — not the device's, so
    // a German phone's English screens do not get a German month name.
    val dateLocale = appLocale()

    var entry by remember { mutableStateOf<DoseEntryEntity?>(null) }
    var loaded by remember { mutableStateOf(false) }

    // Amount, unit, route, time and note as *text*, so a half-typed number is a
    // draft rather than a parse failure.
    var amountText by remember { mutableStateOf("") }
    var unitText by remember { mutableStateOf("mg") }
    var route by remember { mutableStateOf(RouteOfAdministration.ORAL) }
    var substanceText by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }
    var isUnknownAmount by remember { mutableStateOf(false) }
    /**
     * The place this dose happened, if the user named one.
     *
     * Held as the picked value rather than as the row's three columns so the picker can hand one
     * object back. Null means "no place", which is what a dose has by default — and what the write
     * below stores as three nulls rather than as a point in the Gulf of Guinea.
     */
    var location by remember { mutableStateOf<PickedLocation?>(null) }
    var pickingLocation by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    // The doses around this one, and whether their overlap could be judged at all. Built after the entry loads,
    // beside the catalogue read the grapefruit check already does.
    //
    // Named `nearby` rather than `context`, because `context` is already this screen's `Context` — a shadowing
    // name here silently changed which object every `context.applicationContext` below referred to.
    var nearby by remember { mutableStateOf(EntryContext.Result(emptyList(), judged = false)) }

    // What is still in the body from **this** dose. Built from the same model the session page uses, with the entry
    // page's own heading and the cleared rows filtered out.
    var bodyLoad by remember { mutableStateOf(SessionBodyLoadModel.Result()) }

    // What the body makes from this dose: the "Also Active" block, built from the catalogue's metabolism rows.
    var metabolites by remember { mutableStateOf<List<ActiveMetaboliteFold.Entry>>(emptyList()) }
    var saved by remember { mutableStateOf(false) }

    /**
     * Whether grapefruit was taken with this dose.
     *
     * Only ever shown for a substance whose clearance a major share of CYP3A4 carries, and only
     * when the user has turned grapefruit logging on — which is what the flag's own column doc
     * says, and what makes it a real answer rather than a toggle for every substance in the
     * catalogue.
     */
    var hadGrapefruit by remember { mutableStateOf(false) }

    /**
     * Whether this screen should offer the toggle at all.
     *
     * The setting is a profile column and the substrate test is a metabolism read, so both are
     * resolved once when the entry loads rather than per recomposition.
     */
    var offersGrapefruit by remember { mutableStateOf(false) }

    // The catalogue, for resolving an edited name back to an identity. Held in state
    // rather than fetched inside the save so the click does not depend on a disk read
    // having finished; null simply means the name could not be checked, and the row's
    // existing identity is kept.
    var catalog by remember { mutableStateOf<SubstanceCatalog?>(null) }

    LaunchedEffect(Unit) {
        catalog = runCatching { app.catalog() }.getOrNull()
        // The setting first, because it gates the substrate read: no reason to ask the catalogue
        // when the user has not asked for this.
        if (app.profile().grapefruitLogging()) {
            val current = entry
            val name = current?.substance
            // majorEnzymes is on InteractionData, not SubstanceCatalog: the engine port
            // that carries the curated enzyme graph. The concrete catalogue is both.
            val metabolic = catalog as? InteractionData
            offersGrapefruit = name != null &&
                metabolic?.majorEnzymes(name)?.contains(Enzyme.CYP3A4) == true
        }
    }

    fun seed(row: DoseEntryEntity) {
        entry = row
        substanceText = row.substance
        amountText = if (row.isUnknownDose) "" else trimNumber(row.amount)
        unitText = row.unit
        route = row.route
        noteText = row.notes.orEmpty()
        isUnknownAmount = row.isUnknownDose
        // Tri-state on the column: null means "not recorded", which reads as off.
        hadGrapefruit = row.hadGrapefruit == true
        location = row.locationName?.let {
            PickedLocation(name = it, latitude = row.latitude, longitude = row.longitude)
        }
    }

    LaunchedEffect(timestampEpochMillis, idOrNull) {
        // Resolve by id first, then fall back to the timestamp. A route can arrive
        // from an older payload or a deep link with no id at all, and the id is
        // what survives an edit to the time — so it is tried first and the
        // timestamp is the safety net, not the other way round.
        val byId = idOrNull?.let { raw ->
            runCatching { java.util.UUID.fromString(raw) }.getOrNull()?.let { app.database.doseEntryDao().byId(it) }
        }
        val row = byId ?: app.database.doseEntryDao()
            .inRange(Date(timestampEpochMillis - 2_000), Date(timestampEpochMillis + 2_000))
            .minByOrNull { kotlin.math.abs(it.timestamp.time - timestampEpochMillis) }
        row?.let { seed(it) }
        loaded = true

        // What else was going on, built here rather than during composition because it reads the day's log.
        nearby = runCatching {
            if (row == null) return@runCatching EntryContext.Result(emptyList(), judged = false)
            val mine = row.timestamp.time / 60_000L
            // The window is the entry's own route's duration: a duration is per route, so comparing an oral dose
            // against an insufflated course would move the window and change which rows are marked.
            // The duration is on the **route row**, not on the catalogue: a substance's routes carry their own
            // profiles, which is what makes the per-route rule above true rather than aspirational.
            val window = catalog?.lookup(row.substance)
                ?.routes
                ?.firstOrNull { it.route == row.route }
                ?.duration
                // `estimatedTotalMinutes` and not `total?.midpoint`: the profile's own accessor is the figure the PK
                // curve is drawn over, so a window built from anything else would disagree with the graph on the
                // same page. A profile with no `total` still has a phase sum, which is what the accessor falls
                // back to.
                ?.estimatedTotalMinutes
            val around = app.database.doseEntryDao().all().filter { other ->
                other.rowId != row.rowId &&
                    kotlin.math.abs(other.timestamp.time - row.timestamp.time) <= 24L * 3_600_000L
            }
            // The metabolites of this dose, folded from the catalogue's metabolism rows. Same read as everything
            // else on this screen.
            metabolites = runCatching {
                val rows = (catalog as? glass.kagerou.piru.substance.DbSubstanceCatalog)
                    // `row`, not `current`: this is the load effect, where the smart-cast name does not exist.
                    ?.metabolismRows(row.substance)
                    .orEmpty()
                ActiveMetaboliteFold.fold(rows)
            }.getOrDefault(emptyList())

            // The body load for this one dose, from the same catalogue read as everything else here. The tints come
            // from the palette the journal already resolves, so a row's dot matches the same substance elsewhere.
            bodyLoad = runCatching {
                val resolved = catalog ?: return@runCatching SessionBodyLoadModel.Result()
                val tints = app.palette().tintsFor(setOf(row.substance))
                SessionBodyLoadModel.make(
                    entries = listOf(row),
                    catalog = resolved,
                    tintFor = { name -> tints[name.lowercase()] ?: P3Color.NEUTRAL },
                    fallbackTint = P3Color.NEUTRAL,
                    customNameFor = { canonical, product -> product ?: canonical },
                    // No active-metabolite accessor in this port's catalogue, so it is stated rather than defaulted:
                    // a silent `false` would read the same as "this substance has none".
                    hasActiveMetabolite = { false },
                )
            }.getOrDefault(SessionBodyLoadModel.Result())

            EntryContext.neighbours(
                entrySubstance = row.substance,
                windowMinutes = window,
                candidates = around.map { other ->
                    EntryContext.Candidate(
                        rowId = other.rowId,
                        substance = other.substance,
                        offsetMinutes = other.timestamp.time / 60_000L - mine,
                        sameSession = other.sessionId != null && other.sessionId == row.sessionId,
                    )
                },
            )
        }.getOrDefault(EntryContext.Result(emptyList(), judged = false))
    }

    // The picker takes the screen while it is up. It is a whole surface rather than a dialog body:
    // it draws its own header, its own close control and its own explanatory card, and upstream
    // presents it as a sheet for the same reason.
    if (pickingLocation) {
        Surface(modifier = Modifier.fillMaxSize(), color = PiruTheme.colors.background) {
            LocationPickerScreen(
                onPick = {
                    location = it
                    pickingLocation = false
                },
                onDismiss = { pickingLocation = false },
            )
        }
        return
    }

    val current = entry
    when {
        !loaded -> Centered(stringResource(R.string.journal_loading))
        current == null -> Centered(stringResource(R.string.journal_entry_gone))
        else -> Column(
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (editing) stringResource(R.string.journal_entry_edit) else current.substance,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    current.timestamp.toInstant().atZone(zone)
                        .format(
                            DateTimeFormatter.ofPattern(
                                context.getString(R.string.datefmt_full_weekday_day_month_time),
                                dateLocale,
                            )
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
                // Move, the third of BUG #31's operations. Beside the split action because both are statements about
                // **one dose**; it needs the picker because the target is a session the reader has to name.
                val moveSource = current.sessionId
                if (moveSource != null) {
                    PiruCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                stringResource(R.string.entry_move_action),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                stringResource(R.string.entry_move_detail),
                                style = MaterialTheme.typography.bodySmall,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                            TextButton(onClick = {
                                navigator.push(
                                    PushRoute.SessionPicker(
                                        kind = PushRoute.SessionPicker.Kind.MOVE.wireValue,
                                        // The **dose** row id, which is what `move` takes. The route carries one
                                        // string for both kinds so the picker has a single shape.
                                        sourceId = current.rowId.toString(),
                                    ),
                                )
                            }) {
                                Text(stringResource(R.string.entry_move_action))
                            }
                        }
                    }
                }

                // Split, offered only when it can succeed. `SessionRepository.split` returns null for a pivot that
                // is already the first dose, because there would be nothing left behind — so the card is drawn only
                // when the dose is **not** the first, and the button therefore cannot be a control that does nothing.
                //
                // One of BUG #31's three operations. `merge` and `move` both need a session **target picker**, which is
                // a new screen rather than an action on this one; they remain open and are named as such.
                val splitSessionId = current.sessionId
                var canSplit by remember(splitSessionId, current.rowId) { mutableStateOf(false) }
                LaunchedEffect(splitSessionId, current.rowId) {
                    canSplit = splitSessionId?.let { id ->
                        runCatching { app.sessionRepository().canSplitAt(id, current.rowId) }.getOrDefault(false)
                    } ?: false
                }
                if (canSplit && splitSessionId != null) {
                    PiruCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                stringResource(R.string.entry_split_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                stringResource(R.string.entry_split_detail),
                                style = MaterialTheme.typography.bodySmall,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                            TextButton(onClick = {
                                scope.launch {
                                    val created = app.sessionRepository().split(splitSessionId, current.rowId)
                                    // Only navigated when a session was actually created, so a refusal leaves the reader
                                    // where they were rather than on a screen for something that did not happen.
                                    if (created != null) {
                                        navigator.invalidate()
                                        navigator.push(PushRoute.Session(created.toString()))
                                    }
                                }
                            }) {
                                Text(stringResource(R.string.entry_split_done))
                            }
                        }
                    }
                    }

                current.sessionId?.let { sessionId ->
                    TextButton(onClick = { navigator.push(PushRoute.Session(sessionId.toString())) }) {
                        Text(stringResource(R.string.journal_entry_part_of_session))
                    }
                }
            }

            if (editing) {
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = substanceText,
                            onValueChange = { substanceText = it },
                            label = { Text(stringResource(R.string.common_substance)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = amountText,
                                onValueChange = {
                                    amountText = it
                                    // Typing a number takes the dose back out of the
                                    // unknown state — the flag follows the field,
                                    // rather than the two being able to disagree.
                                    if (it.isNotBlank()) isUnknownAmount = false
                                },
                                label = { Text(stringResource(R.string.common_amount)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = unitText,
                                onValueChange = { unitText = it },
                                label = { Text(stringResource(R.string.common_unit)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = isUnknownAmount,
                                onClick = {
                                    isUnknownAmount = !isUnknownAmount
                                    if (isUnknownAmount) amountText = ""
                                },
                                label = { Text(stringResource(R.string.journal_entry_amount_unknown)) },
                            )
                        }
                        for (candidate in routeChoices) {
                            FilterChip(
                                selected = route == candidate,
                                onClick = { route = candidate },
                                label = { Text(CoreLabels.route(candidate)) },
                            )
                        }
                        // Grapefruit, offered only where it would matter. A CYP3A4 inhibitor
                        // taken with a substrate cleared mostly by that enzyme is a real change
                        // to the substance's time course, and it is the one such context the app
                        // lets a user record per dose.
                        if (offersGrapefruit) {
                            FilterChip(
                                selected = hadGrapefruit,
                                onClick = { hadGrapefruit = !hadGrapefruit },
                                label = { Text(stringResource(R.string.journal_entry_grapefruit)) },
                            )
                        }
                        OutlinedTextField(
                            value = noteText,
                            onValueChange = { noteText = it },
                            label = { Text(stringResource(R.string.common_note)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // The place, as upstream's "Change Location" button. `EntryContextSection`
                        // shows it on the read side; this is the write side, and without it the
                        // three columns could only ever come from an import.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.meds_location),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    location?.name
                                        ?: stringResource(R.string.journal_entry_no_location),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PiruTheme.colors.secondaryLabel,
                                )
                            }
                            TextButton(onClick = { pickingLocation = true }) {
                                Text(
                                    stringResource(
                                        if (location == null) {
                                            R.string.journal_entry_add_location
                                        } else {
                                            R.string.journal_entry_change_location
                                        },
                                    ),
                                )
                            }
                            if (location != null) {
                                TextButton(onClick = { location = null }) {
                                    Text(stringResource(R.string.journal_entry_clear_location))
                                }
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { seed(current); editing = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    Button(
                        onClick = {
                            val parsed = amountText.trim().toDoubleOrNull()
                            val unknown = isUnknownAmount || parsed == null
                            val newName = substanceText.trim().ifEmpty { current.substance }
                            scope.launch {
                                // Renaming re-resolves the identity, because the identity
                                // key prefers the UID and therefore *ignores* the name:
                                // `SubstanceIdentity.identityKey` is the UID when one is
                                // present, so a dose renamed from Magnesium to Ibuprofen
                                // kept the magnesium key, went on crediting the magnesium
                                // med slot, and drew ibuprofen's curve from the name —
                                // the two halves of the same row disagreeing.
                                //
                                // A name the catalogue does not know clears the UID rather
                                // than keeping the stale one, which falls the key back to
                                // the name: an unknown substance is its own identity, and
                                // that is the honest answer.
                                val renamed = newName != current.substance
                                val resolvedUid = when {
                                    !renamed -> current.substanceUID
                                    else -> runCatching { catalog?.substanceUID(newName) }
                                        .getOrNull()
                                }
                                app.database.doseEntryDao().update(
                                    current.copy(
                                        substance = newName,
                                        substanceUID = resolvedUid,
                                        // An unparseable field is the unknown state, not
                                        // a silent zero: the flag and the number say the
                                        // same thing.
                                        amount = if (unknown) 0.0 else parsed!!,
                                        unit = unitText.trim().ifEmpty { "mg" },
                                        route = route,
                                        notes = noteText.trim().ifEmpty { null },
                                        isUnknownDose = unknown,
                                        // Left null when the toggle was not offered, so "not
                                        // recorded" stays distinguishable from "no grapefruit".
                                        hadGrapefruit = if (offersGrapefruit) hadGrapefruit else null,
                                        // All three or none: a name with no coordinate is what this
                                        // build can honestly record, and a coordinate with no name
                                        // would be a point the user cannot recognise later.
                                        locationName = location?.name,
                                        latitude = location?.latitude,
                                        longitude = location?.longitude,
                                    ).withTags(
                                        // Re-derived on every save rather than merged: the note
                                        // is the source of these, so removing a hashtag from the
                                        // text has to remove the tag. A merge would leave a tag
                                        // the user had deleted, with nothing left to explain it.
                                        TagExtractor.extractTags(noteText),
                                    ),
                                )
                                // The grouping is a reading of the log, so an edit that
                                // changes what the log says can change the session. The
                                // user's own merges are untouched — the sweep only
                                // looks at session-less doses, and this one has a home.
                                //
                                // The occurrence record is the same kind of reading and is
                                // re-derived for the same reason: a dose retimed onto
                                // another slot's hour, or relabelled onto another
                                // substance, satisfies a different slot than it did.
                                app.reconcileRoutineOccurrences()
                                // And the home-screen widget, which draws this slot's
                                // state. A dose retimed, relabelled or deleted settles a
                                // different slot than it did, and the widget was left
                                // showing the previous answer.
                                MedWidgetRefresh.afterWrite(app)
                                editing = false
                                saved = true
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.common_save)) }
                }
            } else {
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReadRow(
                            stringResource(R.string.common_amount),
                            if (current.isUnknownDose) {
                                stringResource(R.string.common_unknown)
                            } else {
                                "${trimNumber(current.amount)} ${current.unit}"
                            },
                        )
                        ReadRow(stringResource(R.string.common_route), CoreLabels.route(current.route))
                        current.saltForm?.let { ReadRow(stringResource(R.string.journal_label_salt), it) }
                        current.isomer?.let { ReadRow(stringResource(R.string.journal_label_isomer), it) }
                        current.releaseForm?.takeIf { it.isNotBlank() }?.let {
                            ReadRow(stringResource(R.string.journal_label_release), it)
                        }
                        current.productName?.let { ReadRow(stringResource(R.string.journal_label_product), it) }
                    }
                }

                if (!current.notes.isNullOrBlank()) {
                    PiruCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.common_note), style = MaterialTheme.typography.titleSmall)
                            Text(current.notes!!, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                // What is still in the body from this dose.
                SessionBodyLoadCard(
                    result = bodyLoad,
                    onOpenSubstance = { name -> navigator.push(PushRoute.Substance(name)) },
                    headingRes = R.string.journal_entry_in_your_body,
                    activeOnly = true,
                )

                EntryContextCard(
                    result = nearby,
                    // A neighbour opens its own entry page, which is where its own context lives. The read is
                    // suspend, so it happens in the screen's scope rather than on the click.
                    onOpen = { rowId ->
                        scope.launch {
                            val other = runCatching {
                                app.database.doseEntryDao().all().firstOrNull { it.rowId == rowId }
                            }.getOrNull()
                            if (other != null) {
                                navigator.push(PushRoute.Entry(other.timestamp.time, other.id.toString()))
                            }
                        }
                    },
                )

                // What the body makes from this dose. Below what is left of it: that card says how much, this one
                // says of what.
                ActiveMetaboliteCard(
                    entries = metabolites,
                    parentName = current.substance,
                    parentHalfLifeMinutes = catalog?.lookup(current.substance)?.halfLifeMinutes,
                    // The **longest** route duration, which is the window the outlasts claim is measured against.
                    // Null when the catalogue has no acute profile — the chronic-medication case, where the
                    // half-life decides instead.
                    parentDurationMinutes = catalog?.lookup(current.substance)
                        ?.routes
                        ?.mapNotNull { it.duration?.estimatedTotalMinutes }
                        ?.maxOrNull(),
                    accent = PiruTheme.colors.accent,
                    formationFractionPct = null,
                    onOpenSubstance = { name -> navigator.push(PushRoute.Substance(name)) },
                )

                if (current.isUnknownDose) {
                    PiruCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.journal_entry_unquantified_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PiruTheme.colors.secondaryLabel,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            // Clearing the confirmation on the way back in: it said
                            // "saved" about a *previous* edit, and leaving it up
                            // during a new one reads as if this edit were already
                            // committed.
                            saved = false
                            editing = true
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.common_edit)) }
                    TextButton(onClick = {
                        scope.launch {
                            app.database.doseEntryDao().deleteByRowId(current.rowId)
                            // The session it was in may now be empty or have a
                            // different span, so its bounds are re-derived.
                            current.sessionId?.let { app.database.sessionDao().refreshDoseBounds(it) }
                            // A dose that is gone no longer satisfies its slot, so the
                            // occurrence goes back to pending rather than leaving the
                            // re-ask suppressed by a dose the user deleted.
                            app.reconcileRoutineOccurrences()
// And the home-screen widget, which draws this slot's state. A dose retimed,
                            // relabelled or deleted settles a different slot than it did, and the widget
                            // was left showing the previous answer.
                            MedWidgetRefresh.afterWrite(app)
                            navigator.invalidate()
                            navigator.pop()
                        }
                    }) { Text(stringResource(R.string.common_delete)) }
                }
            }

            if (saved) {
                Text(
                    stringResource(R.string.journal_entry_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.successText,
                )
            }

            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = FAB_CLEARANCE))
        }
    }
}

/** The routes worth offering as chips. The full eleven live in the quick log's picker. */
private val routeChoices = listOf(
    RouteOfAdministration.ORAL,
    RouteOfAdministration.SUBLINGUAL,
    RouteOfAdministration.INSUFFLATION,
    RouteOfAdministration.INHALATION,
    RouteOfAdministration.INTRAVENOUS,
    RouteOfAdministration.INTRAMUSCULAR,
    RouteOfAdministration.SUBCUTANEOUS,
    RouteOfAdministration.RECTAL,
)

@Composable
private fun ReadRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = PiruTheme.colors.secondaryLabel)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Centered(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = PiruTheme.colors.secondaryLabel)
    }
}

/**
 * A dose, trimmed.
 *
 * Integral amounts lose the decimal point — "100 mg", not "100.0 mg" — which is
 * how the user typed it and how every source writes it.
 */
private fun trimNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
