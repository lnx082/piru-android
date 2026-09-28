package glass.kagerou.piru.ui.insights

import androidx.annotation.StringRes
import glass.kagerou.piru.R
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.SessionDay
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.model.BaseReleaseForm
import glass.kagerou.piru.model.DoseLevel
import glass.kagerou.piru.model.DoseRange
import glass.kagerou.piru.model.DoseUnit
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.model.SubstanceCategory
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.min

/**
 * The Usage screen's arithmetic, ported from `Views/Insights/Usage/UsageAnalytics.swift`
 * (1,200 lines) and the stage-one resolver in `UsageAnalyticsModel.swift`.
 *
 * ## Two day-boundary semantics, and they are not interchangeable
 * Everything here that buckets by **day** — the heatmap, the hour profile, the
 * co-use pairs, the regularity gaps, the trend buckets — goes through
 * [InsightsCalendar.sessionDayStart], which rolls the day at the user's
 * configured hour (4 AM by default) so a 02:00 dose belongs to the night it was
 * part of. Everything that asks **which weekday** a dose fell on reads the raw
 * timestamp's weekday, with no roll-back, because Monday 01:00 is Monday to
 * anyone reading a bar chart. Upstream draws the same line at the same call
 * sites (`UsageAnalytics.swift` §0.1 of the porting spec lists them line by
 * line), and collapsing the two would silently move doses between days.
 *
 * ## Indices, not enums, in the aggregates
 * Upstream carries `Int` indices because `SubstanceCategory` and friends are
 * main-actor-isolated under its default isolation and the aggregation runs
 * detached. Kotlin has no such constraint, but the indices are kept: they are
 * what the chart layers key on, and they keep the grouping maps cheap.
 *
 * ## `nil` is not zero
 * `commonDoses`, `commonTotal`, `commonAverage` and `doseIntensity` are all
 * nullable, and the distinction is load-bearing everywhere they appear: a dose
 * the app cannot express in common-dose units (no common band on the ladder, or
 * a unit that will not convert) is *left out* of the statistic and counted in
 * the section's own "N of M" footnote — never summed as 0, which would read as
 * "never taken" instead of "not measurable this way".
 */

// MARK: - The calendar

/**
 * The calendar the aggregations read, as explicit values rather than an ambient
 * default.
 *
 * [firstWeekday] is Foundation's numbering (`1` = Sunday … `7` = Saturday),
 * because that is what `Calendar.current.firstWeekday` returns on the iOS side
 * and the heatmap's row order has to mean the same thing on both. It comes from
 * the device's locale, which is a *display* preference and so deliberately not
 * `Locale.ROOT` — only number formatting takes ROOT in this port.
 */
internal data class InsightsCalendar(
    val zone: ZoneId,
    val firstWeekday: Int,
    val dayBoundaryHour: Int,
) {
    private fun localDay(instant: Instant): LocalDate = instant.atZone(zone).toLocalDate()

    /** Midnight, the boundary adherence and the Patterns stats use. */
    fun startOfDay(instant: Instant): Instant = localDay(instant).atStartOfDay(zone).toInstant()

    /** The configurable session day, which the Usage day-buckets and heatmap use. */
    fun sessionDayStart(instant: Instant): Instant =
        SessionDay.sessionDayStart(instant, zone, dayBoundaryHour)

    /** Foundation weekday of the raw instant: `1` = Sunday … `7` = Saturday. */
    fun weekday(instant: Instant): Int = foundationWeekday(localDay(instant))

    fun hourOfDay(instant: Instant): Int = instant.atZone(zone).hour

    fun minuteOfHour(instant: Instant): Int = instant.atZone(zone).minute

    /** The instant the week containing [instant] starts, per [firstWeekday]. */
    fun weekStart(instant: Instant): Instant {
        val day = localDay(instant)
        val offset = ((foundationWeekday(day) - firstWeekday) + 7) % 7
        return day.minusDays(offset.toLong()).atStartOfDay(zone).toInstant()
    }

    /** The month [instant] falls in, as a year-month. */
    fun yearMonth(instant: Instant): YearMonth = YearMonth.from(localDay(instant))

    /** Midnight on the first of [month], in [zone]. */
    fun monthStart(month: YearMonth): Instant =
        month.atDay(1).atStartOfDay(zone).toInstant()

    fun plusDays(instant: Instant, days: Long): Instant =
        localDay(instant).plusDays(days).atStartOfDay(zone).toInstant()

    fun plusMonths(instant: Instant, months: Long): Instant =
        yearMonth(instant).plusMonths(months).atDay(1).atStartOfDay(zone).toInstant()

    fun plusWeeks(instant: Instant, weeks: Long): Instant = plusDays(instant, weeks * 7)

    /** `1` = Sunday … `7` = Saturday, from a [LocalDate]. */
    fun weekdayOf(date: LocalDate): Int = foundationWeekday(date)

    fun dateOf(instant: Instant): LocalDate = localDay(instant)

    companion object {
        /**
         * The calendar from the ambient locale and zone.
         *
         * The stored day-boundary hour is read from the same preferences key
         * upstream uses, and validated by [SessionDay.boundaryHour] — so a missing
         * key is 4 AM and an out-of-range one falls back rather than being trusted.
         * The settings screen that writes this key has not landed in the port; the
         * read is here so that screen only has to write the one value.
         */
        fun ambient(storedBoundaryHour: Int?): InsightsCalendar = InsightsCalendar(
            zone = ZoneId.systemDefault(),
            firstWeekday = firstWeekdayOf(),
            dayBoundaryHour = SessionDay.boundaryHour(storedBoundaryHour),
        )

        private fun firstWeekdayOf(): Int {
            // `WeekFields.firstDayOfWeek` is ISO (Monday = 1); Foundation counts
            // Sunday as 1, so the two ladders are shifted by one.
            val iso = java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).firstDayOfWeek.value
            return iso % 7 + 1
        }
    }
}

/** Foundation's weekday numbering from a [LocalDate]; see [InsightsCalendar.weekday]. */
private fun foundationWeekday(day: LocalDate): Int = day.dayOfWeek.value % 7 + 1

// MARK: - Time range and metric

/**
 * The window the Usage screen aggregates over.
 *
 * Declaration order is upstream's `CaseIterable` order and is what the range
 * picker lists; Kotlin enums take `compareTo` from that order and it cannot be
 * overridden, so it must not be rearranged casually.
 */
