package glass.kagerou.piru.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.PharmacologyParameters
import glass.kagerou.piru.engine.PharmacologySource
import glass.kagerou.piru.engine.ReceptorClasses
import glass.kagerou.piru.model.BindingAction
import glass.kagerou.piru.model.ConfidenceTier
import glass.kagerou.piru.model.RouteOfAdministration
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app-side plumbing, against real SQLite on a device: the log read, the cache
 * gate, the catalog resolve, the write, and the read-back.
 *
 * Instrumented because the two things most likely to be wrong here are exactly the
 * two a JVM test cannot see — what Room actually stores, and whether the gate's idea
 * of "unchanged" matches the replay's idea of "same inputs".
 *
 * The pharmacology is faked. The real catalog's own behaviour is covered in
 * `:core:substance`; what is under test here is the wiring, and a fake is what makes
 * a log's *content* the only variable.
 */
@RunWith(AndroidJUnit4::class)
class ToleranceRepositoryTest {

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

    /** A morphinan-shaped parameter set, so a dose of any size drives the μ-opioid class. */
    private fun opioidParams() = PharmacologyParameters(
        molarMassGramsPerMole = 285.0,
        vdLPerKg = 3.5,
        bioavailabilityFraction = 1.0,
        bioavailabilityConfidence = ConfidenceTier.HIGH,
        doseScale = 1.0,
        doseScaleConfidence = ConfidenceTier.HIGH,
        halfLifeMinutes = 200.0,
        vdConfidence = ConfidenceTier.HIGH,
        referenceDoseMg = 100.0,
        suppressesSerotoninSynthesis = false,
        targets = listOf(
            PharmacologyParameters.TargetEngagement(
                target = "MOR",
                targetBase = "mor",
                action = BindingAction.AGONIST,
                halfMaxNanomolar = 10.0,
                kind = PharmacologyParameters.HalfMaxKind.KI,
                confidence = ConfidenceTier.HIGH,
            ),
        ),
    )

    /**
     * What the catalog answers for a substance it carries but cannot model: no PK at
     * all, and a category — which is what makes it *incomplete data* rather than
     * simply unknown. A name the catalog does not carry at all answers absent, and
     * that is a different case.
     */
    private fun unmodellableParams() = PharmacologyParameters(
        molarMassGramsPerMole = null,
        vdLPerKg = null,
        bioavailabilityFraction = null,
        bioavailabilityConfidence = ConfidenceTier.UNVERIFIED,
        doseScale = 1.0,
        doseScaleConfidence = ConfidenceTier.HIGH,
        halfLifeMinutes = null,
        vdConfidence = ConfidenceTier.UNVERIFIED,
        referenceDoseMg = null,
        suppressesSerotoninSynthesis = false,
        targets = emptyList(),
        categoryClasses = setOf(ReceptorClasses.ReceptorClass.MU_OPIOID),
    )

    /**
     * A catalog that gives three distinguishable answers, because the engine's
     * missing-PK diagnostic turns on the difference between them:
     * - **[modellable]** — a full parameter set; the dose replays.
     * - **[carried]** — a substance the catalog knows and categorises but carries no
     *   PK for. This is the research-chemical case the fallback and the "incomplete
     *   data" warning exist for.
     * - **anything else** — a name the catalog has never heard of, which answers
     *   absent. Conflating this with the previous case would put a warning on a typo.
     */
    private fun source(
        modellable: Set<String> = emptySet(),
        carried: Set<String> = emptySet(),
    ) = object : PharmacologySource {
        override fun pharmacologyParameters(nameOrAlias: String): PharmacologyParameters = when {
            modellable.any { it.equals(nameOrAlias, ignoreCase = true) } -> opioidParams()
            carried.any { it.equals(nameOrAlias, ignoreCase = true) } -> unmodellableParams()
            else -> absentParams()
        }

        override fun classRepresentativeNames(): List<String> = emptyList()
    }

