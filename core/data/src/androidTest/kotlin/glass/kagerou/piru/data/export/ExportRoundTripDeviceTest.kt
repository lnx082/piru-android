package glass.kagerou.piru.data.export

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.data.PiruDatabase
import glass.kagerou.piru.data.entity.DailyDoseItemEntity
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.data.entity.FavoriteSubstanceEntity
import glass.kagerou.piru.data.entity.SessionEntity
import glass.kagerou.piru.data.entity.SessionNoteEntity
import glass.kagerou.piru.data.entity.SubstanceColorEntity
import glass.kagerou.piru.model.RouteOfAdministration
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The export, out of one real store and into another.
 *
 * The JVM suite pins the *format* — the exact text, the absent-versus-null rule,
 * the number spelling — but it cannot open Room, so it never proves that a row
 * survives the trip. This does: seed a store, export it, import the result into a
 * second store, and compare the columns. It is the only test that would notice a
 * mapping that reads the wrong field on the way out or writes the wrong one on
 * the way in.
 *
 * JUnit 4, because `AndroidJUnitRunner` is a JUnit 4 runner. Every test here is a
 * block body; an expression body would need an explicit `: Unit` or the runner
 * rejects the whole class.
 */
@RunWith(AndroidJUnit4::class)
class ExportRoundTripDeviceTest {

    private lateinit var source: PiruDatabase
    private lateinit var target: PiruDatabase

