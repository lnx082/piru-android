package glass.kagerou.piru.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.EsterRecord
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.model.DoseRange
import glass.kagerou.piru.model.DurationProfile
import glass.kagerou.piru.model.DurationRange
import glass.kagerou.piru.model.RouteOfAdministration
import glass.kagerou.piru.model.Substance
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.model.SubstanceRoute
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The session grouper, against real SQLite on a device.
 *
 * The clustering *heuristic* is already covered by the ported engine suite. What
 * is only testable here is the bridge: that the placement precedence reads the
 * right doses, that a stored session's bounds are re-derived rather than trusted,
 * that the backfill leaves the user's own merges alone, and that the manual
 * overrides do what they say.
 */
@RunWith(AndroidJUnit4::class)
class SessionRepositoryTest {

    private lateinit var db: PiruDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PiruDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** A catalog carrying one substance with a four-hour oral curve. */
    private fun catalog(curveMinutes: Double = 240.0) = object : SubstanceCatalog {
        private val substance = Substance(
            name = "Testine",
            category = SubstanceCategory.STIMULANT,
            defaultRoute = RouteOfAdministration.ORAL,
            routes = listOf(
                SubstanceRoute(
                    route = RouteOfAdministration.ORAL,
                    unit = "mg",
                    doses = DoseRange(common = 10.0..20.0, heavy = 40.0),
                    duration = DurationProfile(
                        onset = DurationRange(10.0, 10.0),
                        comeup = DurationRange(20.0, 20.0),
                        peak = DurationRange(90.0, 90.0),
                        offset = DurationRange(curveMinutes - 120.0, curveMinutes - 120.0),
                    ),
                ),
            ),
            halfLifeMinutes = 200.0,
        )

        override fun lookup(name: String): Substance? =
            if (name.equals("Testine", ignoreCase = true)) substance else null

        override fun substanceUID(name: String): String? = null
        override fun esters(parentUID: String): List<EsterRecord> = emptyList()
        override fun productDuration(productName: String): DurationProfile? = null
    }

    private fun repository(curveMinutes: Double = 240.0) =
        SessionRepository(db, catalog(curveMinutes))

    /**
     * Assign a dose, requiring a session back.
     *
     * `assignSession` returns null only for a row id that does not exist, which in
     * a test is a broken fixture rather than an expected outcome — so the `!!` is
     * the assertion.
     */
    private suspend fun assign(repo: SessionRepository, row: Long): java.util.UUID =
        repo.assignSession(row)!!

    /** Log a dose [hoursAgo] before a fixed now, and return its row id. */
    private suspend fun logDose(hoursAgo: Double, substance: String = "Testine"): Long =
        db.doseEntryDao().insert(
            DoseEntryEntity(
                substance = substance,
                amount = 10.0,
                unit = "mg",
                route = RouteOfAdministration.ORAL,
                timestamp = Date(NOW - (hoursAgo * 3_600_000).toLong()),
            ),
        )

    // MARK: - Log-time placement

    @Test
    fun theFirstDoseOpensASession(): Unit = runBlocking {
        val repo = repository()
        val row = logDose(hoursAgo = 0.0)

        val sessionId = assign(repo, row)

        val dose = db.doseEntryDao().byRowId(row)!!
        dose.sessionId shouldBe sessionId
        val session = db.sessionDao().byId(sessionId)!!
        session.startDate.time shouldBe NOW
        session.lastDoseDate!!.time shouldBe NOW
    }

    @Test
    fun aDoseCloseAfterAnotherJoinsIt(): Unit = runBlocking {
        // A redose inside the substance's own curve is the same session — the
        // common case the whole heuristic exists for.
        val repo = repository()
        val first = assign(repo, logDose(hoursAgo = 2.0))
        val second = assign(repo, logDose(hoursAgo = 0.0))

        second shouldBe first
        db.sessionDao().all().size shouldBe 1
    }

    @Test
    fun aDoseFarAfterAnotherStartsItsOwn(): Unit = runBlocking {
        // Well past the 24-hour hard cap in the heuristic, so no amount of
        // spacing makes these one session.
        val repo = repository()
        val first = assign(repo, logDose(hoursAgo = 40.0))
        val second = assign(repo, logDose(hoursAgo = 0.0))

        (second != first) shouldBe true
        db.sessionDao().all().size shouldBe 2
    }

    @Test
    fun aBackDatedDoseInsideAnExistingSpanJoinsIt(): Unit = runBlocking {
        // The in-span rule: a dose logged late but *about* a moment already inside
        // a session belongs to it, rather than spawning an overlapping one.
        val repo = repository()
        val first = assign(repo, logDose(hoursAgo = 4.0))
        assign(repo, logDose(hoursAgo = 0.0))

        // Now a dose dated between them, logged out of order.
        val middle = assign(repo, logDose(hoursAgo = 2.0))

        middle shouldBe first
        db.sessionDao().all().size shouldBe 1
    }

