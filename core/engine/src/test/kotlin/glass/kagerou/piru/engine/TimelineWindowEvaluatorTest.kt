package glass.kagerou.piru.engine

import glass.kagerou.piru.model.P3Color
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import org.junit.jupiter.api.Test

/**
 * Ported from `PiruTests/TimelineWindowEvaluatorTests.swift`.
 *
 * The continuous ribbon's window evaluator must be *exactly* the session curve
 * math, just sampled through an arbitrary window: within a dose's activity window
 * the value equals the session's Hill-merged intensity, adjacent tiles agree at
 * their shared boundary, and a dose contributes iff its activity window
 * intersects the evaluated window.
 */
class TimelineWindowEvaluatorTest {

    private val t0: Instant = Instant.ofEpochMilli(1_000_000_000)
    private val tint = P3Color(red = 0.929, green = 0.439, blue = 0.660)

    /** A dose with a typical oral profile, mirroring the curve-model tests. */
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
    )

    private fun minutes(value: Double): Long = (value * 60_000).toLong()

    // MARK: - Consistency with the session curve

    @Test
    fun `Single dose window evaluation matches the session curve at the same instants`() {
        val s = dose()
        val extent = TimelineCurveModel.curveExtent(s)

        // The window sits fully inside the dose's activity, so per-dose extent
        // clipping never bites and the values must equal the session's stacked
        // evaluation exactly.
        (extent >= 360) shouldBe true
        val start = t0
        val end = t0.plusMillis(minutes(360.0))
        val plot = TimelineWindowEvaluator.evaluate(listOf(s), start, end, sampleCount = 61)

        plot.series.size shouldBe 1
        val values = plot.series.first().values
        values.size shouldBe 61

        val spanMillis = Duration.between(start, end).toMillis()
        for ((index, value) in values.withIndex()) {
            val sampleDate = start.plusMillis((spanMillis.toDouble() * index / (61 - 1)).toLong())
            val globalMinutes = Duration.between(t0, sampleDate).toMillis() / 60_000.0
            val expected = TimelineCurveModel.stackedIntensity(globalMinutes, listOf(s), t0)
            (abs(value - expected) < 1e-12) shouldBe true
        }

        (abs(plot.peakValue - values.max()) < 1e-12) shouldBe true
    }

    @Test
    fun `Redose group merges through the same Hill link as the session graph`() {
        val first = dose()
        val redose = dose(timestamp = t0.plusMillis(minutes(90.0)))
        val group = listOf(first, redose)

        val start = t0
        val end = t0.plusMillis(minutes(300.0))
        val plot = TimelineWindowEvaluator.evaluate(group, start, end, sampleCount = 31)

        // Same substance and route give one merged series, like the session lanes.
        plot.series.size shouldBe 1
        val values = plot.series.first().values
        val spanMillis = Duration.between(start, end).toMillis()
        for ((index, value) in values.withIndex()) {
            val sampleDate = start.plusMillis((spanMillis.toDouble() * index / (31 - 1)).toLong())
            val globalMinutes = Duration.between(t0, sampleDate).toMillis() / 60_000.0
            val expected = TimelineCurveModel.stackedIntensity(globalMinutes, group, t0)
            // Both doses are within extent across this window, so the clipped
            // superposition and the session's agree exactly.
            (abs(value - expected) < 1e-12) shouldBe true
        }
    }

    // MARK: - Tile-boundary continuity

    @Test
    fun `Samples are continuous across a tile boundary`() {
        val s = dose()
        val boundary = t0.plusMillis(minutes(180.0))
        val left = TimelineWindowEvaluator.evaluate(listOf(s), t0, boundary, sampleCount = 61)
        val right = TimelineWindowEvaluator.evaluate(
            listOf(s), boundary, t0.plusMillis(minutes(360.0)), sampleCount = 61,
        )

        val leftEdge = left.series.first().values.last()
        val rightEdge = right.series.first().values.first()
        // Both tiles sample the exact boundary instant, and the evaluated value is
        // a pure function of absolute time, so they agree to the bit.
        (abs(leftEdge - rightEdge) < 1e-12) shouldBe true
        // And the boundary lands mid-curve — this is not two zeros agreeing.
        (leftEdge > 0.1) shouldBe true
    }

    @Test
    fun `A dose ending before the boundary reads zero on both sides of it`() {
        val s = dose()
        val extent = TimelineCurveModel.curveExtent(s)
        val boundary = t0.plusMillis(minutes(extent + 60))

        val left = TimelineWindowEvaluator.evaluate(listOf(s), t0, boundary, sampleCount = 61)
        val right = TimelineWindowEvaluator.evaluate(
            listOf(s), boundary, boundary.plusMillis(minutes(360.0)), sampleCount = 61,
        )

        // The left tile still carries the series — the dose is active earlier in
        // the window — but its boundary sample is zero: clipped at the extent,
        // the same place the session's drawn path lands on the baseline.
        left.series.first().values.last() shouldBe 0.0
        // The right tile's activity window does not intersect, so there is no series.
        right.series shouldBe emptyList()
    }

    // MARK: - Activity-window intersection

    @Test
    fun `Doses contribute iff their activity window intersects the window`() {
        val extent = TimelineCurveModel.curveExtent(dose())

        val before = dose(name = "Beforezine", timestamp = t0)
        val during = dose(name = "Duringol", timestamp = t0.plusMillis(minutes(extent + 200)))
        val after = dose(name = "Afterium", timestamp = t0.plusMillis(minutes(extent + 2_000)))
        val start = t0.plusMillis(minutes(extent + 100))
        val end = t0.plusMillis(minutes(extent + 400))

        val plot = TimelineWindowEvaluator.evaluate(listOf(before, during, after), start, end)
        plot.series.map { it.name } shouldBe listOf("Duringol")
        (plot.series.first().values.any { it > 0.1 }) shouldBe true

        // A dose logged *before* the window whose curve reaches into it still
        // contributes — continuity is about activity, not the log timestamp.
        val straddling = TimelineWindowEvaluator.evaluate(
            listOf(before),
            t0.plusMillis(minutes(100.0)),
            t0.plusMillis(minutes(200.0)),
        )
        straddling.series.size shouldBe 1
        (straddling.series.first().values.all { it > 0 }) shouldBe true

        // Its dose tick stays out of windows that do not contain the timestamp.
        straddling.series.first().doseTimes shouldBe emptyList()
    }

    @Test
    fun `Relevant-dose culling matches the intersection rule`() {
        val s = dose()
        val extent = TimelineCurveModel.curveExtent(s)

        val interval = TimelineWindowEvaluator.activityInterval(s)
        interval.start shouldBe t0
        (abs(interval.durationSeconds - extent * 60) < 1e-9) shouldBe true

        // Just-touching windows count as intersecting; disjoint ones do not.
        val after = t0.plusMillis(minutes(extent))
        TimelineWindowEvaluator.relevantDoses(
            listOf(s), after.plusMillis(60_000), after.plusMillis(3_600_000),
        ) shouldBe emptyList()
        TimelineWindowEvaluator.relevantDoses(
            listOf(s), t0.plusMillis(-3_600_000), t0.plusMillis(-1_000),
        ) shouldBe emptyList()
        TimelineWindowEvaluator.relevantDoses(
            listOf(s), t0.plusMillis(minutes(100.0)), t0.plusMillis(minutes(200.0)),
        ).size shouldBe 1
    }
}
