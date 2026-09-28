package glass.kagerou.piru.engine

import glass.kagerou.piru.model.P3Color
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.math.abs
import org.junit.jupiter.api.Test

/**
 * Ported from `PiruTests/TimelineCurveModelTests.swift`.
 *
 * Characterization tests for the extracted timeline curve math: they pin the
 * *current* rendering semantics — the split flat-top effect shape, the
 * `Hill(Σ magnitude·bell)` redose merge, gamma compression, tail scanning and
 * the lane layout — so the pure functions cannot drift silently under the view.
 */
class TimelineCurveModelTest {

    private val t0: Instant = Instant.ofEpochSecond(0)
    private val tint = P3Color(red = 0.929, green = 0.439, blue = 0.660)

    /**
     * A dose with a typical oral profile: 30 min onset, come-up to 60, peak to
     * 180, offset to 360. The magnitude defaults to 0.7 — inside Hill's
     * near-linear region, like a single common dose.
     */
    private fun dose(
        name: String = "Testine",
        timestamp: Instant = t0,
        amount: Double = 20.0,
        route: String = "oral",
        onsetEnd: Double = 30.0,
        comeupEnd: Double = 60.0,
        peakEnd: Double = 180.0,
        offsetEnd: Double = 360.0,
        total: Double = 360.0,
        magnitude: Double = 0.7,
        tachyphylaxis: Double = 0.0,
    ) = ActiveSubstanceState(
        substanceName = name,
        tint = tint,
        doseTimestamp = timestamp,
        amount = amount,
        unit = "mg",
        route = route,
        onsetEndMinutes = onsetEnd,
        comeupEndMinutes = comeupEnd,
        peakEndMinutes = peakEnd,
        offsetEndMinutes = offsetEnd,
        afterglowEndMinutes = null,
        totalMinutes = total,
        doseIntensity = minOf(magnitude, 1.0),
        doseMagnitude = magnitude,
        tachyphylaxis = tachyphylaxis,
    )

    private fun ramp(from: Double, through: Double, by: Double): List<Double> {
        val out = mutableListOf<Double>()
        var v = from
        while (v <= through) {
            out += v
            v += by
        }
        return out
    }

    // MARK: - Single-dose curve shape

    @Test
    fun `Single-dose curve rises then falls with its peak after the onset`() {
        val s = dose()
        val extent = TimelineCurveModel.curveExtent(s)

        var peakValue = -1.0
        var peakTime = 0.0
        for (t in ramp(0.0, extent, 1.0)) {
            val v = TimelineCurveModel.intensity(t, s)
            if (v > peakValue) {
                peakValue = v
                peakTime = t
            }
        }
        (peakValue > 0.99) shouldBe true
        (peakTime > s.onsetEndMinutes) shouldBe true
        (peakTime < s.offsetEndMinutes) shouldBe true
        (TimelineCurveModel.intensity(extent, s) < peakValue * 0.1) shouldBe true

        // The rising shoulder is monotone non-decreasing up to the crest.
        var previous = -1.0
        for (t in ramp(0.0, 80.0, 10.0)) {
            val v = TimelineCurveModel.intensity(t, s)
            (v >= previous) shouldBe true
            previous = v
        }
    }

    @Test
    fun `Intensity is non-negative, bounded, and zero before the dose`() {
        val s = dose()
        val extent = TimelineCurveModel.curveExtent(s)
        for (t in ramp(0.0, extent, 2.0)) {
            val v = TimelineCurveModel.intensity(t, s)
            (v >= 0) shouldBe true
            (v <= 1) shouldBe true
        }
        TimelineCurveModel.intensity(-5.0, s) shouldBe 0.0
    }

    @Test
    fun `Descending limb tapers monotonically to near zero at the curve extent`() {
        val s = dose()
        val extent = TimelineCurveModel.curveExtent(s)
        // The extent reaches past the stated offset so the tail eases onto the
        // axis instead of being clipped.
        (extent >= s.offsetEndMinutes) shouldBe true

        var previous = Double.MAX_VALUE
        for (t in ramp(s.peakEndMinutes, extent, 5.0)) {
            val v = TimelineCurveModel.intensity(t, s)
            (v <= previous + 1e-12) shouldBe true
            previous = v
        }
        (TimelineCurveModel.intensity(extent, s) < 0.05) shouldBe true
    }

