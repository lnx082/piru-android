package glass.kagerou.piru.data.export

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The text shape Foundation's `JSONEncoder` writes with
 * `outputFormatting = [.prettyPrinted, .sortedKeys]` — the only two options the
 * iOS export sets, and therefore the shape every Piru file on disk has.
 *
 * Ported from `Piru/Utilities/DataExportImport.swift` (`encodeJSON`).
 *
 * ## Why this is hand-written rather than `Json.prettyPrint`
 * kotlinx's pretty printer emits `"key": value` and preserves the declaration
 * order of the properties it walks. Foundation emits `"key" : value` — a space on
 * **both** sides of the colon — and sorts keys. Neither difference changes what a
 * parser reads, so this is not about correctness of the *data*; it is about the
 * file being the same file. The export is the one artifact a user carries between
 * two apps and may diff, checksum, or hand-inspect, and "almost identical" is a
 * worse answer there than exactly identical.
 *
 * ## What is pinned, and where the guesses are
 * The three rules below are the ones the source settles:
 *
 * 1. **Two-space indent per level**, newline after `{`/`[` and before `}`/`]`.
 * 2. **`" : "` between key and value**, and `,\n` between siblings.
 * 3. **Keys sorted** ascending. All Piru keys are ASCII, so Foundation's
 *    `String <` and Kotlin's `String.compareTo` order them identically.
 *
 * One rule is *not* settled by the source and is a judgement call, isolated in
 * [EMPTY_CONTAINER_LINES] and [numberText] so either is a one-line change:
 *
 * - An **empty** array or object is written as an opening bracket, a blank line,
 *   and a closing bracket at the container's own indent — `[\n\n]`. That is what
 *   Foundation's pretty printer does; it does not collapse to `[]`.
 * - A **`Double` whose value is integral** is written without a decimal point —
 *   `1.0` reaches the file as `1`. Foundation routes a `Double` through
 *   `NSNumber`, whose description drops the trailing `.0`.
 *
 * These matter only to the byte-for-byte comparison, never to a reader: every
 * JSON parser in both apps accepts either spelling.
 */
object FoundationJSON {

    /**
     * How many newlines separate an empty container's brackets. Foundation writes
     * two — the one it would have put after the opener, and the one before the
     * closer — with nothing between them.
     */
    private const val EMPTY_CONTAINER_LINES = "\n\n"

    private val reader = Json { ignoreUnknownKeys = true }

    /**
     * Parse [text], or null when it is not JSON at all.
     *
     * The leading-character guard is not belt and braces: kotlinx's
     * `parseToJsonElement` reads a bare unquoted word as a `JsonPrimitive`, where
     * Foundation's `JSONSerialization` refuses it. Without the guard, a file of
     * the word `nonsense` would be reported as "valid JSON but not a Piru export"
     * instead of "the file isn't valid JSON" — a different sentence about a
     * different problem.
     */
    fun parse(text: String): JsonElement? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val first = trimmed[0]
        val startsLikeJson = first == '{' || first == '[' || first == '"' || first == '-' ||
            first.isDigit() ||
            trimmed.startsWith("true") || trimmed.startsWith("false") || trimmed.startsWith("null")
        if (!startsLikeJson) return null
        return runCatching { reader.parseToJsonElement(trimmed) }.getOrNull()
    }

    /** Render [element] exactly as the iOS encoder would have. */
    fun write(element: JsonElement): String = StringBuilder(1 shl 12).also { write(element, 0, it) }.toString()

    // MARK: - The writer

    private fun write(element: JsonElement, depth: Int, out: StringBuilder) {
        when (element) {
            is JsonNull -> out.append("null")
            is JsonPrimitive -> writePrimitive(element, out)
            is JsonArray -> writeArray(element, depth, out)
            // Exhaustive over kotlinx's sealed JsonElement, so a new case is a
            // compile error rather than a silently dropped value.
            is JsonObject -> writeObject(element, depth, out)
        }
    }

    private fun writeArray(array: JsonArray, depth: Int, out: StringBuilder) {
        out.append('[')
        if (array.isEmpty()) {
            out.append(EMPTY_CONTAINER_LINES)
            indent(depth, out)
            out.append(']')
            return
        }
        out.append('\n')
        array.forEachIndexed { index, child ->
            if (index > 0) out.append(",\n")
            indent(depth + 1, out)
            write(child, depth + 1, out)
        }
        out.append('\n')
        indent(depth, out)
        out.append(']')
    }

    private fun writeObject(obj: JsonObject, depth: Int, out: StringBuilder) {
        out.append('{')
        // `.sortedKeys`. Sorted here rather than at construction so every object
        // in the tree is covered by one call site, including the ones a nested
        // serializer builds.
        val keys = obj.keys.sorted()
        if (keys.isEmpty()) {
            out.append(EMPTY_CONTAINER_LINES)
            indent(depth, out)
            out.append('}')
            return
        }
        out.append('\n')
        keys.forEachIndexed { index, key ->
            if (index > 0) out.append(",\n")
            indent(depth + 1, out)
            writeString(key, out)
            out.append(" : ")
            write(obj.getValue(key), depth + 1, out)
        }
        out.append('\n')
        indent(depth, out)
        out.append('}')
    }

    private fun indent(depth: Int, out: StringBuilder) {
        repeat(depth) { out.append("  ") }
    }

    private fun writePrimitive(primitive: JsonPrimitive, out: StringBuilder) {
        if (primitive.isString) {
            writeString(primitive.content, out)
            return
        }
        out.append(numberText(primitive.content))
    }

    /**
     * A number's text, with an integral `Double` collapsed to an integer.
     *
     * See the class note. Bounded at 2^53 because past that a `Double` no longer
     * names distinct integers and [Double.toLong] would invent digits.
     */
    private fun numberText(raw: String): String {
        if (raw.none { it == '.' || it == 'e' || it == 'E' }) return raw
        val value = raw.toDoubleOrNull() ?: return raw
        if (!value.isFinite()) return raw
        if (value == Math.floor(value) && Math.abs(value) < 9_007_199_254_740_992.0) {
            return value.toLong().toString()
        }
        return raw
    }

    /**
     * A JSON string literal.
     *
     * Escapes exactly what JSON requires. Forward slashes are **not** escaped:
     * Foundation can escape them (`NSJSONWritingWithoutEscapingSlashes` implies
     * they are escaped by default), and this build assumes its `JSONEncoder` does
     * not, which is what every modern Swift writer does. A slash is legal either
     * way and both readers accept both spellings.
     */
    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else ->
                    if (ch < ' ') {
                        // Locale.ROOT always: the port's rule for every format call.
                        out.append(String.format(Locale.ROOT, "\\u%04x", ch.code))
                    } else {
                        out.append(ch)
                    }
            }
        }
        out.append('"')
    }
}
