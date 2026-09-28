package glass.kagerou.piru.engine

import glass.kagerou.piru.model.P3Color
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * Ported from `Shared/SessionVitals.swift` in the iOS app.
 *
 * Everything here is pure arithmetic over samples that a platform reader has already
 * fetched: no HealthKit, no Health Connect, no view state, so the whole file is unit
 * testable on the JVM. The Android reader fills [HeartRateSample] / [BloodPressureReading]
 * and hands them over; nothing below knows where they came from.
 *
 * Times are [Instant]s, as everywhere else in the engine, and the window constants are
 * held in the seconds the Swift source spells them in (`TimeInterval`) rather than in the
 * minutes the pharmacology code uses — these are wall-clock windows over sensor samples,
 * not modeled durations, and the two must not be conflated.
 */

/** Fixed vitals colors — deliberately distinct from any substance color and legible in both light and dark. */
object VitalsPalette {
    /** HR is a warm crimson. */
    val heart = P3Color(red = 0.898, green = 0.290, blue = 0.310)

    /** BP is a calm blue. */
    val bloodPressure = P3Color(red = 0.231, green = 0.490, blue = 0.847)
}

/**
 * A single heart-rate sample read from the platform health store (a wrist sensor records
 * these roughly every five minutes).
 */
data class HeartRateSample(
    val date: Instant,
    val bpm: Double,

    /**
     * Whether the sample falls inside a recorded workout.
     *
     * A run raises heart rate by more than any dose in the library, so a workout inside a
     * dose's window would otherwise be read as that dose's response — the single largest
     * confound in the whole overlay. [VitalsAnalysis.doseResponses] drops these; the
     * session [VitalsAnalysis.summary] keeps them, because it describes the session rather
     * than the doses.
     */
    val isWorkout: Boolean = false,
)

/**
 * A single blood-pressure reading (systolic/diastolic) read from the platform health store.
 *
 * BP is sparse — there is no continuous BP sensor, so these are occasional spot checks,
 * not a curve. Nothing in this file computes from them; they are carried so the overlay
 * can plot them.
 */
data class BloodPressureReading(
    val date: Instant,
    val systolic: Double,
    val diastolic: Double,
)

/**
 * Physiological vitals for one session's time window, overlaid on the timeline.
 *
 * Empty lists mean "no data" — the UI renders **nothing** rather than an empty axis, so a
 * day without a wearable (or without a BP reading) shows no vitals chrome at all.
 */
data class SessionVitals(
    val heartRate: List<HeartRateSample>,
    val bloodPressure: List<BloodPressureReading>,

    /** The user's resting heart rate near the session, for the "elevated vs resting" summary. */
    val restingHeartRate: Double?,
) {
    val hasHeartRate: Boolean get() = heartRate.isNotEmpty()
    val hasBloodPressure: Boolean get() = bloodPressure.isNotEmpty()
    val isEmpty: Boolean get() = heartRate.isEmpty() && bloodPressure.isEmpty()

    companion object {
        val empty = SessionVitals(emptyList(), emptyList(), null)
    }
}

/**
 * Which way a dose's heart rate moved.
 *
 * [UNCHANGED] means the move stayed inside [VitalsAnalysis.noiseFloor] — the wander a wrist
 * sensor shows from posture and movement alone — so the numbers are shown without claiming
 * a response.
 */
enum class HRDirection { ROSE, FELL, UNCHANGED }

/**
 * One dose's heart-rate response, shown as an inline chip on its entry row: HR at the
 * moment of dosing → the extreme reached within the response window.
 */
data class DoseHRResponse(
    /** HR nearest the dose time (bpm, rounded). */
    val atDose: Int,

    /**
     * The furthest HR from [atDose] within the response window (bpm, rounded) — the peak
     * for a dose that raised heart rate, the nadir for one that lowered it.
     *
     * A beta blocker's whole signal is the drop, so the extreme is chosen by distance from
     * baseline rather than by magnitude.
     */
    val extreme: Int,

    /** `extreme - atDose` (negative when heart rate fell). */
    val delta: Int,

    val direction: HRDirection,

    /**
     * Other substances still in their come-up across this dose's window — whatever the
     * numbers show is theirs as much as this dose's.
     */
    val confounders: List<String>,

    /** In-window bpm values, for the row's mini sparkline. */
    val sparkline: List<Double>,
) {
    val isConfounded: Boolean get() = confounders.isNotEmpty()
}

/**
 * One dose handed to the batch analysis: when it was taken, how long its own effects take
 * to arrive, and what it is called.
 */
data class HRDoseWindow(
    val id: UUID,
    val at: Instant,
    val substance: String,

    /**
     * Minutes from the dose to the end of its modeled peak — the stretch a cardiovascular
     * response would land in. Null for a substance with no modeled duration.
     */
    val peakEndMinutes: Double? = null,

    /**
     * Minutes from the dose to the end of its modeled come-up — the stretch in which this
     * dose is still *changing* the body, so a later dose landing inside it can't claim its
     * own reading cleanly. Null for a substance with no modeled duration.
     */
    val comeUpEndMinutes: Double? = null,
)

