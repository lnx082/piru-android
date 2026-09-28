package glass.kagerou.piru.substance

/**
 * The identity indexes search and name resolution run against — three maps built
 * once from the catalog, so a keystroke never touches SQL.
 *
 * Ported from `SubstanceStore.buildIndexes`.
 *
 * ## First wins, never last
 * Both maps collapse a duplicate key to the **first** row, not the last. It has
 * to be that way round for one specific reason: localized titles are appended
 * after the aliases, so first-wins leaves every existing alias with the owner it
 * already had, and a localized name only fills a key nothing else claimed.
 *
 * It also has to be a collapse rather than an error. A duplicate lowercased
 * canonical name — `MDMA` and `mdma` in an imported store — would trap a
 * build-from-pairs call at launch, which is an uncatchable cold-start crash.
 */
class SubstanceIdentityIndex(
    /** Lowercased canonical name to substance id. */
    val nameIndex: Map<String, Long>,
    /** Normalized alias (or localized name) to substance id. */
    val aliasIndex: Map<String, Long>,
    /** Normalized alias to the casing the catalog displays it in. */
    val aliasDisplayIndex: Map<String, String>,
    /**
     * Rows the build flagged as carrying no dose data at all. They stay reachable
     * by name, but name resolution demotes them, so a bare stub does not outrank
     * a substance that actually has content.
     */
    val stubIds: Set<Long>,

    /**
     * Every alias key owned by more than one substance, with all of its owners.
     *
     * Only the contested keys are kept — a single-owner alias can never need the
     * fallback that reads this. On the shipped catalog 95 keys are contested, and
     * **17 of them have both a stub and a real owner**; for 8 of those, first-wins
     * lands on the stub.
     */
    val contestedAliasOwners: Map<String, List<Long>>,
) {

    /**
     * The substance a logged name refers to, or null when nothing matches.
     *
     * A canonical name wins — **except when it names a data-less stub and the same
     * string also resolves to a substance that has data**. The build ships rows
     * like `Dextroamphetamine-Amphetamine` with zero dose, duration, PK and
     * half-life rows behind them; because the name index is consulted first,
     * logging that exact name used to resolve to the empty row and draw nothing.
     *
     * The preference runs both ways round: a *name* hit that is a stub yields to a
     * non-stub alias hit, and when there is no name hit at all the alias index is
     * asked for a non-stub owner before falling back to whichever substance
     * happens to be first. On the shipped catalog 415 of 1689 substances are stubs
     * and 95 alias keys have more than one owner, so "first-wins landed on the
     * empty one" is not a rare shape — it is what `lorcet` does, which resolves to
     * a stub instead of Hydrocodone.
     */
    fun resolve(name: String): Long? {
        val key = name.lowercase()
        val byName = nameIndex[key]
        val byAlias = aliasIndex[key]

        if (byName != null && byName !in stubIds) return byName
        if (byAlias != null && byAlias !in stubIds) return byAlias
        nonStubAliasOwner(key)?.let { return it }

        // Every owner is a stub, or nothing matched. Returning the stub is the
        // honest answer: the name does refer to something, it just carries no
        // data — better than reporting that the named substance does not exist.
        return byName ?: byAlias
    }

    /**
     * A substance owning [key] as an alias that actually carries data, for when
     * the first-wins alias entry is a stub. Null when every owner is a stub.
     */
    private fun nonStubAliasOwner(key: String): Long? =
        contestedAliasOwners[key]?.firstOrNull { it !in stubIds }

    companion object {

        /**
         * Build the indexes from the catalog.
         *
         * The canonical names are read in `COLLATE NOCASE` order, matching
         * upstream — it makes the first-wins outcome for two casings of one name
         * depend on a defined order rather than on the table's physical layout.
         */
        fun build(db: SubstanceDb): SubstanceIdentityIndex {
            val nameRows = db.query(
                "SELECT id, canonical_name, is_stub FROM substances ORDER BY canonical_name COLLATE NOCASE",
            )
            val nameIndex = LinkedHashMap<String, Long>()
            val stubIds = mutableSetOf<Long>()
            for (row in nameRows) {
                val id = row.long("id") ?: continue
                val canonical = row.string("canonical_name") ?: continue
                nameIndex.putIfAbsent(canonical.lowercase(), id)
                if ((row.long("is_stub") ?: 0L) != 0L) stubIds += id
            }

            val aliasRows = db.query(
                "SELECT substance_id, alias, alias_normalized FROM aliases",
            )
            // Localized titles (Ketamina, 氯胺酮) are searchable in every app
            // language, so they join the search keys — after the aliases, so
            // first-wins leaves every existing alias with its owner. They stay out
            // of the `aliases` table, which is what a header lists under the title.
            val localizedRows = db.query(
                "SELECT substance_id, name, name_normalized FROM localized_names",
            )

            val aliasIndex = LinkedHashMap<String, Long>()
            val aliasDisplay = LinkedHashMap<String, String>()
            val owners = LinkedHashMap<String, MutableSet<Long>>()

            fun noteAlias(normalized: String?, display: String?, id: Long?) {
                if (normalized.isNullOrEmpty() || id == null) return
                aliasIndex.putIfAbsent(normalized, id)
                if (display != null) aliasDisplay.putIfAbsent(normalized, display)
                owners.getOrPut(normalized) { linkedSetOf() }.add(id)
            }
            for (row in aliasRows) {
                noteAlias(row.string("alias_normalized"), row.string("alias"), row.long("substance_id"))
            }
            for (row in localizedRows) {
                noteAlias(row.string("name_normalized"), row.string("name"), row.long("substance_id"))
            }

            // Only the contested keys: a single-owner alias can never need the
            // stub-skipping fallback, and keeping all ~6,000 would be a map of
            // one-element lists for no reason.
            val contested = owners
                .filterValues { it.size > 1 }
                .mapValues { (_, ids) -> ids.toList() }

            return SubstanceIdentityIndex(nameIndex, aliasIndex, aliasDisplay, stubIds, contested)
        }
    }
}