    @Test
    fun aBackDatedDoseJustBeforeAnExistingSessionPrecedesIt(): Unit = runBlocking {
        // The prepend rule, and the reason it exists: "15 minutes ago" logged right
        // after a dose that already opened a session must not become a second one.
        val repo = repository()
        val opened = assign(repo, logDose(hoursAgo = 0.0))
        val earlier = assign(repo, logDose(hoursAgo = 0.25))

        earlier shouldBe opened
        db.sessionDao().all().size shouldBe 1
    }

    @Test
    fun reassigningADoseToItsOwnSessionIsANoOp(): Unit = runBlocking {
        val repo = repository()
        val row = logDose(hoursAgo = 0.0)
        val first = assign(repo, row)
        val again = assign(repo, row)

        again shouldBe first
        db.sessionDao().all().size shouldBe 1
    }

    @Test
    fun theSpanUsedForPlacementIsDerivedFromTheDosesNotTheStoredBounds(): Unit = runBlocking {
        // The stored bounds only *bound the fetch* — a badly-wrong one really does
        // exclude a session. What placement uses is the span re-derived from the
        // doses, so a session whose bounds are narrow but plausible still places a
        // dose that its doses actually contain.
        val repo = repository()
        val first = assign(repo, logDose(hoursAgo = 3.0))
        assign(repo, logDose(hoursAgo = 1.0))
        // Rewrite the bounds to the first dose only, as a stale cache would.
        db.sessionDao().update(
            db.sessionDao().byId(first)!!.copy(
                startDate = Date(NOW - 3 * 3_600_000L),
                lastDoseDate = Date(NOW - 3 * 3_600_000L),
            ),
        )

        // A dose at -2h is inside the *true* span and outside the stored one.
        val joined = assign(repo, logDose(hoursAgo = 2.0))

        joined shouldBe first
    }

    // MARK: - Backfill

    @Test
    fun theBackfillGroupsSessionlessHistory(): Unit = runBlocking {
        logDose(hoursAgo = 2.0)
        logDose(hoursAgo = 0.0)
        // Twelve hours later: a different session.
        logDose(hoursAgo = 12.0 + 4.0)

        val created = repository().ensureSessionsPopulated()

        created shouldBe 2
        db.sessionDao().all().size shouldBe 2
        db.doseEntryDao().unassigned().isEmpty() shouldBe true
    }

    @Test
    fun theBackfillIsANoOpOnceEverythingIsGrouped(): Unit = runBlocking {
        logDose(hoursAgo = 1.0)
        val repo = repository()
        repo.ensureSessionsPopulated() shouldBe 1
        // Safe on every launch, which is what the launch path depends on.
        repo.ensureSessionsPopulated() shouldBe 0
    }

    @Test
    fun theBackfillLeavesTheUsersOwnGroupingAlone(): Unit = runBlocking {
        // An import or a fresh store gets its own grouping; an existing one is never
        // re-clustered. This is why the sweep can run unconditionally.
        val repo = repository()
        val first = assign(repo, logDose(hoursAgo = 2.0))
        assign(repo, logDose(hoursAgo = 0.0))
        val before = db.sessionDao().all().size

        repo.ensureSessionsPopulated() shouldBe 0

        db.sessionDao().all().size shouldBe before
        db.doseEntryDao().byRowId(db.doseEntryDao().forSession(first).first().rowId)!!.sessionId shouldBe first
    }

    // MARK: - The user's own overrides

    @Test
    fun mergeMovesEveryDoseAndDeletesTheSource(): Unit = runBlocking {
        val repo = repository()
        val first = assign(repo, logDose(hoursAgo = 40.0))
        val second = assign(repo, logDose(hoursAgo = 0.0))
        (first != second) shouldBe true

        repo.merge(sourceId = second, targetId = first)

        db.sessionDao().byId(second).shouldBeNull()
        db.sessionDao().all().size shouldBe 1
        db.doseEntryDao().dosesFor(first).size shouldBe 2
    }

    @Test
    fun mergingASessionIntoItselfIsANoOp(): Unit = runBlocking {
        val repo = repository()
        val only = assign(repo, logDose(hoursAgo = 0.0))
        repo.merge(sourceId = only, targetId = only)
        db.doseEntryDao().dosesFor(only).size shouldBe 1
    }

    @Test
    fun splitMovesThePivotAndEverythingAfterIt(): Unit = runBlocking {
        val repo = repository()
        val session = assign(repo, logDose(hoursAgo = 4.0))
        assign(repo, logDose(hoursAgo = 3.0))
        assign(repo, logDose(hoursAgo = 2.0))

        val pivot = db.doseEntryDao().dosesFor(session)[1].rowId
        val created = repo.split(session, pivot)!!

        db.doseEntryDao().dosesFor(created).size shouldBe 2
        db.doseEntryDao().dosesFor(session).size shouldBe 1
    }

