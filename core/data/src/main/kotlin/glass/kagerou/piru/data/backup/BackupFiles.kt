package glass.kagerou.piru.data.backup

import glass.kagerou.piru.data.PiruDatabase
import glass.kagerou.piru.data.export.DataExportImport
import java.io.File
import java.io.IOException

/**
 * Making and restoring an encrypted backup **file**.
 *
 * The parts of `BackupManager` that do not depend on iCloud: the status line, the two restore strategies, the size
 * bound and the failure messages. The automatic iCloud sync is deliberately absent — it is excluded from this work —
 * and so is the device key's **storage**, which Android would put in the Keystore and which the caller owns.
 *
 * ## The size bound, and why it is not merely defensive
 * Upstream caps a backup at 256 MB. Without a cap, reading a mistakenly-selected file reads the whole thing into
 * memory first, so the failure for picking a 4 GB video is an out-of-memory crash rather than a message. The cap is
 * checked against the **file's own length before reading**, and then again against the bytes read — a file can grow
 * between the two calls, and the second check is the one that matters.
 *
 * ## Why the strategies are two code paths and not a flag
 * See [RestoreStrategy]. A merge that goes wrong duplicates data; a replace that goes wrong destroys it. The wipe
 * **precedes** the import in the replace path, so a file that turns out to be unreadable leaves the user with their
 * old data rather than with nothing.
 */
object BackupFiles {

    /**
     * The largest file this will read, matching iOS's cap.
     *
     * The same number as [BackupCrypto.MAX_ENVELOPE_BYTES], and stated separately because they bound different things:
     * that one bounds what the *format* can describe, this one bounds what this code will pull into memory.
     */
    const val MAX_BACKUP_BYTES: Long = 256L * 1024L * 1024L

    /** Where a backup sits while it is being made or restored. */
    /**
     * Where a backup stands, for the settings screen's status line.
     *
     * A sealed class rather than an enum, because two of the four cases carry something: an enum would make them carry
     * it out of band — a `lastError` field beside a `FAILED` constant — and the two could then disagree with each
     * other.
     */
    sealed class Status {
        data object Idle : Status()
        data object Running : Status()

        /** Finished at this epoch-millisecond instant. */
        data class Succeeded(val atEpochMillis: Long) : Status()

        /** Failed, with the message to show. */
        data class Failed(val message: String) : Status()
    }

    /**
     * Why an operation could not proceed, as a case rather than a sentence.
     *
     * Cases rather than the message itself, because the caller has to decide what to *offer*: a file that is too large
     * is a different conversation from a passphrase that is wrong, and one of them is retryable.
     */
    enum class Failure {
        /** The file is larger than [MAX_BACKUP_BYTES], or grew past it while being read. */
        FILE_TOO_LARGE,

        /** The file could not be read at all. */
        UNREADABLE,

        /** No backup exists at the location asked for. */
        NO_BACKUP_FOUND,

        /** The envelope is a kind this operation cannot open. */
        WRONG_KIND,

        /** The file is damaged, or was sealed with a key this device does not have. */
        UNDECRYPTABLE,
    }

    /** Raised with a [Failure] rather than a string, so a caller can react to the case. */
    class BackupFileException(val failure: Failure, cause: Throwable? = null) :
        Exception(failure.name, cause)

    /**
     * Reads a backup file, refusing anything over [MAX_BACKUP_BYTES].
     *
     * The length is checked **twice** and both checks earn their place: before the read, so an obviously oversized file
     * never gets pulled into memory, and after, because a file can be replaced between the two and the length that
     * matters is the one actually read.
     *
     * A missing file is [Failure.NO_BACKUP_FOUND] rather than [Failure.UNREADABLE]: "there is no backup here" is a
     * different thing to tell someone than "the backup is unreadable", and only one of them suggests they go looking
     * for the file.
     */
    fun readBounded(file: File): ByteArray {
        if (!file.exists()) throw BackupFileException(Failure.NO_BACKUP_FOUND)
        if (file.length() > MAX_BACKUP_BYTES) {
            throw BackupFileException(Failure.FILE_TOO_LARGE)
        }
        val bytes = try {
            file.readBytes()
        } catch (error: IOException) {
            throw BackupFileException(Failure.UNREADABLE, error)
        } catch (error: OutOfMemoryError) {
            // The file passed the length check and was still too big to hold — a race with whatever replaced it, or a
            // heap far smaller than the cap. Reported as the size failure, because that is the actionable fact.
            throw BackupFileException(Failure.FILE_TOO_LARGE, error)
        }
        if (bytes.size.toLong() > MAX_BACKUP_BYTES) throw BackupFileException(Failure.FILE_TOO_LARGE)
        return bytes
    }

