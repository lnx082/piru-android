package glass.kagerou.piru.ui.onboarding

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.R
import glass.kagerou.piru.data.backup.BackupCrypto
import glass.kagerou.piru.data.export.DataExportImport
import glass.kagerou.piru.ui.settings.OpenBackupDialog
import glass.kagerou.piru.ui.settings.describeImportReport
import glass.kagerou.piru.ui.theme.PiruTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bring an existing journal in.
 *
 * Ported from `OnboardingImportStep.swift` (94 lines).
 *
 * ## The step the file used to describe as impossible
 * This screen used to raise the system picker, read the bytes, classify the file
 * and tell the user the import layer was not ported. That stopped being true when
 * `DataExportImport` landed in `:core:data` — the classifier it wrote by hand is
 * that type's `classify`, and the same type's `importJSON` is what the Data &
 * Backup screen has been calling since. So this calls it too, and the copy says
 * what actually happens.
 *
 * ## What it does with each of the four shapes
 * - **Piru native, PsyLog, the early `doseEntries` dump** — imported. Nothing here
 *   chooses between them, because [DataExportImport.classify] reads the top-level
 *   keys and routes; the picker offers `application/json` and the file decides.
 * - **An encrypted envelope** — [BackupCrypto] recognises it and this asks for the
 *   passphrase in place. Onboarding is the one screen where bouncing the user to
 *   Data & Backup would mean finishing the flow, finding the screen, and starting
 *   again, and a user arriving with their whole journal on a new phone is exactly
 *   the user that hits this.
 * - **A device-key envelope** — refused by name, because the key lives in an Apple
 *   service and there is nothing on this platform that can open it.
 *
 * ## Merge, not replace
 * Onboarding imports into a store the user has not written to yet, so a merge is a
 * restore. There is no wipe here and no merge-or-replace dialog: a destructive
 * choice offered before the user has seen the app is a choice about data they
 * cannot picture yet.
 */
