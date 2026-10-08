package glass.kagerou.piru.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.entity.CustomSubstanceRecordEntity
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The user's own substances, and the edits they have made to the catalogue's.
 *
 * ## Why this exists at all
 * `custom_substance` has had a row, a DAO, an export and an import since the port began, and **no UI**. So the table
 * was reachable only by importing a file made elsewhere — which is the same shape as BUG #44: the data model and
 * the persistence were complete, and no person could use them.
 *
 * ## What an entry can be
 * Two different things, and the distinction is upstream's:
 *
 * - An **override** of a substance the catalogue has, matched by name. The catalogue's own data is kept and the
 *   entry's fields replace theirs. This is what makes a personal duration profile for 2-MMC possible — the case
 *   upstream names, where neither the curated source nor TripSit ships one.
 * - A **net-new** substance the user invented. Every reference section on its page reads the bundled database by
 *   name, finds nothing and hides, which is the honest outcome: the app does not know this compound.
 *
 * ## Why the list is its own section rather than folded into the library
 * Upstream's contract, kept: **collection-level APIs stay library-only.** A reader expects the library grid to be
 * the reference, so folding personal entries into it would surprise them. The overlay applies to *lookups*, which
 * is where a correction has to take effect.
 */
@Composable
fun CustomSubstancesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf<List<CustomSubstanceRecordEntity>>(emptyList()) }
    var editing by remember { mutableStateOf<CustomSubstanceDraft?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        entries = withContext(Dispatchers.IO) {
            runCatching { app.database.customSubstanceDao().all() }
                .getOrDefault(emptyList())
                .sortedBy { it.name.lowercase() }
        }
    }

    val draft = editing
    if (draft != null) {
        CustomSubstanceEditor(
            draft = draft,
            modifier = modifier,
            onCancel = { editing = null },
            onSave = { saved ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val dao = app.database.customSubstanceDao()
                            if (saved.rowId == 0L) dao.insert(saved) else dao.update(saved)
                        }
                    }
                    editing = null
                    reload++
                }
            },
            onDelete = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        // A per-row delete. The earlier version cleared the table and re-inserted the survivors,
                        // which gave every other entry a new `row_id` to remove one.
                        runCatching { app.database.customSubstanceDao().deleteById(draft.id) }
                    }
                    editing = null
                    reload++
                }
            },
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.shell_custom_substances_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.shell_custom_substances_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        item {
            TextButton(onClick = { editing = CustomSubstanceDraft.blank() }) {
                Text(stringResource(R.string.shell_custom_substances_add))
            }
        }

        if (entries.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.shell_custom_substances_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        items(entries, key = { it.id.toString() }) { entry ->
            PiruCard(modifier = Modifier.fillMaxWidth(), onClick = { editing = CustomSubstanceDraft.of(entry) }) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        // The label when set, because that is what the user asked to see — the note about why is
                        // on the editor, where it matters.
                        entry.displayName?.takeIf { it.isNotBlank() } ?: entry.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (!entry.displayName.isNullOrBlank()) {
                        Text(
                            stringResource(R.string.shell_custom_substances_logged_as, entry.name),
                            style = MaterialTheme.typography.labelSmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                    Text(
                        // What the entry actually changes, so the list says which of the four things is set
                        // rather than looking identical for a relabel and a full PK profile.
                        CustomSubstanceSummary.describe(
                            hasDoses = entry.doses != null,
                            hasDuration = entry.duration != null,
                            hasHalfLife = entry.halfLifeMinutes != null,
                            hasLabel = !entry.displayName.isNullOrBlank(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }
    }
}

/**
 * What an entry changes, as one line.
 *
 * A function rather than four `if`s in the layout so the wording is testable, and because the list's whole job is
 * to say which of the four optional fields an entry uses — a list where a relabel and a full PK profile look the
 * same is a list a user has to open every row to read.
 */
internal object CustomSubstanceSummary {
    fun describe(
        hasDoses: Boolean,
        hasDuration: Boolean,
        hasHalfLife: Boolean,
        hasLabel: Boolean,
    ): String {
        val parts = buildList {
            if (hasLabel) add("relabelled")
            if (hasDoses) add("own dose ladder")
            if (hasDuration) add("own duration")
            if (hasHalfLife) add("own half-life")
        }
        return when {
            parts.isEmpty() -> "name only"
            else -> parts.joinToString(", ")
        }
    }
}

/** The editable state of one entry, kept out of the entity so a cancel discards it. */
internal data class CustomSubstanceDraft(
    val rowId: Long = 0L,
    val id: java.util.UUID = java.util.UUID.randomUUID(),
    val name: String = "",
    val label: String = "",
    val notes: String = "",
    val halfLifeText: String = "",
) {
    companion object {
        fun blank() = CustomSubstanceDraft()

        fun of(entry: CustomSubstanceRecordEntity) = CustomSubstanceDraft(
            rowId = entry.rowId,
            id = entry.id,
            name = entry.name,
            label = entry.displayName.orEmpty(),
            notes = entry.notes,
            halfLifeText = entry.halfLifeMinutes?.let { trim(it) }.orEmpty(),
        )

        private fun trim(value: Double): String =
            if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    }

    /**
     * The entity this draft produces, or null when it is not saveable.
     *
     * `name` is the identity — it is what a logged dose matches on — so a blank one is refused rather than
     * defaulted: an entry with no name could never be found again, and it would show in the list as an empty row.
     */
    fun toEntity(existing: CustomSubstanceRecordEntity?): CustomSubstanceRecordEntity? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val hours = halfLifeText.trim().toDoubleOrNull()
        val base = existing ?: CustomSubstanceRecordEntity()
        return base.copy(
            rowId = rowId,
            id = id,
            name = trimmed,
            displayName = label.trim().takeIf { it.isNotEmpty() },
            notes = notes,
            // A blank or unparseable field clears the override rather than writing a zero, which is what "I did not
            // give a half-life" means.
            halfLifeMinutes = hours?.takeIf { it > 0 },
        )
    }
}

/** The editor for one entry. */
@Composable
private fun CustomSubstanceEditor(
    draft: CustomSubstanceDraft,
    modifier: Modifier,
    onCancel: () -> Unit,
    onSave: (CustomSubstanceRecordEntity) -> Unit,
    onDelete: () -> Unit,
) {
    var model by remember(draft.id) { mutableStateOf(draft) }
    val app = LocalContext.current.applicationContext as PiruApplication
    var existing by remember(draft.id) { mutableStateOf<CustomSubstanceRecordEntity?>(null) }

    LaunchedEffect(draft.id) {
        existing = withContext(Dispatchers.IO) {
            runCatching { app.database.customSubstanceDao().byId(draft.id) }.getOrNull()
        }
    }

    val saveable = model.toEntity(existing)

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.shell_custom_substances_editor),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        OutlinedTextField(
            value = model.name,
            onValueChange = { model = model.copy(name = it) },
            label = { Text(stringResource(R.string.shell_custom_substances_name)) },
            supportingText = { Text(stringResource(R.string.shell_custom_substances_name_note)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model.label,
            onValueChange = { model = model.copy(label = it) },
            label = { Text(stringResource(R.string.shell_custom_substances_label)) },
            supportingText = { Text(stringResource(R.string.shell_custom_substances_label_note)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model.halfLifeText,
            onValueChange = { model = model.copy(halfLifeText = it) },
            label = { Text(stringResource(R.string.shell_custom_substances_half_life)) },
            supportingText = { Text(stringResource(R.string.shell_custom_substances_half_life_note)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model.notes,
            onValueChange = { model = model.copy(notes = it) },
            label = { Text(stringResource(R.string.shell_custom_substances_notes)) },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.shell_cancel)) }
            TextButton(
                // Disabled rather than silently doing nothing: a Save that appears to work and discards the entry
                // is worse than one that is visibly unavailable.
                enabled = saveable != null,
                onClick = { saveable?.let(onSave) },
            ) { Text(stringResource(R.string.shell_save)) }
            if (draft.rowId != 0L) {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.shell_delete)) }
            }
        }
    }
}