/** Session-wide heart-rate summary for the Summary card. */
data class HRSummary(
    val average: Int,
    val peak: Int,

    /** Resting HR baseline, if known (for "elevated vs your resting N bpm"). */
    val resting: Int?,

    /**
     * Minutes of the window spent in a recorded workout. Both figures above include it —
     * they describe the session as it happened — so this is what stops a post-run peak
     * from reading as something a dose did.
     */
    val workoutMinutes: Int = 0,
)

/**
 * Pure heart-rate analysis over sample arrays — no platform health API, no view state, so
 * it's unit-testable.
 *
 * Ported from `VitalsAnalysis` in `Shared/SessionVitals.swift`. Constant names are kept as
 * the Swift source spells them, the way the rest of the ported engine objects do, so a
 * reader can diff the two side by side.
 */
object VitalsAnalysis {

    /** Response window for a dose whose substance has no modeled duration. */
    const val responseWindow: Double = 45 * 60.0

    /**
     * Floor on a duration-derived window, so a fast substance's few-minute peak still spans
     * enough of a ~5-minute-cadence sample series to describe anything.
     *
     * Numerically equal to [responseWindow] and deliberately a separate name: one is the
     * fallback for a substance we cannot model, the other is a floor under one we can.
     */
    const val minimumWindow: Double = 30 * 60.0

    /**
     * Cap on a duration-derived window: past three hours the reading stops being *this*
     * dose's response and becomes the afternoon's.
     */
    const val maximumWindow: Double = 3 * 3_600.0

    /** How far *before* the dose to accept a sample as the "at dose" baseline. */
    const val baselineLookback: Double = 20 * 60.0

    /**
     * Swing a wrist sensor shows at rest from posture, movement and breathing alone. A
     * delta under this is reported as measured but not as a response.
     */
    const val noiseFloor: Int = 8

    /** Fewest in-window samples that can describe a change rather than assert one. */
    const val minimumSamples: Int = 2

    /**
     * Shortest first-to-last span of in-window samples worth reading. Two samples a minute
     * apart describe that minute, not the dose.
     */
    const val minimumSpan: Double = 10 * 60.0

    /**
     * Longest gap between samples that still counts as measured time in the average — and,
     * through [workoutSpan], in the workout minutes.
     *
     * Past it the watch simply wasn't looking, and stretching two readings across the hole
     * would let a single pre-nap sample outweigh an hour of the session.
     */
    const val maximumSampleGap: Double = 15 * 60.0

    /**
     * Per-dose heart-rate responses for a whole session, keyed by dose id.
     *
     * Two things the single-dose call can't do on its own, and the reason the session goes
     * through here: each dose's window is cut short at the **next** dose, so one rise is
     * never counted as three separate responses to a re-dose; and a dose taken while
     * something else is still coming up carries that substance in
     * [DoseHRResponse.confounders] rather than quietly taking credit for it.
     *
     * Workout samples are dropped here — unlike in [summary], which describes the session
     * itself. A per-dose chip is meant to be the *drug's* change, and a run in the same
     * window is not.
     */
    fun doseResponses(doses: List<HRDoseWindow>, samples: List<HeartRateSample>): Map<UUID, DoseHRResponse> {
        if (samples.isEmpty() || doses.isEmpty()) return emptyMap()
        val readable = samples.filter { !it.isWorkout }
        val ordered = doses.sortedBy { it.at }
        val out = LinkedHashMap<UUID, DoseHRResponse>()
        for ((index, dose) in ordered.withIndex()) {
            val modeled = dose.peakEndMinutes?.let { min(max(it * 60.0, minimumWindow), maximumWindow) }
            var end = dose.at.addingSeconds(modeled ?: responseWindow)
            if (index + 1 < ordered.size) {
                end = minOf(end, ordered[index + 1].at)
            }
            // Same-substance re-doses are left out: the reading is still that substance's,
            // and naming it as its own confounder says nothing the row doesn't already show.
            //
            // `equals(ignoreCase = true)` is the locale-independent comparison upstream's
            // `caseInsensitiveCompare` performs — deliberately not a `lowercase()` pair, which
            // would fold a Turkish dotless i into the wrong letter.
            val confounders = ordered.subList(0, index)
                .filter { earlier ->
                    !earlier.substance.equals(dose.substance, ignoreCase = true) &&
                        (earlier.comeUpEndMinutes?.let { earlier.at.addingSeconds(it * 60.0) > dose.at } ?: false)
                }
                .map { it.substance }
            val response = response(dose.at, end, readable, uniqued(confounders))
            if (response != null) out[dose.id] = response
        }
        return out
    }

    /**
     * The heart-rate response to a single dose over a fixed window, or null when the samples
     * in it are too few or too bunched to describe one.
     *
     * The single-dose entry point carries no confounders: it is handed one dose and no
     * knowledge of the others, so [doseResponses] is the one that can name them.
     */
    fun doseResponse(
        doseAt: Instant,
        samples: List<HeartRateSample>,
        window: Double = responseWindow,
    ): DoseHRResponse? = response(
        doseAt = doseAt,
        end = doseAt.addingSeconds(window),
        samples = samples.filter { !it.isWorkout },
        confounders = emptyList(),
    )

