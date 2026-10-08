package glass.kagerou.piru.ui.journal

import glass.kagerou.piru.data.entity.SessionNoteEntity
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The session summary's rules, which are the part of it a test can hold.
 *
 * ## Why this needed rules at all
 * `SessionEntity.note` and `SessionNoteEntity.summaryFor(sessionId)` have both existed since the table did, and
 * **neither had a reader or a writer anywhere in the app** — so the only sessions with a summary were ones whose
 * file came from iOS. That is BUG #44's shape again: the column and the mirror complete, and no person able to
 * produce or read one.
 *
 * ## The three rules, and what each one protects
 * 1. **Blank clears rather than storing an empty string.** The mirror's doc says a summary row *is* the session's
 *    note, so no row is what "no summary" means. A stored `""` would make "has a summary" true for every session a
 *    user opened and cleared — and the card would then draw an empty box forever.
 * 2. **The text is trimmed and bounded.** A trailing newline from a text field is not content; a pasted passage
 *    wants the session saved rather than an error, so it is truncated rather than refused.
 * 3. **The kind is the plain observation**, so a mid-session check-in is never mistaken for the session's own
 *    description.
 */
class SessionSummaryTextTest {

    /**
     * A blank edit clears.
     *
     * Asserted for every way a text field can be empty, including the whitespace-only case a user produces by
     * selecting all and typing a space — which must clear and not store `" "`.
     */
    @Test
    fun `a blank edit clears the summary`() {
        SessionSummaryText.toStore("") shouldBe null
        SessionSummaryText.toStore("   ") shouldBe null
        SessionSummaryText.toStore("\n\t ") shouldBe null
    }

    /** Real text is stored, trimmed. */
    @Test
    fun `text is stored trimmed`() {
        SessionSummaryText.toStore("  a good night  ") shouldBe "a good night"
        SessionSummaryText.toStore("line one\nline two") shouldBe "line one\nline two"
        SessionSummaryText.toStore("\nstarts with a newline") shouldBe "starts with a newline"
    }

    /**
     * A long passage is truncated, not refused.
     *
     * The choice matters: refusing would lose the rest of what the user wrote and make them do it again, and a
     * summary of a few hundred characters is already more than a summary. Truncation keeps their session saved.
     */
    @Test
    fun `a long passage is truncated to the limit`() {
        val long = "x".repeat(SessionSummaryText.MAX_LENGTH + 250)
        val stored = SessionSummaryText.toStore(long)
        stored shouldBe "x".repeat(SessionSummaryText.MAX_LENGTH)
        stored!!.length shouldBe SessionSummaryText.MAX_LENGTH
    }

    /** Exactly the limit is kept whole, so the boundary is not off by one. */
    @Test
    fun `exactly the limit is kept`() {
        val exact = "y".repeat(SessionSummaryText.MAX_LENGTH)
        SessionSummaryText.toStore(exact) shouldBe exact
        SessionSummaryText.toStore("y".repeat(SessionSummaryText.MAX_LENGTH - 1))!!.length shouldBe
            SessionSummaryText.MAX_LENGTH - 1
    }

    /**
     * The card shows itself only for a real note.
     *
     * The gate that keeps a session without a summary from drawing an empty field — which is the ordinary case,
     * because most sessions are not annotated.
     */
    @Test
    fun `the card hides for an absent or blank note`() {
        SessionSummaryText.shouldShow(null) shouldBe false
        SessionSummaryText.shouldShow("") shouldBe false
        SessionSummaryText.shouldShow("   ") shouldBe false
        SessionSummaryText.shouldShow("a sentence") shouldBe true
    }

    /**
     * The summary's kind is the plain observation.
     *
     * The rule that stops a check-in from being read as the session's own description. Asserted as the specific
     * value rather than "not a check-in", because the enum has several kinds and only one of them is the summary.
     */
    @Test
    fun `the summary is a plain observation`() {
        SessionSummaryText.KIND shouldBe SessionNoteEntity.Kind.OBSERVATION
        // And the enum really does have other kinds, so the choice is a choice.
        (SessionNoteEntity.Kind.entries.size > 1) shouldBe true
    }
}
