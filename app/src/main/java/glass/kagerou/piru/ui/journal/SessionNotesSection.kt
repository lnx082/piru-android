package glass.kagerou.piru.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.annotation.StringRes
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.entity.SessionNoteEntity
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment

/**
 * A session's notes and check-ins.
 *
 * Ported from `Journal/Session/Notes/` — seven files and about 1,485 lines, of
 * which this draws the two things that matter: the note timeline, and the ability
 * to add one. The scheduled check-in *notifications* are a platform concern
 * (upstream's `SessionNotificationScheduler`, which needs `WorkManager` here) and
 * are not part of this; what is here is the note a check-in produces, and the
 * session's own offsets shown so the user can see what it was set to.
 *
 * ## The scales are kept separate on purpose
 * Mood, energy, social and "did it work" are different questions, and upstream's
 * model says so in the field docs — on an empathogen, *wanting company* and
 * *feeling good* diverge, which is exactly the axis people report. Collapsing them
 * into one "how was it" number would throw away the thing the note was for.
 *
 * The Shulgin scale is the one with a published vocabulary (PiHKAL, 1991), so it
 * is the one that prints as words rather than as a number.
 */
@Composable
fun SessionNotesSection(
    sessionId: java.util.UUID,
    checkInOffsetMinutes: List<Int>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }

    var notes by remember(sessionId) { mutableStateOf<List<SessionNoteEntity>>(emptyList()) }
    var composing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var shulgin by remember { mutableStateOf<Int?>(null) }
    var mood by remember { mutableStateOf<Int?>(null) }
    var energy by remember { mutableStateOf<Int?>(null) }
    var social by remember { mutableStateOf<Int?>(null) }
    var worked by remember { mutableStateOf<Int?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(sessionId, reload) {
        notes = app.database.sessionNoteDao().forSession(sessionId)
    }

    fun write(kind: SessionNoteEntity.Kind) {
        scope.launch {
            app.database.sessionNoteDao().insert(
                SessionNoteEntity(
                    timestamp = Date(),
                    text = text.trim(),
                    shulgin = shulgin,
                    mood = mood,
                    energy = energy,
                    social = social,
                    worked = worked,
                    kindRaw = kind.wireValue,
                    sessionId = sessionId,
                ),
            )
            text = ""
            shulgin = null
            mood = null
            energy = null
            social = null
            worked = null
            composing = false
            reload++
        }
    }

    /**
     * Move a note's timestamp by [minutes].
     *
     * The note's time is when it was written, and a note is usually about something that happened
     * earlier — so nudging it is the correction the user actually needs, and `SessionNoteDao.update`
     * has supported it since the table existed with no screen calling it.
     */
    fun shiftNote(note: SessionNoteEntity, minutes: Long) {
        scope.launch {
            app.database.sessionNoteDao().update(
                note.copy(
                    timestamp = Date(note.timestamp.toInstant().plusSeconds(minutes * 60).toEpochMilli()),
                ),
            )
            reload++
        }
    }

    /** Remove a note. `deleteById` has existed the whole time; this is its first caller. */
    fun deleteNote(note: SessionNoteEntity) {
        scope.launch {
            app.database.sessionNoteDao().deleteById(note.id)
            reload++
        }
    }

    // A session with no notes and nothing scheduled contributes nothing to the
    // screen — an empty card headed "Notes" would be furniture.
    if (notes.isEmpty() && !composing && checkInOffsetMinutes.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.journal_session_notes_heading), style = MaterialTheme.typography.titleSmall)

        if (checkInOffsetMinutes.isNotEmpty()) {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.journal_session_scheduled_check_ins),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        // `map` is inline and can hold a composable read; a
                        // `stringResource` inside `joinToString`'s transform
                        // does not compile, so the labels are resolved first.
                        checkInOffsetMinutes
                            .map { offset ->
                                val h = offset / 60
                                val m = offset % 60
                                when {
                                    m == 0 -> stringResource(R.string.journal_offset_hours, h)
                                    h == 0 -> stringResource(R.string.journal_offset_minutes, m)
                                    else -> stringResource(R.string.journal_offset_hours_minutes, h, m)
                                }
                            }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        for (note in notes) {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                note.timestamp.toInstant().atZone(zone)
                                    .format(DateTimeFormatter.ofPattern("HH:mm")),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            // A note's time was fixed at the moment it was written, and the note
                            // is often about something that happened earlier — the come-up, a
                            // wave that passed an hour ago. `SessionNoteDao.update` existed with
                            // no UI caller, so a mistimed note could not be corrected and the
                            // only remedy was to delete it and retype.
                            IconButton(
                                onClick = { shiftNote(note, -15) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Text(
                                    "\u2212",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = PiruTheme.colors.secondaryLabel,
                                )
                            }
                            Text(
                                stringResource(R.string.journal_note_nudge),
                                style = MaterialTheme.typography.labelSmall,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                            IconButton(
                                onClick = { shiftNote(note, 15) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Text(
                                    "+",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = PiruTheme.colors.secondaryLabel,
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (SessionNoteEntity.Kind.fromWire(note.kindRaw)) {
                                    SessionNoteEntity.Kind.CHECK_IN -> stringResource(R.string.common_check_in)
                                    SessionNoteEntity.Kind.SUMMARY -> stringResource(R.string.common_summary)
                                    // An observation is the default kind and wears no label.
                                    SessionNoteEntity.Kind.OBSERVATION -> ""
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                            // Delete, which the DAO has always supported and no screen offered:
                            // notes were write-only, so a note typed by mistake stayed for good.
                            IconButton(onClick = { deleteNote(note) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.common_delete),
                                    tint = PiruTheme.colors.secondaryLabel,
                                )
                            }
                        }
                    }
                    if (note.text.isNotBlank()) {
                        Text(note.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    val readings = ArrayList<String>(5)
                    // The Shulgin rating is the published vocabulary (±, +, ++…) and is
                    // not translated; the three scales around it are.
                    note.shulgin?.let { readings.add(shulginLabel(it)) }
                    note.mood?.let { readings.add(stringResource(R.string.journal_note_mood, signed(it))) }
                    note.energy?.let { readings.add(stringResource(R.string.journal_note_energy, signed(it))) }
                    note.social?.let { readings.add(stringResource(R.string.journal_note_social, signed(it))) }
                    note.worked?.let {
                        readings.add(
                            stringResource(
                                if (it > 0) R.string.journal_note_worked else R.string.journal_note_didnt_work,
                            ),
                        )
                    }
                    if (readings.isNotEmpty()) {
                        Text(
                            readings.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                }
            }
        }

        if (composing) {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(stringResource(R.string.journal_note_prompt)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.common_shulgin_scale),
                        style = MaterialTheme.typography.labelSmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (value in 0..4) {
                            FilterChip(
                                selected = shulgin == value,
                                onClick = { shulgin = if (shulgin == value) null else value },
                                label = { Text(shulginLabel(value)) },
                            )
                        }
                    }
                    Scale(R.string.journal_scale_mood, mood, -3..3) { mood = it }
                    Scale(R.string.journal_scale_energy, energy, -3..3) { energy = it }
                    Scale(R.string.journal_scale_social, social, -3..3) { social = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = worked == 1,
                            onClick = { worked = if (worked == 1) null else 1 },
                            label = { Text(stringResource(R.string.journal_note_did_its_job)) },
                        )
                        FilterChip(
                            selected = worked == -1,
                            onClick = { worked = if (worked == -1) null else -1 },
                            label = { Text(stringResource(R.string.journal_note_didnt)) },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { composing = false }) {
                            Text(stringResource(R.string.common_cancel))
                        }
                        Button(onClick = { write(SessionNoteEntity.Kind.OBSERVATION) }) {
                            Text(stringResource(R.string.journal_note_save))
                        }
                        // A check-in is the same record with a different kind: it
                        // says *why* it exists — a scheduled prompt, not a thought
                        // the user had — and the difference is what the timeline
                        // colours and what an export states.
                        TextButton(onClick = { write(SessionNoteEntity.Kind.CHECK_IN) }) {
                            Text(stringResource(R.string.journal_note_check_in))
                        }
                    }
                }
            }
        } else {
            TextButton(onClick = { composing = true }) {
                Text(stringResource(R.string.journal_note_add))
            }
        }
    }
}

/**
 * A `-n…+n` scale as chips, with the neutral middle label spelled out.
 *
 * The heading is a resource id rather than text because the caller's label is
 * the only translated part — the chip values are signed numbers.
 */
@Composable
private fun Scale(@StringRes labelRes: Int, value: Int?, range: IntRange, onChange: (Int?) -> Unit) {
    Text(
        stringResource(labelRes),
        style = MaterialTheme.typography.labelSmall,
        color = PiruTheme.colors.secondaryLabel,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (option in range) {
            FilterChip(
                selected = value == option,
                onClick = { onChange(if (value == option) null else option) },
                label = { Text(if (option > 0) "+$option" else if (option == 0) "0" else "$option") },
            )
        }
    }
}

/**
 * The Shulgin rating, as PiHKAL writes it.
 *
 * `0…4` maps to ±, +, ++, +++, ++++ — the published vocabulary rather than a bare
 * number, because "3" means nothing to a reader and "+++" does.
 */
private fun shulginLabel(value: Int): String = when (value) {
    0 -> "±"
    1 -> "+"
    2 -> "++"
    3 -> "+++"
    4 -> "++++"
    else -> "$value"
}

private fun signed(value: Int): String = if (value > 0) "+$value" else "$value"