    private fun response(
        doseAt: Instant,
        end: Instant,
        samples: List<HeartRateSample>,
        confounders: List<String>,
    ): DoseHRResponse? {
        if (end <= doseAt) return null
        val inWindow = samples
            .filter { it.date >= doseAt && it.date <= end }
            .sortedBy { it.date }
        val first = inWindow.firstOrNull() ?: return null
        val last = inWindow.last()
        if (inWindow.size < minimumSamples) return null
        if (secondsBetween(first.date, last.date) < minimumSpan) return null

        // Baseline = the most recent sample just before the dose (within `baselineLookback`);
        // fall back to the first in-window sample when nothing precedes the dose.
        val baselineWindowStart = doseAt.addingSeconds(-baselineLookback)
        val before = samples
            .filter { it.date <= doseAt && it.date >= baselineWindowStart }
            .maxByOrNull { it.date }
        val atSample = before ?: first

        val at = atSample.bpm.roundToInt()
        // Furthest from baseline in either direction, so a dose that lowered heart rate
        // reports its nadir instead of the meaningless highest sample of the window.
        val extremeSample = inWindow.maxByOrNull { abs(it.bpm - atSample.bpm) }!!
        val extreme = extremeSample.bpm.roundToInt()
        val delta = extreme - at
        val direction = when {
            abs(delta) < noiseFloor -> HRDirection.UNCHANGED
            delta > 0 -> HRDirection.ROSE
            else -> HRDirection.FELL
        }
        return DoseHRResponse(
            atDose = at,
            extreme = extreme,
            delta = delta,
            direction = direction,
            confounders = confounders,
            sparkline = inWindow.map { it.bpm },
        )
    }

    /**
     * Time covered by workout samples, measured the same gap-capped way the average is
     * weighted: only a stretch *between two* workout samples counts, so one stray flagged
     * sample contributes nothing.
     */
    private fun workoutSpan(samples: List<HeartRateSample>): Double =
        samples.zipWithNext()
            .filter { (previous, next) -> previous.isWorkout && next.isWorkout }
            .sumOf { (previous, next) -> min(secondsBetween(previous.date, next.date), maximumSampleGap) }

    /** Order-preserving dedup, so "overlaps Caffeine, Caffeine" can't happen. */
    private fun uniqued(names: List<String>): List<String> {
        val seen = HashSet<String>()
        return names.filter { seen.add(it.lowercase()) }
    }

    /**
     * The session-wide heart-rate summary over `[start, end]`, or null if no samples fall
     * inside.
     *
     * The average is **time-weighted**. A wrist sensor bursts to seconds-apart samples
     * during movement and idles at five-minute intervals at rest, so counting every sample
     * equally reports the average of whatever the user was doing most actively rather than
     * the average of the session. The peak is the plain maximum: a real beat the watch
     * recorded is worth reporting even when a staircase, not the dose, caused it.
     *
     * Workout samples stay in both figures — this describes the session, not the doses —
     * and are reported separately as [HRSummary.workoutMinutes]. The per-dose chips are the
     * place a workout must not appear, and [doseResponses] drops it there.
     */
    fun summary(
        from: Instant,
        to: Instant,
        heartRate: List<HeartRateSample>,
        restingHeartRate: Double?,
    ): HRSummary? {
        val inWindow = heartRate
            .filter { it.date >= from && it.date <= to }
            .sortedBy { it.date }
        val firstSample = inWindow.firstOrNull() ?: return null
        val peak = inWindow.maxOf { it.bpm }.roundToInt()
        val resting = restingHeartRate?.roundToInt()

        var weighted = 0.0
        var weight = 0.0
        for (index in 0 until inWindow.size - 1) {
            val previous = inWindow[index]
            val next = inWindow[index + 1]
            val span = min(secondsBetween(previous.date, next.date), maximumSampleGap)
            weighted += (previous.bpm + next.bpm) / 2 * span
            weight += span
        }
        val average = if (weight > 0) weighted / weight else firstSample.bpm
        return HRSummary(
            average = average.roundToInt(),
            peak = peak,
            resting = resting,
            workoutMinutes = (workoutSpan(inWindow) / 60.0).roundToInt(),
        )
    }
}

/**
 * `Date.timeIntervalSince(_:)`: the signed seconds from [from] to [to].
 *
 * Built on millisecond arithmetic because that is the resolution every other engine file
 * uses for an [Instant] span, and it is coarser than the sample timestamps the platform
 * readers hand over.
 */
private fun secondsBetween(from: Instant, to: Instant): Double =
    Duration.between(from, to).toMillis() / 1_000.0

/** `Date.addingTimeInterval(_:)`, for the window edges this file derives from its constants. */
private fun Instant.addingSeconds(seconds: Double): Instant =
    plusMillis((seconds * 1_000.0).roundToLong())
