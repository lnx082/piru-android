package glass.kagerou.piru.data.export

import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.model.DoseRange
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.model.RouteOfAdministration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test

/**
 * The export format, pinned at the level that matters.
 *
 * Two things are checked here and they are different in kind:
 *
 * 1. **The exact text.** The export is the only thing a user carries between the
 *    iOS app and this one, so its bytes are an interface, not an implementation
 *    detail. A pinned literal is the only assertion that fails when the format
 *    drifts — a round-trip test passes for any self-consistent scheme, including
 *    one that writes `1.0` where Foundation writes `1`, or `null` where it omits
 *    the key.
 * 2. **A real round trip.** Build a file, write it, read it back, and compare
 *    every field. That is what catches a mapping that loses a column.
 *
 * What is *not* here: writing to and reading from a database. Room needs a device,
 * so the store-level round trip lives in
 * `core/data/src/androidTest/.../DataExportImportDeviceTest.kt`, which runs under
 * the instrumentation runner.
 */
class ExportFormatTest {

    // MARK: - The printer

    @Test
    fun `the printer writes Foundation's shape, sorted and space-colon-space`() {
        val element = buildJsonObject {
            put("b", JsonPrimitive(1))
            put("a", JsonPrimitive("x"))
            put("c", buildJsonObject { put("z", JsonPrimitive(true)) })
        }
        FoundationJSON.write(element) shouldBe
            """
            {
              "a" : "x",
              "b" : 1,
              "c" : {
                "z" : true
              }
            }
            """.trimIndent()
    }

    @Test
    fun `an empty container keeps its two newlines`() {
        // Foundation's pretty printer does not collapse `[]` to `[]`; it leaves the
        // newline it would have put after the opener and the one before the closer.
        FoundationJSON.write(JsonArray(emptyList())) shouldBe "[\n\n]"
        FoundationJSON.write(buildJsonObject { }) shouldBe "{\n\n}"
    }

