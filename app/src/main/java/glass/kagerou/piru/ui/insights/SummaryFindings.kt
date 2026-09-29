package glass.kagerou.piru.ui.insights

import glass.kagerou.piru.engine.DrugClass
import glass.kagerou.piru.engine.InteractionSeverity
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What a report says *about* a journal, rather than what is in it.
 *
 * Ported from `Piru/Data/Summary/SummaryFindings.swift` (264 lines). This is the
 * half of the PDF report a clinician reads first: the substance table says what was
 * taken, and these say what it adds up to — a peak opioid load against the CDC's
 * references, a dose that has been climbing, two substances that were active at the
 * same time, how the days used compare with the days in the window.
 *
 * ## Why this exists as its own file rather than inside the PDF renderer
 * Upstream keeps it in `Data/Summary/` for the same reason: it is arithmetic and
 * thresholds with no drawing in it, so it can be reasoned about — and tested —
 * without a page. The renderer that consumes it is `PdfReportWriter`; the on-screen
 * patterns card reads [`JournalSummary`] directly and does not need these.
 *
 * ## Findings are severity-ordered, and the order is load-bearing
 * A warning outranks an info whatever its kind, and within a tier the kinds run
 * `opioidLoad → escalation → coExposure → cadence → duplication` — most urgent
 * first. That is upstream's `Kind.sortOrder`, copied rather than reinvented, because
 * the list is truncated in places and the truncation should cut the least urgent.
 */
internal data class Finding(
    val id: String,
    val severity: Severity,
    val kind: Kind,
    val summary: String,
) {
    /** Two tiers, and only two: a finding either wants attention or it is context. */
    enum class Severity { WARNING, INFO }

    /**
     * What a finding is about, in the order it should be read.
     *
     * Declaration order is the sort order — see the class note. Adding a kind means
     * deciding where it belongs in that order, not appending it.
     */
    enum class Kind(val sortOrder: Int) {
        OPIOID_LOAD(0),
        ESCALATION(1),
        CO_EXPOSURE(2),
        CADENCE(3),
        DUPLICATION(4),
    }
}

/**
 * A class-deduped interaction: every substance pair sharing one unordered drug-class
 * pair collapses into a single row listing all of the participants.
 *
 * ## Why the report compresses at all
 * A journal that crosses two classes produces one row per *substance* pair, and a
 * busy week crosses the same two classes repeatedly. The uncompressed list is the
 * same sentence with different names in it; the compressed one is "opioid ×
 * benzodiazepine, 4 substances — dangerous", which is what a reader can act on. The
 * severity kept is the **worst** of the collapsed rows: a class pair is as risky as
 * its most dangerous instance.
 *
 * ## What is deliberately lossy
 * The individual pair names survive (both sides list their members), but the pairing
 * *within* the collapse does not: "A + X" and "B + Y" become one row rather than
 * two, so a reader cannot tell which substance met which. That is upstream's
 * behaviour and it is the right trade for a summary, which is why the uncompressed
 * card list still exists for the screen.
 */
internal data class CompressedInteraction(
    val id: String,
    val severity: InteractionSeverity,
    val classA: DrugClass,
    val classB: DrugClass,
    val substancesA: List<String>,
    val substancesB: List<String>,
)

/**
 * Collapse raw interaction rows by their drug-class pair.
 *
 * Both sides of every row contribute every combination of their classes, so a
 * substance carrying two classes joins two pairs. Classes are ordered by their own
 * ordinal rather than by name: the pair is unordered, so something has to fix which
 * side is `A`, and an enum's declaration order is stable where a name comparison
 * would put `ssri` before `opioid` and read oddly in the table.
 */
internal fun compressInteractions(
    raw: List<InteractionRow>,
): List<CompressedInteraction> {
    class Accumulator(
        var severity: InteractionSeverity,
        val classA: DrugClass,
        val classB: DrugClass,
        val substancesA: MutableSet<String> = linkedSetOf(),
        val substancesB: MutableSet<String> = linkedSetOf(),
    )

    val byPair = mutableMapOf<Pair<DrugClass, DrugClass>, Accumulator>()
    for (row in raw) {
        for (classA in row.drugClassesA) {
            for (classB in row.drugClassesB) {
                // The pair is unordered: fold to the enum's own order so the same two
                // classes always land in the same bucket whichever side they came in on.
                val ordered = if (classA.ordinal <= classB.ordinal) classA to classB else classB to classA
                val existing = byPair[ordered]
                if (existing == null) {
                    // The row's substances enter through the same fold as its classes:
                    // `classA` is `ordered.first`, so whichever of the row's two lists
                    // belongs to that class is the one that goes on side A. Pairing by
                    // the row's own orientation instead would file an opioid under
                    // `substancesA` and then label side A "benzodiazepine".
                    val accumulator = Accumulator(row.severity, ordered.first, ordered.second)
                    if (classA == ordered.first) {
                        accumulator.substancesA += row.substanceA
                        accumulator.substancesB += row.substanceB
                    } else {
                        accumulator.substancesA += row.substanceB
                        accumulator.substancesB += row.substanceA
                    }
                    byPair[ordered] = accumulator
                } else {
                    // The worst severity wins: a class pair is as risky as its most
                    // dangerous instance.
                    if (row.severity > existing.severity) existing.severity = row.severity
                    if (classA == existing.classA) {
                        existing.substancesA += row.substanceA
                        existing.substancesB += row.substanceB
                    } else {
                        existing.substancesA += row.substanceB
                        existing.substancesB += row.substanceA
                    }
                }
            }
        }
    }

    return byPair.values
        .map { acc ->
            CompressedInteraction(
                id = "${acc.classA.name}|${acc.classB.name}",
                severity = acc.severity,
                classA = acc.classA,
                classB = acc.classB,
                substancesA = acc.substancesA.sorted(),
                substancesB = acc.substancesB.sorted(),
            )
        }
        .sortedByDescending { it.severity }
}

