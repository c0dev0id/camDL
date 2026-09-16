package de.codevoid.camdl.probe

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider

/**
 * Shares the probe log file.
 *
 * The file is shared as it stands rather than re-rendered: entries were written already
 * redacted, so what is on disk is what is safe to send - including entries from runs that
 * ended before this process started.
 */
object ProbeExport {

    fun shareIntent(context: Context, probeFile: ProbeFile): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.probe", probeFile.forSharing())

        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "camDL probe log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share probe log",
        )
    }
}
