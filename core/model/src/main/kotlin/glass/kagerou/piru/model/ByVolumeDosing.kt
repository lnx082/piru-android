package glass.kagerou.piru.model

import java.util.Locale
import kotlin.math.round
import java.util.regex.Pattern

/**
 * A substance whose natural input is a **concentration applied to a measured
 * volume** rather than a mass entered directly — alcohol by %ABV today, dissolved
 * solids (mg/mL) later.
 *
 * Ported from `Shared/ByVolumeDosing.swift`.
 *
 * Substances opt in (alcohol is the only adopter); the capability owns the
 * density constant and the canonical-unit conversion, so the dose form stays
 * substance-agnostic. The user measures in volume — a 330 mL can, a 175 mL glass
 * — and the app stores the canonical mass (grams of ethanol) that the dose ladder
 * and the PK model run on.
 *
 * This is the exact, uncontested arithmetic between the two. Not a model, no
 * confidence hedge: `grams = volume × (ABV/100) × density`.
 */
data class ByVolumeDosing(
    val concentration: Concentration,
    /**
     * The unit stored on the resulting dose entry — "g" for alcohol — and what
     * the dose ladder and PK pipeline consume. The whole point of canonicalising.
     */
    val canonicalUnit: String,
    /**
     * Mass of one colloquial "standard" unit in [canonicalUnit] — 14 g for a US
     * standard drink. A gloss for reading an amount already logged, never a
     * threshold.
     */
    val standardUnitMass: Double,
    /** What that colloquial unit is called on the dose form ("drink"). */
    val standardUnitLabel: String,
    /**
     * Tappable presets that pre-fill a volume and a default strength (Beer, Wine,
     * …). First-class rather than optional: the watch flow logs primarily from
     * these.
     */
    val drinkPresets: List<DrinkPreset> = emptyList(),
) {
    /** How a strength figure maps onto a volume. */
    sealed interface Concentration {
        /**
         * Percent volume-by-volume of a liquid with the given density (g/mL).
         * Alcohol: the strength field is ABV %, ethanol density 0.789 g/mL at 20 °C.
         */
        data class PercentByVolume(val densityGramsPerML: Double) : Concentration

        /**
         * A dissolved solid at a concentration in `canonicalUnit` per mL — the
         * strength field *is* that concentration (mg/mL for an injectable ester in
         * oil). `mass = volume × concentration`, no density term.
         *
         * The concentration varies by ester and by product, so it is user-entered
         * per dose and saved as a preset — never a shipped default.
         */
        data object MassPerVolume : Concentration
    }

    /**
     * Canonical-unit amount for a measured [volumeML] at the given [strength].
     * For [Concentration.PercentByVolume], strength is ABV % and the result is
     * grams of ethanol.
     */
    fun canonicalAmount(volumeML: Double, strength: Double): Double = when (val c = concentration) {
        is Concentration.PercentByVolume -> grams(volumeML, strength, c.densityGramsPerML)
        Concentration.MassPerVolume -> mass(volumeML, strength)
    }

    /**
     * Inverse of [canonicalAmount] — the volume that yields [amount] canonical
     * units at [strength]. Keeps the volume field consistent when the dose is
     * edited by mass. Zero for a strength of zero.
     */
    fun volumeML(amount: Double, strength: Double): Double = when (val c = concentration) {
        is Concentration.PercentByVolume -> volumeML(grams = amount, abv = strength, densityGramsPerML = c.densityGramsPerML)
        Concentration.MassPerVolume -> volumeML(mass = amount, concentrationPerML = strength)
    }

    /** A dissolved-solid concentration (mg/mL) rather than alcohol's %ABV. Drives the shared UI's copy so one component serves both adopters. */
    val isMassPerVolume: Boolean get() = concentration is Concentration.MassPerVolume

    /** Title for the strength field: "Concentration" for an ester, "Strength" for alcohol. */
    val strengthFieldLabel: String get() = if (isMassPerVolume) "Concentration" else "Strength"

    /** Unit suffix on the strength field: "mg/mL" for an ester, "%" for alcohol. */
    val strengthUnitLabel: String get() = if (isMassPerVolume) "$canonicalUnit/mL" else "%"

    companion object {
        /**
         * Ethanol density at 20 °C (g/mL) and the US standard-drink mass (g), for
         * the targets that cannot read `by_volume_dosing`: the watch, which has no
         * database on the wrist, and the pure engine conversions a widget runs in
         * its own process. Everything inside the app takes these off the resolved
         * [ByVolumeDosing] capability instead.
         *
         * A `ByVolumeDosingDbTest` asserts each equals its `by_volume_dosing`
         * column, so the two can be one edit apart but never silently disagree.
         * Never add a third declaration — that fork is what this pair exists to end.
         */
        const val ETHANOL_DENSITY_GRAMS_PER_ML: Double = 0.789
        const val US_STANDARD_DRINK_GRAMS: Double = 14.0

        /** The two input-mode labels: (volume-mode, mass-mode). One pair for every adopter. */
        val MODE_LABELS: Pair<String, String> = "By Volume" to "By Mass"

        /**
         * Grams of ethanol in [volumeML] at [abv] percent.
         *
         * Non-finite or non-positive inputs yield zero: a blank or garbled field
         * means no dose, never a crash and never a NaN that would propagate into
         * the curve.
         */
        fun grams(
            volumeML: Double,
            abv: Double,
            densityGramsPerML: Double = ETHANOL_DENSITY_GRAMS_PER_ML,
        ): Double {
            if (!volumeML.isFinite() || !abv.isFinite() || !densityGramsPerML.isFinite()) return 0.0
            if (volumeML <= 0 || abv <= 0 || densityGramsPerML <= 0) return 0.0
            return volumeML * (abv / 100) * densityGramsPerML
        }

        /** Inverse of [grams]: the volume that yields [grams] of ethanol at [abv]. Non-positive inputs yield zero. */
        fun volumeML(
            grams: Double,
            abv: Double,
            densityGramsPerML: Double = ETHANOL_DENSITY_GRAMS_PER_ML,
        ): Double {
            if (!grams.isFinite() || !abv.isFinite() || !densityGramsPerML.isFinite()) return 0.0
            if (grams <= 0 || abv <= 0 || densityGramsPerML <= 0) return 0.0
            return grams / ((abv / 100) * densityGramsPerML)
        }

        /** Canonical mass in [volumeML] of a solution at [concentrationPerML]. Non-positive inputs yield zero. */
        fun mass(volumeML: Double, concentrationPerML: Double): Double {
            if (!volumeML.isFinite() || !concentrationPerML.isFinite()) return 0.0
            if (volumeML <= 0 || concentrationPerML <= 0) return 0.0
            return volumeML * concentrationPerML
        }

        /** Inverse of [mass]: the volume that holds [mass] at [concentrationPerML]. Non-positive inputs yield zero. */
        fun volumeML(mass: Double, concentrationPerML: Double): Double {
            if (!mass.isFinite() || !concentrationPerML.isFinite()) return 0.0
            if (mass <= 0 || concentrationPerML <= 0) return 0.0
            return mass / concentrationPerML
        }

        /**
         * Approximate US standard-drink equivalent of a grams-of-ethanol amount —
         * the intuitive gloss shown alongside the canonical grams. Convention
         * dependent (US 14 g); label it as such, never a safety line.
         */
        fun standardDrinks(grams: Double): Double {
            if (!grams.isFinite() || grams <= 0) return 0.0
            return grams / US_STANDARD_DRINK_GRAMS
        }

        /**
         * Trim a numeric value for display and storage: integer when whole, else
         * one decimal. Shared by the input field, the presets and the breadcrumb.
         *
         * `Locale.ROOT` is load-bearing rather than tidy. This string is written
         * into a dose's notes and **parsed back** by [ByVolumeBreadcrumb], whose
         * pattern expects a `.` decimal separator — so on a device set to a
         * comma-decimal locale, the locale-sensitive form would write "5,5% ABV"
         * and the parse would then find no breadcrumb at all, silently losing the
         * origin of every by-volume dose.
         */
        fun formatTrimmed(value: Double): String {
            if (!value.isFinite()) return "0"
            if (round(value) == value) return value.toInt().toString()
            return String.format(Locale.ROOT, "%.1f", value)
        }
    }
}