internal enum class UsageTimeRange(
    /** Length of the window in days, or null for "All". */
    val days: Int?,
    val displayName: String,
    /**
     * The picker's label as a resource. [displayName] carries the same label in
     * its English spelling and stays because the Usage screen reads it; the
     * Patterns screen resolves this one. The two spell the same labels.
     */
    @StringRes val displayNameRes: Int,
    /** Whether the trend and dose-level buckets are a week wide (else a day). */
    val usesWeeklyBuckets: Boolean,
    /** Rolling window behind each trend point, in days. */
    val rollingWindowDays: Int,
    /** Whether a trend point reads as a per-week rate (7D reads as raw per-day buckets). */
    val trendPerWeek: Boolean,
) {
    SEVEN_DAYS(7, "7D", R.string.toolsb_analytics_range_7d, usesWeeklyBuckets = false, rollingWindowDays = 1, trendPerWeek = false),
    THIRTY_DAYS(30, "30D", R.string.toolsb_analytics_range_30d, usesWeeklyBuckets = false, rollingWindowDays = 7, trendPerWeek = true),
    NINETY_DAYS(90, "90D", R.string.toolsb_analytics_range_90d, usesWeeklyBuckets = true, rollingWindowDays = 28, trendPerWeek = true),
    ONE_YEAR(365, "1Y", R.string.toolsb_analytics_range_1y, usesWeeklyBuckets = true, rollingWindowDays = 28, trendPerWeek = true),
    ALL(null, "All", R.string.toolsb_analytics_range_all, usesWeeklyBuckets = true, rollingWindowDays = 28, trendPerWeek = true),
}

/** The two lenses the ranking and the trend chart can be read through. */
internal enum class UsageRankMetric { ENTRIES, COMMON_DOSES }

// MARK: - Inputs

/**
 * One logged dose reduced to plain values.
 *
 * [categoryIndex] and [routeIndex] are indices into [SubstanceCategory.entries]
 * and [RouteOfAdministration.entries]; [doseLevelIndex] is an index into the
 * `DoseLevel` ladder, or null when the dose could not be placed.
 */
internal data class UsageEntrySnapshot(
    val substanceIndex: Int,
    val categoryIndex: Int,
    val routeIndex: Int,
    val doseLevelIndex: Int?,
    val commonDoses: Double?,
    val timestamp: Instant,
)

/** A distinct substance: its logged name, its display name, and its class. */
internal data class UsageSubstanceRef(
    val name: String,
    val displayName: String,
    val categoryIndex: Int,
)

// MARK: - Outputs

internal data class UsageOverview(
    val entryCount: Int,
    val previousEntryCount: Int?,
    val percentChange: Double?,
    /** Seven equal buckets across the window, oldest first. */
    val sparkline: List<Int>,
    val uniqueSubstances: Int,
    val newSubstances: Int,
    val averagePerDay: Double,
    /** Weekday (`1` = Sunday) carrying the most entries, or null when there are none. */
    val busiestWeekday: Int?,
    val doseResolvedCount: Int,
    val commonOrAboveCount: Int,
    val heavyCount: Int,
) {
    /** Share of *resolvable* entries at Common or above. Null when nothing resolved. */
    val doseIntensity: Double?
        get() = if (doseResolvedCount > 0) commonOrAboveCount.toDouble() / doseResolvedCount else null
}

internal data class UsageHeatmapCell(
    val date: Instant,
    val total: Int,
    val commonTotal: Double,
    val byCategory: Map<Int, Int>,
    val byCategoryCommon: Map<Int, Double>,
    /** False for the leading and trailing cells that pad the first and last week columns. */
    val inRange: Boolean,
)

internal data class UsageHeatmap(
    val weekStarts: List<Instant>,
    /** The seven weekday numbers in the user's display order. */
    val rowWeekdays: List<Int>,
    /** `weekStarts.size * 7` cells in column-major order. */
    val cells: List<UsageHeatmapCell>,
    val maxCount: Int,
    val maxCommon: Double,
    val cellByDate: Map<Instant, UsageHeatmapCell>,
    /** The most recent day inside the range; later days are the future and stay blank. */
    val lastInRange: Instant,
)

/** Twenty-four hour-of-day bins, overall and split by category. */
internal data class UsageHourBins(
    val total: List<Int>,
    val byCategory: Map<Int, List<Int>>,
    val totalCommon: List<Double>,
    val byCategoryCommon: Map<Int, List<Double>>,
) {
    fun bins(category: Int?): List<Int> =
        if (category == null) total else byCategory[category] ?: List(HOURS) { 0 }

    fun commonBins(category: Int?): List<Double> =
        if (category == null) totalCommon else byCategoryCommon[category] ?: List(HOURS) { 0.0 }

    companion object {
        const val HOURS = 24
    }
}

internal data class UsageHourProfile(
    val all: UsageHourBins,
    /** Session-day start to that day's bins, for the tap-a-cell drill-down. */
    val byDay: Map<Instant, UsageHourBins>,
)

internal data class UsageTrendPoint(val date: Instant, val value: Double, val commonValue: Double)

internal data class UsageTrendSeries(
    val substanceIndex: Int,
    val points: List<UsageTrendPoint>,
    val hasCommonDoses: Boolean,
)

internal data class UsageDoseLevelBucket(val date: Instant, val counts: Map<Int, Int>) {
    val total: Int get() = counts.values.sum()
}

internal data class UsageDoseLevelBreakdown(
    val overall: List<UsageDoseLevelBucket>,
    val bySubstance: Map<Int, List<UsageDoseLevelBucket>>,
    val selectableSubstances: List<Int>,
    val resolvedEntries: Int,
    val totalEntries: Int,
) {
    val coverage: Double
        get() = if (totalEntries > 0) resolvedEntries.toDouble() / totalEntries else 0.0

    /**
     * Below 30% coverage the section demotes itself to a collapsed disclosure
     * rather than presenting a chart built from a minority of the data.
     */
    val isLowCoverage: Boolean get() = coverage < 0.3
}

internal data class UsageRouteRow(
    val substanceIndex: Int,
    val total: Int,
    /** Null when none of this substance's entries carried a common-dose value. */
    val commonTotal: Double?,
    val byRoute: List<RouteSlice>,
) {
    data class RouteSlice(val routeIndex: Int, val count: Int, val common: Double)
}

