package glass.kagerou.piru.ui.meds

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.JsonLists
import glass.kagerou.piru.data.entity.DailyDoseItemEntity
import glass.kagerou.piru.model.DoseFrequency
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.model.doseFormatted
import glass.kagerou.piru.substance.DbSubstanceCatalog
import glass.kagerou.piru.substance.SubstanceMatch
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The 10-second add/edit form of the Meds redesign
 * (`Specs/meds-reminders-redesign.md`): what → how much → when → remind me.
 *
 * Ported from `Piru/Views/Journal/DailyDose/MedFormView.swift` (483 lines,
 * including the `MedFormDraft` at its head).
 *
 * ## The schedule is one decision, not two
 * Upstream folds the recurrence cadence and the separate `isAsNeeded` axis into
 * one picker ([FormSchedule]) so the form asks a question a person can answer:
 * "daily, weekly, … or as needed", rather than "frequency" plus "PRN".
 *
 * ## Presented as a full-screen dialog
 * Upstream presents it as a local sheet from the hub, and the note there is
 * load-bearing: dismissal must be the *local* dismiss, not `navigator.dismiss()`,
 * which would pop the hosting navigator sheet out from under it. This port takes
 * an [onDismiss] callback for the same reason, and hosts the form in a
 * `Dialog(usePlatformDefaultWidth = false)` — a long, keyboard-heavy form is the
 * one case this build's usual `ModalBottomSheet` idiom fits badly.
 *
 * ## Identity is resolved, never typed
 * The field text may read as the brand the user picked ("Medikinet"); the stored
 * substance is always the canonical family (Methylphenidate), with the brand
 * kept separately in `productName`. See [draftIdentity] for the one facet this
 * build cannot recover.
 *
 * ## No reminder resync on close
 * Upstream's `save()` ends in `DoseNotificationManager.syncMedReminders`. Here
 * that lives in [MedsStore.save] — the write and the reschedule are one call, so
 * a screen cannot commit a schedule it never re-armed.
 */
