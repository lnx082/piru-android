package glass.kagerou.piru.data.export

import glass.kagerou.piru.model.LegacyColorImport
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.toOklch

/**
 * PsychonautWiki's fixed colour palette, and the nearest-name search.
 *
 * Ported from `PsyLogColorMap` in
 * `Piru/Utilities/DataExportImport+PsyLog.swift`.
 *
 * PW stores a **name** from this list, not a colour, so any Piru colour exports
 * as whichever of the fifty-four is closest in Oklab. The table is copied
 * verbatim — every hex here is a name PW's importer has to recognize, and
 * "roughly this red" would be a different name than the one iOS writes.
 */
internal object PsyLogColorMap {

    private val NAME_TO_HEX: Map<String, String> = mapOf(
        "BLUE" to "007AFF",
        "PURPLE" to "AF52DE",
        "PINK" to "FF2D55",
        "RED" to "FF3B30",
        "ORANGE" to "FF9500",
        "YELLOW" to "FFCC00",
        "GREEN" to "34C759",
        "CYAN" to "00BCD4",
        "MINT" to "00C7BE",
        "TEAL" to "30B0C7",
        "INDIGO" to "5856D6",
        "BROWN" to "A2845E",
        "DEEP_PINK" to "FF1493",
        "MAGENTA" to "E91E63",
        "FUCHSIA" to "CA1F7B",
        "CRIMSON" to "D32F2F",
        "FIRE_ENGINE_RED" to "CE2029",
        "MAROON" to "800000",
        "BURGUNDY" to "800020",
        "SCARLET" to "FF2400",
        "CINNABAR" to "E34234",
        "BYZANTIUM" to "702963",
        "JAZZBERRY_JAM" to "A50B5E",
        "DARK_MAGENTA" to "8B008B",
        "HELIOTROPE" to "DF73FF",
        "DEEP_LAVENDER" to "CE93D8",
        "LIME_GREEN" to "32CD32",
        "ROYAL_BLUE" to "4169E1",
        "AUBURN" to "A52A2A",
        "BLUE_VIOLET" to "8A2BE2",
        "BRONZE" to "CD7F32",
        "CORAL" to "FF7F50",
        "DARK_GOLD" to "B8860B",
        "DARK_OLIVE_GREEN" to "556B2F",
        "DARK_ORANGE" to "FF8C00",
        "DARK_TURQUOISE" to "00CED1",
        "DARK_VIOLET" to "9400D3",
        "DODGER_BLUE" to "1E90FF",
        "FOREST_GREEN" to "228B22",
        "GOLD" to "FFD700",
        "GRAYISH_MAGENTA" to "9E7C93",
        "HOT_PINK" to "FF69B4",
        "JUNGLE_GREEN" to "29AB87",
        "KHAKI" to "BDB76B",
        "LIGHT_SEA_GREEN" to "20B2AA",
        "LIME" to "00FF00",
        "MOSS_GREEN" to "8A9A5B",
        "OLIVE" to "808000",
        "OLIVE_DRAB" to "6B8E23",
        "ORANGE_RED" to "FF4500",
        "RUST" to "B7410E",
        "SADDLE_BROWN" to "8B4513",
        "SEA_GREEN" to "2E8B57",
        "TOMATO" to "FF6347",
    )

    /**
     * The palette name nearest [tint], by squared Oklab distance.
     *
     * The search is over the map's *iteration* order upstream and ties therefore
     * resolve to whichever entry came first — which for a Swift `Dictionary` is
     * not a stable order either. A tie here needs two palette entries equidistant
     * from one colour, which the table has none of, so the difference is
     * unreachable; sorted order is used here because it makes the result
     * deterministic rather than because it changes it.
     */
    fun name(nearest: P3Color): String {
        val target = oklab(nearest)
        var closest = "BLUE"
        var minDistance = Double.MAX_VALUE
        for ((name, hex) in NAME_TO_HEX.toSortedMap()) {
            val candidate = oklab(LegacyColorImport.p3(hex))
            val distance = square(target.first - candidate.first) +
                square(target.second - candidate.second) +
                square(target.third - candidate.third)
            if (distance < minDistance) {
                minDistance = distance
                closest = name
            }
        }
        return closest
    }

