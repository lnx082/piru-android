package glass.kagerou.piru.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import glass.kagerou.piru.BuildConfig
import glass.kagerou.piru.R
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.backup.BackupCrypto
import glass.kagerou.piru.data.export.DataExportImport
import glass.kagerou.piru.data.export.ExportFormat
import glass.kagerou.piru.data.recovery.StoreRecovery
import glass.kagerou.piru.notifications.PiruNotifications
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.theme.PiruTheme
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything about the user's data: what is on this device, export and import,
 * the encryption, the recoverable copies, and the destructive action at the
 * bottom.
 *
 * Ported from `Piru/Views/Settings/DataStorageView.swift` (818 lines) and
 * `DataStorageModel.swift` (297 lines).
 *
 * ## Sections, in the source's order
 * [LocalStorageSection] → [ExportImportSection] → [SubstanceDatabaseSection] →
 * [HowEncryptionWorksSection] → [RecoverableCopiesSection] →
 * [DeleteEverythingSection]. The order is not decoration: the destructive action
 * is last and the thing it destroys is described first.
 *
 * ## `ICloudBackupSection` is absent, and that is the upstream's own choice
 * The iOS file carries `ICloudBackupSection()` **commented out**, gated for App
 * Store review (Guideline 5.1.3(ii)) — the section offered a second, automatic
 * copy of the journal and the reviewer read the copy as a reason to require an
 * account. It is not ported here because it has nothing to port *to*: there is no
 * iCloud on Android, no `CKContainer`, and the whole feature is a private CloudKit
 * database plus an iCloud Keychain item that holds the envelope key. A surface
 * that looked like it — a toggle, a "last backup" row — would be a promise this
 * platform cannot keep.
 *
 * ## What is deliberately *not* offered
 * - **Automatic backup.** Upstream's is "encrypt and write to iCloud Drive when
 *   you leave the app", and the key lives in the iCloud Keychain. There is no
 *   Android counterpart and this screen says so rather than substituting a local
 *   scheduled export: a file in the app's own storage is not the thing a user
 *   means by "backup", and calling it one would be a false assurance about the
 *   only copy of a years-long log.
 * - **Device-key encrypted backups.** [BackupCrypto] recognizes the envelope and
 *   refuses it, because the key is in an Apple service. The export sheet says so
 *   in as many words; see [HowEncryptionWorksSection].
 *
 * ## SAF, not a storage permission
 * Export and import go through the Storage Access Framework —
 * `CreateDocument("application/json")` and `OpenDocument` — so the user picks the
 * destination and grants exactly that one file. `WRITE_EXTERNAL_STORAGE` would
 * add a permission that does nothing on API 30+, which is this build's floor, and
 * would put a scarier dialog in front of the same operation.
 */
