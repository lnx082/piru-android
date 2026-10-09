package glass.kagerou.piru.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Your History": what this substance's own log looks like, folded open.
 *
 * Ported from `HistorySection`. The card answers a question the reference tables cannot: not what a substance does in
 * general but what **this user's** doses of it have been — how many, over what span, in what range, and most often
 * what.
 *
 * ## Why the fold is a button rather than a `DisclosureGroup`
 * Upstream's note: a `DisclosureGroup` draws its chevron as a separate, unnamed 10 pt button that VoiceOver and Voice
 * Control cannot name. So the whole header row is the control, which is what makes it announceable.
 *
 * ## Three things it declines to do
 * - **No rows, no card.** A substance the user has never logged gets nothing rather than a summary of zero doses.
 * - **Ten rows, then a count.** The fold shows `prefix(10)` and offers "Show all N entries" — a substance logged four
 *   hundred times would otherwise put four hundred rows between the reader and the rest of the page.
 * - **One unit, or none.** The figures are printed with the **first row's** unit, because a range spanning `mg` and `g`
 *   is not a range. A history that mixes units is summarised by its first row's, which is the same rule the body-load
 *   card's groups follow and for the same reason.
 */
@Composable
internal fun HistoryCard(
    substanceName: String,
    defaultUnit: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    var entries by remember(substanceName) { mutableStateOf<List<DoseEntryEntity>>(emptyList()) }
    var loaded by remember(substanceName) { mutableStateOf(false) }
    var expanded by remember(substanceName) { mutableStateOf(false) }
    var showAll by remember(substanceName) { mutableStateOf(false) }

    LaunchedEffect(substanceName) {
        val catalog = runCatching { withContext(Dispatchers.IO) { app.catalog() } }.getOrNull()
        // Matched on the catalogue's **canonical** name, so a dose logged under an alias or a brand still counts as
        // this substance's history. The inventory lookup beside this card resolves the same way.
        val canonical = catalog?.lookup(substanceName)?.name ?: substanceName
        entries = runCatching {
            withContext(Dispatchers.IO) {
                app.database.doseEntryDao().all()
                    .filter { (catalog?.lookup(it.substance)?.name ?: it.substance) == canonical }
                    // Newest first, which is what the header's span and the row list both assume.
                    .sortedByDescending { it.timestamp.time }
            }
        }.getOrDefault(emptyList())
        loaded = true
    }

    if (!loaded || entries.isEmpty()) return

    val unit = entries.first().unit.ifBlank { defaultUnit }
    val stats = DoseHistoryStats.rebuild(entries.map { it.amount })
    val zone = remember { ZoneId.systemDefault() }
    // The two dates the header prints, formatted here rather than in the layout.
    val earliest = entries.last().timestamp.toInstant()
    val latest = entries.first().timestamp.toInstant()

    PiruCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The whole header is the control: see this card's note on why it is not a DisclosureGroup.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        stringResource(R.string.history_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Text(
                    if (expanded) "▾" else "▸",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }

            Text(
                stringResource(R.string.history_count, entries.size),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                historySpan(earliest, latest, zone),
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
            if (stats != null) {
                Text(
                    if (stats.minDose == stats.maxDose) {
                        stringResource(R.string.history_single_dose, formatDose(stats.minDose), unit)
                    } else {
                        stringResource(
                            R.string.history_dose_range,
                            formatDose(stats.minDose),
                            formatDose(stats.maxDose),
                            unit,
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.history_most_common, formatDose(stats.mostCommon), unit),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }

            if (expanded) {
                val shown = if (showAll) entries else entries.take(HISTORY_PREVIEW_ROWS)
                for (entry in shown) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                "${formatDose(entry.amount)} ${entry.unit}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                entry.route.wireValue,
                                style = MaterialTheme.typography.labelSmall,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                        }
                        Text(
                            ROW_DATE.format(entry.timestamp.toInstant().atZone(zone)),
                            style = MaterialTheme.typography.labelSmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                }
                if (entries.size > HISTORY_PREVIEW_ROWS && !showAll) {
                    TextButton(onClick = { showAll = true }) {
                        Text(stringResource(R.string.history_show_all, entries.size))
                    }
                }
            }
        }
    }
}

/** How many rows the fold shows before it offers the rest. Upstream's ten. */
private const val HISTORY_PREVIEW_ROWS = 10

/** `12 Mar 2025, 21:40` — a row's own stamp, which needs a date and a time. */
private val ROW_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ROOT)

/**
 * `Mar 2025`, and `Jan 2024 – Mar 2025` when the history spans more than one month.
 *
 * **One formatter, not two.** I wrote a second one with `MMMM` for the single-month case on the reasoning that a full
 * month name is right when it stands alone — and the probe showed `MMMM yyyy` also renders `Mar` here, so the two were
 * always the same string and the second formatter was decoration. See [historySpan] for what that taught.
 */
private val MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ROOT)

/**
 * The header's span.
 *
 * A single month prints as one figure, because "March 2025 – March 2025" reads as a range and is not one. Upstream
 * compares at **month** granularity for the same reason.
 */
internal fun historySpan(earliest: Instant, latest: Instant, zone: ZoneId): String {
    val first = earliest.atZone(zone)
    val last = latest.atZone(zone)
    val sameMonth = first.year == last.year && first.month == last.month
    // Both branches use the same formatter, because `MMMM yyyy` renders `Mar` under this locale stack — so my
    // "full name when it stands alone" rule produced the abbreviation either way. What a reader actually reads was
    // settled by printing it (`HISTORYPROBE`), not by reasoning about what a pattern ought to print; the first version
    // of this function and its test both asserted the reasoning and both were wrong.
    return if (sameMonth) {
        MONTH_YEAR.format(first)
    } else {
        "${MONTH_YEAR.format(first)} – ${MONTH_YEAR.format(last)}"
    }
}

/**
 * A dose amount without a trailing `.0`.
 *
 * The same rule the body-load and metabolite amounts follow, and for the same reason: `100.0` reads as a measurement
 * taken to a tenth of a milligram. Kept local rather than shared because each of the three trims a differently shaped
 * number, and a shared helper would have to guess which.
 */
internal fun formatDose(value: Double): String = when {
    value == value.toLong().toDouble() -> value.toLong().toString()
    else -> String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
}
