package glass.kagerou.piru.data.backup

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.security.SecureRandom
import org.junit.jupiter.api.Test

/**
 * The device-key envelope, which the format and the reader knew about and nothing could produce.
 *
 * ## Why this file exists
 * [BackupCrypto.inspect] returned `Inspection.DeviceKey` for a `kind=deviceKey` file, and `decrypt` refused one with a
 * passphrase — so the kind was in the format and in the reader and in **no writer**. A backup could be recognised and
 * neither made nor restored.
 *
 * ## The three failures that must not read alike
 * - **A different key** is `DEVICE_KEY_UNAVAILABLE`. It is genuinely distinguishable from corruption, and telling
 *   somebody their backup is damaged when it is merely another device's is the worse of the two messages.
 * - **A corrupted file** is `MALFORMED`. GCM cannot tell a wrong key from a damaged tag, so once the id matches,
 *   everything is one failure — the same reasoning `decrypt` documents for passphrases.
 * - **A passphrase envelope handed to the device-key reader** is `MALFORMED`, not a message about a passphrase: the
 *   caller asked for the device key, so a file that needs a passphrase is the wrong file.
 */
class BackupCryptoDeviceKeyTest {

    private val key = ByteArray(32).also(SecureRandom()::nextBytes)
    private val otherKey = ByteArray(32).also(SecureRandom()::nextBytes)
    private val plaintext = """{"doses":[],"sessions":[],"note":"ünïcode and emoji 🜛"}""".toByteArray()

    @Test
    fun `a device-key envelope round-trips`() {
        val envelope = BackupCrypto.encryptWithDeviceKey(plaintext, key, appVersion = "0.6.1")
        BackupCrypto.decryptWithDeviceKey(envelope, key).toString(Charsets.UTF_8) shouldBe
            plaintext.toString(Charsets.UTF_8)
    }

    /** The envelope says what it is, so a reader can route it without guessing. */
    @Test
    fun `a device-key envelope identifies itself as one`() {
        val envelope = BackupCrypto.encryptWithDeviceKey(plaintext, key, appVersion = "0.6.1")
        val text = envelope.toString(Charsets.UTF_8)
        println("DEVICEKEYPROBE " + text.take(240))
        text shouldContain "deviceKey"
        BackupCrypto.inspect(envelope).shouldBeInstanceOf<BackupCrypto.Inspection.DeviceKey>()
    }

    /**
     * The envelope carries the key's id and **no** KDF block.
     *
     * A device-key envelope derives nothing, so a `kdf` in it would claim a derivation that did not happen — and the
     * id is what lets a reader say "this is another device's file" without attempting to unseal it.
     */
    @Test
    fun `a device-key envelope carries the key id and no kdf`() {
        val envelope = BackupCrypto.encryptWithDeviceKey(plaintext, key, appVersion = "0.6.1")
        val text = envelope.toString(Charsets.UTF_8)
        text shouldContain BackupCrypto.keyID(key)
        // The word would appear inside "kdf" only if a block were written.
        (text.contains("\"kdf\"")) shouldBe false
    }

    /** A different key is `DEVICE_KEY_UNAVAILABLE`, which is a different message from "this file is damaged". */
    @Test
    fun `another device's key reports as unavailable`() {
        val envelope = BackupCrypto.encryptWithDeviceKey(plaintext, key, appVersion = "0.6.1")
        val failure = runCatching { BackupCrypto.decryptWithDeviceKey(envelope, otherKey) }
            .exceptionOrNull()
            .shouldBeInstanceOf<BackupCrypto.BackupException>()
        failure.failure shouldBe BackupCrypto.Failure.DEVICE_KEY_UNAVAILABLE
    }

