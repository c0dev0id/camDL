package de.codevoid.camdl.probe

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Writes a rendered probe log to cache and hands back a share intent.
 *
 * Redaction is applied here, at the only point where the log leaves the device.
 */
object ProbeExport {

    fun render(log: ProbeLog): String = ProbeRenderer.render(log.snapshot(), log.redactor)

    fun shareIntent(context: Context, log: ProbeLog): Intent {
        val directory = File(context.cacheDir, "probe").apply { mkdirs() }
        // One file, overwritten: these are shared immediately and keeping a history would just
        // accumulate captures containing Wi-Fi credentials on disk.
        val file = File(directory, "camdl-probe.txt")
        file.writeText(render(log))

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.probe", file)

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
