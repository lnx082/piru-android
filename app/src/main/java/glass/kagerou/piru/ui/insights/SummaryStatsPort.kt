package glass.kagerou.piru.ui.insights

import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.PKModel
import glass.kagerou.piru.engine.PKResolver
import glass.kagerou.piru.engine.PharmacologySource
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.model.BaseReleaseForm
import glass.kagerou.piru.model.DoseUnit
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.model.SubstanceCategory
import java.time.Instant

/**
 * The clinical/patterns aggregate, ported from `Piru/Data/Summary/SummaryStats.swift`
 * (345 lines) and `SummaryStatsResolver.swift` (153 lines).
 *
 * One value serves both the Patterns screen and — when the port grows one — the
 * PDF clinician report, so the app and the print-out cannot disagree: the
 * exposure totals, the escalation medians and the co-exposure hours are computed
 * once, over the same snapshots, by the same functions.
 *
 * ## `nil` is "not measurable this way", never zero
 * A dose whose amount cannot be expressed in its substance's currency is dropped
 * from that substance's total, and a substance with no such dose at all produces
 * no row. Summing it as 0 would read as "never taken" rather than "no published
 * equivalent exists".
 *
 * ## Time is equal-weighted here, unlike §3 of Usage
 * Every number below is a plain sum or mean over the window. The only time-aware
 * pass is the overlap scan, which samples hourly because co-exposure is a
 * question about *when*, not how much.
 */

// MARK: - Currency

/**
 * The currency a substance's exposure is measured in.
 *
 * Clinical equivalents lead because a prescriber already reasons in them; the
 * common-dose fallback — a multiple of the substance's own typical dose — is the
 * honest cross-substance unit when no clinical equivalence exists.
 *
 * Declaration order is upstream's and is not load-bearing for sorting; it is kept
 * so the two enums read the same way.
 */
internal enum class ExposureCurrency(val unitLabel: String) {
    /** Morphine milligram equivalents (linear opioids, CDC 2022). */
    MME("MME"),

    /** Diazepam-equivalent milligrams (benzodiazepines). */
    DIAZEPAM("mg diazepam-eq"),

    /** Multiples of the substance's own common dose. */
    COMMON_DOSE("common doses"),

    /** The raw logged mass — a mass-dosed substance with no ladder and no equivalence. */
    MILLIGRAMS("mg"),
}

/** One substance in the report: identity, tint, and the currency its doses are summed in. */
internal data class SummarySubstance(
    val name: String,
    val displayName: String,
    val tint: P3Color,
    val currency: ExposureCurrency,
) {
    val unit: String get() = currency.unitLabel
}

/**
 * One dose reduced to the values the aggregation needs.
 *
 * [exposure] is the dose in its substance's currency, or null when it cannot be
 * expressed that way. [ke] and [ka] are the PK rate constants behind the overlap
 * pass, null for supplements and unmodeled forms — the same things the body-load
 * graph leaves out.
 */
internal data class SummaryDose(
    val substanceIndex: Int,
    val timestamp: Instant,
    val exposure: Double?,
    val ke: Double?,
    val ka: Double?,
)

// MARK: - Outputs

/**
 * Days used against days off, framed as a record (days used, longest break)
 * rather than as failed abstinence.
 */
internal data class HolidayStats(
    val totalDays: Int,
    val daysUsed: Int,
    val longestBreakDays: Int,
    val currentBreakDays: Int,
) {
    val daysOff: Int get() = totalDays - daysUsed

    val fractionUsed: Double get() = if (totalDays > 0) daysUsed.toDouble() / totalDays else 0.0
}

internal data class ExposureStat(
    val substanceIndex: Int,
    val currency: ExposureCurrency,
    val total: Double,
    /** The heaviest single day's exposure — the number a clinician checks against a daily threshold. */
    val peakDay: Double,
    val dailyMean: Double,
    /** Running cumulative total at each dose, oldest to newest: the curve. */
    val cumulative: List<CumulativePoint>,
) {
    data class CumulativePoint(val index: Int, val date: Instant, val total: Double)
}