internal data class UsageRouteBreakdown(
    val rows: List<UsageRouteRow>,
    val distinctRoutes: List<Int>,
) {
    /** Whether route colour adds anything; a one-route history draws every bar the same colour. */
    val routesAreMeaningful: Boolean get() = distinctRoutes.size >= 2

    val commonDoseSubstances: Int get() = rows.count { it.commonTotal != null }
}

internal data class UsageCoUsePair(
    val firstIndex: Int,
    val secondIndex: Int,
    val days: Int,
    val unionDays: Int,
) {
    val overlap: Double get() = if (unionDays > 0) days.toDouble() / unionDays else 0.0
}

internal data class UsageRegularity(
    val substanceIndex: Int,
    val entryCount: Int,
    val meanIntervalDays: Double,
    val coefficientOfVariation: Double,
) {
    /** Bar fill, `0…1`. */
    val fill: Double get() = 1 - min(coefficientOfVariation, 1.5) / 1.5

    val tier: UsageRegularityTier get() = UsageRegularityTier.of(coefficientOfVariation)
}

internal enum class UsageRegularityTier(
    val displayName: String,
    /** The tier's label as a resource; see [UsageTimeRange.displayNameRes] for why both exist. */
    @StringRes val displayNameRes: Int,
) {
    VERY_REGULAR("Very regular", R.string.toolsb_analytics_regularity_very_regular),
    SOMEWHAT_REGULAR("Somewhat regular", R.string.toolsb_analytics_regularity_somewhat_regular),
    IRREGULAR("Irregular", R.string.toolsb_analytics_regularity_irregular),
    SPORADIC("Sporadic", R.string.toolsb_analytics_regularity_sporadic),
    ;

    companion object {
        fun of(coefficientOfVariation: Double): UsageRegularityTier = when {
            coefficientOfVariation < 0.3 -> VERY_REGULAR
            coefficientOfVariation < 0.6 -> SOMEWHAT_REGULAR
            coefficientOfVariation < 1.0 -> IRREGULAR
            else -> SPORADIC
        }
    }
}

internal data class UsageWeekdayBucket(
    val weekday: Int,
    val total: Int,
    val commonTotal: Double?,
    val occurrences: Int,
) {
    val average: Double get() = if (occurrences > 0) total.toDouble() / occurrences else 0.0

    val commonAverage: Double?
        get() = commonTotal?.let { if (occurrences > 0) it / occurrences else null }
}

internal data class UsageCategoryCount(val categoryIndex: Int, val count: Int)

internal data class UsageAnalyticsResult(
    val range: UsageTimeRange,
    val substances: List<UsageSubstanceRef>,
    val entryCount: Int,
    val overview: UsageOverview,
    val heatmap: UsageHeatmap,
    val hours: UsageHourProfile,
    val trends: List<UsageTrendSeries>,
    val doseLevels: UsageDoseLevelBreakdown,
    val routes: UsageRouteBreakdown,
    val coUse: List<UsageCoUsePair>,
    val regularity: List<UsageRegularity>,
    val weekdays: List<UsageWeekdayBucket>,
    val categories: List<UsageCategoryCount>,
) {
    val isEmpty: Boolean get() = entryCount == 0
}

// MARK: - The aggregation

/**
 * Every derived value on the Usage screen, over explicit inputs.
 *
 * Each step is a pure function so the bucketing, the rolling windows, the
 * period-over-period delta, the dose-level resolution and the co-occurrence
 * pairing are all testable without a database or a view.
 */
internal object UsageAnalytics {

    /**
     * The index of common (and heavy) in the `DoseLevel` ladder — sub,
     * threshold, light, **common**, strong, heavy. Written as literals rather
     * than derived from `entries`, so a tier inserted upstream breaks the build
     * here instead of silently shifting every band in the dose-level chart.
     */
    const val COMMON_LEVEL_INDEX = 3
    const val HEAVY_LEVEL_INDEX = 5

    /** A pair must co-occur on at least this many days to be shown. */
    const val MINIMUM_CO_USE_DAYS = 2

    /** How many pairs to keep, even on "All". */
    const val MAXIMUM_CO_USE_PAIRS = 15

    /** A substance needs this many entries before a regularity stat is anything but noise. */
    const val MINIMUM_REGULARITY_ENTRIES = 5

    /** Trend lines drawn by default, before "Show all". */
    const val DEFAULT_TREND_SUBSTANCES = 5

    /** Hard cap on trend lines even with "Show all"; more is a tangle, not an insight. */
    const val MAXIMUM_TREND_SUBSTANCES = 10

    /** Substances listed in the route breakdown. */
    const val MAXIMUM_ROUTE_ROWS = 10

    data class Bounds(
        val start: Instant,
        val end: Instant,
        val lengthDays: Double,
        val hasPreviousPeriod: Boolean,
    )

    private const val SECONDS_PER_DAY = 86_400.0

    fun compute(
        entries: List<UsageEntrySnapshot>,
        substances: List<UsageSubstanceRef>,
        range: UsageTimeRange,
        calendar: InsightsCalendar,
        now: Instant,
    ): UsageAnalyticsResult {
        val sorted = entries.sortedBy { it.timestamp }
        val bounds = bounds(range, sorted, now)
        val inRange = sorted.filter { it.timestamp >= bounds.start && it.timestamp <= bounds.end }

        val dayIndex = groupByDay(inRange, calendar)
        val overview = overview(sorted, inRange, bounds, calendar)
        // The activity heatmap is a full-history contribution graph: it colours
        // every day the user ever logged, not just the selected range, so a 30-day
        // range still reveals a year of rhythm. Graying out real earlier data just
        // to match the picker hid history the user actually has.
        val historyBounds = Bounds(
            start = sorted.firstOrNull()?.timestamp ?: bounds.start,
            end = bounds.end,
            lengthDays = bounds.lengthDays,
            hasPreviousPeriod = false,
        )
        val heatmap = heatmap(groupByDay(sorted, calendar), historyBounds, calendar)
        val hours = hourProfile(inRange, calendar)
        val bucketStarts = bucketStarts(bounds, range.usesWeeklyBuckets, calendar)
        val ranking = substanceRanking(inRange)
        val trends = trends(
            entries = inRange,
            ranking = ranking,
            bucketStarts = bucketStarts,
            bucketDays = if (range.usesWeeklyBuckets) 7 else 1,
            windowDays = range.rollingWindowDays,
            rangeEnd = bounds.end,
            ratePerWeek = range.trendPerWeek,
        )
        val doseLevels = doseLevels(inRange, ranking, bucketStarts)
        val routes = routeBreakdown(inRange, ranking)
        val coUse = coUsePairs(dayIndex)
        val regularity = regularity(dayIndex, ranking)
        val weekdays = weekdayBreakdown(inRange, bounds, calendar)
        val categories = categoryRanking(inRange)

        return UsageAnalyticsResult(
            range = range,
            substances = substances,
            entryCount = inRange.size,
            overview = overview,
            heatmap = heatmap,
            hours = hours,
            trends = trends,
            doseLevels = doseLevels,
            routes = routes,
            coUse = coUse,
            regularity = regularity,
            weekdays = weekdays,
            categories = categories,
        )
    }

