package glass.kagerou.piru.data.backup

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.text.Normalizer
import java.time.Instant
import org.junit.jupiter.api.Test

/**
 * The backup envelope, pinned at the byte level where it has to be.
 *
 * The associated data is not a detail this file is free to change: a backup
 * written by the iOS app authenticates a string built the same way, and one byte
 * of difference makes every file fail to open with an error that looks exactly
 * like a wrong password. So the AAD is asserted literally rather than by
 * round-trip, which would pass for any self-consistent scheme.
 */
class BackupCryptoTest {

    private val passphrase = "correct horse battery staple"
    private val payload = """{"piruExportVersion":2,"doseEntries":[]}""".toByteArray()

    private fun encrypt(pass: String = passphrase): ByteArray =
        BackupCrypto.encrypt(payload, pass, appVersion = "0.1.0 (1)", now = Instant.parse("2026-09-28T12:00:00Z"))

    // MARK: - The associated data, literally

    @Test
    fun `a passphrase envelope authenticates exactly these bytes`() {
        val aad = BackupCrypto.headerAAD(
            format = 1,
            kind = BackupCrypto.Envelope.Kind.PASSPHRASE,
            kdf = BackupCrypto.Envelope.Kdf(
                algorithm = "pbkdf2-hmac-sha256",
                salt = "AAECAwQFBgcICQoLDA0ODw==",
                rounds = 600_000,
            ),
            keyID = null,
        )
        aad.toString(Charsets.UTF_8) shouldBe
            "piru.backup.header.v1\n" +
            "kind=passphrase\n" +
            "kdf=pbkdf2-hmac-sha256\n" +
            "rounds=600000\n" +
            "salt=AAECAwQFBgcICQoLDA0ODw=="
    }

    @Test
    fun `a device-key envelope authenticates exactly these bytes`() {
        val aad = BackupCrypto.headerAAD(
            format = 1,
            kind = BackupCrypto.Envelope.Kind.DEVICE_KEY,
            kdf = null,
            keyID = "0123456789abcdef",
        )
        aad.toString(Charsets.UTF_8) shouldBe
            "piru.backup.header.v1\n" +
            "kind=deviceKey\n" +
            "keyID=0123456789abcdef"
    }

    @Test
    fun `the two header shapes are not the same length, so a swap cannot authenticate`() {
        // A device-key header carries no kdf lines and a passphrase header carries
        // no keyID line. Asserting the absence is the point: a builder that wrote
        // an empty line instead would still round-trip against itself.
        val device = BackupCrypto.headerAAD(1, BackupCrypto.Envelope.Kind.DEVICE_KEY, null, "0123456789abcdef")
        device.toString(Charsets.UTF_8) shouldContain "kind=deviceKey"
        (device.toString(Charsets.UTF_8).contains("kdf=")) shouldBe false
        (device.toString(Charsets.UTF_8).contains("keyID=\n")) shouldBe false
    }

    // MARK: - Round trip

    @Test
    fun `a passphrase envelope round-trips`() {
        val envelope = encrypt()
        BackupCrypto.decrypt(envelope, passphrase) shouldBe payload
    }

    @Test
    fun `the envelope is JSON with the fields the iOS decoder expects`() {
        val text = encrypt().toString(Charsets.UTF_8)
        text shouldStartWith "{"
        text shouldContain "\"format\":1"
        text shouldContain "\"kind\":\"passphrase\""
        text shouldContain "\"algorithm\":\"pbkdf2-hmac-sha256\""
        text shouldContain "\"rounds\":600000"
        text shouldContain "\"createdAt\":\"2026-09-28T12:00:00Z\""
        // A nil optional is omitted, not written as null — that is what Swift's
        // synthesized encoder does, and the decoder on the other side reads both
        // the same way only if this side matches.
        (text.contains("\"keyID\"")) shouldBe false
        (text.contains("null")) shouldBe false
    }

    // MARK: - The passphrase

