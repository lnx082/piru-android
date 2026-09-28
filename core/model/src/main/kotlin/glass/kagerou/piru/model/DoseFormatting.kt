package glass.kagerou.piru.model

import java.util.Locale

/**
 * Format a dose value with sensible rounding — no false precision.
 *
 * Ported from `Shared/Formatting/DoseFormatting.swift`.
 *
 * ## Locale.ROOT is load-bearing
 * `String.format` without an explicit locale uses the *default* one, so on a
 * device set to a comma-decimal locale a dose of 44.66 would render `"44,7"` —
 * and every one of the iOS expectations in `DoseFormattedTests.swift` would
 * fail on that device alone. Swift's `String(format:)` has no such behaviour.
 *
 * ## One known divergence, deliberate
 * Java's `%f` rounds half-up; C's `printf` — which Swift's `String(format:)`
 * sits on — rounds half-to-even. The two differ only on an exact tie at the
 * cut (2.5 → `"3"` here, `"2"` there). No dose in the catalog and none of the
 * upstream assertions land on a tie, and matching C would mean reimplementing
 * the formatter; noting it here is more useful than a silent difference.
 */
fun doseFormatted(value: Double): String {
    val magnitude = kotlin.math.abs(value)
    if (magnitude == 0.0) return "0"
    // 100+ rounds to a whole number — "250".
    if (magnitude >= 100) return String.format(Locale.ROOT, "%.0f", value)
    // 10–99 takes one decimal, below 10 takes two; both then shed trailing
    // zeros, which is why 10.0 reads "10" and 5.10 reads "5.1".
    val format = if (magnitude >= 10) "%.1f" else "%.2f"
    return trimZeros(String.format(Locale.ROOT, format, value))
}

/**
 * [doseFormatted] with thousands separators — for stock figures, which run
 * large (a 50,000 mg jar, 124,000 IU). Same rounding tiers, just grouped.
 *
 * Grouping follows the device locale, as the iOS `.formatted(.number.…)` does.
 */
fun inventoryFormatted(value: Double): String {
    val magnitude = kotlin.math.abs(value)
    val fractionDigits = when {
        magnitude >= 100 -> 0
        magnitude >= 10 -> 1
        else -> 2
    }
    return String.format(Locale.getDefault(), "%,.${fractionDigits}f", value)
}

/**
 * Display nicety for the data-driven unit string "units" (legacy and imported
 * alcohol doses): exactly one reads "unit", not "1 units".
 *
 * Unit strings are unlocalized data ("mg", "g", "units"), so this is plain
 * English surgery — every other unit passes through untouched.
 */
fun unitDisplay(unit: String, amount: Double): String =
    if (amount == 1.0 && unit.equals("units", ignoreCase = true)) "unit" else unit

/**
 * Shed trailing zeros, then a trailing dot.
 *
 * Hand-rolled rather than a regex, as it is upstream: this runs per chip label
 * and inside hot equality paths, and the regular expression showed up in
 * profiles.
 */
private fun trimZeros(formatted: String): String {
    if (!formatted.contains('.')) return formatted
    var end = formatted.length
    while (end > 0 && formatted[end - 1] == '0') end--
    if (end > 0 && formatted[end - 1] == '.') end--
    return formatted.substring(0, end)
}