/**
 * A tappable drink preset: a fixed measured volume and a default strength the
 * user can nudge.
 *
 * Swift stores this as a `Measurement<UnitVolume>` so a "pint" is 568 mL whether
 * the user's locale shows it as mL or fl oz. Here the volume is millilitres,
 * which is the unit the conversion already works in — the display unit is a
 * formatting concern that arrives with the locale work, and storing the
 * presentation unit alongside the value is what would let the two drift.
 */
data class DrinkPreset(
    val kind: Kind,
    /** Pre-filled ABV %, user-adjustable. */
    val defaultABV: Double,
    val volumeML: Double,
) {
    /**
     * The closed vocabulary `drink_presets.kind` is written in.
     *
     * The volume and strength are data; the label and the symbol are app copy keyed
     * by the case, so a row naming a kind this build does not know is dropped
     * rather than rendered as a raw string.
     */
    enum class Kind(val wireValue: String) {
        BEER("beer"),
        WINE("wine"),
        SHOT("shot"),
        PINT("pint"),
        ;

        /** Default emoji for the curated seed — the by-drink UI is emoji-first. */
        val emoji: String
            get() = when (this) {
                BEER -> "🍺"
                WINE -> "🍷"
                SHOT -> "🥃"
                PINT -> "🍺"
            }

        companion object {
            fun fromWire(value: String?): Kind? = entries.firstOrNull { it.wireValue == value }
        }
    }

    val id: Kind get() = kind
}

