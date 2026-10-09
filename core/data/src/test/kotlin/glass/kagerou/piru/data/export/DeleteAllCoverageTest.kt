package glass.kagerou.piru.data.export

import io.kotest.matchers.collections.shouldBeEmpty
import java.io.File
import org.junit.jupiter.api.Test

/**
 * Every table the database stores can be deleted from, and the wipe reaches all of them.
 *
 * ## The defect this exists for
 * BUG #16: `deleteAll` missed `dose_routines`, a table that also had **no DAO at all** — so nothing could clear it and
 * a "delete everything" left those rows behind. The wipe and the export have to agree about what "all the data" means,
 * and each keeping its own list is how they stop agreeing.
 *
 * ## Why it reads the sources rather than a list
 * A hand-written list of tables is the thing that goes stale — that is exactly how the settings export fell from two
 * keys to ten unexported ones. This walks the module's own sources: every `@Entity` yields a table, and every
 * `DELETE FROM` yields a table that can be cleared. Both sides are **read**, so neither can be invented.
 *
 * ## What it does not prove
 * That the wipe is *called* on a restore, and that it is correct at runtime — `RoomSchemaTest` pins the schema and the
 * device specs pin the behaviour. This pins the one thing that failed before: a table with no way to clear it.
 */
class DeleteAllCoverageTest {

    /** `src/main/kotlin/glass/kagerou/piru/data`, found from the test's working directory. */
    private val dataSourceRoot: File = run {
        val candidates = listOf(
            File("src/main/kotlin/glass/kagerou/piru/data"),
            File("core/data/src/main/kotlin/glass/kagerou/piru/data"),
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error(
                "the data module's sources were not found from ${File(".").absolutePath}; " +
                    "a check that reads nothing must not pass",
            )
    }

    private fun sources(): List<File> = dataSourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    /**
     * The tables the `@Entity` classes declare.
     *
     * ## Four patterns were wrong before this one, and the guard caught every one
     * The real annotations are **multi-line** and carry `indices = [ … ]`, whose bracket list can contain a `)`.
     * `@Entity\(([^)]*)\)` therefore matched nothing or stopped in the wrong place, and a "match up to the `)` on its
     * own line" variant matched nothing at all.
     *
     * So this stops pattern-matching the annotation. It finds each `@Entity` occurrence, then reads **forward** for the
     * two things that are actually needed: a `tableName = "…"` if one appears before the class, and the class name
     * after it. That cannot be confused by nesting, because it never tries to find the annotation's end.
     *
     * Room derives a table name from the class name when none is given, which is the fallback.
     */
    private fun entityTables(): Set<String> {
        val tables = mutableSetOf<String>()
        for (file in sources()) {
            val text = file.readText()
            var index = text.indexOf("@Entity")
            while (index >= 0) {
                // The class this annotation belongs to: the next `class X` at word boundary.
                val classMatch = Regex("""\bclass\s+(\w+)""").find(text, index)
                if (classMatch == null) break
                // A `tableName` between the annotation and the class belongs to this annotation.
                val between = text.substring(index, classMatch.range.first)
                val explicit = Regex("""tableName\s*=\s*"([^"]+)"""").find(between)?.groupValues?.get(1)
                tables += explicit ?: classMatch.groupValues[1]
                    // Room's own derivation: camel case to snake case.
                    .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
                    .lowercase()
                index = text.indexOf("@Entity", classMatch.range.last)
            }
        }
        return tables
    }

    /** Every table any source in the module deletes from. */
    private fun deletableTables(): Set<String> {
        val tables = mutableSetOf<String>()
        for (file in sources()) {
            for (match in Regex("""DELETE\s+FROM\s+([A-Za-z_][A-Za-z0-9_]*)""", RegexOption.IGNORE_CASE)
                .findAll(file.readText())) {
                tables += match.groupValues[1]
            }
        }
        return tables
    }

    /**
     * The tables the wipe itself reaches, by the DAO interfaces it calls.
     *
     * Resolved by **searching every source for the interface**, not by deriving a filename: `CuratedDaos.kt` holds
     * several DAOs, and an earlier version of this check reported two false negatives because it assumed one interface
     * per file.
     */
    private fun wipedTables(): Set<String> {
        val wipeText = File(dataSourceRoot, "export/DataExportImport.kt").readText()
        val start = wipeText.indexOf("suspend fun deleteAll(")
        check(start >= 0) { "the wipe was not found in DataExportImport.kt" }
        val end = wipeText.indexOf("\n    }", start)
        check(end > start) { "the wipe's body was not delimited" }
        val body = wipeText.substring(start, end)

        val accessors = Regex("""db\.(\w+)\(\)\.\w+\(\)""").findAll(body).map { it.groupValues[1] }.toSet()
        check(accessors.size >= 10) { "only ${accessors.size} accessors were found, which is too few to be the wipe" }

        val all = sources()
        val tables = mutableSetOf<String>()
        for (accessor in accessors) {
            val interfaceName = accessor.replaceFirstChar { it.uppercase() }
            for (file in all) {
                val text = file.readText()
                if (Regex("""interface\s+${Regex.escape(interfaceName)}\b""").containsMatchIn(text)) {
                    tables += Regex("""DELETE\s+FROM\s+([A-Za-z_][A-Za-z0-9_]*)""", RegexOption.IGNORE_CASE)
                        .findAll(text).map { it.groupValues[1] }.toSet()
                }
            }
        }
        return tables
    }

    /** The check read something on both sides. Without this, "none missing" would mean nothing. */
    @Test
    fun `the check found entities and deletes to compare`() {
        val entities = entityTables()
        val deletable = deletableTables()
        println("DELETEAUDPROBE entities=" + entities.sorted())
        println("DELETEAUDPROBE deletable=" + deletable.sorted())
        check(entities.size >= 10) { "only ${entities.size} entity tables were read" }
        check(deletable.size >= 10) { "only ${deletable.size} deletable tables were read" }
    }

    /**
     * Every entity table has something that can delete from it.
     *
     * The BUG #16 shape: a table with no DAO and therefore no way to clear it.
     */
    @Test
    fun `every table can be deleted from`() {
        (entityTables() - deletableTables()).shouldBeEmpty()
    }

    /** And the wipe reaches every one of them, which is the stronger claim. */
    @Test
    fun `the wipe reaches every table`() {
        val wiped = wipedTables()
        println("DELETEAUDPROBE wiped=" + wiped.sorted())
        check(wiped.size >= 10) { "only ${wiped.size} tables were resolved from the wipe" }
        (entityTables() - wiped).shouldBeEmpty()
    }
}
