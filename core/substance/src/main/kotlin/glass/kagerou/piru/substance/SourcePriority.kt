package glass.kagerou.piru.substance

/**
 * The SQL that ranks a source-attributed table by the user's enabled-source
 * order, highest priority first.
 *
 * Ported from the fragment builders in `SubstanceReadModel.swift`. The two
 * pieces are always built together, from the same order, so the value a
 * resolver shows and the source it attributes that value to cannot come out of
 * different rankings — which is why they live in one object rather than being
 * assembled at each call site.
 *
 * The values are built once per instance. Upstream memoizes against the order
 * because the order changes only when the user reorders sources while the
 * strings were rebuilt on every call; constructing this per resolve gets the
 * same result with no lock, and a resolve is not a hot loop.
 */
class SourcePriority(val order: List<String>) {

    /**
     * A `CASE src.slug WHEN … THEN … END` expression mapping enabled source slugs
     * to their rank, for use in an `ORDER BY`.
     *
     * An empty order yields the constant `999`, so every row ties — the same
     * behaviour as an order that simply has not loaded yet, rather than a syntax
     * error from an empty `CASE`.
     */
    val priorityCaseSQL: String get() = priorityCaseSQL("src")

    /**
     * The same expression bound to [alias] instead of the default `src`.
     *
     * The alias is a parameter because **a fragment with a hard-coded alias only
     * works in one position**. A statement that joins `sources` twice — an outer
     * query and a correlated subquery — must use a different alias inside the
     * subquery or the two shadow each other; the fragment then has to name
     * whichever one is in scope, and passing it is the only way to say which.
     *
     * This was a real failure rather than a hypothetical: `metabolismRows` joins
     * `sources` as `inner_src` inside its subquery and spliced in the unparameterised
     * fragment, so on Android the query died with `no such column: src.slug` while
     * the same code passed under the JDBC driver. Left unparameterised, the next
     * double-join would do it again.
     */
    fun priorityCaseSQL(alias: String): String = run {
        if (order.isEmpty()) return@run "999"
        val cases = order.mapIndexed { index, slug -> "WHEN '${escape(slug)}' THEN $index" }
        "CASE $alias.slug ${cases.joinToString(" ")} ELSE 999 END"
    }

    /**
     * The enabled slugs as a SQL list for a `WHERE src.slug IN (…)` clause.
     *
     * An empty order yields `''` — a single empty string, matching nothing. That
     * is deliberate and load-bearing: the prose resolvers retry without the
     * enabled-source filter, and this is what makes the launch window before
     * source preferences load fall through to the retry instead of blanking
     * every overview.
     */
    val enabledSourceListSQL: String =
        if (order.isEmpty()) "''" else order.joinToString(", ") { "'${escape(it)}'" }

    /**
     * The ranking SQL for one field of a source-attributed table.
     *
     * [join] is a `LEFT JOIN source_field_priority …` to splice in after the
     * sources join; it is empty when the field has no override row to consult.
     * [terms] are ranking expressions, most significant first.
     */
    data class FieldRankSQL(val join: String, val terms: List<String>) {
        /** The terms as an `ORDER BY` list. */
        val orderBy: String get() = terms.joinToString(", ") { "$it ASC" }
    }

    /**
     * Ranking SQL for one field of a table aliased [alias].
     *
     * [field], when given, names a field in `source_field_priority` — the bundled
     * database's per-field rank overrides. A source with a row there resolves
     * that one field at that rank instead of its position in the user's source
     * order, which is why the plain priority case stays on as the last term: a
     * tie falls back to the ordinary order. Passing null ranks on the source
     * order alone, so an override has to be asked for — a row added to that table
     * cannot move a field nobody meant to move.
     *
     * [doseContextLast] sorts a `dose_context = 'therapeutic'` row behind every
     * recreational or unknown one before rank is consulted at all. A therapeutic
     * ladder next to a dose somebody logged reads as a recommendation to take
     * that much, and it is the recreational ladder their number means anything
     * against.
     *
     * [field] and [alias] are fixed internal literals, so interpolating them
     * carries no injection surface — but the escaping is kept anyway, because a
     * future caller passing a user-supplied field name should not have to know
     * that.
     */
    fun fieldRankSQL(
        field: String?,
        alias: String,
        doseContextLast: Boolean = false,
    ): FieldRankSQL {
        val terms = mutableListOf<String>()
        if (doseContextLast) terms += "($alias.dose_context = 'therapeutic')"
        if (field == null) {
            terms += priorityCaseSQL
            return FieldRankSQL(join = "", terms = terms)
        }
        terms += "COALESCE(sfp.priority, $priorityCaseSQL)"
        terms += priorityCaseSQL
        val join = buildString {
            append("LEFT JOIN source_field_priority sfp\n")
            append("       ON sfp.source_id = $alias.source_id\n")
            append("      AND sfp.field = '${escape(field)}'")
        }
        return FieldRankSQL(join = join, terms = terms)
    }

    /** A single-quoted SQL literal's contents, with `'` doubled. */
    private fun escape(value: String): String = value.replace("'", "''")
}
