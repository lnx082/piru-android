package glass.kagerou.piru.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A band on a dose ladder, e.g. `common = 30..60 mg`.
 *
 * [upper] below [lower] is not a band — it decodes to null rather than to an
 * inverted range, matching how the iOS side reads the same JSON.
 */
@Serializable
data class CodableRange(val lower: Double, val upper: Double) {

    val closedRange: ClosedRange<Double>? get() = if (lower <= upper) lower..upper else null

    companion object {
        fun of(range: ClosedRange<Double>) = CodableRange(range.start, range.endInclusive)
    }
}

/**
 * The five-tier dose ladder for one substance route.
 *
 * Every tier is optional: a route can ship a duration and no ladder at all
 * (e.g. Cannabis sublingual from PsychonautWiki), which is why [hasAnyValue]
 * exists rather than callers testing individual tiers.
 */
@Serializable(with = DoseRangeSerializer::class)
data class DoseRange(
    val threshold: Double? = null,
    val light: ClosedRange<Double>? = null,
    val common: ClosedRange<Double>? = null,
    val strong: ClosedRange<Double>? = null,
    val heavy: Double? = null,
) {

    /**
     * `true` when at least one dose tier is populated. Routes that ship a
     * duration but no dose ladder would otherwise render an empty "Dosage" card.
     */
    val hasAnyValue: Boolean
        get() = threshold != null || light != null || common != null || strong != null || heavy != null

    /**
     * Where [dose] sits on this ladder, or null when there is no ladder.
     *
     * A range with every tier empty cannot place a dose. Tested in descending
     * order, and each tier also claims everything above it, so the ladder
     * degrades gracefully when tiers are missing.
     *
     * An all-empty ladder answers null rather than [DoseLevel.SUB]: labeling an
     * unknown dose "sub-threshold" is a claim about the dose rather than an
     * absence of one, and the reader cannot tell the two apart.
     */
    fun levelFor(dose: Double): DoseLevel? {
        if (!hasAnyValue) return null
        heavy?.let { if (dose >= it) return DoseLevel.HEAVY }
        strong?.let { if (dose in it || dose > it.endInclusive) return DoseLevel.STRONG }
        common?.let { if (dose in it || dose > it.endInclusive) return DoseLevel.COMMON }
        light?.let { if (dose in it || dose > it.endInclusive) return DoseLevel.LIGHT }
        threshold?.let { if (dose >= it) return DoseLevel.THRESHOLD }
        return DoseLevel.SUB
    }

    /**
     * How precisely a substance must be measured, derived from its average
     * reference dose.
     */
    enum class DosingPrecision {
        NONE,
        RECOMMENDED,
        CRITICAL,
    }

    /**
     * Classifies the route by average reference dose, converted to mg:
     * under 1 mg is [DosingPrecision.CRITICAL] (true microgram/sub-milligram
     * potency, e.g. fentanyl, nitazenes), 1–5 mg is
     * [DosingPrecision.RECOMMENDED] (a precise scale is worth using, but the
     * "active in micrograms" framing would be false), otherwise
     * [DosingPrecision.NONE].
     */
    fun dosingPrecision(unit: String): DosingPrecision {
        val values = buildList {
            threshold?.let { add(it) }
            light?.let { add((it.start + it.endInclusive) / 2) }
            common?.let { add((it.start + it.endInclusive) / 2) }
            strong?.let { add((it.start + it.endInclusive) / 2) }
            heavy?.let { add(it) }
        }
        if (values.isEmpty()) return DosingPrecision.NONE
        val average = values.sum() / values.size

        // Only reason about potency when the unit is a convertible mass unit.
        // Annotated and colloquial units ("g (leaf powder)", "mL", "IU") cannot
        // map to mg; treating the raw number as mg would wrongly flag
        // gram-dosed botanicals (kratom leaf, mushrooms) as microgram-potent.
        val averageInMg = DoseUnit.convert(average, from = unit, to = "mg") ?: return DosingPrecision.NONE
        return when {
            averageInMg < 1 -> DosingPrecision.CRITICAL
            averageInMg < 5 -> DosingPrecision.RECOMMENDED
            else -> DosingPrecision.NONE
        }
    }

    companion object {
        /** A ladder with only [common] filled — the shape most test fixtures want. */
        fun common(range: ClosedRange<Double>) = DoseRange(common = range)
    }
}

/**
 * Reads and writes the iOS side's JSON shape exactly: tiers are numbers and
 * `{ "lower": …, "upper": … }` objects, absent tiers are omitted, and an
 * inverted band is dropped rather than written or kept.
 *
 * A plain `@Serializable` data class would encode an inverted band. The iOS
 * encoder drops it, so a Kotlin row that kept it would round-trip to a
 * different file than the one the iOS app would have written.
 */
object DoseRangeSerializer : KSerializer<DoseRange> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("DoseRange")

    override fun serialize(encoder: Encoder, value: DoseRange) {
        val json = requireNotNull(encoder as? JsonEncoder) {
            "DoseRange serializes to JSON only"
        }
        val obj = buildJsonObject {
            value.threshold?.let { put("threshold", JsonPrimitive(it)) }
            value.light?.let { band(it)?.let { b -> put("light", b) } }
            value.common?.let { band(it)?.let { b -> put("common", b) } }
            value.strong?.let { band(it)?.let { b -> put("strong", b) } }
            value.heavy?.let { put("heavy", JsonPrimitive(it)) }
        }
        json.encodeJsonElement(obj)
    }

    override fun deserialize(decoder: Decoder): DoseRange {
        val json = requireNotNull(decoder as? JsonDecoder) {
            "DoseRange deserializes from JSON only"
        }
        val obj = json.decodeJsonElement().jsonObject
        return DoseRange(
            threshold = obj["threshold"]?.jsonPrimitive?.doubleOrNull,
            light = readBand(obj["light"]),
            common = readBand(obj["common"]),
            strong = readBand(obj["strong"]),
            heavy = obj["heavy"]?.jsonPrimitive?.doubleOrNull,
        )
    }

    private fun band(range: ClosedRange<Double>): JsonObject? {
        if (range.start > range.endInclusive) return null
        return buildJsonObject {
            put("lower", JsonPrimitive(range.start))
            put("upper", JsonPrimitive(range.endInclusive))
        }
    }

    private fun readBand(element: kotlinx.serialization.json.JsonElement?): ClosedRange<Double>? {
        val obj = element?.jsonObject ?: return null
        val lower = obj["lower"]?.jsonPrimitive?.doubleOrNull ?: return null
        val upper = obj["upper"]?.jsonPrimitive?.doubleOrNull ?: return null
        return if (lower <= upper) lower..upper else null
    }
}

/**
 * The tier a dose sits in. [wireValue] is the iOS `rawValue` and is what crosses
 * the wire — never derive a stored value from [name].
 */
enum class DoseLevel(val wireValue: String) {
    SUB("Sub-threshold"),
    THRESHOLD("Threshold"),
    LIGHT("Light"),
    COMMON("Common"),
    STRONG("Strong"),
    HEAVY("Heavy"),
}
