package glass.kagerou.piru.model

import kotlin.math.min
import kotlin.math.pow

/**
 * Display P3 is the gamut every substance colour lives in: Oklch is where a
 * colour is *chosen* and adjusted, P3 is where it is stored and shown.
 *
 * Ported from `Shared/Formatting/Oklch+DisplayP3.swift`.
 *
 * ## A gamut Android does not test for you
 * Compose will happily hand a colour to a P3 surface and let the display clamp
 * whatever falls outside — silently, and by rotating its hue as it clips each
 * channel independently. So the fit has to happen *here*, before the value is
 * built: [displayP3ChromaCeiling] binary-searches the largest chroma that fits at
 * a given lightness and hue, and [fittedToDisplayP3] reduces chroma to it, which
 * is the CSS Color 4 gamut map and holds hue exactly where channel clipping would
 * not.
 *
 * That also means the colours the generator produces are the colours that get
 * painted, rather than something the panel reinterprets on the way out.
 */
private object Gamut {
    /**
     * Slack on the `0…1` test, so a colour sitting exactly on the gamut surface
     * passes despite rounding through the matrix chain.
     */
    const val TOLERANCE = 1e-4

    /**
     * Bisection steps for the chroma ceiling: 24 halvings of a 0.4 span resolve
     * chroma to about 2e-8, far below one 8-bit code value.
     */
    const val CEILING_ITERATIONS = 24

    /** Above every displayable chroma, so the search always brackets. */
    const val CHROMA_SEARCH_LIMIT = 0.4
}

/** Linear-light Display P3 components in extended range. */
val Oklch.extendedLinearP3: LinearRgb
    get() {
        val rgb = extendedLinearRgb
        return LinearRgb(
            red = 0.8224621209 * rgb.red + 0.1775378791 * rgb.green,
            green = 0.0331941989 * rgb.red + 0.9668058011 * rgb.green,
            blue = 0.0170826307 * rgb.red + 0.0723974407 * rgb.green + 0.9105199286 * rgb.blue,
        )
    }

/** Whether the colour is displayable in Display P3. */
val Oklch.isInDisplayP3: Boolean
    get() {
        val p3 = extendedLinearP3
        val low = -Gamut.TOLERANCE
        val high = 1 + Gamut.TOLERANCE
        return p3.red in low..high && p3.green in low..high && p3.blue in low..high
    }

/**
 * The largest chroma Display P3 can show at this lightness and hue.
 *
 * A binary search rather than an analytic solve: the gamut boundary in Oklch is
 * the image of a cube under a non-linear map, and there is no closed form for
 * where a ray leaves it.
 */
fun displayP3ChromaCeiling(l: Double, h: Double): Double {
    var low = 0.0
    var high = Gamut.CHROMA_SEARCH_LIMIT
    repeat(Gamut.CEILING_ITERATIONS) {
        val mid = (low + high) / 2
        if (Oklch.of(l, mid, h).isInDisplayP3) low = mid else high = mid
    }
    return low
}

/** The same lightness and hue with chroma reduced to fit Display P3. */
val Oklch.fittedToDisplayP3: Oklch
    get() = if (isInDisplayP3) this else Oklch.of(l, min(c, displayP3ChromaCeiling(l, h)), h)

/** Encoded Display P3 components of the gamut-fitted colour. */
val Oklch.displayP3: P3Color
    get() {
        val p3 = fittedToDisplayP3.extendedLinearP3
        return P3Color(
            red = encode(p3.red),
            green = encode(p3.green),
            blue = encode(p3.blue),
        )
    }

/**
 * The sRGB transfer function, which Display P3 shares.
 *
 * Both ends clamp. Decoding an out-of-range component would produce a NaN or an
 * infinity through the `pow`, and an infinity here propagates into every
 * comparison the gamut test makes.
 */
private fun encode(linear: Double): Double {
    val v = linear.coerceIn(0.0, 1.0)
    return if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055
}

private fun decode(encoded: Double): Double {
    val v = encoded.coerceIn(0.0, 1.0)
    return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}

/** From encoded Display P3 components. */
fun P3Color.toOklch(): Oklch {
    val r = decode(red)
    val g = decode(green)
    val b = decode(blue)
    // Linear Display P3 → linear sRGB (extended range).
    return Oklch.fromLinearRgb(
        r = 1.2249401763 * r - 0.2249401763 * g,
        g = -0.0420569547 * r + 1.0420569547 * g,
        b = -0.0196375546 * r - 0.0786360456 * g + 1.0982736002 * b,
    )
}
