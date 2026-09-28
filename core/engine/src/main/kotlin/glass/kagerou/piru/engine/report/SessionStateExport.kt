package glass.kagerou.piru.engine.report

import glass.kagerou.piru.engine.InteractionSeverity
import glass.kagerou.piru.engine.PKModel
import glass.kagerou.piru.model.doseFormatted
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * An immutable snapshot of the person's **current state** — the substances still
 * on board, what each feels like right now (phase, subjective intensity, time to
 * the next phase), and how each is clearing.
 *
 * Ported from `Piru/Utilities/SessionStateExport.swift` (+Markdown). Built once
 * into value types by the data layer (every catalog lookup pre-resolved), then
 * rendered as Markdown off the main thread. The **subjective** section is
 * per-dose (each dose's phase is distinct), while **elimination** groups redoses
 * of the same substance into one combined PK curve — first-order superposes
 * linearly, alcohol runs the zero-order ceiling model.
 */
data class SessionStateExport(
    val generatedAt: Instant,
    val sessionStart: Instant,
    /** Per-dose subjective state. */
    val substances: List<SubstanceState>,
    /** Per-substance combined elimination. */
    val eliminations: List<EliminationGroup>,
    val interactions: List<InteractionLine>,
    /** `true` when the session is currently active (some dose still on board). */
    val isLive: Boolean,
    /** The session's timestamped notes (empty when the host has none to give). */
    val notes: List<NoteLine> = emptyList(),
) {
    data class SubstanceState(
        val name: String,
        val amount: Double,
        val unit: String,
        val route: String,
        val doseTimestamp: Instant,
        /** Subjective — right now. */
        val phase: SessionPhase,
        /** `0…1` of this dose's own peak (not a measure of impairment). */
        val intensity: Double,
        /** `0…1` through the effect curve. */
        val progress: Double,
        val totalMinutes: Double,
        /** Cumulative phase-end positions as fractions of `totalMinutes` — `[onsetEnd, comeupEnd, peakEnd, offsetEnd]`. */
        val phaseBoundaries: List<Double>,
        /** `null` once past the offset (at/after baseline). */
        val next: NextPhase?,
        val baselineAt: Instant,
        /** Normalized `0…1` subjective effect over `totalMinutes`. */
        val effectCurve: List<Double>,
    )

    /** The next phase the person will enter, and when. */
    data class NextPhase(val phase: SessionPhase, val at: Instant)

    /** One note, resolved for rendering: its T+ from the session start, the structure line, and the descriptor names. */
    data class NoteLine(
        val timestamp: Instant,
        val checkIn: Boolean,
        val text: String,
        val structure: String,
        val descriptors: List<String>,
    )

    /** One substance's combined elimination — all its active doses superposed. */
    data class EliminationGroup(
        val name: String,
        val doseCount: Int,
        val model: Elimination,
        /** Absolute amount in body (`unit`) over `horizonMinutes` measured from `groupStart`. Empty when unmodellable. */
        val curve: List<Double>,
        val unit: String,
        val horizonMinutes: Double,
        /** Earliest dose in the group — the curve/decay-table time origin. */
        val groupStart: Instant,
    )

    data class InteractionLine(
        val severity: InteractionSeverity,
        val a: String,
        val b: String,
        val detail: String,
    )

    // MARK: - Markdown

    fun markdown(locale: Locale, zone: ZoneId): String {
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone)
        val dayMonth = DateTimeFormatter.ofPattern("MMM d", locale).withZone(zone)
        val full = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone)
        val dateOnly = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zone)

        fun clock(date: Instant): String =
            if (date.atZone(zone).toLocalDate() == generatedAt.atZone(zone).toLocalDate()) {
                time.format(date)
            } else {
                "${time.format(date)} (${dayMonth.format(date)})"
            }

        val out = mutableListOf<String>()
        out.add("# Piru — " + if (isLive) "Session Snapshot" else "Session Report")
        out.add("")
        if (isLive) {
            out.add("- **Generated:** ${full.format(generatedAt)}")
            out.add("- **Session started:** ${clock(sessionStart)} (${durationHM(seconds(sessionStart, generatedAt))} ago)")
            out.add("- **Active substances:** ${substances.size}")
        } else {
            out.add("- **Session:** ${dateOnly.format(sessionStart)}")
            out.add("- **Doses:** ${substances.size}")
        }
        out.add("")

        if (interactions.isNotEmpty()) {
            for (line in interactions) {
                out.add("> ${line.severity.exportSymbol} **${line.severity.label}:** ${line.a} + ${line.b} — ${line.detail}")
            }
            out.add("")
        }

        out.add("## " + if (isLive) "Current state (subjective)" else "Entries")
        out.add("")
        for (s in substances) {
            out.add("### ${s.name} — ${doseFormatted(s.amount)} ${s.unit} ${s.route.lowercase()}")
            if (isLive) {
                out.add("- Taken: ${clock(s.doseTimestamp)} (${durationHM(seconds(s.doseTimestamp, generatedAt))} ago)")
                val next = s.next
                if (next != null) {
                    out.add(
                        "- Phase: **${s.phase.englishName}** — ${next.phase.englishName.lowercase()} in ~" +
                            "${durationHM(seconds(generatedAt, next.at))}, baseline ${clock(s.baselineAt)}",
                    )
                } else {
                    out.add("- Phase: **${s.phase.englishName}** — baseline ${clock(s.baselineAt)}")
                }
                out.add("- Subjective intensity: **${(s.intensity * 100).roundToInt()}%** of this dose's peak")
                out.add("- Curve progress: ${(s.progress * 100).roundToInt()}%")
            } else {
                out.add("- Taken: ${clock(s.doseTimestamp)}")
            }
            out.add("")
        }

        if (notes.isNotEmpty()) {
            out.add("## Notes")
            out.add("")
            for (note in notes) {
                var head = "**${tPlus(sessionStart, note.timestamp)}** (${clock(note.timestamp)})"
                if (note.checkIn) head += " · check-in"
                if (note.structure.isNotEmpty()) head += " · " + note.structure
                out.add("- $head")
                if (note.text.isNotEmpty()) out.add("  " + note.text.replace("\n", "\n  "))
                if (note.descriptors.isNotEmpty()) out.add("  _" + note.descriptors.joinToString(" · ") + "_")
            }
            out.add("")
        }

        out.add("## Elimination")
        out.add("")
        if (isLive) {
            out.add("| Substance | Half-life | In body now | Eliminated | 50% gone | 90% gone | Cleared |")
            out.add("|---|---|---|---|---|---|---|")
        } else {
            out.add("| Substance | Half-life | 50% gone | 90% gone | Cleared |")
            out.add("|---|---|---|---|---|")
        }
        for (group in eliminations) {
            out.add(eliminationRow(group, isLive, ::clock))
        }
        out.add("")

        val detail = eliminations.filter { it.curve.isNotEmpty() }
        if (detail.isNotEmpty()) {
            out.add("### Decay detail")
            out.add("")
            for (group in detail) {
                out.add("**${group.name}**${halfLifeSuffix(group)}:")
                out.add("```")
                out.addAll(decayRows(group, ::clock))
                out.add("```")
                out.add("")
            }
        }

        out.add("---")
        out.add("*Model estimates (one-compartment oral PK; alcohol zero-order). Individual clearance varies. Intensity is peak-relative. Not medical advice.*")
        return out.joinToString("\n")
    }

    private fun countSuffix(group: EliminationGroup): String =
        if (group.doseCount > 1) " (×${group.doseCount})" else ""

    private fun eliminationRow(group: EliminationGroup, isLive: Boolean, clock: (Instant) -> String): String {
        val name = group.name + countSuffix(group)
        fun row(halfLife: String, inBody: String, eliminated: String, t50: String, t90: String, last: String): String =
            if (isLive) "| $name | $halfLife | $inBody | $eliminated | $t50 | $t90 | $last |"
            else "| $name | $halfLife | $t50 | $t90 | $last |"

        return when (val model = group.model) {
            is Elimination.FirstOrder -> row(
                halfLife = durationHM(model.halfLifeMinutes * 60),
                inBody = amount(model.amountRemaining, group.unit),
                eliminated = "${((1 - model.fractionRemaining) * 100).roundToInt()}%",
                t50 = clock(model.t50),
                t90 = clock(model.t90),
                last = clock(model.cleared),
            )

            is Elimination.ZeroOrder -> row(
                halfLife = "zero-order",
                inBody = amount(model.gramsRemaining, "g"),
                eliminated = "${((1 - model.fractionRemaining) * 100).roundToInt()}%",
                t50 = clock(model.t50),
                t90 = clock(model.t90),
                last = clock(model.modelZero),
            )

            Elimination.Unknown ->
                if (isLive) "| $name | — | — | — | — | — | — |" else "| $name | — | — | — | — |"
        }
    }

    private fun halfLifeSuffix(group: EliminationGroup): String = when (val model = group.model) {
        is Elimination.FirstOrder -> " (t½ ${durationHM(model.halfLifeMinutes * 60)})"
        is Elimination.ZeroOrder -> " (zero-order)"
        Elimination.Unknown -> ""
    }

    private fun decayRows(group: EliminationGroup, clock: (Instant) -> String): List<String> {
        val series = group.curve
        val peak = series.maxOrNull() ?: return emptyList()
        if (!(peak > 0)) return emptyList()
        val rows = 6
        val lines = mutableListOf<String>()
        for (i in 0..rows) {
            val frac = i.toDouble() / rows
            val idx = min(series.size - 1, (frac * (series.size - 1)).roundToInt())
            val minutes = frac * group.horizonMinutes
            val value = series[idx]
            val pct = (value / peak * 100).roundToInt()
            val tod = pad(durationHM(minutes * 60), 10)
            val amt = padLeft(amount(value, group.unit), 9)
            val whenStr = clock(group.groupStart.plusSeconds((minutes * 60).roundToLong()))
            lines.add("t+$tod$amt  ${padLeft("$pct%", 4)} left   $whenStr")
        }
        return lines
    }

    private fun amount(value: Double, unit: String): String {
        val s = if (value >= 100) String.format(Locale.ROOT, "%.0f", value) else String.format(Locale.ROOT, "%.1f", value)
        return "$s $unit"
    }

    private fun pad(s: String, n: Int): String =
        if (s.length >= n) s else s + " ".repeat(n - s.length)

    private fun padLeft(s: String, n: Int): String =
        if (s.length >= n) s else " ".repeat(n - s.length) + s

    private fun seconds(from: Instant, to: Instant): Double =
        Duration.between(from, to).toMillis() / 1000.0
}

