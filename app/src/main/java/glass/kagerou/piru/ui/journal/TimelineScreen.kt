package glass.kagerou.piru.ui.journal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.theme.PiruTheme
import glass.kagerou.piru.ui.theme.toComposeColor
import glass.kagerou.piru.ui.labels.appLocale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The log as one continuous list, oldest day first.
 *
 * ## What this fills in
 * `PushRoute.Timeline` carried a key and nothing else — no destination, no producer — and its own
 * declaration says where it was meant to be pushed from: "the continuous timeline, pushed from the
 * journal's day header". The journal draws one day; a session draws one session; nothing drew the log.
 * So a dose taken three weeks ago was reachable only by finding the right day in the day browser, and
 * a pattern that spans weeks — the thing a log is for — could not be seen at all.
 *
 * ## Days are headers, not rows
 * A `LazyColumn` over every dose with a sticky day header would be the obvious build, but the day
 * boundaries are what a reader scans by, and they are cheap: the list is already sorted, so a day
 * header is emitted when the date changes. Sticky headers would need the experimental API for no gain
 * here, because the header repeats on every day and the reader is scrolling *through* days rather than
 * parking on one.
 *
 * ## Newest first
 * The opposite order to upstream's chart, deliberately. A log is read by looking at the recent end,
 * and the journal's own day view is today-first; a continuous list that started at the oldest dose
 * would open on a date the user has long stopped thinking about.
 */
@Composable
fun TimelineScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val zone = remember { ZoneId.systemDefault() }
    val dateLocale = appLocale()

    var entries by remember { mutableStateOf<List<DoseEntryEntity>>(emptyList()) }
    var tints by remember { mutableStateOf<Map<String, P3Color>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }

    // Reloads on the invalidation counter for the same reason the journal does: without the
    // observation layer, a screen that loaded once would keep showing a dose the user just deleted.
    LaunchedEffect(navigator.dataVersion) {
        loading = true
        val all = runCatching { app.database.doseEntryDao().all() }.getOrDefault(emptyList())
        entries = all
        tints = runCatching { app.palette().tintsFor(all.map { it.substance }.toSet()) }
            .getOrDefault(emptyMap())
        loading = false
    }

    // Grouped in memory because the list is already in hand: a second query per day would be N queries
    // for data the first one returned.
    val byDay = remember(entries) {
        entries
            .groupBy { it.timestamp.toInstant().atZone(zone).toLocalDate() }
            .toSortedMap(compareByDescending { it })
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(
                modifier = Modifier.padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.journal_timeline_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    // The count is the answer, including the empty one: "0 doses" is a fact about the
                    // log, and a blank screen would be indistinguishable from a read that failed.
                    if (loading) {
                        stringResource(R.string.journal_timeline_loading)
                    } else {
                        stringResource(R.string.journal_timeline_count, entries.size)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        for ((day, doses) in byDay) {
            item(key = "day-$day") {
                Text(
                    dayHeading(day, zone, dateLocale, context.getString(R.string.datefmt_day_month)),
                    style = MaterialTheme.typography.labelLarge,
                    color = PiruTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(doses, key = { it.rowId }) { entry ->
                val tint = tints[entry.substance.lowercase()] ?: P3Color.NEUTRAL
                PiruCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        navigator.push(
                            PushRoute.Entry(entry.timestamp.time, entry.id.toString()),
                        )
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(color = tint.toComposeColor(), shape = CircleShape),
                        )
                        Text(
                            // The time, because within a day the order is what the reader is
                            // following; the date is the heading above.
                            entry.timestamp.toInstant().atZone(zone)
                                .format(DateTimeFormatter.ofPattern("HH:mm")),
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                        Text(
                            entry.substance,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (entry.isUnknownDose) "?" else "${entry.amount} ${entry.unit}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

/** A day's heading, formatted with the app's own language rather than the device's. */
private fun dayHeading(
    day: LocalDate,
    zone: ZoneId,
    locale: java.util.Locale,
    pattern: String,
): String = day.atStartOfDay(zone).toInstant()
    .let { Instant.from(it) }
    .atZone(zone)
    .format(DateTimeFormatter.ofPattern(pattern, locale))
