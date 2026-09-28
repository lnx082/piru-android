package glass.kagerou.piru.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * A color as encoded Display P3 components, each `0…1` — the numbers a wide-gamut
 * panel shows and an asset catalog's `display-p3` entry holds.
 *
 * Ported from `Shared/Formatting/P3Color.swift`. This is the form a substance
 * color is stored in and travels in, everywhere: never a hex string.
 *
 * ## Serialization
 * `[r, g, b]` at four decimals. A quarter of the bytes of a keyed object, which
 * mattered inside the iOS Live Activity's 4 KB content-state budget, and finer
 * than any panel's own code values. Kept identical here because a backup
 * round-trips through the same JSON.
 */
@Serializable(with = P3ColorSerializer::class)
data class P3Color(
    val red: Double,
    val green: Double,
    val blue: Double,
) {
    companion object {
        /**
         * Shown for a substance no color has been resolved for — a reader
         * rendering a name the app has yet to mint a color row for.
         */
        val NEUTRAL = P3Color(red = 0.62, green = 0.62, blue = 0.65)
    }
}

/** Writes `[r, g, b]`, each rounded to four decimals; reads the same shape. */
object P3ColorSerializer : KSerializer<P3Color> {

    private const val PRECISION = 10_000.0

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("P3Color")

    override fun serialize(encoder: Encoder, value: P3Color) {
        val json = requireNotNull(encoder as? JsonEncoder) { "P3Color serializes to JSON only" }
        json.encodeJsonElement(
            buildJsonArray {
                for (component in listOf(value.red, value.green, value.blue)) {
                    add(JsonPrimitive(kotlin.math.round(component * PRECISION) / PRECISION))
                }
            },
        )
    }

    override fun deserialize(decoder: Decoder): P3Color {
        val json = requireNotNull(decoder as? JsonDecoder) { "P3Color deserializes from JSON only" }
        val array = json.decodeJsonElement().let {
            requireNotNull(it as? kotlinx.serialization.json.JsonArray) { "P3Color must be a JSON array" }
        }
        return P3Color(
            red = array[0].let { (it as JsonPrimitive).content.toDouble() },
            green = array[1].let { (it as JsonPrimitive).content.toDouble() },
            blue = array[2].let { (it as JsonPrimitive).content.toDouble() },
        )
    }
}
