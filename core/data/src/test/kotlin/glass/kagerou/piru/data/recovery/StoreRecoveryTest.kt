package glass.kagerou.piru.data.recovery

import glass.kagerou.piru.data.export.DataExportImport
import glass.kagerou.piru.data.export.FoundationJSON
import glass.kagerou.piru.data.export.PiruDoseData
import glass.kagerou.piru.data.export.PiruFile
import glass.kagerou.piru.data.export.PiruFavoriteData
import glass.kagerou.piru.data.export.PiruSessionData
import glass.kagerou.piru.data.export.PiruColorData
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The recovery snapshots, on a real directory.
 *
 * The file layer takes a [File] rather than a `Context` precisely so this is a
 * JVM spec: everything here is name parsing, listing order and the two rules the
 * source states as rules — a snapshot is written before anything destructive, and
 * a snapshot the *user* caused is never resurrected by a machine.
 *
 * The store-level half (does a snapshot restore the journal?) needs Room and
 * lives in the instrumentation suite.
 */
class StoreRecoveryTest {

    @TempDir
    lateinit var temp: Path

    private val directory: File get() = temp.resolve(StoreRecovery.DIRECTORY_NAME).toFile()

    private fun recovery() = StoreRecovery(directory)

    // MARK: - Writing

    @Test
    fun `a snapshot lands in a file whose name says why and when`() {
        val at = Instant.parse("2026-09-28T12:00:00Z")
        val file = recovery().snapshotStore("predelete", journalJson(doses = 2), at)

        file shouldNotBe null
        file!!.name shouldBe "piru-backup.predelete-${at.epochSecond}.json"
        file.readText() shouldBe journalJson(doses = 2)
        // The name is the index: both halves have to parse back out of it.
        StoreRecovery.sidecarReason(file.name) shouldBe "predelete"
        StoreRecovery.sidecarTimestamp(file.name) shouldBe at
    }

    @Test
    fun `a snapshot creates its directory rather than failing on a fresh install`() {
        directory.exists() shouldBe false
        recovery().snapshotStore("predelete", journalJson(doses = 1), Instant.EPOCH) shouldNotBe null
        directory.isDirectory shouldBe true
    }

    @Test
    fun `a snapshot that cannot be written reports null rather than throwing`() {
        // The caller is mid-way through a destructive action whose snapshot is a
        // safety net; a failure has to come back as a value the caller can refuse
        // to proceed on, not as an exception from inside a file write.
        val blocked = File(temp.toFile(), "blocked").also { it.writeText("not a directory") }
        StoreRecovery(File(blocked, StoreRecovery.DIRECTORY_NAME))
            .snapshotStore("predelete", "{}", Instant.EPOCH) shouldBe null
    }

    // MARK: - The rule that matters

    @Test
    fun `a snapshot the user caused is marked intentional, a quarantine is not`() {
        StoreRecovery.INTENTIONAL_REASONS shouldBe setOf("predelete", "prerestore", "prepsid")
        val at = Instant.parse("2026-09-28T12:00:00Z")

        copyNamed("predelete", at).isIntentional shouldBe true
        copyNamed("prerestore", at).isIntentional shouldBe true
        copyNamed("prepsid", at).isIntentional shouldBe true

        // Written by the restore flow itself, and deliberately NOT in the set —
        // it is a file-level safety copy, not a snapshot the user asked for.
        copyNamed(StoreRecovery.BEFORE_MANUAL_RESTORE, at).isIntentional shouldBe false
        copyNamed(StoreRecovery.EMPTY_BEFORE_RECOVERY, at).isIntentional shouldBe false
        copyNamed("corrupt", at).isIntentional shouldBe false
    }

    /**
     * A descriptor built without touching disk.
     *
     * The classification is a property of the *name*, so a fixture that wrote six
     * files to test six names would be testing the filesystem instead.
     */
    private fun copyNamed(reason: String, at: Instant): StoreRecovery.RecoverableCopy =
        StoreRecovery.RecoverableCopy(
            file = File(directory, "piru-backup.$reason-${at.epochSecond}.json"),
            reason = reason,
            rowCount = 1,
            timestamp = at,
            bytes = 1,
        )

    // MARK: - Listing

    @Test
    fun `recoverable stores list newest first and carry their own row count`() {
        val store = recovery()
        store.snapshotStore("predelete", journalJson(doses = 1), Instant.ofEpochSecond(1_000))
        store.snapshotStore("corrupt", journalJson(doses = 3, colors = 2), Instant.ofEpochSecond(2_000))

        val listed = store.recoverableStores()
        listed.size shouldBe 2
        // Newest first, which is what the screen shows.
        listed.map { it.reason } shouldContainExactly listOf("corrupt", "predelete")
        // Three doses, one favourite and two colours for the first; one dose and
        // one favourite for the second — the four counted sections.
        listed.map { it.rowCount } shouldContainExactly listOf(6, 2)
        listed[0].timestamp shouldBe Instant.ofEpochSecond(2_000)
        listed[0].bytes shouldBe listed[0].file.length()
    }

