package glass.kagerou.piru.model

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.jupiter.api.Test

/**
 * Ported from the `Oklch Display P3` and `SubstanceColorGenerator` suites in
 * `PiruTests/SubstanceColorGeneratorTests.swift`.
 *
 * The reference values in the first suite come from a *different* implementation
 * — the design system's Python colorimetry, which reaches Oklab through XYZ where
 * this goes through Ottosson's direct sRGB matrices. The two agree to about 4e-4
 * per component, so a 1e-3 tolerance is a quarter of one 8-bit code value: the
 * two independent derivations cross-checking each other is the evidence, not
 * either one alone.
 */
class SubstanceColorGeneratorTest {

    private val tolerance = 1e-3

    // MARK: - The colour space, against the design system

    @Test
    fun `The converter matches the design system on three reference colours`() {
        val cases = listOf(
            Oklch.of(0.765, 0.175, 62.6) to P3Color(0.94274, 0.60431, 0.21763),
            Oklch.of(0.615, 0.213, 312.4) to P3Color(0.64043, 0.34215, 0.84269),
            Oklch.of(0.648, 0.007, 285.9) to P3Color(0.55660, 0.55661, 0.57347),
        )
        for ((oklch, expected) in cases) {
            val p3 = oklch.displayP3
            abs(p3.red - expected.red) shouldBe (0.0 plusOrMinus tolerance)
            abs(p3.green - expected.green) shouldBe (0.0 plusOrMinus tolerance)
            abs(p3.blue - expected.blue) shouldBe (0.0 plusOrMinus tolerance)
        }
    }

    @Test
    fun `The chroma ceiling matches the design system's gamut fit`() {
        // A published number from the other implementation, so this pins the
        // bisection rather than restating it.
        abs(displayP3ChromaCeiling(0.7, 145.0) - 0.29866) shouldBe (0.0 plusOrMinus tolerance)
    }

    @Test
    fun `An out-of-gamut colour keeps its lightness and hue and lands on the ceiling`() {
        // The CSS Color 4 gamut map, not channel clipping: clipping would rotate
        // the hue as it clamped each component independently, which is visible on
        // exactly the vivid colours a substance palette is made of.
        val wild = Oklch.of(0.7, 0.4, 145.0)
        wild.isInDisplayP3 shouldBe false
        val fitted = wild.fittedToDisplayP3
        fitted.isInDisplayP3 shouldBe true
        fitted.l shouldBe wild.l
        fitted.h shouldBe wild.h
        abs(fitted.c - 0.29866) shouldBe (0.0 plusOrMinus tolerance)
    }

    @Test
    fun `A colour beyond sRGB survives the P3 round trip`() {
        // The point of P3: a colour the sRGB gamut cannot hold is still
        // representable, and decoding it back recovers the same Oklch.
        val vivid = Oklch.of(0.7, 0.28, 145.0)
        val back = vivid.displayP3.toOklch()
        abs(back.l - vivid.l) shouldBe (0.0 plusOrMinus 1e-6)
        abs(back.c - vivid.c) shouldBe (0.0 plusOrMinus 1e-6)
        abs(back.h - vivid.h) shouldBe (0.0 plusOrMinus 1e-4)
    }

    @Test
    fun `A colour whose hue is mathematically negative comes back wrapped`() {
        // `atan2` returns about -47.6 degrees for a magenta, and the picker read
        // that straight out as "H -43°" — because `fromLinearRgb` sits in the
        // companion, where the primary constructor shadowed the normalizing
        // factory. This is the assertion that would have caught it.
        val magenta = P3Color(0.64043, 0.34215, 0.84269).toOklch()
        (magenta.h >= 0.0) shouldBe true
        abs(magenta.h - 312.4) shouldBe (0.0 plusOrMinus 0.5)
    }

    @Test
    fun `The hue is normalized on construction`() {
        // Load-bearing rather than tidy: the generator applies its jitter through
        // `shifted`, and an unwrapped hue there would compare unequal to the same
        // angle written the other way round.
        Oklch.of(0.7, 0.1, -30.0).h shouldBe 330.0
        Oklch.of(0.7, 0.1, 400.0).h shouldBe 40.0
        Oklch.of(0.7, 0.1, 360.0).h shouldBe 0.0
        Oklch.of(0.7, 0.1, 30.0).shifted(hue = 350.0).h shouldBe 20.0
    }

    // MARK: - The generator

