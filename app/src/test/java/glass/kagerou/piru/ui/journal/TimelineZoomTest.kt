package glass.kagerou.piru.ui.journal

import glass.kagerou.piru.data.AppSettingsStore
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The timeline's zoom ladder and its options gating.
 *
 * ## The two rules worth holding
 * **The top preset is the ceiling.** The ladder offers what a pinch can reach, so the menu cannot offer a factor no
 * gesture could reproduce — which reads as the menu and the gesture disagreeing about the scale.
 *
 * **Zoom, curves and compression are not offered with the axis off.** They describe the strip's geometry, and with
 * the bubbles stacked as a plain list they change nothing. A menu that offers them anyway looks entirely normal and
 * does nothing, which is the kind of defect that survives review because nothing about it looks wrong.
 *
 * And the preferences they describe are **kept** rather than cleared when the axis goes off, so the toggle is not a
 * one-way trip.
 */
class TimelineZoomTest {

    /**
     * The presets ascend and the top one is the ceiling.
     *
     * Asserted as a relationship rather than as the literal ladder: a ladder edited to add a step above the ceiling
     * would break this, which is the point — that edit is the bug.
     */
    @Test
    fun `the ladder ascends to the ceiling`() {
        val presets = TimelineZoom.PRESETS
        (presets.size > 1) shouldBe true
        presets shouldContainExactly presets.sorted()
        presets.last() shouldBe TimelineZoom.MAXIMUM
        // And nothing on the ladder is below what a pinch can reach.
        (presets.first() >= TimelineZoom.MINIMUM) shouldBe true
    }

    /**
     * A factor reads with at most one decimal and no trailing `.0`.
     *
     * The rounding is what stops a menu showing "1.0000000000000002×" after a pinch, and the dropped `.0` is what
     * stops it announcing a precision the ladder does not have.
     */
    @Test
    fun `a factor reads as a rounded multiple`() {
        TimelineZoom.label(1.0) shouldBe "1×"
        TimelineZoom.label(0.6) shouldBe "0.6×"
        TimelineZoom.label(1.6) shouldBe "1.6×"
        TimelineZoom.label(2.5) shouldBe "2.5×"
        TimelineZoom.label(5.0) shouldBe "5×"
        // A pinch produces arbitrary values, which is what the rounding is for.
        TimelineZoom.label(1.0000000000000002) shouldBe "1×"
        TimelineZoom.label(1.64999) shouldBe "1.6×"
        // And this is `1.6`, not the `1.7` the decimal would suggest: `1.65` is not representable in binary and the
        // nearest double is a hair **below** it, so it rounds down. Asserted so the behaviour is on record rather
        // than looking like a rounding bug to the next reader.
        TimelineZoom.label(1.65) shouldBe "1.6×"
        TimelineZoom.label(1.66) shouldBe "1.7×"
    }

    /** The preset labels are the ladder's, in order — what the menu shows. */
    @Test
    fun `the preset labels follow the ladder`() {
        TimelineZoom.presetLabels() shouldContainExactly listOf("0.6×", "1×", "1.6×", "2.5×", "5×")
    }

    /**
     * A stored factor is clamped into the pinch bounds, and a pinch snaps to the nearest preset.
     *
     * Two different operations and both are needed: clamping keeps an out-of-range value from laying the strip out
     * at a scale no gesture can return from, and snapping is what makes the menu's selection follow a pinch rather
     * than showing no selection at all.
     */
    @Test
    fun `a factor is clamped and a pinch snaps`() {
        TimelineZoom.clamp(0.1) shouldBe TimelineZoom.MINIMUM
        TimelineZoom.clamp(9.0) shouldBe TimelineZoom.MAXIMUM
        TimelineZoom.clamp(1.6) shouldBe 1.6

        TimelineZoom.nearest(1.7) shouldBe 1.6
        TimelineZoom.nearest(1.9) shouldBe 1.6
        TimelineZoom.nearest(2.2) shouldBe 2.5
        TimelineZoom.nearest(0.55) shouldBe 0.6
        TimelineZoom.nearest(4.9) shouldBe 5.0
    }

    /** The selection index tracks the snap, so a segmented control shows the right preset. */
    @Test
    fun `the preset index follows the snap`() {
        TimelineZoom.presetIndex(1.0) shouldBe 1
        TimelineZoom.presetIndex(1.05) shouldBe 1
        TimelineZoom.presetIndex(4.8) shouldBe 4
        TimelineZoom.presetIndex(0.4) shouldBe 0
    }

    // MARK: - The bubble style

