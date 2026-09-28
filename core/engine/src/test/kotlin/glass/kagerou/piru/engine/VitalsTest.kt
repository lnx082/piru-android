package glass.kagerou.piru.engine

import glass.kagerou.piru.model.P3Color
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The vitals analysis, pinned against the iOS suite for `Shared/SessionVitals.swift`.
 *
 * The numbers are the upstream assertions wherever upstream had one, so a divergence in the
 * port shows up here rather than as a subtly different chip in the app. The cases upstream
 * does not spell out — the gap cap changing an answer, the order-preserving confounder dedup,
 * the exact edge of the noise floor — exist because those are the rules a transcription is
 * most likely to lose.
 */
class VitalsTest {

    /** The instant every case is built relative to — epoch, as upstream's reference date is 0. */
    private val base: Instant = Instant.ofEpochSecond(0)

    private fun at(minutes: Double): Instant = base.plusMillis((minutes * 60_000.0).toLong())

    private fun sample(minutes: Double, bpm: Double, workout: Boolean = false): HeartRateSample =
        HeartRateSample(date = at(minutes), bpm = bpm, isWorkout = workout)

    private fun samples(vararg samples: HeartRateSample): List<HeartRateSample> = samples.toList()

    private fun window(id: UUID, atMinutes: Double, substance: String, peak: Double?, comeUp: Double?) =
        HRDoseWindow(
            id = id,
            at = at(atMinutes),
            substance = substance,
            peakEndMinutes = peak,
            comeUpEndMinutes = comeUp,
        )

    private fun caffeine(
        id: UUID = UUID.randomUUID(),
        atMinutes: Double = 0.0,
        peak: Double = 120.0,
        comeUp: Double = 30.0,
    ) =
        window(id, atMinutes, "Caffeine", peak = peak, comeUp = comeUp)

    // MARK: - doseResponse

    @Test
    fun `at-dose baseline is the sample just before the dose, and the extreme is the furthest from it`() {
        // -5m → 66 (baseline), +10m → 80, +30m → 96 (extreme), +60m → 88 (outside the 45m window)
        val hr = samples(sample(-5.0, 66.0), sample(10.0, 80.0), sample(30.0, 96.0), sample(60.0, 88.0))
        val response = VitalsAnalysis.doseResponse(base, hr)!!
        response.atDose shouldBe 66
        response.extreme shouldBe 96
        response.delta shouldBe 30
        response.direction shouldBe HRDirection.ROSE
        response.sparkline shouldBe listOf(80.0, 96.0)
        response.confounders.shouldBeEmpty()
    }

    @Test
    fun `nil when no samples fall in the response window`() {
        val hr = samples(sample(-30.0, 70.0), sample(90.0, 100.0))
        VitalsAnalysis.doseResponse(base, hr) shouldBe null
    }

    @Test
    fun `empty samples give no response`() {
        VitalsAnalysis.doseResponse(base, emptyList()) shouldBe null
    }

    @Test
    fun `baseline falls back to the first in-window sample when nothing precedes the dose`() {
        val response = VitalsAnalysis.doseResponse(base, samples(sample(5.0, 74.0), sample(20.0, 92.0)))!!
        response.atDose shouldBe 74
        response.extreme shouldBe 92
    }

    @Test
    fun `baseline ignores pre-dose samples older than the lookback`() {
        // -40m is older than the 20m lookback, so the baseline is the first in-window sample (72).
        val hr = samples(sample(-40.0, 55.0), sample(10.0, 72.0), sample(25.0, 100.0))
        VitalsAnalysis.doseResponse(base, hr)!!.atDose shouldBe 72
    }

    @Test
    fun `a pre-dose sample inside the lookback beats the first in-window sample`() {
        val hr = samples(sample(-19.0, 58.0), sample(10.0, 72.0), sample(25.0, 100.0))
        val response = VitalsAnalysis.doseResponse(base, hr)!!
        response.atDose shouldBe 58
        response.delta shouldBe 42
    }

