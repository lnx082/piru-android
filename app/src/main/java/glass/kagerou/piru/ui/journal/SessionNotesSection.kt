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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.entity.SessionNoteEntity
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlinx.coroutines.launch

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

    // A session with no notes and nothing scheduled contributes nothing to the
    // screen — an empty card headed "Notes" would be furniture.
    if (notes.isEmpty() && !composing && checkInOffsetMinutes.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Notes & check-ins", style = MaterialTheme.typography.titleSmall)

        if (checkInOffsetMinutes.isNotEmpty()) {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Scheduled check-ins", style = MaterialTheme.typography.labelLarge)
                    Text(
                        checkInOffsetMinutes.joinToString(" · ") { offset ->
                            val h = offset / 60
                            val m = offset % 60
                            when {
                                m == 0 -> "+${h}h"
                                h == 0 -> "+${m}m"
                                else -> "+${h}h${m}m"
                            }
                        },
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
                    ) {
                        Text(
                            note.timestamp.toInstant().atZone(zone)
                                .format(DateTimeFormatter.ofPattern("HH:mm")),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            when (SessionNoteEntity.Kind.fromWire(note.kindRaw)) {
                                SessionNoteEntity.Kind.CHECK_IN -> "Check-in"
                                SessionNoteEntity.Kind.SUMMARY -> "Summary"
                                SessionNoteEntity.Kind.OBSERVATION -> ""
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                    if (note.text.isNotBlank()) {
                        Text(note.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    val readings = buildList {
                        note.shulgin?.let { add(shulginLabel(it)) }
                        note.mood?.let { add("mood ${signed(it)}") }
                        note.energy?.let { add("energy ${signed(it)}") }
                        note.social?.let { add("social ${signed(it)}") }
                        note.worked?.let { add(if (it > 0) "worked" else "didn't work") }
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
                        label = { Text("What's happening") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Shulgin",
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
                    Scale("Mood", mood, -3..3) { mood = it }
                    Scale("Energy", energy, -3..3) { energy = it }
                    Scale("Social", social, -3..3) { social = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = worked == 1,
                            onClick = { worked = if (worked == 1) null else 1 },
                            label = { Text("Did its job") },
                        )
                        FilterChip(
                            selected = worked == -1,
                            onClick = { worked = if (worked == -1) null else -1 },
                            label = { Text("Didn't") },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { composing = false }) { Text("Cancel") }
                        Button(onClick = { write(SessionNoteEntity.Kind.OBSERVATION) }) { Text("Save note") }
                        // A check-in is the same record with a different kind: it
                        // says *why* it exists — a scheduled prompt, not a thought
                        // the user had — and the difference is what the timeline
                        // colours and what an export states.
                        TextButton(onClick = { write(SessionNoteEntity.Kind.CHECK_IN) }) { Text("Check in") }
                    }
                }
            }
        } else {
            TextButton(onClick = { composing = true }) { Text("Add a note") }
        }
    }
}

/** A `-n…+n` scale as chips, with the neutral middle label spelled out. */
@Composable
private fun Scale(label: String, value: Int?, range: IntRange, onChange: (Int?) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = PiruTheme.colors.secondaryLabel)
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