// MARK: - Elimination model

/** How a substance is clearing (combined across its doses). */
sealed interface Elimination {
    /** First-order (Bateman) clearance from a known half-life. */
    data class FirstOrder(
        val halfLifeMinutes: Double,
        val amountRemaining: Double,
        val fractionRemaining: Double,
        val t50: Instant,
        val t90: Instant,
        val cleared: Instant,
    ) : Elimination

    /** Zero-order (capacity-limited) clearance — alcohol. */
    data class ZeroOrder(
        val gramsRemaining: Double,
        val fractionRemaining: Double,
        val t50: Instant,
        val t90: Instant,
        val modelZero: Instant,
    ) : Elimination

    /** No half-life data — elimination can't be modeled for this substance. */
    data object Unknown : Elimination
}

/** Per-dose first-order kinetics contributing to a substance's combined curve. */
data class FirstOrderDose(val offsetMinutes: Double, val amount: Double, val ke: Double, val ka: Double)

/** Per-dose zero-order (alcohol) contribution. */
data class ZeroOrderDose(val offsetMinutes: Double, val doseMg: Double)

/**
 * The pure elimination arithmetic — the combined body-load curve and its
 * crossings — separated from the data-layer resolution so it is testable on the
 * JVM exactly as upstream's `SessionStateExport` private helpers are.
 */