    @Before
    fun openStores() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        source = inMemory(context)
        target = inMemory(context)
    }

    @After
    fun closeStores() {
        source.close()
        target.close()
    }

    /**
     * Two independent stores, so "the export" and "the import" cannot be the same
     * rows read twice. In-memory, because a round trip that left a file behind on
     * a device running this suite would be worse than no suite.
     */
    private fun inMemory(context: android.content.Context): PiruDatabase =
        Room.inMemoryDatabaseBuilder(context, PiruDatabase::class.java)
            .addMigrations(PiruDatabase.MIGRATION_1_2, PiruDatabase.MIGRATION_2_3)
            .build()

    // MARK: - The round trip

    @Test
    fun everyJournalledFieldSurvivesTheRoundTrip() {
        runBlocking {
            val sessionId = UUID.randomUUID()
            val doseId = UUID.randomUUID()
            val noteId = UUID.randomUUID()

            source.sessionDao().insert(
                SessionEntity(
                    id = sessionId,
                    startDate = Date(1_699_000_000_000),
                    title = "An evening",
                    note = "kept",
                    checkInIntervalMinutes = 90.0,
                    checkInOffsetsJson = "[0,60]",
                    checkInOffered = true,
                ),
            )
            source.doseEntryDao().insert(
                DoseEntryEntity(
                    id = doseId,
                    substance = "Caffeine",
                    amount = 100.0,
                    unit = "mg",
                    route = RouteOfAdministration.ORAL,
                    saltForm = "citrate",
                    substanceUID = "caffeine",
                    isomer = "racemic",
                    releaseForm = "extended",
                    productName = "Brand",
                    displayNameSnapshot = "Caffeine",
                    timestamp = Date(1_699_000_000_000),
                    notes = "with breakfast",
                    // CSV, not JSON — and the two look identical at a call site,
                    // which is the trap. `DoseEntryEntity.tags` splits on a comma;
                    // writing a JSON array here produced a single tag whose text
                    // was `["morning"]`, and the round trip faithfully preserved
                    // that nonsense. The export reads the parsed list, so the test
                    // has to start from what the field actually holds.
                    tagsRaw = "morning",
                    sessionId = sessionId,
                    isBackgroundMed = true,
                    locationName = "Kitchen",
                    latitude = 51.5074,
                    longitude = -0.1278,
                    hadGrapefruit = false,
                    isApproximate = true,
                    isUnknownDose = false,
                    volumeML = 330.0,
                    abv = 5.2,
                    drinkName = "Lager",
                ),
            )
            source.sessionNoteDao().insert(
                SessionNoteEntity(
                    id = noteId,
                    timestamp = Date(1_699_000_060_000),
                    text = "settling in",
                    shulgin = 2,
                    mood = 1,
                    energy = 0,
                    social = -1,
                    worked = 1,
                    descriptorsJson = encodeStrings(listOf("visuals")),
                    heartRate = 72.5,
                    kindRaw = SessionNoteEntity.Kind.CHECK_IN.wireValue,
                    sessionId = sessionId,
                ),
            )
            source.sessionDao().refreshDoseBounds(sessionId)

            val text = DataExportImport.exportJSON(
                format = ExportFormat.PIRU,
                db = source,
                appVersion = "Piru 0.1.0 (1)",
                now = java.time.Instant.ofEpochMilli(1_700_000_000_000),
            )
            DataExportImport.importJSON(text, target)

            val session = target.sessionDao().byId(sessionId)
            assertNotNull(session)
            assertEquals("An evening", session!!.title)
            assertEquals("kept", session.note)
            assertEquals(90.0, session.checkInIntervalMinutes!!, 0.0)
            assertEquals(listOf(0, 60), session.checkInOffsetMinutes)
            assertTrue(session.checkInOffered)

            val dose = target.doseEntryDao().byId(doseId)
            assertNotNull(dose)
            assertEquals("Caffeine", dose!!.substance)
            assertEquals(100.0, dose.amount, 0.0)
            assertEquals("mg", dose.unit)
            assertEquals(RouteOfAdministration.ORAL, dose.route)
            assertEquals("citrate", dose.saltForm)
            assertEquals("caffeine", dose.substanceUID)
            assertEquals("racemic", dose.isomer)
            assertEquals("extended", dose.releaseForm)
            assertEquals("Brand", dose.productName)
            assertEquals("Caffeine", dose.displayNameSnapshot)
            assertEquals(1_699_000_000_000, dose.timestamp.time)
            assertEquals("with breakfast", dose.notes)
            assertEquals(listOf("morning"), dose.tags)
            assertEquals(sessionId, dose.sessionId)
            assertTrue(dose.isBackgroundMed)
            assertEquals("Kitchen", dose.locationName)
            assertEquals(51.5074, dose.latitude!!, 0.0)
            assertEquals(-0.1278, dose.longitude!!, 0.0)
            assertEquals(false, dose.hadGrapefruit)
            assertTrue(dose.isApproximate)
            assertEquals(false, dose.isUnknownDose)
            assertEquals(330.0, dose.volumeML!!, 0.0)
            assertEquals(5.2, dose.abv!!, 0.0)
            assertEquals("Lager", dose.drinkName)

            val note = target.sessionNoteDao().byId(noteId)
            assertNotNull(note)
            assertEquals("settling in", note!!.text)
            assertEquals(2, note.shulgin)
            assertEquals(-1, note.social)
            assertEquals(listOf("visuals"), note.descriptors)
            assertEquals(72.5, note.heartRate!!, 0.0)
            assertEquals(SessionNoteEntity.Kind.CHECK_IN, note.kind)
            assertEquals(sessionId, note.sessionId)
        }
    }

    @Test
    fun theCuratedRowsSurviveAndAReimportAddsNothing() {
        runBlocking {
            source.dailyDoseItemDao().insert(
                DailyDoseItemEntity(
                    substance = "Sertraline",
                    amount = 50.0,
                    unit = "mg",
                    route = RouteOfAdministration.ORAL,
                    sortOrder = 2,
                    category = "med",
                    isBackgroundMed = true,
                    frequencyRaw = "specificDays",
                    frequencyDaysJson = encodeInts(listOf(2, 4, 6)),
                    startDate = Date(1_600_000_000_000),
                    substanceUID = "sertraline",
                    saltForm = "hydrochloride",
                    reminderTimesJson = encodeInts(listOf(480, 1_200)),
                    remind = true,
                    isQuiet = true,
                    maxPerDay = 3,
                ),
            )
            source.favoriteSubstanceDao().insert(
                FavoriteSubstanceEntity(
                    substance = "Caffeine",
                    createdAt = Date(1_650_000_000_000),
                    sortOrder = 1,
                    substanceUID = "caffeine",
                ),
            )
            source.substanceColorDao().insert(
                SubstanceColorEntity(
                    substance = "Caffeine",
                    hexColor = "",
                    red = 0.898,
                    green = 0.497,
                    blue = 0.591,
                    usesDefault = false,
                ),
            )

            val text = DataExportImport.exportJSON(
                format = ExportFormat.PIRU,
                db = source,
                appVersion = "Piru 0.1.0 (1)",
            )

            DataExportImport.importJSON(text, target)
            // Twice: a merge re-importing the same file must add nothing.
            DataExportImport.importJSON(text, target)

            val meds = target.dailyDoseItemDao().all()
            assertEquals(1, meds.size)
            assertEquals("Sertraline", meds[0].substance)
            assertEquals(50.0, meds[0].amount, 0.0)
            assertEquals("med", meds[0].category)
            assertEquals(listOf(2, 4, 6), meds[0].frequencyDays)
            assertEquals(listOf(480, 1_200), meds[0].reminderTimesMinutes)
            assertTrue(meds[0].isBackgroundMed)
            assertTrue(meds[0].isQuiet)
            assertEquals(3, meds[0].maxPerDay)

            assertEquals(1, target.favoriteSubstanceDao().all().size)

            val color = target.substanceColorDao().forSubstance("Caffeine")
            assertNotNull(color)
            assertEquals(0.898, color!!.red, 0.0)
            assertEquals(0.497, color.green, 0.0)
            assertEquals(0.591, color.blue, 0.0)
            assertEquals(false, color.usesDefault)
        }
    }

    @Test
    fun aCustomSubstanceWithItsLadderAndDurationSurvives() {
        runBlocking {
            val data = PiruCustomSubstanceData(
                id = UUID.randomUUID().toString().uppercase(),
                name = "My Mix",
                category = "other",
                defaultRoute = "insufflation",
                unit = "mg",
                notes = "keep refrigerated",
                createdAt = 1_600_000_000_000,
                displayName = "Mix",
                doses = glass.kagerou.piru.model.DoseRange(
                    threshold = 1.0,
                    light = 2.0..4.0,
                    heavy = 40.0,
                ),
                halfLifeMinutes = 90.0,
            )
            // Straight through the importer, which is how a PiRu-native file reaches
            // this table.
            NativeImport.importCustomSubstances(listOf(data), source)

            val text = DataExportImport.exportJSON(
                format = ExportFormat.PIRU,
                db = source,
                appVersion = "Piru 0.1.0 (1)",
            )
            DataExportImport.importJSON(text, target)

            val row = target.customSubstanceDao().byName("My Mix")
            assertNotNull(row)
            assertEquals("Mix", row!!.displayName)
            assertEquals("insufflation", row.defaultRouteRaw)
            assertEquals("keep refrigerated", row.notes)
            assertEquals(90.0, row.halfLifeMinutes!!, 0.0)
            assertEquals(1_600_000_000_000, row.createdAt.time)
            assertEquals(2.0..4.0, row.doses!!.light)
            assertEquals(40.0, row.doses!!.heavy!!, 0.0)
        }
    }

    @Test
    fun deleteAllEmptiesEveryTableTheUserOwns() {
        runBlocking {
            source.doseEntryDao().insert(
                DoseEntryEntity(substance = "Caffeine", amount = 1.0, timestamp = Date(0)),
            )
            source.substanceColorDao().insert(SubstanceColorEntity(substance = "Caffeine"))
            source.userProfileDao().insert(
                glass.kagerou.piru.data.entity.UserProfileRecordEntity(bodyWeightKg = 70.0),
            )
            source.notificationPreferencesDao().insert(
                glass.kagerou.piru.data.entity.NotificationPreferencesEntity(),
            )
            source.labMeasurementDao().insert(
                glass.kagerou.piru.data.entity.LabMeasurementEntity(
                    id = "lab-erase",
                    date = Date(0),
                    analyteKey = "estradiol",
                    value = 100.0,
                    inputUnit = "pg/mL",
                ),
            )

            DataExportImport.deleteAll(source)

            assertEquals(0L, source.doseEntryDao().count())
            assertTrue(source.sessionDao().all().isEmpty())
            assertTrue(source.substanceColorDao().all().isEmpty())
            assertTrue(source.toleranceStateDao().all().isEmpty())
            assertEquals(null, source.userProfileDao().current())
            assertEquals(null, source.notificationPreferencesDao().current())
            assertTrue(source.favoriteSubstanceDao().all().isEmpty())
            assertTrue(source.dailyDoseItemDao().all().isEmpty())
            assertTrue(source.customSubstanceDao().all().isEmpty())
            assertTrue(source.quickLogDoseDao().all().isEmpty())
            assertTrue(source.inventoryDao().all().isEmpty())
            assertTrue(source.labMeasurementDao().all().isEmpty())
        }
    }

    @Test
    fun anImportRefusesAnEncryptedEnvelopeByItsShape() {
        val error = runCatching {
            runBlocking { DataExportImport.importJSON("""{"sealed":"AAAA","kind":"passphrase"}""", target) }
        }.exceptionOrNull()

        assertTrue(error is DataExportImport.ImportFileException.Encrypted)
    }

    /**
     * A lab result survives the trip, because it used to survive nothing.
     *
     * Until v3 these rows lived in a `SharedPreferences` file, which meant the export
     * could not see them, the import counted them as unsupported, and "Delete
     * Everything" left them on the device. This is the test that fails if any of those
     * three come back — it seeds one, exports, imports into a second store, and
     * compares every column including the two (`note`, `createdAt`) that only exist
     * because iOS carries them.
     */
    @Test
    fun aLabMeasurementSurvivesTheRoundTrip() {
        runBlocking {
            source.labMeasurementDao().insert(
                glass.kagerou.piru.data.entity.LabMeasurementEntity(
                    id = "lab-1",
                    date = Date(1_699_100_000_000),
                    analyteKey = "estradiol",
                    value = 187.5,
                    inputUnit = "pmol/L",
                    esterId = "estradiol_valerate",
                    excludedFromCalibration = true,
                    note = "trough, 12 h after dose",
                    createdAt = Date(1_699_100_500_000),
                ),
            )

            val json = DataExportImport.exportJSON(
                format = ExportFormat.PIRU,
                db = source,
                appVersion = "Piru 0.1.0 (1)",
            )
            val report = DataExportImport.importJSON(json, target)

            // No longer reported as a section this build cannot store.
            assertEquals(
                0,
                report.unsupported.count { it.name == "labMeasurements" },
            )

            val rows = target.labMeasurementDao().all()
            assertEquals(1, rows.size)
            val row = rows.single()
            assertEquals("lab-1", row.id)
            assertEquals(1_699_100_000_000L, row.date.time)
            assertEquals("estradiol", row.analyteKey)
            assertEquals(187.5, row.value, 0.0)
            assertEquals("pmol/L", row.inputUnit)
            assertEquals("estradiol_valerate", row.esterId)
            assertTrue(row.excludedFromCalibration)
            assertEquals("trough, 12 h after dose", row.note)
            assertEquals(1_699_100_500_000L, row.createdAt.time)
        }
    }

    /**
     * And an erase takes them with it.
     *
     * The sibling of the round trip: a restore is not the only path these rows have to
     * appear in, and an erase that leaves a user's blood results behind is the worse
     * half of the same bug.
     */
    @Test
    fun deleteAllEmptiesTheLabMeasurementsToo() {
        runBlocking {
            source.labMeasurementDao().insert(
                glass.kagerou.piru.data.entity.LabMeasurementEntity(
                    id = "lab-2",
                    date = Date(0),
                    analyteKey = "testosterone",
                    value = 540.0,
                    inputUnit = "ng/dL",
                ),
            )

            DataExportImport.deleteAll(source)

            assertTrue(source.labMeasurementDao().all().isEmpty())
        }
    }

    private fun encodeInts(values: List<Int>): String = glass.kagerou.piru.data.JsonLists.encode(values)

    private fun encodeStrings(values: List<String>): String =
        glass.kagerou.piru.data.JsonLists.encodeStrings(values)
}