internal enum class EscalationDirection { RISING, FALLING, STEADY }

internal data class EscalationStat(
    val substanceIndex: Int,
    val direction: EscalationDirection,
    /** Signed fractional change from the window's first third to its last third. */
    val change: Double,
    val earlyMedian: Double,
    val lateMedian: Double,
)

internal data class OverlapStat(val a: Int, val b: Int, val hours: Double)

internal data class JournalSummary(
    val substances: List<SummarySubstance>,
    val holidays: HolidayStats,
    val exposure: List<ExposureStat>,
    val escalation: List<EscalationStat>,
    val overlaps: List<OverlapStat>,
) {
    val isEmpty: Boolean get() = substances.isEmpty()

    /** Mean opioid exposure per day across the window, in MME. Null when no opioids. */
    val opioidMmePerDay: Double? get() = classDailyMean(ExposureCurrency.MME)

    /** Peak single-day MME — the number the CDC's 50/90 MME references are about. */
    val opioidPeakDayMme: Double? get() = classPeakDay(ExposureCurrency.MME)

    val benzoDiazepamPerDay: Double? get() = classDailyMean(ExposureCurrency.DIAZEPAM)

    private fun classDailyMean(currency: ExposureCurrency): Double? {
        val rows = exposure.filter { it.currency == currency }
        if (rows.isEmpty()) return null
        return rows.sumOf { it.dailyMean }
    }

    private fun classPeakDay(currency: ExposureCurrency): Double? {
        val rows = exposure.filter { it.currency == currency }
        if (rows.isEmpty()) return null
        // Same-class substances share the MME or diazepam scale, so their per-day
        // peaks add rather than being taken one at a time.
        return rows.sumOf { it.peakDay }
    }
}

// MARK: - The aggregation

internal object SummaryStats {

    /**
     * Doses whose PK curve exceeds this fraction of the dose count as "active"
     * for the overlap pass — the same 3% floor the body-load readout uses.
     */
    private const val ACTIVE_FRACTION_FLOOR = 0.03

    /** A substance needs this many doses before its trend is reported. */
    private const val MINIMUM_ESCALATION_DOSES = 6

    /** …spanning at least this many days. */
    private const val MINIMUM_ESCALATION_SPAN_DAYS = 21.0

    /** Change beyond ±this fraction reads as a real trend, not noise. */
    private const val ESCALATION_THRESHOLD = 0.15

    /** Overlap sample spacing in minutes — hourly is fine for co-exposure hours. */
    private const val OVERLAP_STEP_MINUTES = 60.0

    private const val SECONDS_PER_DAY = 86_400.0

    fun report(
        substances: List<SummarySubstance>,
        doses: List<SummaryDose>,
        start: Instant,
        end: Instant,
        calendar: InsightsCalendar,
    ): JournalSummary = JournalSummary(
        substances = substances,
        holidays = holidays(doses, start, end, calendar),
        exposure = exposure(substances, doses, start, end, calendar),
        escalation = escalation(substances, doses, start, end),
        overlaps = overlaps(substances, doses, start, end),
    )

    // MARK: Days used

    /**
     * Days used, the longest break, and the run of unused days ending today.
     *
     * Plain `startOfDay`, not the session day: this is a count of calendar days
     * the user would recognise from a wall calendar, and upstream takes it the
     * same way (spec §0.1 lists `SummaryStats.swift:199,200,205,251`).
     */
    private fun holidays(
        doses: List<SummaryDose>,
        start: Instant,
        end: Instant,
        calendar: InsightsCalendar,
    ): HolidayStats {
        val startDay = calendar.startOfDay(start)
        val endDay = calendar.startOfDay(end)
        val totalDays = maxOf(
            1,
            (java.time.temporal.ChronoUnit.DAYS.between(
                calendar.dateOf(startDay),
                calendar.dateOf(endDay),
            ) + 1).toInt(),
        )

        val used = HashSet<Instant>()
        for (dose in doses) {
            if (dose.timestamp >= start && dose.timestamp <= end) {
                used += calendar.startOfDay(dose.timestamp)
            }
        }

        var longestBreak = 0
        var run = 0
        for (offset in 0 until totalDays) {
            val day = calendar.plusDays(startDay, offset.toLong())
            // A DST change can make `plusDays` land past the window's end, which
            // would otherwise count a day that does not exist as a day off.
            if (calendar.dateOf(day).toEpochDay() - calendar.dateOf(startDay).toEpochDay() >= totalDays) break
            if (used.contains(day)) run = 0 else {
                run++
                longestBreak = maxOf(longestBreak, run)
            }
        }

        var currentBreak = 0
        var back = 0
        while (back < totalDays && !used.contains(calendar.plusDays(endDay, -back.toLong()))) {
            currentBreak++
            back++
        }

        return HolidayStats(totalDays, used.size, longestBreak, currentBreak)
    }

