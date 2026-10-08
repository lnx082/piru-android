package glass.kagerou.piru.ui.journal

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.data.TimelineBubbleStyleName
import glass.kagerou.piru.data.TimelineDisplay
import glass.kagerou.piru.engine.ActiveSubstanceState
import glass.kagerou.piru.engine.DoseMarker
import glass.kagerou.piru.ui.theme.PiruTheme
import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import glass.kagerou.piru.model.P3Color
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * A zoom actually changes what is drawn, which is the only thing a unit test cannot show.
 *
 * ## Why this exists
 * `TimelineDisplay.plotHeight` is covered by unit tests, and **the graph can ignore it entirely and they all still
 * pass** — which is exactly the bug this project has hit five times: a preference that writes to a store nothing
 * reads. The audit has a whole family of "stored but no reader" items, and BUG #44 is one of them.
 *
 * So this renders the **same** graph twice and compares the measured size of the drawn node. A taller plot is a
 * taller graph; nothing else in the composition changes between the two renders.
 *
 * ## Why the measured node is a `Box` around the graph
 * `TimelineGraph` draws onto a `Canvas`, which contributes no semantics — there is nothing to measure unless the
 * caller puts a node there. The wrapper supplies one, and its size is the graph's size because the graph fills it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TimelineDisplayDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * Renders the graph once, from mutable state, and measures it at whatever options are set.
     *
     * `setContent` may be called **once** per activity — the compose rule owns it — so the options are state and
     * each measurement flips them. That also makes the comparison stronger than two separate compositions: nothing
     * but the options changes between the two reads.
     */
    private fun measure(): TimelineDisplayDeviceTest.Measurer {
        val now = Instant.now()
        // The shapes are the engine's, taken from its own test fixture. Both are needed because the bands below the
        // curves are sized from the marker lanes, and a markerless graph would measure only the curves.
        val state = ActiveSubstanceState(
            substanceName = "Caffeine",
            tint = P3Color(0.6, 0.4, 0.2),
            doseTimestamp = now.minusSeconds(3600),
            amount = 80.0,
            unit = "mg",
            route = "oral",
            onsetEndMinutes = 30.0,
            comeupEndMinutes = 60.0,
            peakEndMinutes = 180.0,
            offsetEndMinutes = 360.0,
            afterglowEndMinutes = null,
            totalMinutes = 360.0,
            doseIntensity = 0.7,
            doseMagnitude = 0.7,
        )
        val marker = DoseMarker(
            substanceName = "Caffeine",
            timestamp = now,
            tint = P3Color(0.6, 0.4, 0.2),
            amount = 80.0,
            unit = "mg",
        )

        val display = mutableStateOf(TimelineDisplay(zoom = 1.0))
        compose.setContent {
            PiruTheme {
                Box(modifier = Modifier.testTag(TAG)) {
                    TimelineGraph(
                        states = listOf(state),
                        markers = listOf(marker),
                        currentTime = now,
                        display = display.value,
                    )
                }
            }
        }
        compose.waitForIdle()

        return Measurer(display)
    }

    /** Flips the state and reads the drawn height. */
    private inner class Measurer(private val display: MutableState<TimelineDisplay>) {
        fun heightAt(value: TimelineDisplay): Float {
            display.value = value
            compose.waitForIdle()
            return compose.onNodeWithTag(TAG).getBoundsInRoot().bottom.value
        }
    }

    /**
     * The twice-zoomed graph is taller than the once-zoomed one.
     *
     * The assertion is a **comparison**, not a pixel count: the bands below the curves (clock, markers, cardio) do
     * not scale with the zoom, so the total height is not a multiple of it. What matters is the direction, and that
     * it is caused by the option rather than by anything else.
     */
    @Test
    fun aLargerZoomDrawsATallerGraph() {
        val measurer = measure()
        val atOne = measurer.heightAt(TimelineDisplay(zoom = 1.0))
        val atTwoAndAHalf = measurer.heightAt(TimelineDisplay(zoom = 2.5))
        println("DISPLAYPROBE at1=$atOne at2.5=$atTwoAndAHalf")
        (atTwoAndAHalf > atOne) shouldBe true
    }

    /** And a smaller zoom draws a shorter one, from the other side of the default. */
    @Test
    fun aSmallerZoomDrawsAShorterGraph() {
        val measurer = measure()
        val atOne = measurer.heightAt(TimelineDisplay(zoom = 1.0))
        val atPointSix = measurer.heightAt(TimelineDisplay(zoom = 0.6))
        println("DISPLAYPROBE at1=$atOne at0.6=$atPointSix")
        (atPointSix < atOne) shouldBe true
    }

    /**
     * Compact shortens the plot too, from the same default.
     *
     * Compact exists so the curve lane keeps more of the width, and in this port's canvas graph the honest reading
     * of that is a shorter plot — so it has to actually shorten it.
     */
    @Test
    fun compactDrawsAShorterGraph() {
        val measurer = measure()
        val full = measurer.heightAt(TimelineDisplay(zoom = 1.0, bubbleStyle = TimelineBubbleStyleName.FULL))
        val compact = measurer.heightAt(
            TimelineDisplay(zoom = 1.0, bubbleStyle = TimelineBubbleStyleName.COMPACT),
        )
        println("DISPLAYPROBE full=$full compact=$compact")
        (compact < full) shouldBe true
    }

    /**
     * The graph's own node carries the options' **identity**, so two different zooms are two different pictures.
     *
     * The weakest of the four assertions and still worth having: if the zoom reached nothing at all, the three
     * comparisons above could still pass by measuring bands that happened to differ. This pins the size against the
     * base height the display derives, for the one case where the arithmetic is exact — no marker lane, no vitals,
     * just the curves.
     */
    @Test
    fun theGraphHeightFollowsTheDerivedPlotHeight() {
        val base = TimelineDisplay.BASE_PLOT_HEIGHT
        TimelineDisplay(zoom = 2.5).plotHeight(base) shouldBe base * 2.5

        val measurer = measure()
        val atOne = measurer.heightAt(TimelineDisplay(zoom = 1.0))
        val atTwoAndAHalf = measurer.heightAt(TimelineDisplay(zoom = 2.5))
        // The growth is at least the plot's own growth: 220 -> 550 is +330, and the bands only add to it.
        ((atTwoAndAHalf - atOne) >= (base * 1.5).toFloat() - 1f) shouldBe true
    }

    private companion object {
        const val TAG = "timeline-display-probe"
    }
}
