package glass.kagerou.piru.ui.settings

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The two rules a new backup passphrase is held to.
 *
 * They are pure functions of two strings, and both are rules the user cannot
 * check for themselves until it is too late — a backup sealed with an eleven
 * character passphrase is a backup that is easier to brute-force, and one sealed
 * with a typo is a backup nobody can open. Pinning them here means a change to
 * the sheet has to move a test rather than a file.
 *
 * The sheet's feedback **sentence** is a resource now (`strings_shell.xml`,
 * `shell_passphrase_*`) and cannot be read from a JVM test with no Android
 * runtime — so what is pinned here is [passphraseFeedback], the state behind the
 * sentence. The wording itself is subject to the house copy rules at review,
 * which is where every other string in the app is checked too.
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
    fun `the footer reports which of the four states the entry is in`() {
        passphraseFeedback("", "") shouldBe PassphraseFeedback.EMPTY
        passphraseFeedback("short", "") shouldBe PassphraseFeedback.TOO_SHORT
        // Eleven characters, so the length rule wins over the mismatch rule.
        passphraseFeedback("long enough one", "long enough two") shouldBe PassphraseFeedback.MISMATCH
        passphraseFeedback("long enough one", "long enough one") shouldBe PassphraseFeedback.MATCH
    }

    @Test
    fun `only a long, matching pair reports a match`() {
        // The state the sheet draws in green is the state a backup can actually be
        // sealed with, so nothing else may reach it.
        val refused = listOf(
            "" to "",
            "short" to "short",
            "12345678901" to "12345678901",
            "short" to "",
            "correct horse battery staple" to "correct horse battery stapl",
        )
        for ((passphrase, confirmation) in refused) {
            (passphraseFeedback(passphrase, confirmation) == PassphraseFeedback.MATCH) shouldBe false
        }
        passphraseFeedback("correct horse battery staple", "correct horse battery staple") shouldBe
            PassphraseFeedback.MATCH
    }
}
