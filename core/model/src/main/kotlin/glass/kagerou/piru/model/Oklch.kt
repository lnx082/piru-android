package glass.kagerou.piru.model

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A colour in Oklch — perceptual lightness `l` (0…1), chroma `c` (0 at grey,
 * about 0.37 at the most saturated displayable colours) and hue `h` in degrees.
 *
 * Ported from `Shared/Formatting/Oklch.swift`.
 *
 * Equal numeric distance here is equal *perceived* difference, so a shift reads
 * the same on every substance colour — which no RGB arithmetic does. That is the
 * whole reason the pipeline exists: a generated palette has to hold a dozen
 * distinguishable colours at the same apparent lightness, and "add 20 to the
 * green channel" cannot promise that.
 *
 * Conversions go through Oklab (Björn Ottosson, 2020) from and to linear-light
 * sRGB. **Encoding to and from a display gamut is the caller's** — see
 * [displayP3], which is where the gamut work lives.
 */
class Oklch private constructor(
    val l: Double,
    val c: Double,
    /** Degrees, **always** normalized to `0..<360` — see the companion. */
    val h: Double,
) {
    /**
     * The same colour moved by [lightness], rotated by [hue] degrees, chroma
     * scaled by [chromaScale].
     *
     * The hue goes back through the constructor, so the result is wrapped too.
     * That matters: this is how the generator applies its jitter, and an
     * unwrapped hue there would compare unequal to the same angle written the
     * other way round.
     */
    fun shifted(lightness: Double = 0.0, hue: Double = 0.0, chromaScale: Double = 1.0): Oklch = of(
        l = min(max(l + lightness, 0.0), 1.0),
        c = maxOf(c * chromaScale, 0.0),
        h = h + hue,
    )

    override fun equals(other: Any?): Boolean =
        other is Oklch && l == other.l && c == other.c && h == other.h

    override fun hashCode(): Int {
        var result = l.hashCode()
        result = 31 * result + c.hashCode()
        result = 31 * result + h.hashCode()
        return result
    }

    override fun toString(): String = "Oklch(l=$l, c=$c, h=$h)"

    companion object {
        /**
         * The one way to build a colour, wrapping the hue.
         *
         * ## Why there is no constructor and no `invoke`
         * Upstream normalizes in `init`. Kotlin cannot: a `val` parameter cannot be
         * reassigned there, and a data class may not take a non-property parameter.
         * The first attempt was a private constructor plus a companion
         * `operator fun invoke`, which looks equivalent and is not — **a primary
         * constructor shadows a companion `invoke` for anything inside the class or
         * its companion**, so [shifted] and [fromLinearRgb] both silently built
         * unwrapped hues while every external caller got normalized ones.
         *
         * The bug survived a round of tests and only showed up as a `H -43°`
         * readout. So there is now exactly one door, and no second construction
         * path to be shadowed.
         */
        fun of(l: Double, c: Double, h: Double): Oklch = Oklch(l, c, normalize(h))

        /** From linear-light sRGB components. Out-of-gamut inputs are accepted; the math is continuous there. */
        fun fromLinearRgb(r: Double, g: Double, b: Double): Oklch {
            val long = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
            val medium = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
            val short = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
            val labL = 0.2104542553 * long + 0.7936177850 * medium - 0.0040720468 * short
            val labA = 1.9779984951 * long - 2.4285922050 * medium + 0.4505937099 * short
            val labB = 0.0259040371 * long + 0.7827717662 * medium - 0.8086757660 * short
            return of(
                l = labL,
                c = sqrt(labA * labA + labB * labB),
                h = atan2(labB, labA) * 180 / Math.PI,
            )
        }

        /** Wrap degrees into `0..<360`. */
        fun normalize(degrees: Double): Double {
            val wrapped = degrees % 360
            return if (wrapped < 0) wrapped + 360 else wrapped
        }
    }
}

/** Linear-light sRGB, extended range — a component below 0 or above 1 is outside the gamut. */
data class LinearRgb(val red: Double, val green: Double, val blue: Double) {
    /** Each component clamped to `0…1`. */
    fun clamped(): LinearRgb = LinearRgb(red.coerceIn(0.0, 1.0), green.coerceIn(0.0, 1.0), blue.coerceIn(0.0, 1.0))
}

/**
 * Linear-light sRGB components in **extended** range.
 *
 * The wide-gamut conversions start here, because clamping first would discard
 * exactly the colours Display P3 adds.
 */
val Oklch.extendedLinearRgb: LinearRgb
    get() {
        val radians = h * Math.PI / 180
        val labA = c * cos(radians)
        val labB = c * sin(radians)
        val long = (l + 0.3963377774 * labA + 0.2158037573 * labB).pow(3)
        val medium = (l - 0.1055613458 * labA - 0.0638541728 * labB).pow(3)
        val short = (l - 0.0894841775 * labA - 1.2914855480 * labB).pow(3)
        return LinearRgb(
            red = 4.0767416621 * long - 3.3077115913 * medium + 0.2309699292 * short,
            green = -1.2684380046 * long + 2.6097574011 * medium - 0.3413193965 * short,
            blue = -0.0041960863 * long - 0.7034186147 * medium + 1.7076147010 * short,
        )
    }

/** Linear-light sRGB, clamped. A shifted colour can leave the gamut; clamping keeps its hue. */
val Oklch.linearRgb: LinearRgb get() = extendedLinearRgb.clamped()
