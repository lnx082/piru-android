package glass.kagerou.piru.data

import kotlinx.serialization.json.Json

/**
 * The iOS models keep every small list in a JSON blob rather than a related
 * table — a deliberate choice so the same flat schema compiles into the widget
 * and Live Activity extensions, which have no relations of their own. The
 * column type differs (SwiftData `Data`, Room `TEXT`) but the payload is the
 * same JSON array, so these helpers read and write exactly what the iOS side
 * does and a backup round-trips unchanged.
 *
 * A malformed or empty blob reads as an empty list rather than throwing. That
 * mirrors the iOS accessors, which are written as `(try? …) ?? []` — a corrupt
 * list must not make the whole row unreadable.
 */
object JsonLists {

    private val json = Json { ignoreUnknownKeys = true }

    fun ints(raw: String?): List<Int> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching { json.decodeFromString<List<Int>>(raw) }.getOrDefault(emptyList())
    }

    fun strings(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    /** Encode, writing an empty string for an empty list — how the iOS side writes "no list". */
    fun encode(values: List<Int>): String =
        if (values.isEmpty()) "" else json.encodeToString(values)

    fun encodeStrings(values: List<String>): String =
        if (values.isEmpty()) "" else json.encodeToString(values)
}