    @Test
    fun `Tachyphylaxis crashes the descending limb but leaves onset and peak untouched`() {
        val plain = dose()
        val releaser = dose(tachyphylaxis = 0.8)

        for (t in ramp(0.0, plain.peakEndMinutes, 10.0)) {
            TimelineCurveModel.intensity(t, plain) shouldBe TimelineCurveModel.intensity(t, releaser)
        }

        val mid = (plain.peakEndMinutes + plain.totalMinutes) / 2
        val plainMid = TimelineCurveModel.intensity(mid, plain)
        val releaserMid = TimelineCurveModel.intensity(mid, releaser)
        (releaserMid < plainMid) shouldBe true

        val plainEnd = TimelineCurveModel.intensity(plain.totalMinutes, plain)
        val releaserEnd = TimelineCurveModel.intensity(releaser.totalMinutes, releaser)
        (abs(releaserEnd - plainEnd * 0.2) < 1e-12) shouldBe true
    }

    // MARK: - Phase boundaries

    @Test
    fun `Phase boundaries stay ordered onset, come-up, peak, extent`() {
        val explicit = dose()
        val comeup = TimelineCurveModel.effectiveComeupEnd(
            explicit, explicit.onsetEndMinutes, explicit.peakEndMinutes,
        )
        comeup shouldBe explicit.comeupEndMinutes
        (explicit.onsetEndMinutes <= comeup) shouldBe true
        (comeup <= explicit.peakEndMinutes) shouldBe true
        (explicit.peakEndMinutes <= TimelineCurveModel.curveExtent(explicit)) shouldBe true
    }

    @Test
    fun `Missing come-up phase synthesizes a climb between onset and peak`() {
        val s = dose(onsetEnd = 30.0, comeupEnd = 30.0, peakEnd = 130.0)
        val comeup = TimelineCurveModel.effectiveComeupEnd(s, 30.0, 130.0)
        (comeup > 30) shouldBe true
        (comeup < 130) shouldBe true

        // A slow, absorbed onset earns the synthesized climb: 60% of the onset,
        // floored at 8 min, capped at half the onset-to-peak gap.
        val slow = dose(onsetEnd = 45.0, comeupEnd = 45.0, peakEnd = 145.0)
        TimelineCurveModel.effectiveComeupEnd(slow, 45.0, 145.0) shouldBe 45.0 + 27.0

        // A fast insufflated onset stays quick — the floor never exceeds the
        // onset itself. This used to assert `2 + 8`: a two-minute onset was given
        // a ten-minute climb, which drew an absorption shoulder on routes that
        // have no absorption phase at all.
        val fast = dose(onsetEnd = 2.0, comeupEnd = 2.0, peakEnd = 32.0)
        TimelineCurveModel.effectiveComeupEnd(fast, 2.0, 32.0) shouldBe 2.0 + 2.0
    }

    @Test
    fun `Crest is a dome, not a flat lid, across a long peak band`() {
        // Short come-up, long peak — the shape that drew a trapezoid: a
        // near-vertical rise into an exactly flat top.
        val s = dose(onsetEnd = 0.5, comeupEnd = 2.0, peakEnd = 122.0, offsetEnd = 332.0)
        val samples = ramp(0.0, 332.0, 0.1).map { TimelineCurveModel.effectShape(it, s) }
        val peak = samples.max()

        // The maximum is still 1, so nothing downstream has to renormalize.
        (abs(peak - 1) < 1e-3) shouldBe true
        // A flat lid would leave hundreds of bit-identical maxima; a dome touches
        // its maximum essentially once.
        val atMaximum = samples.count { peak - it < 1e-6 }
        (atMaximum < 40) shouldBe true
        // Still recognizably a plateau: mid-crest sits within the sag budget.
        (TimelineCurveModel.effectShape(60.0, s) > 1 - TimelineCurveModel.EffectCurveParams.DOME_SAG) shouldBe true
    }

