package de.codevoid.camdl.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import de.codevoid.camdl.BuildConfig
import de.codevoid.camdl.probe.ProbeLog
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Fetches the rolling `dev` pre-release and hands a newer APK to the system installer.
 *
 * Uses a plain OkHttp client with no network bound to it, so it travels over whatever the
 * phone normally uses. That only works because camera sockets are bound individually rather
 * than with `bindProcessToNetwork` - had the process been pinned to the camera's access point,
 * this would be trying to reach GitHub through a camera.
 *
 * Nothing is installed silently: the APK is handed to the system package installer, which
 * prompts. Android additionally refuses to install over an app signed with a different key, so
 * a substituted download cannot replace this app - it can only fail to install.
 */
class Updater(
    private val context: Context,
    private val probe: ProbeLog,
    private val repository: String = REPOSITORY,
) {
    private val http = OkHttpClient.Builder()
        .callTimeout(2, TimeUnit.MINUTES)
        .build()

    /** The published build, when it differs from the installed one. */
    fun check(): ReleaseAsset? {
        val url = "https://api.github.com/repos/$repository/releases/tags/$TAG"
        probe.note(TAG_LOG, "checking", mapOf("installed" to BuildConfig.VERSION_NAME))

        val body = http.newCall(Request.Builder().url(url).header("Accept", ACCEPT).build()).execute()
            .use { response ->
                if (!response.isSuccessful) {
                    throw IOException("GitHub answered ${response.code} for the $TAG release")
                }
                response.body?.string() ?: throw IOException("GitHub returned an empty response")
            }

        val assets = JSONObject(body).optJSONArray("assets")?.let { array ->
            (0 until array.length()).map { index ->
                val asset = array.getJSONObject(index)
                ReleaseAsset(asset.getString("name"), asset.getString("browser_download_url"))
            }
        }.orEmpty()

        val available = ReleaseVersion.pick(BuildConfig.VERSION_NAME, assets)
        probe.note(
            TAG_LOG,
            if (available == null) "already current" else "update available",
            mapOf("assets" to assets.joinToString(" ") { it.name }),
        )
        return available
    }

    /** Downloads [asset] and returns the file. Replaces any earlier download. */
    fun download(asset: ReleaseAsset): File {
        probe.note(TAG_LOG, "downloading", mapOf("asset" to asset.name))

        val directory = File(context.filesDir, "updates").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val file = File(directory, asset.name)

        http.newCall(Request.Builder().url(asset.url).build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("downloading ${asset.name} answered ${response.code}")
            }
            val body = response.body ?: throw IOException("${asset.name} came back empty")
            file.outputStream().use { out -> body.byteStream().copyTo(out) }
        }

        probe.note(TAG_LOG, "downloaded", mapOf("bytes" to file.length().toString()))
        return file
    }

    /**
     * True when the user has already allowed this app to install packages. Without it the
     * installer intent opens and immediately refuses, which reads as the update being broken.
     */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Sends the user to the settings screen where installing from camDL can be allowed. */
    fun allowInstallsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.probe", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    companion object {
        const val REPOSITORY = "c0dev0id/camDL"
        private const val TAG = "dev"
        private const val TAG_LOG = "update"
        private const val ACCEPT = "application/vnd.github+json"
    }
}