object EliminationModel {
    const val ELIM_SAMPLES: Int = 100

    fun firstOrderGroup(
        name: String,
        halfLifeMinutes: Double,
        doses: List<FirstOrderDose>,
        unit: String,
        groupStart: Instant,
        doseCount: Int,
        now: Instant,
    ): SessionStateExport.EliminationGroup {
        val totalDosed = doses.sumOf { it.amount }
        fun body(global: Double): Double = doses.sumOf { dose ->
            val t = global - dose.offsetMinutes
            if (t >= 0) dose.amount * PKModel.fractionRemainingInBody(t, dose.ke, dose.ka) else 0.0
        }
        val maxOffset = doses.maxOfOrNull { it.offsetMinutes } ?: 0.0
        val cap = maxOffset + halfLifeMinutes * 20
        val cross = crossings(::body, totalDosed, doubleArrayOf(0.5, 0.1, 0.03), cap)
        val horizon = max(cross[2], 1.0)
        val nowGlobal = max(0.0, Duration.between(groupStart, now).toMillis() / 60_000.0)
        val remaining = body(nowGlobal)
        return SessionStateExport.EliminationGroup(
            name = name,
            doseCount = doseCount,
            model = Elimination.FirstOrder(
                halfLifeMinutes = halfLifeMinutes,
                amountRemaining = remaining,
                fractionRemaining = if (totalDosed > 0) min(1.0, remaining / totalDosed) else 0.0,
                t50 = groupStart.plusSeconds((cross[0] * 60).roundToLong()),
                t90 = groupStart.plusSeconds((cross[1] * 60).roundToLong()),
                cleared = groupStart.plusSeconds((cross[2] * 60).roundToLong()),
            ),
            curve = sampleCurve(::body, horizon),
            unit = unit,
            horizonMinutes = horizon,
            groupStart = groupStart,
        )
    }

