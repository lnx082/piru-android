package glass.kagerou.piru.model

import kotlin.math.pow

/**
 * Reading a colour stored by a build that predated class colours.
 *
 * Ported from `LegacyColorImport.p3(fromSRGBHex:)`.
 *
 * ## Why this exists at all
 * Before the generated palette, a substance's colour was an sRGB hex string the
 * user picked. Those rows are still in the store, and dropping them would reset
 * the colour of every substance someone had customised — so an upgrade has to be
 * able to read one. This is the only code that knows how.
 *
 * ## The conversion is a real one, not a relabel
 * The hex is decoded from **sRGB** and carried through Oklch into **Display P3**,
 * which is the space the palette writes. Copying the three bytes across as if the
 * spaces were the same would shift every legacy colour — mostly subtly, and
 * badly for saturated ones, which is exactly where a hand-picked colour tends to
 * sit.
 *
 * A malformed hex is [P3Color.NEUTRAL] rather than a throw: this runs over rows
 * that have survived at least one schema change, and one unreadable value must
 * not make the store unopenable.
 */
object LegacyColorImport {

    /**
     * The Display P3 colour [hex] names, read as sRGB.
     *
     * Accepts a leading `#` and any mix of upper and lower case, because the
     * column has held all of those. Anything that is not exactly six hex digits
     * after that is not a colour this can honour, and reads as neutral.
     */
    fun p3(hex: String): P3Color {
        val digits = hex.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        if (digits.length != 6) return P3Color.NEUTRAL

        val r = digits.substring(0, 2).toIntOrNull(16) ?: return P3Color.NEUTRAL
        val g = digits.substring(2, 4).toIntOrNull(16) ?: return P3Color.NEUTRAL
        val b = digits.substring(4, 6).toIntOrNull(16) ?: return P3Color.NEUTRAL

        return Oklch.fromLinearRgb(
            r = srgbToLinear(r / 255.0),
            g = srgbToLinear(g / 255.0),
            b = srgbToLinear(b / 255.0),
        ).displayP3
    }

    /**
     * The sRGB transfer function's inverse — encoded byte to linear light.
     *
     * The `0.04045` knee is the standard's, and it is not a rounding detail: a
     * plain `pow(v, 2.2)` is wrong by several code values across the dark end,
     * which is where a muted colour lives.
     */
    private fun srgbToLinear(value: Double): Double =
        if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
}
