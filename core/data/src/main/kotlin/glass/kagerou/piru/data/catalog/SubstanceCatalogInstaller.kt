package glass.kagerou.piru.data.catalog

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Puts the bundled substance catalog on disk, where SQLite can open it.
 *
 * The catalog ships inside the APK as an asset and is 18 MB. SQLite needs a real
 * file path — it cannot open a path inside the APK — so the asset is copied out
 * once and verified before anything reads it.
 *
 * ## Why the checksum is checked here, and against what
 * The Android framework has no equivalent of opening an asset read-only, so the
 * copy is unavoidable; what matters is that a truncated or corrupted copy is
 * caught *before* it is used, rather than surfacing later as a substance that
 * quietly has no dose ladder. The expected hash comes from `manifest.json`,
 * which ships as a tracked file beside the catalog in the build inputs and is
 * staged into the assets with it.
 *
 * The manifest is the authority, not the file being verified: taking the hash
 * from the catalog itself would make the check always agree with whatever was
 * copied, which is not a check at all. This mirrors `db/fetch-db.sh`.
 */
object SubstanceCatalogInstaller {

    /** Asset file name of the catalog. */
    const val CATALOG_ASSET = "piru-substances.sqlite"

    /** Asset file name of the manifest that describes it. */
    const val MANIFEST_ASSET = "manifest.json"

    /** On-disk name once installed. */
    const val CATALOG_FILE = "piru-substances.sqlite"

    private val json = Json { ignoreUnknownKeys = true }

    /** The fields of `manifest.json` this side reads. */
    @Serializable
    data class CatalogManifest(
        val schema_version: Int = 0,
        val content_version: String = "",
        val sqlite_sha256: String = "",
        val sqlite_size_bytes: Long = 0,
    )

    /**
     * Ensure the catalog is installed and matches its manifest, returning the
     * file to open.
     *
     * A no-op when the file is already in place and correct, so this is safe to
     * call on every launch. A wrong or truncated copy is deleted and rewritten
     * once; a second failure throws, because by then the asset itself is wrong
     * and retrying will not help.
     */
    suspend fun install(context: Context): File = withContext(Dispatchers.IO) {
        val manifest = readManifest(context)
        val target = File(context.filesDir, CATALOG_FILE)

        if (target.exists() && target.length() == manifest.sqlite_size_bytes) {
            if (sha256(target) == manifest.sqlite_sha256) return@withContext target
        }

        target.delete()
        copyAsset(context, CATALOG_ASSET, target)

        val actual = sha256(target)
        if (actual != manifest.sqlite_sha256) {
            target.delete()
            throw IOException(
                "Substance catalog failed verification: manifest expects " +
                    "${manifest.sqlite_sha256} (${manifest.sqlite_size_bytes} bytes), " +
                    "the packaged asset hashes to $actual (${target.length()} bytes). " +
                    "The asset and db/manifest.json are out of step — run db/fetch-db.sh.",
            )
        }
        target
    }

    /** The catalog's manifest, for a caller that wants `content_version` or `schema_version`. */
    suspend fun manifest(context: Context): CatalogManifest = withContext(Dispatchers.IO) {
        readManifest(context)
    }

    private fun readManifest(context: Context): CatalogManifest {
        val text = context.assets.open(MANIFEST_ASSET).use { it.readBytes().decodeToString() }
        val parsed = json.decodeFromString<CatalogManifest>(text)
        if (parsed.sqlite_sha256.isEmpty()) {
            throw IOException("$MANIFEST_ASSET carries no sqlite_sha256, so nothing can be verified")
        }
        return parsed
    }

    private fun copyAsset(context: Context, assetName: String, target: File) {
        context.assets.open(assetName).use { input ->
            target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