    /** What the catalog answers for a name it has never heard of: nothing at all. */
    private fun absentParams() = unmodellableParams().copy(categoryClasses = emptySet())

    private fun repository(
        source: PharmacologySource,
        atMinute: Double = 0.0,
    ) = ToleranceRepository(
        database = db,
        pharmacology = source,
        weightKg = { 70.0 },
        now = { Instant.ofEpochSecond((atMinute * 60).toLong()) },
    )

    private suspend fun logDose(
        substance: String = "Morphine",
        amount: Double = 10.0,
        unit: String = "mg",
        atMinute: Double = -120.0,
        isUnknown: Boolean = false,
    ) {
        db.doseEntryDao().upsert(
            DoseEntryEntity(
                substance = substance,
                amount = amount,
                unit = unit,
                route = RouteOfAdministration.ORAL,
                timestamp = Date((atMinute * 60_000).toLong()),
                isUnknownDose = isUnknown,
            ),
        )
    }

    @Test
    fun aReplayTurnsTheLogIntoStateAndCachesIt(): Unit = runBlocking {
        logDose()
        val repo = repository(source(modellable = setOf("Morphine")))

        repo.recompute() shouldBe true

        val card = repo.states.value.getValue(ReceptorClasses.ReceptorClass.MU_OPIOID)
        (card.sAdaptive > 0) shouldBe true
        card.contributors shouldBe listOf("Morphine")

        // And it reached the database, carrying the checkpoint the next launch needs.
        val row = db.toleranceStateDao().forClass(ReceptorClasses.ReceptorClass.MU_OPIOID.wireValue)
        row.shouldNotBeNull()
        row.sAdaptive shouldBe card.sAdaptive
        row.chronicExposure shouldBe card.chronicExposure
    }

    @Test
    fun anUnchangedLogSkipsTheReplay(): Unit = runBlocking {
        // Navigating back into the tool is the common case and the replay is the
        // expensive one, so this is the property that makes it free.
        logDose()
        val repo = repository(source(modellable = setOf("Morphine")))
        repo.recompute() shouldBe true
        repo.recompute() shouldBe false
    }

    @Test
    fun loggingADoseOpensTheGateAgain(): Unit = runBlocking {
        logDose()
        val repo = repository(source(modellable = setOf("Morphine")))
        repo.recompute() shouldBe true

        logDose(atMinute = -60.0)
        repo.recompute() shouldBe true
        // The new dose is in the replay, so the card moved.
        val card = repo.states.value.getValue(ReceptorClasses.ReceptorClass.MU_OPIOID)
        card.contributors shouldBe listOf("Morphine")
    }

    @Test
    fun aDoseOfUnknownAmountIsNotReplayed(): Unit = runBlocking {
        // It has no concentration to compute, so it is absent from the log rather than
        // present as a zero — the same rule every numeric engine follows.
        logDose(isUnknown = true)
        val repo = repository(source(modellable = setOf("Morphine")))
        // The replay *runs* — the log it was handed is empty, which is a result, not a
        // reason to skip. What matters is that it produced no card.
        repo.recompute() shouldBe true
        repo.states.value.isEmpty() shouldBe true
    }

    @Test
    fun aDoseInAVolumeIsNotReplayed(): Unit = runBlocking {
        // Millilitres are not a mass; there is no milligram equivalent to integrate.
        logDose(unit = "mL", amount = 5.0)
        val repo = repository(source(modellable = setOf("Morphine")))
        repo.recompute() shouldBe true
        repo.states.value.isEmpty() shouldBe true
    }

    @Test
    fun aMassUnitOtherThanMilligramsStillConverts(): Unit = runBlocking {
        // The conversion is the point: a dose logged in grams is the same dose.
        logDose(amount = 0.01, unit = "g")
        val repo = repository(source(modellable = setOf("Morphine")))
        repo.recompute() shouldBe true
        repo.states.value.getValue(ReceptorClasses.ReceptorClass.MU_OPIOID).contributors shouldBe
            listOf("Morphine")
    }

