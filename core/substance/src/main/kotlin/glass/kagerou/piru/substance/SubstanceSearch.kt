package glass.kagerou.piru.substance

/**
 * One ranked search hit: the resolved substance plus the name the query actually
 * matched.
 *
 * [matchedAlias] is the load-bearing half. A user searching "Concerta" gets
 * Methylphenidate — correct, but the typed string matters too: carrying the alias
 * out lets the result row echo what it matched and lets a staged dose record the
 * *product* the user actually named.
 */
data class SubstanceMatch<T>(
    val substance: T,
    /**
     * The catalog alias the query named, in its display casing ("Concerta",
     * "Vyvanse"). Null when the query matched the canonical name, when it only
     * matched loosely (contains or fuzzy — too weak a signal that the user meant
     * *that* alias), or when the alias has no display form on record.
     */
    val matchedAlias: String?,
)

/**
 * Ranked search over the catalog's identity indexes.
 *
 * Ported from `SubstanceStore.rankedSearch` and its helpers. Pure and
 * allocation-light: it runs on every keystroke, so it takes index snapshots
 * rather than touching SQL.
 *
 * ## Tiers, and why each is totally ordered
 * Exact → prefix → contains → fuzzy. Within a tier, candidates sort by
 * `(length, key)` — a *total* order, not merely by relevance. The indexes are
 * hash maps, whose iteration order differs from run to run, so without a total
 * order the same query could title a row "Ritalin SR" on one keystroke and
 * "Ritalin LA" on the next. Shortest-wins is also the intuitive answer:
 * "rital" resolves to "Ritalin".
 */
object SubstanceSearch {

    /**
     * [nameIndex] and [aliasIndex] map a normalized name to a substance id;
     * [aliasDisplayIndex] maps a normalized alias back to its display casing.
     * [idToSubstance] resolves an id to whatever the caller wants back, so this
     * can rank rows, domain objects, or ids.
     */
    fun <T> rankedSearch(
        query: String,
        nameIndex: Map<String, Long>,
        aliasIndex: Map<String, Long>,
        aliasDisplayIndex: Map<String, String>,
        idToSubstance: Map<Long, T>,
        limit: Int = 50,
    ): List<SubstanceMatch<T>> {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return emptyList()

        val exactIds = mutableListOf<Long>()
        val prefixIds = mutableListOf<Long>()
        val containsIds = mutableListOf<Long>()
        val seen = mutableSetOf<Long>()

        // id to the normalized alias the query named. Only exact and prefix hits
        // register: a *contains* match ("in" hitting "Ritalin") is no evidence the
        // user meant that alias, and titling a row from it would put words in
        // their mouth. Fuzzy never registers — it only scans the name index.
        val aliasHits = mutableMapOf<Long, String>()

        /** Keep the shortest match per id, ties broken lexicographically. */
        fun noteAlias(key: String, id: Long) {
            val existing = aliasHits[id]
            if (existing == null || compareKeys(key, existing) < 0) aliasHits[id] = key
        }

        // Tier 1: exact, on the canonical name first, then on an alias.
        when (val id = nameIndex[q]) {
            null -> aliasIndex[q]?.let { aliasId ->
                exactIds += aliasId
                seen += aliasId
                noteAlias(q, aliasId)
            }
            else -> {
                exactIds += id
                seen += id
            }
        }

        // Tier 2: canonical-name prefixes, then alias prefixes. Aliases are
        // collected first and claimed in (length, key) order so both the ranking
        // and the displayed alias are deterministic.
        val namePrefix = mutableListOf<Pair<String, Long>>()
        val nameContains = mutableListOf<Pair<String, Long>>()
        for ((key, id) in nameIndex) {
            if (id in seen) continue
            if (key.startsWith(q)) namePrefix += key to id else if (key.contains(q)) nameContains += key to id
        }
        for (hit in namePrefix.sortedWith(KEY_ORDER)) {
            if (seen.add(hit.second)) prefixIds += hit.second
        }
        for (hit in nameContains.sortedWith(KEY_ORDER)) {
            if (seen.add(hit.second)) containsIds += hit.second
        }

        for ((key, id) in aliasIndex) {
            if (id !in seen && key.startsWith(q)) noteAlias(key, id)
        }
        for ((id, key) in aliasHits.entries.sortedWith(compareBy({ it.value.length }, { it.value }))) {
            if (id !in seen) {
                prefixIds += id
                seen += id
            }
        }

        // Tier 3: aliases that merely contain the query.
        val aliasContains = aliasIndex.entries
            .filter { it.value !in seen && it.key.contains(q) }
            .map { it.key to it.value }
        for (hit in aliasContains.sortedWith(KEY_ORDER)) {
            if (seen.add(hit.second)) containsIds += hit.second
        }

        var ranked = exactIds + prefixIds + containsIds
        if (ranked.size > limit) ranked = ranked.take(limit)

        // Tier 4: fuzzy, only once the query is long enough to be worth the scan
        // and only if the earlier tiers left room.
        if (ranked.size < limit && q.length >= 4) {
            ranked = ranked + fuzzyMatch(q, nameIndex, seen, limit - ranked.size)
        }

        return ranked.take(limit).mapNotNull { id ->
            val substance = idToSubstance[id] ?: return@mapNotNull null
            // Normalized to display casing through the catalog's own mapping
            // rather than re-deriving it: `alias_normalized` is the pipeline's
            // normalization (Greek-capital folding and all), which `lowercase()`
            // does not reproduce.
            val alias = aliasHits[id]?.let { aliasDisplayIndex[it] }
            SubstanceMatch(substance, alias)
        }
    }

