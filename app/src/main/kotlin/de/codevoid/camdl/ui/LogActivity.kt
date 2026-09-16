package de.codevoid.camdl.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import de.codevoid.camdl.CamDlApp
import de.codevoid.camdl.R
import de.codevoid.camdl.databinding.ActivityLogBinding
import de.codevoid.camdl.probe.ProbeExport

/**
 * The probe log as text, with a share action.
 *
 * Reads the file rather than memory, so it shows every run kept on disk and not just this one.
 * Monospaced and not wrapped, because hexdump columns that reflow are unreadable; the
 * horizontal scroll in the layout is what makes that work on a phone.
 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private lateinit var app: CamDlApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = CamDlApp.of(application)

        binding.share.setOnClickListener {
            startActivity(ProbeExport.shareIntent(this, app.probeFile))
        }

        binding.toggleLogging.setOnClickListener {
            app.setLoggingEnabled(!app.probe.enabled)
            refresh()
        }

        binding.clear.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.confirm_clear_log)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.action_clear_log) { _, _ ->
                    app.probeFile.clear()
                    refresh()
                }
                .show()
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        binding.log.text = app.probeFile.read()
        binding.toggleLogging.text = getString(
            if (app.probe.enabled) R.string.action_logging_on else R.string.action_logging_off,
        )
        binding.size.text = getString(R.string.log_size, app.probeFile.sizeBytes() / 1024)
    }
}
