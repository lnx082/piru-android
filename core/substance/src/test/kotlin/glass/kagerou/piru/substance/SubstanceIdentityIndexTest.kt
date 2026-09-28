package glass.kagerou.piru.substance

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * Name resolution against the shipped catalog.
 *
 * Ported behaviour from `SubstanceStore.substanceID(forNameOrAlias:)`. The
 * expectations below were read out of the real database before being written
 * down — in particular the stub cases, where first-wins lands on an empty row.
 */
class SubstanceIdentityIndexTest {

    private class Fixture(db: SubstanceDb) {
        val index = SubstanceIdentityIndex.build(db)
        val names: Map<Long, String> = db.query("SELECT id, canonical_name FROM substances")
            .associate { it.long("id")!! to it.string("canonical_name")!! }

        fun resolved(name: String): String? = index.resolve(name)?.let { names[it] }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        openBundledSubstanceDb().use { db -> block(Fixture(db)) }
    }

    // MARK: - The plain cases

    @Test
    fun `a canonical name resolves to itself`() {
        withFixture { f ->
            f.resolved("Caffeine") shouldBe "Caffeine"
            // The key is lowercased, and so is the query — the app's search box
            // and its stored names are not the same casing.
            f.resolved("caffeine") shouldBe "Caffeine"
            f.resolved("CAFFEINE") shouldBe "Caffeine"
        }
    }

    @Test
    fun `an alias resolves to its owner`() {
        withFixture { f ->
            f.resolved("Concerta") shouldBe "Methylphenidate"
            f.resolved("Vyvanse") shouldBe "Lisdexamfetamine"
        }
    }

    @Test
    fun `an unknown name resolves to nothing`() {
        withFixture { f ->
            f.index.resolve("not-a-substance-anywhere") shouldBe null
            f.index.resolve("") shouldBe null
        }
    }

    // MARK: - Stub demotion

    @Test
    fun `an alias owned by a stub and a real substance resolves to the real one`() {
        withFixture { f ->
            // `lorcet` is owned by both Hydrocodone and the data-less
            // Hydrocodone-Acetaminophen row, and first-wins lands on the stub.
            // Resolving it to the stub means logging Lortab draws no curve at
            // all — the alias exists precisely so it does.
            f.resolved("lorcet") shouldBe "Hydrocodone"
            f.resolved("vicodin") shouldBe "Hydrocodone"
            f.resolved("norco") shouldBe "Hydrocodone"
            f.resolved("percocet") shouldBe "Oxycodone"
        }
    }

    @Test
    fun `stub demotion works for a localized alias too`() {
        withFixture { f ->
            // The demotion is not an English-only nicety: the same source feeds
            // the localized-name index, and 曼陀罗 is contested the same way.
            f.resolved("曼陀罗") shouldBe "Datura"
            f.resolved("devil's apple") shouldBe "Datura"
            f.resolved("乌羽玉") shouldBe "Peyote"
        }
    }

    @Test
    fun `first wins would have picked the stub in eight of these cases`() {
        withFixture { f ->
            // Pinned because it is the reason the demotion exists. These eight
            // keys have a stub as the first alias row and a real substance behind
            // them; without the fallback every one of them resolves to a row that
            // carries no dose, duration, binding or effect.
            val cases = mapOf(
                "lorcet" to "Hydrocodone",
                "devil's apple" to "Datura",
                "devil's weed" to "Datura",
                "moonflower" to "Datura",
                "stinkweed" to "Datura",
                "thorn apple" to "Datura",
                "曼陀罗" to "Datura",
                "乌羽玉" to "Peyote",
            )
            for ((alias, expected) in cases) {
                val firstWins = f.index.aliasIndex[alias]!!
                (firstWins in f.index.stubIds) shouldBe true
                f.resolved(alias) shouldBe expected
                f.resolved(alias) shouldNotBe f.names[firstWins]
            }
        }
    }

    @Test
    fun `a stub with no data-bearing alternative still resolves to itself`() {
        withFixture { f ->
            // The honest answer. The name does refer to something — it just
            // carries no data — and reporting "no such substance" would be worse
            // than reporting a substance the reader can see is empty. 414 of the
            // catalog's 415 stubs land here; Aclidinium is one.
            val name = "Aclidinium"
            val id = f.index.nameIndex[name.lowercase()]!!
            (id in f.index.stubIds) shouldBe true
            f.resolved(name) shouldBe name
        }
    }

    @Test
    fun `a stub name that does have a data-bearing alias resolves past it`() {
        withFixture { f ->
            // The case upstream's demotion was written for, and the one that
            // reads most strangely: `Dextroamphetamine-Amphetamine` is its own
            // stub row, but Adderall's alias belongs to Amphetamine too. Logging
            // that exact name used to resolve to the empty row and draw nothing.
            val name = "Dextroamphetamine-Amphetamine"
            val stubId = f.index.nameIndex[name.lowercase()]!!
            (stubId in f.index.stubIds) shouldBe true
            f.resolved(name) shouldBe "Amphetamine"
        }
    }

    // MARK: - Index shape

    @Test
    fun `only contested aliases keep an owner list`() {
        withFixture { f ->
            // A single-owner alias can never need the fallback, and there are
            // thousands of them. Keeping only the contested keys is the whole
            // reason the map is cheap enough to hold alongside the indexes.
            f.index.contestedAliasOwners.isNotEmpty() shouldBe true
            f.index.contestedAliasOwners.values.forEach { it.size shouldNotBe 1 }
            // A key with one owner is resolvable and absent from the map.
            f.index.aliasIndex["concerta"] shouldNotBe null
            f.index.contestedAliasOwners["concerta"] shouldBe null
        }
    }

    @Test
    fun `the shipped catalog has the stub and contested counts these expectations assume`() {
        withFixture { f ->
            // The port's demotion logic is only exercised while the catalog keeps
            // this shape. If a build merges the stub rows or trims the aliases,
            // the tests above stop testing anything — so the shape is pinned.
            f.index.stubIds.size shouldBe 415
            f.index.contestedAliasOwners.size shouldBe 95
        }
    }
}
