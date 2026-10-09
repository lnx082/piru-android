package glass.kagerou.piru.ui.i18n

import io.kotest.matchers.shouldBe
import java.io.File
import java.util.IllegalFormatException
import java.util.Locale
import org.junit.jupiter.api.Test

/**
 * Every string resource must survive a `Formatter`, because that is what `Resources.getString` runs.
 *
 * ## The crash this exists for
 * 0.6.0 shipped `journal_dose_count` as **`%1 doses`** — a placeholder written without its type. It should have been
 * `%1$d doses`. `Resources.getString(id, count)` on that form throws
 *
 *     java.util.UnknownFormatConversionException: Conversion = ' '
 *
 * because `%1` parses as a conversion with flags and a width but **no type**, and the next character it reads is a
 * space. The journal's session cards render it, so the journal died — and it was **reported as an instant crash on
 * launch after upgrading**, which is why three rounds of reasoning about migrations and startup paths found nothing:
 * the fault was in a string resource, and nothing about either the string or its call site looks wrong.
 *
 * ## Why a test and not a script
 * I wrote three Python approximations of Java's format grammar and **each one was wrong** — the first missed a
 * trailing `%1`, the second missed `%1` before a Chinese bracket, and the third reported valid `%1$.0f%%` as broken.
 * Java's own `Formatter` is the authority, it is available here, and it is what actually threw. So the assertion is
 * `String.format` itself over every resource, with as many dummy arguments as the string has placeholders.
 *
 * ## What is and is not claimed
 * A string with **no** percent sign is skipped. A string whose percents are all literal (doubled) passes. A string
 * with positional placeholders is formatted with the right number of arguments, so a **mismatch between the
 * placeholders and the call site's argument count** is not caught here — that would need the call sites, and the
 * count is a separate check. What is caught is the class of fault that shipped: a percent that cannot be a
 * conversion at all.
 */
class StringResourceFormatTest {

    private val resourcesRoot = File("src/main/res")

    /**
     * Every locale directory under `res`, so a translation is held to the same rule as the default.
     *
     * The directory is named here without its glob, because a slash-star inside a block comment closes it.
     */
    private fun stringFiles(): List<File> =
        resourcesRoot.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("values") }
            ?.flatMap { it.listFiles()?.toList() ?: emptyList() }
            ?.filter { it.isFile && it.name.endsWith(".xml") }
            ?.sortedBy { it.path }
            ?: emptyList()

    /** One `<string>` from a resource file: its name, its raw body, and where it came from. */
    private data class Entry(val file: String, val name: String, val body: String)

    private fun entries(): List<Entry> {
        val pattern = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val out = mutableListOf<Entry>()
        for (file in stringFiles()) {
            for (match in pattern.findAll(file.readText())) {
                out += Entry(file.path, match.groupValues[1], match.groupValues[2])
            }
        }
        return out
    }

    /**
     * The resource directory is found, so a passing run is not a vacuous one.
     *
     * Asserted first because every other case in this file iterates the same list: if the path is wrong, they would
     * all pass over nothing, which is the failure mode this port has recorded more than once.
     */
    @Test
    fun `the resource files are found`() {
        val files = stringFiles()
        (files.isNotEmpty()) shouldBe true
        val all = entries()
        println("FORMATCHECK scanned ${files.size} files, ${all.size} strings")
        (all.size > 500) shouldBe true
        // Both locales are represented, so a translation is not silently skipped.
        (files.any { it.path.contains("values-zh") }) shouldBe true
    }

    /**
     * Every string with a placeholder survives `String.format`.
     *
     * The assertion that would have caught the 0.6.0 crash: `%1 doses` throws here with the same exception the
     * device threw.
     *
     * The stub arguments **match each conversion's type**, because passing a `String` for a `%d` raises
     * `IllegalFormatConversionException` — a failure of my stub rather than of the resource. That cost a run: the
     * first version passed `"x"` for everything and reported 236 false failures.
     */
    @Test
    fun `every string with a placeholder is a valid format`() {
        val failures = mutableListOf<String>()
        for (entry in entries()) {
            if ("%" !in entry.body) continue
            val args = stubArguments(entry.body)
            try {
                // The locale matters: a percent that is valid under one and not another is still a bug.
                String.format(Locale.ROOT, entry.body, *args)
                String.format(Locale.SIMPLIFIED_CHINESE, entry.body, *args)
            } catch (e: IllegalFormatException) {
                failures += "${e.javaClass.simpleName}: ${e.message}" +
                    "  <-  ${entry.file} ${entry.name} = ${entry.body.take(70)}"
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError(
                "${failures.size} string resource(s) cannot be formatted:\n" + failures.joinToString("\n"),
            )
        }
    }

    /**
     * One stub value per conversion in [format], of the type that conversion needs.
     *
     * Positional and plain conversions are both filled: a plain `%d` takes the next argument, and mixing the two
     * forms in one string is legal Java, so the array is sized to the larger demand.
     */
    private fun stubArguments(format: String): Array<Any> {
        val conversions = Regex("""%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])""")
            .findAll(format)
            .toList()
        val highest = conversions.mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0
        val plain = conversions.count { it.groupValues[1].isEmpty() }
        val size = maxOf(highest, plain)
        val args = Array<Any>(size) { 1 }
        // Fill positionally-declared slots with a type their conversion accepts.
        for ((index, match) in conversions.withIndex()) {
            val position = match.groupValues[1].toIntOrNull()
            val type = match.groupValues[2]
            val value: Any = when (type) {
                "s", "S" -> "x"
                // The float conversions take a `Double`/`Float`; an `Int` raises `f != java.lang.Integer`, which is a
                // failure of this stub rather than of the resource. That cost a run.
                "f", "e", "E", "g", "G", "a", "A" -> 1.0
                else -> 1
            }
            if (position != null && position in 1..size) args[position - 1] = value
            if (position == null && index < size) args[index] = value
        }
        return args
    }

    /**
     * And no string carries the specific shape that shipped: an index with no type.
     *
     * The `Formatter` check above catches it, but this names the fault so a failure reads as "this is the 0.6.0
     * crash" rather than as a generic format error — which is the difference between a five-minute fix and three
     * rounds of looking in the wrong place.
     */
    @Test
    fun `no placeholder is missing its type`() {
        val offenders = entries().filter { Regex("""%\d+(?![\d$])""").containsMatchIn(it.body) }
        if (offenders.isNotEmpty()) {
            throw AssertionError(
                "placeholder without a type (write %1\$d or %1\$s):\n" +
                    offenders.joinToString("\n") { "  ${it.file} ${it.name} = ${it.body.take(70)}" },
            )
        }
    }

    /**
     * And no string carries a bare percent that a future `getString(id, …)` would choke on.
     *
     * These are read with no arguments today, which is safe **only** because `Resources.getString(id)` does not run
     * a `Formatter`. That is a trap rather than a guarantee — `alcohol_abv_suffix` was `% 酒精` and crashed the
     * alcohol screen the moment anything formatted it.
     */
    @Test
    fun `no bare percent outside a conversion`() {
        val offenders = entries().filter { entry ->
            // Remove every valid conversion and every escaped percent; nothing may remain.
            val stripped = Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]|%%""")
                .replace(entry.body, "")
            "%" in stripped
        }
        if (offenders.isNotEmpty()) {
            throw AssertionError(
                "a bare % must be written %% unless it is a conversion:\n" +
                    offenders.joinToString("\n") { "  ${it.file} ${it.name} = ${it.body.take(70)}" },
            )
        }
    }
}
