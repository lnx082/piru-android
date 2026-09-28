package glass.kagerou.piru.data.backup

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The encrypted envelope a backup file is written in.
 *
 * Ported from `Piru/Data/Backup/BackupCrypto.swift`.
 *
 * ## Why the byte layout is written out this way
 * The point of this file is that an envelope produced by the iOS app opens here
 * and the other way round, so every parameter that has to agree is pinned by hand
 * rather than left to a library default:
 *
 * - **AES-256-GCM**, 12-byte nonce, 16-byte tag. `Cipher.doFinal` returns
 *   ciphertext with the tag appended, so the sealed blob is
 *   `nonce ‖ ciphertext ‖ tag` — exactly CryptoKit's `SealedBox.combined`, and
 *   exactly the order Java writes them in.
 * - **PBKDF2-HMAC-SHA256**, 600,000 rounds, 32-byte key. 600,000 is OWASP's 2023
 *   floor for this construction; it is stored in the file, so raising it later
 *   does not strand old backups.
 * - **The passphrase is NFC-normalized before it is hashed.** A composed "é" and
 *   a decomposed "e + ◌́" are different byte strings and the same word; without
 *   this, a backup made on a Mac keyboard could fail to open on a phone.
 *
 * ## Device keys are absent by design
 * The iOS build can also encrypt with a key held in the iCloud Keychain. There is
 * no Android equivalent — the key lives in an Apple service, not in the file — so
 * a device-key envelope is recognized and refused rather than mis-opened. See
 * [Inspection.DeviceKey].
 */
object BackupCrypto {

    const val FORMAT: Int = 1

    /** The rounds written for a new passphrase backup. See the class note. */
    const val PBKDF2_ROUNDS: Int = 600_000

    /** The range a file's own `rounds` must fall in before it is worth deriving from. */
    const val MIN_ROUNDS: Int = 100_000
    const val MAX_ROUNDS: Int = 10_000_000

    /** Refuse anything larger before parsing it — an envelope is JSON and this bounds the read. */
    const val MAX_ENVELOPE_BYTES: Int = 256 * 1024 * 1024

    private const val KEY_BYTES = 32
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    private const val KDF_ALGORITHM = "pbkdf2-hmac-sha256"
    private const val AAD_PREFIX = "piru.backup.header.v"

    /** The extension a backup file carries, and the name the automatic export uses. */
    const val FILE_EXTENSION: String = "piruenc"
    const val AUTOMATIC_FILENAME: String = "Piru-Backup.$FILE_EXTENSION"

    private val random = SecureRandom()
    private val base64Encoder = Base64.getEncoder()
    private val base64Decoder = Base64.getDecoder()

