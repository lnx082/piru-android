package glass.kagerou.piru.ui.onboarding

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.R
import glass.kagerou.piru.ui.theme.PiruTheme
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Bring an existing journal in.
 *
 * Ported from `OnboardingImportStep.swift` (94 lines).
 *
 * ## The picker is real; the importer behind it is not ported
 * The source calls `DataExportImport.importJSON(data:context:)`, which
 * auto-detects a Piru export, a PsyLog export, an early `doseEntries` dump, or
 * an encrypted envelope. **That type does not exist in this port.** `:core:data`
 * carries `BackupCrypto` and the entities an import would write into, but no
 * reader that turns a JSON document into rows, and no `classify`.
 *
 * So the step does the part it can do honestly: it raises the system file picker
 * over `application/json` (the Android counterpart of `.fileImporter`), reads
 * the bytes through the content resolver, and tells the user what the file
 * appears to be and what is missing. Two things it deliberately does **not** do:
 * it does not render an "Import complete" state it could never reach, and it
 * does not stay quiet about the gap until after the user has picked a file — the
 * note is on screen before they tap. Where the import lands: a `DataExportImport`
 * equivalent in `:core:data`, called where [readAndClassify] returns.
 */
@Composable
fun OnboardingImportStep(nav: OnboardingNav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    // OpenDocument rather than GetContent: an export arrives from a file manager
    // or a download, not from another app publishing it as a stream, and
    // OpenDocument is the one that can reach every provider on the device. The
    // MIME filter is the source's `allowedContentTypes: [.json]`.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // A null URI is the user backing out of the picker, which is a decision
        // rather than a failure and gets no notice.
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            reading = true
            notice = readAndClassify(context, uri)
            reading = false
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
                OnboardingNote(
                    icon = Icons.Filled.Info,
                    text = stringResource(R.string.shell_onboarding_import_not_ported),
                )
                val result = notice
                if (result != null) {
                    Text(
                        result,
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.dangerText,
                    )
                }
            }
        },
    ) {
        OnboardingPillButton(
            title = if (reading) {
                stringResource(R.string.shell_onboarding_reading)
            } else {
                stringResource(R.string.shell_onboarding_import_action)
            },
            enabled = !reading,
            onClick = { picker.launch(arrayOf("application/json")) },
        )
        OnboardingPillButton(
            title = stringResource(R.string.shell_start_fresh),
            prominence = Prominence.NEUTRAL,
            onClick = nav.advance,
        )
    }
}

/**
 * Read the picked document and say what it is.
 *
 * A port of the *classification* half of the source's `DataExportImport`, driven
 * off the same top-level keys — `sealed` + `kind` is an encrypted envelope,
 * `piruExportVersion` is a Piru export, `experiences` is PsyLog, `doseEntries` is
 * the early legacy shape — because that is the part that needs no schema and
 * tells the user whether they picked the right file. Reading it into the store is
 * the part this build cannot do, and the notice says so rather than failing with
 * something generic.
 *
 * `org.json` rather than a serialization library: it is on the platform, and this
 * needs one object's key set, not a typed model of a document nobody has ported.
 *
 * Takes a `Context` rather than being `@Composable`: it runs inside the picker's
 * callback, where there is no composition to read a resource from. The keys it
 * tests (`sealed`, `piruExportVersion`, `experiences`, `doseEntries`) are wire
 * values and stay as they are.
 */
private suspend fun readAndClassify(context: Context, uri: Uri): String {
    val bytes = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
    } ?: return context.getString(R.string.shell_onboarding_import_read_failed)

    val root = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
        ?: return context.getString(R.string.shell_onboarding_import_not_json)

    return when {
        root.has("sealed") && root.has("kind") ->
            context.getString(R.string.shell_onboarding_import_encrypted)
        root.has("piruExportVersion") ->
            context.getString(R.string.shell_onboarding_import_piru_export)
        root.has("experiences") ->
            context.getString(R.string.shell_onboarding_import_psylog_export)
        root.has("doseEntries") ->
            context.getString(R.string.shell_onboarding_import_legacy_export)
        else ->
            context.getString(R.string.shell_onboarding_import_unrecognised)
    }
}