/** One raw interaction as the summary needs it: severity, the pair, and both class sets. */
internal data class InteractionRow(
    val severity: InteractionSeverity,
    val substanceA: String,
    val substanceB: String,
    val description: String,
    val drugClassesA: List<DrugClass>,
    val drugClassesB: List<DrugClass>,
)

/**
 * Where a finding's dose unit comes from.
 *
 * `SummarySubstance` carries its currency as a **string resource id**, because the
 * unit's name is copy and copy is localized — `MME`, `mg diazepam-eq`, `common doses`.
 * A finding that printed the resource id, or an English constant beside it, would be a
 * second place for the same word to live. So the caller supplies the lookup and this
 * file stays free of `Context`, which is what lets the thresholds below be tested on
 * the JVM without Robolectric.
 */
internal fun interface UnitLabel {
    fun of(unitRes: Int): String
}

/**
 * The findings a report leads with.
 *
 * Pure: the same report, interactions and units produce the same list, in the same
 * order, on any machine. That is what makes the thresholds testable, and they are the
 * part of this file worth testing — a wrong threshold here is a claim about someone's
 * opioid load.
 */
internal fun findings(
    report: JournalSummary,
    interactions: List<CompressedInteraction>,
    unitLabel: UnitLabel,
): List<Finding> {
    val results = mutableListOf<Finding>()
    appendOpioidLoadFindings(report, results)
    appendEscalationFindings(report, unitLabel, results)
    appendCoExposureFindings(report, interactions, results)
    appendCadenceFindings(report, results)
    return results.sortedWith(
        compareBy<Finding> { if (it.severity == Finding.Severity.WARNING) 0 else 1 }
            .thenBy { it.kind.sortOrder },
    )
}

// MARK: - Threshold rules

/**
 * The CDC's morphine-milligram-equivalent references, which is why this rule fires at
 * all: 50 MME/day is the level at which the guideline suggests reassessing, and 90 the
 * level at which it suggests specialist involvement. Both are named in the sentence, so
 * a reader can check the claim rather than trust it.
 */
private const val OPIOID_MME_RECONSIDER = 50.0
private const val OPIOID_MME_SPECIALIST = 90.0

/**
 * A rise is only reported when the aggregation already called it a rise *and* it moved
 * more than the aggregation's own noise threshold. The two conditions are not
 * redundant: the direction is a sign, and this is a magnitude, so a 1% rise is a rise
 * the report should not spend a line on.
 */
private const val ESCALATION_REPORTABLE = 0.15

/** Below this many days the used-days fraction says nothing about a cadence. */
private const val CADENCE_MINIMUM_DAYS = 14

private const val CADENCE_MOSTLY_ON = 0.85
private const val CADENCE_MOSTLY_OFF = 0.15

private fun appendOpioidLoadFindings(report: JournalSummary, results: MutableList<Finding>) {
    val peakMme = report.opioidPeakDayMme ?: return
    if (peakMme < OPIOID_MME_RECONSIDER) return
    val n = peakMme.roundToInt()
    results += Finding(
        id = "opioidLoad",
        severity = Finding.Severity.WARNING,
        kind = Finding.Kind.OPIOID_LOAD,
        summary = if (peakMme >= OPIOID_MME_SPECIALIST) {
            "Peak opioid load $n MME/day — above the CDC 90 MME reference."
        } else {
            "Peak opioid load $n MME/day — at or above the CDC 50 MME reference."
        },
    )
}

