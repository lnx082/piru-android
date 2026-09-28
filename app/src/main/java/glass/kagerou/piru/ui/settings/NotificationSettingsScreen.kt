package glass.kagerou.piru.ui.settings

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.data.entity.NotificationPreferencesEntity
import glass.kagerou.piru.notifications.NotificationPreferencesStore
import glass.kagerou.piru.notifications.NotificationType
import glass.kagerou.piru.notifications.PiruNotifications
import glass.kagerou.piru.notifications.isEnabled
import glass.kagerou.piru.notifications.isTimeSensitiveEnabled
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.LocalTime
import kotlinx.coroutines.launch

/**
 * Notifications.
 *
 * Ported from `Piru/Views/Settings/NotificationSettingsView.swift` — the rows, the
 * three groups, the pause switch, quiet hours and the re-ask cadence.
 *
 * ## Three groups, three channels
 * The grouping is not decoration. The three sections below are the three Android
 * notification channels ([PiruNotifications.CHANNEL_REMINDERS] and friends), so a
 * user who mutes "Session Alerts" here and a user who mutes that channel in
 * system settings have done the same thing. Everything else in this file is
 * per-type, which Android has no channel for; the app enforces those itself.
 *
 * ## Time Sensitive is shown, and shown as unavailable
 * The break-through row appears for the three types that support it on iOS (med
 * reminders and the cumulative warning) and its switch is **disabled**, with the
 * reason given underneath. A live switch would be a promise this platform cannot
 * keep, and a hidden row would be a silent loss of a setting the model still
 * carries. See [PiruNotifications] for why Android has no equivalent.
 *
 * ## Every write goes through the store, and the screen re-reads
 * There is no local draft state: each toggle calls the store and the screen
 * reloads what the store says. A screen that keeps its own copy of a preference
 * is a screen that can disagree with the notification that already went out.
 */