    fun zeroOrderGroup(
        name: String,
        kinetics: PKModel.ZeroOrderKinetics,
        doses: List<ZeroOrderDose>,
        groupStart: Instant,
        doseCount: Int,
        now: Instant,
    ): SessionStateExport.EliminationGroup {
        val totalGrams = doses.sumOf { it.doseMg } / 1_000.0
        fun body(global: Double): Double = doses.sumOf { dose ->
            val t = global - dose.offsetMinutes
            if (t >= 0) PKModel.zeroOrderBodyContent(dose.doseMg, t, kinetics) / 1_000.0 else 0.0
        }
        val maxOffset = doses.maxOfOrNull { it.offsetMinutes } ?: 0.0
        val cap = maxOffset + totalGrams * 1_000 / kinetics.vmaxMgPerMin + 180
        val cross = crossings(::body, totalGrams, doubleArrayOf(0.5, 0.1, 0.02), cap)
        val horizon = max(cross[2], 1.0)
        val nowGlobal = max(0.0, Duration.between(groupStart, now).toMillis() / 60_000.0)
        val remaining = body(nowGlobal)
        return SessionStateExport.EliminationGroup(
            name = name,
            doseCount = doseCount,
            model = Elimination.ZeroOrder(
                gramsRemaining = remaining,
                fractionRemaining = if (totalGrams > 0) min(1.0, remaining / totalGrams) else 0.0,
                t50 = groupStart.plusSeconds((cross[0] * 60).roundToLong()),
                t90 = groupStart.plusSeconds((cross[1] * 60).roundToLong()),
                modelZero = groupStart.plusSeconds((cross[2] * 60).roundToLong()),
            ),
            curve = sampleCurve(::body, horizon),
            unit = "g",
            horizonMinutes = horizon,
            groupStart = groupStart,
        )
    }

    fun unknown(name: String, unit: String, doseCount: Int, groupStart: Instant): SessionStateExport.EliminationGroup =
        SessionStateExport.EliminationGroup(
            name = name,
            doseCount = doseCount,
            model = Elimination.Unknown,
            curve = emptyList(),
            unit = unit,
            horizonMinutes = 1.0,
            groupStart = groupStart,
        )

    /**
     * Global-time crossings: for each `target` fraction of `total`, the first
     * minute *after the combined peak* at which body-load falls to that level.
     */
    private fun crossings(body: (Double) -> Double, total: Double, targets: DoubleArray, cap: Double): DoubleArray {
        if (!(total > 0) || !(cap > 0)) return DoubleArray(targets.size) { cap }
        val step = max(1.0, cap / 4_000)
        var peakGlobal = 0.0
        var peakValue = body(0.0)
        var global = 0.0
        while (global <= cap) {
            val value = body(global)
            if (value > peakValue) {
                peakValue = value
                peakGlobal = global
            }
            global += step
        }
        return targets.map { target ->
            val threshold = target * total
            var t = peakGlobal
            while (t <= cap) {
                if (body(t) <= threshold) return@map t
                t += step
            }
            cap
        }.toDoubleArray()
    }

    private fun sampleCurve(body: (Double) -> Double, horizon: Double): List<Double> =
        (0..ELIM_SAMPLES).map { i -> body(i.toDouble() / ELIM_SAMPLES * horizon) }
}
