package glass.kagerou.piru.data.recovery

import android.content.Context
import glass.kagerou.piru.data.export.DataExportImport
import glass.kagerou.piru.data.export.PiruFile
import java.io.File
import java.time.Instant

/**
 * The copies a destructive action leaves behind.
 *
 * Ported from `Piru/Data/Persistence/StoreRecovery.swift`.
 *
 * ## What is the same, and what could not be
 * Two things are load-bearing and both are kept exactly:
 *
 * 1. **A snapshot is taken before anything destructive.** Delete Everything and a
 *    manual restore both write one first, and the comment for that in the source
 *    — "a copy (not a move)… so it's always recoverable" — is the reason this
 *    exists at all.
 * 2. **[INTENTIONAL_REASONS] is never auto-restored.** A snapshot the *user*
 *    caused must not be resurrected by a later launch, because doing so would
 *    undo a delete they asked for. `predelete`, `prerestore` and `prepsid` are
 *    the three.
 *
 * What could not be ported, and is not pretended:
 *
 * - **Auto-recovery has no counterpart here.** Upstream's whole
 *   `prepareCanonicalStore` / `richestRecoverableStore` apparatus exists because
 *   the iOS app shares its store with widget extensions through an App Group, and
 *   a widget's timeline refresh can create an empty store before the app's first
 *   launch. Android has no extensions and no shared container, so there is no
 *   race to lose and nothing to auto-recover. The listing below is manual only —
 *   the user picks a copy and restores it.
 * - **A snapshot is a JSON export, not a store file.** Upstream copies
 *   `default.store`, `-wal` and `-shm`. There is no equivalent here: Room's
 *   database is a live handle the running process holds open, so copying the file
 *   out from under it is exactly the sort of thing that silently loses a WAL.
 *   Writing the format the app already knows how to read back — the plaintext
 *   Piru export — gives the same guarantee with no file-level surgery. The cost
 *   is that a snapshot carries only what the export carries; see
 *   [DataExportImport] for the four sections this build has no table for.
 * - **`JournalResetGeneration` has no counterpart.** It filters candidates written
 *   before a journal reset; the store-swap it guards does not exist here.
 *
 * ## Where the copies live
 * One directory, `filesDir/recovery`, so they are as private as the database
 * itself and are removed with the app. Deleting one is a file delete; there is no
 * `-wal`/`-shm` triad to keep in step.
 */
class StoreRecovery(private val directory: File) {

    /** A copy on disk, as the Data & Storage screen lists it. */
    data class RecoverableCopy(
        val file: File,
        /** Why it was set aside: `corrupt`, `empty-before-recovery`, `predelete`, … */
        val reason: String,
        /** User-data row count, or `-1` when the file cannot be read. */
        val rowCount: Int,
        /** When it was set aside, parsed from the filename. `null` when it carries none. */
        val timestamp: Instant?,
        /** On-disk size in bytes. */
        val bytes: Long,
    ) {
        /**
         * A deliberate user snapshot (Delete Everything, pre-restore) rather than
         * an automatic quarantine — so it is listed for manual restore and never
         * resurrected by itself.
         */
        val isIntentional: Boolean get() = reason in INTENTIONAL_REASONS
    }

    /**
     * Write [json] as a new snapshot, returning the file it landed in, or null
     * when the write failed.
     *
     * A failure is returned rather than thrown: the caller is mid-way through a
     * destructive action whose snapshot is a safety net, and a snapshot that
     * could not be written must abort that action — which is the caller's
     * decision to make, not this file's to force with an exception.
     */
    fun snapshotStore(reason: String, json: String, now: Instant = Instant.now()): File? {
        if (!directory.exists() && !directory.mkdirs()) return null
        val file = File(directory, "${SNAPSHOT_BASE}.$reason-${now.epochSecond}$SNAPSHOT_SUFFIX")
        return runCatching {
            file.writeText(json)
            file
        }.getOrNull()
    }

    /**
     * Every copy on disk that could be restored, newest first.
     *
     * Unlike auto-recovery this lists intentional snapshots too — the user is
     * allowed to roll back a delete they regret, and this is where they do it.
     * A file that cannot be read is still listed: the bytes are the user's data,
     * and "unreadable" is more useful than "absent".
     */
    fun recoverableStores(): List<RecoverableCopy> = (directory.listFiles() ?: emptyArray())
        .filter { it.isFile && it.name.endsWith(SNAPSHOT_SUFFIX) }
        .map { file ->
            RecoverableCopy(
                file = file,
                reason = sidecarReason(file.name) ?: FALLBACK_REASON,
                rowCount = rowCountOf(file),
                timestamp = sidecarTimestamp(file.name),
                bytes = file.length(),
            )
        }
        // A zero-byte file is not a copy to surface; it is evidence of a failed
        // write, and offering to restore from it would only produce the same
        // failure again.
        .filter { it.bytes > 0 }
        .sortedByDescending { it.timestamp ?: Instant.EPOCH }

    /** The snapshot's text, or null when it cannot be read. */
    fun read(copy: RecoverableCopy): String? = runCatching { copy.file.readText() }.getOrNull()