@Composable
fun NotificationSettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { NotificationPreferencesStore(context) }

    var prefs by remember { mutableStateOf(NotificationPreferencesStore.DEFAULTS) }
    var authorization by remember { mutableStateOf(PiruNotifications.Authorization.NOT_DETERMINED) }

    suspend fun reload() {
        prefs = store.load()
        authorization = PiruNotifications.authorization(context)
    }

    LaunchedEffect(Unit) {
        PiruNotifications.registerChannels(context)
        reload()
    }

    // Remembered, not built inline: `rememberLauncherForActivityResult` re-registers
    // the launcher whenever its contract's identity changes, and a contract built
    // in the composition body is a new object every recomposition.
    val applicationContext = context.applicationContext
    val permissionContract = remember(applicationContext) {
        PiruNotifications.permissionRequestContract(applicationContext)
    }
    val permissionLauncher = rememberLauncherForActivityResult(permissionContract) {
        scope.launch { reload() }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Text(
                "Notifications",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        item {
            SectionCard("Permission") {
                Text(
                    when (authorization) {
                        PiruNotifications.Authorization.AUTHORIZED -> "Piru may send notifications."
                        PiruNotifications.Authorization.NOT_DETERMINED ->
                            "Piru has not asked yet. Nothing below will arrive until it may."
                        PiruNotifications.Authorization.DENIED ->
                            "Notifications are off for Piru. Nothing below will arrive."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
                if (authorization != PiruNotifications.Authorization.AUTHORIZED) {
                    TextButton(onClick = {
                        PiruNotifications.notePermissionRequest(context)
                        permissionLauncher.launch(Unit)
                    }) {
                        Text("Allow notifications")
                    }
                }
            }
        }

        item {
            SectionCard("All notifications") {
                ToggleRow(
                    title = "Pause all",
                    detail = "Stops everything without losing the choices below.",
                    checked = prefs.masterEnabled,
                    onCheckedChange = { value -> scope.launch { store.setMasterEnabled(value); reload() } },
                )
            }
        }

        item {
            GroupCard(
                title = "Session Alerts",
                detail = "Hydration and wind-down nudges, onset and peak cues, and check-ins " +
                    "while something you logged is still active.",
            ) {
                for (type in listOf(
                    NotificationType.PHASE,
                    NotificationType.HYDRATION,
                    NotificationType.SLEEP,
                    NotificationType.CHECK_IN,
                )) {
                    TypeRow(
                        type = type,
                        prefs = prefs,
                        store = store,
                        onChanged = { scope.launch { reload() } },
                    )
                }
            }
        }

        item {
            GroupCard(
                title = "Med Reminders",
                detail = "Reminders at each routine's time, and a re-ask a little later if it has " +
                    "not been logged.",
            ) {
                for (type in listOf(NotificationType.ROUTINE, NotificationType.ROUTINE_FOLLOW_UP)) {
                    TypeRow(
                        type = type,
                        prefs = prefs,
                        store = store,
                        onChanged = { scope.launch { reload() } },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Text(
                    "Ask again",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (prefs.askAgainDefaultMinutes.isEmpty()) {
                        "Off — a reminder that has not been logged is not asked about again."
                    } else {
                        "A re-ask at " + prefs.askAgainDefaultMinutes.joinToString(", ") {
                            "+${it}m"
                        } + " after each reminder."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val cadence = prefs.askAgainDefaultMinutes
                    TextButton(
                        enabled = cadence.size < MAXIMUM_REASKS,
                        onClick = {
                            val next = if (cadence.isEmpty()) listOf(10) else cadence + minOf(cadence.last() + 10, 60)
                            scope.launch { store.setAskAgainDefault(next); reload() }
                        },
                    ) {
                        Text("Add re-ask")
                    }
                    TextButton(
                        enabled = cadence.size > 1,
                        onClick = {
                            scope.launch { store.setAskAgainDefault(cadence.dropLast(1)); reload() }
                        },
                    ) {
                        Text("Remove last")
                    }
                    TextButton(
                        enabled = cadence.isNotEmpty(),
                        onClick = {
                            scope.launch { store.setAskAgainDefault(emptyList()); reload() }
                        },
                    ) {
                        Text("Turn off")
                    }
                }
            }
        }

        item {
            GroupCard(
                title = "Safety & Supplies",
                detail = "A heads-up when one substance's daily total climbs into a heavy range, " +
                    "or when tracked stock runs low.",
            ) {
                for (type in listOf(NotificationType.CUMULATIVE, NotificationType.INVENTORY)) {
                    TypeRow(
                        type = type,
                        prefs = prefs,
                        store = store,
                        onChanged = { scope.launch { reload() } },
                    )
                }
            }
        }

        item {
            SectionCard("Quiet hours") {
                ToggleRow(
                    title = "Quiet hours",
                    detail = "Reminders whose time falls inside the window are not sent. The " +
                        "cumulative dose warning is never silenced — it is the one that has to arrive.",
                    checked = prefs.quietHoursEnabled,
                    onCheckedChange = { value ->
                        scope.launch { store.setQuietHours(enabled = value); reload() }
                    },
                )
                if (prefs.quietHoursEnabled) {
                    TimeRow(
                        label = "Start",
                        minutes = prefs.quietHoursStartMinutes,
                        onPicked = { scope.launch { store.setQuietHours(true, startMinutes = it); reload() } },
                    )
                    TimeRow(
                        label = "End",
                        minutes = prefs.quietHoursEndMinutes,
                        onPicked = { scope.launch { store.setQuietHours(true, endMinutes = it); reload() } },
                    )
                }
            }
        }

        item {
            SectionCard("Time Sensitive") {
                Text(
                    "iOS lets a notification break through Focus. Android has no equivalent: " +
                        "overriding Do Not Disturb needs a system-wide permission that also lets " +
                        "an app read and rewrite your DND rules, and Piru does not ask for that. " +
                        "Reminders about doses you logged still arrive at their time, as ordinary " +
                        "notifications.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
                for (type in NotificationType.entries.filter { it.supportsTimeSensitive }) {
                    ToggleRow(
                        title = type.rowTitle,
                        detail = null,
                        checked = prefs.isTimeSensitiveEnabled(type),
                        enabled = false,
                        onCheckedChange = {},
                    )
                }
            }
        }
    }
}

// MARK: - Rows

@Composable
private fun TypeRow(
    type: NotificationType,
    prefs: NotificationPreferencesEntity,
    store: NotificationPreferencesStore,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    ToggleRow(
        title = type.rowTitle,
        detail = type.rowDetail,
        checked = prefs.isEnabled(type),
        // The per-type switch is per-type only in the app: Android groups
        // notifications by channel, and these three channels are the three
        // sections. Turning a type off here cancels what it has already armed,
        // which is what `setEnabled` does.
        enabled = prefs.masterEnabled,
        onCheckedChange = { value ->
            scope.launch {
                store.setEnabled(type, value)
                onChanged()
            }
        },
    )
}

@Composable
private fun ToggleRow(
    title: String,
    detail: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun TimeRow(label: String, minutes: Int, onPicked: (Int) -> Unit) {
    val context = LocalContext.current
    val time = LocalTime.of(minutes / 60, minutes % 60)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = {
            TimePickerDialog(
                context,
                { _, hour, minute -> onPicked(hour * 60 + minute) },
                time.hour,
                time.minute,
                DateFormat.is24HourFormat(context),
            ).show()
        }) {
            // Locale.ROOT, as everywhere in this port: a Turkish device would
            // otherwise render a clock face with the wrong digits.
            Text("%02d:%02d".format(java.util.Locale.ROOT, time.hour, time.minute))
        }
    }
}

@Composable
private fun GroupCard(title: String, detail: String, content: @Composable () -> Unit) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
            content()
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

// MARK: - Copy

/**
 * The row's name, from the iOS screen's `rowTitle` table.
 *
 * "Reminders" and "Ask Again" are the parts of one idea — a routine time and its
 * follow-up — and they read as separate switches because a user may want the
 * first without the second.
 */
private val NotificationType.rowTitle: String
    get() = when (this) {
        NotificationType.HYDRATION -> "Hydration Reminders"
        NotificationType.SLEEP -> "Sleep Reminders"
        NotificationType.PHASE -> "Phase Alerts"
        NotificationType.CUMULATIVE -> "Cumulative Dose Warnings"
        NotificationType.ROUTINE -> "Med Reminders"
        NotificationType.ROUTINE_FOLLOW_UP -> "Ask Again"
        NotificationType.INVENTORY -> "Low Stock Alerts"
        NotificationType.CHECK_IN -> "Check-ins"
    }

/** What the row says under its name, so a switch is never the only description of itself. */
private val NotificationType.rowDetail: String?
    get() = when (this) {
        NotificationType.HYDRATION -> "A water reminder about an hour in, and one when the effect starts to fade."
        NotificationType.SLEEP -> "A wind-down nudge after a long session on something that keeps you up."
        NotificationType.PHASE -> "Onset, come-up and peak, timed from the dose's own modelled curve."
        NotificationType.CUMULATIVE -> "When one substance's total over twelve hours reaches the heavy range."
        NotificationType.ROUTINE -> "At the times you set for each medication."
        NotificationType.ROUTINE_FOLLOW_UP -> "A question a little later if a reminder has not been logged."
        NotificationType.INVENTORY -> "When a tracked supply crosses its low-stock threshold."
        NotificationType.CHECK_IN -> "A prompt to note how a session is going, on sessions you turn it on for."
    }

/** How many re-ask times the cadence editor allows, matching the iOS editor's cap of four. */
private const val MAXIMUM_REASKS = 4