@Composable
fun DataStorageScreen(modifier: Modifier = Modifier, onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val scope = rememberCoroutineScope()

    var counts by remember { mutableStateOf<LocalCounts?>(null) }
    var storeBytes by remember { mutableStateOf(0L) }
    var substanceCount by remember { mutableStateOf(0) }
    var recoverable by remember { mutableStateOf<List<StoreRecovery.RecoverableCopy>>(emptyList()) }
    var loadingRecoverable by remember { mutableStateOf(true) }

    var busy by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Notice?>(null) }

    // The plain export waiting for a destination, and the encrypted one. The
    // content is built *before* the picker opens, so a large library's encode runs
    // behind a spinner rather than after the user has already chosen a file.
    var pendingPlainExport by remember { mutableStateOf<String?>(null) }
    var pendingEncryptedExport by remember { mutableStateOf<ByteArray?>(null) }

    // A picked encrypted backup, until its passphrase opens it. Held as the raw
    // bytes so a wrong passphrase can be retried without asking for the file
    // again.
    var lockedBackup by remember { mutableStateOf<ByteArray?>(null) }
    var askingForBackupPassphrase by remember { mutableStateOf(false) }

    // The payload a restore is about to apply, and the copy it came from.
    var pendingPayload by remember { mutableStateOf<String?>(null) }
    var pendingCopy by remember { mutableStateOf<StoreRecovery.RecoverableCopy?>(null) }
    var askingStrategy by remember { mutableStateOf(false) }
    var askingDelete by remember { mutableStateOf(false) }
    var askingExportOptions by remember { mutableStateOf(false) }
    var askingCreatePassphrase by remember { mutableStateOf(false) }

    var reload by remember { mutableStateOf(0) }

    suspend fun reloadCounts() {
        val db = app.database
        val colors = db.substanceColorDao().all()
        counts = LocalCounts(
            entries = db.doseEntryDao().count().toInt(),
            sessions = db.sessionDao().all().size,
            meds = db.dailyDoseItemDao().all().size,
            quickLog = db.quickLogDoseDao().all().size,
            favorites = db.favoriteSubstanceDao().all().size,
            inventory = db.inventoryDao().all().size,
            customColors = colors.count { !it.usesDefault || it.isLegacy },
        )
        storeBytes = storeSize(context)
        substanceCount = runCatching { app.substanceCount() }.getOrDefault(0)
    }

    suspend fun reloadRecoverable() {
        loadingRecoverable = true
        recoverable = withContext(Dispatchers.IO) { StoreRecovery.forContext(context).recoverableStores() }
        loadingRecoverable = false
    }

    LaunchedEffect(reload) {
        reloadCounts()
        reloadRecoverable()
    }

    /**
     * Failures here are all "the file could not be written", and they are the one
     * thing this screen must never swallow: the user believes their data is now in
     * a file.
     */
    fun report(title: String, message: String) {
        notice = Notice(title, message)
    }

    // The contracts are remembered rather than built in the composition body:
    // `rememberLauncherForActivityResult` re-registers its launcher when the
    // contract's identity changes, and a contract built inline is a new object on
    // every recomposition. The same trap is called out in
    // `NotificationSettingsScreen`.
    val plainExportContract = remember { ActivityResultContracts.CreateDocument(MIME_JSON) }
    val encryptedExportContract = remember { ActivityResultContracts.CreateDocument(MIME_BINARY) }
    val importContract = remember { ActivityResultContracts.OpenDocument() }

    val plainExporter = rememberLauncherForActivityResult(plainExportContract) { uri ->
        val text = pendingPlainExport
        pendingPlainExport = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        scope.launch {
            val wrote = withContext(Dispatchers.IO) { writeText(context, uri, text) }
            if (!wrote) {
                report(
                    context.getString(R.string.shell_data_export_failed),
                    context.getString(R.string.shell_data_export_write_failed),
                )
            }
        }
    }

    val encryptedExporter = rememberLauncherForActivityResult(encryptedExportContract) { uri ->
        val bytes = pendingEncryptedExport
        pendingEncryptedExport = null
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        scope.launch {
            val wrote = withContext(Dispatchers.IO) { writeBytes(context, uri, bytes) }
            if (!wrote) {
                report(
                    context.getString(R.string.shell_data_export_failed),
                    context.getString(R.string.shell_data_export_write_failed),
                )
            }
        }
    }

    /** Classify, validate and hold a payload for the merge-or-replace choice. */
    suspend fun prepareRestoreFrom(text: String, from: StoreRecovery.RecoverableCopy?) {
        try {
            DataExportImport.validate(text)
        } catch (error: Throwable) {
            report(
                context.getString(R.string.shell_data_import_failed),
                DataExportImport.importErrorMessage(error),
            )
            return
        }
        pendingPayload = text
        pendingCopy = from
        askingStrategy = true
    }

    val importer = rememberLauncherForActivityResult(importContract) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            // Bounded, and refused *before* the read where the provider declares a size:
            // this used to be an unbounded `readBytes()`, so a mis-picked or corrupt file
            // was read into memory until the process died. iOS refuses over 256 MB up
            // front; so does `ImportFiles`.
            val bytes = when (
                val read = withContext(Dispatchers.IO) { ImportFiles.read(context, uri) }
            ) {
                is ImportFiles.Result.Bytes -> read.value
                ImportFiles.Result.TooLarge -> {
                    busy = false
                    report(
                        context.getString(R.string.shell_data_import_failed),
                        context.getString(R.string.shell_data_file_too_large),
                    )
                    return@launch
                }
                ImportFiles.Result.Unreadable -> {
                    busy = false
                    report(
                        context.getString(R.string.shell_data_import_failed),
                        context.getString(R.string.shell_data_file_unreadable),
                    )
                    return@launch
                }
            }
            busy = false
            val asText = bytes.toString(Charsets.UTF_8)
            // An encrypted backup is decided by `BackupCrypto`, not by the JSON
            // classifier: `classify` throws `Encrypted` for the same file, but the
            // envelope is the thing that knows whether this build can open it.
            val inspection = runCatching { BackupCrypto.inspect(bytes) }.getOrNull()
            when (inspection) {
                is BackupCrypto.Inspection.DeviceKey -> report(
                    context.getString(R.string.shell_data_device_key_title),
                    context.getString(R.string.shell_data_device_key_body),
                )
                is BackupCrypto.Inspection.Passphrase -> {
                    lockedBackup = bytes
                    askingForBackupPassphrase = true
                }
                null -> prepareRestoreFrom(text = asText, from = null)
            }
        }
    }

    fun applyRestore(replace: Boolean, payload: String) {
        scope.launch {
            busy = true
            try {
                // A replace takes a snapshot first, which is what makes the
                // destructive half reversible — upstream says the same in the
                // dialog's own message.
                if (replace) {
                    val snapshot = withContext(Dispatchers.IO) {
                        snapshotNow(app, "prerestore")
                    }
                    if (snapshot == null) {
                        report(
                            context.getString(R.string.shell_data_restore_failed),
                            context.getString(R.string.shell_data_snapshot_failed),
                        )
                        return@launch
                    }
                    DataExportImport.deleteAll(app.database)
                }
                val report = DataExportImport.importJSON(
                    text = payload,
                    db = app.database,
                    catalog = runCatching { app.catalog() }.getOrNull(),
                )
                app.refreshLiveStores()
                onChanged()
                reload++
                notice = Notice(
                    context.getString(
                        if (replace) R.string.shell_data_restore_complete else R.string.shell_data_import_complete,
                    ),
                    describeImportReport(context, report),
                )
            } catch (error: Throwable) {
                report(
                    context.getString(R.string.shell_data_restore_failed),
                    DataExportImport.importErrorMessage(error),
                )
            } finally {
                busy = false
                pendingPayload = null
                pendingCopy = null
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Text(
                stringResource(R.string.shell_settings_data_backup),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        item { LocalStorageSection(counts, storeBytes) }

        item {
            ExportImportSection(
                generating = generating || busy,
                onExport = { askingExportOptions = true },
                onImport = {
                    // Three types rather than one: a Piru file is `application/json`
                    // on most providers, nothing at all on some, and an encrypted
                    // backup is neither. The picker is allowed to show everything
                    // because `classify` and `BackupCrypto` decide what the file
                    // is — which is knowledge the user came here without.
                    importer.launch(arrayOf(MIME_JSON, MIME_BINARY, MIME_ANY))
                },
            )
        }

        item { SubstanceDatabaseSection(substanceCount) }

        item { HowEncryptionWorksSection() }

        item { RecoverableCopiesSection(recoverable, loadingRecoverable) { copy ->
            // Nothing is written until the user confirms in the dialog below.
            scope.launch {
                busy = true
                val text = withContext(Dispatchers.IO) { StoreRecovery.forContext(context).read(copy) }
                busy = false
                if (text == null) {
                    report(
                        context.getString(R.string.shell_data_restore_failed),
                        context.getString(R.string.shell_data_copy_unreadable),
                    )
                    return@launch
                }
                prepareRestoreFrom(text, copy)
            }
        } }

        item {
            DeleteEverythingSection(enabled = !busy && !generating) { askingDelete = true }
        }
    }

    // MARK: - Dialogs

    if (askingExportOptions) {
        AlertDialog(
            onDismissRequest = { askingExportOptions = false },
            title = { Text(stringResource(R.string.shell_data_export_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ExportOption(
                        stringResource(R.string.shell_data_export_piru),
                        stringResource(R.string.shell_data_export_piru_detail),
                    ) {
                        askingExportOptions = false
                        scope.launch {
                            generating = true
                            val text = runCatching {
                                withContext(Dispatchers.Default) {
                                    DataExportImport.exportJSON(
                                        ExportFormat.PIRU,
                                        app.database,
                                        appVersion(),
                                    )
                                }
                            }.getOrNull()
                            generating = false
                            if (text == null) {
                                report(
                                    context.getString(R.string.shell_data_export_failed),
                                    context.getString(R.string.shell_data_export_build_failed),
                                )
                                return@launch
                            }
                            pendingPlainExport = text
                            plainExporter.launch(DataExportImport.exportFilename())
                        }
                    }
                    ExportOption(
                        stringResource(R.string.shell_data_export_psywiki),
                        stringResource(R.string.shell_data_export_psywiki_detail),
                    ) {
                        askingExportOptions = false
                        scope.launch {
                            generating = true
                            val text = runCatching {
                                withContext(Dispatchers.Default) {
                                    DataExportImport.exportJSON(
                                        ExportFormat.PSY_LOG,
                                        app.database,
                                        appVersion(),
                                    )
                                }
                            }.getOrNull()
                            generating = false
                            if (text == null) {
                                report(
                                    context.getString(R.string.shell_data_export_failed),
                                    context.getString(R.string.shell_data_export_build_failed),
                                )
                                return@launch
                            }
                            pendingPlainExport = text
                            plainExporter.launch(DataExportImport.exportFilename())
                        }
                    }
                    ExportOption(
                        stringResource(R.string.shell_data_export_encrypted),
                        stringResource(R.string.shell_data_export_encrypted_detail),
                    ) {
                        askingExportOptions = false
                        askingCreatePassphrase = true
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { askingExportOptions = false }) {
                    Text(stringResource(R.string.shell_cancel))
                }
            },
        )
    }

    if (askingCreatePassphrase) {
        PassphraseSheet(
            onDismiss = { askingCreatePassphrase = false },
            onSubmit = { passphrase ->
                askingCreatePassphrase = false
                scope.launch {
                    generating = true
                    val bytes = runCatching {
                        withContext(Dispatchers.Default) {
                            val plaintext = DataExportImport.exportJSON(
                                ExportFormat.PIRU,
                                app.database,
                                appVersion(),
                            ).toByteArray(Charsets.UTF_8)
                            BackupCrypto.encrypt(plaintext, passphrase, appVersion())
                        }
                    }.getOrNull()
                    generating = false
                    if (bytes == null) {
                        report(
                            context.getString(R.string.shell_data_export_failed),
                            context.getString(R.string.shell_data_export_encrypted_build_failed),
                        )
                        return@launch
                    }
                    pendingEncryptedExport = bytes
                    encryptedExporter.launch(BackupCrypto.AUTOMATIC_FILENAME)
                }
            },
        )
    }

    if (askingForBackupPassphrase) {
        val bytes = lockedBackup
        OpenBackupDialog(
            onDismiss = {
                askingForBackupPassphrase = false
                lockedBackup = null
            },
            onPassphrase = { passphrase ->
                askingForBackupPassphrase = false
                lockedBackup = null
                if (bytes == null) return@OpenBackupDialog
                scope.launch {
                    busy = true
                    val plaintext = runCatching {
                        withContext(Dispatchers.Default) {
                            BackupCrypto.decrypt(bytes, passphrase).toString(Charsets.UTF_8)
                        }
                    }.getOrElse { error ->
                        busy = false
                        val failure = (error as? BackupCrypto.BackupException)?.failure
                        report(
                            context.getString(R.string.shell_data_restore_failed),
                            context.getString(
                                when (failure) {
                                    // One message for both, because GCM cannot tell a
                                    // wrong passphrase from a tampered file.
                                    BackupCrypto.Failure.DECRYPTION_FAILED ->
                                        R.string.shell_data_passphrase_wrong
                                    BackupCrypto.Failure.DEVICE_KEY_UNAVAILABLE ->
                                        R.string.shell_data_device_key_unsupported
                                    else -> R.string.shell_data_not_a_backup
                                },
                            ),
                        )
                        return@launch
                    }
                    busy = false
                    prepareRestoreFrom(plaintext, null)
                }
            },
        )
    }

    if (askingStrategy) {
        val copy = pendingCopy
        AlertDialog(
            onDismissRequest = { askingStrategy = false; pendingPayload = null; pendingCopy = null },
            title = { Text(stringResource(R.string.shell_data_restore_title)) },
            text = {
                // Two sentences, and only the first is conditional: the copy's own
                // date, when there is a copy. Joined rather than concatenated so the
                // two stay separately translatable.
                val prefix = copy?.timestamp?.let {
                    stringResource(R.string.shell_data_restore_replace_prefix, whenText(it))
                }
                Text(
                    listOfNotNull(prefix, stringResource(R.string.shell_data_restore_strategy))
                        .joinToString(" "),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askingStrategy = false
                    pendingPayload?.let { applyRestore(replace = false, payload = it) }
                }) { Text(stringResource(R.string.shell_data_merge)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    askingStrategy = false
                    pendingPayload?.let { applyRestore(replace = true, payload = it) }
                }) { Text(stringResource(R.string.shell_data_replace)) }
            },
        )
    }

    if (askingDelete) {
        AlertDialog(
            onDismissRequest = { askingDelete = false },
            title = { Text(stringResource(R.string.shell_data_delete_title)) },
            text = {
                Text(stringResource(R.string.shell_data_delete_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    askingDelete = false
                    scope.launch { deleteEverything(app, onDeleted = { reload++ }, onMessage = ::report) }
                }) { Text(stringResource(R.string.shell_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { askingDelete = false }) { Text(stringResource(R.string.shell_cancel)) }
            },
        )
    }

    notice?.let { current ->
        AlertDialog(
            onDismissRequest = { notice = null },
            title = { Text(current.title) },
            text = { Text(current.message) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text(stringResource(R.string.shell_ok)) } },
        )
    }
}

// MARK: - On This Device

/** What this device holds, as the first section counts it. */
private data class LocalCounts(
    val entries: Int,
    val sessions: Int,
    val meds: Int,
    val quickLog: Int,
    val favorites: Int,
    val inventory: Int,
    val customColors: Int,
)

private data class Notice(val title: String, val message: String)

@Composable
private fun LocalStorageSection(counts: LocalCounts?, storeBytes: Long) {
    val context = LocalContext.current
    SectionCard(
        title = stringResource(R.string.shell_data_on_device),
        footer = stringResource(R.string.shell_data_on_device_footer),
    ) {
        CountRow(stringResource(R.string.shell_data_count_entries), counts?.entries)
        CountRow(stringResource(R.string.shell_data_count_sessions), counts?.sessions)
        CountRow(stringResource(R.string.shell_data_count_meds), counts?.meds)
        CountRow(stringResource(R.string.shell_data_count_quicklog), counts?.quickLog)
        CountRow(stringResource(R.string.shell_data_count_favorites), counts?.favorites)
        CountRow(stringResource(R.string.shell_data_count_inventory), counts?.inventory)
        CountRow(stringResource(R.string.shell_data_count_custom_colours), counts?.customColors)
        CountRow(
            stringResource(R.string.shell_data_count_store_size),
            formatted = byteString(context, storeBytes),
        )
    }
}

@Composable
private fun CountRow(title: String, count: Int? = null, formatted: String? = null) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            formatted ?: count?.toString().orEmpty(),
            style = MaterialTheme.typography.bodyLarge,
            color = PiruTheme.colors.secondaryLabel,
        )
    }
}

// MARK: - Export & Import

/**
 * The three ways out and the one way in.
 *
 * One import row for every format, because the file decides: `classify` names the
 * importer and `BackupCrypto` names the envelope, so the user does not have to
 * know which kind of file they are holding — which is exactly the knowledge they
 * came here without.
 */
@Composable
private fun ExportImportSection(
    generating: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.shell_data_export_import),
        footer = stringResource(R.string.shell_data_export_import_footer),
    ) {
        ActionRow(
            title = stringResource(R.string.shell_data_export_row),
            subtitle = stringResource(R.string.shell_data_export_row_detail),
            showSpinner = generating,
            onClick = onExport,
        )
        ActionRow(
            title = stringResource(R.string.shell_data_import_row),
            subtitle = stringResource(R.string.shell_data_import_row_detail),
            enabled = !generating,
            onClick = onImport,
        )
    }
}