    /**
     * A **tampered** envelope under the right key is not a key problem.
     *
     * The other half of the distinction, and the assertion is narrower than my first version. I expected `MALFORMED`
     * and got `DECRYPTION_FAILED` — the crypto layer already separates "the structure is wrong" from "the tag does not
     * verify", which is a **better** distinction than the one I was asserting, so the test was wrong and the code was
     * right.
     *
     * What must hold either way is the thing that matters to a user: a damaged file must not be reported as *another
     * device's key*, because that is the one failure with a different remedy.
     */
    @Test
    fun `a tampered envelope is not reported as a key problem`() {
        val envelope = BackupCrypto.encryptWithDeviceKey(plaintext, key, appVersion = "0.6.1")
        // Flip a byte well inside the base64 payload rather than in the JSON structure, so the failure is the tag and
        // not the parser.
        val marker = "\"sealed\":\"".toByteArray(Charsets.UTF_8)
        val at = envelope.indexOfSequence(marker)
        check(at >= 0) { "the sealed field was not found, so this test would corrupt nothing" }
        val target = at + marker.size + 8
        envelope[target] = if (envelope[target] == 'A'.code.toByte()) 'B'.code.toByte() else 'A'.code.toByte()

        val failure = runCatching { BackupCrypto.decryptWithDeviceKey(envelope, key) }
            .exceptionOrNull()
            .shouldBeInstanceOf<BackupCrypto.BackupException>()
        println("DEVICEKEYPROBE tampered=" + failure.failure)
        (failure.failure != BackupCrypto.Failure.DEVICE_KEY_UNAVAILABLE) shouldBe true
        // And it is one of the two structural outcomes, not some third thing.
        (failure.failure == BackupCrypto.Failure.MALFORMED ||
            failure.failure == BackupCrypto.Failure.DECRYPTION_FAILED) shouldBe true
    }

    /** The passphrase reader refuses a device-key file, and says so in the way the caller can act on. */
    @Test
    fun `a passphrase cannot open a device-key envelope`() {
        val envelope = BackupCrypto.encryptWithDeviceKey(plaintext, key, appVersion = "0.6.1")
        val failure = runCatching { BackupCrypto.decrypt(envelope, "hunter2") }
            .exceptionOrNull()
            .shouldBeInstanceOf<BackupCrypto.BackupException>()
        failure.failure shouldBe BackupCrypto.Failure.DEVICE_KEY_UNAVAILABLE
    }

    /** And the device-key reader refuses a passphrase file, because the caller asked for the wrong kind. */
    @Test
    fun `the device-key reader refuses a passphrase envelope`() {
        val envelope = BackupCrypto.encrypt(plaintext, "hunter2", appVersion = "0.6.1")
        val failure = runCatching { BackupCrypto.decryptWithDeviceKey(envelope, key) }
            .exceptionOrNull()
            .shouldBeInstanceOf<BackupCrypto.BackupException>()
        failure.failure shouldBe BackupCrypto.Failure.MALFORMED
    }

    /**
     * A key that is not 32 bytes is refused rather than stretched.
     *
     * Silently padding a short key would produce a backup that a correct implementation cannot open, which is the worst
     * available outcome: the file looks fine and the data is unrecoverable.
     */
    @Test
    fun `a wrongly sized key is refused`() {
        for (bad in listOf(ByteArray(0), ByteArray(16), ByteArray(31), ByteArray(33))) {
            val failure = runCatching { BackupCrypto.encryptWithDeviceKey(plaintext, bad, "0.6.1") }
                .exceptionOrNull()
                .shouldBeInstanceOf<BackupCrypto.BackupException>()
            failure.failure shouldBe BackupCrypto.Failure.MALFORMED
        }
    }

    /** Two envelopes of the same plaintext differ, so the nonce is fresh rather than fixed. */
    @Test
    fun `two envelopes of one plaintext differ`() {
        val a = BackupCrypto.encryptWithDeviceKey(plaintext, key, appVersion = "0.6.1")
        val b = BackupCrypto.encryptWithDeviceKey(plaintext, key, appVersion = "0.6.1")
        (a.contentEquals(b)) shouldBe false
        // And both still open, which is what makes the difference a fresh nonce rather than damage.
        BackupCrypto.decryptWithDeviceKey(a, key).size shouldBe plaintext.size
        BackupCrypto.decryptWithDeviceKey(b, key).size shouldBe plaintext.size
    }

    /** The key id is stable for a key and different for another, which is what the identity check rests on. */
    @Test
    fun `the key id identifies the key`() {
        BackupCrypto.keyID(key) shouldBe BackupCrypto.keyID(key.copyOf())
        (BackupCrypto.keyID(key) == BackupCrypto.keyID(otherKey)) shouldBe false
    }
}

/** The first index of [needle] in this array, or -1. A small helper so the tamper test can find its target. */
private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
    if (needle.isEmpty() || needle.size > size) return -1
    outer@ for (start in 0..(size - needle.size)) {
        for (offset in needle.indices) {
            if (this[start + offset] != needle[offset]) continue@outer
        }
        return start
    }
    return -1
}