    /**
     * Writes an encrypted backup to [file], sealed under [passphrase].
     *
     * The bytes are written all at once rather than streamed: the envelope has to be built in memory anyway to seal it,
     * so a streaming write would only move the peak. The **size** is checked on the way out as well, so this cannot
     * produce a file the restore path would refuse.
     */
    fun exportEncrypted(
        file: File,
        plaintext: ByteArray,
        passphrase: String,
        appVersion: String,
        now: java.time.Instant = java.time.Instant.now(),
    ): File {
        val envelope = BackupCrypto.encrypt(plaintext, passphrase, appVersion, now)
        if (envelope.size.toLong() > MAX_BACKUP_BYTES) throw BackupFileException(Failure.FILE_TOO_LARGE)
        try {
            file.parentFile?.mkdirs()
            file.writeBytes(envelope)
        } catch (error: IOException) {
            throw BackupFileException(Failure.UNREADABLE, error)
        }
        return file
    }

    /**
     * Opens a backup file, either with a passphrase or with a device key.
     *
     * `passphrase == null` means "this is a device-key envelope"; a non-null one means "this is a passphrase envelope".
     * The two are not interchangeable, and asking for the wrong one reports [Failure.WRONG_KIND] rather than the
     * crypto layer's more specific error, because at *this* level the caller's mistake is the kind and not the secret.
     */
    fun open(file: File, passphrase: String?, deviceKey: ByteArray?): ByteArray {
        val bytes = readBounded(file)
        val inspection = try {
            BackupCrypto.inspect(bytes)
        } catch (error: BackupCrypto.BackupException) {
            throw BackupFileException(Failure.UNDECRYPTABLE, error)
        }
        val wantDeviceKey = passphrase == null
        val isDeviceKey = inspection is BackupCrypto.Inspection.DeviceKey
        if (wantDeviceKey != isDeviceKey) throw BackupFileException(Failure.WRONG_KIND)
        if (!wantDeviceKey && deviceKey != null) throw BackupFileException(Failure.WRONG_KIND)

        return try {
            if (isDeviceKey) {
                val key = deviceKey ?: throw BackupFileException(Failure.WRONG_KIND)
                BackupCrypto.decryptWithDeviceKey(bytes, key)
            } else {
                BackupCrypto.decrypt(bytes, passphrase!!)
            }
        } catch (error: BackupCrypto.BackupException) {
            throw BackupFileException(Failure.UNDECRYPTABLE, error)
        }
    }

    /**
     * Restores [file] into [database], under [strategy].
     *
     * ## The order is the whole safety argument
     * A replace is a wipe followed by the same import a merge performs — that is exactly what makes it a replace,
     * since this port's import has no mode flag. The passphrase is therefore verified by [open] **before** anything is
     * deleted: a file that turns out to be unreadable, or a passphrase with a typo in it, must not cost the user their
     * journal. Open, then wipe, then import, in that order.
     *
     * [settings] is passed through because this module has no `Context` and the preferences live in a
     * `SharedPreferences` file whose writer is in the app; a caller restoring a backup wants the settings section
     * applied, so it supplies it.
     */
    suspend fun restore(
        file: File,
        database: PiruDatabase,
        strategy: RestoreStrategy,
        passphrase: String?,
        deviceKey: ByteArray?,
        appVersion: String,
        catalog: glass.kagerou.piru.engine.SubstanceCatalog? = null,
        now: java.time.Instant = java.time.Instant.now(),
    ): DataExportImport.ImportReport {
        // Opened first, deliberately: see this function's own note. Nothing is deleted until the bytes have decrypted.
        val plaintext = open(file, passphrase, deviceKey)

        when (strategy) {
            RestoreStrategy.MERGE -> Unit
            RestoreStrategy.REPLACE -> DataExportImport.deleteAll(database)
        }

        return DataExportImport.importJSON(
            text = plaintext.toString(Charsets.UTF_8),
            db = database,
            catalog = catalog,
        )
    }
}
