package glass.kagerou.piru.data.backup

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Reading, writing and opening a backup **file** — no database, so this runs as a plain JVM test.
 *
 * ## The claim most of this file is about
 * A mistakenly-selected file must produce a **message**, not an out-of-memory crash. The cap is checked against the
 * file's own length *before* reading, and the test proves the pre-check is what fires by using a **sparse** file: it
 * reports 300 MB while occupying almost nothing on disk, so an implementation that read first would pull 300 MB into
 * the heap — failing loudly rather than passing slowly, which is what makes the sparse trick safe to rely on. It is
 * the only way to test a 256 MB bound in a suite.
 *
 * The restore strategies need a `PiruDatabase` and so live in `:app`'s Robolectric harness, beside the other database
 * tests. The split is by dependency, not by subject.
 */
class BackupFileIoTest {

    private val key = ByteArray(32).also(SecureRandom()::nextBytes)
    private val passphrase = "correct horse battery staple"

    @TempDir
    lateinit var directory: File

    private fun file(name: String) = File(directory, name)

    // MARK: - The size bound

    /**
     * A file larger than the cap is refused **before** it is read.
     *
     * The sparse file is the point: 300 MB is reported and nothing like that is stored. If the length check did not
     * run first this would try to hold 300 MB, so the elapsed-time assertion is a real check on the ordering rather
     * than decoration.
     */
    @Test
    fun `a file over the cap is refused without being read`() {
        val huge = file("huge.${BackupCrypto.FILE_EXTENSION}")
        RandomAccessFile(huge, "rw").use { it.setLength(BackupFiles.MAX_BACKUP_BYTES + 1) }

        val started = System.nanoTime()
        val failure = runCatching { BackupFiles.readBounded(huge) }
            .exceptionOrNull()
            .shouldBeInstanceOf<BackupFiles.BackupFileException>()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        println("BACKUPPROBE refused ${huge.length()} bytes in ${elapsedMs}ms")

        failure.failure shouldBe BackupFiles.Failure.FILE_TOO_LARGE
        (elapsedMs < 2_000) shouldBe true
    }

    /** Exactly at the cap is **not** over it: the comparison is `>`, and an off-by-one here refuses valid files. */
    @Test
    fun `a file exactly at the cap is read`() {
        val atCap = file("at-cap.${BackupCrypto.FILE_EXTENSION}")
        RandomAccessFile(atCap, "rw").use { it.setLength(BackupFiles.MAX_BACKUP_BYTES) }
        BackupFiles.readBounded(atCap).size.toLong() shouldBe BackupFiles.MAX_BACKUP_BYTES
    }

    /** A file that is not there says so specifically, rather than "unreadable". */
    @Test
    fun `a missing file reports that no backup was found`() {
        val failure = runCatching { BackupFiles.readBounded(file("absent.piruenc")) }
            .exceptionOrNull()
            .shouldBeInstanceOf<BackupFiles.BackupFileException>()
        failure.failure shouldBe BackupFiles.Failure.NO_BACKUP_FOUND
    }

    // MARK: - Envelope kinds

    /** A passphrase export opens with its passphrase and yields exactly what was exported. */
    @Test
    fun `a passphrase backup round-trips`() {
        val plaintext = """{"doses":[],"note":"ünïcode and emoji 🜛"}""".toByteArray()
        val target = file("with-passphrase.piruenc")
        BackupFiles.exportEncrypted(target, plaintext, passphrase, "0.6.1")

        target.exists() shouldBe true
        BackupFiles.open(target, passphrase, null).toString(Charsets.UTF_8) shouldBe plaintext.toString(Charsets.UTF_8)
    }

    /** A device-key export opens with its key, and a different key is undecryptable rather than a crash. */
    @Test
    fun `a device-key backup round-trips`() {
        val plaintext = """{"doses":[]}""".toByteArray()
        val target = file("with-key.piruenc")
        target.writeBytes(BackupCrypto.encryptWithDeviceKey(plaintext, key, "0.6.1"))

        BackupFiles.open(target, null, key).toString(Charsets.UTF_8) shouldBe plaintext.toString(Charsets.UTF_8)

        val wrong = runCatching { BackupFiles.open(target, null, ByteArray(32)) }
            .exceptionOrNull()
            .shouldBeInstanceOf<BackupFiles.BackupFileException>()
        wrong.failure shouldBe BackupFiles.Failure.UNDECRYPTABLE
    }

