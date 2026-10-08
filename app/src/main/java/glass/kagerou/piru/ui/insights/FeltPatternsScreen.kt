package glass.kagerou.piru.ui.insights

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.data.entity.SessionEntity
import glass.kagerou.piru.data.entity.SessionNoteEntity
import glass.kagerou.piru.model.doseFormatted
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import glass.kagerou.piru.data.AppSettingsStore

/**
 * What the "did it work?" answers line up with.
 *
 * Ported from `Views/Insights/FeltPatternsView.swift` (168 lines) and
 * `FeltPatternsModel.swift` (270 lines).
 *
 * ## The note stays a record; the interpretation lives here
 * A session note shows the word that was entered and nothing derived from it.
 * Everything interpretive is on this screen, which is what Insights already is:
 * Adherence, Usage and Patterns all read the log and draw conclusions from it
 * while the log itself stays a record.
 *
 * ## This is correlation from one person with no control group
 * A pattern here is a thing to notice, never a reason. The screen says so once,
 * plainly, and the copy never turns an observation into an instruction: *"your
 * doses before 8:10 read 'about right' more often than your later ones"*, never
 * *"take it before 8"*.
 *
 * ## Two floors, both load-bearing
 * A side reports only with at least ``MINIMUM_PER_SIDE`` rated days on it —
 * below that, one bad week writes the headline — and a split is *notable* only
 * when the two sides differ by at least 0.20. Everything else is listed as "no
 * difference" rather than dressed up as one.
 *
 * ## The cut points are the user's own
 * The time-of-day and amount splits cut at the **median** of that person's rated
 * days, not at 8:00 or at some milligram figure someone picked. Weekday and
 * caffeine are the two fixed dichotomies, because weekend and "coffee within the
 * hour" are already binary facts.
 */