    @Test
    fun splittingAtTheFirstDoseDoesNothing(): Unit = runBlocking {
        // Nothing would be left in the original, so the answer is no rather than an
        // empty session.
        val repo = repository()
        val session = assign(repo, logDose(hoursAgo = 1.0))
        assign(repo, logDose(hoursAgo = 0.0))

        val firstRow = db.doseEntryDao().dosesFor(session).first().rowId
        repo.split(session, firstRow).shouldBeNull()
        db.sessionDao().all().size shouldBe 1
    }

    @Test
    fun moveReassignsOneDoseAndDeletesAnEmptiedSession(): Unit = runBlocking {
        val repo = repository()
        val first = assign(repo, logDose(hoursAgo = 40.0))
        val second = assign(repo, logDose(hoursAgo = 0.0))

        val row = db.doseEntryDao().dosesFor(second).first().rowId
        repo.move(row, first)

        db.sessionDao().byId(second).shouldBeNull()
        db.doseEntryDao().byRowId(row)!!.sessionId shouldBe first
    }

    @Test
    fun moveLeavesANonEmptySourceInPlace(): Unit = runBlocking {
        val repo = repository()
        val first = assign(repo, logDose(hoursAgo = 40.0))
        val second = assign(repo, logDose(hoursAgo = 1.0))
        assign(repo, logDose(hoursAgo = 0.0))

        repo.move(db.doseEntryDao().dosesFor(second).first().rowId, first)

        db.sessionDao().byId(second).shouldNotBeNull()
        db.doseEntryDao().dosesFor(second).size shouldBe 1
    }

    @Test
    fun aTitleTrimsToNullWhenCleared(): Unit = runBlocking {
        // A cleared field is the absence of a title, not an empty one — the
        // difference shows up in the journal, which falls back to the date.
        val repo = repository()
        val session = assign(repo, logDose(hoursAgo = 0.0))

        repo.setTitle(session, "  Friday night  ")
        db.sessionDao().byId(session)!!.title shouldBe "Friday night"

        repo.setTitle(session, "   ")
        db.sessionDao().byId(session)!!.title.shouldBeNull()
    }

    private companion object {
        /** A fixed now, so nothing here depends on the wall clock. */
        val NOW = 1_700_000_000_000L
    }

    /** The guard is false at the first dose, which is exactly where `split` refuses. */
    @Test
    fun canSplitAtIsFalseAtTheFirstDose(): Unit = runBlocking {
        val repo = repository()
        val session = assign(repo, logDose(hoursAgo = 2.0))
        assign(repo, logDose(hoursAgo = 1.0))

        val firstRow = db.doseEntryDao().dosesFor(session).first().rowId
        repo.canSplitAt(session, firstRow) shouldBe false
        // And `split` agrees, so the button being hidden matches the write being refused.
        repo.split(session, firstRow) shouldBe null
    }

    /** And true at every later dose, so the action is offered wherever it can succeed. */
    @Test
    fun canSplitAtIsTrueAtEveryLaterDose(): Unit = runBlocking {
        val repo = repository()
        val session = assign(repo, logDose(hoursAgo = 4.0))
        assign(repo, logDose(hoursAgo = 3.0))
        assign(repo, logDose(hoursAgo = 2.0))

        val ordered = db.doseEntryDao().dosesFor(session)
        for (index in 1 until ordered.size) {
            repo.canSplitAt(session, ordered[index].rowId) shouldBe true
        }
    }

    /**
     * The guard and the operation never disagree about the pivot.
     *
     * The case that makes the other two worth having: asserting either alone would pass with a guard that always said
     * true and a button that silently failed. This walks every dose of one session and requires the guard's answer to
     * match whether `split` would actually move anything — which is `null` only at the first dose.
     */
    @Test
    fun theGuardAndTheOperationAgreeOnEveryDose(): Unit = runBlocking {
        val repo = repository()
        val session = assign(repo, logDose(hoursAgo = 4.0))
        assign(repo, logDose(hoursAgo = 3.0))
        assign(repo, logDose(hoursAgo = 2.0))

        val ordered = db.doseEntryDao().dosesFor(session)
        ordered.size shouldBe 3
        // The first dose is the only one `split` refuses, so it is the only one the guard may refuse.
        for ((index, dose) in ordered.withIndex()) {
            repo.canSplitAt(session, dose.rowId) shouldBe (index > 0)
        }
    }

    /** A dose in no session cannot be a pivot, because there is no session to split. */
    @Test
    fun canSplitAtIsFalseForAMissingPivot(): Unit = runBlocking {
        val repo = repository()
        val session = assign(repo, logDose(hoursAgo = 2.0))
        assign(repo, logDose(hoursAgo = 1.0))

        // A row id that belongs to no dose: the guard must answer false rather than throw, because a UI calls it while
        // the row is on screen and a delete may have raced it.
        repo.canSplitAt(session, Long.MAX_VALUE) shouldBe false
    }
}
