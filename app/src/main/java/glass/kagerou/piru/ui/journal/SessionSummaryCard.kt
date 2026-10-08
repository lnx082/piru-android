package glass.kagerou.piru.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.entity.SessionNoteEntity
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The session's summary: one note saying what the session was.
 *
 * ## Why it needs a card of its own
 * The note timeline below it holds every observation, check-in and follow-up. The summary is a different thing —
 * it is the sentence a reader sees first, and the one an export prints — and without a card it had no reader and no
 * writer anywhere in the app, so the only sessions with a summary were ones whose file came from iOS.
 *
 * ## Where the text is written
 * Twice, deliberately, and that is upstream's design rather than this port's: `SessionEntity.note` is the session's
 * own field and `SessionNoteEntity` carries the mirror that puts the summary in the note timeline. The mirror's
 * doc says a summary row *is* the session's note and that more than one means it got out of step — so the write
 * goes through `SessionSummaryText`'s rules (reuse the row, clear rather than store an empty string) and the two
 * are written together.
 */
@Composable
fun SessionSummaryCard(
    sessionId: UUID,
    modifier: Modifier = Modifier,
    onChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val scope = rememberCoroutineScope()

    var stored by remember(sessionId) { mutableStateOf<String?>(null) }
    var draft by remember(sessionId) { mutableStateOf("") }
    var editing by remember(sessionId) { mutableStateOf(false) }
    var reload by remember(sessionId) { mutableStateOf(0) }

    LaunchedEffect(sessionId, reload) {
        val note = withContext(Dispatchers.IO) {
            runCatching { app.database.sessionDao().byId(sessionId)?.note }.getOrNull()
        }
        stored = note
        // The draft is seeded from what is stored, so opening the editor does not lose the existing summary.
        if (!editing) draft = note.orEmpty()
    }

    val current = stored

    // Nothing stored and not editing: the card hides rather than drawing an empty field. Most sessions are not
    // annotated, and a permanent empty summary box is furniture.
    if (!SessionSummaryText.shouldShow(current) && !editing) {
        // The affordance to add one. Kept to a text button rather than a card, because a card that exists only to
        // offer an editor is a card about the app rather than about the session.
        TextButton(
            onClick = {
                draft = ""
                editing = true
            },
            modifier = modifier,
        ) {
            Text(stringResource(R.string.journal_session_add_summary))
        }
        return
    }

    PiruCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.journal_session_summary),
                style = MaterialTheme.typography.labelLarge,
            )

            if (editing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(stringResource(R.string.journal_session_summary_hint)) },
                    supportingText = {
                        Text(stringResource(R.string.journal_session_summary_limit, SessionSummaryText.MAX_LENGTH))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        onClick = {
                            editing = false
                            draft = current.orEmpty()
                        },
                    ) { Text(stringResource(R.string.shell_cancel)) }
                    TextButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching { write(app, sessionId, draft) }
                                }
                                editing = false
                                reload++
                                onChanged()
                            }
                        },
                    ) { Text(stringResource(R.string.shell_save)) }
                }
            } else {
                Text(
                    current.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        onClick = {
                            draft = current.orEmpty()
                            editing = true
                        },
                    ) { Text(stringResource(R.string.shell_edit)) }
                    TextButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching { write(app, sessionId, "") }
                                }
                                draft = ""
                                reload++
                                onChanged()
                            }
                        },
                    ) { Text(stringResource(R.string.shell_delete)) }
                }
            }
        }
    }
}

/**
 * Writes the summary to both places it lives.
 *
 * The session's own `note` and the mirrored `SessionNoteEntity`, in that order, so a reader that looks at either
 * sees the same sentence. Clearing removes the mirror row rather than blanking it: the mirror's doc says a row *is*
 * the session's note, so no row is what "no summary" means, and a blank row would make the timeline draw an empty
 * entry.
 */
private suspend fun write(app: PiruApplication, sessionId: UUID, edit: String) {
    val session = app.database.sessionDao().byId(sessionId) ?: return
    val text = SessionSummaryText.toStore(edit)
    val dao = app.database.sessionNoteDao()

    app.database.sessionDao().update(session.copy(note = text))

    val existing = dao.summaryFor(sessionId)
    when {
        text == null && existing != null -> dao.deleteById(existing.id)
        text == null -> Unit
        existing != null -> dao.update(existing.copy(text = text, timestamp = Date()))
        else -> dao.insert(
            SessionNoteEntity(
                timestamp = Date(),
                text = text,
                kindRaw = SessionSummaryText.KIND.wireValue,
                sessionId = sessionId,
            ),
        )
    }
}
