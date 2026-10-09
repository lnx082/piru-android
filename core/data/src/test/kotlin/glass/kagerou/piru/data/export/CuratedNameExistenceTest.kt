package glass.kagerou.piru.data

import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.jupiter.api.Test

/**
 * The curated name sets in `:core:engine` are self-consistent, and the names that cannot work are documented.
 *
 * ## The defect this is for
 * `ContestedDrugClasses.SUBSTANCES` lists substances whose curated class assignment the classifier calls contestable. I
 * wrote it from memory of the reference's comment and included three names that can never be flagged:
 *
 *     Moclobemide    no `drug_class` at all
 *     Agomelatine    MELATONERGIC, one of four classes the enum deliberately omits
 *     Tianeptine     OPIOIDERGIC, likewise
 *
 * A set entry that can only ever return `false` is dead code that **reads as a curated judgement** — the next reader
 * takes it for a decision, which is worse than an ordinary unused function. They are out, and this keeps them out.
 *
 * ## Why this reads the source instead of the catalogue
 * The catalogue asset is a SQLite file and this module's test source set has **no JDBC driver** for it — my first
 * version of this check ended in `SQLException: No suitable driver`, from a test that looked like it was verifying
 * something. `:core:data` also has no dependency on the substance module's reader.
 *
 * So this checks the part that *can* be read here: the file's own structure and its comments. **Whether each flagged
 * name exists in the catalogue and carries one of the nine classes is a real check that this file cannot make**, and it
 * belongs where the catalogue can be opened — stated rather than approximated, because an approximation here would be
 * the same kind of instrument that produced the wrong list in the first place.
 */
class CuratedNameExistenceTest {

    /** `AntidepressantClass.kt` in the engine module, found relative to the module's own working directory. */
    private fun sourceFile(): File {
        // The test's working directory is the **module**: `E:\…\piru-android\core\data`. So the engine module is its
        // **sibling** at `../engine/…`, and the repository root's `core/engine/…` is two levels up. My first two
        // attempts used neither and the guard reported "not found" rather than the cases passing over nothing — which
        // is exactly why the guard is there, and it took me three tries to satisfy it.
        val candidates = listOf(
            File("../engine/src/main/kotlin/glass/kagerou/piru/engine/AntidepressantClass.kt"),
            File("../../core/engine/src/main/kotlin/glass/kagerou/piru/engine/AntidepressantClass.kt"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error(
                "AntidepressantClass.kt was not found from ${File(".").absolutePath}; " +
                    "a check that reads nothing must not pass",
            )
    }

    private fun quotedNamesIn(setName: String): List<String> {
        val text = sourceFile().readText()
        val start = text.indexOf("val $setName: Set<String> = setOf(")
        check(start >= 0) { "the $setName set was not found, so this check would read nothing" }
        val end = text.indexOf(")", start)
        return Regex("\"([^\"]+)\"").findAll(text.substring(start, end)).map { it.groupValues[1] }.toList()
    }

    /** The check read something on both sides. Without this, "all present" would mean nothing. */
    @Test
    fun `the check found both name sets`() {
        val flagged = quotedNamesIn("SUBSTANCES")
        val unreachable = quotedNamesIn("UNREACHABLE")
        println("CURATEDPROBE flagged=$flagged")
        println("CURATEDPROBE unreachable=$unreachable")
        check(flagged.isNotEmpty()) { "no flagged names were read" }
        check(unreachable.isNotEmpty()) { "no unreachable names were read" }
        flagged.size shouldBe 8
        unreachable.size shouldBe 3
    }

    /** The three unreachable names are **not** in the flagged set, which is the correction that was made. */
    @Test
    fun `the unreachable names are not flagged`() {
        val flagged = quotedNamesIn("SUBSTANCES").map { it.lowercase() }.toSet()
        for (name in quotedNamesIn("UNREACHABLE")) {
            (name.lowercase() in flagged) shouldBe false
        }
    }

    /** And the source explains *why* they are unreachable, so the next reader is not left to re-derive it. */
    @Test
    fun `the source explains why they are unreachable`() {
        val text = sourceFile().readText()
        for (name in listOf("Moclobemide", "Agomelatine", "Tianeptine")) {
            text.contains(name) shouldBe true
        }
        // The reason each one fails, in the file's own words.
        text.contains("MELATONERGIC") shouldBe true
        text.contains("OPIOIDERGIC") shouldBe true
        text.contains("dead code") shouldBe true
    }

    /** The flagged set has no duplicates, since a set literal with one would be a silent typo. */
    @Test
    fun `the flagged names are distinct`() {
        val flagged = quotedNamesIn("SUBSTANCES")
        flagged.size shouldBe flagged.map { it.lowercase() }.toSet().size
    }
}
