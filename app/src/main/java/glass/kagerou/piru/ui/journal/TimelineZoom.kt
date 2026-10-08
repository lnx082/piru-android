package glass.kagerou.piru.ui.journal

/**
 * The vertical timeline's zoom scale: its preset ladder, the pinch bounds, and how a factor reads.
 *
 * Ported from `TimelineZoom`. The ladder is what the menu offers and the bounds are what a pinch may reach, and the
 * two are deliberately different: the top preset **sits at the ceiling**, so the highest thing the menu offers is
 * also the highest thing a gesture can reach. A ladder that went past the ceiling would offer a preset that pinching
 * could never reproduce, which reads as the menu and the gesture disagreeing.
 */
internal object TimelineZoom {

    /** The preset ladder, roughly ×1.6 per step. */
    val PRESETS: List<Double> = listOf(0.6, 1.0, 1.6, 2.5, 5.0)

    /** What a pinch may reach. The top preset is the ceiling. */
    const val MINIMUM: Double = 0.5
    const val MAXIMUM: Double = 5.0

    /** The nearest preset to an arbitrary factor, for snapping a pinch. */
    fun nearest(value: Double): Double =
        PRESETS.minByOrNull { kotlin.math.abs(it - value) } ?: 1.0

    /** A factor clamped into the pinch bounds. */
    fun clamp(value: Double): Double = value.coerceIn(MINIMUM, MAXIMUM)

    /**
     * How a factor reads: "1.6×".
     *
     * One decimal place at most, and **no trailing `.0`** — a menu offering "1.0×" beside "1.6×" is announcing a
     * precision the ladder does not have, since a ladder step is a subjective amount of zoom and not a measurement.
     * The stored value is a `Double` either way; only the label rounds.
     */
    fun label(value: Double): String {
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        val text = if (rounded == kotlin.math.floor(rounded)) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
        return "$text×"
    }

    /** The presets as labels, in ladder order — what the menu shows. */
    fun presetLabels(): List<String> = PRESETS.map { label(it) }

    /** The index of the preset a stored value is nearest, for a segmented control's selection. */
    fun presetIndex(value: Double): Int =
        PRESETS.indexOfFirst { it == nearest(value) }.coerceAtLeast(0)
}
