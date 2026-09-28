package glass.kagerou.piru.substance

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * [SubstanceDb] over JDBC, for the JVM specs.
 *
 * Production uses the Android framework's SQLite instead (`:core:data`); this
 * exists so the query layer can be exercised against the real 18 MB file
 * without an emulator.
 */
class JdbcSubstanceDb private constructor(private val connection: Connection) : SubstanceDb {

    override fun query(sql: String, args: List<Any?>): List<Row> =
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, arg -> statement.setObject(index + 1, arg) }
            statement.executeQuery().use { rs ->
                val labels = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                buildList {
                    while (rs.next()) {
                        // Materialized eagerly: the ResultSet is closed before
                        // the caller sees these rows.
                        add(JdbcRow(labels.associateWith { rs.getObject(it) }))
                    }
                }
            }
        }

    override fun close() {
        connection.close()
    }

    private class JdbcRow(private val values: Map<String, Any?>) : Row {
        override fun string(column: String): String? = values[column] as? String
        override fun double(column: String): Double? = (values[column] as? Number)?.toDouble()
        override fun long(column: String): Long? = (values[column] as? Number)?.toLong()
    }

    companion object {
        fun open(path: Path): JdbcSubstanceDb {
            // Read-only, matching how the app opens it: the database is a fixed
            // artifact and nothing in either code path ever writes to it.
            val url = "jdbc:sqlite:file:${path.toAbsolutePath()}?mode=ro"
            return JdbcSubstanceDb(DriverManager.getConnection(url))
        }
    }
}

/**
 * Opens the bundled substance database, or skips the calling test when it is
 * not present — the same posture as the upstream Python suite, which raises
 * `SkipTest` rather than failing on a checkout that has not fetched it.
 */
fun openBundledSubstanceDb(): SubstanceDb {
    val configured = System.getProperty("piru.substanceDb")
    assumeTrue(configured != null, "piru.substanceDb system property is not set")
    val path = Path.of(configured!!)
    assumeTrue(Files.exists(path), "substance database missing at $path — run db/fetch-db.sh")
    return JdbcSubstanceDb.open(path)
}
