package glass.kagerou.piru.model

/**
 * The cadence a medication or supplement is scheduled on.
 *
 * [wireValue] is the iOS `rawValue` and is what the store and every export
 * carry — [EVERY_OTHER_DAY] is `"everyOtherDay"`, not `"every_other_day"`.
 */
enum class DoseFrequency(val wireValue: String) {
    DAILY("daily"),
    EVERY_OTHER_DAY("everyOtherDay"),
    WEEKLY("weekly"),
    BIWEEKLY("biweekly"),
    MONTHLY("monthly"),

    /**
     * A chosen set of weekdays, stored as `1` = Sunday … `7` = Saturday —
     * Foundation's `Calendar` weekday convention, kept as-is so a stored
     * schedule means the same thing on both platforms.
     */
    SPECIFIC_DAYS("specificDays"),
    ;

    companion object {
        /** Resolve a stored cadence, defaulting to [DAILY] for a value this build predates. */
        fun fromWire(value: String): DoseFrequency =
            entries.firstOrNull { it.wireValue == value } ?: DAILY
    }
}