    // MARK: Windowing

    /** "All" spans the first entry to now; every other range is a fixed window ending now. */
    fun bounds(range: UsageTimeRange, entries: List<UsageEntrySnapshot>, now: Instant): Bounds {
        val days = range.days
        if (days == null) {
            val start = entries.firstOrNull()?.timestamp ?: now
            val length = maxOf(1.0, (now.toEpochMilli() - start.toEpochMilli()) / 1000.0 / SECONDS_PER_DAY)
            return Bounds(start, now, length, hasPreviousPeriod = false)
        }
        val start = now.minusMillis((days * SECONDS_PER_DAY * 1000).toLong())
        return Bounds(start, now, days.toDouble(), hasPreviousPeriod = true)
    }

    /**
     * Session-day start to the entries logged that day.
     *
     * Session days, not calendar days: a 02:00 dose counts toward the night it
     * belongs to, which is the boundary the journal and the rest of the app use.
     */
    fun groupByDay(
        entries: List<UsageEntrySnapshot>,
        calendar: InsightsCalendar,
    ): Map<Instant, List<UsageEntrySnapshot>> {
        val out = LinkedHashMap<Instant, MutableList<UsageEntrySnapshot>>()
        for (entry in entries) {
            out.getOrPut(calendar.sessionDayStart(entry.timestamp)) { mutableListOf() }.add(entry)
        }
        return out
    }

    // MARK: §1 Overview

    fun overview(
        all: List<UsageEntrySnapshot>,
        inRange: List<UsageEntrySnapshot>,
        bounds: Bounds,
        calendar: InsightsCalendar,
    ): UsageOverview {
        val previousCount: Int? = if (bounds.hasPreviousPeriod) {
            val previousStart = bounds.start.minusMillis((bounds.lengthDays * SECONDS_PER_DAY * 1000).toLong())
            all.count { it.timestamp >= previousStart && it.timestamp < bounds.start }
        } else {
            null
        }

        val change: Double? = if (previousCount != null && previousCount > 0) {
            (inRange.size - previousCount).toDouble() / previousCount
        } else {
            null
        }

        val seen = inRange.mapTo(mutableSetOf()) { it.substanceIndex }
        // "New" = the first-ever dose falls inside the window. `all` is sorted, so
        // a substance's first sighting is its debut.
        val debuts = LinkedHashMap<Int, Instant>()
        for (entry in all) debuts.putIfAbsent(entry.substanceIndex, entry.timestamp)
        val newCount = seen.count { index -> debuts[index]?.let { it >= bounds.start } == true }

        val weekdayCounts = HashMap<Int, Int>()
        var resolved = 0
        var commonOrAbove = 0
        var heavy = 0
        for (entry in inRange) {
            weekdayCounts.merge(calendar.weekday(entry.timestamp), 1, Int::plus)
            val level = entry.doseLevelIndex ?: continue
            resolved++
            if (level >= COMMON_LEVEL_INDEX) commonOrAbove++
            if (level >= HEAVY_LEVEL_INDEX) heavy++
        }
        // Ties break toward the earlier weekday so the readout is stable across
        // recomputes rather than flipping with map order.
        val busiest = weekdayCounts.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key

        return UsageOverview(
            entryCount = inRange.size,
            previousEntryCount = previousCount,
            percentChange = change,
            sparkline = sparkline(inRange, bounds),
            uniqueSubstances = seen.size,
            newSubstances = newCount,
            averagePerDay = inRange.size / maxOf(1.0, bounds.lengthDays),
            busiestWeekday = busiest,
            doseResolvedCount = resolved,
            commonOrAboveCount = commonOrAbove,
            heavyCount = heavy,
        )
    }

    /** Seven equal-width buckets across the window, oldest first. */
    fun sparkline(
        entries: List<UsageEntrySnapshot>,
        bounds: Bounds,
        buckets: Int = 7,
    ): List<Int> {
        val counts = IntArray(buckets)
        val span = (bounds.end.toEpochMilli() - bounds.start.toEpochMilli()) / 1000.0
        if (span <= 0) {
            counts[buckets - 1] = entries.size
            return counts.toList()
        }
        for (entry in entries) {
            val offset = (entry.timestamp.toEpochMilli() - bounds.start.toEpochMilli()) / 1000.0 / span
            val index = min(buckets - 1, maxOf(0, (offset * buckets).toInt()))
            counts[index]++
        }
        return counts.toList()
    }

    // MARK: §2 Heatmap and hours