// MARK: - Substance database

/**
 * A row that reports the catalog's size without pretending to open a screen.
 *
 * Upstream pushes `SubstanceDatabaseView`, which is where the source-priority
 * editor lives. This build browses the catalog from the Library tab and has no
 * source-priority screen, so the row names the tab and the footer says what is
 * missing rather than half-describing a screen that does not exist. A card that
 * looks tappable and is not is worse than a plain sentence.
 */
@Composable
private fun SubstanceDatabaseSection(substanceCount: Int) {
    SectionCard(
        title = stringResource(R.string.shell_data_substance_db),
        footer = stringResource(R.string.shell_data_substance_db_footer),
    ) {
        CountRow(stringResource(R.string.shell_data_substances_in_catalog), count = substanceCount)
        Text(
            stringResource(R.string.shell_data_browse_library),
            style = MaterialTheme.typography.bodyMedium,
            color = PiruTheme.colors.secondaryLabel,
        )
    }
}

// MARK: - How encryption works

@Composable
private fun HowEncryptionWorksSection() {
    SectionCard(title = stringResource(R.string.shell_data_how_encryption)) {
        HowItWorksRow(
            stringResource(R.string.shell_data_enc_strong),
            stringResource(R.string.shell_data_enc_strong_detail),
        )
        HowItWorksRow(
            stringResource(R.string.shell_data_enc_passphrase),
            stringResource(R.string.shell_data_enc_passphrase_detail),
        )
        HowItWorksRow(
            stringResource(R.string.shell_data_enc_snapshot),
            stringResource(R.string.shell_data_enc_snapshot_detail),
        )
        HowItWorksRow(
            stringResource(R.string.shell_data_enc_device_key),
            stringResource(R.string.shell_data_enc_device_key_detail),
        )
    }
}