@Composable
fun MedFormScreen(
    itemRowId: Long?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val zone = remember { ZoneId.systemDefault() }
    val scope = rememberCoroutineScope()

    val draft = remember { MedFormDraft() }
    var existing by remember { mutableStateOf<DailyDoseItemEntity?>(null) }
    var existingCount by remember { mutableStateOf(0) }
    var catalog by remember { mutableStateOf<DbSubstanceCatalog?>(null) }
    var askAgainCadence by remember { mutableStateOf(listOf(10)) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(itemRowId) {
        val loadedCatalog = withContext(Dispatchers.Default) { app.catalog() }
        catalog = loadedCatalog
        existingCount = app.database.dailyDoseItemDao().all().size
        askAgainCadence = app.notificationPreferences().load().askAgainDefaultMinutes

        val item = itemRowId?.let { app.database.dailyDoseItemDao().byRowId(it) }
        existing = item
        if (item != null) draft.load(item, loadedCatalog)
    }

    val isEditing = existing != null
    val canSave = draft.substance.isNotBlank() &&
        draft.amount != null &&
        !(draft.frequency == DoseFrequency.SPECIFIC_DAYS && draft.selectedWeekdays.isEmpty())

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = modifier.fillMaxSize(), color = PiruTheme.colors.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (isEditing) "Edit Med" else "Add a Med",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    MedsGlyph(
                        kind = MedsGlyphKind.CLOSE,
                        tint = PiruTheme.colors.secondaryLabel,
                        size = 18.dp,
                        modifier = Modifier.clickable(onClick = onDismiss).padding(6.dp),
                    )
                    MedsGlyph(
                        kind = MedsGlyphKind.CHECK,
                        tint = if (canSave) PiruTheme.colors.accent else PiruTheme.colors.tertiaryLabel,
                        size = 20.dp,
                        modifier = Modifier
                            .clickable(enabled = canSave && !saving) {
                                saving = true
                                scope.launch {
                                    save(app, existing, draft, existingCount)
                                    saving = false
                                    onDismiss()
                                }
                            }
                            .padding(6.dp),
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 1. What.
                    FormSection("Med") {
                        SubstanceSearchField(
                            text = draft.substance,
                            catalog = catalog,
                            onTextChange = draft::typeSubstance,
                            onPick = draft::select,
                            onUseCustom = draft::useCustom,
                        )
                    }

                    // 2. How much, and by which route.
                    FormSection("Dosage") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedTextField(
                                value = draft.amountText,
                                onValueChange = { draft.amountText = it },
                                label = { Text("Amount") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                            UnitPicker(
                                units = draft.currentUnits(),
                                selected = draft.unit,
                                onSelect = { draft.unit = it },
                            )
                        }
                        RoutePicker(
                            routes = draft.availableRoutes,
                            selected = draft.route,
                            onSelect = {
                                draft.route = it
                                draft.onRouteChanged()
                            },
                        )
                        FormFooter("Checked off by an entry for the same substance and route.")
                    }

                    // 3. When.
                    FormSection("Schedule") {
                        SchedulePicker(schedule = draft.schedule, onSelect = draft::chooseSchedule)
                        if (draft.isAsNeeded) {
                            IntegerStepper(
                                value = draft.maxPerDay ?: 0,
                                range = 0..12,
                                label = draft.maxPerDay?.let { "Up to ${it}× daily" }
                                    ?: "No daily limit entered",
                                onChange = { draft.maxPerDay = if (it == 0) null else it },
                            )
                        }
                        if (!draft.isAsNeeded && draft.frequency == DoseFrequency.SPECIFIC_DAYS) {
                            WeekdayPicker(
                                selected = draft.selectedWeekdays,
                                onToggle = draft::toggleWeekday,
                            )
                        }
                        if (draft.needsStartDate) {
                            DateOffsetPicker(
                                startDate = draft.startDate,
                                onShift = { days ->
                                    draft.startDate = (draft.startDate ?: Instant.now())
                                        .plusSeconds(days * 86_400L)
                                },
                                zone = zone,
                            )
                        }
                        when {
                            draft.isAsNeeded -> FormFooter(
                                "Never marked missed. A daily limit feeds the cumulative " +
                                    "dose warnings.",
                            )

                            draft.frequency == DoseFrequency.SPECIFIC_DAYS &&
                                draft.selectedWeekdays.isEmpty() ->
                                FormFooter("Select at least one day.")

                            else -> Unit
                        }
                    }

                    // 4. When, and remind me.
                    if (!draft.isAsNeeded) {
                        FormSection("Times") {
                            for (time in draft.times) {
                                ReminderTimeRow(
                                    time = time,
                                    zone = zone,
                                    expanded = draft.expandedTimeId == time.id,
                                    onToggleExpanded = {
                                        draft.expandedTimeId =
                                            if (draft.expandedTimeId == time.id) null else time.id
                                    },
                                    onPick = { draft.setTime(time.id, it) },
                                    onDelete = { draft.removeTime(time.id) },
                                    consequence = MedTimeConsequence.resolve(
                                        draft.selectedSubstance,
                                        draft.route,
                                    ),
                                )
                            }
                            AddTimeButton(hasTimes = draft.times.isNotEmpty()) { draft.addTime() }
                            if (draft.times.isNotEmpty()) {
                                RemindMeToggle(remind = draft.remind) { draft.remind = it }
                            }
                            when {
                                draft.times.isEmpty() -> FormFooter(
                                    "No set time — this med still counts toward adherence " +
                                        "once per due day.",
                                )

                                draft.remind -> FormFooter(
                                    "A reminder at each time. If you don't log it, Piru asks " +
                                        "again ${askAgainCadence.joinToString(", ") { "$it min" }} later.",
                                )
                            }
                            if (draft.times.isNotEmpty() && draft.consequence != null) {
                                FormFooter(
                                    "Kick-in and wear-off come from this med's own duration " +
                                        "data — the same model the timeline draws. An estimate.",
                                )
                            }
                        }
                    }

                    // 5. Quiet tier.
                    QuietMedSection(isQuiet = draft.isQuiet) {
                        draft.isQuiet = it
                        draft.userTouchedQuiet = true
                    }
                }
            }
        }
    }
}

