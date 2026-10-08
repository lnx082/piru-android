package glass.kagerou.piru.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import glass.kagerou.piru.data.backup.BackupCrypto
import java.io.InputStream

/**
 * Reading a user-chosen file for import, with a ceiling.
 *
 * Both import paths — Data & Backup and onboarding — used to do
 * `openInputStream(uri).readBytes()`, which reads the whole document into a `ByteArray` and then
 * into a `String` with no bound at all. A mis-picked file, a corrupt one, or a provider that
 * streams forever would be read until the process died. iOS refuses anything over 256 MB before
 * reading ([`BackupFileImport.swift`]); `BackupCrypto` has the same limit but applies it only
 * after the bytes are already in memory, which is the point where it is too late to help.
 *
 * So the size is asked for first, from the provider's own metadata, and refused before a single
 * byte is read when it is over. The streaming cap is the belt to that braces: a provider is free
 * not to report a size, and one that reports `null` and then streams 4 GB has to be stopped some
 * other way.
 */
internal object ImportFiles {

    /**
     * The ceiling, shared with `BackupCrypto` rather than restated.
     *
     * Not a new number: this is the envelope's own limit, and a plain JSON export can be larger
     * than an encrypted one only by the ratio of base64 expansion, so the same bound is the right
     * one for both and having two would eventually mean two different answers.
     */
    const val MAX_BYTES: Int = BackupCrypto.MAX_ENVELOPE_BYTES

    /** What reading a picked file produced. */
    sealed interface Result {
        /** The file's bytes, guaranteed to be at most [MAX_BYTES]. */
        data class Bytes(val value: ByteArray) : Result {
            // A data class over a ByteArray needs these to behave like a value; without them
            // two equal payloads compare unequal, which is a trap for a caller that compares
            // results rather than switching on them.
            override fun equals(other: Any?): Boolean =
                other is Bytes && value.contentEquals(other.value)

            override fun hashCode(): Int = value.contentHashCode()
        }

        /** The document is over [MAX_BYTES], refused before reading. */
        data object TooLarge : Result

        /** Nothing readable there: a revoked permission, a deleted file, a broken provider. */
        data object Unreadable : Result
    }

    /**
     * Read [uri], or say why not.
     *
     * [sizeOf] is the provider's declared size, which is the only way to refuse *before* the
     * read rather than after it. A provider that does not know answers `null`, and then the
     * streaming cap in [readCapped] is what bounds the work.
     */
    fun read(context: Context, uri: Uri): Result {
        val declared = declaredSize(context, uri)
        if (declared != null && declared > MAX_BYTES) return Result.TooLarge

        val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: return Result.Unreadable
        return stream.use { input ->
            when (val bytes = runCatching { readCapped(input) }.getOrNull()) {
                null -> Result.Unreadable
                else -> bytes
            }
        }
    }

    /**
     * The provider's size for [uri], or null when it will not say.
     *
     * `OpenableColumns.SIZE` is the contract's own answer — the file's size in bytes — and a
     * provider that returns nothing for it is the case this has to tolerate rather than assume
     * away.
     */
    private fun declaredSize(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index)
            }
    }.getOrNull()

    /**
     * Read [input] up to the ceiling, and report the ceiling if it goes past.
     *
     * Reads one byte past the limit so that "exactly the maximum" is accepted and "the maximum
     * plus one" is refused: a stream that ends precisely at the limit must not be reported as
     * oversized, and a comparison that only checks `size > MAX` after filling a capped buffer
     * cannot tell those apart.
     */
    private fun readCapped(input: InputStream): Result {
        val buffer = ByteArray(64 * 1024)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (out.size() + read > MAX_BYTES) return Result.TooLarge
            out.write(buffer, 0, read)
        }
        return Result.Bytes(out.toByteArray())
    }
}