@Composable
private fun HowItWorksRow(title: String, detail: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = PiruTheme.colors.secondaryLabel)
    }
}

// MARK: - Recoverable copies

/**
 * Copies set aside before something destructive.
 *
 * A copy marked [StoreRecovery.RecoverableCopy.isIntentional] is one the **user**
 * caused. It is listed here so they can roll back a delete they regret, and it is
 * why the recovery machinery never resurrects one by itself.
 */
@Composable
private fun RecoverableCopiesSection(
    stores: List<StoreRecovery.RecoverableCopy>,
    loading: Boolean,
    onSelect: (StoreRecovery.RecoverableCopy) -> Unit,
) {
    val context = LocalContext.current
    SectionCard(
        title = stringResource(R.string.shell_data_recoverable),
        footer = stringResource(R.string.shell_data_recoverable_footer),
    ) {
        when {
            loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator()
                Text(
                    stringResource(R.string.shell_data_checking_copies),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }

            stores.isEmpty() -> Text(
                stringResource(R.string.shell_data_no_copies),
                style = MaterialTheme.typography.bodyMedium,
                color = PiruTheme.colors.secondaryLabel,
            )

            else -> for (store in stores) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (store.isIntentional) {
                                StoreRecovery.reasonTitle(store.reason)
                            } else {
                                StoreRecovery.reasonTitle(store.reason) +
                                    context.getString(R.string.shell_data_automatic_suffix)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            copySubtitle(context, store),
                            style = MaterialTheme.typography.bodySmall,
                            color = PiruTheme.colors.secondaryLabel,
                        )
                    }
                    if (store.rowCount > 0) {
                        TextButton(onClick = { onSelect(store) }) {
                            Text(stringResource(R.string.shell_restore))
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Delete

@Composable
private fun DeleteEverythingSection(enabled: Boolean, onDelete: () -> Unit) {
    SectionCard(
        title = stringResource(R.string.shell_data_danger_zone),
        footer = stringResource(R.string.shell_data_danger_zone_footer),
    ) {
        TextButton(onClick = onDelete, enabled = enabled) {
            Text(stringResource(R.string.shell_data_delete_title), color = PiruTheme.colors.dangerText)
        }
    }
}

// MARK: - Rows

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    showSpinner: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    PiruCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = if (enabled) onClick else null,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = PiruTheme.colors.secondaryLabel)
            }
            if (showSpinner) CircularProgressIndicator()
        }
    }
}