    // MARK: Exposure

    private fun exposure(
        substances: List<SummarySubstance>,
        doses: List<SummaryDose>,
        start: Instant,
        end: Instant,
        calendar: InsightsCalendar,
    ): List<ExposureStat> {
        val windowDays = maxOf(1.0, (end.toEpochMilli() - start.toEpochMilli()) / 1000.0 / SECONDS_PER_DAY)
        val out = ArrayList<ExposureStat>()
        for ((index, substance) in substances.withIndex()) {
            val rows = doses
                .filter { it.substanceIndex == index && it.exposure != null && it.timestamp >= start && it.timestamp <= end }
                .sortedBy { it.timestamp }
            if (rows.isEmpty()) continue

            var runningTotal = 0.0
            val cumulative = ArrayList<ExposureStat.CumulativePoint>(rows.size)
            val perDay = HashMap<Instant, Double>()
            for ((i, dose) in rows.withIndex()) {
                val value = dose.exposure ?: 0.0
                runningTotal += value
                cumulative += ExposureStat.CumulativePoint(i, dose.timestamp, runningTotal)
                perDay.merge(calendar.startOfDay(dose.timestamp), value, Double::plus)
            }
            out += ExposureStat(
                substanceIndex = index,
                currency = substance.currency,
                total = runningTotal,
                peakDay = perDay.values.maxOrNull() ?: 0.0,
                dailyMean = runningTotal / windowDays,
                cumulative = cumulative,
            )
        }
        return out.sortedByDescending { it.total }
    }

    // MARK: Escalation

    /**
     * The first-third median against the last-third median, as a fraction.
     *
     * The medians rather than the means so a single outlier dose does not read as
     * a trend, and the thirds rather than the endpoints so one odd first dose does
     * not either. Rising, falling and steady are all reported — a dose that has
     * crept *down* is as much a reading of the record as one that crept up.
     */
    private fun escalation(
        substances: List<SummarySubstance>,
        doses: List<SummaryDose>,
        start: Instant,
        end: Instant,
    ): List<EscalationStat> {
        val out = ArrayList<EscalationStat>()
        for (index in substances.indices) {
            val rows = doses
                .filter { it.substanceIndex == index && it.exposure != null && it.timestamp >= start && it.timestamp <= end }
                .sortedBy { it.timestamp }
            if (rows.size < MINIMUM_ESCALATION_DOSES) continue
            val first = rows.first().timestamp
            val last = rows.last().timestamp
            if ((last.toEpochMilli() - first.toEpochMilli()) / 1000.0 / SECONDS_PER_DAY < MINIMUM_ESCALATION_SPAN_DAYS) continue

            val third = rows.size / 3
            if (third < 1) continue
            val early = median(rows.take(third).mapNotNull { it.exposure }) ?: continue
            val late = median(rows.takeLast(third).mapNotNull { it.exposure }) ?: continue
            if (early <= 0) continue

            val change = (late - early) / early
            val direction = when {
                change > ESCALATION_THRESHOLD -> EscalationDirection.RISING
                change < -ESCALATION_THRESHOLD -> EscalationDirection.FALLING
                else -> EscalationDirection.STEADY
            }
            out += EscalationStat(index, direction, change, early, late)
        }
        // Largest movement first, whichever way it went.
        return out.sortedByDescending { kotlin.math.abs(it.change) }
    }