    @Test
    fun `a composed and a decomposed passphrase open the same file`() {
        // "é" written as one code point, and as "e" plus a combining acute. They
        // are the same word and different byte strings; NFC is what makes them
        // one passphrase instead of two that look identical.
        val composed = Normalizer.normalize("café-2026", Normalizer.Form.NFC)
        val decomposed = Normalizer.normalize("café-2026", Normalizer.Form.NFD)
        (composed == decomposed) shouldBe false

        val envelope = encrypt(composed)
        BackupCrypto.decrypt(envelope, decomposed) shouldBe payload
    }

    @Test
    fun `a wrong passphrase and a tampered ciphertext report the same failure`() {
        // GCM verifies a tag; it cannot say which of the two happened, so neither
        // does this. Distinguishing them would be inventing information.
        shouldThrow<BackupCrypto.BackupException> {
            BackupCrypto.decrypt(encrypt(), "not the passphrase")
        }.failure shouldBe BackupCrypto.Failure.DECRYPTION_FAILED

        shouldThrow<BackupCrypto.BackupException> {
            BackupCrypto.decrypt(retamper(encrypt(), "sealed") { flipOneCharacter(it) }, passphrase)
        }.failure shouldBe BackupCrypto.Failure.DECRYPTION_FAILED
    }

    @Test
    fun `tampering with an authenticated header field is caught`() {
        // `rounds` reaches the key derivation *and* the AAD, so a file whose
        // header was edited fails the tag — which is the whole reason the header
        // is authenticated rather than merely parsed.
        shouldThrow<BackupCrypto.BackupException> {
            BackupCrypto.decrypt(retamper(encrypt(), "rounds") { "300000" }, passphrase)
        }.failure shouldBe BackupCrypto.Failure.DECRYPTION_FAILED
    }

    @Test
    fun `tampering with a field outside the authenticated header still opens`() {
        // `createdAt` and `appVersion` are metadata: they are in the envelope and
        // deliberately **not** in the AAD, so editing them is not an attack on the
        // payload and must not be treated as one. A version string that a future
        // build reformats would otherwise make every old backup unopenable.
        val edited = retamper(encrypt(), "appVersion") { "9.9 (999)" }
        BackupCrypto.decrypt(edited, passphrase) shouldBe payload
    }

    @Test
    fun `a tampered envelope that is no longer valid json is malformed, not a bad password`() {
        // A different failure with a different message, because a different thing
        // happened: there is nothing here to authenticate.
        val bytes = encrypt()
        bytes[0] = 'X'.code.toByte()
        shouldThrow<BackupCrypto.BackupException> {
            BackupCrypto.decrypt(bytes, passphrase)
        }.failure shouldBe BackupCrypto.Failure.MALFORMED
    }

    /**
     * Replace one field of the envelope, leaving everything else byte-for-byte.
     *
     * Quotes the replacement only when the field it is replacing was quoted, so
     * this can reach `rounds` (a JSON number) as well as `appVersion` (a string)
     * without the caller having to say which.
     */
    private fun retamper(envelope: ByteArray, field: String, edit: (String) -> String): ByteArray {
        val text = envelope.toString(Charsets.UTF_8)
        val match = Regex("\"$field\":(\"([^\"]*)\"|([0-9]+))").find(text)
            ?: error("no field \"$field\" in the envelope")
        val wasQuoted = match.groupValues[2].isNotEmpty()
        val current = if (wasQuoted) match.groupValues[2] else match.groupValues[3]
        val replacement = if (wasQuoted) "\"$field\":\"${edit(current)}\"" else "\"$field\":${edit(current)}"
        return text.replaceRange(match.range, replacement).toByteArray(Charsets.UTF_8)
    }

    /** Change one character of a base64 body, keeping it base64 so the failure is the tag and not the decoder. */
    private fun flipOneCharacter(value: String): String =
        value.mapIndexed { index, c ->
            if (index == value.length / 2) (if (c == 'A') 'B' else 'A') else c
        }.joinToString("")

    @Test
    fun `an empty passphrase is refused before it is derived from`() {
        shouldThrow<BackupCrypto.BackupException> {
            BackupCrypto.encrypt(payload, "", appVersion = "0.1.0 (1)")
        }.failure shouldBe BackupCrypto.Failure.EMPTY_PASSPHRASE

        shouldThrow<BackupCrypto.BackupException> {
            BackupCrypto.decrypt(encrypt(), "")
        }.failure shouldBe BackupCrypto.Failure.EMPTY_PASSPHRASE
    }