@Composable
private fun ExportOption(title: String, subtitle: String, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onClick) {
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, footer: String? = null, content: @Composable () -> Unit) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            content()
            if (footer != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Text(
                    footer,
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
    }
}

// MARK: - Passphrase

/**
 * The minimum length for a **new** passphrase.
 *
 * A backup file is offline-attackable forever, so the floor is deliberately above
 * the eight characters a login screen would accept; the 600,000-round PBKDF2
 * raises the cost per guess on top of it.
 */
internal const val PASSPHRASE_MIN_LENGTH: Int = 12

/**
 * Whether a new passphrase may be used.
 *
 * Both halves matter and neither is a formality: the length is the only thing
 * standing between a stolen file and its contents, and the confirmation is what
 * stops a typo from becoming a backup nobody can open. Split out from the sheet so
 * it can be tested without a composition.
 */
internal fun passphraseIsValid(passphrase: String, confirmation: String): Boolean =
    passphrase.length >= PASSPHRASE_MIN_LENGTH && passphrase == confirmation

/**
 * Sets the passphrase that seals a new encrypted backup.
 *
 * Entered twice with live length and match feedback, which an alert has no room
 * for. Ported from `PassphraseSheet`.
 */
@Composable
private fun PassphraseSheet(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val valid = passphraseIsValid(passphrase, confirmation)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shell_data_set_passphrase)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.shell_passphrase)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Next,
                    ),
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text(stringResource(R.string.shell_passphrase_confirm)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Done,
                    ),
                )
                Text(
                    strengthFooter(passphrase, confirmation),
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        passphrase.isNotEmpty() && passphrase.length < PASSPHRASE_MIN_LENGTH ->
                            PiruTheme.colors.cautionText
                        valid -> PiruTheme.colors.successText
                        else -> PiruTheme.colors.secondaryLabel
                    },
                )
                HorizontalDivider()
                Text(
                    stringResource(R.string.shell_data_passphrase_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.cautionText,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(passphrase) }, enabled = valid) {
                Text(stringResource(R.string.shell_data_encrypt))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.shell_cancel)) } },
    )
}

