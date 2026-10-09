package glass.kagerou.piru.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.data.AppSettingsStore
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import glass.kagerou.piru.data.TimelineBubbleStyleName
import androidx.compose.ui.semantics.Role

/**
 * The vertical timeline's display options.
 *
 * ## Why this screen exists
 * Upstream keeps seven keys in an app-group suite — zoom, gap compression, PK curves, the hour axis, the bubble
 * style, the vitals overlay and redose stacking. This port had **one** of them, `stackRedoses`, so the timeline drew
 * itself the same way for everybody and there was nowhere to say otherwise.
 *
 * Three of the remaining six are here: zoom, the hour axis and the bubble style. The other two that describe the
 * strip — modeled curves and gap compression — are **deliberately absent**, because this port has no
 * concentration-curve layer and no vertical bubble strip, so neither could change anything. See `TimelineOptions`.
 *
 * ## Why the state is re-read on every write rather than cached
 * Each value is held in a `remember`ed state initialised from the store and written back on change. That is the
 * shape `SettingsScreen` already uses, and it is right here for the same reason: **two surfaces draw the strip** —
 * this screen and the journal's own grouping — and a cached copy in one would go stale the moment the other wrote.
 * The store is the single source, and this is a view of it.
 *
 * ## The gating is `TimelineOptions`' rule, not this screen's
 * Zoom, PK curves and gap compression describe the strip's geometry, so with the axis off they are **not drawn**
 * rather than drawn disabled. The reason is upstream's: a disabled control inside a menu still reads as something
 * the user might be able to turn on, and on a Compose `DropdownMenu` a disabled row still takes a tap target. An
 * option that cannot do anything is not an option.
 *
 * The preferences themselves are **kept**, so turning the axis off and on again restores what the user had.
 */
@Composable
fun TimelinePreferencesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { AppSettingsStore(context) }

    var zoom by remember { mutableStateOf(store.timelineZoom()) }
    var showsAxis by remember { mutableStateOf(store.timelineShowsAxis()) }
    var showsVitals by remember { mutableStateOf(store.timelineVitalsShown()) }
    var grouping by remember { mutableStateOf(JournalGrouping.from(store.journalGrouping())) }
    var bubbleStyle by remember { mutableStateOf(TimelineBubbleStyleName.from(store.timelineBubbleStyle())) }

    val rows = TimelineOptions.rows(showsAxis)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.journal_timeline_prefs_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        if (TimelineOptions.Row.SHOWS_AXIS in rows) {
            ToggleCard(
                title = stringResource(R.string.journal_timeline_axis),
                detail = stringResource(R.string.journal_timeline_axis_detail),
                checked = showsAxis,
                onCheckedChange = {
                    showsAxis = it
                    store.setTimelineShowsAxis(it)
                    // Nothing else is written: the withdrawn rows' preferences are **kept**, so turning the axis
                    // back on restores what the user had rather than resetting it.
                },
            )
        }

        if (TimelineOptions.Row.VITALS in rows) {
            ToggleCard(
                title = stringResource(R.string.journal_timeline_vitals),
                detail = stringResource(R.string.journal_timeline_vitals_detail),
                checked = showsVitals,
                onCheckedChange = {
                    showsVitals = it
                    store.setTimelineVitalsShown(it)
                },
            )
        }

        // Radio rows rather than a switch: neither grouping is the "on" state, so a switch would imply that off means
        // something other than a complete layout. The zoom ladder below is drawn the same way for the same reason.
        PiruCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.journal_grouping_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.journal_grouping_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
                for (option in JournalGrouping.entries) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = grouping == option,
                                role = Role.RadioButton,
                                onClick = {
                                    grouping = option
                                    // Written through the store's own guard, which refuses an unknown value rather
                                    // than storing one that would read back as the default.
                                    store.setJournalGrouping(option.wireValue)
                                },
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = grouping == option, onClick = null)
                        Text(
                            stringResource(
                                when (option) {
                                    JournalGrouping.BY_SESSION -> R.string.journal_grouping_by_session
                                    JournalGrouping.BY_DAY -> R.string.journal_grouping_by_day
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        if (TimelineOptions.Row.ZOOM in rows) {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.journal_timeline_zoom),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.journal_timeline_zoom_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                    // The ladder as radio rows rather than a slider: the presets are the ladder, and a slider would
                    // let a user land between them and then snap on the next pinch, which reads as the setting
                    // undoing itself.
                    for (preset in TimelineZoom.PRESETS) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = preset == zoom,
                                    onClick = {
                                        zoom = preset
                                        store.setTimelineZoom(preset)
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(selected = preset == zoom, onClick = null)
                            Text(TimelineZoom.label(preset), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        if (TimelineOptions.Row.COMPACT_ENTRIES in rows) {
            ToggleCard(
                title = stringResource(R.string.journal_timeline_compact),
                detail = stringResource(R.string.journal_timeline_compact_detail),
                checked = bubbleStyle == TimelineBubbleStyleName.COMPACT,
                onCheckedChange = { compact ->
                    bubbleStyle = if (compact) TimelineBubbleStyleName.COMPACT else TimelineBubbleStyleName.FULL
                    store.setTimelineBubbleStyle(bubbleStyle.wireValue)
                },
            )
        }

    }
}

/** One switch row: a title, a sentence explaining it, and the switch. */
@Composable
private fun ToggleCard(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = PiruTheme.colors.secondaryLabel)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
