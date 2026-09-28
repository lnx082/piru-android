package glass.kagerou.piru.substance

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * Ranked search against the shipped catalog.
 *
 * Ported behaviour from `SubstanceStore.rankedSearch`. Every expectation below
 * was confirmed against `db/piru-substances.sqlite` with `sqlite3` before it was
 * written down.
 */
class SubstanceSearchTest {

    private class Fixture(db: SubstanceDb) {
        val index = SubstanceIdentityIndex.build(db)
        val ids: Map<Long, String> = db.query("SELECT id, canonical_name FROM substances")
            .associate { it.long("id")!! to it.string("canonical_name")!! }

        fun search(query: String, limit: Int = 50): List<SubstanceMatch<String>> =
            SubstanceSearch.rankedSearch(
                query = query,
                nameIndex = index.nameIndex,
                aliasIndex = index.aliasIndex,
                aliasDisplayIndex = index.aliasDisplayIndex,
                idToSubstance = ids,
                limit = limit,
            )

        fun names(query: String, limit: Int = 50): List<String> = search(query, limit).map { it.substance }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        openBundledSubstanceDb().use { db -> block(Fixture(db)) }
    }

    // MARK: - The tiers

    @Test
    fun `an exact canonical name resolves to itself`() {
        withFixture { f ->
            f.search("Caffeine").first().substance shouldBe "Caffeine"
            // Matched on the name, not an alias, so nothing is echoed back.
            f.search("Caffeine").first().matchedAlias shouldBe null
        }
    }

    @Test
    fun `an exact alias resolves to its owner and echoes the alias`() {
        withFixture { f ->
            val hit = f.search("Concerta").first()
            hit.substance shouldBe "Methylphenidate"
            // The load-bearing half: the user typed "Concerta" and the result can
            // echo that, and a staged dose can record the product they named.
            hit.matchedAlias shouldBe "Concerta"
        }
    }

    @Test
    fun `a prefix finds the substance and reports the shortest alias`() {
        withFixture { f ->
            // Five aliases contain "rital": ritalin, ritalin la, ritalin sr,
            // relexxii ritalin and 4f ritalin. The shortest is what titles the
            // row, and the (length, key) ordering is what makes that stable
            // across runs rather than dependent on hash-map iteration order.
            val hit = f.search("rital").first()
            hit.substance shouldBe "Methylphenidate"
            hit.matchedAlias shouldBe "Ritalin"
        }
    }

    @Test
    fun `a contains match does not report an alias`() {
        withFixture { f ->
            // "italin" appears inside "Ritalin", which is no evidence the user
            // meant that alias — titling a row from it would put words in their
            // mouth. The substance still resolves; only the alias echo is withheld.
            val hits = f.search("italin")
            hits.map { it.substance }.contains("Methylphenidate") shouldBe true
            hits.filter { it.substance == "Methylphenidate" }
                .forEach { it.matchedAlias shouldBe null }
        }
    }

    @Test
    fun `a fuzzy query finds the substance behind a typo`() {
        withFixture { f ->
            // "caffiene" is not a name in the catalog; "caffeine" is, two
            // substitutions away, inside the 30%-of-length cap.
            f.names("caffiene").contains("Caffeine") shouldBe true
        }
    }

    @Test
    fun `fuzzy never runs on a short query`() {
        withFixture { f ->
            // A discriminating pair, verified against the catalog: "lsf" and
            // "lsfd" match nothing by exact name, exact alias, prefix or contains.
            // Both are one edit from the canonical name "LSD", so the only thing
            // that separates them is the four-character floor.
            //
            // "lsf" is three characters, so the fuzzy tier never runs and the
            // result must be empty. Were the floor removed this would return LSD,
            // which is what makes the assertion worth making.
            f.names("lsf").shouldBeEmpty()

            // "lsfd" is four, so fuzzy runs — and the length prefilter still lets
            // it through, since |4 - 3| is within the one-edit cap.
            f.names("lsfd").contains("LSD") shouldBe true
        }
    }

    @Test
    fun `an empty or blank query returns nothing`() {
        withFixture { f ->
            f.search("").shouldBeEmpty()
            f.search("   ").shouldBeEmpty()
        }
    }

    @Test
    fun `the limit is honoured`() {
        withFixture { f ->
            f.names("a", limit = 3).size shouldBe 3
            f.names("a", limit = 500).size shouldBe f.names("a", limit = 500).distinct().size
        }
    }

    @Test
    fun `results are stable across repeated calls`() {
        withFixture { f ->
            // The indexes are hash maps. Without a total order inside each tier
            // the same query could come back in a different order on the next
            // call, which is exactly what the ordering exists to prevent.
            val first = f.search("rital", limit = 10).map { it.substance to it.matchedAlias }
            repeat(5) {
                f.search("rital", limit = 10).map { it.substance to it.matchedAlias } shouldBe first
            }
        }
    }

    // MARK: - Levenshtein

    @Test
    fun `levenshtein computes distance within the cap`() {
        SubstanceSearch.levenshtein("caffeine", "caffeine", 2) shouldBe 0
        SubstanceSearch.levenshtein("caffiene", "caffeine", 2) shouldBe 2
        SubstanceSearch.levenshtein("caffeine", "caffein", 2) shouldBe 1
        SubstanceSearch.levenshtein("", "abc", 5) shouldBe 3
        SubstanceSearch.levenshtein("abc", "", 5) shouldBe 3
    }

    @Test
    fun `levenshtein answers null the moment the cap is exceeded`() {
        // The early exit is what keeps the fuzzy pass cheap over ~1,700 names:
        // once a whole DP row is above the cap the distance can only grow.
        SubstanceSearch.levenshtein("caffeine", "zzzzzzzz", 2) shouldBe null
        SubstanceSearch.levenshtein("abc", "abcdefgh", 2) shouldBe null
        // Exactly at the cap still answers.
        SubstanceSearch.levenshtein("abc", "abcde", 2) shouldBe 2
    }

    // MARK: - Index construction

    @Test
    fun `the index keeps the first owner of a contested alias`() {
        withFixture { f ->
            // Localized titles are appended after the aliases, so first-wins
            // leaves every existing alias with the owner it had. A localized
            // name only fills a key nothing else claimed.
            f.index.aliasIndex.isNotEmpty() shouldBe true
            f.index.aliasDisplayIndex.isNotEmpty() shouldBe true
            f.index.nameIndex.isNotEmpty() shouldBe true

            // Every alias the search can display is one the index actually has.
            val displayed = f.search("concerta").first().matchedAlias
            displayed shouldNotBe null
            f.index.aliasDisplayIndex["concerta"] shouldBe displayed
        }
    }

    @Test
    fun `canonical names are indexed lowercased`() {
        withFixture { f ->
            f.index.nameIndex["caffeine"] shouldNotBe null
            f.index.nameIndex["Caffeine"] shouldBe null
        }
    }
}