    @Test
    fun `a dose that lowers heart rate reports its nadir, not the window maximum`() {
        val hr = samples(sample(-2.0, 100.0), sample(10.0, 80.0), sample(30.0, 70.0))
        val response = VitalsAnalysis.doseResponse(base, hr)!!
        response.atDose shouldBe 100
        response.extreme shouldBe 70
        response.delta shouldBe -30
        response.direction shouldBe HRDirection.FELL
    }

    @Test
    fun `a move inside the noise floor is reported as unchanged`() {
        val response =
            VitalsAnalysis.doseResponse(base, samples(sample(-2.0, 66.0), sample(10.0, 70.0), sample(30.0, 69.0)))!!
        response.delta shouldBe 4
        response.direction shouldBe HRDirection.UNCHANGED
    }

    @Test
    fun `a fall inside the noise floor is unchanged too, not a fall`() {
        // The floor is on the size of the move, not its sign.
        val response =
            VitalsAnalysis.doseResponse(base, samples(sample(-2.0, 74.0), sample(10.0, 67.0), sample(30.0, 70.0)))!!
        response.delta shouldBe -7
        response.direction shouldBe HRDirection.UNCHANGED
    }

    @Test
    fun `a move of exactly the noise floor is a change, since the floor is strict`() {
        val rose =
            VitalsAnalysis.doseResponse(base, samples(sample(-2.0, 60.0), sample(10.0, 68.0), sample(30.0, 66.0)))!!
        rose.delta shouldBe 8
        rose.direction shouldBe HRDirection.ROSE

        val fell =
            VitalsAnalysis.doseResponse(base, samples(sample(-2.0, 76.0), sample(10.0, 68.0), sample(30.0, 70.0)))!!
        fell.delta shouldBe -8
        fell.direction shouldBe HRDirection.FELL
    }

    @Test
    fun `a single in-window sample describes nothing, so no response`() {
        // The pre-dose sample is a baseline only; it is not an in-window sample.
        VitalsAnalysis.doseResponse(base, samples(sample(-2.0, 66.0), sample(10.0, 110.0))) shouldBe null
    }

    @Test
    fun `samples bunched into less than the minimum span are rejected`() {
        // Two samples 4 minutes apart describe those 4 minutes, not the dose.
        val hr = samples(sample(-2.0, 66.0), sample(5.0, 100.0), sample(9.0, 104.0))
        VitalsAnalysis.doseResponse(base, hr) shouldBe null
    }

    @Test
    fun `a first-to-last span of exactly the minimum span is enough`() {
        val hr = samples(sample(-2.0, 66.0), sample(0.0, 70.0), sample(10.0, 80.0))
        VitalsAnalysis.doseResponse(base, hr)!!.sparkline shouldBe listOf(70.0, 80.0)
    }

    @Test
    fun `workout samples are excluded from a dose's response`() {
        val hr = samples(
            sample(-2.0, 66.0),
            sample(10.0, 70.0),
            sample(20.0, 148.0, workout = true),
            sample(25.0, 152.0, workout = true),
            sample(35.0, 71.0),
        )
        val response = VitalsAnalysis.doseResponse(base, hr)!!
        response.extreme shouldBe 71
        response.direction shouldBe HRDirection.UNCHANGED
        response.sparkline shouldBe listOf(70.0, 71.0)
    }

    @Test
    fun `a window that is all workout leaves no response at all`() {
        val hr = samples(
            sample(-2.0, 66.0),
            sample(10.0, 150.0, workout = true),
            sample(25.0, 158.0, workout = true),
            sample(40.0, 145.0, workout = true),
        )
        VitalsAnalysis.doseResponse(base, hr) shouldBe null
    }

    @Test
    fun `a custom window overrides the default response window`() {
        val hr = samples(sample(10.0, 80.0), sample(20.0, 96.0), sample(100.0, 60.0))
        VitalsAnalysis.doseResponse(base, hr, window = 30 * 60.0)!!.sparkline shouldBe listOf(80.0, 96.0)
    }