    /**
     * Asking for the wrong **kind** is reported as the wrong kind, in both directions.
     *
     * It is the caller's mistake rather than a problem with the file, and telling someone their backup is damaged when
     * they simply asked for the wrong one is the worse message — only one of the two suggests the file is lost.
     */
    @Test
    fun `asking for the wrong kind is reported as such`() {
        val plaintext = """{"doses":[]}""".toByteArray()

        val passphraseFile = file("pass.piruenc")
        passphraseFile.writeBytes(BackupCrypto.encrypt(plaintext, passphrase, "0.6.1"))
        val asDeviceKey = runCatching { BackupFiles.open(passphraseFile, null, key) }
            .exceptionOrNull()
            .shouldBeInstanceOf<BackupFiles.BackupFileException>()
        asDeviceKey.failure shouldBe BackupFiles.Failure.WRONG_KIND

        val deviceFile = file("dev.piruenc")
        deviceFile.writeBytes(BackupCrypto.encryptWithDeviceKey(plaintext, key, "0.6.1"))
        val asPassphrase = runCatching { BackupFiles.open(deviceFile, passphrase, null) }
            .exceptionOrNull()
            .shouldBeInstanceOf<BackupFiles.BackupFileException>()
        asPassphrase.failure shouldBe BackupFiles.Failure.WRONG_KIND
    }

    /** A wrong passphrase is undecryptable, and the file on disk is left exactly as it was. */
    @Test
    fun `a wrong passphrase is undecryptable and changes nothing`() {
        val target = file("wrong-pass.piruenc")
        BackupFiles.exportEncrypted(target, """{"doses":[]}""".toByteArray(), passphrase, "0.6.1")
        val before = target.readBytes()

        val failure = runCatching { BackupFiles.open(target, "not the passphrase", null) }
            .exceptionOrNull()
            .shouldBeInstanceOf<BackupFiles.BackupFileException>()
        failure.failure shouldBe BackupFiles.Failure.UNDECRYPTABLE
        target.readBytes().contentEquals(before) shouldBe true
    }

    /** Garbage that is not an envelope at all is undecryptable rather than a parse crash escaping to the caller. */
    @Test
    fun `a file that is not an envelope is undecryptable`() {
        val target = file("garbage.piruenc")
        target.writeBytes("this is a holiday photo, not a backup".toByteArray())

        val failure = runCatching { BackupFiles.open(target, passphrase, null) }
            .exceptionOrNull()
            .shouldBeInstanceOf<BackupFiles.BackupFileException>()
        // Either the reader cannot parse it or it cannot open it; both are "this is not a backup I can use", and both
        // are `UNDECRYPTABLE` at this level rather than a raw crypto exception.
        failure.failure shouldBe BackupFiles.Failure.UNDECRYPTABLE
    }

    /**
     * An **empty** plaintext is still a valid envelope that opens.
     *
     * The boundary of the round trip rather than a size case: a backup taken before anything was logged has no rows,
     * and the export still has to produce a file the restore path can read. A `0`-byte file would be the shape that
     * broke it, and it does not.
     *
     * An envelope genuinely over the cap cannot be built from a small plaintext, and the crypto layer refuses one at a
     * lower bound anyway — so the *export* refusal is not asserted here rather than being asserted with an empty body
     * that would prove nothing.
     */
    @Test
    fun `an empty plaintext round-trips`() {
        val target = file("empty.piruenc")
        BackupFiles.exportEncrypted(target, ByteArray(0), passphrase, "0.6.1")

        (target.length() in 1..BackupFiles.MAX_BACKUP_BYTES) shouldBe true
        BackupFiles.open(target, passphrase, null).size shouldBe 0
    }

    // MARK: - The status line

    /** The status cases carry their own data, so a status and its message cannot disagree. */
    @Test
    fun `the status cases carry their own data`() {
        (BackupFiles.Status.Succeeded(1_700_000_000_000L) ==
            BackupFiles.Status.Succeeded(1_700_000_000_000L)) shouldBe true
        (BackupFiles.Status.Succeeded(1L) == BackupFiles.Status.Failed("x")) shouldBe false
        BackupFiles.Status.Failed("disk full").message shouldBe "disk full"
        (BackupFiles.Status.Idle == BackupFiles.Status.Running) shouldBe false
    }

    /** The strategy names its two cases the way the import's rules describe them. */
    @Test
    fun `the restore strategies are the two the import supports`() {
        RestoreStrategy.entries.map { it.name } shouldBe listOf("MERGE", "REPLACE")
    }
}