    fun heatmap(
        dayIndex: Map<Instant, List<UsageEntrySnapshot>>,
        bounds: Bounds,
        calendar: InsightsCalendar,
    ): UsageHeatmap {
        val rowWeekdays = (0 until 7).map { (calendar.firstWeekday - 1 + it) % 7 + 1 }
        val firstWeek = calendar.weekStart(bounds.start)
        val lastWeek = calendar.weekStart(bounds.end)

        val weekStarts = ArrayList<Instant>()
        var cursor = firstWeek
        // Guard against a pathological range producing an unbounded column list.
        while (cursor <= lastWeek && weekStarts.size < 600) {
            weekStarts += cursor
            cursor = calendar.plusWeeks(cursor, 1)
        }

        val startDay = calendar.sessionDayStart(bounds.start)
        val endDay = calendar.sessionDayStart(bounds.end)

        val cells = ArrayList<UsageHeatmapCell>(weekStarts.size * 7)
        var maxCount = 0
        var maxCommon = 0.0
        for (weekStart in weekStarts) {
            for (row in 0 until 7) {
                // Noon before the day-start conversion, so adding days never lands
                // on an hour a daylight-saving change skipped.
                val noon = calendar.plusDays(weekStart, row.toLong()).plusSeconds(12 * 3_600)
                val day = calendar.sessionDayStart(noon)
                val dayEntries = dayIndex[day].orEmpty()
                val byCategory = HashMap<Int, Int>()
                val byCategoryCommon = HashMap<Int, Double>()
                var commonTotal = 0.0
                for (entry in dayEntries) {
                    byCategory.merge(entry.categoryIndex, 1, Int::plus)
                    val common = entry.commonDoses
                    if (common != null) {
                        byCategoryCommon.merge(entry.categoryIndex, common, Double::plus)
                        commonTotal += common
                    }
                }
                maxCount = maxOf(maxCount, dayEntries.size)
                maxCommon = maxOf(maxCommon, commonTotal)
                cells += UsageHeatmapCell(
                    date = day,
                    total = dayEntries.size,
                    commonTotal = commonTotal,
                    byCategory = byCategory,
                    byCategoryCommon = byCategoryCommon,
                    inRange = day >= startDay && day <= endDay,
                )
            }
        }

        // Built once here rather than per layout pass in the grid.
        val byDate = LinkedHashMap<Instant, UsageHeatmapCell>()
        for (cell in cells) byDate.putIfAbsent(cell.date, cell)

        return UsageHeatmap(
            weekStarts = weekStarts,
            rowWeekdays = rowWeekdays,
            cells = cells,
            maxCount = maxCount,
            maxCommon = maxCommon,
            cellByDate = byDate,
            lastInRange = cells.filter { it.inRange }.maxOfOrNull { it.date } ?: Instant.MIN,
        )
    }

    /** 24-bin hour-of-day histograms: overall, per category, and per session day. */
    fun hourProfile(
        entries: List<UsageEntrySnapshot>,
        calendar: InsightsCalendar,
    ): UsageHourProfile {
        val total = IntArray(24)
        val byCategory = HashMap<Int, IntArray>()
        val perDayTotal = HashMap<Instant, IntArray>()
        val perDayCategory = HashMap<Instant, HashMap<Int, IntArray>>()
        val totalCommon = DoubleArray(24)
        val byCategoryCommon = HashMap<Int, DoubleArray>()
        val perDayCommon = HashMap<Instant, DoubleArray>()
        val perDayCategoryCommon = HashMap<Instant, HashMap<Int, DoubleArray>>()

        for (entry in entries) {
            val hour = calendar.hourOfDay(entry.timestamp)
            val day = calendar.sessionDayStart(entry.timestamp)
            total[hour]++
            byCategory.getOrPut(entry.categoryIndex) { IntArray(24) }[hour]++
            perDayTotal.getOrPut(day) { IntArray(24) }[hour]++
            perDayCategory.getOrPut(day) { HashMap() }
                .getOrPut(entry.categoryIndex) { IntArray(24) }[hour]++
            val common = entry.commonDoses ?: continue
            totalCommon[hour] += common
            byCategoryCommon.getOrPut(entry.categoryIndex) { DoubleArray(24) }[hour] += common
            perDayCommon.getOrPut(day) { DoubleArray(24) }[hour] += common
            perDayCategoryCommon.getOrPut(day) { HashMap() }
                .getOrPut(entry.categoryIndex) { DoubleArray(24) }[hour] += common
        }

        val byDay = HashMap<Instant, UsageHourBins>()
        for ((day, bins) in perDayTotal) {
            byDay[day] = UsageHourBins(
                total = bins.toList(),
                byCategory = (perDayCategory[day] ?: emptyMap()).mapValues { it.value.toList() },
                totalCommon = (perDayCommon[day] ?: DoubleArray(24)).toList(),
                byCategoryCommon = (perDayCategoryCommon[day] ?: emptyMap()).mapValues { it.value.toList() },
            )
        }

        return UsageHourProfile(
            all = UsageHourBins(
                total = total.toList(),
                byCategory = byCategory.mapValues { it.value.toList() },
                totalCommon = totalCommon.toList(),
                byCategoryCommon = byCategoryCommon.mapValues { it.value.toList() },
            ),
            byDay = byDay,
        )
    }

    // MARK: Bucketing

    /** The bucket start dates spanning the window, always including the final partial bucket. */
    fun bucketStarts(
        bounds: Bounds,
        weekly: Boolean,
        calendar: InsightsCalendar,
    ): List<Instant> {
        val first = if (weekly) calendar.weekStart(bounds.start) else calendar.sessionDayStart(bounds.start)
        val last = if (weekly) calendar.weekStart(bounds.end) else calendar.sessionDayStart(bounds.end)

        val result = ArrayList<Instant>()
        var cursor = first
        while (cursor <= last && result.size < 800) {
            result += cursor
            cursor = if (weekly) calendar.plusWeeks(cursor, 1) else calendar.plusDays(cursor, 1)
        }
        return result
    }