    @Test
    fun `a window that does not advance past the dose has no response`() {
        val hr = samples(sample(10.0, 80.0), sample(20.0, 96.0))
        VitalsAnalysis.doseResponse(base, hr, window = 0.0) shouldBe null
    }

    @Test
    fun `beats are rounded to whole numbers`() {
        val hr = samples(sample(-2.0, 65.6), sample(10.0, 79.4), sample(30.0, 96.4))
        val response = VitalsAnalysis.doseResponse(base, hr)!!
        response.atDose shouldBe 66
        response.extreme shouldBe 96
        response.delta shouldBe 30
    }

    // MARK: - doseResponses (session-wide)

    @Test
    fun `a dose's window stops at the next dose, so one rise is not counted twice`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val doses = listOf(
            caffeine(first, atMinutes = 0.0),
            caffeine(second, atMinutes = 40.0),
        )
        // The whole climb happens after the second dose; the first dose's window ends at +40m.
        val hr = samples(
            sample(-2.0, 62.0), sample(10.0, 63.0), sample(30.0, 64.0), sample(50.0, 90.0), sample(70.0, 104.0),
        )
        val out = VitalsAnalysis.doseResponses(doses, hr)
        out[first]!!.delta shouldBe 2
        out[first]!!.direction shouldBe HRDirection.UNCHANGED
        out[second]!!.delta shouldBe 40
        out[second]!!.direction shouldBe HRDirection.ROSE
    }

    @Test
    fun `a dose taken while another substance is still coming up names it as a confounder`() {
        val mdma = UUID.randomUUID()
        val laterCaffeine = UUID.randomUUID()
        val doses = listOf(
            window(mdma, 0.0, "MDMA", peak = 180.0, comeUp = 60.0),
            caffeine(laterCaffeine, atMinutes = 30.0),
        )
        val hr = samples(
            sample(-2.0, 62.0), sample(10.0, 70.0), sample(25.0, 84.0), sample(40.0, 92.0), sample(70.0, 110.0),
        )
        val out = VitalsAnalysis.doseResponses(doses, hr)
        out[mdma]!!.confounders.shouldBeEmpty()
        out[laterCaffeine]!!.confounders shouldBe listOf("MDMA")
        out[laterCaffeine]!!.isConfounded shouldBe true
    }

    @Test
    fun `a re-dose of the same substance is not its own confounder`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val doses = listOf(
            caffeine(first, atMinutes = 0.0, comeUp = 60.0),
            window(second, 30.0, "caffeine", peak = 120.0, comeUp = 60.0),
        )
        val hr = samples(sample(-2.0, 62.0), sample(10.0, 70.0), sample(40.0, 92.0), sample(70.0, 110.0))
        VitalsAnalysis.doseResponses(doses, hr)[second]!!.confounders.shouldBeEmpty()
    }

    @Test
    fun `an earlier dose that has finished coming up no longer confounds`() {
        val early = UUID.randomUUID()
        val late = UUID.randomUUID()
        val doses = listOf(
            caffeine(early, atMinutes = 0.0, comeUp = 20.0),
            window(late, 60.0, "MDMA", peak = 180.0, comeUp = 60.0),
        )
        val hr = samples(
            sample(-2.0, 62.0), sample(10.0, 66.0), sample(40.0, 68.0), sample(75.0, 96.0), sample(100.0, 104.0),
        )
        VitalsAnalysis.doseResponses(doses, hr)[late]!!.confounders.shouldBeEmpty()
    }

    @Test
    fun `the same confounder named twice is listed once, in first-mention order`() {
        val mdma = UUID.randomUUID()
        val reDose = UUID.randomUUID()
        val target = UUID.randomUUID()
        val doses = listOf(
            window(mdma, 0.0, "MDMA", peak = 240.0, comeUp = 240.0),
            window(reDose, 10.0, "mdma", peak = 240.0, comeUp = 240.0),
            caffeine(target, atMinutes = 30.0, peak = 60.0, comeUp = 30.0),
        )
        val hr = samples(
            sample(-2.0, 62.0), sample(10.0, 70.0), sample(25.0, 84.0), sample(40.0, 92.0), sample(70.0, 110.0),
        )
        VitalsAnalysis.doseResponses(doses, hr)[target]!!.confounders shouldBe listOf("MDMA")
    }

    @Test
    fun `two different substances both coming up are both named`() {
        val mdma = UUID.randomUUID()
        val caffeineDose = UUID.randomUUID()
        val target = UUID.randomUUID()
        val doses = listOf(
            window(mdma, 0.0, "MDMA", peak = 240.0, comeUp = 240.0),
            caffeine(caffeineDose, atMinutes = 10.0, comeUp = 120.0),
            window(target, 30.0, "Ethanol", peak = 120.0, comeUp = 30.0),
        )
        val hr = samples(
            sample(-2.0, 62.0), sample(10.0, 70.0), sample(25.0, 84.0), sample(50.0, 92.0), sample(70.0, 110.0),
        )
        VitalsAnalysis.doseResponses(doses, hr)[target]!!.confounders shouldBe listOf("MDMA", "Caffeine")
    }

    @Test
    fun `a confounder with no modeled come-up never confounds`() {
        val unknown = UUID.randomUUID()
        val target = UUID.randomUUID()
        val doses = listOf(
            window(unknown, 0.0, "Mystery", peak = null, comeUp = null),
            caffeine(target, atMinutes = 30.0, peak = 60.0, comeUp = 30.0),
        )
        val hr = samples(
            sample(-2.0, 62.0), sample(10.0, 70.0), sample(25.0, 84.0), sample(40.0, 92.0), sample(70.0, 110.0),
        )
        VitalsAnalysis.doseResponses(doses, hr)[target]!!.confounders.shouldBeEmpty()
    }

    @Test
    fun `doses are read in time order, whatever order they are handed over in`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val doses = listOf(
            caffeine(second, atMinutes = 40.0),
            caffeine(first, atMinutes = 0.0),
        )
        val hr = samples(
            sample(-2.0, 62.0), sample(10.0, 63.0), sample(30.0, 64.0), sample(50.0, 90.0), sample(70.0, 104.0),
        )
        val out = VitalsAnalysis.doseResponses(doses, hr)
        out[first]!!.delta shouldBe 2
        out[second]!!.delta shouldBe 40
    }

    @Test
    fun `the window comes from the dose's own peak, clamped to the floor and the cap`() {
        // A 5-minute peak is floored to 30m, so the +25m sample is still in window.
        val fast = UUID.randomUUID()
        val fastOut = VitalsAnalysis.doseResponses(
            listOf(window(fast, 0.0, "Cocaine", peak = 5.0, comeUp = 5.0)),
            samples(sample(-2.0, 66.0), sample(10.0, 90.0), sample(25.0, 108.0)),
        )
        fastOut[fast]!!.extreme shouldBe 108

        // An 8-hour peak is capped at 3h, so the +200m sample falls outside.
        val slow = UUID.randomUUID()
        val slowOut = VitalsAnalysis.doseResponses(
            listOf(window(slow, 0.0, "Lisdexamfetamine", peak = 480.0, comeUp = 90.0)),
            samples(sample(-2.0, 66.0), sample(60.0, 88.0), sample(170.0, 92.0), sample(200.0, 140.0)),
        )
        slowOut[slow]!!.extreme shouldBe 92
    }

    @Test
    fun `a dose with no modeled duration falls back to the default window`() {
        val unknown = UUID.randomUUID()
        // The 45-minute default window holds +40m and excludes +50m.
        val out = VitalsAnalysis.doseResponses(
            listOf(window(unknown, 0.0, "Mystery", peak = null, comeUp = null)),
            samples(sample(-2.0, 66.0), sample(10.0, 90.0), sample(40.0, 130.0), sample(50.0, 140.0)),
        )
        out[unknown]!!.extreme shouldBe 130
    }

    @Test
    fun `a dose whose window holds too little drops out of the map entirely`() {
        val lonely = UUID.randomUUID()
        val out = VitalsAnalysis.doseResponses(
            listOf(window(lonely, 0.0, "Caffeine", peak = 5.0, comeUp = 5.0)),
            samples(sample(-2.0, 66.0), sample(5.0, 90.0), sample(9.0, 94.0)),
        )
        out.containsKey(lonely) shouldBe false
        out.size shouldBe 0
    }

    @Test
    fun `no doses or no samples gives an empty map`() {
        VitalsAnalysis.doseResponses(emptyList(), samples(sample(10.0, 80.0), sample(20.0, 96.0))).size shouldBe 0
        VitalsAnalysis.doseResponses(listOf(caffeine(atMinutes = 0.0, peak = 60.0)), emptyList()).size shouldBe 0
    }

    @Test
    fun `workout samples are dropped from a batch response, leaving it no response at all`() {
        val dose = UUID.randomUUID()
        val out = VitalsAnalysis.doseResponses(
            listOf(caffeine(dose, atMinutes = 0.0, peak = 60.0)),
            samples(
                sample(0.0, 66.0),
                sample(10.0, 150.0, workout = true),
                sample(25.0, 158.0, workout = true),
                sample(40.0, 145.0, workout = true),
            ),
        )
        out.size shouldBe 0
    }

    // MARK: - summary

    @Test
    fun `averages and peaks the samples in the window, rounds, and carries resting`() {
        val hr = samples(sample(0.0, 60.0), sample(20.0, 80.0), sample(40.0, 100.0))
        val summary = VitalsAnalysis.summary(base, at(60.0), hr, restingHeartRate = 62.4)!!
        summary.average shouldBe 80
        summary.peak shouldBe 100
        summary.resting shouldBe 62
    }

    @Test
    fun `excludes samples outside the window`() {
        val hr = samples(sample(-10.0, 200.0), sample(10.0, 70.0), sample(50.0, 90.0), sample(120.0, 200.0))
        val summary = VitalsAnalysis.summary(base, at(60.0), hr, restingHeartRate = null)!!
        summary.average shouldBe 80
        summary.peak shouldBe 90
        summary.resting shouldBe null
    }

    @Test
    fun `nil when no samples fall in the window`() {
        VitalsAnalysis.summary(base, at(60.0), emptyList(), restingHeartRate = 60.0) shouldBe null
    }

    @Test
    fun `a single sample is its own average, since no stretch was measured`() {
        // weight stays 0 with nothing to pair, so the average degenerates to the one reading
        // rather than to a division by zero.
        val summary = VitalsAnalysis.summary(base, at(60.0), samples(sample(10.0, 71.0)), restingHeartRate = null)!!
        summary.average shouldBe 71
        summary.peak shouldBe 71
        summary.workoutMinutes shouldBe 0
    }

    @Test
    fun `the average is time-weighted, not sample-weighted`() {
        // A ~55-minute stretch at 60 bpm, then four samples a minute apart at 120.
        val hr = samples(
            sample(0.0, 60.0), sample(55.0, 60.0), sample(56.0, 120.0),
            sample(57.0, 120.0), sample(58.0, 120.0), sample(59.0, 120.0),
        )
        val summary = VitalsAnalysis.summary(base, at(60.0), hr, restingHeartRate = null)!!
        // Sample-weighted would be 100. Time-weighted, with the idle stretch capped at its 15
        // minutes, the four burst samples cannot outvote the hour: 81000 / 1140 = 71.
        summary.average shouldBe 71
        summary.peak shouldBe 120 // a real recorded beat is still reported
    }

    @Test
    fun `a gap longer than the cap counts as the cap, whichever side it is measured from`() {
        val hr = samples(sample(0.0, 60.0), sample(120.0, 60.0), sample(240.0, 120.0), sample(255.0, 120.0))
        val summary = VitalsAnalysis.summary(base, at(300.0), hr, restingHeartRate = null)!!
        // Each stretch counts as its capped 15 minutes at its mean rate: (54000 + 81000 + 108000) / 2700 = 90.
        summary.average shouldBe 90
    }

    @Test
    fun `the summary keeps workout samples and reports how long the workout ran`() {
        val hr = samples(
            sample(0.0, 60.0),
            sample(10.0, 62.0),
            sample(20.0, 150.0, workout = true),
            sample(30.0, 155.0, workout = true),
            sample(40.0, 64.0),
        )
        val summary = VitalsAnalysis.summary(base, at(60.0), hr, restingHeartRate = null)!!
        summary.peak shouldBe 155 // the session's real maximum, workout and all
        summary.workoutMinutes shouldBe 10 // only the stretch *between* two workout samples
    }

    @Test
    fun `an isolated workout sample contributes no workout minutes`() {
        val hr = samples(
            sample(0.0, 60.0),
            sample(10.0, 62.0, workout = true),
            sample(20.0, 90.0),
            sample(30.0, 64.0),
        )
        VitalsAnalysis.summary(base, at(60.0), hr, restingHeartRate = null)!!.workoutMinutes shouldBe 0
    }

    @Test
    fun `workout minutes are capped by the sample gap exactly as the average is`() {
        // Two workout samples an hour apart count as 15 minutes, not 60.
        val hr = samples(sample(0.0, 120.0, workout = true), sample(60.0, 122.0, workout = true), sample(70.0, 64.0))
        VitalsAnalysis.summary(base, at(120.0), hr, restingHeartRate = null)!!.workoutMinutes shouldBe 15
    }

    @Test
    fun `no workout means no workout minutes`() {
        val hr = samples(sample(0.0, 60.0), sample(20.0, 80.0), sample(40.0, 100.0))
        VitalsAnalysis.summary(base, at(60.0), hr, restingHeartRate = null)!!.workoutMinutes shouldBe 0
    }

    @Test
    fun `the window bounds are inclusive`() {
        val hr = samples(sample(0.0, 70.0), sample(60.0, 80.0))
        val summary = VitalsAnalysis.summary(base, at(60.0), hr, restingHeartRate = null)!!
        summary.peak shouldBe 80
        summary.average shouldBe 75
    }

    @Test
    fun `unsorted samples are sorted before they are paired`() {
        // The trapezoid depends on adjacency, so arrival order must not change the answer.
        val ordered = samples(sample(0.0, 60.0), sample(10.0, 120.0), sample(20.0, 60.0))
        val shuffled = listOf(ordered[2], ordered[0], ordered[1])
        VitalsAnalysis.summary(base, at(30.0), shuffled, null)!!.average shouldBe
            VitalsAnalysis.summary(base, at(30.0), ordered, null)!!.average
        VitalsAnalysis.summary(base, at(30.0), ordered, null)!!.average shouldBe 90
    }

    // MARK: - SessionVitals

    @Test
    fun `SessionVitals emptiness reflects both series`() {
        SessionVitals.empty.isEmpty shouldBe true
        val withHr = SessionVitals(
            heartRate = listOf(sample(0.0, 70.0)),
            bloodPressure = emptyList(),
            restingHeartRate = null,
        )
        withHr.isEmpty shouldBe false
        withHr.hasHeartRate shouldBe true
        withHr.hasBloodPressure shouldBe false
    }

    @Test
    fun `blood pressure is carried without being computed from`() {
        val withBp = SessionVitals(
            heartRate = emptyList(),
            bloodPressure = listOf(BloodPressureReading(date = base, systolic = 118.0, diastolic = 76.0)),
            restingHeartRate = null,
        )
        withBp.isEmpty shouldBe false
        withBp.hasBloodPressure shouldBe true
        withBp.hasHeartRate shouldBe false
    }

    @Test
    fun `VitalsPalette keeps vitals off the substance palette`() {
        VitalsPalette.heart shouldBe P3Color(red = 0.898, green = 0.290, blue = 0.310)
        VitalsPalette.bloodPressure shouldBe P3Color(red = 0.231, green = 0.490, blue = 0.847)
    }
}