    // MARK: Overlap

    /**
     * Hours two substances were both above the body-load floor.
     *
     * Sampled hourly from `start` to `end`, and each sample asks the PK model
     * whether *each* dose is still more than 3% present. This is the one
     * time-aware pass in the file, and the only place a dose logged before the
     * window matters — which is why the resolver hands it every dose up to `end`
     * rather than only those inside the window.
     */
    private fun overlaps(
        substances: List<SummarySubstance>,
        doses: List<SummaryDose>,
        start: Instant,
        end: Instant,
    ): List<OverlapStat> {
        val modeled = doses.filter { it.ke != null && it.ka != null && it.timestamp <= end }
        if (modeled.isEmpty() || end <= start) return emptyList()

        val hoursPerStep = OVERLAP_STEP_MINUTES / 60.0
        val pairHours = HashMap<Long, Double>()
        val count = substances.size

        var t = start
        while (t <= end) {
            val active = sortedSetOf<Int>()
            for (dose in modeled) {
                if (dose.timestamp > t) continue
                val ke = dose.ke ?: continue
                val ka = dose.ka ?: continue
                val elapsed = (t.toEpochMilli() - dose.timestamp.toEpochMilli()) / 60_000.0
                if (PKModel.fractionRemainingInBody(elapsed, ke, ka) > ACTIVE_FRACTION_FLOOR) {
                    active += dose.substanceIndex
                }
            }
            if (active.size >= 2) {
                val list = active.toList()
                for (i in 0 until list.size - 1) {
                    for (j in i + 1 until list.size) {
                        pairHours.merge(list[i].toLong() * 1_000L + list[j], hoursPerStep, Double::plus)
                    }
                }
            }
            t = t.plusMillis((OVERLAP_STEP_MINUTES * 60_000).toLong())
        }

        return pairHours.entries
            .map { OverlapStat((it.key / 1_000L).toInt(), (it.key % 1_000L).toInt(), it.value) }
            .filter { it.hours >= 1.0 }
            .sortedByDescending { it.hours }
    }

    /** The median, or null for nothing. Even counts average the middle two. */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
    }
}

// MARK: - Stage one: the log read

/**
 * Resolves the log into the snapshots [SummaryStats] aggregates.
 *
 * Every equivalence, ladder and PK lookup behind a dose happens here, once; the
 * aggregation that follows is a pure function of the result. Two callers share
 * it — the Patterns screen and the report builder — so they cannot show
 * different numbers for the same log.
 */
internal object SummaryStatsResolver {

    fun resolve(
        entries: List<DoseEntryEntity>,
        catalog: SubstanceCatalog,
        pharmacology: PharmacologySource,
        tintMap: Map<String, P3Color>,
        end: Instant,
    ): Pair<List<SummarySubstance>, List<SummaryDose>> {
        val substances = ArrayList<SummarySubstance>()
        val doses = ArrayList<SummaryDose>()
        val metaByName = HashMap<String, Meta>()
        val substanceCache = HashMap<String, Substance?>()

        fun lookup(name: String): Substance? =
            substanceCache.getOrPut(name.lowercase()) { catalog.lookup(name) }

        // Overlap needs doses that landed before the window but are still active
        // inside it, so everything up to `end` is included; the per-window stats
        // filter themselves.
        for (entry in entries) {
            if (entry.timestamp.toInstant() > end) continue
            val substance = lookup(entry.substance)
            val canonical = substance?.name ?: entry.substance
            val canonicalKey = canonical.lowercase()

            val meta = metaByName.getOrPut(canonicalKey) {
                val params = pharmacology.pharmacologyParameters(canonical)
                val index = substances.size
                val currency = currencyFor(substance, params.opioidMMEPerMg, params.diazepamPerMg)
                substances += SummarySubstance(
                    name = canonical,
                    displayName = substance?.displayTitle ?: canonical,
                    tint = tintMap[canonicalKey] ?: P3Color.NEUTRAL,
                    currency = currency,
                )
                Meta(index, substance, currency)
            }

            // An unknown dose is a dose taken with no exposure to sum.
            val unknown = entry.isUnknownDose
            val doseMg = if (unknown) null else DoseUnit.convert(entry.amount, from = entry.unit, to = "mg")
            val exposure = if (unknown) {
                null
            } else {
                exposureValue(meta.currency, meta.substance, entry, doseMg, pharmacology, canonicalKey)
            }
            val (ke, ka) = rateConstants(meta.substance, entry, catalog)
            doses += SummaryDose(meta.index, entry.timestamp.toInstant(), exposure, ke, ka)
        }

        return substances to doses
    }

