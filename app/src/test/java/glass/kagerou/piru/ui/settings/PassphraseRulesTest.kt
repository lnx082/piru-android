package glass.kagerou.piru.ui.settings

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The two rules a new backup passphrase is held to.
 *
 * They are pure functions of two strings, and both are rules the user cannot
 * check for themselves until it is too late — a backup sealed with an eleven
 * character passphrase is a backup that is easier to brute-force, and one sealed
 * with a typo is a backup nobody can open. Pinning them here means a change to
 * the sheet has to move a test rather than a file.
 */
class PassphraseRulesTest {

    @Test
    fun `the floor is twelve characters`() {
        // Deliberately above the eight a login screen would take: a backup file is
        // offline-attackable forever, and 600,000 rounds of PBKDF2 only raises the
        // cost per guess on top of this.
        PASSPHRASE_MIN_LENGTH shouldBe 12
    }

    @Test
    fun `a passphrase under the floor is refused however it is confirmed`() {
        passphraseIsValid("", "") shouldBe false
        passphraseIsValid("short", "short") shouldBe false
        // Eleven characters: one short, and refused.
        passphraseIsValid("12345678901", "12345678901") shouldBe false
        passphraseIsValid("123456789012", "123456789012") shouldBe true
    }

    @Test
    fun `a mismatch is refused however long the passphrase is`() {
        passphraseIsValid("correct horse battery staple", "correct horse battery stapl") shouldBe false
        passphraseIsValid("correct horse battery staple", "correct horse battery staple") shouldBe true
    }

    @Test
    fun `the footer says which of the four states the entry is in`() {
        strengthFooter("", "") shouldContain "at least 12 characters"
        strengthFooter("short", "") shouldContain "Too short"
        strengthFooter("long enough one", "long enough two") shouldContain "don't match yet"
        strengthFooter("long enough one", "long enough one") shouldContain "Passphrases match"
    }

    @Test
    fun `the footer never congratulates and never scolds`() {
        // The house copy rules, applied to the one place in this feature that gives
        // feedback about user input: it may say which state the entry is in, and
        // nothing about the person who typed it.
        for (footer in listOf(
            strengthFooter("", ""),
            strengthFooter("short", "short"),
            strengthFooter("long enough one", "long enough two"),
            strengthFooter("long enough one", "long enough one"),
        )) {
            footer.lowercase().contains("harm reduction") shouldBe false
            footer.lowercase().contains("weak") shouldBe false
            footer.lowercase().contains("strong password") shouldBe false
        }
    }
}
