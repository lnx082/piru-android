package glass.kagerou.piru.engine.report

import glass.kagerou.piru.engine.ActiveSubstanceState
import glass.kagerou.piru.engine.InteractionSeverity
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The small, language-neutral pieces the two report renderers share — the
 * Shulgin and Worked rating tables, the phase ladder, and the plain-text
 * helpers. Ported from the scattered `nonisolated static` helpers upstream:
 * `ShulginScale`, `WorkedScale`, `DosePhaseProgressBar.Phase`,
 * `TripReport.{tPlus,structureLine,signed,moodEnergyCell,cell}`, and
 * `TimeInterval.durationHM`.
 *
 * Everything here is pure and English on purpose — the reports are the portable
 * interchange form, like the JSON and PsyLog exports, so a rating or a phase is
 * spelled the same regardless of the screen language.
 */

// MARK: - Rating scales

/** The Shulgin `+` ladder (PiHKAL, 1991), `0…4`. */
object ShulginScale {
    /** `0…4` → ±, +, ++, +++, ++++; null when the value is outside the ladder. */
    fun glyph(level: Int): String? = when (level) {
        0 -> "±"
        1 -> "+"
        2 -> "++"
        3 -> "+++"
        4 -> "++++"
        else -> null
    }
}

/**
 * Whether a dose did its job, `-1…+1`. A separate axis from [ShulginScale]: that
 * one measures how strong an experience is, this one whether a medication
 * performed as it usually does.
 */
object WorkedScale {
    /** `-1…+1` → the export word, or null when the value is outside the scale. */
    fun exportWord(level: Int): String? = when (level) {
        -1 -> "less than usual"
        0 -> "about right"
        1 -> "more than usual"
        else -> null
    }
}

// MARK: - Phase

/** Subjective effect phase, mirroring the timeline's progress-bar ladder. */
enum class SessionPhase {
    ONSET,
    COMEUP,
    PEAK,
    OFFSET,
    AFTER;

    /** English name — the reports are the portable form, so a phase is never localized. */
    val englishName: String
        get() = when (this) {
            ONSET -> "Onset"
            COMEUP -> "Come-up"
            PEAK -> "Peak"
            OFFSET -> "Offset"
            AFTER -> "Afterglow"
        }
}

/**
 * The phase a dose is in [elapsedMinutes] past it, off the same boundaries the
 * timeline draws — so a report and the curve it was written against cannot
 * disagree. Ported from `DosePhaseProgressBar.phase`.
 */
fun phase(state: ActiveSubstanceState, elapsedMinutes: Double): SessionPhase = when {
    elapsedMinutes <= state.onsetEndMinutes -> SessionPhase.ONSET
    elapsedMinutes <= state.comeupEndMinutes -> SessionPhase.COMEUP
    elapsedMinutes <= state.peakEndMinutes -> SessionPhase.PEAK
    elapsedMinutes <= state.offsetEndMinutes -> SessionPhase.OFFSET
    else -> SessionPhase.AFTER
}

// MARK: - Interaction severity

/** The report's per-severity glyph, matching the on-screen danger ladder. */
val InteractionSeverity.exportSymbol: String
    get() = when (this) {
        InteractionSeverity.CAUTION -> "⚠️"
        InteractionSeverity.UNSAFE -> "🔶"
        InteractionSeverity.DANGEROUS -> "🛑"
    }

// MARK: - Plain-text helpers

/**
 * A duration in seconds as "2h 8m" / "3h" / "45m"; negative clamps to zero.
 * Ported from `TimeInterval.durationHM`.
 */
fun durationHM(seconds: Double): String {
    val totalMinutes = max(0, (seconds / 60).toInt())
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

/**
 * `T+1:20` style offset from [sessionStart]; minutes zero-padded, a leading
 * minus sign for a note placed before the first dose.
 */
fun tPlus(sessionStart: Instant, at: Instant): String {
    val seconds = Duration.between(sessionStart, at).toMillis() / 1000.0
    val totalMinutes = (abs(seconds) / 60).roundToInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val sign = if (seconds < 0 && totalMinutes > 0) "−" else ""
    return String.format(Locale.ROOT, "T%s+%d:%02d", sign, hours, minutes)
}

/** `+2` / `−1` / `0`, with the minus sign upstream uses (U+2212, not a hyphen). */
fun signed(value: Int): String = when {
    value > 0 -> "+$value"
    value < 0 -> "−${abs(value)}"
    else -> "0"
}

/**
 * The structured part of a note as one line — `++ · about right · mood +2 ·
 * energy −1 · social +3 · ♥ 84` — every piece optional, nothing invented. Empty
 * when the note has no structure.
 */
fun structureLine(
    shulgin: Int?,
    mood: Int?,
    energy: Int?,
    social: Int? = null,
    worked: Int? = null,
    heartRate: Int?,
): String {
    val parts = mutableListOf<String>()
    shulgin?.let { ShulginScale.glyph(it) }?.let(parts::add)
    worked?.let { WorkedScale.exportWord(it) }?.let(parts::add)
    mood?.let { parts.add("mood ${signed(it)}") }
    energy?.let { parts.add("energy ${signed(it)}") }
    social?.let { parts.add("social ${signed(it)}") }
    heartRate?.let { parts.add("♥ $it") }
    return parts.joinToString(" · ")
}

/**
 * The mood/energy cell of the timeline table — `+2 / 0` — with `–` for a side
 * not captured and empty when neither was.
 */
fun moodEnergyCell(mood: Int?, energy: Int?, heartRate: Int? = null): String {
    var cell = ""
    if (mood != null || energy != null) {
        cell = (mood?.let(::signed) ?: "–") + " / " + (energy?.let(::signed) ?: "–")
    }
    if (heartRate != null) {
        cell += (if (cell.isEmpty()) "" else " · ") + "♥ $heartRate"
    }
    return cell
}

/**
 * Text made safe for one Markdown table cell: pipes escaped, line breaks kept as
 * `<br>` so a multi-paragraph note stays one row.
 */
fun cell(text: String): String =
    text.trim()
        .replace("|", "\\|")
        .replace("\r\n", "\n")
        .split("\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("<br>")