    private val json = Json {
        // The iOS encoder omits a nil optional rather than writing null, and its
        // decoder treats a missing key and an explicit null the same way. Both
        // halves of that have to hold here or a round-trip through the two apps
        // stops being byte-stable.
        explicitNulls = false
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private val iso8601: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    // MARK: - Envelope

    @Serializable
    data class Envelope(
        val format: Int,
        val kind: Kind,
        val kdf: Kdf? = null,
        val keyID: String? = null,
        /** Standard Base64 of `nonce ‖ ciphertext ‖ tag`. */
        val sealed: String,
        val createdAt: String,
        val appVersion: String,
    ) {
        @Serializable
        enum class Kind {
            @SerialName("deviceKey")
            DEVICE_KEY,

            @SerialName("passphrase")
            PASSPHRASE,
        }

        @Serializable
        data class Kdf(
            val algorithm: String,
            val salt: String,
            val rounds: Int,
        )
    }

    /** What a file turned out to be, before anything is decrypted. */
    sealed interface Inspection {
        /** A passphrase backup this build can open, with the parameters read from the file. */
        data class Passphrase(val rounds: Int, val createdAt: Instant) : Inspection

        /**
         * A device-key backup. Recognized, and deliberately not openable here: the
         * key it needs lives in the iCloud Keychain that wrote it, not in the file.
         */
        data object DeviceKey : Inspection
    }

    /** Everything that can go wrong, named so the UI can say which one happened. */
    enum class Failure {
        /** Wrong passphrase, or a tampered file. Deliberately one case — see [decrypt]. */
        DECRYPTION_FAILED,

        EMPTY_PASSPHRASE,
        KEY_DERIVATION_FAILED,
        UNSUPPORTED_FORMAT,
        MALFORMED,

        /** The file is a device-key backup, which this platform cannot open. */
        DEVICE_KEY_UNAVAILABLE,
    }

    class BackupException(val failure: Failure) : Exception(failure.name)

    // MARK: - Reading without the key

    /**
     * What this file is, and whether it can be opened — without the passphrase.
     *
     * Every check here is also a guard on the decrypt path, which re-runs them:
     * a file can be inspected, then replaced, then decrypted, and the second pass
     * is the one that matters.
     */
    fun inspect(bytes: ByteArray): Inspection {
        if (bytes.size > MAX_ENVELOPE_BYTES) throw BackupException(Failure.MALFORMED)
        val envelope = decodeEnvelope(bytes)

        if (envelope.format != FORMAT) throw BackupException(Failure.UNSUPPORTED_FORMAT)

        return when (envelope.kind) {
            Envelope.Kind.DEVICE_KEY -> Inspection.DeviceKey
            Envelope.Kind.PASSPHRASE -> {
                val kdf = envelope.kdf
                if (kdf == null ||
                    kdf.algorithm != KDF_ALGORITHM ||
                    decodeBase64OrNull(kdf.salt)?.size != SALT_BYTES ||
                    kdf.rounds !in MIN_ROUNDS..MAX_ROUNDS
                ) {
                    throw BackupException(Failure.MALFORMED)
                }
                Inspection.Passphrase(kdf.rounds, parseInstant(envelope.createdAt))
            }
        }
    }

    // MARK: - Sealing

    /** Encrypt [plaintext] under [passphrase], with a fresh salt, as a new envelope. */
    fun encrypt(
        plaintext: ByteArray,
        passphrase: String,
        appVersion: String,
        now: Instant = Instant.now(),
    ): ByteArray {
        val normalized = normalize(passphrase)
        if (normalized.isEmpty()) throw BackupException(Failure.EMPTY_PASSPHRASE)

        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val kdf = Envelope.Kdf(
            algorithm = KDF_ALGORITHM,
            salt = base64Encoder.encodeToString(salt),
            rounds = PBKDF2_ROUNDS,
        )
        val key = deriveKey(normalized, salt, PBKDF2_ROUNDS)
        val aad = headerAAD(format = FORMAT, kind = Envelope.Kind.PASSPHRASE, kdf = kdf, keyID = null)

        val envelope = Envelope(
            format = FORMAT,
            kind = Envelope.Kind.PASSPHRASE,
            kdf = kdf,
            keyID = null,
            sealed = base64Encoder.encodeToString(seal(plaintext, key, aad)),
            createdAt = iso8601.format(now.truncatedTo(ChronoUnit.SECONDS)),
            appVersion = appVersion,
        )
        return json.encodeToString(Envelope.serializer(), envelope).toByteArray(Charsets.UTF_8)
    }

    /**
     * Decrypt [bytes].
     *
     * A wrong passphrase and a corrupted file are **the same failure** and report
     * as one. GCM cannot tell them apart — it verifies a tag, and both produce a
     * tag mismatch — so pretending otherwise would be inventing a distinction.
     */
    fun decrypt(bytes: ByteArray, passphrase: String): ByteArray {
        if (bytes.size > MAX_ENVELOPE_BYTES) throw BackupException(Failure.MALFORMED)
        val envelope = decodeEnvelope(bytes)

        if (envelope.format != FORMAT) throw BackupException(Failure.UNSUPPORTED_FORMAT)
        if (envelope.kind == Envelope.Kind.DEVICE_KEY) throw BackupException(Failure.DEVICE_KEY_UNAVAILABLE)

        val kdf = envelope.kdf ?: throw BackupException(Failure.MALFORMED)
        if (kdf.algorithm != KDF_ALGORITHM) throw BackupException(Failure.MALFORMED)
        val salt = decodeBase64OrNull(kdf.salt) ?: throw BackupException(Failure.MALFORMED)
        if (salt.size != SALT_BYTES) throw BackupException(Failure.MALFORMED)
        if (kdf.rounds !in MIN_ROUNDS..MAX_ROUNDS) throw BackupException(Failure.MALFORMED)

        val normalized = normalize(passphrase)
        if (normalized.isEmpty()) throw BackupException(Failure.EMPTY_PASSPHRASE)

        // The KDF parameters come from the file, not from this build's constants:
        // that is what lets a backup made when the floor was lower still open.
        val key = deriveKey(normalized, salt, kdf.rounds)
        val aad = headerAAD(
            format = envelope.format,
            kind = envelope.kind,
            kdf = kdf,
            // The file's own keyID. Null for a passphrase envelope, and read from
            // the file rather than recomputed so a device-key envelope would
            // authenticate against the id it was written with.
            keyID = envelope.keyID,
        )

        val sealed = decodeBase64OrNull(envelope.sealed) ?: throw BackupException(Failure.MALFORMED)
        return open(sealed, key, aad)
    }

    // MARK: - The associated data

    /**
     * The bytes GCM authenticates alongside the ciphertext.
     *
     * ## This is not JSON, and that is the whole point
     * It is the header fields joined with `\n`, in a fixed order:
     *
     * ```
     * piru.backup.header.v1
     * kind=passphrase
     * kdf=pbkdf2-hmac-sha256
     * rounds=600000
     * salt=<base64>
     * ```
     *
     * and for a device key:
     *
     * ```
     * piru.backup.header.v1
     * kind=deviceKey
     * keyID=<16 hex>
     * ```
     *
     * No trailing newline. The two lines that do not apply are **absent**, not
     * empty — a passphrase envelope has no `keyID` line and a device-key envelope
     * has no kdf lines at all, matching the Swift builder appending each part only
     * when it is present.
     *
     * Getting a single byte of this wrong does not weaken the encryption; it makes
     * every file fail to open, with a tag mismatch that looks exactly like a wrong
     * password.
     */
    internal fun headerAAD(
        format: Int,
        kind: Envelope.Kind,
        kdf: Envelope.Kdf?,
        keyID: String?,
    ): ByteArray {
        val parts = mutableListOf(
            "$AAD_PREFIX$format",
            "kind=${kind.wireValue()}",
        )
        if (kdf != null) {
            parts += "kdf=${kdf.algorithm}"
            parts += "rounds=${kdf.rounds}"
            parts += "salt=${kdf.salt}"
        }
        if (keyID != null) parts += "keyID=$keyID"
        return parts.joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    /** The wire spelling of a kind, which is its `@SerialName` and also its AAD token. */
    private fun Envelope.Kind.wireValue(): String = when (this) {
        Envelope.Kind.DEVICE_KEY -> "deviceKey"
        Envelope.Kind.PASSPHRASE -> "passphrase"
    }

    // MARK: - Primitives

    private fun seal(plaintext: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        val body = cipher.doFinal(plaintext)
        return nonce + body
    }

    private fun open(sealed: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
        if (sealed.size <= NONCE_BYTES) throw BackupException(Failure.MALFORMED)
        val nonce = sealed.copyOfRange(0, NONCE_BYTES)
        val body = sealed.copyOfRange(NONCE_BYTES, sealed.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(body)
        } catch (_: Exception) {
            // Tag mismatch, whatever caused it. See `decrypt`.
            throw BackupException(Failure.DECRYPTION_FAILED)
        }
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, rounds: Int): ByteArray = try {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, rounds, KEY_BYTES * 8)
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } catch (_: Exception) {
        throw BackupException(Failure.KEY_DERIVATION_FAILED)
    }

    /**
     * NFC, before the passphrase is measured or hashed.
     *
     * Order matters: normalizing after the emptiness check would let a string of
     * nothing but combining marks pass as non-empty and then normalize to
     * something the user cannot retype.
     */
    private fun normalize(passphrase: String): String =
        Normalizer.normalize(passphrase, Normalizer.Form.NFC)

    /** The 16-hex-character identifier of a key: the first 8 bytes of its SHA-256. */
    fun keyID(key: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(key).take(8).joinToString("") { "%02x".format(it) }

    /**
     * The content hash the automatic backup uses to skip a write when nothing has
     * changed. Lowercase hex, matching the iOS side.
     */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun decodeEnvelope(bytes: ByteArray): Envelope {
        val text = bytes.toString(Charsets.UTF_8)
        return try {
            json.decodeFromString(Envelope.serializer(), text)
        } catch (_: Exception) {
            throw BackupException(Failure.MALFORMED)
        }
    }

    private fun decodeBase64OrNull(value: String): ByteArray? =
        try {
            base64Decoder.decode(value)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun parseInstant(value: String): Instant =
        try {
            Instant.parse(value)
        } catch (_: Exception) {
            throw BackupException(Failure.MALFORMED)
        }
}
