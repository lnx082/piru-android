package glass.kagerou.piru.ui.meds

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.entity.DailyDoseItemEntity
import glass.kagerou.piru.model.DoseFrequency
import glass.kagerou.piru.model.doseFormatted
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.ZoneId

/**
 * The one front door for everything a user takes on a schedule.
 *
 * Ported from `Piru/Views/Journal/DailyDose/MyMedsHubView.swift` (282 lines,
 * including the `MedRow` at its foot).
 *
 * ## The hub is a place
 * It opens a med's detail (through [onOpenMed]) rather than editing in place, so
 * only the add/edit *task* is modal. Upstream pushes onto whatever stack hosts
 * it; this port hands the push to the caller because the route does not exist
 * yet — see [onOpenMed].
 *
 * ## No named containers
 * Meds group by time of day on their own ([MedTimeGroup.belongs]), so there is
 * nothing to create and nothing to rename. A multi-time med appears once per
 * group it has a time in.
 *
 * ## One thing this screen does itself
 * "Notification Settings" pushes `PushRoute.NotificationSettings`, which already
 * exists — so the one navigation the hub owns is not a callback.
 */
@Composable
fun MyMedsHubScreen(
    navigator: AppNavigator,
    onOpenMed: (DailyDoseItemEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val zone = remember { ZoneId.systemDefault() }

    var items by remember { mutableStateOf<List<DailyDoseItemEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var showingAddMed by remember { mutableStateOf(false) }

    LaunchedEffect(navigator.dataVersion) {
        items = app.database.dailyDoseItemDao().all()
        loaded = true
    }

    val groups = remember(items) {
        MedTimeGroup.entries.mapNotNull { group ->
            val members = items.filter { MedTimeGroup.belongs(it, group) }
            if (members.isEmpty()) null else group to members
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("My Meds", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Add a Med",
                tint = PiruTheme.colors.accent,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { showingAddMed = true }
                    .padding(8.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
        ) {
            if (loaded && items.isEmpty()) {
                item {
                    PiruCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("No Meds Yet", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Keep track of what you take and when — one tap to set up " +
                                    "gentle reminders. Prescriptions, supplements, vitamins: " +
                                    "anything on a schedule.",
                                style = captionSecondaryStyle,
                            )
                        }
                    }
                }
            }

            for ((group, members) in groups) {
                item(key = "group-${group.slug}") {
                    Row(
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        MedsGlyph(kind = group.glyph(), tint = PiruTheme.colors.secondaryLabel, size = 13.dp)
                        Text(group.label, style = MaterialTheme.typography.labelLarge)
                        Text(group.rangeLabel, style = captionSecondaryStyle)
                    }
                }
                item(key = "card-${group.slug}") {
                    PiruCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            for (item in members) {
                                MedRow(
                                    item = item,
                                    group = group,
                                    zone = zone,
                                    onTap = { onOpenMed(item) },
                                )
                            }
                        }
                    }
                }
            }

            item {
                AddMedButton(onClick = { showingAddMed = true })
            }

            item {
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { navigator.push(PushRoute.NotificationSettings) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Notifications,
                            contentDescription = null,
                            tint = PiruTheme.colors.secondaryLabel,
                            modifier = Modifier.size(18.dp),
                        )
                        Text("Notification Settings", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    if (showingAddMed) {
        MedFormScreen(
            itemRowId = null,
            onDismiss = {
                showingAddMed = false
                navigator.invalidate()
            },
        )
    }
}

/** The hub's section mark, drawn like every other glyph in the package. */
private fun MedTimeGroup.glyph(): MedsGlyphKind = when (this) {
    MedTimeGroup.MORNING -> MedsGlyphKind.SUNRISE
    MedTimeGroup.AFTERNOON -> MedsGlyphKind.SUN
    MedTimeGroup.EVENING -> MedsGlyphKind.SUNSET
    MedTimeGroup.NIGHT -> MedsGlyphKind.MOON
    MedTimeGroup.ANYTIME -> MedsGlyphKind.PILL
    MedTimeGroup.AS_NEEDED -> MedsGlyphKind.VIAL
}

/**
 * The "Add a Med" pill.
 *
 * `OnboardingPillButton` is the app's public pill and is `internal` to this
 * module, but it is full-width and 52 dp tall — a hero button for a wizard step.
 * Here the pill sits at the end of a list, so it keeps upstream's shape
 * (`GlassPillButton`) at the list's own scale.
 */
@Composable
private fun AddMedButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Add a Med", style = MaterialTheme.typography.titleSmall, color = PiruTheme.colors.accent)
    }
}

/**
 * One med in one group. A multi-time med appears once per group it has a time
 * in, showing that group's time first and the others as "also …".
 */
@Composable
private fun MedRow(
    item: DailyDoseItemEntity,
    group: MedTimeGroup,
    zone: ZoneId,
    onTap: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTap).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MedAvatar(size = 30.dp)

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    displayName(item),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.isQuiet) {
                    MedsCapsuleChip(text = "quiet", tint = PiruTheme.colors.secondaryLabel)
                }
            }
            Text(rowSubtitle(item, group, zone), style = captionSecondaryStyle)
        }

        val remindersOn = item.remind && item.reminderTimesMinutes.isNotEmpty()
        MedsGlyph(
            kind = if (remindersOn) MedsGlyphKind.BELL else MedsGlyphKind.BELL_OFF,
            tint = if (remindersOn) PiruTheme.colors.accent else PiruTheme.colors.secondaryLabel,
            size = 14.dp,
        )
        MedsGlyph(
            kind = MedsGlyphKind.CHEVRON_RIGHT,
            tint = PiruTheme.colors.tertiaryLabel,
            size = 14.dp,
        )
    }
}

/**
 * The dose, the group's own times, any other times, and the cadence.
 *
 * The "also …" clause is what makes a twice-daily med legible in both of the
 * groups it appears in without duplicating it: each row leads with its own
 * group's time and mentions the rest.
 */
private fun rowSubtitle(item: DailyDoseItemEntity, group: MedTimeGroup, zone: ZoneId): String {
    val dose = "${doseFormatted(item.amount)} ${item.unit}"
    if (item.isAsNeeded) {
        val limit = item.maxPerDay
        return if (limit != null) "$dose · up to ${limit}× daily" else "$dose · as needed"
    }
    val times = item.reminderTimesMinutes
    if (times.isEmpty()) return "$dose · anytime"

    val inGroup = times.filter { MedTimeGroup.groupForMinutes(it) == group }
    val others = times.filter { MedTimeGroup.groupForMinutes(it) != group }
    var text = "$dose · " + inGroup.joinToString(" · ") { timeText(it, zone) }
    if (others.isNotEmpty()) {
        text += " · also " + others.joinToString(", ") { timeText(it, zone) }
    }
    if (item.frequency != DoseFrequency.DAILY) {
        text += " · ${frequencyShortLabel(item.frequency)}"
    }
    return text
}