    /**
     * The tripwire.
     *
     * A change to the hash, the streams or the band recolours every user's
     * journal at once. These two values come from the upstream suite, so the port
     * is pinned against the implementation it copied rather than against itself —
     * and they passing at 1e-9 is what says the FNV-1a, the three SplitMix64
     * streams and the band arithmetic are all exact.
     */
    @Test
    fun `Pinned colours stay put`() {
        val amphetamine = SubstanceColorGenerator.color(SubstanceCategory.STIMULANT, "KWTSXDURSIMDCE")
        val lsd = SubstanceColorGenerator.color(SubstanceCategory.PSYCHEDELIC, "VAYOSLLFUXYJDT")

        abs(amphetamine.l - 0.7451375251428101) shouldBe (0.0 plusOrMinus 1e-9)
        abs(amphetamine.c - 0.17061943064171395) shouldBe (0.0 plusOrMinus 1e-9)
        abs(amphetamine.h - 54.20993733345348) shouldBe (0.0 plusOrMinus 1e-9)
        abs(lsd.l - 0.6510313115503726) shouldBe (0.0 plusOrMinus 1e-9)
        abs(lsd.c - 0.21530413367688286) shouldBe (0.0 plusOrMinus 1e-9)
        abs(lsd.h - 316.9249603689005) shouldBe (0.0 plusOrMinus 1e-9)
    }

    @Test
    fun `Every class stays in its hue family, in gamut, and in the legible band`() {
        for (category in SubstanceCategory.entries) {
            for (index in 0 until 200) {
                val colour = SubstanceColorGenerator.color(category, "SEED$index")
                colour.isInDisplayP3 shouldBe true
                // Legible as a dot or a curve on both the light and the dark card.
                (colour.l in 0.60..0.82) shouldBe true
                if (category != SubstanceCategory.OTHER) {
                    hueDistance(colour.h, category.oklchSeed.h) shouldBe
                        (0.0 plusOrMinus (HUE_JITTER + 1e-9))
                    (hueDistance(colour.h, category.oklchSeed.h) <= HUE_JITTER + 1e-9) shouldBe true
                }
            }
        }
    }

    @Test
    fun `The grey class spreads around the wheel at low chroma`() {
        // Without the achromatic branch every unclassified substance would be the
        // same near-grey. The seed is almost colourless, so the draw picks a hue
        // instead of jittering one.
        val colours = (0 until 200).map { SubstanceColorGenerator.color(SubstanceCategory.OTHER, "SEED$it") }
        colours.map { (it.h / 60).toInt() }.toSet().size shouldBe 6
        colours.all { it.c <= 0.09 + 1e-9 } shouldBe true
    }

    @Test
    fun `Siblings in one class are mostly tellable apart`() {
        // The palette's whole job. Lightness and chroma hold about six colours at
        // a distinguishable Oklab distance, so the hue jitter is what carries the
        // rest — and this is the assertion that would fail if it were dropped.
        val colours = (0 until 12).map { SubstanceColorGenerator.color(SubstanceCategory.STIMULANT, "SIBLING$it") }
        var close = 0
        var pairs = 0
        for (i in colours.indices) {
            for (j in colours.indices) {
                if (j <= i) continue
                pairs++
                if (oklabDistance(colours[i], colours[j]) < 0.03) close++
            }
        }
        (close.toDouble() / pairs < 0.15) shouldBe true
        pairs shouldBeGreaterThanOrEqual 66
    }

    @Test
    fun `The same seed gives the same colour and a different one does not`() {
        val a = SubstanceColorGenerator.color(SubstanceCategory.OPIOID, "Morphine")
        val b = SubstanceColorGenerator.color(SubstanceCategory.OPIOID, "Morphine")
        a shouldBe b
        val c = SubstanceColorGenerator.color(SubstanceCategory.OPIOID, "Codeine")
        (a != c) shouldBe true
    }

    @Test
    fun `The class decides the hue family and the identity decides the place in it`() {
        // The design promise, stated as one assertion: two substances of one class
        // differ, and neither leaves the class's band.
        val one = SubstanceColorGenerator.color(SubstanceCategory.CANNABINOID, "THC")
        val two = SubstanceColorGenerator.color(SubstanceCategory.CANNABINOID, "CBD")
        val seed = SubstanceCategory.CANNABINOID.oklchSeed
        (one.h != two.h) shouldBe true
        (hueDistance(one.h, seed.h) <= HUE_JITTER + 1e-9) shouldBe true
        (hueDistance(two.h, seed.h) <= HUE_JITTER + 1e-9) shouldBe true
    }

    @Test
    fun `A generated colour is always paintable`() {
        // Every draw ends in an encoded P3 triple, so nothing the UI holds can be
        // out of gamut or NaN.
        for (category in SubstanceCategory.entries) {
            val p3 = SubstanceColorGenerator.displayP3(category, "paintable")
            (p3.red in 0.0..1.0) shouldBe true
            (p3.green in 0.0..1.0) shouldBe true
            (p3.blue in 0.0..1.0) shouldBe true
        }
    }

    private fun hueDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return minOf(d, 360 - d)
    }

    private fun oklabDistance(x: Oklch, y: Oklch): Double {
        val xa = x.c * cos(x.h * Math.PI / 180)
        val xb = x.c * sin(x.h * Math.PI / 180)
        val ya = y.c * cos(y.h * Math.PI / 180)
        val yb = y.c * sin(y.h * Math.PI / 180)
        return sqrt((x.l - y.l).pow(2) + (xa - ya).pow(2) + (xb - yb).pow(2))
    }

    private companion object {
        const val HUE_JITTER = 10.0
    }
}