// MARK: - Save

/**
 * Turn the draft into a row and hand it to the store.
 *
 * The field text may read as the brand the user picked; the stored substance is
 * always the canonical family, with the brand kept separately in `productName`.
 * A hand-typed custom substance has no `selectedSubstance`, so its typed name is
 * the substance.
 */
private suspend fun save(
    app: PiruApplication,
    existing: DailyDoseItemEntity?,
    draft: MedFormDraft,
    existingCount: Int,
) {
    val amount = draft.amount ?: return
    val identity = draftIdentity(draft)
    val canonical = draft.selectedSubstance?.name ?: draft.substance.trim()
    val sortedTimes = draft.times.map { it.minutes }.toSortedSet().toList()

    val entity = existing?.copy() ?: DailyDoseItemEntity(
        substance = canonical,
        amount = amount,
        sortOrder = existingCount,
    )
    MedsStore.save(
        app = app,
        existing = existing,
        entity = entity.copy(
            substance = canonical,
            amount = amount,
            unit = draft.unit,
            route = draft.route,
            substanceUID = identity.uid,
            isomer = identity.isomer ?: existing?.isomer,
            releaseForm = identity.release ?: existing?.releaseForm,
            productName = identity.product,
            isBackgroundMed = draft.isQuiet,
            reminderTimesJson = JsonLists.encode(if (draft.isAsNeeded) emptyList() else sortedTimes),
            remind = draft.remind,
            isQuiet = draft.isQuiet,
            isAsNeeded = draft.isAsNeeded,
            maxPerDay = if (draft.isAsNeeded) draft.maxPerDay else null,
            frequencyRaw = (if (draft.isAsNeeded) DoseFrequency.DAILY else draft.frequency).wireValue,
            frequencyDaysJson = JsonLists.encode(draft.selectedWeekdays.sorted()),
            // Never the entity's `Date(0)` sentinel. `isDue` admits every day at
            // or after `startDate`, so a med saved at the epoch is "due" for every
            // day since 1970 — and because a daily med never shows the start-date
            // control, the user cannot correct it. The adherence calendar then
            // paints a month of failures for a medication they set up this
            // morning. Upstream's draft defaults to `.now` and never reaches
            // `distantPast` on this path.
            startDate = Date.from(draft.startDate ?: Instant.now()),
        ),
    )
}

/** The identity facets a saved med is stamped with. */
private data class DraftIdentity(
    val uid: String?,
    val isomer: String?,
    val release: String?,
    val product: String?,
)

/**
 * The identity to stamp on the saved item.
 *
 * Upstream derives it from `SubstanceLibrary.isomer(for:)` and
 * `releaseForm(for:)` — the facet-annotated alias table that turns "Concerta"
 * into Methylphenidate·XR. **This build has no such resolver**: neither
 * `DbSubstanceCatalog` nor `SubstanceIdentityIndex` exposes a facet lookup, and
 * `Substance` carries only the family uid. So the uid resolves and the two
 * facets stay whatever the row already had — which means a med saved under a
 * brand name keeps the family identity rather than the branded form, and a
 * "Concerta" med is answered by any methylphenidate dose of the same route.
 * That is a real fidelity loss, and it belongs to `:core:substance` rather than
 * to this form.
 */
private fun draftIdentity(draft: MedFormDraft): DraftIdentity =
    DraftIdentity(
        uid = draft.selectedSubstance?.substanceUID,
        isomer = null,
        release = null,
        product = draft.productName,
    )