    @Test
    fun `Effect shape is one smooth arc, monotone each side of a single crest`() {
        // The old three-piece curve decelerated to a dead stop at the crest's
        // left edge, then climbed again to the true maximum. One monotone rise
        // and one monotone fall around a single argmax is exactly the property
        // that forbids it.
        val profiles = listOf(
            dose(onsetEnd = 5.0, comeupEnd = 12.0, peakEnd = 34.0, offsetEnd = 108.0),
            dose(),
            dose(onsetEnd = 40.0, comeupEnd = 75.0, peakEnd = 210.0, offsetEnd = 285.0),
            dose(onsetEnd = 25.0, comeupEnd = 55.0, peakEnd = 320.0, offsetEnd = 500.0),
        )
        for (s in profiles) {
            val extent = TimelineCurveModel.curveExtent(s)
            val samples = ramp(0.0, extent, 0.25).map { TimelineCurveModel.effectShape(it, s) }
            val crest = samples.indexOf(samples.max())
            for (i in 1..crest) (samples[i] >= samples[i - 1] - 1e-9) shouldBe true
            for (i in (crest + 1) until samples.size) {
                (samples[i] <= samples[i - 1] + 1e-9) shouldBe true
            }
        }
    }

    @Test
    fun `Effect shape passes its phase anchors`() {
        val s = dose(onsetEnd = 40.0, comeupEnd = 75.0, peakEnd = 210.0, offsetEnd = 285.0)
        val foot = TimelineCurveModel.effectShape(40.0, s)
        val comeupTop = TimelineCurveModel.effectShape(75.0, s)
        val peakEdge = TimelineCurveModel.effectShape(210.0, s)
        val tail = TimelineCurveModel.effectShape(285.0, s)

        (abs(foot - TimelineCurveModel.EffectCurveParams.FOOT_LO) < 0.02) shouldBe true
        (comeupTop > 0.85) shouldBe true
        (peakEdge > 0.85) shouldBe true
        (abs(tail - TimelineCurveModel.EffectCurveParams.TAIL_LO) < 0.03) shouldBe true
        (TimelineCurveModel.curveExtent(s) >= 285) shouldBe true
    }

    @Test
    fun `Phase-range spreads widen the curve without breaking its anchors`() {
        val tight = dose(onsetEnd = 40.0, comeupEnd = 75.0, peakEnd = 210.0, offsetEnd = 285.0)
        val spread = ActiveSubstanceState(
            substanceName = "Testine", tint = tint, doseTimestamp = t0,
            amount = 20.0, unit = "mg", route = "oral",
            onsetEndMinutes = 40.0, comeupEndMinutes = 75.0, peakEndMinutes = 210.0,
            offsetEndMinutes = 285.0, afterglowEndMinutes = null, totalMinutes = 400.0,
            doseIntensity = 0.7, doseMagnitude = 0.7,
            comeupSpreadMinutes = 30.0, peakSpreadMinutes = 120.0, offsetSpreadMinutes = 90.0,
        )
        // The offset spread extends the tail landing: the spread curve still
        // carries real effect where the tight one has already landed.
        (TimelineCurveModel.effectShape(285.0, spread) >
            TimelineCurveModel.effectShape(285.0, tight) + 0.02) shouldBe true

        val extent = TimelineCurveModel.curveExtent(spread)
        (extent > TimelineCurveModel.curveExtent(tight)) shouldBe true
        (TimelineCurveModel.effectShape(extent, spread) < 0.05) shouldBe true
    }

    @Test
    fun `Visible extent trims a curve dwarfed by a taller peer`() {
        val s = dose(onsetEnd = 30.0, comeupEnd = 45.0, peakEnd = 120.0, offsetEnd = 300.0)
        val full = TimelineCurveModel.curveExtent(s)

        val alone = TimelineCurveModel.visibleExtent(s, s.doseMagnitude)
        (alone <= full) shouldBe true

        // Beside a peer fifty times taller it stops much earlier — the dead-axis case.
        val dwarfed = TimelineCurveModel.visibleExtent(s, s.doseMagnitude * 50)
        (dwarfed < alone) shouldBe true
        (dwarfed >= 1) shouldBe true
    }

    // MARK: - The Hill merge