    /** The bucket a timestamp falls into: the last bucket whose start is `<=` it. */
    fun bucket(date: Instant, bucketStarts: List<Instant>): Instant? {
        if (bucketStarts.isEmpty()) return null
        if (date < bucketStarts.first()) return bucketStarts.first()
        var low = 0
        var high = bucketStarts.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (bucketStarts[mid] <= date) low = mid else high = mid - 1
        }
        return bucketStarts[low]
    }

    /** Substance indices ranked by entry count, descending; ties by index so the order is stable. */
    fun substanceRanking(entries: List<UsageEntrySnapshot>): List<Pair<Int, Int>> {
        val counts = HashMap<Int, Int>()
        for (entry in entries) counts.merge(entry.substanceIndex, 1, Int::plus)
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
    }

    fun categoryRanking(entries: List<UsageEntrySnapshot>): List<UsageCategoryCount> {
        val counts = HashMap<Int, Int>()
        for (entry in entries) counts.merge(entry.categoryIndex, 1, Int::plus)
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .map { UsageCategoryCount(it.key, it.value) }
    }

    // MARK: §3 Trends

    /**
     * Rolling frequency, sampled once per bucket.
     *
     * Each point is plotted at its bucket's start but its window **closes at the
     * bucket's end**, clamped to `rangeEnd`, so the newest point reflects
     * everything logged up to now instead of dipping toward zero just because
     * the current week is young. The window is half-open on the left:
     * `(close − window, close]`.
     *
     * The value is normalized to entries **per week** whatever the window is, so
     * the y-axis means the same thing on 7D and on All.
     */
    fun trends(
        entries: List<UsageEntrySnapshot>,
        ranking: List<Pair<Int, Int>>,
        bucketStarts: List<Instant>,
        bucketDays: Int,
        windowDays: Int,
        rangeEnd: Instant,
        ratePerWeek: Boolean,
    ): List<UsageTrendSeries> {
        if (bucketStarts.isEmpty() || windowDays <= 0) return emptyList()
        val top = ranking.take(MAXIMUM_TREND_SUBSTANCES)
        val windowMillis = (windowDays * SECONDS_PER_DAY * 1000).toLong()
        val bucketSpanMillis = (bucketDays * SECONDS_PER_DAY * 1000).toLong()
        // Per-week rate normalizes the window to a week; raw (7D) leaves each
        // bucket as its own count, which with a one-day window is a per-day bucket.
        val perWeek = if (ratePerWeek) 7.0 / windowDays else 1.0

        val eventsBySubstance = HashMap<Int, MutableList<Pair<Instant, Double?>>>()
        for (entry in entries) {
            eventsBySubstance.getOrPut(entry.substanceIndex) { mutableListOf() }
                .add(entry.timestamp to entry.commonDoses)
        }

        return top.map { (substanceIndex, _) ->
            val events = eventsBySubstance[substanceIndex].orEmpty().sortedBy { it.first }
            val hasCommon = events.any { it.second != null }
            val points = bucketStarts.map { start ->
                val closeCandidate = start.plusMillis(bucketSpanMillis)
                val close = if (closeCandidate < rangeEnd) closeCandidate else rangeEnd
                val lower = close.minusMillis(windowMillis)
                var count = 0
                var common = 0.0
                for (event in events) {
                    if (event.first > lower && event.first <= close) {
                        count++
                        common += event.second ?: 0.0
                    }
                }
                UsageTrendPoint(
                    date = start,
                    value = count * perWeek,
                    commonValue = common * perWeek,
                )
            }
            UsageTrendSeries(substanceIndex, points, hasCommonDoses = hasCommon)
        }
    }

    // MARK: §4 Dose levels

    fun doseLevels(
        entries: List<UsageEntrySnapshot>,
        ranking: List<Pair<Int, Int>>,
        bucketStarts: List<Instant>,
    ): UsageDoseLevelBreakdown {
        val overall = HashMap<Instant, HashMap<Int, Int>>()
        val perSubstance = HashMap<Int, HashMap<Instant, HashMap<Int, Int>>>()
        var resolved = 0

        for (entry in entries) {
            val level = entry.doseLevelIndex ?: continue
            val bucketDate = bucket(entry.timestamp, bucketStarts) ?: continue
            resolved++
            overall.getOrPut(bucketDate) { HashMap() }.merge(level, 1, Int::plus)
            perSubstance.getOrPut(entry.substanceIndex) { HashMap() }
                .getOrPut(bucketDate) { HashMap() }.merge(level, 1, Int::plus)
        }

        fun buckets(from: Map<Instant, Map<Int, Int>>): List<UsageDoseLevelBucket> =
            from.entries.sortedBy { it.key }.map { UsageDoseLevelBucket(it.key, it.value) }

        // Most-logged first, so the selector leads with what the user actually uses.
        val selectable = ranking.map { it.first }.filter { perSubstance.containsKey(it) }

        return UsageDoseLevelBreakdown(
            overall = buckets(overall),
            bySubstance = perSubstance.mapValues { buckets(it.value) },
            selectableSubstances = selectable,
            resolvedEntries = resolved,
            totalEntries = entries.size,
        )
    }

    // MARK: §5 Routes

    /**
     * Every substance's row: entry total, common-dose total, and the per-route
     * split of each.
     *
     * Emits **all** substances rather than a top-N slice: the view can rank by
     * entries *or* by common-dose units, the two orderings genuinely differ, and
     * truncating to the count leaders here would hide the substances that only
     * lead once each dose is weighed by its common dose.
     */
    fun routeBreakdown(
        entries: List<UsageEntrySnapshot>,
        ranking: List<Pair<Int, Int>>,
    ): UsageRouteBreakdown {
        val routeTotals = HashMap<Int, Int>()
        val perSubstanceCount = HashMap<Int, HashMap<Int, Int>>()
        val perSubstanceCommon = HashMap<Int, HashMap<Int, Double>>()
        // Tracked apart from the summed total so a substance with a real 0-sum
        // stays distinct from one with no common-dose data at all.
        val hasCommon = HashSet<Int>()
        for (entry in entries) {
            routeTotals.merge(entry.routeIndex, 1, Int::plus)
            perSubstanceCount.getOrPut(entry.substanceIndex) { HashMap() }
                .merge(entry.routeIndex, 1, Int::plus)
            val common = entry.commonDoses
            if (common != null) {
                perSubstanceCommon.getOrPut(entry.substanceIndex) { HashMap() }
                    .merge(entry.routeIndex, common, Double::plus)
                hasCommon += entry.substanceIndex
            }
        }

        val rows = ranking.mapNotNull { (substanceIndex, count) ->
            val split = perSubstanceCount[substanceIndex] ?: return@mapNotNull null
            val commonByRoute = perSubstanceCommon[substanceIndex] ?: emptyMap()
            val sorted = split.entries
                .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
                .map { UsageRouteRow.RouteSlice(it.key, it.value, commonByRoute[it.key] ?: 0.0) }
            UsageRouteRow(
                substanceIndex = substanceIndex,
                total = count,
                commonTotal = if (hasCommon.contains(substanceIndex)) commonByRoute.values.sum() else null,
                byRoute = sorted,
            )
        }

        val distinct = routeTotals.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

        return UsageRouteBreakdown(rows, distinct)
    }

    // MARK: §6 Co-use

    /**
     * Substance pairs logged on the same session day, ranked by how many days
     * they co-occur.
     *
     * Below [MINIMUM_CO_USE_DAYS] a pair is a coincidence rather than a habit, so
     * it never surfaces — the same overreach the rest of the app avoids.
     */
    fun coUsePairs(dayIndex: Map<Instant, List<UsageEntrySnapshot>>): List<UsageCoUsePair> {
        val pairDays = HashMap<Long, Int>()
        val soloDays = HashMap<Int, Int>()

        for (dayEntries in dayIndex.values) {
            val present = dayEntries.mapTo(mutableSetOf()) { it.substanceIndex }
            for (index in present) soloDays.merge(index, 1, Int::plus)
            val ordered = present.sorted()
            if (ordered.size < 2) continue
            for (i in 0 until ordered.size - 1) {
                for (j in i + 1 until ordered.size) {
                    val key = ordered[i].toLong() * 1_000_003L + ordered[j]
                    pairDays.merge(key, 1, Int::plus)
                }
            }
        }

        val ranked = pairDays.entries
            .filter { it.value >= MINIMUM_CO_USE_DAYS }
            .map { (key, days) ->
                val first = (key / 1_000_003L).toInt()
                val second = (key % 1_000_003L).toInt()
                val union = (soloDays[first] ?: 0) + (soloDays[second] ?: 0) - days
                UsageCoUsePair(first, second, days, maxOf(union, days))
            }
            .sortedWith(
                compareByDescending<UsageCoUsePair> { it.days }
                    .thenBy { it.firstIndex }
                    .thenBy { it.secondIndex },
            )
        return ranked.take(MAXIMUM_CO_USE_PAIRS)
    }

    // MARK: §7 Regularity

    /**
     * Mean gap and coefficient of variation between the **days** a substance was
     * logged.
     *
     * Deliberately day-level and not dose-level: a supplement taken three times
     * daily has ~8 h and ~16 h gaps, whose CV would label a rock-steady routine
     * "Sporadic". The question §7 asks — "do I use on a schedule?" — is about
     * days.
     */
    fun regularity(
        dayIndex: Map<Instant, List<UsageEntrySnapshot>>,
        ranking: List<Pair<Int, Int>>,
    ): List<UsageRegularity> {
        val daysBySubstance = HashMap<Int, MutableSet<Instant>>()
        for ((day, entries) in dayIndex) {
            for (entry in entries) {
                daysBySubstance.getOrPut(entry.substanceIndex) { HashSet() }.add(day)
            }
        }
        val countLookup = ranking.toMap()

        return daysBySubstance.mapNotNull { (index, days) ->
            val entryCount = countLookup[index] ?: 0
            if (entryCount < MINIMUM_REGULARITY_ENTRIES || days.size < 3) return@mapNotNull null
            val sorted = days.sorted()
            val intervals = sorted.zipWithNext { a, b ->
                (b.toEpochMilli() - a.toEpochMilli()) / 1000.0 / SECONDS_PER_DAY
            }
            val stats = intervalStatistics(intervals) ?: return@mapNotNull null
            UsageRegularity(
                substanceIndex = index,
                entryCount = entryCount,
                meanIntervalDays = stats.mean,
                coefficientOfVariation = stats.coefficientOfVariation,
            )
        }.sortedWith(
            compareBy<UsageRegularity> { it.coefficientOfVariation }.thenBy { it.substanceIndex },
        )
    }

    /** Mean, population standard deviation and CV of a set of gaps. */
    fun intervalStatistics(intervals: List<Double>): IntervalStatistics? {
        if (intervals.isEmpty()) return null
        val mean = intervals.sum() / intervals.size
        if (mean <= 0) return null
        // Population variance (divide by n), as upstream.
        val variance = intervals.sumOf { (it - mean) * (it - mean) } / intervals.size
        val deviation = kotlin.math.sqrt(variance)
        return IntervalStatistics(mean, deviation, deviation / mean)
    }

    data class IntervalStatistics(
        val mean: Double,
        val standardDeviation: Double,
        val coefficientOfVariation: Double,
    )

    // MARK: §8 Day of week

    /**
     * Per-weekday totals.
     *
     * **Not** bucketed by session day: the weekday is read off the raw timestamp,
     * because Monday 01:00 is Monday to anyone reading a bar chart. The
     * occurrence denominator is a plain `startOfDay` walk for the same reason.
     */
    fun weekdayBreakdown(
        entries: List<UsageEntrySnapshot>,
        bounds: Bounds,
        calendar: InsightsCalendar,
    ): List<UsageWeekdayBucket> {
        val totals = HashMap<Int, Int>()
        val commonTotals = HashMap<Int, Double>()
        val hasCommon = HashSet<Int>()
        for (entry in entries) {
            val weekday = calendar.weekday(entry.timestamp)
            totals.merge(weekday, 1, Int::plus)
            val common = entry.commonDoses
            if (common != null) {
                commonTotals.merge(weekday, common, Double::plus)
                hasCommon += weekday
            }
        }

        val occurrences = weekdayOccurrences(bounds, calendar)
        val rowWeekdays = (0 until 7).map { (calendar.firstWeekday - 1 + it) % 7 + 1 }
        return rowWeekdays.map { weekday ->
            UsageWeekdayBucket(
                weekday = weekday,
                total = totals[weekday] ?: 0,
                commonTotal = if (hasCommon.contains(weekday)) commonTotals[weekday] else null,
                occurrences = occurrences[weekday] ?: 0,
            )
        }
    }

    /** How many times each weekday falls inside the window — §8's average denominator. */
    fun weekdayOccurrences(bounds: Bounds, calendar: InsightsCalendar): Map<Int, Int> {
        val counts = HashMap<Int, Int>()
        var cursor = calendar.startOfDay(bounds.start)
        var guardRail = 0
        while (cursor <= bounds.end && guardRail < 4_000) {
            counts.merge(calendar.weekday(cursor), 1, Int::plus)
            cursor = calendar.plusDays(cursor, 1)
            guardRail++
        }
        return counts
    }
}