// MARK: - The draft

/**
 * The form's edit draft — one stable holder instead of a pile of local `@State`
 * values, so the form has a single source of mutable state and each section
 * re-evaluates from the draft it actually reads.
 */
@Stable
private class MedFormDraft {
    var substance by mutableStateOf("")
    var amountText by mutableStateOf("")
    var unit by mutableStateOf("mg")
    var route by mutableStateOf(RouteOfAdministration.ORAL)

    // Schedule
    var schedule by mutableStateOf<FormSchedule>(FormSchedule.Frequency(DoseFrequency.DAILY))
    var selectedWeekdays by mutableStateOf<Set<Int>>(emptySet())

    /** Null means "unset" — the entity's `Date(0)` sentinel. */
    var startDate by mutableStateOf<Instant?>(null)
    var maxPerDay by mutableStateOf<Int?>(null)

    // Times & reminders
    var times by mutableStateOf<List<ReminderTime>>(emptyList())
    var remind by mutableStateOf(true)
    var expandedTimeId by mutableStateOf<Long?>(null)

    // Quiet tier — `userTouchedQuiet` keeps the supplement smart-default from
    // overriding an explicit choice when the substance changes afterwards.
    var isQuiet by mutableStateOf(false)
    var userTouchedQuiet by mutableStateOf(false)

    var selectedSubstance by mutableStateOf<Substance?>(null)
    var productName by mutableStateOf<String?>(null)
    var availableRoutes by mutableStateOf(RouteOfAdministration.entries.toList())

    private var nextTimeId = 0L

    val amount: Double? get() = amountText.trim().toDoubleOrNull()

    val isAsNeeded: Boolean get() = schedule is FormSchedule.AsNeeded

    val frequency: DoseFrequency
        get() = (schedule as? FormSchedule.Frequency)?.value ?: DoseFrequency.DAILY

    /** The acute profile the picked substance and route share, or null to stay silent. */
    val consequence: MedTimeConsequence?
        get() = MedTimeConsequence.resolve(selectedSubstance, route)

    /** Whether the start-date field is the one the current cadence needs. */
    val needsStartDate: Boolean
        get() = !isAsNeeded &&
            frequency != DoseFrequency.DAILY &&
            frequency != DoseFrequency.SPECIFIC_DAYS

    /** The unit choices: the substance's own first, then the form's defaults. */
    fun currentUnits(): List<String> {
        val sub = selectedSubstance ?: return DEFAULT_UNITS
        val preferred = sub.unit(route)
        return listOf(preferred) + (sub.routes.map { it.unit } + DEFAULT_UNITS)
            .distinct()
            .filter { it != preferred }
    }

    fun typeSubstance(text: String) {
        substance = text
        // Typing over a picked brand abandons the pick: identity resolution keys
        // off the canonical name, and a stale `selectedSubstance` would file the
        // typed name under the old family.
        selectedSubstance = null
        productName = null
        availableRoutes = RouteOfAdministration.entries.toList()
    }

    fun select(match: SubstanceMatch<Substance>) {
        val sub = match.substance
        selectedSubstance = sub
        substance = sub.displayTitle
        productName = match.matchedAlias
        route = sub.defaultRoute
        unit = sub.unit(sub.defaultRoute)
        availableRoutes = sub.orderedRoutes
        // Supplements default into the Quiet tier — a smart default only, so it
        // never overrides a choice the user already made.
        if (!userTouchedQuiet) isQuiet = sub.category == SubstanceCategory.SUPPLEMENT
    }

    fun useCustom() {
        selectedSubstance = null
        // A hand-typed custom substance names no catalog product.
        productName = null
        availableRoutes = RouteOfAdministration.entries.toList()
    }

    fun onRouteChanged() {
        selectedSubstance?.let { unit = it.unit(route) }
    }