    @Test
    fun aCacheLoadWarmsTheLayersWithoutClaimingTheyWereComputed(): Unit = runBlocking {
        logDose()
        repository(source(modellable = setOf("Morphine"))).recompute()

        val fresh = repository(source(modellable = setOf("Morphine")))
        fresh.loadCached()
        val warm = fresh.states.value.getValue(ReceptorClasses.ReceptorClass.MU_OPIOID)
        (warm.sAdaptive > 0) shouldBe true

        // The four layers are real; everything the cache does not carry is a neutral
        // placeholder rather than a guess, so a caller cannot mistake a warm card for a
        // computed one.
        warm.confidence shouldBe ConfidenceTier.UNVERIFIED
        warm.subTargets.isEmpty() shouldBe true
        warm.contributors.isEmpty() shouldBe true
        warm.safetyShiftFactor shouldBe null
        warm.representativeOccupancy shouldBe 0.5
    }

    @Test
    fun aLoadedCacheDoesNotSuppressTheFirstReplay(): Unit = runBlocking {
        // The gate is per-process state, so a fresh process always replays once — a
        // loaded cache is not a computed result and must not stand in for one.
        logDose()
        repository(source(modellable = setOf("Morphine"))).recompute()

        val fresh = repository(source(modellable = setOf("Morphine")))
        fresh.loadCached()
        fresh.recompute() shouldBe true
    }

    @Test
    fun aClassTheLogNoLongerDrivesIsResetRatherThanDeleted(): Unit = runBlocking {
        // The end-to-end version of the DAO rule: a substance the user stopped taking
        // reads as rested, and the row survives so "no row" keeps meaning "never
        // driven". This is the behaviour a naive rewrite-the-table cache gets wrong.
        logDose()
        val repo = repository(source(modellable = setOf("Morphine")))
        repo.recompute() shouldBe true
        (repo.states.value.isNotEmpty()) shouldBe true

        // The log is emptied, and enough time passes that the gate reopens.
        db.doseEntryDao().deleteAll()
        val later = repository(source(modellable = setOf("Morphine")), atMinute = 600.0)
        later.recompute() shouldBe true
        later.states.value.isEmpty() shouldBe true

        val row = db.toleranceStateDao().forClass(ReceptorClasses.ReceptorClass.MU_OPIOID.wireValue)
        row.shouldNotBeNull()
        row.sAdaptive shouldBe 0.0
        row.sDeep shouldBe 0.0
    }

    @Test
    fun aSubstanceTheCatalogCarriesButCannotModelIsReported(): Unit = runBlocking {
        // "No tolerance predicted" and "no tolerance" look identical on a card, so the
        // log has to be able to say which one it is. This substance is categorised —
        // the app knows what it is — and carries no PK and no representative to borrow.
        logDose(substance = "Unmodellable", amount = 10.0)
        val repo = repository(source(modellable = setOf("Morphine"), carried = setOf("Unmodellable")))
        repo.recompute() shouldBe true

        repo.states.value.isEmpty() shouldBe true
        repo.incompleteData.value shouldBe setOf("Unmodellable")
    }

    @Test
    fun aNameTheCatalogHasNeverHeardOfIsNotReported(): Unit = runBlocking {
        // The diagnostic is a claim about the *evidence base* for something the app
        // knows, so a name it has never heard of is a different answer — and conflating
        // the two would put a warning on every typo, which is how a warning stops
        // meaning anything.
        logDose(substance = "Typo", amount = 10.0)
        val repo = repository(source(modellable = setOf("Morphine")))
        repo.recompute() shouldBe true

        repo.states.value.isEmpty() shouldBe true
        repo.incompleteData.value.isEmpty() shouldBe true
    }
}
