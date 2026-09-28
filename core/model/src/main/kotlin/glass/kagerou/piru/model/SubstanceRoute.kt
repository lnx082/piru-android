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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One step of a titration ramp. */
@Serializable
data class TitrationStep(
    /** Amount in the owning route's unit. */
    val amount: Double,
    /** Localized phase label, e.g. "weeks 1–4", "month 2". */
    val label: String,
)

/**
 * Clinical-protocol dosing for compounds taken on a schedule (peptides, some
 * prescription drugs) rather than along a trip-intensity ladder. When present,
 * the detail UI renders this instead of the [DoseRange] tiers. Amounts are in
 * the owning [SubstanceRoute.unit].
 */
@Serializable
data class ProtocolDosing(
    /** Typical dose range bounds; either may be null for a single fixed dose. */
    val lowAmount: Double? = null,
    val highAmount: Double? = null,
    /** Localized frequency, e.g. "2×/day", "once weekly", "every 2 months". */
    val frequency: String,
    /** Optional titration ramp. */
    val titration: List<TitrationStep>? = null,
    /** Course length, e.g. "8–12 weeks", "cycle then break". Null = ongoing or unknown. */
    val courseDuration: String? = null,
    /** Administration notes, e.g. "fasted", "before sleep". */
    val notes: String? = null,
)

/**
 * Release or duration-of-action window for a long-acting formulation — depot
 * injections, esters, weekly peptides.
 *
 * Distinct from [DurationProfile], which is the *acute* dose-effect curve
 * (hours). A single dose of these acts or releases over days to weeks, which is
 * worth showing in the drug card but is **not** plotted as an acute timeline
 * curve. Stored normalized to minutes.
 *
 * ## Serialization
 * Authored as `{ min, max, unit }` with the unit in hours, days, weeks or
 * months, and normalized to minutes on read. It always writes back as **days**,
 * which is what the iOS encoder does — the authored unit is a convenience for
 * whoever writes the curated overlay, not something the stored form preserves.
 */
@Serializable(with = DurationOfActionSerializer::class)
data class DurationOfAction(
    val minMinutes: Double,
    val maxMinutes: Double,
) {
    companion object {
        /**
         * Minutes per authored unit. Anything unrecognized — including a missing
         * unit — is days, matching the iOS default.
         */
        fun minutesPerUnit(unit: String?): Double = when (unit?.lowercase()) {
            "hour", "hours", "h" -> 60.0
            "week", "weeks", "w" -> 1_440.0 * 7
            "month", "months", "mo" -> 1_440.0 * 30
            else -> 1_440.0
        }
    }
}

/**
 * Reads `{ min, max, unit }` (hours, days, weeks or months) and writes days.
 *
 * Ported from `DurationOfAction`'s `Codable` conformance. The asymmetry is
 * upstream's, not an oversight here: the curated overlay authors whichever unit
 * reads naturally, and the stored form is canonical days.
 */
object DurationOfActionSerializer : KSerializer<DurationOfAction> {

    private const val MINUTES_PER_DAY = 1_440.0

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("DurationOfAction")

    override fun serialize(encoder: Encoder, value: DurationOfAction) {
        val json = requireNotNull(encoder as? JsonEncoder) {
            "DurationOfAction serializes to JSON only"
        }
        json.encodeJsonElement(
            buildJsonObject {
                put("min", JsonPrimitive(value.minMinutes / MINUTES_PER_DAY))
                put("max", JsonPrimitive(value.maxMinutes / MINUTES_PER_DAY))
                put("unit", JsonPrimitive("days"))
            },
        )
    }

    override fun deserialize(decoder: Decoder): DurationOfAction {
        val json = requireNotNull(decoder as? JsonDecoder) {
            "DurationOfAction deserializes from JSON only"
        }
        val obj = json.decodeJsonElement().jsonObject
        val unit = obj["unit"]?.jsonPrimitive?.contentOrNull
        val factor = DurationOfAction.minutesPerUnit(unit)
        return DurationOfAction(
            minMinutes = (obj["min"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * factor,
            maxMinutes = (obj["max"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * factor,
        )
    }
}

/**
 * Which dosing regime a ladder describes.
 *
 * Several compounds have both, and the two differ by multiples — quetiapine's
 * clinical range is 150–750 mg while its recreational one is 50–150 — so a
 * number shown without its regime can be read as the wrong kind of dose
 * entirely.
 */
enum class DoseContext(val wireValue: String) {
    THERAPEUTIC("therapeutic"),
    RECREATIONAL("recreational"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String?): DoseContext =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

/**
 * A single dose-bearing **form** of a route — historically salt-only, now
 * multi-axis.
 *
 * Each variant carries its own unit, doses and duration, so a resolved
 * enantiomer's distinct pharmacology (armodafinil's longer exposure, Focalin's
 * roughly doubled potency) is real per-form data rather than a cosmetic label.
 * The two orthogonal facets are independent: [saltForm] is the counter-ion and
 * [isomer] is the stereo code; null on either means unspecified on that axis.
 */
@Serializable
data class DoseVariant(
    /**
     * Salt or ester label (Citrate, Glycinate, L-Threonate…). Null = freebase or
     * unspecified — the common case, and the racemic form of an isomer family.
     */
    val saltForm: String? = null,

    /** Stereoisomer code (D/S/L/R). Null = racemate or unspecified. Drives identity and dedup. */
    val isomer: String? = null,

    /**
     * The recognized name titling this isomer form ("Dexmethylphenidate",
     * "Esketamine", "Armodafinil"). Null for the racemic or unspecified form.
     */
    val isomerDisplayName: String? = null,

    val unit: String,
    val doses: DoseRange,
    val duration: DurationProfile? = null,

    /**
     * Mass fraction of the elemental active in this salt — magnesium citrate
     * about 0.16, glycinate about 0.14, L-threonate about 0.08. Null when unknown
     * or not applicable. Lets the UI show "= ⟨elemental⟩ mg".
     */
    val elementalFraction: Double? = null,
)

/**
 * One route of administration for a substance, with its dose ladder and duration.
 *
 * [saltForms] is null or empty for the overwhelming majority of substances,
 * which have a single unspecified form. When present, the top-level [unit],
 * [doses] and [duration] mirror `saltForms.first` — the default — so code that
 * ignores salt form transparently gets the default, and the picker is shown only
 * when there is more than one form.
 */
@Serializable
data class SubstanceRoute(
    val route: RouteOfAdministration,
    val unit: String,
    val doses: DoseRange,

    /**
     * The regime [doses] describes. Prescription and dual-use compounds ship only
     * their recreational ladder — the build strips therapeutic ones — and the card
     * labels it so it is never mistaken for a clinical dose.
     */
    val doseContext: DoseContext = DoseContext.UNKNOWN,

    val duration: DurationProfile? = null,

    /** Clinical-protocol dosing. When present the UI shows this instead of the trip-intensity ladder. */
    val protocolDosing: ProtocolDosing? = null,

    /** Release or duration-of-action window for long-acting formulations. Never drawn as an acute curve. */
    val durationOfAction: DurationOfAction? = null,

    /** Salt and ester forms for this route, default first. Null or empty for most substances. */
    val saltForms: List<DoseVariant>? = null,
)
