package glass.kagerou.piru.data

import android.content.Context

/**
 * The vertical timeline's display options, read once.
 *
 * ## Why this exists as a value
 * The store keeps five separate keys, which is right for a settings screen and wrong for the surfaces that draw:
 * three of them read the same five, and a surface that reads five keys of its own is how two of them end up
 * disagreeing about the zoom. Upstream guards this with a shared `layoutSignature`; this is the same idea carried
 * one step further, by making the options a single value that is read once and handed to whatever draws.
 *
 * ## The two derivations
 * Both are arithmetic, and neither would be tested if it lived at a call site:
 *
 * - [plotHeight] multiplies a base height by the zoom, so `0.6` gives a shorter strip and `5.0` a taller one. The
 *   store clamps on read, which is what keeps the result inside the pinch bounds even if the value got in another
 *   way.
 * - [bubbleIsCompact] parses the stored wire value **once**. A call site comparing strings directly would keep
 *   working until the style's wire value changed, and then draw the wrong bubble with no error at all.
 */
data class TimelineDisplay(
    /** Points-per-hour multiplier, inside the pinch bounds. */
    val zoom: Double = 1.0,
    /** Collapse the empty stretches between clusters. */
    val compressGaps: Boolean = true,
    /** Draw the modeled concentration curves behind the bubbles. */
    val pkCurves: Boolean = false,
    /** Show the hour axis down the left edge. */
    val showsAxis: Boolean = true,
    /** How much of a dose each bubble spells out. */
    val bubbleStyle: TimelineBubbleStyleName = TimelineBubbleStyleName.FULL,
) {

    /** Whether the bubbles are drawn compact, derived rather than compared at each call site. */
    val bubbleIsCompact: Boolean get() = bubbleStyle == TimelineBubbleStyleName.COMPACT

    /**
     * The strip's height for a base height, at this zoom.
     *
     * Deliberately **not** clamped again: the zoom already is, and a second clamp here would silently disagree with
     * the first if either changed.
     */
    fun plotHeight(base: Double): Double = base * zoom

    /**
     * Whether the geometry rows describe anything on screen.
     *
     * Zoom, curves and gap compression all describe the strip; with the axis off the entries stack as a plain list
     * and none of the three changes anything. The screen's menu reads this rather than re-deriving it.
     */
    val geometryApplies: Boolean get() = showsAxis

    /**
     * The layout signature: a change to any display option re-lays the strip, and nothing else does.
     *
     * Upstream keeps the same string for the same reason — two surfaces draw the strip, so a shared signature is
     * what makes a change on one show on the other.
     */
    fun layoutSignature(): String = listOf(
        zoom.toString(),
        compressGaps.toString(),
        pkCurves.toString(),
        showsAxis.toString(),
        bubbleStyle.wireValue,
    ).joinToString("|")

    companion object {
        /** Reads every display option from the store. */
        fun read(context: Context): TimelineDisplay {
            val store = AppSettingsStore(context)
            return TimelineDisplay(
                zoom = store.timelineZoom(),
                compressGaps = store.timelineCompressGaps(),
                pkCurves = store.timelinePKCurves(),
                showsAxis = store.timelineShowsAxis(),
                bubbleStyle = TimelineBubbleStyleName.from(store.timelineBubbleStyle()),
            )
        }

        /** The base Strip height a zoom of `1.0` draws, in points. */
        const val BASE_PLOT_HEIGHT: Double = 220.0
    }
}

/**
 * How much of a dose the timeline's bubbles spell out.
 *
 * In `:core:data` rather than the app because the store persists its wire value, and because the drawing surfaces
 * and the settings screen both need to name it. Ported from `TimelineBubbleStyle`; compact exists for a real reason
 * rather than as a density option — the bubble is demoted to a label so **the curve lane keeps more of the width**,
 * which matters when the PK curves are on.
 */
enum class TimelineBubbleStyleName(val wireValue: String) {
    /** Name over dose + route chip; the trailing readout beside them. */
    FULL("full"),

    /** Name and dose on one line, no route chip. */
    COMPACT("compact"),
    ;

    companion object {
        /**
         * Parse a stored wire value, defaulting to [FULL].
         *
         * A default rather than null: this is a display preference, so an unreadable value should leave the timeline
         * drawing the fullest and most informative bubble rather than nothing.
         */
        fun from(raw: String?): TimelineBubbleStyleName =
            entries.firstOrNull { it.wireValue == raw } ?: FULL
    }
}
