package glass.kagerou.piru.ui.insights

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Offers a rendered image to another app.
 *
 * ## Why the flags are exactly this
 * Extracted from the PDF export, which already learned the hard way. The comment there records a crash the release
 * walk caught: with only `FLAG_GRANT_READ_URI_PERMISSION`, the chooser re-targeted the intent to the system print
 * spooler and the spooler was **denied** —
 * `SecurityException: Permission Denial: opening provider androidx.core.content.FileProvider from
 * ProcessRecord{…com.android.bips}` — so "Print" in the share sheet failed inside the system's own service.
 *
 * The grant authorises the URI **for the intent it is set on**; when a chooser re-targets, a target that
 * enumerates no clips gets nothing, and the clip is what carries the grant across that hop. So the clip data is
 * attached as well as the flag, and the flag goes on the chooser too.
 *
 * A PNG rather than a JPEG: the card is flat colour and text, which is where JPEG's artefacts are most visible, and
 * a shared card is often read as text.
 */
internal object ShareImage {

    /** Writes [bitmap] into the share cache and opens a chooser for it. */
    fun share(context: Context, bitmap: Bitmap, name: String, subject: String? = null) {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        // The extension is part of the file name because the URI's own mime type is derived from the provider's
        // declaration, and a receiver that re-derives it from the name would otherwise see no type at all.
        val file = File(directory, if (name.endsWith(".png")) name else "$name.png")
        FileOutputStream(file).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        shareFile(context, file, subject ?: name.substringBeforeLast('.'))
    }

    /** The same chooser for an already-written file. */
    fun shareFile(context: Context, file: File, subject: String) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // See the class doc: the clip is what carries the grant across a chooser hop.
            clipData = android.content.ClipData.newRawUri(file.name, uri)
        }
        val chooser = Intent.createChooser(send, null).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // A chooser needs the flag on a new task when started from a non-activity context, which the callers
            // here are — they are invoked from a coroutine inside the app.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