private fun appendEscalationFindings(
    report: JournalSummary,
    unitLabel: UnitLabel,
    results: MutableList<Finding>,
) {
    for (stat in report.escalation) {
        if (stat.direction != EscalationDirection.RISING) continue
        if (abs(stat.change) <= ESCALATION_REPORTABLE) continue
        val substance = report.substances.getOrNull(stat.substanceIndex) ?: continue
        val percent = (stat.change * 100).roundToInt()
        results += Finding(
            id = "escalation-${stat.substanceIndex}",
            severity = Finding.Severity.WARNING,
            kind = Finding.Kind.ESCALATION,
            summary = "${substance.displayName} dose rising, +$percent% over the reporting " +
                "period (${formatFindingDose(stat.earlyMedian)} → " +
                "${formatFindingDose(stat.lateMedian)} ${unitLabel.of(substance.unitRes)}).",
        )
    }
}

/**
 * A dangerous class pair is only reported when the two substances were **actually
 * active together**, and the hours come from the overlap pass rather than from the
 * interaction itself.
 *
 * That is the whole point of the rule: the interaction table can say a pair is
 * dangerous from the classes alone, but a report that says so for a month in which the
 * two never met would be frightening and false. Match on the canonical (lowercased)
 * name, because the overlap pass keys on the aggregation's names.
 */
private fun appendCoExposureFindings(
    report: JournalSummary,
    interactions: List<CompressedInteraction>,
    results: MutableList<Finding>,
) {
    val dangerous = interactions.filter {
        it.severity == InteractionSeverity.DANGEROUS || it.severity == InteractionSeverity.UNSAFE
    }
    if (dangerous.isEmpty()) return

    data class Overlap(val displayA: String, val displayB: String, val hours: Double)

    val overlapByPair = mutableMapOf<String, Overlap>()
    for (overlap in report.overlaps) {
        val subA = report.substances.getOrNull(overlap.a) ?: continue
        val subB = report.substances.getOrNull(overlap.b) ?: continue
        overlapByPair[overlapKey(subA.name, subB.name)] =
            Overlap(subA.displayName, subB.displayName, overlap.hours)
    }

    for (interaction in dangerous) {
        var best: Overlap? = null
        for (nameA in interaction.substancesA) {
            for (nameB in interaction.substancesB) {
                val entry = overlapByPair[overlapKey(nameA, nameB)] ?: continue
                if (entry.hours > (best?.hours ?: 0.0)) best = entry
            }
        }
        val match = best ?: continue
        val hours = match.hours.roundToInt()
        results += Finding(
            id = "coExposure-${interaction.id}",
            severity = Finding.Severity.WARNING,
            kind = Finding.Kind.CO_EXPOSURE,
            summary = "${match.displayA} + ${match.displayB} active together ~${hours}h " +
                "(${interaction.classA.label} + ${interaction.classB.label}).",
        )
    }
}

/**
 * How the window was used, as one of two sentences — and **neither is a judgement**.
 *
 * The house rule is anti-guilt: a journal that was used every day and one that was
 * used twice in a month are both just facts about the month, so the two branches are
 * worded the same way and neither congratulates nor scolds. Upstream does the same,
 * and the low branch is the one that names the longest break, because that is the
 * figure a reader actually wonders about.
 */
private fun appendCadenceFindings(report: JournalSummary, results: MutableList<Finding>) {
    val holidays = report.holidays
    if (holidays.totalDays < CADENCE_MINIMUM_DAYS) return

    if (holidays.fractionUsed > CADENCE_MOSTLY_ON) {
        val percent = (holidays.fractionUsed * 100).roundToInt()
        results += Finding(
            id = "cadence-high",
            severity = Finding.Severity.INFO,
            kind = Finding.Kind.CADENCE,
            summary = "Used ${holidays.daysUsed} of ${holidays.totalDays} days ($percent%).",
        )
    } else if (holidays.fractionUsed < CADENCE_MOSTLY_OFF) {
        results += Finding(
            id = "cadence-low",
            severity = Finding.Severity.INFO,
            kind = Finding.Kind.CADENCE,
            summary = "Used ${holidays.daysUsed} of ${holidays.totalDays} days; " +
                "longest break ${holidays.longestBreakDays} days.",
        )
    }
}

// MARK: - Helpers

/** The unordered key the overlap pass is looked up by, both names lowercased. */
private fun overlapKey(nameA: String, nameB: String): String =
    listOf(nameA.lowercase(Locale.ROOT), nameB.lowercase(Locale.ROOT)).sorted().joinToString("|")

/**
 * A dose as a finding prints it: whole when it is whole, one decimal otherwise.
 *
 * `Locale.ROOT` because the number came from the model and a Turkish device would
 * otherwise write `1,5` where the arithmetic produced 1.5 — the port's standing rule.
 */
private fun formatFindingDose(value: Double): String =
    if (value == value.roundToInt().toDouble() && value < 10_000) {
        value.roundToInt().toString()
    } else {
        String.format(Locale.ROOT, "%.1f", value)
    }