/**
 * The by-volume origin stored on a dose's notes — canonical millilitres and ABV,
 * with an optional drink name (`"IPA · 568 mL · 6% ABV"` or `"330 mL · 5% ABV"`).
 *
 * The form prepends it on save and re-derives the name, volume and strength
 * fields from it on edit. The canonical-mL form keeps the parse
 * locale-independent.
 *
 * Ported from `Shared/ByVolumeDosing.swift`.
 */
object ByVolumeBreadcrumb {

    const val SEPARATOR: String = " · "

    /**
     * The pattern is compiled with `UNICODE_CHARACTER_CLASS`, so `\s` means
     * Unicode whitespace as it does in the Swift regex literal rather than the
     * ASCII-only set Kotlin's `\s` defaults to. The breadcrumb this app writes
     * uses plain spaces, so the two agree on everything it produces — the flag is
     * for a notes field the user has typed into, where a non-breaking space is
     * one paste away and an ASCII-only `\s` would silently stop matching.
     */
    private val PATTERN: Regex = Pattern.compile(
        """([0-9]+(?:\.[0-9]+)?)\s*mL\s*·\s*([0-9]+(?:\.[0-9]+)?)\s*%\s*ABV""",
        Pattern.UNICODE_CHARACTER_CLASS or Pattern.CASE_INSENSITIVE,
    ).toRegex()

    fun make(name: String? = null, volumeML: Double, abv: Double): String {
        val core = "${ByVolumeDosing.formatTrimmed(volumeML)} mL$SEPARATOR" +
            "${ByVolumeDosing.formatTrimmed(abv)}% ABV"
        val trimmed = name?.trimUnicodeWhitespace()
        return if (trimmed.isNullOrEmpty()) core else "$trimmed$SEPARATOR$core"
    }

    /**
     * First `"[name · ]<v> mL · <a>% ABV"` found in [notes] — prepended as the
     * leading line — or null. Case-insensitive and whitespace-tolerant. [Parsed.name]
     * is the optional text before the volume, with its trailing separator removed.
     */
    fun parse(notes: String): Parsed? {
        for (line in notes.split("\n")) {
            val match = PATTERN.find(line) ?: continue
            val volumeML = match.groupValues[1].toDoubleOrNull() ?: continue
            val abv = match.groupValues[2].toDoubleOrNull() ?: continue
            var prefix = line.substring(0, match.range.first).trimUnicodeWhitespace()
            if (prefix.endsWith("·")) prefix = prefix.dropLast(1).trimUnicodeWhitespace()
            return Parsed(name = prefix.ifEmpty { null }, volumeML = volumeML, abv = abv)
        }
        return null
    }

    /**
     * [notes] without the breadcrumb line, so the form shows the user's own text
     * and not the machine prefix.
     */
    fun strip(notes: String): String =
        notes.split("\n").filter { parse(it) == null }.joinToString("\n").trimUnicodeWhitespace()

    /** A parsed breadcrumb: the optional drink name, the volume and the strength. */
    data class Parsed(val name: String?, val volumeML: Double, val abv: Double)
}
