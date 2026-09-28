package glass.kagerou.piru.substance

/**
 * One result row, addressed by column name.
 *
 * Accessors return null for both SQL NULL and an unknown column name. The
 * bundled database is a fixed artifact with a `schema_version` in its manifest
 * table, so an unknown column is a bug rather than a runtime condition worth
 * distinguishing — but neither is it worth crashing a read over.
 */
interface Row {
    fun string(column: String): String?
    fun double(column: String): Double?
    fun long(column: String): Long?
    fun int(column: String): Int? = long(column)?.toInt()
    fun boolean(column: String): Boolean? = long(column)?.let { it != 0L }
}

/**
 * A read-only view of the bundled substance database.
 *
 * The app opens the 18 MB file through the Android framework's SQLite; the
 * specs open the very same file through JDBC. Both satisfy this interface, so
 * the query text, the source-priority resolution and the row mapping are
 * written once and exercised against real data on the JVM — no emulator, and no
 * second implementation to drift.
 */
interface SubstanceDb : AutoCloseable {

    /**
     * Run [sql] with [args] and map every row.
     *
     * Parameters are positional `?` placeholders. Values must be the boxed
     * primitives SQLite understands (String, Long, Double, null); anything else
     * is a caller bug, and a driver is free to reject it.
     */
    fun query(sql: String, args: List<Any?> = emptyList()): List<Row>

    /** Convenience for the single-row lookups that dominate the read path. */
    fun queryOne(sql: String, args: List<Any?> = emptyList()): Row? = query(sql, args).firstOrNull()
}