// MARK: - Stage one: the log read

/**
 * Reduces logged doses to the plain values the aggregation reads.
 *
 * The catalog lookups happen here, once per distinct substance rather than once
 * per dose: a thousand caffeine doses cost one resolve, and the ladder is
 * memoized per (substance, route, salt, isomer, release form) so a substance
 * whose forms differ is not read against the wrong set of numbers.
 */
internal object UsageResolver {

    /**
     * @param displayNameOf the user's own relabel, falling back to the catalog
     *   title. `CustomSubstanceStore` is not ported, so callers pass the catalog
     *   title; the seam is here so the overlay arrives without touching this.
     */
    fun resolve(
        entries: List<DoseEntryEntity>,
        catalog: SubstanceCatalog,
        displayNameOf: (String) -> String = { name -> catalog.lookup(name)?.displayTitle ?: name },
    ): Pair<List<UsageEntrySnapshot>, List<UsageSubstanceRef>> {
        val categoryIndices = SubstanceCategory.entries.withIndex().associate { it.value to it.index }
        val routeIndices = RouteOfAdministration.entries.withIndex().associate { it.value to it.index }
        val otherCategoryIndex = categoryIndices[SubstanceCategory.OTHER] ?: 0

        val substanceIndices = HashMap<String, Int>()
        val substances = ArrayList<UsageSubstanceRef>()
        val resolvedSubstances = HashMap<String, Substance?>()
        val ladders = HashMap<LadderKey, Ladder?>()

        val snapshots = ArrayList<UsageEntrySnapshot>(entries.size)
        for (entry in entries) {
            val key = entry.substance.lowercase()
            val substance = resolvedSubstances.getOrPut(key) { catalog.lookup(entry.substance) }

            val categoryIndex = substance?.let { categoryIndices[it.category] } ?: otherCategoryIndex
            val substanceIndex = substanceIndices.getOrPut(key) {
                val index = substances.size
                substances += UsageSubstanceRef(entry.substance, displayNameOf(entry.substance), categoryIndex)
                index
            }

            val ladderKey = LadderKey(key, entry.route, entry.saltForm, entry.isomer, entry.releaseForm)
            val ladder = ladders.getOrPut(ladderKey) { Ladder.of(substance, ladderKey) }

            // An unknown dose is counted but has no tier and no common-dose
            // multiple — a 0 would read as the lowest tier.
            val unknown = entry.isUnknownDose
            snapshots += UsageEntrySnapshot(
                substanceIndex = substanceIndex,
                categoryIndex = categoryIndex,
                routeIndex = routeIndices[entry.route] ?: 0,
                doseLevelIndex = if (unknown) null else ladder?.levelIndex(entry.amount, entry.unit),
                commonDoses = if (unknown) null else ladder?.commonDoses(entry.amount, entry.unit),
                timestamp = entry.timestamp.toInstant(),
            )
        }
        return snapshots to substances
    }

