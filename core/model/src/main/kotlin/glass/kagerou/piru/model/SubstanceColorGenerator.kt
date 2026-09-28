package glass.kagerou.piru.model

/**
 * The default colour of a substance: its **class decides the hue**, its
 * **identity decides where inside that hue's band** it sits.
 *
 * Ported from `Piru/Domain/SubstanceColorGenerator.swift`.
 *
 * The output depends only on the category and the seed string, so a substance
 * keeps its colour across launches, devices and database rebuilds for as long as
 * its class and PSID family hold. That is a design promise, not an incidental
 * property — which is why the hash is FNV-1a and not `String.hashCode`: the
 * platform's own hash is stable here but the iOS one is reseeded per launch, so
 * the algorithm was chosen to be reproducible anywhere rather than left to
 * whatever the runtime happens to do.
 */
object SubstanceColorGenerator {

    private object Band {
        /**
         * The class seed's lightness is pulled into this range before the spread
         * is applied, so every result stays legible as a dot or a curve on both
         * the light and the dark card.
         */
        val LIGHTNESS_CENTER = 0.67..0.75
        const val LIGHTNESS_SPREAD = 0.07

        /** Chroma as a multiple of the class seed's, so a muted class stays muted and a vivid one vivid. */
        val CHROMA_SCALE = 0.65..1.25

        /**
         * Muted classes get at least this much chroma above their seed; a purely
         * proportional range would leave them one shade.
         */
        const val MINIMUM_CHROMA_HEADROOM = 0.04

        /**
         * Share of the Display P3 chroma ceiling a generated colour may reach;
         * the last few percent are the panel's most garish colours.
         */
        const val GAMUT_CEILING_SHARE = 0.95

        /**
         * Degrees either side of the class hue.
         *
         * Lightness and chroma alone hold about six colours at a distinguishable
         * Oklab distance, so the hue is what makes a twelfth sibling tellable
         * from the others.
         */
        const val HUE_JITTER = 10.0

        /** Below this seed chroma the class is a grey and has no hue to keep. */
        const val ACHROMATIC_SEED_CHROMA = 0.02
        val ACHROMATIC_CHROMA = 0.04..0.09
    }

    /** The generated colour for [category] and [seed], as an Oklch. Call [displayP3] to paint it. */
    fun color(category: SubstanceCategory, seed: String): Oklch {
        val base = category.oklchSeed
        val hash = fnv1a(seed)
        val lightnessDraw = unit(hash, stream = 1uL)
        val chromaDraw = unit(hash, stream = 2uL)
        val hueDraw = unit(hash, stream = 3uL)

        val center = base.l.coerceIn(Band.LIGHTNESS_CENTER.start, Band.LIGHTNESS_CENTER.endInclusive)
        val lightness = center + (lightnessDraw * 2 - 1) * Band.LIGHTNESS_SPREAD

        // A grey class has no hue to keep, so the draw picks one instead — and
        // the chroma is a narrow low band rather than the class's, because the
        // point of these is that they read as near-neutral.
        if (base.c < Band.ACHROMATIC_SEED_CHROMA) {
            return Oklch.of(lightness, lerp(Band.ACHROMATIC_CHROMA, chromaDraw), hueDraw * 360)
        }

        val hue = base.h + (hueDraw * 2 - 1) * Band.HUE_JITTER
        // The range is bounded by the gamut *before* the draw: fitting afterwards
        // would stack every over-the-ceiling draw onto the same edge colour, which
        // is how a palette loses its distinguishability exactly at its most vivid
        // end.
        val ceiling = displayP3ChromaCeiling(lightness, hue) * Band.GAMUT_CEILING_SHARE
        val high = minOf(
            maxOf(base.c * Band.CHROMA_SCALE.endInclusive, base.c + Band.MINIMUM_CHROMA_HEADROOM),
            ceiling,
        )
        val low = minOf(base.c * Band.CHROMA_SCALE.start, high * Band.CHROMA_SCALE.start)
        return Oklch.of(lightness, lerp(low..high, chromaDraw), hue)
    }

    /** The colour to paint for a substance whose identity is [seed] and whose class is [category]. */
    fun displayP3(category: SubstanceCategory, seed: String): P3Color = color(category, seed).displayP3

    private fun lerp(range: ClosedRange<Double>, t: Double): Double =
        range.start + (range.endInclusive - range.start) * t

    /**
     * FNV-1a over the UTF-8 bytes.
     *
     * Chosen because it is the same on every platform and in every process, which
     * is what "a substance keeps its colour across devices" requires. The
     * platform hashes guarantee neither: iOS reseeds its `Hasher` on every launch,
     * and while Kotlin's `String.hashCode` happens to be stable, relying on that
     * would make the palette a property of the runtime rather than of the design.
     */
    private fun fnv1a(string: String): ULong {
        var hash: ULong = 14_695_981_039_346_656_037uL
        for (byte in string.encodeToByteArray()) {
            hash = (hash xor byte.toUByte().toULong()) * 1_099_511_628_211uL
        }
        return hash
    }

    /**
     * An independent draw in `0..<1` per stream: the SplitMix64 finalizer over
     * the hash offset by the stream's golden-ratio multiple, top 53 bits kept.
     *
     * Three streams rather than three hashes, so one input string yields three
     * uncorrelated draws — otherwise lightness and hue would move together and
     * every class's palette would lie on a line.
     */
    private fun unit(hash: ULong, stream: ULong): Double {
        var z = hash + stream * 0x9E37_79B9_7F4A_7C15uL
        z = (z xor (z shr 30)) * 0xBF58_476D_1CE4_E5B9uL
        z = (z xor (z shr 27)) * 0x94D0_49BB_1331_11EBuL
        z = z xor (z shr 31)
        // 53 bits is a Double's exact integer range, so the division lands on a
        // representable value with no rounding.
        return (z shr 11).toDouble() / (1L shl 53).toDouble()
    }
}