@Composable
fun FeltPatternsScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication

    var splits by remember { mutableStateOf<List<FeltPatterns.Split>>(emptyList()) }
    var ratedDayCount by remember { mutableStateOf(0) }
    var substanceCount by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    val calendar = remember {
        // The user's own day boundary. This passed `null`, which meant these screens
        // computed every session day from 4 AM whatever the setting said — so even a
        // written preference would not have reached them.
        InsightsCalendar.ambient(AppSettingsStore(context).storedDayBoundaryHour())
    }

    LaunchedEffect(navigator.dataVersion) {
        loaded = false
        failure = null
        runCatching {
            val catalog = app.catalog()
            val sessions = app.database.sessionDao().all()
            val doses = app.database.doseEntryDao().all()
            val notes = app.database.sessionNoteDao().all()
            val days = FeltPatterns.ratedDays(sessions, doses, notes, catalog::lookupName, calendar)
            ratedDayCount = days.size
            substanceCount = days.map { it.substance }.toSet().size
            splits = FeltPatterns.splits(days, calendar).sortedByDescending { it.gap }
        }.onFailure { failure = it::class.simpleName + ": " + it.message }
        loaded = true
    }

    val notable = splits.filter { it.gap >= FeltPatterns.NOTABLE_GAP }
    val flat = splits.filter { it.gap < FeltPatterns.NOTABLE_GAP }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.toolsb_felt_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.toolsb_felt_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        failure?.let { item { InsightsEmptyPanel(stringResource(R.string.toolsb_felt_error_log_unreadable), it) } }

        if (failure == null && loaded && ratedDayCount == 0) {
            item {
                InsightsEmptyPanel(
                    stringResource(R.string.toolsb_felt_empty_title),
                    stringResource(R.string.toolsb_felt_empty_detail),
                )
            }
        }

        if (ratedDayCount > 0) {
            item { HeaderRow() }

            item {
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabelledCount(stringResource(R.string.toolsb_felt_days_rated), ratedDayCount)
                        LabelledCount(stringResource(R.string.toolsb_substances), substanceCount)
                    }
                }
            }

            if (notable.isEmpty() && flat.isEmpty()) {
                item {
                    InsightsEmptyPanel(
                        stringResource(R.string.toolsb_felt_not_enough_title),
                        stringResource(
                            R.string.toolsb_felt_not_enough_detail,
                            FeltPatterns.MINIMUM_PER_SIDE,
                        ),
                    )
                }
            }

            for (split in notable) {
                item(key = "notable-${split.id}") { SplitSection(split, isNotable = true) }
            }
            for (split in flat) {
                item(key = "flat-${split.id}") { SplitSection(split, isNotable = false) }
            }

            item {
                Text(
                    stringResource(R.string.toolsb_felt_footer_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = PiruTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

/**
 * The method badge.
 *
 * Tagged before the first number is read: a comparison of one person's own days
 * is not a trial, and saying so up front is cheaper than saying it afterwards.
 */
@Composable
private fun HeaderRow() {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.toolsb_felt_header_title), style = MaterialTheme.typography.labelLarge)
                Text(
                    stringResource(R.string.toolsb_felt_header_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
            Text(
                stringResource(R.string.toolsb_felt_badge_experimental),
                style = MaterialTheme.typography.labelSmall,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(PiruTheme.colors.secondaryLabel.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun LabelledCount(label: String, value: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value.toString(), style = MaterialTheme.typography.bodyMedium)
    }
}

// MARK: - One split

/** Two sides of one variable, each as a count and a bar. */
@Composable
private fun SplitSection(split: FeltPatterns.Split, isNotable: Boolean) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(split.variable.title),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    split.substance,
                    style = MaterialTheme.typography.labelSmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
            SplitSideRow(split.lowLabel, split.lowLabelArg, split.low, isNotable)
            SplitSideRow(split.highLabel, split.highLabelArg, split.high, isNotable)
            if (!isNotable) {
                Text(
                    stringResource(R.string.toolsb_felt_both_groups_similar),
                    style = MaterialTheme.typography.labelSmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
    }
}

@Composable
private fun SplitSideRow(
    @StringRes label: Int,
    labelArg: String?,
    tally: FeltPatterns.Tally,
    isNotable: Boolean,
) {
    val track = PiruTheme.colors.accent.copy(alpha = 0.18f)
    val fill = if (isNotable) PiruTheme.colors.accent else PiruTheme.colors.secondaryLabel
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(splitLabel(label, labelArg), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.toolsb_felt_tally_of_days, tally.asExpected, tally.days),
                style = MaterialTheme.typography.labelSmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(6.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(color = track, cornerRadius = CornerRadius(size.height / 2))
                drawRoundRect(
                    color = fill,
                    size = Size(maxOf(2f, size.width * tally.fraction.toFloat()), size.height),
                    cornerRadius = CornerRadius(size.height / 2),
                )
            }
        }
    }
}

/**
 * A split's side label with its cut point substituted, where the split has one.
 *
 * The cut is a clock time or an amount, so it is data the model carries rather
 * than copy: [FeltPatterns.Split] holds the format and the value apart, and the
 * two are joined here, in composition.
 */
@Composable
private fun splitLabel(@StringRes label: Int, labelArg: String?): String =
    if (labelArg == null) stringResource(label) else stringResource(label, labelArg)

// MARK: - The model

/**
 * The joins and the splits, ported from `FeltPatternsModel`.
 *
 * Pure over explicit inputs — sessions, doses, notes, a lookup and a calendar —
 * so the arithmetic is testable without a database and the screen keeps only the
 * reading of it.
 */
internal object FeltPatterns {

    /** A side reports only with this many rated days on it. */
    const val MINIMUM_PER_SIDE = 5

    /** A gap smaller than this is two sides reading the same. */
    const val NOTABLE_GAP = 0.20

    /** What a split is cut on. One variable at a time. */
    enum class Variable(@StringRes val title: Int) {
        DOSE_HOUR(R.string.toolsb_felt_variable_dose_hour),
        AMOUNT(R.string.toolsb_felt_variable_amount),
        WEEKDAY(R.string.toolsb_felt_variable_weekday),
        CAFFEINE(R.string.toolsb_felt_variable_caffeine),
    }

    /** One day's answer for one substance: the mean of that day's ratings. */
    data class RatedDay(
        val day: Instant,
        val substance: String,
        val doseHour: Double,
        val amount: Double,
        val unit: String,
        val isWeekend: Boolean,
        val hadCaffeineBefore: Boolean,
        /** True when the day's mean rating is "about right" or more. */
        val wasAsExpected: Boolean,
    )

    data class Tally(val days: Int, val asExpected: Int) {
        val fraction: Double get() = if (days == 0) 0.0 else asExpected.toDouble() / days
    }

    data class Split(
        val substance: String,
        val variable: Variable,
        /** The side's label, and the cut point substituted into it where it takes one. */
        @StringRes val lowLabel: Int,
        val lowLabelArg: String?,
        @StringRes val highLabel: Int,
        val highLabelArg: String?,
        val low: Tally,
        val high: Tally,
    ) {
        val id: String get() = "$substance|${variable.name}"

        /** How far apart the two sides read, in fraction-of-days. */
        val gap: Double get() = kotlin.math.abs(low.fraction - high.fraction)
    }

    /**
     * One row per (day, substance) that carries a rating.
     *
     * A note is attributed to the dose in its session that most recently
     * *preceded* it, which is the dose the note is a rating of. A note with no
     * dose before it in its session is a rating of nothing and is skipped, as is
     * a dose whose substance the catalog does not carry — an unresolved name has
     * no identity to compare across days.
     */
    fun ratedDays(
        sessions: List<SessionEntity>,
        doses: List<DoseEntryEntity>,
        notes: List<SessionNoteEntity>,
        lookupName: (String) -> String?,
        calendar: InsightsCalendar,
    ): List<RatedDay> {
        val dosesBySession = doses.filter { it.sessionId != null }.groupBy { it.sessionId }
        val notesBySession = notes.filter { it.sessionId != null }.groupBy { it.sessionId }

        val caffeineTimes = doses
            .filter { lookupName(it.substance)?.lowercase() == "caffeine" }
            .map { it.timestamp.toInstant() }
            .sorted()

        val buckets = LinkedHashMap<String, Bucket>()
        for (session in sessions) {
            val sessionDoses = dosesBySession[session.id].orEmpty().sortedBy { it.timestamp.time }
            if (sessionDoses.isEmpty()) continue
            for (note in notesBySession[session.id].orEmpty().sortedBy { it.timestamp.time }) {
                val rating = note.worked ?: continue
                // The most recent dose at or before the note.
                val dose = sessionDoses.lastOrNull { it.timestamp.time <= note.timestamp.time } ?: continue
                val substance = lookupName(dose.substance) ?: continue
                val day = calendar.startOfDay(dose.timestamp.toInstant())
                val key = "${day.toEpochMilli()}|${substance.lowercase()}"
                val bucket = buckets.getOrPut(key) { Bucket(day, dose, mutableListOf()) }
                bucket.ratings += rating
            }
        }

        return buckets.values.mapNotNull { bucket ->
            val substance = lookupName(bucket.dose.substance) ?: return@mapNotNull null
            val mean = bucket.ratings.sum().toDouble() / bucket.ratings.size
            val timestamp = bucket.dose.timestamp.toInstant()
            val hour = calendar.hourOfDay(timestamp).toDouble() + calendar.minuteOfHour(timestamp) / 60.0
            val weekday = calendar.weekday(timestamp)
            val hadCaffeine = caffeineTimes.any { caffeine ->
                val delta = timestamp.toEpochMilli() - caffeine.toEpochMilli()
                // Only caffeine taken *before* the dose, within the hour.
                delta in 0..3_600_000L
            }
            RatedDay(
                day = bucket.day,
                substance = substance,
                doseHour = hour,
                amount = bucket.dose.amount,
                unit = bucket.dose.unit,
                isWeekend = weekday == 1 || weekday == 7,
                hadCaffeineBefore = hadCaffeine,
                // The threshold is exactly zero: "about right" and "more than
                // usual" both count, "less than usual" does not.
                wasAsExpected = mean >= 0,
            )
        }
    }

    private class Bucket(val day: Instant, val dose: DoseEntryEntity, val ratings: MutableList<Int>)

    /** The four splits per substance, in the fixed order upstream tries them. */
    fun splits(days: List<RatedDay>, calendar: InsightsCalendar): List<Split> {
        val out = ArrayList<Split>()
        for ((substance, rows) in days.groupBy { it.substance }) {
            hourSplit(substance, rows, calendar)?.let { out += it }
            amountSplit(substance, rows)?.let { out += it }
            weekdaySplit(substance, rows)?.let { out += it }
            caffeineSplit(substance, rows)?.let { out += it }
        }
        return out
    }

    /**
     * Tally two groups, or null when either is under the floor.
     *
     * The floor is the whole gate: without it a substance rated twice produces a
     * headline from two days, and the screen reads as though it knew something.
     */
    private fun tallied(
        substance: String,
        variable: Variable,
        @StringRes lowLabel: Int,
        @StringRes highLabel: Int,
        low: List<RatedDay>,
        high: List<RatedDay>,
        lowLabelArg: String? = null,
        highLabelArg: String? = null,
    ): Split? {
        if (low.size < MINIMUM_PER_SIDE || high.size < MINIMUM_PER_SIDE) return null
        fun tally(rows: List<RatedDay>) = Tally(rows.size, rows.count { it.wasAsExpected })
        return Split(
            substance = substance,
            variable = variable,
            lowLabel = lowLabel,
            lowLabelArg = lowLabelArg,
            highLabel = highLabel,
            highLabelArg = highLabelArg,
            low = tally(low),
            high = tally(high),
        )
    }

    /**
     * The median, so the cut is where this person's own days actually sit rather
     * than at an hour or a milligram someone else picked.
     */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
    }

    private fun hourSplit(substance: String, rows: List<RatedDay>, calendar: InsightsCalendar): Split? {
        val cut = median(rows.map { it.doseHour }) ?: return null
        val clock = hourLabel(cut)
        return tallied(
            substance = substance,
            variable = Variable.DOSE_HOUR,
            lowLabel = R.string.toolsb_felt_split_before_clock,
            highLabel = R.string.toolsb_felt_split_clock_or_later,
            low = rows.filter { it.doseHour < cut },
            high = rows.filter { it.doseHour >= cut },
            lowLabelArg = clock,
            highLabelArg = clock,
        )
    }

    /**
     * The cut as a clock time, in the reader's own format.
     *
     * A display preference, so this follows the ambient locale rather than
     * `Locale.ROOT` — only the numbers this port prints are pinned to ROOT.
     */
    fun hourLabel(hour: Double): String {
        val whole = hour.toInt()
        val minutes = ((hour - whole) * 60).toInt().coerceIn(0, 59)
        val time = java.time.LocalTime.of(whole.coerceIn(0, 23), minutes)
        return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).format(time)
    }

    private fun amountSplit(substance: String, rows: List<RatedDay>): Split? {
        // Mixed units would compare numbers that are not the same quantity, so a
        // substance logged by the milligram and by the millilitre produces no
        // amount split at all rather than a wrong one.
        val units = rows.map { it.unit }.toSet()
        if (units.size != 1) return null
        val unit = units.first()
        val cut = median(rows.map { it.amount }) ?: return null
        if (cut <= 0) return null
        val cutText = "${doseFormatted(cut)} $unit"
        return tallied(
            substance = substance,
            variable = Variable.AMOUNT,
            lowLabel = R.string.toolsb_felt_split_under_amount,
            highLabel = R.string.toolsb_felt_split_amount_or_more,
            low = rows.filter { it.amount < cut },
            high = rows.filter { it.amount >= cut },
            lowLabelArg = cutText,
            highLabelArg = cutText,
        )
    }

    private fun weekdaySplit(substance: String, rows: List<RatedDay>): Split? = tallied(
        substance = substance,
        variable = Variable.WEEKDAY,
        lowLabel = R.string.toolsb_felt_split_weekdays,
        highLabel = R.string.toolsb_felt_split_weekends,
        low = rows.filter { !it.isWeekend },
        high = rows.filter { it.isWeekend },
    )

    private fun caffeineSplit(substance: String, rows: List<RatedDay>): Split? = tallied(
        substance = substance,
        variable = Variable.CAFFEINE,
        lowLabel = R.string.toolsb_felt_split_no_caffeine,
        highLabel = R.string.toolsb_felt_split_caffeine_within_hour,
        low = rows.filter { !it.hadCaffeineBefore },
        high = rows.filter { it.hadCaffeineBefore },
    )
}

/** The catalog lookup the join needs, narrowed to the one thing it asks. */
private fun glass.kagerou.piru.engine.SubstanceCatalog.lookupName(name: String): String? = lookup(name)?.name
