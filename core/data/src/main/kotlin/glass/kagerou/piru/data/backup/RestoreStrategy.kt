package glass.kagerou.piru.data.backup

/**
 * What restoring a backup does to the data already on the device.
 *
 * Ported from `BackupManager.RestoreStrategy`. The two cases are **not** a flag the import layer honours — this port's
 * `importJSON` deliberately has no merge-or-replace parameter, because the difference is entirely whether the caller
 * emptied the stores first:
 *
 * - [MERGE] adds the backup's rows to what is there, de-duplicated by the import layer.
 * - [REPLACE] is a wipe followed by the same call.
 *
 * So the strategy belongs to the **caller**, and the reason it lives here rather than as a Boolean at the call site is
 * that the two have different failure modes. A merge that goes wrong leaves duplicated data, which is recoverable; a
 * replace that goes wrong leaves nothing, which is not. A caller that has to name one of two cases is a caller that
 * has to decide which risk it is taking.
 *
 * ## Why replace is ordered wipe-then-import and not the reverse
 * An import that fails halfway through a replace leaves the user with the backup's rows merged into their old data —
 * noisy but intact. The reverse order would leave them with **nothing**, having deleted the old data before knowing
 * the file was readable. Upstream wipes first for the same reason, and the wipe is what makes the import a replace.
 */
enum class RestoreStrategy {
    /** Add the backup's rows to the current data, de-duplicated by the import layer. */
    MERGE,

    /** Snapshot and wipe the current data, then import only the backup's rows. */
    REPLACE,
}