/**
 * The sheet's own feedback line.
 *
 * Four states in the source's order, and the wording is the source's: a short
 * passphrase is told it is short, a mismatched pair is told it does not match
 * yet, and a good one is confirmed rather than merely not-complained-about.
 *
 * `@Composable` rather than a pure function of the two strings: the copy is a
 * resource now, and `PassphraseRulesTest` pins [passphraseFeedback] — the rule —
 * rather than a sentence, so a wording change moves a resource and not a test.
 */
@Composable
private fun strengthFooter(passphrase: String, confirmation: String): String =
    when (passphraseFeedback(passphrase, confirmation)) {
        PassphraseFeedback.EMPTY -> stringResource(R.string.shell_passphrase_hint, PASSPHRASE_MIN_LENGTH)
        PassphraseFeedback.TOO_SHORT -> stringResource(R.string.shell_passphrase_too_short, PASSPHRASE_MIN_LENGTH)
        PassphraseFeedback.MISMATCH -> stringResource(R.string.shell_passphrase_mismatch)
        PassphraseFeedback.MATCH -> stringResource(R.string.shell_passphrase_match)
    }

/** Which of the four states a new passphrase entry is in. */
internal enum class PassphraseFeedback { EMPTY, TOO_SHORT, MISMATCH, MATCH }