    /**
     * Remove every copy, intentional ones included.
     *
     * Upstream is explicit that Delete Everything does this — "so they cannot
     * restore erased records" — and it is why the call sits *inside* the delete
     * flow rather than being offered as a separate tidy-up.
     */
    fun deleteRecoveryCopies(): Result<Unit> = runCatching {
        (directory.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(SNAPSHOT_SUFFIX) }
            .forEach { it.delete() }
    }

    /**
     * User-data rows in a snapshot: the journal plus the three tables
     * `UserDataCounter` counts.
     *
     * Reads the export rather than a store file, which is the one structural
     * difference from `StoreRecovery.userDataCount` — but the *definition* of
     * "user data" is upstream's, and the four sections are the same four. `-1`
     * means the file exists and could not be read, which is what upstream returns
     * for a store no strategy could open.
     */
    private fun rowCountOf(file: File): Int {
        val text = runCatching { file.readText() }.getOrNull() ?: return -1
        return runCatching {
            val parsed = DataExportImport.wireJson.decodeFromString(PiruFile.serializer(), text)
            parsed.sessions.sumOf { it.doses.size } +
                parsed.orphanDoses.size +
                parsed.dailyDoseItems.size +
                parsed.favorites.size +
                parsed.substanceColors.size
        }.getOrDefault(-1)
    }

    companion object {

        /** The directory under `filesDir` the snapshots live in. */
        const val DIRECTORY_NAME: String = "recovery"

        /** The name every snapshot is built from, so one parser reads them all. */
        private const val SNAPSHOT_BASE = "piru-backup"

        private const val SNAPSHOT_SUFFIX = ".json"

        /** What a copy whose name carries no recognizable reason is called. */
        private const val FALLBACK_REASON = "backup"

        /**
         * Sidecar reasons that represent a deliberate user choice — Delete
         * Everything, a pre-restore snapshot, a pre-PSID migration — and are
         * therefore **never** restored automatically.
         *
         * The three values are upstream's, verbatim. `prerestore` and
         * `prepsid` are written by flows this build does not have; they are kept
         * in the set because a copy carried over from a device that did write one
         * must still be recognized as intentional.
         */
        val INTENTIONAL_REASONS: Set<String> = setOf("predelete", "prerestore", "prepsid")

        /** The store's own directory, which is private to the app. */
        fun forContext(context: Context): StoreRecovery =
            StoreRecovery(File(context.applicationContext.filesDir, DIRECTORY_NAME))

        /**
         * The `<reason>` in `piru-backup.<reason>-<timestamp>.json`.
         *
         * The same parse as upstream's `sidecarReason`, including its one oddity:
         * a name with no `-` at all is returned whole rather than as nil, so a
         * hand-made file called `piru-backup.something.json` reports `something`
         * rather than falling back.
         */
        fun sidecarReason(fileName: String): String? {
            val prefix = SNAPSHOT_BASE + "."
            if (!fileName.startsWith(prefix)) return null
            val tag = fileName.removeSuffix(SNAPSHOT_SUFFIX).removePrefix(prefix)
            val dash = tag.lastIndexOf('-')
            return if (dash < 0) tag else tag.substring(0, dash)
        }

        /**
         * The trailing `-<unix-seconds>` in a snapshot's name.
         *
         * Seconds, not milliseconds: the name is built by
         * `Instant.epochSecond`, and a millisecond stamp would overflow nothing
         * but would make `sidecarReason` strip a different number of digits than
         * upstream's for no benefit.
         */
        fun sidecarTimestamp(fileName: String): Instant? {
            val tag = fileName.removeSuffix(SNAPSHOT_SUFFIX)
            val dash = tag.lastIndexOf('-')
            if (dash < 0) return null
            val seconds = tag.substring(dash + 1).toLongOrNull() ?: return null
            return runCatching { Instant.ofEpochSecond(seconds) }.getOrNull()
        }

        /**
         * The reason a pre-restore snapshot is written under.
         *
         * Upstream's `StoreRecovery.restore(from:)` uses
         * `"before-manual-restore"`, which is deliberately *not* one of
         * [INTENTIONAL_REASONS] — it is a file-level safety copy rather than a
         * purpose-built user snapshot. Kept as its own constant so a rename cannot
         * silently change that classification.
         */
        const val BEFORE_MANUAL_RESTORE: String = "before-manual-restore"

        /** Written when the store was empty and about to be overwritten by a recovery. */
        const val EMPTY_BEFORE_RECOVERY: String = "empty-before-recovery"

        /**
         * A human name for a copy, from the screen's own vocabulary.
         *
         * Ported from `DataStorageFormat.reasonTitle`. It lives here rather than
         * in `:app` because the set of reasons is defined here, and a title table
         * kept away from its keys drifts.
         */
        fun reasonTitle(reason: String): String = when (reason) {
            "corrupt" -> "Auto-recovered Data"
            "predelete" -> "Before You Deleted Everything"
            "prerestore", BEFORE_MANUAL_RESTORE -> "Before a Restore"
            EMPTY_BEFORE_RECOVERY -> "Recovered Data"
            else -> "Saved Copy"
        }
    }
}
