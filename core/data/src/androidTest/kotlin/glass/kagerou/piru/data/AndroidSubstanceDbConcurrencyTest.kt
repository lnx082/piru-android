package glass.kagerou.piru.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.data.catalog.AndroidSubstanceDb
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The catalog's connection is read from more than one thread at a time, and this pins
 * that it survives being read that way.
 *
 * ## What this is guarding
 * `AndroidSubstanceDb` holds a single connection from the bundled SQLite driver. Two
 * threads preparing statements on it at once raise no Java exception: they corrupt
 * SQLite's own parser state and the process takes a `SIGABRT`, with a tombstone whose
 * frames run `AndroidSubstanceDb.query` → `CheckJNI::NewStringUTF` → `abort`. That is
 * what shipped in 0.3.0 — the journal, the tolerance replay and the body-load and
 * receptor-load charts all read the catalog, and once those began loading off the
 * main thread two of them genuinely overlapped. The reproducibility was total: the
 * tolerance tool died within a second of opening, every time.
 *
 * So the real assertion here is "the process survived". A regression does not fail
 * this test, it crashes the run — which is the honest shape of the bug, and the
 * reason the lock in `AndroidSubstanceDb.query` is not the kind of thing a comment
 * can carry on its own.
 *
 * ## Its own file, not the packed catalog
 * The 18 MB catalog is fetched rather than committed, so a test that needed it could
 * not run on a fresh checkout. The connection is per-instance and the race lives in
 * the driver, not in the schema, so a two-column table built here exercises exactly
 * the same path.
 *
 * The explicit `: Unit` is load-bearing: kotest's `shouldBe` returns its receiver, so
 * an expression-bodied test method infers a non-void return type and JUnit 4 refuses
 * the whole class naming the method rather than the assertion library.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSubstanceDbConcurrencyTest {

    @Test
    fun `concurrent queries do not corrupt the connection`(): Unit {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "substance-db-concurrency.db")
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

        val database = AndroidSubstanceDb.open(file)
        val threadCount = 8
        val queriesPerThread = 60
        val start = CountDownLatch(1)
        val finished = CountDownLatch(threadCount)
        val emptyResults = AtomicInteger()

        val workers = (0 until threadCount).map { thread ->
            Thread {
                start.await()
                try {
                    repeat(queriesPerThread) { iteration ->
                        val rows = database.query(
                            "SELECT name, weight FROM substances WHERE weight >= ? ORDER BY weight",
                            listOf((thread * 25 + iteration) % 200),
                        )
                        // Every bound value is in range, so a well-behaved connection
                        // always answers with rows; an empty answer means the lock let
                        // something through rather than that the query was narrowed.
                        if (rows.isEmpty()) emptyResults.incrementAndGet()
                    }
                } finally {
                    finished.countDown()
                }
            }
        }
        workers.forEach { it.start() }
        start.countDown()
        val completed = finished.await(60, TimeUnit.SECONDS)

        database.close()
        file.delete()

        completed shouldBe true
        emptyResults.get() shouldBe 0
    }
}