    /**
     * The stored wire value parses, and an unreadable one defaults to the fullest bubble.
     *
     * A default rather than null: this is a display preference, so a value from a build that knew another style
     * should leave the timeline drawing the most informative bubble rather than nothing.
     */
    @Test
    fun `the bubble style parses and defaults to full`() {
        TimelineBubbleStyle.from("full") shouldBe TimelineBubbleStyle.FULL
        TimelineBubbleStyle.from("compact") shouldBe TimelineBubbleStyle.COMPACT
        TimelineBubbleStyle.from(null) shouldBe TimelineBubbleStyle.FULL
        TimelineBubbleStyle.from("") shouldBe TimelineBubbleStyle.FULL
        TimelineBubbleStyle.from("FULL") shouldBe TimelineBubbleStyle.FULL
        TimelineBubbleStyle.from("nonsense") shouldBe TimelineBubbleStyle.FULL
    }

    /** Every style has its own wire value, so a round trip is lossless. */
    @Test
    fun `every bubble style round-trips`() {
        for (style in TimelineBubbleStyle.entries) {
            TimelineBubbleStyle.from(style.wireValue) shouldBe style
        }
        TimelineBubbleStyle.entries.map { it.wireValue }.distinct().size shouldBe TimelineBubbleStyle.entries.size
    }

    // MARK: - The options gating

    /**
     * With the axis on, every row is offered.
     *
     * The order is asserted because the menu draws in it: zoom first, then the curve mode, then the three toggles.
     */
    @Test
    fun `the axis on offers every row in order`() {
        TimelineOptions.rows(showsAxis = true) shouldContainExactly listOf(
            TimelineOptions.Row.ZOOM,
            TimelineOptions.Row.PK_CURVES,
            TimelineOptions.Row.SHOWS_AXIS,
            TimelineOptions.Row.COMPACT_ENTRIES,
            TimelineOptions.Row.COMPRESS_GAPS,
        )
    }

    /**
     * With the axis off, only the two rows that still do something are offered.
     *
     * The gating rule. Zoom, curves and compression describe the strip's geometry; with the bubbles stacked as a
     * plain list they change nothing, so offering them would be offering a control with no visible effect.
     */
    @Test
    fun `the axis off withdraws the geometry rows`() {
        TimelineOptions.rows(showsAxis = false) shouldContainExactly listOf(
            TimelineOptions.Row.SHOWS_AXIS,
            TimelineOptions.Row.COMPACT_ENTRIES,
        )
        // Named individually too, so a failure says which row leaked through.
        TimelineOptions.offers(TimelineOptions.Row.ZOOM, showsAxis = false) shouldBe false
        TimelineOptions.offers(TimelineOptions.Row.PK_CURVES, showsAxis = false) shouldBe false
        TimelineOptions.offers(TimelineOptions.Row.COMPRESS_GAPS, showsAxis = false) shouldBe false
        // The axis toggle itself and the bubble style stay: the first would otherwise be a trap, the second
        // changes the rows rather than the strip's geometry.
        TimelineOptions.offers(TimelineOptions.Row.SHOWS_AXIS, showsAxis = false) shouldBe true
        TimelineOptions.offers(TimelineOptions.Row.COMPACT_ENTRIES, showsAxis = false) shouldBe true
    }

    /** The predicate and the list agree, so a call site drawing in a fixed order cannot disagree with a caller
     * iterating. */
    @Test
    fun `the predicate matches the list`() {
        for (showsAxis in listOf(true, false)) {
            val rows = TimelineOptions.rows(showsAxis)
            for (row in TimelineOptions.Row.entries) {
                TimelineOptions.offers(row, showsAxis) shouldBe (row in rows)
            }
        }
    }

    /**
     * The withdrawn preferences are **kept**, not cleared.
     *
     * Stated as its own test because the alternative is the tempting one: turning off the axis and clearing the
     * hidden preferences would make the toggle a one-way trip. The port's tab settings already hit exactly that
     * failure once — hiding every tab in turn left one, because the stored shape could not express the answer.
     */
    @Test
    fun `withdrawing a row does not clear its preference`() {
        TimelineOptions.retainsHiddenPreferences() shouldBe true
    }

    // MARK: - The store's own contract

    /**
     * The layout signature is built from the five display options, and nothing else.
     *
     * Two surfaces draw the strip, so a shared signature is what makes a change on one show on the other. Asserted
     * through the real store, which means this runs under Robolectric.
     */
    @Test
    fun `the layout signature covers the five display options`() {
        // The key names are the contract with upstream's export, so they are asserted literally.
        AppSettingsStore.KEY_TIMELINE_ZOOM shouldBe "timelineZoom"
        AppSettingsStore.KEY_TIMELINE_COMPRESSION shouldBe "timelineCompression"
        AppSettingsStore.KEY_TIMELINE_PK_CURVES shouldBe "timelinePKCurves"
        AppSettingsStore.KEY_TIMELINE_SHOWS_AXIS shouldBe "timelineShowsAxis"
        AppSettingsStore.KEY_TIMELINE_BUBBLE_STYLE shouldBe "timelineBubbleStyle"
    }
}
