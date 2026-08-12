package dev.droiddoodle.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Shares generated text as a file.
 *
 * Not as an intent extra: a trace holds every prompt verbatim, so a session's
 * export is comfortably into the megabytes, and an extra that large throws
 * TransactionTooLargeException. Writing to cache and handing over a content URI
 * has no such ceiling.
 */
internal fun shareText(context: Context, content: String, fileName: String) {
    val dir = File(context.cacheDir, "export").apply { mkdirs() }
    val file = File(dir, fileName)
    file.writeText(content)

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(intent, "Export trace")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