    // MARK: - Inspection

    @Test
    fun `inspection reads a passphrase envelope without the passphrase`() {
        val found = BackupCrypto.inspect(encrypt())
        found shouldBe BackupCrypto.Inspection.Passphrase(600_000, Instant.parse("2026-09-28T12:00:00Z"))
    }

    @Test
    fun `a device-key envelope is recognized and refused rather than mis-opened`() {
        // The key it needs is in the iCloud Keychain that wrote it. Guessing would
        // produce a tag mismatch indistinguishable from a wrong password, which
        // would send a migrating user hunting for a passphrase that never existed.
        val deviceKeyEnvelope = """
            {"format":1,"kind":"deviceKey","keyID":"0123456789abcdef",
             "sealed":"AAAA","createdAt":"2026-09-28T12:00:00Z","appVersion":"0.1.0 (1)"}
        """.trimIndent().toByteArray()

        BackupCrypto.inspect(deviceKeyEnvelope) shouldBe BackupCrypto.Inspection.DeviceKey
        shouldThrow<BackupCrypto.BackupException> {
            BackupCrypto.decrypt(deviceKeyEnvelope, passphrase)
        }.failure shouldBe BackupCrypto.Failure.DEVICE_KEY_UNAVAILABLE
    }

    @Test
    fun `a file this build does not understand says so rather than reporting a bad password`() {
        val future = """
            {"format":9,"kind":"passphrase","sealed":"AAAA",
             "createdAt":"2026-09-28T12:00:00Z","appVersion":"9.0 (1)"}
        """.trimIndent().toByteArray()
        shouldThrow<BackupCrypto.BackupException> {
            BackupCrypto.inspect(future)
        }.failure shouldBe BackupCrypto.Failure.UNSUPPORTED_FORMAT
    }

    @Test
    fun `a malformed kdf is refused, including rounds outside the sane range`() {
        fun envelope(rounds: Int) = """
            {"format":1,"kind":"passphrase",
             "kdf":{"algorithm":"pbkdf2-hmac-sha256","salt":"AAECAwQFBgcICQoLDA0ODw==","rounds":$rounds},
             "sealed":"AAAA","createdAt":"2026-09-28T12:00:00Z","appVersion":"0.1.0 (1)"}
        """.trimIndent().toByteArray()

        // Below the floor: not worth deriving from, and a sign the file is wrong.
        shouldThrow<BackupCrypto.BackupException> { BackupCrypto.inspect(envelope(1_000)) }
            .failure shouldBe BackupCrypto.Failure.MALFORMED
        // Above the ceiling: a denial of service dressed as a backup.
        shouldThrow<BackupCrypto.BackupException> { BackupCrypto.inspect(envelope(50_000_000)) }
            .failure shouldBe BackupCrypto.Failure.MALFORMED

        BackupCrypto.inspect(envelope(1_200_000)) shouldBe
            BackupCrypto.Inspection.Passphrase(1_200_000, Instant.parse("2026-09-28T12:00:00Z"))
    }

    @Test
    fun `something that is not an envelope at all is malformed`() {
        shouldThrow<BackupCrypto.BackupException> {
            BackupCrypto.inspect("this is a text file".toByteArray())
        }.failure shouldBe BackupCrypto.Failure.MALFORMED
    }

    // MARK: - Identifiers

    @Test
    fun `a key id is the first eight bytes of the key's digest, in lowercase hex`() {
        val key = ByteArray(32) { it.toByte() }
        BackupCrypto.keyID(key) shouldBe "630dcd2966c43366"
    }

    @Test
    fun `a fresh salt and nonce make two envelopes of the same plaintext differ`() {
        // Deterministic encryption would leak which backups are identical.
        val first = encrypt()
        val second = encrypt()
        (first.contentEquals(second)) shouldBe false
        BackupCrypto.decrypt(second, passphrase) shouldBe payload
    }
}