    /**
     * Name-index entries within an edit distance of [query], nearest first.
     *
     * Ties on distance break by `(length, key)` so the cut at [limit] is stable
     * across runs.
     */
    private fun fuzzyMatch(
        query: String,
        nameIndex: Map<String, Long>,
        excluding: Set<Long>,
        limit: Int,
    ): List<Long> {
        if (limit <= 0) return emptyList()
        val maxDistance = maxOf(1, (query.length * 0.3).toInt())
        val matches = mutableListOf<Triple<Long, Int, String>>()

        for ((key, id) in nameIndex) {
            if (id in excluding) continue
            // Distance is at least the length difference, so most of the catalog
            // is skipped without touching the DP table or allocating for it.
            if (kotlin.math.abs(key.length - query.length) > maxDistance) continue
            val distance = levenshtein(query, key, maxDistance) ?: continue
            matches += Triple(id, distance, key)
        }

        return matches
            .sortedWith(compareBy({ it.second }, { it.third.length }, { it.third }))
            .take(limit)
            .map { it.first }
    }

    /**
     * Levenshtein distance between [a] and [b], or null as soon as it is certain
     * to exceed [cap].
     *
     * The early exit is the point: a whole DP row above the cap means the
     * distance can only grow, which is the common case for the ~1,700
     * non-matching names each fuzzy pass scans.
     */
    internal fun levenshtein(a: String, b: String, cap: Int): Int? {
        if (a.isEmpty()) return if (b.length <= cap) b.length else null
        if (b.isEmpty()) return if (a.length <= cap) a.length else null

        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)

        for (i in 1..a.length) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
                rowMin = minOf(rowMin, curr[j])
            }
            if (rowMin > cap) return null
            val swap = prev
            prev = curr
            curr = swap
        }
        return if (prev[b.length] <= cap) prev[b.length] else null
    }

    /** Shortest key first, then lexicographic. */
    private val KEY_ORDER: Comparator<Pair<String, Long>> =
        compareBy({ it.first.length }, { it.first })

    private fun compareKeys(a: String, b: String): Int =
        if (a.length != b.length) a.length - b.length else a.compareTo(b)
}
