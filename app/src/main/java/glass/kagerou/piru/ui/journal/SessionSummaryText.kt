package glass.kagerou.piru.ui.journal

import glass.kagerou.piru.data.entity.SessionNoteEntity

/**
 * The session's one summary note — what the session was, in the user's words.
 *
 * ## Why this exists
 * `SessionEntity.note` has existed since the table did, `SessionNoteEntity.summaryFor(sessionId)` has existed beside
 * it, and **neither had a reader or a writer anywhere in the app**. A session summary could only ever arrive from an
 * imported file — the same shape as BUG #44: the column and the mirror were complete and no person could produce or
 * read one.
 *
 * ## The rules, which are what a test can hold
 * 1. **The summary is the session-kind note**, not any note. A check-in observation is a different thing, and
 *    treating the newest note of any kind as the summary would silently promote a mid-session check-in into the
 *    session's own description.
 * 2. **A blank edit clears the summary** rather than storing an empty string. The mirror's own doc says a summary
 *    row *is* the session's note, so an empty one is the *absence* of a note — and a stored `""` would make "has a
 *    summary" true for every session a user opened and cleared.
 * 3. **The row is reused when one exists**, because `summaryFor` returns at most one per session and the DAO has
 *    `update`. Writing a second is the duplicate the mirror's doc warns about.
 *
 * ## What is deliberately not here
 * Nothing about *where* a note's timestamp comes from. `SessionNotesSection` already writes `Date()` at the moment
 * of writing, which is right for a note about now, and the audit's "timestamp is hardcoded" item is about the
 * check-in path rather than this one.
 */
internal object SessionSummaryText {

    /** The longest summary kept, in characters. Long enough for a paragraph, short enough to stay a summary. */
    const val MAX_LENGTH: Int = 500

    /** The kind a summary note carries: the plain observation, which is what a session's own note is. */
    val KIND: SessionNoteEntity.Kind = SessionNoteEntity.Kind.OBSERVATION

    /**
     * The text to store for an edit, or null when the edit **clears** the summary.
     *
     * Trimmed, because a trailing newline from a text field is not content, and a summary of only whitespace is not
     * a summary. Truncated to [MAX_LENGTH] rather than refused, because a user pasting a long passage wants their
     * session saved and not an error.
     */
    fun toStore(edit: String): String? =
        edit.trim().take(MAX_LENGTH).takeIf { it.isNotEmpty() }

    /**
     * Whether the summary should be shown at all.
     *
     * False for a blank or absent note, so the card hides rather than drawing an empty field. A session with no
     * summary is the ordinary case, not an error — most sessions are not annotated.
     */
    fun shouldShow(note: String?): Boolean = !note.isNullOrBlank()
}