    /** Oklch as Cartesian Oklab, which is the space the distance is measured in. */
    private fun oklab(tint: P3Color): Triple<Double, Double, Double> {
        val color = tint.toOklch()
        val radians = Math.toRadians(color.h)
        return Triple(color.l, color.c * Math.cos(radians), color.c * Math.sin(radians))
    }

    private fun square(value: Double): Double = value * value
}

/**
 * The route names PsychonautWiki's journal uses.
 *
 * Ported from the `RouteOfAdministration.psylogName` extension. The spelling is
 * PW's, not the wire value's — `INSUFFLATED`, not `insufflation` — and `other`
 * degrades to `ORAL`, which is what upstream does rather than inventing a name PW
 * would reject.
 */
internal fun RouteOfAdministration.psylogName(): String = when (this) {
    RouteOfAdministration.ORAL -> "ORAL"
    RouteOfAdministration.SUBLINGUAL -> "SUBLINGUAL"
    // PW's journal has BUCCAL, so this round-trips rather than degrading to ORAL.
    RouteOfAdministration.BUCCAL -> "BUCCAL"
    RouteOfAdministration.INSUFFLATION -> "INSUFFLATED"
    RouteOfAdministration.INHALATION -> "INHALED"
    RouteOfAdministration.INTRAVENOUS -> "INTRAVENOUS"
    RouteOfAdministration.INTRAMUSCULAR -> "INTRAMUSCULAR"
    RouteOfAdministration.SUBCUTANEOUS -> "SUBCUTANEOUS"
    RouteOfAdministration.TRANSDERMAL -> "TRANSDERMAL"
    RouteOfAdministration.RECTAL -> "RECTAL"
    RouteOfAdministration.OTHER -> "ORAL"
}

/**
 * The inverse of [psylogName], including the spellings older PW files use.
 *
 * Ported from `RouteOfAdministration.init(psylogName:)`. An unrecognized name is
 * [RouteOfAdministration.OTHER], matching upstream, which has no error case here.
 */
internal fun routeFromPsyLogName(name: String): RouteOfAdministration =
    when (name.uppercase(java.util.Locale.ROOT)) {
        "ORAL" -> RouteOfAdministration.ORAL
        "SUBLINGUAL" -> RouteOfAdministration.SUBLINGUAL
        "BUCCAL" -> RouteOfAdministration.BUCCAL
        "INSUFFLATED" -> RouteOfAdministration.INSUFFLATION
        "INHALED" -> RouteOfAdministration.INHALATION
        "INTRAVENOUS" -> RouteOfAdministration.INTRAVENOUS
        "INTRAMUSCULAR" -> RouteOfAdministration.INTRAMUSCULAR
        "SUBCUTANEOUS" -> RouteOfAdministration.SUBCUTANEOUS
        "TRANSDERMAL" -> RouteOfAdministration.TRANSDERMAL
        "RECTAL" -> RouteOfAdministration.RECTAL
        else -> RouteOfAdministration.OTHER
    }

/**
 * The Shulgin scale's glyphs, as PiHKAL (1991) states them.
 *
 * Ported from `ShulginScale`. `0…4` map to ±, +, ++, +++, ++++; anything outside
 * that has no glyph and is left out of the report line rather than guessed at.
 */
internal object ShulginScale {
    fun glyph(level: Int): String? = when (level) {
        0 -> "±"
        1 -> "+"
        2 -> "++"
        3 -> "+++"
        4 -> "++++"
        else -> null
    }
}

/**
 * The three answers to "did it work?", in the **English words the portable
 * exports write**.
 *
 * Ported from `WorkedScale.exportWord`. The localized labels stay with the app's
 * resources; a report has to read the same whatever locale produced it, which is
 * why this half of the type is here and not there.
 */
internal object WorkedScale {
    fun exportWord(level: Int): String? = when (level) {
        -1 -> "less than usual"
        0 -> "about right"
        1 -> "more than usual"
        else -> null
    }
}
