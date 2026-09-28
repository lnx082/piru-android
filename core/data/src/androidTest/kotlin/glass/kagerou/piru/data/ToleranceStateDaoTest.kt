package glass.kagerou.piru.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.data.entity.ToleranceStateEntity
import io.kotest.matchers.shouldBe
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tolerance cache's write rules, against real SQLite on a device.
 *
 * Instrumented rather than Robolectric on purpose: every rule below is about *which
 * rows survive a write*, which is SQL behaviour plus Room's generated bindings. A
 * shadow of SQLite would test the shadow, and the rules are exactly where a
 * plausible-looking implementation goes wrong — the difference between deleting a
 * class that stopped being driven and resetting it is invisible on screen and only
 * shows up as a meaning the cache can no longer express.
 *
 * ## The explicit `: Unit` is load-bearing
 * Kotest's `shouldBe` returns its **receiver**, not `Unit`, so an expression-bodied
 * test method infers a non-void return type and JUnit 4 refuses the whole class with
 * "Method … should be void" — naming the method, never the assertion library. Every
 * test here is declared `: Unit` for that reason, and a new one must be too.
 */
@RunWith(AndroidJUnit4::class)
class ToleranceStateDaoTest {

    private lateinit var db: PiruDatabase
    private lateinit var dao: ToleranceStateDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PiruDatabase::class.java,
        ).build()
        dao = db.toleranceStateDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun row(target: String, adaptive: Double = 0.0, deep: Double = 0.0) = ToleranceStateEntity(
        target = target,
        sAcute = 0.1,
        sAdaptive = adaptive,
        sDeep = deep,
        sSynthesis = 0.0,
        chronicExposure = 0.4,
        lastUpdated = ToleranceStateEntity.NEVER_UPDATED,
    )

    @Test
    fun persistWritesTheComputedRows(): Unit = runBlocking {
        dao.persist(listOf(row("muOpioid", adaptive = 1.2, deep = 0.3)), NOW)

        val stored = dao.forClass("muOpioid")!!
        stored.sAcute shouldBe 0.1
        stored.sAdaptive shouldBe 1.2
        stored.sDeep shouldBe 0.3
        // The checkpoint fields travel with the row: a replay cannot rebuild the
        // months-scale accumulators from a bounded window, so dropping them here would
        // silently reset the deep layer on the next read.
        stored.chronicExposure shouldBe 0.4
        stored.lastUpdated.time shouldBe NOW
    }

    @Test
    fun persistReplacesARowRatherThanDuplicatingIt(): Unit = runBlocking {
        dao.persist(listOf(row("muOpioid", adaptive = 1.0)), NOW)
        dao.persist(listOf(row("muOpioid", adaptive = 2.5)), NOW + 1_000)

        dao.all().size shouldBe 1
        dao.forClass("muOpioid")!!.sAdaptive shouldBe 2.5
        dao.forClass("muOpioid")!!.lastUpdated.time shouldBe NOW + 1_000
    }

    @Test
    fun aClassThatIsNoLongerDrivenIsResetAndKept(): Unit = runBlocking {
        // The rule that is easy to get backwards. A class the user has stopped taking
        // is *rested*, not absent — and "no row" has to keep meaning "never driven",
        // which it cannot if recovery deletes the row.
        dao.persist(listOf(row("muOpioid", adaptive = 1.5, deep = 0.8)), NOW)
        // The next replay drives nothing at all.
        dao.persist(emptyList(), NOW + 1_000)

        val kept = dao.forClass("muOpioid")
        (kept != null) shouldBe true
        kept!!.sAdaptive shouldBe 0.0
        kept.sDeep shouldBe 0.0
        kept.sAcute shouldBe 0.0
        kept.sSynthesis shouldBe 0.0
        kept.chronicExposure shouldBe 0.0
        kept.lastUpdated.time shouldBe NOW + 1_000
    }

    @Test
    fun aKeyThatIsNotAClassIsDeleted(): Unit = runBlocking {
        // Pre-per-class builds cached one row per *receptor*. Those keys name nothing
        // the engine can recompute, so leaving them would grow the table forever —
        // and they must not be confused with the reset rule above.
        dao.upsert(row("5-HT2A", adaptive = 3.0))
        dao.persist(listOf(row("muOpioid", adaptive = 1.0)), NOW)

        dao.forClass("5-HT2A") shouldBe null
        (dao.forClass("muOpioid") != null) shouldBe true
    }

    @Test
    fun anUntouchedClassIsLeftAlone(): Unit = runBlocking {
        // Only the two rules apply: computed, or not computed. A class that is computed
        // keeps its own values even while another is being reset.
        dao.persist(listOf(row("muOpioid", adaptive = 1.0), row("gaba", adaptive = 2.0)), NOW)
        dao.persist(listOf(row("muOpioid", adaptive = 1.5)), NOW + 1_000)

        dao.forClass("muOpioid")!!.sAdaptive shouldBe 1.5
        dao.forClass("gaba")!!.sAdaptive shouldBe 0.0
    }

    @Test
    fun clearEmptiesTheTable(): Unit = runBlocking {
        dao.persist(listOf(row("muOpioid", adaptive = 1.0)), NOW)
        dao.clear()
        dao.all().isEmpty() shouldBe true
    }

    @Test
    fun theObservedFlowCarriesWhatWasPersisted(): Unit = runBlocking {
        dao.persist(listOf(row("gaba", adaptive = 0.7)), NOW)
        val observed = dao.all()
        observed.size shouldBe 1
        observed.first().target shouldBe "gaba"
        observed.first().sAdaptive shouldBe 0.7
    }

    private companion object {
        /** A fixed checkpoint timestamp: the DAO stores it verbatim, so nothing here needs a clock. */
        const val NOW = 1_700_000_000_000L
    }
}
