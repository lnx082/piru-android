package glass.kagerou.piru.data.export

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import org.junit.jupiter.api.Test

/**
 * The wipe covers the schema, checked against the schema rather than against a list.
 *
 * ## Why this test reads source instead of running SQL
 * `deleteAll` is fifteen DAO calls and no SQL of its own; what can go wrong is that a **sixteenth table** is added
 * to `PiruDatabase.entities` and nobody remembers the wipe. That is a source-level fact, so a source-level test
 * is the right instrument — and this module's unit tests have no Room runtime (no Robolectric), so a source check
 * is also the only one available without a device.
 *
 * The defect this guards is real and has happened here twice: `dose_routines` sat in the schema from v1 with no
 * DAO, so the wipe could not address it, and `lab_measurements` lived outside the database entirely. Both are
 * covered now, and the function's own doc claims "the count is checked against `PiruDatabase`'s `entities` list
 * rather than maintained by hand" — a claim that, until this file, nothing checked.
 *
 * ## What it does not check
 * That each DAO's `deleteAll` deletes that table. That is Room's generated implementation and is pinned by
 * `CopyDatabaseTest`'s fixture. This checks the connection between the schema and the wipe, which is the part
 * written by hand.
 */
class DataWipeCoverageTest {

    /**
     * The module's own source root, found by walking up from the working directory.
     *
     * A unit test's working directory is the module's, so two levels of `..` reach the repository and the path
     * below is fixed. Asserted rather than assumed: a test that silently reads nothing would pass forever.
     */
    private fun sourceFile(relative: String): File {
        val candidates = listOf(
            File("src/main/kotlin/glass/kagerou/piru/data/$relative"),
            File("core/data/src/main/kotlin/glass/kagerou/piru/data/$relative"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error(
                "could not find $relative; looked in " +
                    candidates.joinToString { it.absolutePath },
            )
    }

    /** The entity class names `PiruDatabase` declares its schema to be. */
    private fun declaredEntities(): List<String> {
        val text = sourceFile("PiruDatabase.kt").readText()
        val start = text.indexOf("entities = [")
        require(start >= 0) { "PiruDatabase has no `entities = [` list" }
        val end = text.indexOf("]", start)
        require(end > start) { "PiruDatabase's entity list is unterminated" }
        return Regex("""(\w+Entity)::class""")
            .findAll(text.substring(start, end))
            .map { it.groupValues[1] }
            .toList()
    }

    /**
     * The wipe reaches every table the schema declares.
     *
     * The whole point: add a table and forget the wipe, and this fails by name. A count-only assertion would fail
     * too, but it would not say *which* table was missed, and the message is most of the value here.
     */
    @Test
    fun `every declared entity is reachable from the wipe`() {
        val entities = declaredEntities()
        // A floor, so a broken parse cannot pass by finding nothing.
        require(entities.size >= 15) { "only found ${entities.size} entities; the parse has probably broken" }

        val wipe = sourceFile("export/DataExportImport.kt").readText()
        // The body of `deleteAll`, from its declaration to the closing brace of the function.
        val start = wipe.indexOf("suspend fun deleteAll(db: PiruDatabase) {")
        require(start >= 0) { "DataExportImport has no `deleteAll(db: PiruDatabase)`" }
        val body = wipe.substring(start, wipe.indexOf("\n    }", start))

        val missing = entities.filter { entity -> findAccessor(entity, body) == null }

        missing.shouldContainExactlyInAnyOrder(emptyList<String>())
    }

    /**
     * The declared schema is the fifteen tables the wipe's own doc claims.
     *
     * A pinned count, because the doc names the number and a silent sixteenth entity would otherwise make that
     * sentence false while the test above still passed for the fifteen it knew about.
     */
    @Test
    fun `the schema is the fifteen tables the wipe documents`() {
        declaredEntities().size shouldBe 15
    }

    /**
     * And the wipe really does delete rather than, say, read.
     *
     * A guard on the assertion above: it looks for `…Dao()` in the function body, and a body that merely *opened*
     * each DAO would satisfy it. This requires the delete call, which is what makes the first test mean something.
     */
    @Test
    fun `the wipe deletes`() {
        val wipe = sourceFile("export/DataExportImport.kt").readText()
        val start = wipe.indexOf("suspend fun deleteAll(db: PiruDatabase) {")
        val body = wipe.substring(start, wipe.indexOf("\n    }", start))
        val deletes = Regex("""db\.\w+Dao\(\)\.deleteAll\(\)""").findAll(body).count()
        deletes shouldBe 15
    }

    /**
     * The doc's claim that the count is checked rather than maintained by hand.
     *
     * Asserted as a sentence rather than as behaviour because it *is* a claim about this file's existence: the
     * doc says the list is checked, and this test is the check. If someone rewrites the doc and deletes the test,
     * the sentence goes with the test.
     */
    @Test
    fun `the wipe's doc says its count is checked`() {
        sourceFile("export/DataExportImport.kt").readText() shouldContain
            "checked against `PiruDatabase`'s `entities` list rather than maintained by hand"
    }

    /**
     * The DAO accessor `deleteAll` uses for [entity], or null when the body calls none.
     *
     * ## Why this is a search rather than a string formula
     * Two of the fifteen entities do not follow the mechanical rule, and writing the rule out would make the test
     * restate the list it exists to check:
     *
     *   `UserProfileRecordEntity`    → `userProfileDao()`
     *   `CustomSubstanceRecordEntity` → `customSubstanceDao()`
     *
     * Both drop the entity's `Record` as well as its `Entity`, so the accessor is accepted when it **contains**
     * the entity's own identifying words. A `doseEntryDao()` still matches `DoseEntryEntity`, a `labDao()` would
     * not match `LabMeasurementEntity`, and a genuinely missing table still fails by name.
     *
     * Asserting on the accessor rather than on the table name is deliberate: the table name lives in each
     * entity's `@Entity(tableName = …)` and would need a second parse in a second file, while the accessor is the
     * thing `deleteAll` actually writes.
     */
    private fun findAccessor(entity: String, body: String): String? {
        // The entity's identifying words, lowercased: `DoseEntryEntity` → ["dose", "entry"].
        //
        // `Record` and `Item` are dropped because they name *what the row is* rather than *what the table holds*,
        // and the DAO accessor names the table: `UserProfileRecordEntity` → `userProfileDao()`,
        // `InventoryItemEntity` → `inventoryDao()`. That is a rule about the naming convention rather than a list
        // of the cases I happened to find, so a fourth entity following the same convention still matches.
        val words = entity
            .removeSuffix("Entity")
            .split(Regex("(?=[A-Z])"))
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
            .filterNot { it == "record" || it == "item" }
        require(words.isNotEmpty()) { "no words parsed out of $entity" }
        val accessors = Regex("""db\.(\w+Dao)\(\)""").findAll(body).map { it.groupValues[1] }.toList()
        return accessors.firstOrNull { accessor ->
            val lower = accessor.lowercase()
            words.all { lower.contains(it) }
        }
    }
}
