package glass.kagerou.piru.data.catalog

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import glass.kagerou.piru.substance.Row
import glass.kagerou.piru.substance.SubstanceDb
import java.io.File

/**
 * [SubstanceDb] over the bundled SQLite driver, reading the installed catalog.
 *
 * ## Why the bundled driver rather than the framework's SQLite
 * Two reasons, one fatal and one a real risk.
 *
 * **Typed binding is not optional.** `SQLiteDatabase.rawQuery` accepts only
 * `String[]`, so every parameter arrives as text. That is fine against a
 * column — SQLite applies the column's affinity, and `heavy > '50'` matches
 * `heavy > 50` — but it is **silently wrong against an expression**. Measured on
 * the shipped catalog:
 *
 * ```
 * SELECT 5 > '2';                                        -- 0
 * SELECT length('aaaa') > '2';                           -- 0
 * SELECT count(*) FROM dose_ranges WHERE heavy > '50';   -- 1109, same as heavy > 50
 * ```
 *
 * A text operand sorts above every number in SQLite's type ordering, so an
 * expression compared against a bound string is always false. The ported
 * invariant `no mechanism summary is a whole document` is exactly
 * `length(m.summary) > ?` — bound as text it returns no rows, and the spec
 * passes while checking nothing. The JVM specs never see this, because
 * sqlite-jdbc binds by type. `SQLiteStatement` here binds each value by its own
 * type, so the two engines agree.
 *
 * **One SQLite version everywhere.** The catalog is not a simple table store: it
 * carries window functions and partial indexes, and an OEM's older SQLite would
 * fail on it in ways that are hard to attribute. The bundled driver ships its own
 * SQLite, so the catalog behaves identically on every device.
 */
class AndroidSubstanceDb private constructor(
    private val connection: SQLiteConnection,
) : SubstanceDb {

    override fun query(sql: String, args: List<Any?>): List<Row> =
        connection.prepare(sql).use { statement ->
            args.forEachIndexed { index, arg -> statement.bind(index + 1, arg) }
            val columns = (0 until statement.getColumnCount()).map { statement.getColumnName(it) }
            buildList {
                // Materialized eagerly: the statement is closed before the caller
                // sees these rows, so nothing may hold a lazy reference into it.
                while (statement.step()) {
                    add(StatementRow(columns) { index -> statement.read(index) })
                }
            }
        }

    override fun close() {
        connection.close()
    }

    /**
     * A row captured out of a statement that has since been closed.
     *
     * The values are read once, up front, into an array — a row that outlives its
     * statement cannot read from it.
     */
    private class StatementRow(
        private val columns: List<String>,
        read: (Int) -> Any?,
    ) : Row {
        private val values: Array<Any?> = Array(columns.size) { read(it) }

        private fun value(column: String): Any? {
            val index = columns.indexOf(column)
            return if (index < 0) null else values[index]
        }

        override fun string(column: String): String? = value(column) as? String
        override fun double(column: String): Double? = (value(column) as? Number)?.toDouble()
        override fun long(column: String): Long? = (value(column) as? Number)?.toLong()
    }

    companion object {
        /**
         * Open the installed catalog.
         *
         * The file is opened through the driver rather than a framework
         * `SQLiteDatabase`, so the handle is not read-only in SQLite's own sense.
         * Nothing here ever writes, and the file's integrity is checked against
         * the published hash before this is called.
         */
        fun open(file: File): AndroidSubstanceDb =
            AndroidSubstanceDb(BundledSQLiteDriver().open(file.absolutePath))
    }
}

/** Bind [value] using its own type, so an expression comparison is not text-versus-number. */
private fun SQLiteStatement.bind(index: Int, value: Any?) {
    when (value) {
        null -> bindNull(index)
        is String -> bindText(index, value)
        is Boolean -> bindBoolean(index, value)
        is Int -> bindInt(index, value)
        is Long -> bindLong(index, value)
        is Double -> bindDouble(index, value)
        is Float -> bindFloat(index, value)
        is Number -> bindDouble(index, value.toDouble())
        else -> error("Cannot bind a ${value::class.simpleName} to a SQLite parameter")
    }
}

/** Read column [index] as the type SQLite reports for it. */
private fun SQLiteStatement.read(index: Int): Any? = when (getColumnType(index)) {
    SQLITE_DATA_INTEGER -> getLong(index)
    SQLITE_DATA_FLOAT -> getDouble(index)
    SQLITE_DATA_TEXT -> getText(index)
    SQLITE_DATA_BLOB -> getBlob(index)
    SQLITE_DATA_NULL -> null
    else -> getText(index)
}
