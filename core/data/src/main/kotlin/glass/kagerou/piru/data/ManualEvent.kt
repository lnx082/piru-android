package glass.kagerou.piru.data

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One hand-entered change to a stock level.
 *
 * Ported from `ManualEvent` in `Shared/Models/InventoryItem.swift`.
 *
 * A restock is not a running total kept in a column; it is an **event**, and the
 * quantity on hand is replayed from the events plus every dose logged since
 * [InventoryItemEntity.trackingStart]. That is why the row's `currentQuantity` is
 * described as a self-healing cache rather than the truth: if it is ever wrong,
 * replaying says what it should be, so a bad cache is recoverable and a wrong
 * total is not.
 *
 * ## Signed, and only adjustments floor
 * [amount] is positive for [Kind.INITIAL] and [Kind.RESTOCK] and signed for
 * [Kind.ADJUSTMENT]. Only adjustments and doses floor the running balance at
 * zero — a restock may carry the balance negative on the way past, so that
 * correcting a count downward does not silently erase the doses that were already
 * logged against a stock the user had not entered yet.
 */
data class ManualEvent(
    /** Stable identity, so re-importing the same backup merges rather than duplicates. */
    val id: UUID = UUID.randomUUID(),

    val kind: Kind = Kind.RESTOCK,

    /** Signed; see the class note. In the item's own unit. */
    val amount: Double = 0.0,

    /** The date the replay sorts by. */
    val date: Instant = Instant.now(),

    val note: String? = null,

    /** Informational only — records that this event is what set the supply bar's baseline. */
    val setsBaseline: Boolean = false,
) {
    enum class Kind(val wireValue: String) {
        INITIAL("initial"),
        RESTOCK("restock"),
        ADJUSTMENT("adjustment"),
        ;

        companion object {
            /** Unknown strings read as [RESTOCK] — the same swallow the iOS decode does. */
            fun fromWire(value: String?): Kind =
                entries.firstOrNull { it.wireValue == value } ?: RESTOCK
        }
    }
}

/**
 * The JSON blob in `inventory_item.restocks_json`.
 *
 * ## Why this is hand-rolled rather than `@Serializable`
 * The column is a blob the iOS side also writes, and its format is not the
 * language's default: Swift's `JSONEncoder()` with no configuration encodes a
 * `Date` as a `Double` of **seconds since 2001-01-01**, not since the Unix epoch,
 * and a `UUID` as an **uppercase** string. Letting kotlinx.serialization pick its
 * own representation would produce a file that parses on both sides but reads the
 * timestamps as fifty-eight years and one day off. So the two conversions that
 * differ are written out here, and the rest follows the property names.
 *
 * `nil` optionals are omitted rather than written as `null`, matching Swift's
 * synthesized `encodeIfPresent`.
 */
object ManualEvents {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Seconds between the Unix epoch and Swift's reference date (2001-01-01T00:00:00Z).
     *
     * `Date.timeIntervalSinceReferenceDate` is what `.deferredToDate` writes, so
     * this is the single constant that makes an iOS restock history readable here.
     */
    private const val SWIFT_REFERENCE_EPOCH_SECONDS: Double = 978_307_200.0

    /**
     * Read the events, or an empty list.
     *
     * A malformed blob reads as no events rather than throwing, mirroring the iOS
     * accessor's `(try? …) ?? []`. That is a real loss of history and it is still
     * the right call: the alternative is a single bad byte making the row
     * unopenable and taking the whole inventory screen with it.
     */
    fun decode(raw: String?): List<ManualEvent> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching {
            json.parseToJsonElement(raw).let { element ->
                if (element !is JsonArray) return@let emptyList()
                element.mapNotNull { decodeOne(it.jsonObject) }
            }
        }.getOrDefault(emptyList())
    }

    /** Encode, writing an empty string for no events — how the iOS side writes "no history". */
    fun encode(events: List<ManualEvent>): String {
        if (events.isEmpty()) return ""
        val array = JsonArray(events.map { encodeOne(it) })
        return array.toString()
    }

    private fun decodeOne(obj: JsonObject): ManualEvent? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return null
        val seconds = obj["date"]?.jsonPrimitive?.doubleOrNull ?: return null
        return ManualEvent(
            id = id,
            kind = ManualEvent.Kind.fromWire(obj["kind"]?.jsonPrimitive?.contentOrNull),
            amount = obj["amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            date = fromSwiftReferenceSeconds(seconds),
            note = obj["note"]?.jsonPrimitive?.contentOrNull,
            setsBaseline = obj["setsBaseline"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    private fun encodeOne(event: ManualEvent): JsonObject = JsonObject(
        buildMap {
            put("id", JsonPrimitive(event.id.toString().uppercase()))
            put("kind", JsonPrimitive(event.kind.wireValue))
            put("amount", JsonPrimitive(event.amount))
            put("date", JsonPrimitive(toSwiftReferenceSeconds(event.date)))
            if (event.note != null) put("note", JsonPrimitive(event.note))
            put("setsBaseline", JsonPrimitive(event.setsBaseline))
        },
    )

    private fun toSwiftReferenceSeconds(instant: Instant): Double =
        instant.epochSecond.toDouble() - SWIFT_REFERENCE_EPOCH_SECONDS + instant.nano / 1_000_000_000.0

    private fun fromSwiftReferenceSeconds(seconds: Double): Instant {
        val whole = seconds.toLong()
        val fraction = seconds - whole
        return Instant.ofEpochSecond(whole + 978_307_200L, (fraction * 1_000_000_000.0).toLong())
    }
}
