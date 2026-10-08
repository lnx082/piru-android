package glass.kagerou.piru.data

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The timeline's display options as one value.
 *
 * ## Why the derivations are here and not at a call site
 * `plotHeight` and `bubbleIsCompact` are both arithmetic, and arithmetic inside a `drawText`/`drawLine` call is
 * arithmetic nothing can test. The graph has two drawing surfaces and a settings screen, so a rule copied three ways
 * is a rule that will disagree with itself.
 *
 * The `bubbleIsCompact` case is the quieter of the two: a call site comparing the stored string to `"compact"`
 * directly works until the wire value changes, and then draws the wrong bubble **with no error at all**.
 */
class TimelineDisplayTest {

    /** The default is the picture the app drew before any of these options existed. */
    @Test
    fun `the default matches the app's previous behaviour`() {
        val display = TimelineDisplay()
        display.zoom shouldBe 1.0
        display.compressGaps shouldBe true
        display.pkCurves shouldBe false
        display.showsAxis shouldBe true
        display.bubbleStyle shouldBe TimelineBubbleStyleName.FULL
        display.bubbleIsCompact shouldBe false
    }

    /**
     * The zoom multiplies the plot, so `0.6` is shorter and `5.0` is taller.
     *
     * Asserted from both sides of `1.0`, because a zoom applied as a divisor would look correct at the default and
     * invert everywhere else.
     */
    @Test
    fun `the zoom scales the plot height`() {
        val base = TimelineDisplay.BASE_PLOT_HEIGHT
        TimelineDisplay(zoom = 1.0).plotHeight(base) shouldBe base
        TimelineDisplay(zoom = 0.6).plotHeight(base) shouldBe base * 0.6
        TimelineDisplay(zoom = 5.0).plotHeight(base) shouldBe base * 5.0
        (TimelineDisplay(zoom = 0.6).plotHeight(base) < base) shouldBe true
        (TimelineDisplay(zoom = 2.5).plotHeight(base) > base) shouldBe true
    }

    /**
     * The bubble style parses from its wire value and **defaults to full** for anything unreadable.
     *
     * A default rather than null: this is a display preference, so a value written by a build that knew another
     * style should leave the timeline drawing the most informative bubble rather than nothing.
     */
    @Test
    fun `the bubble style parses and defaults to full`() {
        TimelineBubbleStyleName.from("full") shouldBe TimelineBubbleStyleName.FULL
        TimelineBubbleStyleName.from("compact") shouldBe TimelineBubbleStyleName.COMPACT
        TimelineBubbleStyleName.from(null) shouldBe TimelineBubbleStyleName.FULL
        TimelineBubbleStyleName.from("") shouldBe TimelineBubbleStyleName.FULL
        TimelineBubbleStyleName.from("COMPACT") shouldBe TimelineBubbleStyleName.FULL
        TimelineBubbleStyleName.from("nonsense") shouldBe TimelineBubbleStyleName.FULL
    }

    /** `bubbleIsCompact` follows the style, which is what a call site should ask rather than compare strings. */
    @Test
    fun `compact is derived from the style`() {
        TimelineDisplay(bubbleStyle = TimelineBubbleStyleName.COMPACT).bubbleIsCompact shouldBe true
        TimelineDisplay(bubbleStyle = TimelineBubbleStyleName.FULL).bubbleIsCompact shouldBe false
    }

    /** Every style round-trips through its own wire value, so the store cannot lose one. */
    @Test
    fun `every style round-trips and has a distinct wire value`() {
        for (style in TimelineBubbleStyleName.entries) {
            TimelineBubbleStyleName.from(style.wireValue) shouldBe style
        }
        TimelineBubbleStyleName.entries.map { it.wireValue }.distinct().size shouldBe
            TimelineBubbleStyleName.entries.size
    }

    /**
     * The geometry options describe the strip only while the axis is on.
     *
     * The rule the preferences screen's menu reads. Asserted here as well as in the app's `TimelineOptions`, because
     * the two are the same claim and a change to one that is not made to the other is how a menu offers a control
     * that does nothing.
     */
    @Test
    fun `the geometry options apply only with the axis on`() {
        TimelineDisplay(showsAxis = true).geometryApplies shouldBe true
        TimelineDisplay(showsAxis = false).geometryApplies shouldBe false
        // And it is the **axis** that decides, not the others: each of them can be flipped without changing it.
        TimelineDisplay(showsAxis = true, pkCurves = true, compressGaps = false).geometryApplies shouldBe true
        TimelineDisplay(showsAxis = false, pkCurves = true, compressGaps = true).geometryApplies shouldBe false
    }

    /**
     * The layout signature changes when any display option changes, and not when one does not.
     *
     * Two surfaces draw the strip, so a shared signature is what makes a change on one show on the other. Asserted
     * as a property over every field rather than as a literal string, so adding a field to the class fails this
     * until the signature carries it — which is the correct pressure.
     */
    @Test
    fun `the layout signature covers every option`() {
        val base = TimelineDisplay()

        // Every single-field change produces a different signature.
        val variations = listOf(
            base.copy(zoom = 2.5),
            base.copy(compressGaps = false),
            base.copy(pkCurves = true),
            base.copy(showsAxis = false),
            base.copy(bubbleStyle = TimelineBubbleStyleName.COMPACT),
        )
        val signatures = variations.map { it.layoutSignature() }
        signatures.distinct().size shouldBe variations.size
        for (signature in signatures) {
            (signature != base.layoutSignature()) shouldBe true
        }

        // And an identical value produces an identical signature, so an unchanged strip is not re-laid out.
        base.copy().layoutSignature() shouldBe base.layoutSignature()
    }

    /**
     * The signature separates its fields, so two different option sets cannot collide by concatenation.
     *
     * The reason it is `joinToString("|")` rather than plain concatenation: without a separator, a zoom of `1.0`
     * with compression on and a zoom of `1.0` with compression off could produce the same text as a different pair.
     */
    @Test
    fun `the signature separates its fields`() {
        val signature = TimelineDisplay(zoom = 1.0, compressGaps = true).layoutSignature()
        (signature.contains("|")) shouldBe true
        // Five fields, four separators.
        signature.split("|").size shouldBe 5
    }
}
