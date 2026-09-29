package glass.kagerou.piru.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import glass.kagerou.piru.R
import glass.kagerou.piru.data.export.DataExportImport

/**
 * What an import did, said the same way wherever it is reported.
 *
 * Two screens import — Data & Backup, and the onboarding step that exists so a
 * user arriving with an old journal can put it back before they start using the
 * app — and a user who sees both must not be told two different things about the
 * same file. Lifted out of `DataStorageScreen` when the onboarding step grew a
 * real importer behind its picker.
 *
 * ## The second half is the part that matters
 * Four sections of a Piru file have no table in this build. An import that said
 * only "your data was imported" would be telling the user their lab measurements
 * came across when they did not, so what could not be taken is named. An empty
 * count means nothing new was added, which is the normal answer for a re-import
 * and is said as one rather than as a failure.
 */
internal fun describeImportReport(context: Context, report: DataExportImport.ImportReport): String {
    val parts = buildList {
        if (report.entriesAdded > 0) {
            add(
                context.getString(
                    if (report.entriesAdded == 1) {
                        R.string.shell_data_import_entry_one
                    } else {
                        R.string.shell_data_import_entry_many
                    },
                    report.entriesAdded,
                ),
            )
        }
        if (report.sessionsAdded > 0) {
            add(context.getString(R.string.shell_data_import_sessions, report.sessionsAdded))
        }
        if (report.medsAdded > 0) {
            add(context.getString(R.string.shell_data_import_medications, report.medsAdded))
        }
        if (report.favoritesAdded > 0) {
            add(context.getString(R.string.shell_data_import_favorites, report.favoritesAdded))
        }
    }
    val head = if (parts.isEmpty()) {
        context.getString(R.string.shell_data_import_nothing_new)
    } else {
        context.getString(R.string.shell_data_import_added, parts.joinToString(", "))
    }
    if (report.unsupported.isEmpty()) return head
    return head + " " + report.unsupported.joinToString(" ") { section ->
        // The four section names are the file's own keys; anything else is a
        // section this build has never heard of and is named as it arrived.
        val what = when (section.name) {
            "labMeasurements" -> context.getString(R.string.shell_data_section_lab)
            "customUnits" -> context.getString(R.string.shell_data_section_units)
            "drinkPresets" -> context.getString(R.string.shell_data_section_drinks)
            "settings" -> context.getString(R.string.shell_data_section_settings)
            else -> section.name
        }
        if (section.rows > 0) {
            context.getString(R.string.shell_data_import_unsupported_rows, section.rows, what)
        } else {
            context.getString(R.string.shell_data_import_unsupported, what)
        }
    }
}

/**
 * Asks for the passphrase of a picked encrypted backup.
 *
 * One field, because there is nothing to confirm — the file already exists and the
 * passphrase either opens it or does not. Shared with the onboarding step for the
 * same reason as [describeImportReport]: an encrypted backup is a legitimate thing
 * to arrive with, and a user restoring one on first run should not have to finish
 * onboarding, find Data & Backup, and start again.
 */
@Composable
internal fun OpenBackupDialog(onDismiss: () -> Unit, onPassphrase: (String) -> Unit) {
    var passphrase by remember { mutableStateOf("") }

    // Read outside the lambda below so the field is not resolved during a click.
    val label = stringResource(R.string.shell_passphrase)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shell_data_open_backup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.shell_data_open_backup_body),
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(label) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Done,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPassphrase(passphrase) }, enabled = passphrase.isNotEmpty()) {
                Text(stringResource(R.string.shell_data_open))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.shell_cancel)) } },
    )
}
