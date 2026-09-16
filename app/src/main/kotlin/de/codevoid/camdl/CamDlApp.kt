package de.codevoid.camdl

import android.app.Application
import android.content.Context
import de.codevoid.camdl.probe.ProbeEvent
import de.codevoid.camdl.probe.ProbeFile
import de.codevoid.camdl.probe.ProbeLog
import de.codevoid.camdl.probe.ProbeRenderer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Holds the probe log and the scope camera links live in.
 *
 * Both are process-scoped rather than tied to an activity: a capture has to survive a
 * rotation or the app being backgrounded mid-connect, and losing it costs a whole round trip
 * of build, install and drive to the camera.
 */
class CamDlApp : Application() {

    lateinit var probeFile: ProbeFile
        private set

    lateinit var probe: ProbeLog
        private set

    /**
     * SupervisorJob so one failed camera link cannot cancel the others, and Dispatchers.Default
     * because the work on it is framing and parsing rather than blocking I/O.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        probeFile = ProbeFile(this)
        probe = ProbeLog(sink = ::persist).also { it.enabled = loggingEnabled() }

        markStart()
        installCrashHandler()
    }

    private fun persist(event: ProbeEvent) {
        probeFile.append(ProbeRenderer.renderEvent(event, probe.redactor))
    }

    /**
     * A banner between runs.
     *
     * Event timestamps are relative to the start of a process, so without something marking
     * the boundary a log kept across restarts reads as one run whose clock keeps jumping
     * backwards.
     */
    private fun markStart() {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        probeFile.append(
            listOf(
                "",
                "================================================================",
                "  camDL ${BuildConfig.VERSION_NAME}  started $stamp" +
                    if (probe.enabled) "" else "   (logging is OFF)",
                "================================================================",
            ),
        )
    }

    /**
     * Appends the stack trace of whatever killed the process.
     *
     * The per-entry sink already has everything up to this point on disk; what it cannot know
     * is why the process stopped, which is the one thing only this handler can add.
     */
    private fun installCrashHandler() {
        val existing = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Nothing in here may throw, or the report is lost to a second crash.
            runCatching {
                probe.note(
                    "crash",
                    "uncaught exception",
                    mapOf("thread" to thread.name, "error" to error.toString()),
                )
                probeFile.append(probe.redactor.text(error.stackTraceToString()).lines())
            }
            existing?.uncaughtException(thread, error)
        }
    }

    // Logging stays off across restarts if it was switched off, so a decision to stop
    // recording is not quietly undone by the next launch.
    private fun preferences() = getSharedPreferences("camdl", Context.MODE_PRIVATE)

    private fun loggingEnabled() = preferences().getBoolean(KEY_LOGGING, true)

    fun setLoggingEnabled(value: Boolean) {
        preferences().edit().putBoolean(KEY_LOGGING, value).apply()
        if (!value) {
            // Recorded while still enabled, so the log says why it stops rather than just
            // stopping.
            probe.note("app", "logging switched off")
        }
        probe.enabled = value
        if (value) probe.note("app", "logging switched on")
    }

    companion object {
        private const val KEY_LOGGING = "logging_enabled"

        fun of(application: Application): CamDlApp = application as CamDlApp

        fun probe(application: Application): ProbeLog = of(application).probe

        fun scope(application: Application): CoroutineScope = of(application).scope
    }
}