    @Test
    fun `an array of arrays indents each level once`() {
        val element = JsonArray(listOf(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))))
        FoundationJSON.write(element) shouldBe "[\n  [\n    1,\n    2\n  ]\n]"
    }

    @Test
    fun `a string escapes what JSON requires and nothing else`() {
        val element = buildJsonObject {
            put("s", JsonPrimitive("a\"b\\c\nd\te/ f\u0001"))
        }
        // The forward slash is left alone: escaping it is legal but a modern Swift
        // writer does not, and the escaping of everything else is not optional.
        FoundationJSON.write(element) shouldBe "{\n  \"s\" : \"a\\\"b\\\\c\\nd\\te/ f\\u0001\"\n}"
    }

    // MARK: - A small instance, pinned byte for byte

    @Test
    fun `a one-dose Piru file is exactly this text`() {
        FoundationJSON.write(nativeJsonOf(sampleFile())) shouldBe SAMPLE_FILE_JSON
    }

    @Test
    fun `nil optionals are omitted, a required section is written empty`() {
        val text = FoundationJSON.write(nativeJsonOf(sampleFile()))
        // `title` is nil and must not appear at all — Swift's synthesized
        // `encodeIfPresent` omits it, and a written `null` would be a different
        // file for the same data.
        text shouldNotContain "null"
        // `notes` is an array the iOS build always writes, empty or not.
        text shouldContain "\"notes\" : [\n\n      ]"
    }

    @Test
    fun `an integral Double is written without a decimal point`() {
        // `amount = 100.0` reaches the file as `100`, which is what Foundation's
        // NSNumber-backed writer produces. `0.5` keeps its fraction.
        val text = FoundationJSON.write(nativeJsonOf(sampleFile()))
        text shouldContain "\"amount\" : 100"
        text shouldContain "\"amount\" : 0.5"
        text shouldNotContain "100.0"
    }

    @Test
    fun `identifiers are uppercase, as Swift's UUID writes them`() {
        val text = FoundationJSON.write(nativeJsonOf(sampleFile()))
        text shouldContain "\"id\" : \"${SESSION_ID.toString().uppercase()}\""
        text shouldContain "\"id\" : \"${DOSE_ID.toString().uppercase()}\""
    }

    // MARK: - Round trip

    @Test
    fun `a Piru file survives write and read with every field intact`() {
        val original = sampleFile()
        val text = FoundationJSON.write(nativeJsonOf(original))
        val decoded = DataExportImport.wireJson.decodeFromString(PiruFile.serializer(), text)
        decoded shouldBe original
    }

    @Test
    fun `a fully populated dose survives write and read`() {
        val original = PiruFile(
            piruExportVersion = DataExportImport.PIRU_EXPORT_VERSION,
            appVersion = "Piru 0.1.0 (1)",
            exportedAt = 1_700_000_000_000,
            orphanDoses = listOf(
                PiruDoseData(
                    id = UUID.randomUUID().toString().uppercase(),
                    substance = "Magnesium Glycinate",
                    amount = 200.0,
                    unit = "mg",
                    route = "oral",
                    saltForm = "glycinate",
                    substanceUID = "magnesium",
                    isomer = "racemic",
                    releaseForm = "extended",
                    productName = "Brand",
                    displayNameSnapshot = "Magnesium",
                    timestamp = 1_700_000_000_000,
                    notes = "before bed #sleep",
                    tags = listOf("sleep"),
                    isBackgroundMed = true,
                    locationName = "Home",
                    latitude = 51.5074,
                    longitude = -0.1278,
                    isApproximate = true,
                    isUnknownDose = false,
                    hadGrapefruit = false,
                    volumeML = 330.0,
                    abv = 5.2,
                    drinkName = "Lager",
                ),
            ),
            customSubstances = listOf(
                PiruCustomSubstanceData(
                    id = UUID.randomUUID().toString().uppercase(),
                    name = "Custom",
                    category = "other",
                    defaultRoute = "insufflation",
                    unit = "mg",
                    notes = "note",
                    duration = null,
                    createdAt = 1,
                    displayName = "Custom Display",
                    doses = DoseRange(threshold = 1.0, light = 2.0..4.0, heavy = 40.0),
                    halfLifeMinutes = 90.0,
                ),
            ),
            inventory = listOf(
                PiruInventoryData(
                    id = UUID.randomUUID().toString().uppercase(),
                    sortOrder = 3,
                    substance = "Sertraline",
                    saltForm = null,
                    unit = "tablet",
                    trackingStart = 1_600_000_000_000,
                    lowStockThreshold = 7.0,
                    baselineQuantity = 28.0,
                    doseSize = 1.0,
                    unitStrengthMG = 50.0,
                    createdAt = 1_500_000_000_000,
                    manualEvents = listOf(
                        PiruManualEventData(
                            id = UUID.randomUUID().toString().uppercase(),
                            kind = "restock",
                            amount = 28.0,
                            date = 1_600_000_000_000,
                            note = "pharmacy",
                            setsBaseline = true,
                        ),
                    ),
                ),
            ),
            profile = PiruProfileData(
                disclosureTier = "full",
                bodyWeightKg = 72.5,
                weightSource = "measured",
                grapefruitLoggingEnabled = true,
                aldh2Deficient = false,
            ),
            notificationPreferences = PiruNotificationPreferencesData(
                quietHoursStartMinutes = 1_380,
                askAgainDefaultMinutes = emptyList(),
            ),
        )

        val text = FoundationJSON.write(nativeJsonOf(original))
        val decoded = DataExportImport.wireJson.decodeFromString(PiruFile.serializer(), text)
        decoded shouldBe original
    }

    @Test
    fun `a colour row round-trips as the p3 triple`() {
        val original = PiruColorData(
            substance = "Caffeine",
            p3 = P3Color(red = 0.898, green = 0.497, blue = 0.591),
        )
        val text = FoundationJSON.write(nativeJsonOf(PiruFile(piruExportVersion = 2, substanceColors = listOf(original))))
        text shouldContain "\"p3\" : [\n        0.898,\n        0.497,\n        0.591\n      ]"
        DataExportImport.wireJson.decodeFromString(PiruFile.serializer(), text).substanceColors shouldBe listOf(original)
    }

    @Test
    fun `a negative longitude keeps its sign rather than flattening`() {
        val text = FoundationJSON.write(
            nativeJsonOf(PiruFile(piruExportVersion = 2, orphanDoses = listOf(negativeLongitudeDose()))),
        )
        text shouldContain "\"longitude\" : -0.1278"
    }

    private fun negativeLongitudeDose(): PiruDoseData = PiruDoseData(
        substance = "Caffeine",
        amount = 1.0,
        unit = "mg",
        route = "oral",
        timestamp = 1,
        longitude = -0.1278,
    )

    // MARK: - classify

    @Test
    fun `classify names each of the four shapes`() {
        DataExportImport.classify("""{"piruExportVersion":2,"appVersion":"Piru 1.4 (212)"}""") shouldBe
            DataExportImport.FileShape.PiruNative("Piru 1.4 (212)")
        DataExportImport.classify("""{"piruExportVersion":1}""") shouldBe
            DataExportImport.FileShape.PiruNative(null)
        DataExportImport.classify("""{"experiences":[]}""") shouldBe DataExportImport.FileShape.PsyLog
        DataExportImport.classify("""{"doseEntries":[]}""") shouldBe DataExportImport.FileShape.Legacy
    }

    @Test
    fun `classify refuses an encrypted file by name, not by shape`() {
        val error = shouldThrow<DataExportImport.ImportFileException.Encrypted> {
            DataExportImport.classify("""{"sealed":"AAAA","kind":"passphrase"}""")
        }
        DataExportImport.importErrorMessage(error) shouldContain "encrypted Piru backup"
    }

    @Test
    fun `classify refuses a newer format and names the version and the writer`() {
        val error = shouldThrow<DataExportImport.ImportFileException.NewerFormat> {
            DataExportImport.classify("""{"piruExportVersion":9,"appVersion":"Piru 2.0 (900)"}""")
        }
        error.version shouldBe 9
        DataExportImport.importErrorMessage(error) shouldContain "export format 9"
        DataExportImport.importErrorMessage(error) shouldContain "written by Piru 2.0 (900)"
    }

    @Test
    fun `classify refuses empty, non-JSON and unrecognized input`() {
        shouldThrow<DataExportImport.ImportFileException.Empty> { DataExportImport.classify("   ") }
        shouldThrow<DataExportImport.ImportFileException.NotJson> { DataExportImport.classify("nonsense") }
        shouldThrow<DataExportImport.ImportFileException.Unrecognized> { DataExportImport.classify("""{"a":1}""") }
        // A version that is present but not a number is not a Piru export either.
        shouldThrow<DataExportImport.ImportFileException.Unrecognized> {
            DataExportImport.classify("""{"piruExportVersion":"two"}""")
        }
    }

    // MARK: - validate

    @Test
    fun `validate refuses a native file whose decoder trips, and names the field`() {
        val broken = """
            {"piruExportVersion":2,"appVersion":"Piru 1.4 (212)","sessions":[{"id":"not-a-uuid","startDate":"soon","doses":[]}]}
        """.trimIndent()
        val error = shouldThrow<DataExportImport.ImportFileException.MalformedNative> {
            DataExportImport.validate(broken)
        }
        // The message names the writer and the field, which is the whole point of
        // surfacing `MalformedNative` rather than letting the decoder's own
        // complaint through.
        DataExportImport.importErrorMessage(error) shouldContain "written by Piru 1.4 (212)"
        DataExportImport.importErrorMessage(error) shouldContain "sessions"
    }

    @Test
    fun `validate accepts a file with only its version and appVersion`() {
        // Every section but the version is optional on decode, which is what lets
        // a file from a build that had no favourites yet still import.
        DataExportImport.validate("""{"piruExportVersion":2,"appVersion":"Piru 1.4 (212)"}""")
    }

    // MARK: - Identity and naming

    @Test
    fun `the dedup key spells its five parts in the source's order`() {
        DataExportImport.doseDedupKey(
            substance = "Caffeine",
            timestamp = Instant.ofEpochMilli(1_700_000_000_000),
            amount = 100.0,
            unit = "MG",
            route = RouteOfAdministration.ORAL,
        ) shouldBe "caffeine|1700000000000|100.0|mg|oral"
    }

    @Test
    fun `the export filename carries the export's own time in the caller's zone`() {
        val now = Instant.parse("2026-09-28T15:30:45Z")
        DataExportImport.exportFilename(now, ZoneId.of("UTC")) shouldBe "Piru 2026-09-28T153045"
        DataExportImport.exportFilename(now, ZoneId.of("Europe/Berlin")) shouldBe "Piru 2026-09-28T173045"
    }

    @Test
    fun `an unknown route reads as other rather than failing the file`() {
        // Deliberately more forgiving than the Swift decoder, whose synthesized
        // `Codable` throws on an unrecognized `rawValue` and takes the whole file
        // with it. A route this build predates must cost the route, not the dose.
        DoseWriter.routeOf("oral") shouldBe RouteOfAdministration.ORAL
        DoseWriter.routeOf("insufflation") shouldBe RouteOfAdministration.INSUFFLATION
        DoseWriter.routeOf("intrarectal") shouldBe RouteOfAdministration.OTHER
        DoseWriter.routeOf("") shouldBe RouteOfAdministration.OTHER

        // And the route string itself survives the wire, so nothing is invented on
        // the way in either.
        val text = FoundationJSON.write(nativeJsonOf(sampleFile())).replace("\"oral\"", "\"intrarectal\"")
        DataExportImport.wireJson.decodeFromString(PiruFile.serializer(), text)
            .sessions.single().doses.first().route shouldBe "intrarectal"
    }

    // MARK: - The PsychonautWiki shape

    @Test
    fun `the PsyLog ingestion writes the nulls and the flag PW expects`() {
        val ingestion = DataExportImport.psyLogIngestion(
            DoseEntryEntity(
                substance = "Caffeine",
                amount = 100.0,
                unit = "mg",
                route = RouteOfAdministration.ORAL,
                timestamp = Date(1_700_000_000_000),
                notes = "note #sleep",
            ),
        )
        FoundationJSON.write(ingestion) shouldBe
            """
            {
              "administrationRoute" : "ORAL",
              "consumerName" : null,
              "creationDate" : 1700000000000,
              "customUnitId" : null,
              "dose" : 100,
              "endTime" : null,
              "estimatedDoseStandardDeviation" : null,
              "isDoseAnEstimate" : false,
              "isHiddenInTimeline" : false,
              "notes" : "note #sleep",
              "stomachFullness" : null,
              "substanceName" : "Caffeine",
              "time" : 1700000000000,
              "units" : "mg"
            }
            """.trimIndent()
    }

    @Test
    fun `a PsyLog file decodes a PsychonautWiki journal with sortDate null`() {
        // PW writes `sortDate: null` for an experience that never had one, and its
        // own importer falls back to the earliest ingestion. Both dates are also
        // given here to prove the explicit nulls decode rather than throw.
        val text = """
            {
              "exportSource" : "iOS Journal 15.0",
              "experiences" : [
                {
                  "title" : "An evening",
                  "isFavorite" : false,
                  "creationDate" : 1700000000000,
                  "sortDate" : null,
                  "text" : "notes",
                  "location" : null,
                  "ingestions" : [
                    { "substanceName" : "Caffeine", "dose" : 100, "time" : 1700000000000,
                      "administrationRoute" : "ORAL", "notes" : "", "units" : "mg" }
                  ],
                  "timedNotes" : [],
                  "ratings" : []
                }
              ],
              "substanceCompanions" : [ { "color" : "BLUE", "substanceName" : "Caffeine" } ],
              "customUnits" : [],
              "customSubstances" : []
            }
        """.trimIndent()
        val file = DataExportImport.wireJson.decodeFromString(PsyLogFile.serializer(), text)
        file.experiences.single().sortDate shouldBe null
        file.experiences.single().ingestions.single().dose shouldBe 100.0
        file.substanceCompanions.single().color shouldBe "BLUE"
    }

    @Test
    fun `a PW file's string-array customSubstances placeholder reads as no customs`() {
        // A real PsychonautWiki file writes `customSubstances: []` as a string
        // array. Upstream decodes that with `try?`, so it reads as none rather
        // than failing the import.
        val text = """{"experiences":[],"customSubstances":["not an object"]}"""
        val file = DataExportImport.wireJson.decodeFromString(PsyLogFile.serializer(), text)
        file.customSubstances.size shouldBe 1
        // The element survives decode as an opaque element; the importer is what
        // decides it cannot become a custom substance.
        file.customSubstances.single().shouldBeInstanceOf<JsonPrimitive>()
    }

    // MARK: - The legacy shape

    @Test
    fun `a legacy file decodes an ISO-8601 timestamp`() {
        val text = """
            {
              "doseEntries" : [
                { "substance" : "Caffeine", "amount" : 100, "unit" : "mg", "route" : "oral",
                  "timestamp" : "2023-05-01T12:00:00Z", "tags" : ["morning"] }
              ],
              "dailyDoseItems" : [],
              "substanceColors" : [ { "substance" : "Caffeine", "hexColor" : "34C759" } ]
            }
        """.trimIndent()
        val file = LegacyImport.decode(text)
        file.doseEntries.single().timestamp shouldBe "2023-05-01T12:00:00Z"
        file.substanceColors.single().hexColor shouldBe "34C759"
    }

    @Test
    fun `legacy requires its three sections, as the Swift struct does`() {
        shouldThrow<Exception> {
            LegacyImport.decode("""{"doseEntries":[]}""")
        }
    }

    // MARK: - Note tags

    @Test
    fun `tags are extracted lowercased, in reading order, once each`() {
        NoteTags.extract("Slept badly #Sleep but #headache then #Headache") shouldBe
            listOf("sleep", "headache")
        NoteTags.extract("no tags here") shouldBe emptyList()
    }

    // MARK: - Fixtures

    private fun nativeJsonOf(file: PiruFile): JsonObject =
        DataExportImport.wireJson.encodeToJsonElement(PiruFile.serializer(), file) as JsonObject

    private fun sampleFile(): PiruFile = PiruFile(
        piruExportVersion = DataExportImport.PIRU_EXPORT_VERSION,
        appVersion = "Piru 1.4 (212)",
        exportedAt = 1_700_000_000_000,
        sessions = listOf(
            PiruSessionData(
                id = SESSION_ID.toString().uppercase(),
                startDate = 1_699_000_000_000,
                title = "An evening",
                doses = listOf(
                    PiruDoseData(
                        id = DOSE_ID.toString().uppercase(),
                        substance = "Caffeine",
                        // One integral and one fractional, so the number rule is
                        // pinned by the same fixture.
                        amount = 100.0,
                        unit = "mg",
                        route = "oral",
                        timestamp = 1_699_000_000_000,
                        tags = emptyList(),
                    ),
                    PiruDoseData(
                        id = UUID.fromString("11111111-2222-3333-4444-555555555555").toString().uppercase(),
                        substance = "Ethanol",
                        amount = 0.5,
                        unit = "g",
                        route = "oral",
                        timestamp = 1_699_000_060_000,
                        tags = listOf("drink"),
                    ),
                ),
                notes = emptyList(),
                checkInOffered = false,
            ),
        ),
        substanceColors = emptyList(),
        favorites = emptyList(),
        customSubstances = emptyList(),
    )

    private companion object {
        val SESSION_ID: UUID = UUID.fromString("0f7a1e2b-3c4d-4e5f-8a9b-0c1d2e3f4a5b")
        val DOSE_ID: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        /**
         * The pinned file.
         *
         * Written out rather than generated so that any change to the format —
         * a renamed field, a `null` that starts being written, a number that
         * starts carrying `.0` — fails here and has to be argued for.
         */
        val SAMPLE_FILE_JSON: String = """
            {
              "appVersion" : "Piru 1.4 (212)",
              "customSubstances" : [

              ],
              "dailyDoseItems" : [

              ],
              "exportedAt" : 1700000000000,
              "favorites" : [

              ],
              "orphanDoses" : [

              ],
              "piruExportVersion" : 2,
              "sessions" : [
                {
                  "checkInOffered" : false,
                  "doses" : [
                    {
                      "amount" : 100,
                      "id" : "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE",
                      "isBackgroundMed" : false,
                      "route" : "oral",
                      "substance" : "Caffeine",
                      "tags" : [

                      ],
                      "timestamp" : 1699000000000,
                      "unit" : "mg"
                    },
                    {
                      "amount" : 0.5,
                      "id" : "11111111-2222-3333-4444-555555555555",
                      "isBackgroundMed" : false,
                      "route" : "oral",
                      "substance" : "Ethanol",
                      "tags" : [
                        "drink"
                      ],
                      "timestamp" : 1699000060000,
                      "unit" : "g"
                    }
                  ],
                  "id" : "0F7A1E2B-3C4D-4E5F-8A9B-0C1D2E3F4A5B",
                  "notes" : [

                  ],
                  "startDate" : 1699000000000,
                  "title" : "An evening"
                }
              ],
              "substanceColors" : [

              ]
            }
        """.trimIndent()
    }
}
