package glass.kagerou.piru.ui.meds

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.entity.DailyDoseItemEntity
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.data.entity.RoutineOccurrenceEntity
import glass.kagerou.piru.engine.InteractionChecker
import glass.kagerou.piru.model.doseFormatted
import glass.kagerou.piru.notifications.toDoseRecord
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import kotlinx.coroutines.launch

/**
 * The Journal tab's "My Meds" card — the daily front door of the Meds redesign
 * (`Specs/meds-reminders-redesign.md`): today's checklist at a glance, tap a
 * circle to log, quiet meds folded into one "Supplements" row with a one-tap
 * Take All.
 *
 * Ported from `Piru/Views/Journal/DailyDose/MyMedsCard.swift` (646 lines).
 *
 * ## Hidden entirely while nothing is due
 * No meds at all, only PRN meds, or only off-cycle schedules: the card renders
 * nothing. A recreational-only journal therefore never carries it, and the
 * Journal stays a journal.
 *
 * ## What the caller has to supply
 * Three callbacks, because the routes this card opens — My Meds, one med's
 * detail, one inventory item's restock form — are the host's to own:
 * [onOpenMyMeds], [onOpenMed] and [onOpenRestock]. Nothing here constructs a
 * `PushRoute`, so the card compiles and behaves before those routes exist.
 *
 * ## The warm gate is gone, and why
 * Upstream renders nothing until `SubstanceStore.ensureAllLoaded()` has run,
 * because a row resolving its display name through the batch index used to build
 * the whole catalog synchronously on the main actor (a DEBUG tripwire caught
 * it). This build reads the catalog through a suspending `PiruApplication.catalog()`
 * off the main thread, and a row's name is the med's own `productName` or
 * `substance` — no index. So the gate has nothing to gate, and a missing one
 * cannot deadlock a card closed.
 */