@Composable
fun OnboardingImportStep(nav: OnboardingNav) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as PiruApplication }
    val scope = rememberCoroutineScope()

    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var busy by remember { mutableStateOf(false) }
    // A picked encrypted backup, held as raw bytes so a wrong passphrase can be
    // retried without asking for the file again.
    var lockedBackup by remember { mutableStateOf<ByteArray?>(null) }

    suspend fun import(text: String) {
        busy = true
        outcome = try {
            DataExportImport.validate(text)
            val report = DataExportImport.importJSON(
                text = text,
                db = app.database,
                catalog = runCatching { app.catalog() }.getOrNull(),
            )
            // Through the application, not this screen's scope: the row counts and
            // every memoized replay are keyed off what was just written, and this
            // step advances the moment the user taps Continue.
            app.refreshLiveStores()
            Outcome.Imported(describeImportReport(context, report))
        } catch (error: Throwable) {
            Outcome.Failed(DataExportImport.importErrorMessage(error))
        }
        busy = false
    }

    // OpenDocument rather than GetContent: an export arrives from a file manager
    // or a download, not from another app publishing it as a stream, and
    // OpenDocument is the one that can reach every provider on the device. The
    // MIME filter is the source's `allowedContentTypes: [.json]`.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // A null URI is the user backing out of the picker, which is a decision
        // rather than a failure and gets no notice.
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val bytes = readBytes(context, uri)
            busy = false
            if (bytes == null) {
                outcome = Outcome.Failed(context.getString(R.string.shell_onboarding_import_read_failed))
                return@launch
            }
            // The envelope decides first: `classify` would also throw `Encrypted`
            // for this file, but only `BackupCrypto` knows whether this build can
            // open it.
            when (val inspection = runCatching { BackupCrypto.inspect(bytes) }.getOrNull()) {
                is BackupCrypto.Inspection.DeviceKey ->
                    outcome = Outcome.Failed(context.getString(R.string.shell_onboarding_import_device_key))

                is BackupCrypto.Inspection.Passphrase -> {
                    lockedBackup = bytes
                    outcome = null
                }

                null -> import(bytes.toString(Charsets.UTF_8))
            }
        }
    }

    OnboardingLayout(
        title = stringResource(R.string.shell_onboarding_import_title),
        subtitle = stringResource(R.string.shell_onboarding_import_subtitle),
        hero = { OnboardingIconHero(Icons.Filled.KeyboardArrowDown) },
        mid = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OnboardingGroupedCard {
                    OnboardingBulletRow(
                        icon = Icons.Filled.KeyboardArrowDown,
                        title = stringResource(R.string.shell_onboarding_import_piru_title),
                        detail = stringResource(R.string.shell_onboarding_import_piru_detail),
                    )
                    OnboardingBulletRow(
                        icon = Icons.Filled.Create,
                        title = stringResource(R.string.shell_onboarding_import_psylog_title),
                        detail = stringResource(R.string.shell_onboarding_import_psylog_detail),
                    )
                }
                // One line, and it is not decoration: a merge is what this does, and
                // a user who has already used the app on this phone deserves to know
                // that importing will not have replaced anything.
                OnboardingNote(
                    icon = Icons.Filled.Info,
                    text = stringResource(R.string.shell_onboarding_import_merges),
                )
                outcome?.let { ImportResult(it) }
            }
        },
    ) {
        OnboardingPillButton(
            title = if (busy) {
                stringResource(R.string.shell_onboarding_reading)
            } else {
                stringResource(R.string.shell_onboarding_import_action)
            },
            enabled = !busy,
            onClick = { picker.launch(arrayOf("application/json")) },
        )
        OnboardingPillButton(
            title = stringResource(R.string.shell_start_fresh),
            prominence = Prominence.NEUTRAL,
            onClick = nav.advance,
        )
    }

    lockedBackup?.let { bytes ->
        OpenBackupDialog(
            onDismiss = { lockedBackup = null },
            onPassphrase = { passphrase ->
                lockedBackup = null
                scope.launch {
                    busy = true
                    val plaintext = runCatching {
                        withContext(Dispatchers.Default) {
                            BackupCrypto.decrypt(bytes, passphrase).toString(Charsets.UTF_8)
                        }
                    }.getOrElse { error ->
                        busy = false
                        // One message for a wrong passphrase and a tampered file
                        // alike, because GCM cannot tell them apart.
                        outcome = Outcome.Failed(
                            context.getString(
                                when ((error as? BackupCrypto.BackupException)?.failure) {
                                    BackupCrypto.Failure.DEVICE_KEY_UNAVAILABLE ->
                                        R.string.shell_data_device_key_unsupported
                                    else -> R.string.shell_data_passphrase_wrong
                                },
                            ),
                        )
                        return@launch
                    }
                    busy = false
                    import(plaintext)
                }
            },
        )
    }
}

/** What the last attempt did, so the step can say it rather than appear inert. */
private sealed interface Outcome {
    /** A file was read into the store; the sentence is the row counts. */
    data class Imported(val summary: String) : Outcome

    /** Nothing was written, and the reason is [message]. */
    data class Failed(val message: String) : Outcome
}

/**
 * The result line: what landed, or why nothing did.
 *
 * Tone follows the step's own rule — a failure that the user can act on says what
 * to do, and one they cannot says what is true rather than wearing a warning the
 * step has no remedy for.
 */
@Composable
private fun ImportResult(outcome: Outcome) {
    val (icon, tint, text) = when (outcome) {
        is Outcome.Imported -> Triple(
            Icons.Filled.CheckCircle,
            PiruTheme.colors.successText,
            outcome.summary,
        )

        is Outcome.Failed -> Triple(Icons.Filled.Warning, PiruTheme.colors.dangerText, outcome.message)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

/** The picked document's bytes, or null when the provider could not be read. */
private suspend fun readBytes(context: android.content.Context, uri: Uri): ByteArray? =
    withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
    }