/**
 * The rule behind [strengthFooter], as a pure function.
 *
 * Split out from the sentence so it can be tested without a composition: what has
 * to hold is that the four inputs land in four different states and that a valid
 * pair is the only one that reports a match.
 */
internal fun passphraseFeedback(passphrase: String, confirmation: String): PassphraseFeedback = when {
    passphrase.isEmpty() -> PassphraseFeedback.EMPTY
    passphrase.length < PASSPHRASE_MIN_LENGTH -> PassphraseFeedback.TOO_SHORT
    passphrase != confirmation -> PassphraseFeedback.MISMATCH
    else -> PassphraseFeedback.MATCH
}

/**
 * Asks for the passphrase of a picked encrypted backup.
 *
 * `OpenBackupDialog` and `describeImportReport` live in `ImportCopy.kt`: the
 * onboarding import step grew a real importer behind its picker, and a second
 * screen that reads the same files must describe them in the same words.
 */

// MARK: - The delete flow

/**
 * Delete Everything, in the source's order.
 *
 * `DataStorageModel.deleteAllData` is a sequence, not a set, and the order is
 * copied step by step. Every step that has **no counterpart in this build** is
 * named in [DELETE_STEPS_WITHOUT_COUNTERPART] rather than quietly dropped: a
 * reader comparing the two has to be able to see what was left out and why.
 */
private suspend fun deleteEverything(
    app: PiruApplication,
    onDeleted: () -> Unit,
    onMessage: (String, String) -> Unit,
) {
    val context = app.applicationContext
    var cleanupErrors = mutableListOf<String>()

    // 1. `DoseLogService.cancelPendingBookkeeping()` — no counterpart.
    // 2. The rows.
    try {
        DataExportImport.deleteAll(app.database)
    } catch (error: Throwable) {
        onMessage(
            context.getString(R.string.shell_data_delete_failed),
            error.message ?: context.getString(R.string.shell_data_delete_failed_body),
        )
        return
    }
    // 3. `JournalResetGeneration.advance()` — no counterpart.
    // 4. `DoseLogService.changed()` — the tolerance cache's invalidation, which
    //    here is dropping the memoized replay and grouping.
    app.invalidateRepositoryCaches()
    // 5. `PhoneSyncCoordinator.journalWasDeleted()` — iOS-only.
    // 6. `ActiveSessionManager.clearSession()` — no counterpart.
    // 7. `DayResolveCache.clear()` — no counterpart.
    // 8. The notification choices.
    app.notificationPreferences().resetAfterDeletion()
    // 9. `CustomSubstanceStore.resetAfterDeletion()` — the store *is* the table,
    //    which step 2 already emptied.
    // 10. `CustomUnitStore.configure(container:)` — no table, no counterpart.
    // 11. `SearchHistoryStore.clear()` — no counterpart.
    // 12. `QuickLogManager.suppressedRecents = []` — no counterpart.

    // 13-14. The per-piece cleanup upstream runs in a loop and reports together.
    for (cleanup in listOf<Pair<String, suspend () -> Unit>>(
        "the profile" to { app.profile().resetAfterDeletion() },
        "the recovery copies" to { StoreRecovery.forContext(context).deleteRecoveryCopies().getOrThrow() },
        // `JournalDeriveCache.clear()`, `TimelineStripCache.clear()` and
        // `BodyLevelsTrailCache.clear()` are the three other entries here. This
        // build keeps none of those caches — every reading is derived on the
        // frame that draws it — so there is nothing to clear.
    )) {
        runCatching { cleanup.second() }.onFailure { cleanupErrors += "${cleanup.first}: ${it.message}" }
    }

    // 15. Every pending and delivered notification.
    runCatching {
        val scheduled = PiruNotifications.scheduledIdentifiers(context)
        if (scheduled.isNotEmpty()) PiruNotifications.cancel(context, scheduled)
        NotificationManagerCompat.from(context).cancelAll()
    }.onFailure { cleanupErrors += "notifications: ${it.message}" }
    // 16. `LiveActivityManager.deleteJournalActivities()` — no live activities.
    // 17. `BackupManager.disableAndRemoveBackup()` — there is no automatic backup
    //     to disable or remove.
    // 18. `WidgetCenter.reloadAllTimelines()` — there are no widgets.
    // 19. The listing, refreshed by the caller.

    onDeleted()
    if (cleanupErrors.isNotEmpty()) {
        onMessage(context.getString(R.string.shell_data_delete_failed), cleanupErrors.joinToString("\n"))
    }
}