    @Test
    fun `Hill link is zero at zero, half-saturated at EC50, and monotone below 1`() {
        TimelineCurveModel.hill(0.0) shouldBe 0.0
        (abs(TimelineCurveModel.hill(TimelineCurveModel.HILL_EC50) - 0.5) < 1e-12) shouldBe true

        var previous = -1.0
        for (magnitude in ramp(0.0, 10.0, 0.25)) {
            val v = TimelineCurveModel.hill(magnitude)
            (v > previous) shouldBe true
            (v < 1) shouldBe true
            previous = v
        }
    }

    @Test
    fun `Four 20 mg doses match one 80 mg dose through the Hill merge`() {
        // Four doses of magnitude 0.7 against one of magnitude 2.8 — the
        // documented superposition invariant: linear raw-dose sum, one
        // saturating link.
        val quad = (0 until 4).map { dose(amount = 20.0, magnitude = 0.7) }
        val single = listOf(dose(amount = 80.0, magnitude = 2.8))

        for (t in ramp(0.0, 400.0, 5.0)) {
            val stacked = TimelineCurveModel.stackedIntensity(t, quad, t0)
            val combined = TimelineCurveModel.stackedIntensity(t, single, t0)
            (abs(stacked - combined) < 1e-9) shouldBe true
        }
    }

    @Test
    fun `Stacked intensity of well-separated doses keeps distinct humps`() {
        val group = listOf(dose(), dose(timestamp = t0.plusMillis(12 * 3_600_000)))
        fun merged(t: Double) = TimelineCurveModel.stackedIntensity(t, group, t0)

        val firstCrest = merged(120.0)
        val valley = merged(550.0)
        val secondCrest = merged(720.0 + 120.0)
        (valley < firstCrest * 0.2) shouldBe true
        (valley < secondCrest * 0.2) shouldBe true
    }

    @Test
    fun `Same substance and route stack into one group while different routes split`() {
        val doses = listOf(
            dose(name = "MDMA", route = "oral"),
            dose(name = "mdma", timestamp = t0.plusMillis(3_600_000), route = "Oral"),
            dose(name = "MDMA", timestamp = t0.plusMillis(7_200_000), route = "insufflated"),
            dose(name = "Ketamine", timestamp = t0.plusMillis(9_000_000), route = "oral"),
        )
        val groups = TimelineCurveModel.stackedGroups(doses)
        groups.size shouldBe 3
        groups[0].size shouldBe 2
        (groups[0].all { it.substanceName.lowercase() == "mdma" }) shouldBe true
        groups[1].size shouldBe 1
        groups[1][0].route shouldBe "insufflated"
        groups[2][0].substanceName shouldBe "Ketamine"
    }

    @Test
    fun `Heights follow the Hill link with a non-zero floor`() {
        val s = dose(magnitude = 0.7)
        TimelineCurveModel.heightScale(s, listOf(s), emptyMap()) shouldBe TimelineCurveModel.hill(0.7)

        val nothing = dose(magnitude = 0.0)
        TimelineCurveModel.heightScale(nothing, listOf(nothing), emptyMap()) shouldBe 0.0001
    }

    // MARK: - Amplitude compression

    @Test
    fun `Gamma compression is monotone, clamped, and lifts the low end`() {
        TimelineCurveModel.compressedAmplitude(0.0) shouldBe 0.0
        TimelineCurveModel.compressedAmplitude(1.0) shouldBe 1.0
        TimelineCurveModel.compressedAmplitude(-0.5) shouldBe 0.0
        TimelineCurveModel.compressedAmplitude(1.5) shouldBe 1.0

        var previous = -1.0
        for (amplitude in ramp(0.0, 1.0, 0.05)) {
            val v = TimelineCurveModel.compressedAmplitude(amplitude)
            // More raw intensity never renders lower...
            (v >= previous) shouldBe true
            // ...and a faint dose is lifted, never crushed.
            (v >= amplitude) shouldBe true
            previous = v
        }
        (TimelineCurveModel.compressedAmplitude(0.1) > 0.3) shouldBe true
    }

    // MARK: - The derived model

