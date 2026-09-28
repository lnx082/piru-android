package glass.kagerou.piru.model

/**
 * How a dose was taken.
 *
 * [wireValue] is the iOS `rawValue` and is what the bundled database and every
 * exported file carry.
 */
enum class RouteOfAdministration(val wireValue: String) {
    ORAL("oral"),

    SUBLINGUAL("sublingual"),

    /**
     * Absorbed across the cheek mucosa — nicotine pouches, snus, buccal films.
     * Distinct from [ORAL]: it bypasses first-pass metabolism, so it has its own
     * (much shorter) duration profile. The bundled database has carried buccal
     * rows all along; without this case they parsed to [OTHER], so a pouch
     * showed an "Other" pill sorted last and defaulted to oral's six-hour curve.
     */
    BUCCAL("buccal"),

    INSUFFLATION("insufflation"),
    INHALATION("inhalation"),
    INTRAVENOUS("intravenous"),
    INTRAMUSCULAR("intramuscular"),
    SUBCUTANEOUS("subcutaneous"),
    TRANSDERMAL("transdermal"),
    RECTAL("rectal"),
    OTHER("other"),
    ;

    /** The English label. The localized label lives with the app's resources. */
    val displayName: String
        get() = when (this) {
            ORAL -> "Oral"
            SUBLINGUAL -> "Sublingual"
            BUCCAL -> "Buccal"
            INSUFFLATION -> "Insufflation"
            INHALATION -> "Inhalation"
            INTRAVENOUS -> "Intravenous"
            INTRAMUSCULAR -> "Intramuscular"
            SUBCUTANEOUS -> "Subcutaneous"
            TRANSDERMAL -> "Transdermal"
            RECTAL -> "Rectal"
            OTHER -> "Other"
        }

    companion object {
        /**
         * Parse a route string from TripSit/OpenFDA into our enum.
         *
         * Unknown routes become [OTHER] rather than failing — the upstream
         * vocabulary is open-ended and a new spelling must not drop a dose.
         */
        fun from(string: String): RouteOfAdministration = when (string.trim().lowercase()) {
            "oral", "oral_ir", "oral_er", "oral(benzedrex)", "oral(pure)" -> ORAL
            "sublingual" -> SUBLINGUAL
            "buccal", "buccally", "pouch", "snus" -> BUCCAL
            "insufflated", "insufflation", "insufflated(pure)", "intranasal", "nasal" -> INSUFFLATION
            "inhaled", "inhalation", "smoked", "vapourized", "vaporized" -> INHALATION
            "intravenous", "iv" -> INTRAVENOUS
            "intramuscular", "im" -> INTRAMUSCULAR
            "subcutaneous" -> SUBCUTANEOUS
            "transdermal", "topical" -> TRANSDERMAL
            "rectal", "plugged" -> RECTAL
            else -> OTHER
        }
    }
}