    /**
     * Named `chooseSchedule` rather than `setSchedule`: `var schedule` already
     * generates a `setSchedule(FormSchedule)` on the JVM, and a same-signature
     * function beside it is a platform declaration clash rather than an
     * overload.
     */
    fun chooseSchedule(next: FormSchedule) {
        schedule = next
    }

    fun toggleWeekday(day: Int) {
        selectedWeekdays = if (selectedWeekdays.contains(day)) {
            selectedWeekdays - day
        } else {
            selectedWeekdays + day
        }
    }

    fun addTime() {
        times = times + ReminderTime(nextTimeId++, suggestedNextTime(times.map { it.minutes }))
    }

    fun setTime(id: Long, minutes: Int) {
        times = times.map { if (it.id == id) it.copy(minutes = minutes) else it }
        expandedTimeId = null
    }

    fun removeTime(id: Long) {
        times = times.filterNot { it.id == id }
    }

    fun load(item: DailyDoseItemEntity, catalog: DbSubstanceCatalog) {
        // Show the brand the med was saved under ("Medikinet") in the field,
        // while identity resolution still keys off the canonical name.
        substance = item.productName ?: item.substance
        amountText = doseFormatted(item.amount)
        unit = item.unit
        route = item.route
        schedule = if (item.isAsNeeded) {
            FormSchedule.AsNeeded
        } else {
            FormSchedule.Frequency(item.frequency)
        }
        selectedWeekdays = item.frequencyDays.toSet()
        // The sentinel reads as "today", not as "unset". Leaving it null would put
        // the write path's fallback back in play, and a row already stored at the
        // epoch would keep it — upstream maps `.distantPast` to `.now` here for
        // the same reason.
        startDate = item.startDate.toInstant().takeIf { it.toEpochMilli() > 0 } ?: Instant.now()
        maxPerDay = item.maxPerDay
        times = item.reminderTimesMinutes.map { ReminderTime(nextTimeId++, it) }
        remind = item.remind
        isQuiet = item.isQuiet
        userTouchedQuiet = true
        productName = item.productName

        val match = catalog.lookup(item.substance)
        if (match != null && match.name.equals(item.substance, ignoreCase = true)) {
            selectedSubstance = match
            availableRoutes = match.orderedRoutes
        }
    }

    private companion object {
        val DEFAULT_UNITS = listOf("mg", "g", "µg", "mL", "IU", "drops", "puffs")
    }
}

// MARK: - Substance search

/**
 * The substance field: an as-you-type catalog search with an explicit way to
 * keep what was typed.
 *
 * The build has no shared search field — QuickLog, the inventory form, the
 * steady-state tool and the interaction explorer each carry a private one — so
 * this is the meds screens' own. The catalog call is the same
 * `DbSubstanceCatalog.search` all four use, off the main thread.
 *
 * Upstream's `keepsMatchedName: true` is what the field's title shows: the name
 * as the user typed or picked it, while the row stores the canonical family.
 */
@Composable
private fun SubstanceSearchField(
    text: String,
    catalog: DbSubstanceCatalog?,
    onTextChange: (String) -> Unit,
    onPick: (SubstanceMatch<Substance>) -> Unit,
    onUseCustom: () -> Unit,
) {
    var results by remember { mutableStateOf<List<SubstanceMatch<Substance>>>(emptyList()) }

    LaunchedEffect(text, catalog) {
        val loaded = catalog ?: return@LaunchedEffect
        results = withContext(Dispatchers.Default) {
            if (text.isBlank()) emptyList() else loaded.search(text, limit = 8)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text("Substance") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        for (match in results) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(match) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MedAvatar(size = 22.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(match.substance.displayTitle, style = MaterialTheme.typography.bodyMedium)
                    match.matchedAlias?.let {
                        Text("matched \"$it\"", style = captionSecondaryStyle)
                    }
                }
            }
        }
        if (text.isNotBlank() && results.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onUseCustom)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Use \"${text.trim()}\" as typed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.accent,
                )
            }
        }
    }
}