    @Test
    fun `Empty input produces the benign flat model`() {
        val now = Instant.ofEpochMilli(1_700_000_000_000)
        val derived = TimelineCurveModel.computeDerived(
            substances = emptyList(), markers = emptyList(),
            stackRedoses = false, dayBounded = false, currentTime = now,
        )
        derived.earliestDose shouldBe now
        derived.maxDoseBySubstance shouldBe emptyMap()
        derived.stackedGroups shouldBe emptyList()
        derived.peakCurveValue shouldBe 1.0
        derived.yNormalization shouldBe 1.0
        derived.rawDataTail shouldBe 1.0
        derived.rawActivityTail shouldBe 1.0
    }

    @Test
    fun `Data tail reaches at least as far as the activity tail and respects the day bound`() {
        val s = dose()
        val derived = TimelineCurveModel.computeDerived(
            substances = listOf(s), markers = emptyList(),
            stackRedoses = false, dayBounded = false, currentTime = t0,
        )
        (derived.rawDataTail >= derived.rawActivityTail) shouldBe true
        (derived.rawActivityTail >= 1) shouldBe true
        derived.earliestDose shouldBe t0
        derived.maxDoseBySubstance["testine"] shouldBe 20.0

        // A long-acting dose cannot stretch a day-bounded axis past 24 h.
        val longActing = dose(peakEnd = 600.0, offsetEnd = 1_800.0, total = 1_800.0)
        val bounded = TimelineCurveModel.computeDerived(
            substances = listOf(longActing), markers = emptyList(),
            stackRedoses = false, dayBounded = true, currentTime = t0,
        )
        (bounded.rawDataTail <= 24 * 60) shouldBe true
    }

    @Test
    fun `Y-normalization maps the tallest curve toward full height capped at 20x`() {
        val faint = dose(magnitude = 0.05)
        val derived = TimelineCurveModel.computeDerived(
            substances = listOf(faint), markers = emptyList(),
            stackRedoses = false, dayBounded = false, currentTime = t0,
        )
        derived.peakCurveValue shouldBe TimelineCurveModel.hill(0.05)
        derived.yNormalization shouldBe minOf(1.0 / derived.peakCurveValue, 20.0)
    }

    // MARK: - Lane layout

    @Test
    fun `Redoses share a lane and lanes keep first-dose order`() {
        val doses = listOf(
            dose(name = "Caffeine"),
            dose(name = "Kratom", timestamp = t0.plusMillis(1_800_000)),
            dose(name = "caffeine", timestamp = t0.plusMillis(3_600_000)),
        )
        val lanes = TimelineCurveModel.laneGroups(doses)
        lanes.size shouldBe 2
        lanes[0].name shouldBe "Caffeine"
        lanes[0].doses.size shouldBe 2
        lanes[1].name shouldBe "Kratom"
        lanes[1].doses.size shouldBe 1
    }

    @Test
    fun `Marker-only substances get their own lanes, excluding curve substances`() {
        val lanes = TimelineCurveModel.laneGroups(listOf(dose(name = "Caffeine")))
        val markers = listOf(
            DoseMarker("Caffeine", t0, tint, 80.0, "mg"),
            DoseMarker("Melatonin", t0, P3Color(0.462, 0.660, 0.974), 0.3, "mg"),
            DoseMarker("melatonin", t0.plusMillis(600_000), P3Color(0.462, 0.660, 0.974), 0.3, "mg"),
        )
        val markerLanes = TimelineCurveModel.markerOnlyLanes(lanes, markers)
        markerLanes.size shouldBe 1
        markerLanes[0].name shouldBe "Melatonin"
        markerLanes[0].markers.size shouldBe 2
    }

    // MARK: - Tick layout

    @Test
    fun `Tick intervals are clean and yield about eight labels`() {
        TimelineCurveModel.intervalForSpan(60.0) shouldBe 15.0
        TimelineCurveModel.intervalForSpan(480.0) shouldBe 60.0
        TimelineCurveModel.intervalForSpan(1_440.0) shouldBe 240.0
        // Beyond every candidate, the interval clamps to a day.
        TimelineCurveModel.intervalForSpan(20_000.0) shouldBe 1_440.0
    }
}
