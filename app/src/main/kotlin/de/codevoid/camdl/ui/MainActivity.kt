package de.codevoid.camdl.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import de.codevoid.camdl.CamDlApp
import de.codevoid.camdl.R
import de.codevoid.camdl.databinding.ActivityMainBinding
import de.codevoid.camdl.dji.DjiCamera
import de.codevoid.camdl.dji.DjiLink
import de.codevoid.camdl.probe.ProbeEvent
import de.codevoid.camdl.probe.ProbeLog
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
                    // lifecycleScope, not this withContext's scope: the session's reader has to
                    // outlive connect(), and withContext cancels its scope on return.
                    DjiCamera(applicationContext, probe, lifecycleScope).connect()
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

    /** The stage the log got to, so a failure names where it stopped rather than just how. */
    private fun lastStage(): String =
        probe.snapshot().events.lastOrNull { it is ProbeEvent.StageBegin }?.tag ?: "startup"

    private fun status(text: String) {
        binding.status.text = text
    }
}
