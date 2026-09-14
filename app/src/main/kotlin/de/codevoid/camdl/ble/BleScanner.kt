package de.codevoid.camdl.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import de.codevoid.camdl.probe.ProbeLog
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds a camera by advertised name.
 *
 * Scans unfiltered and logs every distinct device it sees. What an Osmo Action 5 Pro
 * advertises is not documented anywhere reliable, so a `ScanFilter` built on a guess would
 * produce an empty scan indistinguishable from a camera that is switched off. Logging
 * everything means one sideload shows the real name, even when the guess was wrong.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_SCAN is held before this is constructed
class BleScanner(
    private val context: Context,
    private val probe: ProbeLog,
    private val tag: String = "ble",
) {
    suspend fun find(namePrefix: String, timeout: Duration = SCAN_TIMEOUT): BluetoothDevice {
        val manager = context.getSystemService(BluetoothManager::class.java)
            ?: throw IOException("this device has no Bluetooth")
        val adapter = manager.adapter ?: throw IOException("this device has no Bluetooth adapter")
        if (!adapter.isEnabled) throw IOException("Bluetooth is switched off")
        val scanner = adapter.bluetoothLeScanner
            ?: throw IOException("no BLE scanner; is Bluetooth switched off?")

        val found = CompletableDeferred<BluetoothDevice>()
        val seen = HashSet<String>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val advertised = result.scanRecord?.deviceName
                val name = advertised ?: result.device.name

                // One line per device, not per advertisement: a scan produces hundreds and the
                // log has to stay readable.
                if (seen.add(result.device.address)) {
                    probe.note(
                        tag,
                        "scan result",
                        mapOf(
                            "address" to probe.redactor.register(result.device.address, "MAC").orEmpty(),
                            "name" to (name ?: "<none>"),
                            "rssi" to result.rssi.toString(),
                        ),
                    )
                }

                if (name != null && name.startsWith(namePrefix, ignoreCase = true)) {
                    found.complete(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                probe.note(tag, "scan failed", mapOf("error" to errorCode.toString()))
                found.completeExceptionally(IOException("BLE scan failed with error $errorCode"))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        probe.note(tag, "scanning", mapOf("prefix" to namePrefix, "timeout" to timeout.toString()))
        // null, not an empty filter list: null is the documented "no filtering", while an empty
        // list matches nothing on some implementations.
        scanner.startScan(null, settings, callback)
        try {
            return withTimeoutOrNull(timeout) { found.await() }
                ?: throw IOException(
                    "no device advertising a name starting with \"$namePrefix\" within $timeout;" +
                        " ${seen.size} other device(s) were seen - check the log for their names",
                )
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    companion object {
        val SCAN_TIMEOUT = 20.seconds
    }
}
