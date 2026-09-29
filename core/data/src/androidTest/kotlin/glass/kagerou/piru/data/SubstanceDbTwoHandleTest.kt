package glass.kagerou.piru.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.data.catalog.AndroidSubstanceDb
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two handles over the catalogue file, one reading while the other closes.
 *
 * ## What this pins, and why the existing concurrency spec did not catch it
 * `AndroidSubstanceDbConcurrencyTest` hammers **one** instance from eight threads and
 * passes, because that instance's lock serializes its own callers. This is the shape that
 * actually crashed a released build: the application holds a long-lived handle for the
 * catalogue, and `EsterPKIndexLoader` opened a **second** handle, read eight rows and
 * closed it. When that `close()` landed while the long-lived handle was mid-`query()`, the
 * process died with:
 *
 * ```
 * Fatal signal 11 (SIGSEGV) in pid … (glass.kagerou.piru), thread DefaultDispatch
 *   #00 sqlite3DbMallocRawNN      #01 allocateCursor
 *   #02 sqlite3VdbeExec           #03 sqlite3_step
 *   … BundledSQLiteStatement.step
 * ```
 *
 * and, on the next run, `sqlite3SrcListAssignCursors` / `selectExpander` / `sqlite3Prepare`.
 * Those are a use-after-free: closing a connection frees the statement and the page cache
 * the other thread is walking.
 *
 * ## Why it asserts rather than only racing
 * A regression here does not fail — it kills the test process, which is the honest shape
 * of the bug and is what `AndroidSubstanceDbConcurrencyTest` documents. But "the process
 * survived" is a weak assertion for a fix that is meant to make the *closed* handle
 * unusable rather than merely serialized, so this also asserts that the closed instance
 * answers with a Java exception instead of reaching SQLite.
 *
 * The explicit `: Unit` is load-bearing: kotest's `shouldBe` returns its receiver, and a
 * method whose inferred return type is not `void` is rejected by AndroidJUnitRunner.
 */
@RunWith(AndroidJUnit4::class)
class SubstanceDbTwoHandleTest {

    private fun seed(context: Context): File {
        val file = File(context.cacheDir, "substance-db-two-handle.db")
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { seed ->
            seed.execSQL("CREATE TABLE substances (name TEXT NOT NULL, weight INTEGER NOT NULL)")
            seed.beginTransaction()
            for (index in 0 until 200) {
                seed.execSQL("INSERT INTO substances (name, weight) VALUES ('substance $index', $index)")
            }
            seed.setTransactionSuccessful()
            seed.endTransaction()
        }
        return file
    }

    /**
     * The reader keeps a long-lived handle open while a second handle is opened, read and
     * closed underneath it — repeatedly, since the failure is a race.
     */
    @Test
    fun `a handle closing under a running query does not kill the process`(): Unit {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = seed(context)

        val longLived = AndroidSubstanceDb.open(file)
        val rowsRead = AtomicInteger()
        val failure = AtomicReference<Throwable?>(null)
        val stop = CountDownLatch(1)
        val readerDone = CountDownLatch(1)

        val reader = Thread {
            try {
                while (stop.count > 0) {
                    rowsRead.addAndGet(
                        longLived.query(
                            "SELECT name, weight FROM substances WHERE weight >= ? ORDER BY weight",
                            listOf(0),
                        ).size,
                    )
                }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                readerDone.countDown()
            }
        }
        reader.start()

        // The short-lived handle, the way the ester loader used it.
        repeat(40) {
            val shortLived = AndroidSubstanceDb.open(file)
            try {
                shortLived.query("SELECT slug FROM (SELECT 'substance' AS slug) ORDER BY slug", emptyList())
            } finally {
                shortLived.close()
            }
        }

        stop.countDown()
        readerDone.await(30, TimeUnit.SECONDS)
        longLived.close()

        failure.get()?.let { throw AssertionError("the long-lived reader failed", it) }
        rowsRead.get() shouldBeGreaterThan 0
        Unit
    }

    /**
     * And a closed handle refuses rather than reaching SQLite.
     *
     * This is the half that makes the fix more than "the race got narrower": after `close`
     * the instance is unusable, and the mistake is a Java exception at the call site rather
     * than native memory corruption in whichever thread got there first.
     */
    @Test
    fun `a closed handle raises instead of reading`(): Unit {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = seed(context)
        val database = AndroidSubstanceDb.open(file)
        database.close()

        val raised = runCatching { database.query("SELECT 1", emptyList()) }
        raised.isFailure.shouldBeTrue()
        Unit
    }
}
