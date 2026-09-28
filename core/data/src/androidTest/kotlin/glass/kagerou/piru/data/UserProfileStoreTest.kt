package glass.kagerou.piru.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.engine.PKModel
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The profile row, and the body weight the models are scaled by.
 *
 * The failure this suite exists to prevent is not a crash: it is the app asking
 * for a weight and then computing everything for a 60 kg person anyway. That
 * shipped once — the number went into a preferences file nothing read — and it
 * produced no error, no warning and no visibly wrong screen. Only an assertion
 * that the stored value is the one the models receive can catch it.
 */
@RunWith(AndroidJUnit4::class)
class UserProfileStoreTest {

    private lateinit var db: PiruDatabase
    private lateinit var store: UserProfileStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PiruDatabase::class.java,
        ).build()
        store = UserProfileStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun anUnsetWeightFallsBackToTheEngineReference(): Unit = runBlocking {
        store.load()
        // The engine's own anchor, not a number of the store's own choosing: the
        // alcohol Vmax is calibrated against it, so a different default here would
        // silently recalibrate that curve.
        store.weightKgOrDefault() shouldBe PKModel.REFERENCE_BODY_WEIGHT_KG
        store.weightSource() shouldBe UserProfileStore.WeightSource.ESTIMATED
    }

    @Test
    fun aStoredWeightIsTheOneTheModelsGet(): Unit = runBlocking {
        store.load()
        store.setWeight(82.5, UserProfileStore.WeightSource.MANUAL)
        store.weightKgOrDefault() shouldBe 82.5
        store.weightSource() shouldBe UserProfileStore.WeightSource.MANUAL
    }

    @Test
    fun loadingReadsBackWhatWasWritten(): Unit = runBlocking {
        store.setWeight(71.0, UserProfileStore.WeightSource.MANUAL)
        // A second store over the same database, standing in for the next launch.
        val reopened = UserProfileStore(db)
        reopened.load()
        reopened.weightKgOrDefault() shouldBe 71.0
    }

    @Test
    fun thereIsOnlyEverOneRow(): Unit = runBlocking {
        store.load()
        store.setWeight(70.0, UserProfileStore.WeightSource.MANUAL)
        store.setWeight(75.0, UserProfileStore.WeightSource.MANUAL)
        store.setDisclosureTier("casual")
        store.setAldh2Deficient(true)
        store.setGrapefruitLogging(true)

        // Two rows would be two answers to "how heavy is this person", and which
        // one the models used would depend on row order.
        db.userProfileDao().count() shouldBe 1
        store.weightKgOrDefault() shouldBe 75.0
    }

    @Test
    fun aHealthConnectReadingDoesNotOverwriteATypedNumber(): Unit = runBlocking {
        store.load()
        store.setWeight(64.0, UserProfileStore.WeightSource.MANUAL)

        store.syncWeightFromHealthConnect(90.0).shouldBeNull()
        store.weightKgOrDefault() shouldBe 64.0
        store.weightSource() shouldBe UserProfileStore.WeightSource.MANUAL
    }

    @Test
    fun aHealthConnectReadingReplacesAnEarlierReading(): Unit = runBlocking {
        store.load()
        store.syncWeightFromHealthConnect(80.0)
        store.syncWeightFromHealthConnect(81.5)
        store.weightKgOrDefault() shouldBe 81.5
        store.weightSource() shouldBe UserProfileStore.WeightSource.HEALTH_CONNECT

        // And a typed number takes over from it permanently.
        store.setWeight(70.0, UserProfileStore.WeightSource.MANUAL)
        store.syncWeightFromHealthConnect(99.0).shouldBeNull()
        store.weightKgOrDefault() shouldBe 70.0
    }

    @Test
    fun deletingEverythingRemovesTheRowRatherThanResettingIt(): Unit = runBlocking {
        store.setWeight(70.0, UserProfileStore.WeightSource.MANUAL)
        store.resetAfterDeletion()

        // Gone, not zeroed: a row that survived the deletion with default contents
        // is the kind of leftover that makes "delete everything" not quite.
        db.userProfileDao().count() shouldBe 0
        store.weightKgOrDefault() shouldBe PKModel.REFERENCE_BODY_WEIGHT_KG
        store.load()
        db.userProfileDao().count() shouldBe 1
    }

    @Test
    fun aZeroOrNegativeWeightIsTreatedAsUnset(): Unit = runBlocking {
        // Reachable from a form field somebody cleared, and a zero here divides
        // every concentration by nothing.
        store.load()
        store.setWeight(0.0, UserProfileStore.WeightSource.MANUAL)
        store.weightKgOrDefault() shouldBe PKModel.REFERENCE_BODY_WEIGHT_KG
    }
}