@Composable
fun MyMedsCard(
    navigator: AppNavigator,
    onOpenMyMeds: () -> Unit,
    onOpenMed: (DailyDoseItemEntity) -> Unit,
    onOpenRestock: (itemId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val zone = remember { ZoneId.systemDefault() }
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<DailyDoseItemEntity>>(emptyList()) }
    var todayOccurrences by remember { mutableStateOf<List<RoutineOccurrenceEntity>>(emptyList()) }
    var yesterdayMissed by remember { mutableStateOf<List<RoutineOccurrenceEntity>>(emptyList()) }
    var recentEntries by remember { mutableStateOf<List<DoseEntryEntity>>(emptyList()) }
    var checker by remember { mutableStateOf<InteractionChecker?>(null) }
    var supplementsExpanded by remember { mutableStateOf(false) }
    var showInteractionSheet by remember { mutableStateOf(false) }

    /** Bumped by this card's own writes, which no other screen can signal. */
    var revision by remember { mutableStateOf(0) }

    val model = remember { MyMedsModel() }
    val info = remember { MyMedsInfoModel(medsPreferences(context)) }

    LaunchedEffect(navigator.dataVersion, revision) {
        val now = Instant.now()
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val yesterdayStart = todayStart.minus(1, ChronoUnit.DAYS)

        items = app.database.dailyDoseItemDao().all()
        val occurrenceWindow = app.database.routineOccurrenceDao().forDay(
            Date.from(yesterdayStart),
            Date.from(todayStart.plus(1, ChronoUnit.DAYS)),
        )
        todayOccurrences = occurrenceWindow.filter { it.dueDay.toInstant() >= todayStart }
        yesterdayMissed = occurrenceWindow.filter {
            it.dueDay.toInstant() < todayStart && it.state == RoutineOccurrenceEntity.State.MISSED
        }
        // The last 48 hours: what the interaction check reads, and the pool
        // today's undo picks from.
        recentEntries = app.database.doseEntryDao()
            .inRange(Date.from(now.minus(48, ChronoUnit.HOURS)), Date.from(now))
        checker = app.catalog().interactionChecker

        info.refresh(app, items, now, zone)
    }

    // Its own effect so a streak landing does not re-run the reads above — the
    // same split upstream makes with two `.task(id:)` modifiers.
    LaunchedEffect(navigator.dataVersion, revision, items) {
        model.refreshStreak(app, items, zone = zone)
    }

    val slots = MyMedsModel.slots(items, todayOccurrences, now = Instant.now(), zone = zone)

    // Nothing due — but "nothing due" and "nothing set up" are different states,
    // and only one of them should be silent. With no meds at all this card is the
    // only way into My Meds, so returning here would leave a fresh install with
    // no route to the editor: the feature would exist and be unreachable, which is
    // the exact failure the whole meds editor was built to end.
    if (slots.isEmpty()) {
        if (items.isNotEmpty()) return
        PiruCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenMyMeds) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.meds_my_meds), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.meds_card_empty_blurb),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
        return
    }

    // The Supplements fold only pays for itself with 2+ quiet slots — a single
    // quiet med renders as a plain row rather than a one-item group.
    val collapseQuiet = slots.count { it.item.isQuiet } >= 2
    val loudSlots = if (collapseQuiet) slots.filter { !it.item.isQuiet } else slots
    val quietSlots = if (collapseQuiet) slots.filter { it.item.isQuiet } else emptyList()
    val nowMinutes = MyMedsModel.nowMinutes(zone = zone)

    fun logSlots(target: List<MedSlot>) {
        if (target.isEmpty()) return
        scope.launch {
            MedsStore.log(app, target.map { it.item })
            model.clearPending()
            revision++
            navigator.invalidate()
        }
    }

    fun toggle(slot: MedSlot) {
        if (slot.taken) {
            scope.launch {
                val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant()
                val entry = MedsStore.latestMatch(recentEntries, slot.item, todayStart)
                if (entry != null) MedsStore.unlog(app, entry)
                revision++
                navigator.invalidate()
            }
            return
        }
        val activeChecker = checker
        if (activeChecker == null) {
            // The catalog is still opening. The interaction gate has nothing to
            // read yet, so the tap logs — a dose the app refuses to record
            // because a read was not ready is worse than one it records without
            // a warning it could not have produced anyway.
            logSlots(listOf(slot))
            return
        }
        val active = activeChecker.activeEntries(recentEntries.map { it.toDoseRecord() })
        if (model.mayLog(activeChecker, active, listOf(slot))) {
            logSlots(listOf(slot))
        } else {
            showInteractionSheet = true
        }
    }

    PiruCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MyMedsHeader(
                takenCount = slots.count { it.taken },
                total = slots.size,
                streak = model.streak,
                onTap = onOpenMyMeds,
            )

            Column {
                for (slot in loudSlots) {
                    SlotRow(
                        slot = slot,
                        zone = zone,
                        nowMinutes = nowMinutes,
                        indented = false,
                        onToggle = { toggle(slot) },
                        onOpen = { onOpenMed(slot.item) },
                    )
                }
                if (quietSlots.isNotEmpty()) {
                    SupplementsRow(
                        takenCount = quietSlots.count { it.taken },
                        total = quietSlots.size,
                        expanded = supplementsExpanded,
                        onToggleExpanded = { supplementsExpanded = !supplementsExpanded },
                        onTakeAll = {
                            val remaining = quietSlots.filter { !it.taken }
                            val activeChecker = checker
                            if (activeChecker == null) {
                                logSlots(remaining)
                            } else {
                                val active = activeChecker.activeEntries(recentEntries.map { it.toDoseRecord() })
                                if (model.mayLog(activeChecker, active, remaining)) {
                                    logSlots(remaining)
                                } else {
                                    showInteractionSheet = true
                                }
                            }
                        },
                    )
                    if (supplementsExpanded) {
                        for (slot in quietSlots) {
                            SlotRow(
                                slot = slot,
                                zone = zone,
                                nowMinutes = nowMinutes,
                                indented = true,
                                onToggle = { toggle(slot) },
                                onOpen = { onOpenMed(slot.item) },
                            )
                        }
                    }
                }
            }

            val lines = infoLines(
                slots = slots,
                items = items,
                yesterdayMissed = yesterdayMissed,
                restock = info.restock,
                nowMinutes = nowMinutes,
                zone = zone,
                isDismissed = { info.isDismissed(it) },
            )
            if (lines.isNotEmpty()) {
                Column {
                    for (line in lines) {
                        when (line) {
                            is MedsInfoLine.Restock -> RestockInfoLine(
                                name = line.name,
                                daysLeft = line.daysLeft,
                                onTap = { onOpenRestock(line.itemId) },
                            )

                            is MedsInfoLine.NextDue -> NextDueInfoLine(
                                name = line.name,
                                timeText = timeText(line.minutes, zone),
                                onTap = onOpenMyMeds,
                            )

                            is MedsInfoLine.MissedYesterday -> MissedYesterdayInfoLine(
                                notice = line.notice,
                                onTap = onOpenMyMeds,
                                onDismiss = {
                                    info.dismiss(line.notice.dayKey)
                                    revision++
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showInteractionSheet) {
        InteractionWarningSheet(
            warnings = model.interactionWarnings,
            onProceed = {
                showInteractionSheet = false
                logSlots(model.pendingSlots)
            },
            onCancel = {
                showInteractionSheet = false
                model.clearPending()
            },
        )
    }
}

// MARK: - Info lines

/**
 * The two-at-most facts under the rows, chosen by [MyMedsInfo.select].
 *
 * The missed entries are named through the item that scheduled them when one
 * exists, so the notice says "Memantine" rather than whatever casing the
 * occurrence row happens to carry.
 */
private fun infoLines(
    slots: List<MedSlot>,
    items: List<DailyDoseItemEntity>,
    yesterdayMissed: List<RoutineOccurrenceEntity>,
    restock: MedsInfoLine?,
    nowMinutes: Int,
    zone: ZoneId,
    isDismissed: (String) -> Boolean,
): List<MedsInfoLine> {
    val summaries = slots.map { slot ->
        MyMedsInfo.SlotSummary(
            name = displayName(slot.item),
            minutes = slot.time,
            pending = slot.state == SlotState.PENDING,
        )
    }
    val missed = yesterdayMissed.map { occurrence ->
        val item = items.firstOrNull { it.substance.equals(occurrence.substance, ignoreCase = true) }
        (item?.let(::displayName) ?: occurrence.substance) to occurrence.slotMinutes
    }
    val missedLine = MyMedsInfo.missedYesterday(missed, LocalDate.now(zone).minusDays(1))
    // Dismissal is checked before the cap, not after: the ✕ has to free the
    // second line for the next-due fact rather than leave an empty slot where
    // the notice used to be.
    val missedDismissed = (missedLine as? MedsInfoLine.MissedYesterday)
        ?.let { isDismissed(it.notice.dayKey) }
        ?: false
    return MyMedsInfo.select(
        restock = restock,
        nextDue = MyMedsInfo.nextDue(summaries, nowMinutes),
        missed = missedLine,
        missedDismissed = missedDismissed,
    )
}

/**
 * A med's display name.
 *
 * Upstream is `productName ?? CustomSubstanceStore.shared.displayName(for:)` —
 * the user's own label for a hand-typed substance wins over the catalog's. This
 * build has **no reader for `custom_substances`** (the table exists; nothing
 * queries it), so the fallback is the substance name itself. A med the user
 * named shows the name they gave it, which is the case that matters.
 */
internal fun displayName(item: DailyDoseItemEntity): String = item.productName ?: item.substance

// MARK: - Header

/**
 * The card's tappable header: the title and the progress chip. No due hint here
 * — a pending row and its "due" chip already say it.
 */
@Composable
private fun MyMedsHeader(
    takenCount: Int,
    total: Int,
    streak: Int?,
    onTap: () -> Unit,
) {
    val isComplete = total > 0 && takenCount == total
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.meds_my_meds), style = MaterialTheme.typography.titleSmall)
        if (isComplete) {
            MedsCapsuleChip(
                text = if (streak != null && streak > 1) {
                    stringResource(R.string.meds_days_logged, streak)
                } else {
                    stringResource(R.string.common_done)
                },
                tint = PiruTheme.colors.accent,
                filled = true,
            )
        } else {
            MedsCapsuleChip(text = "$takenCount/$total", tint = PiruTheme.colors.secondaryLabel)
        }
        Spacer(Modifier.weight(1f))
        MedsGlyph(
            kind = MedsGlyphKind.CHEVRON_RIGHT,
            tint = PiruTheme.colors.tertiaryLabel,
            size = 14.dp,
        )
    }
}

// MARK: - Rows

/**
 * One checklist row.
 *
 * The row is split into two targets, as upstream splits it: the circle toggles
 * the dose, the rest of the row opens the med. Merging them would make a
 * mis-tap on the name log a dose.
 */
@Composable
private fun SlotRow(
    slot: MedSlot,
    zone: ZoneId,
    nowMinutes: Int,
    indented: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val skipped = slot.state == SlotState.SKIPPED
    val taken = slot.taken
    val due = slot.isDueNow(nowMinutes)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indented) 20.dp else 0.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(6.dp)
                .clickable(enabled = !skipped, onClick = onToggle),
        ) {
            MedCheckCircle(
                state = when (slot.state) {
                    SlotState.TAKEN -> MedCheckState.TAKEN
                    SlotState.SKIPPED -> MedCheckState.SKIPPED
                    SlotState.PENDING -> MedCheckState.PENDING
                },
                due = due,
            )
        }

        Row(
            modifier = Modifier.weight(1f).clickable(onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = displayName(slot.item),
                style = MaterialTheme.typography.bodyMedium,
                color = if (taken || skipped) PiruTheme.colors.secondaryLabel else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (taken) TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (skipped) {
                Text(
                    stringResource(R.string.meds_skipped),
                    style = MaterialTheme.typography.labelSmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            } else if (due && slot.time != null) {
                MedsCapsuleChip(text = stringResource(R.string.meds_due_chip), tint = PiruTheme.colors.accent)
            }
            Text(
                "${doseFormatted(slot.item.amount)} ${slot.item.unit}",
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
            slot.time?.let {
                Text(
                    "· ${timeText(it, zone)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
    }
}

/**
 * The collapsed "Supplements" group row: a fractional ring, the taken count, a
 * one-tap Take All, and the disclosure chevron.
 */
@Composable
private fun SupplementsRow(
    takenCount: Int,
    total: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onTakeAll: () -> Unit,
) {
    val accent = PiruTheme.colors.accent
    val muted = PiruTheme.colors.tertiaryLabel
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f).clickable(onClick = onToggleExpanded),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Canvas(Modifier.size(18.dp)) {
                val radius = size.minDimension / 2 - 1f
                if (takenCount == total) {
                    drawCircle(color = accent, radius = size.minDimension / 2)
                } else {
                    drawCircle(color = muted, radius = radius, style = Stroke(width = 2f))
                    val fraction = if (total == 0) 0f else takenCount.toFloat() / total
                    // The arc starts at twelve o'clock, where SwiftUI's
                    // `.rotationEffect(.degrees(-90))` put it.
                    drawArc(
                        color = accent,
                        startAngle = -90f,
                        sweepAngle = 360f * fraction,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(width = 2f, cap = StrokeCap.Round),
                    )
                }
            }
            Column {
                Text(stringResource(R.string.meds_supplements), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.meds_taken_of_total, takenCount, total),
                    style = captionSecondaryStyle,
                )
            }
        }
        if (takenCount < total) {
            MedsCapsuleChip(
                text = stringResource(R.string.meds_take_all),
                tint = accent,
                filled = true,
                modifier = Modifier.clickable(onClick = onTakeAll),
            )
        }
        MedsGlyph(
            kind = MedsGlyphKind.CHEVRON_RIGHT,
            tint = PiruTheme.colors.secondaryLabel,
            size = 14.dp,
            modifier = Modifier.rotate(if (expanded) 90f else 0f).clickable(onClick = onToggleExpanded),
        )
    }
}