    @Test
    fun `a zero-byte copy is not offered`() {
        // A failed write is evidence, not a restore candidate: offering it would
        // only reproduce the failure.
        val store = recovery()
        store.snapshotStore("predelete", journalJson(doses = 1), Instant.ofEpochSecond(1_000))
        File(directory, "piru-backup.corrupt-2000.json").writeText("")

        store.recoverableStores().map { it.reason } shouldContainExactly listOf("predelete")
    }

    @Test
    fun `an unreadable copy is still listed, with a count of minus one`() {
        val store = recovery()
        directory.mkdirs()
        File(directory, "piru-backup.corrupt-1500.json").writeText("{ this is not a Piru file }")

        val copy = store.recoverableStores().single()
        copy.rowCount shouldBe -1
        copy.reason shouldBe "corrupt"
    }

    @Test
    fun `the copy's text is what a restore hands to the importer`() {
        val store = recovery()
        store.snapshotStore("predelete", journalJson(doses = 2), Instant.EPOCH)
        store.read(store.recoverableStores().single()) shouldBe journalJson(doses = 2)
    }

    // MARK: - Deleting

    @Test
    fun `deleting the copies takes the intentional ones too`() {
        // Delete Everything must not leave a snapshot that could restore what was
        // just erased, which is why this is not a tidy-up the user opts into.
        val store = recovery()
        store.snapshotStore("predelete", journalJson(doses = 1), Instant.ofEpochSecond(1))
        store.snapshotStore("corrupt", journalJson(doses = 1), Instant.ofEpochSecond(2))
        store.recoverableStores().size shouldBe 2

        store.deleteRecoveryCopies().isSuccess shouldBe true
        store.recoverableStores() shouldBe emptyList()
    }

    // MARK: - The filename, which is the index

    @Test
    fun `a reason is parsed from the name and a nameless one falls back`() {
        StoreRecovery.sidecarReason("piru-backup.predelete-1789732800.json") shouldBe "predelete"
        StoreRecovery.sidecarReason("piru-backup.before-manual-restore-1.json") shouldBe "before-manual-restore"
        // Upstream's one oddity, kept: no trailing `-` means the whole tag is the
        // reason rather than nothing.
        StoreRecovery.sidecarReason("piru-backup.something.json") shouldBe "something"
        StoreRecovery.sidecarReason("unrelated.json") shouldBe null
    }

    @Test
    fun `a timestamp is parsed from the name, in seconds`() {
        StoreRecovery.sidecarTimestamp("piru-backup.predelete-1789732800.json") shouldBe
            Instant.ofEpochSecond(1_789_732_800)
        StoreRecovery.sidecarTimestamp("piru-backup.something.json") shouldBe null
        StoreRecovery.sidecarTimestamp("piru-backup.predelete-notanumber.json") shouldBe null
    }

    @Test
    fun `a copy knows how to name itself to the user`() {
        StoreRecovery.reasonTitle("corrupt") shouldBe "Auto-recovered Data"
        StoreRecovery.reasonTitle("predelete") shouldBe "Before You Deleted Everything"
        StoreRecovery.reasonTitle("prerestore") shouldBe "Before a Restore"
        StoreRecovery.reasonTitle(StoreRecovery.BEFORE_MANUAL_RESTORE) shouldBe "Before a Restore"
        StoreRecovery.reasonTitle("whatever") shouldBe "Saved Copy"
    }

    // MARK: - Fixture

    /**
     * A snapshot's payload, with the number of counted rows asked for.
     *
     * Built through the real encoder so the row count is measured against the
     * same wire shape a snapshot is written in — a fixture assembled by hand
     * could agree with the counter while disagreeing with the exporter.
     */
    private fun journalJson(doses: Int, colors: Int = 0): String {
        val file = PiruFile(
            piruExportVersion = DataExportImport.PIRU_EXPORT_VERSION,
            appVersion = "Piru 0.1.0 (1)",
            exportedAt = 0,
            sessions = listOf(
                PiruSessionData(
                    id = "0F7A1E2B-3C4D-4E5F-8A9B-0C1D2E3F4A5B",
                    startDate = 0,
                    doses = (1..doses).map { index ->
                        PiruDoseData(
                            substance = "Caffeine",
                            amount = index.toDouble(),
                            unit = "mg",
                            route = "oral",
                            timestamp = index.toLong(),
                        )
                    },
                ),
            ),
            favorites = listOf(PiruFavoriteData(substance = "Caffeine")),
            substanceColors = (1..colors).map { PiruColorData(substance = "Caffeine $it") },
        )
        return FoundationJSON.write(
            DataExportImport.wireJson.encodeToJsonElement(PiruFile.serializer(), file),
        )
    }
}
