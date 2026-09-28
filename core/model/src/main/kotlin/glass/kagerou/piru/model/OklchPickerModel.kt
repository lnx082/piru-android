package glass.kagerou.piru.model

/**
 * The colour being edited, in the space it is edited in.
 *
 * Ported from `Piru/Views/Library/Custom/OklchPickerModel.swift`.
 *
 * Lightness and chroma move on the plane, hue on the rail; chroma is held under
 * the Display P3 ceiling so the thumb never lands on a colour the panel cannot
 * show. Holding it here rather than at paint time is what makes the ceiling
 * visible to the user: the plane is *clipped* to the gamut, so the shape they see
 * is the shape of what is displayable, not a rectangle with dead corners.
 *
 * A plain value rather than an observable object: the caller holds it in its own
 * state, and nothing here needs to notify anyone.
 */
data class OklchPickerModel(
    val color: Oklch,
    /** Whether the colour is still the substance's class colour. Cleared by any edit, restored by [restoreDefault]. */
    val usesDefault: Boolean,
    val defaultColor: Oklch,
) {
    /** The plane's extent. Lightness stops short of both ends: a substance colour has to read as a dot or a curve on both the light card and the dark. */
    object Plane {
        val LIGHTNESS = 0.45..0.92
        val CHROMA = 0.0..0.34
    }

    /** The colour to store and paint. */
    val tint: P3Color get() = color.displayP3

    /** Move the thumb on the plane, holding chroma under the ceiling at the new lightness. */
    fun setPlane(lightness: Double, chroma: Double): OklchPickerModel {
        val l = lightness.coerceIn(Plane.LIGHTNESS.start, Plane.LIGHTNESS.endInclusive)
        // The ceiling is re-evaluated at the *new* lightness: it is a function of
        // both, so a chroma legal at one end of the plane can be out of gamut at
        // the other.
        val ceiling = displayP3ChromaCeiling(l, color.h)
        return copy(
            color = Oklch.of(l, chroma.coerceIn(0.0, ceiling), color.h),
            usesDefault = false,
        )
    }

    /** Move the hue rail, reducing chroma if the new hue cannot hold it. */
    fun setHue(hue: Double): OklchPickerModel {
        val ceiling = displayP3ChromaCeiling(color.l, hue)
        return copy(
            color = Oklch.of(color.l, minOf(color.c, ceiling), hue),
            usesDefault = false,
        )
    }

    /** Go back to the class colour. */
    fun restoreDefault(): OklchPickerModel = copy(color = defaultColor, usesDefault = true)

    companion object {
        /**
         * @param current the colour currently stored, ignored when [usesDefault].
         * @param defaultTint the generated class colour the substance would have.
         */
        fun from(current: P3Color, usesDefault: Boolean, defaultTint: P3Color): OklchPickerModel {
            val fallback = defaultTint.toOklch()
            return OklchPickerModel(
                color = if (usesDefault) fallback else current.toOklch(),
                usesDefault = usesDefault,
                defaultColor = fallback,
            )
        }
    }
}