    /**
     * Identity of one dose ladder: a substance's tiers for a route, narrowed to
     * the logged salt and isomer (a magnesium glycinate dose must not be read
     * against the default form's numbers) and release form (an extended-release
     * product has no ladder at all).
     */
    private data class LadderKey(
        val substance: String,
        val route: RouteOfAdministration,
        val saltForm: String?,
        val isomer: String?,
        val releaseForm: String?,
    )

    private class Ladder(val range: DoseRange, val unit: String) {
        fun levelIndex(amount: Double, loggedUnit: String): Int? {
            val converted = DoseUnit.convert(amount, from = loggedUnit, to = unit) ?: return null
            return range.levelFor(converted)?.let(::doseLevelIndex)
        }

        fun commonDoses(amount: Double, loggedUnit: String): Double? {
            val common = range.common ?: return null
            val midpoint = (common.start + common.endInclusive) / 2
            if (midpoint <= 0) return null
            val converted = DoseUnit.convert(amount, from = loggedUnit, to = unit) ?: return null
            return converted / midpoint
        }

        companion object {
            /**
             * The ladder a dose in this form is judged against.
             *
             * An extended-release product spreads its dose over the day — 36 mg of
             * Concerta is a morning dose, and the immediate-release ladder would
             * call it "strong" — so a non-base release form has no ladder, exactly
             * like a dose of unknown amount.
             */
            fun of(substance: Substance?, key: LadderKey): Ladder? {
                if (substance == null) return null
                if (!BaseReleaseForm.contains(key.releaseForm)) return null
                val range = substance.doseRange(key.route, key.saltForm, key.isomer) ?: return null
                if (!range.hasAnyValue) return null
                return Ladder(range, substance.unit(key.route, key.saltForm, key.isomer))
            }
        }
    }

    /**
     * A dose's tier as an index into the ladder.
     *
     * The index is written out rather than derived from `entries`, so a tier
     * inserted upstream breaks the build here instead of silently shifting every
     * band in §4.
     */
    fun doseLevelIndex(level: DoseLevel): Int = when (level) {
        DoseLevel.SUB -> 0
        DoseLevel.THRESHOLD -> 1
        DoseLevel.LIGHT -> 2
        DoseLevel.COMMON -> 3
        DoseLevel.STRONG -> 4
        DoseLevel.HEAVY -> 5
    }
}

// MARK: - Formatting

/**
 * The number shapes the Usage and Patterns copy prints.
 *
 * Every one of these takes `Locale.ROOT`: Swift's `String(format:)` and
 * `.formatted(.number…)` are locale-sensitive, but the *values* the model
 * computes are not, and a device set to a comma-decimal locale would otherwise
 * render `1,5` where the model said `1.5`.
 */
internal object InsightsFormat {

    /** `0…1` fraction digits — "3" for 3.0, "2.5" for 2.5. */
    fun oneDecimal(value: Double): String = trimTrailingZero(String.format(java.util.Locale.ROOT, "%.1f", value))

    /** No fraction digits — "388". */
    fun whole(value: Double): String = String.format(java.util.Locale.ROOT, "%.0f", value)

    /** Exposure numbers span tens (MME) to thousands (mg): whole above 10, else up to one decimal. */
    fun exposure(value: Double): String = if (value >= 10) whole(value) else oneDecimal(value)

    /** Common-dose units: whole past ten, exactly one decimal below it. */
    fun commonDose(value: Double): String =
        if (value >= 10) whole(value) else String.format(java.util.Locale.ROOT, "%.1f", value)

    /** `0.42` → "42%". */
    fun percent(fraction: Double): String = "${Math.round(fraction * 100)}%"

    /** A signed fractional change: `0.12` → "+12%". */
    fun signedPercent(change: Double): String {
        val rounded = Math.round(abs(change) * 100)
        return if (change >= 0) "+$rounded%" else "-$rounded%"
    }

    private fun trimTrailingZero(formatted: String): String =
        if (formatted.endsWith(".0")) formatted.dropLast(2) else formatted
}
