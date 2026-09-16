package de.codevoid.camdl.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import de.codevoid.camdl.BuildConfig
import de.codevoid.camdl.CamDlApp
import de.codevoid.camdl.R
import de.codevoid.camdl.databinding.ActivityMainBinding
import de.codevoid.camdl.dji.DjiCamera
import de.codevoid.camdl.dji.DjiLink
import de.codevoid.camdl.probe.ProbeEvent
import de.codevoid.camdl.probe.ProbeLog
import de.codevoid.camdl.update.ReleaseAsset
import de.codevoid.camdl.update.ReleaseVersion
import de.codevoid.camdl.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One button and a status line.
 *
 * Deliberately thin: M1 only has to get as far as reaching the camera and produce a log that
 * says what happened. The media grid arrives with M2.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var probe: ProbeLog

    private var link: DjiLink? = null
    private var connecting = false

    private val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.NEARBY_WIFI_DEVICES,
    )

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val denied = granted.filterValues { !it }.keys
        if (denied.isEmpty()) {
            connect()
        } else {
            status(getString(R.string.status_permissions_denied, denied.joinToString { it.substringAfterLast('.') }))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        probe = CamDlApp.probe(application)

        binding.connect.setOnClickListener {
            if (!connecting) requestPermissionsThenConnect()
        }
        binding.openLog.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        binding.update.setOnClickListener { checkForUpdate() }

        status(getString(R.string.status_idle))
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            link?.close()
            link = null
        }
    }

    private fun requestPermissionsThenConnect() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) connect() else requestPermissions.launch(missing.toTypedArray())
    }

    private fun connect() {
        connecting = true
        binding.connect.isEnabled = false

        lifecycleScope.launch {
            // The connect chain writes its own progress into the probe log; the status line
            // just reflects the last stage it reached, so the two can never disagree.
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    // The application scope, not lifecycleScope or this withContext's scope:
                    // the session's reader has to outlive both connect() returning and the
                    // activity being recreated.
                    DjiCamera(applicationContext, probe, CamDlApp.scope(application)).connect()
                }
            }

            link?.close()
            link = result.getOrNull()

            status(
                result.fold(
                    onSuccess = { getString(R.string.status_connected, DjiCamera.CAMERA_IP) },
                    onFailure = { getString(R.string.status_failed, lastStage(), it.message ?: it.javaClass.simpleName) },
                ),
            )
            connecting = false
            binding.connect.isEnabled = true
        }
    }

    private fun checkForUpdate() {
        binding.update.isEnabled = false
        status(getString(R.string.update_checking))

        lifecycleScope.launch {
            val updater = Updater(applicationContext, probe)
            val found = runCatching { withContext(Dispatchers.IO) { updater.check() } }

            found
                .onSuccess { asset ->
                    if (asset == null) {
                        status(getString(R.string.update_current, BuildConfig.VERSION_NAME))
                    } else {
                        offer(updater, asset)
                    }
                }
                .onFailure {
                    status(getString(R.string.update_failed, it.message ?: it.javaClass.simpleName))
                }

            binding.update.isEnabled = true
        }
    }

    private fun offer(updater: Updater, asset: ReleaseAsset) {
        val version = ReleaseVersion.versionOf(asset.name) ?: asset.name

        AlertDialog.Builder(this)
            .setMessage(getString(R.string.update_available, version))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> download(updater, asset) }
            .show()
    }

    private fun download(updater: Updater, asset: ReleaseAsset) {
        // Asked for before downloading rather than after: without it the installer opens and
        // immediately refuses, which reads as the update being broken rather than as a setting
        // that has not been granted.
        if (!updater.canInstall()) {
            AlertDialog.Builder(this)
                .setMessage(R.string.update_needs_permission)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    startActivity(updater.allowInstallsIntent())
                }
                .show()
            return
        }

        binding.update.isEnabled = false
        status(getString(R.string.update_downloading, asset.name))

        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { updater.download(asset) } }
                .onSuccess { startActivity(updater.installIntent(it)) }
                .onFailure {
                    status(getString(R.string.update_failed, it.message ?: it.javaClass.simpleName))
                }
            binding.update.isEnabled = true
        }
    }

    /** The stage the log got to, so a failure names where it stopped rather than just how. */
    private fun lastStage(): String =
        probe.snapshot().events.lastOrNull { it is ProbeEvent.StageBegin }?.tag ?: "startup"

    private fun status(text: String) {
        binding.status.text = text
    }
}