    private data class Meta(val index: Int, val substance: Substance?, val currency: ExposureCurrency)

    /**
     * Which currency this substance's doses are summed in.
     *
     * The equivalence tables lead, and their read layer already refuses a factor
     * for a row that is not linearly convertible — so methadone, transdermal
     * fentanyl and buprenorphine carry no MME here rather than borrowing a
     * number that does not exist for them.
     */
    private fun currencyFor(
        substance: Substance?,
        opioidMmePerMg: Double?,
        diazepamPerMg: Double?,
    ): ExposureCurrency {
        if (opioidMmePerMg != null) return ExposureCurrency.MME
        if (diazepamPerMg != null) return ExposureCurrency.DIAZEPAM
        val range = substance?.let { it.doseRange(it.defaultRoute) ?: it.routes.firstOrNull()?.doses }
        if (range?.common != null) return ExposureCurrency.COMMON_DOSE
        return ExposureCurrency.MILLIGRAMS
    }

    private fun exposureValue(
        currency: ExposureCurrency,
        substance: Substance?,
        entry: DoseEntryEntity,
        doseMg: Double?,
        pharmacology: PharmacologySource,
        canonicalKey: String,
    ): Double? = when (currency) {
        ExposureCurrency.MME -> {
            val factor = pharmacology.pharmacologyParameters(canonicalKey).opioidMMEPerMg
            if (doseMg == null || doseMg <= 0 || factor == null) null else doseMg * factor
        }

        ExposureCurrency.DIAZEPAM -> {
            val factor = pharmacology.pharmacologyParameters(canonicalKey).diazepamPerMg
            if (doseMg == null || doseMg <= 0 || factor == null) null else doseMg * factor
        }

        ExposureCurrency.COMMON_DOSE -> {
            // A common dose is a point on the *base-form* ladder; an
            // extended-release dose is not a multiple of it.
            if (substance == null || !BaseReleaseForm.contains(entry.releaseForm)) {
                null
            } else {
                val route = entry.route
                val range = substance.doseRange(route)
                    ?: substance.doseRange(substance.defaultRoute)
                    ?: substance.routes.firstOrNull()?.doses
                val common = range?.common
                if (range == null || common == null) {
                    null
                } else {
                    val midpoint = (common.start + common.endInclusive) / 2
                    val ladderUnit = substance.unit(route)
                    if (midpoint <= 0) null
                    else DoseUnit.convert(entry.amount, from = entry.unit, to = ladderUnit)?.div(midpoint)
                }
            }
        }

        ExposureCurrency.MILLIGRAMS -> doseMg
    }

    /**
     * `(ke, ka)` for the overlap pass, or `(null, null)` for supplements and
     * unmodeled forms — the same things the body-load graph excludes.
     */
    private fun rateConstants(
        substance: Substance?,
        entry: DoseEntryEntity,
        catalog: SubstanceCatalog,
    ): Pair<Double?, Double?> {
        if (substance?.category == SubstanceCategory.SUPPLEMENT) return null to null
        val productName = entry.productName
        val productDuration = if (productName.isNullOrEmpty()) null else catalog.productDuration(productName)
        if (productDuration == null && !BaseReleaseForm.contains(entry.releaseForm)) return null to null
        val params = PKResolver.params(
            substance,
            productDuration ?: substance?.resolveDuration(entry.route, entry.saltForm, entry.isomer),
        ) ?: return null to null
        return params.ke to params.ka
    }

    /** A route's label for the report copy; the port has no localized route table yet. */
    fun routeLabel(route: RouteOfAdministration): String = route.displayName
}