/**
 * The steps of the upstream delete that this build has no counterpart for.
 *
 * Written out because the alternative — deleting the lines and saying nothing —
 * makes the port look like it does more than it does. Each is a real behaviour on
 * iOS and each is absent for a reason, not by omission.
 */
private val DELETE_STEPS_WITHOUT_COUNTERPART: List<String> = listOf(
    "DoseLogService.cancelPendingBookkeeping() — there is no dose-log service to book-keep for",
    "JournalResetGeneration.advance() — the store-swap machinery it guards does not exist here",
    "PhoneSyncCoordinator.journalWasDeleted() — iOS-only, no paired-phone mirror",
    "ActiveSessionManager.clearSession() — no active-session manager in this build",
    "DayResolveCache.clear() — no day-resolve cache; days are derived per draw",
    "CustomSubstanceStore.resetAfterDeletion() — the store is the table, already emptied",
    "CustomUnitStore.configure(container:) — custom units have no table in this build",
    "SearchHistoryStore.clear() — no search history store",
    "QuickLogManager.suppressedRecents = [] — no suppressed-recents set",
    "JournalDeriveCache / TimelineStripCache / BodyLevelsTrailCache — none of the three exists",
    "LiveActivityManager.deleteJournalActivities() — Android has no Live Activities",
    "BackupManager.disableAndRemoveBackup() — there is no automatic backup to disable",
    "WidgetCenter.reloadAllTimelines() — this build ships no widgets",
)

// MARK: - Helpers

private const val MIME_JSON = "application/json"
private const val MIME_BINARY = "application/octet-stream"
private const val MIME_ANY = "*/*"

private fun appVersion(): String = "Piru ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

/**
 * What an import did: `describeImportReport`, in `ImportCopy.kt`.
 *
 * Shared with the onboarding import step, which reads the same files and has to
 * say the same things about them.
 */

/** A snapshot of the store as it stands, returning the file it landed in. */
private suspend fun snapshotNow(app: PiruApplication, reason: String): File? {
    val text = runCatching {
        DataExportImport.exportJSON(ExportFormat.PIRU, app.database, appVersion())
    }.getOrNull() ?: return null
    return StoreRecovery.forContext(app.applicationContext).snapshotStore(reason, text)
}

/**
 * The store's bytes on disk.
 *
 * Room's database plus its `-wal` and `-shm` siblings, which is the whole of what
 * the user's data occupies. Upstream's `canonicalStoreBytes()` measures the same
 * three files for the same reason: a size that left out the write-ahead log would
 * under-report by however much has been written since the last checkpoint.
 */
private fun storeSize(context: android.content.Context): Long {
    val base = context.getDatabasePath("piru.db")
    return listOf("", "-wal", "-shm").sumOf { suffix ->
        File(base.path + suffix).takeIf { it.exists() }?.length() ?: 0L
    }
}

private fun readBytes(context: android.content.Context, uri: Uri): ByteArray? =
    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()

private fun writeText(context: android.content.Context, uri: Uri, text: String): Boolean =
    writeBytes(context, uri, text.toByteArray(Charsets.UTF_8))

private fun writeBytes(context: android.content.Context, uri: Uri, bytes: ByteArray): Boolean =
    runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null
    }.getOrDefault(false)

/**
 * A copy's subtitle: rows, size, and when.
 *
 * Upstream joins the three with `·` and says "unreadable" rather than `-1 records`
 * for a copy it could not open, which is the difference between a file the user
 * should send to the developer and one they should not bother with.
 */
private fun copySubtitle(context: android.content.Context, copy: StoreRecovery.RecoverableCopy): String {
    val rows = if (copy.rowCount > 0) {
        context.getString(R.string.shell_data_records, copy.rowCount)
    } else {
        context.getString(R.string.shell_data_unreadable)
    }
    val when_ = copy.timestamp?.let { whenText(it) } ?: context.getString(R.string.shell_data_unknown_date)
    return listOf(rows, byteString(context, copy.bytes), when_).joinToString(" · ")
}

private fun whenText(at: Instant): String = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .withLocale(Locale.getDefault())
    .withZone(ZoneId.systemDefault())
    .format(at)

/** Upstream's `ByteCountFormatter.string(fromByteCount:countStyle:.file)`. */
private fun byteString(context: android.content.Context, bytes: Long): String {
    if (bytes < 1_000) return context.getString(R.string.shell_data_bytes, bytes)
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1_000
    var index = 0
    while (value >= 1_000 && index < units.lastIndex) {
        value /= 1_000
        index++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[index])
}
