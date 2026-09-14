package de.codevoid.camdl.dji

import android.content.Context
import de.codevoid.camdl.ble.BleDumlChannel
import de.codevoid.camdl.ble.BleScanner
import de.codevoid.camdl.ble.GattClient
import de.codevoid.camdl.camera.DumlSession
import de.codevoid.camdl.camera.DumlSession.Companion.pair
import de.codevoid.camdl.camera.DumlSession.Companion.provisionWifi
import de.codevoid.camdl.duml.PayloadStrings
import de.codevoid.camdl.probe.ProbeLog
import de.codevoid.camdl.wifi.WifiJoiner
import de.codevoid.camdl.wifi.WifiLease
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope

/**
 * Everything between "app is open" and "packets reach the camera".
 *
 * Written as plain suspend functions composed here rather than as a step framework: the
 * compiler enforces the ordering because each stage consumes what the previous one produced,
 * and `probe.stage` supplies both the log's structure and the UI's progress text.
 */
class DjiCamera(
    private val context: Context,
    private val probe: ProbeLog,
    private val scope: CoroutineScope,
) {
    suspend fun connect(namePrefix: String = NAME_PREFIX): DjiLink {
        val device = probe.stage("ble-scan") {
            BleScanner(context, probe).find(namePrefix)
        }

        val gatt = probe.stage("ble-connect") {
            GattClient.connect(context, device, probe)
        }

        val channel = probe.stage("ble-setup") {
            gatt.discoverServices()
            probe.note("ble", "services", mapOf("found" to gatt.describeServices()))

            gatt.requestMtu(MTU)

            val notify = gatt.characteristic(NOTIFY_CHARACTERISTIC)
            val write = gatt.characteristic(WRITE_CHARACTERISTIC)
            gatt.subscribe(notify)
            gatt.subscribe(write)

            // Not a descriptor write: the published notes have the app writing 01 00 to the
            // notify characteristic's own value as part of bringing the link up.
            gatt.write(notify, byteArrayOf(0x01, 0x00), withResponse = true)

            BleDumlChannel(gatt, write, NOTIFY_CHARACTERISTIC)
        }

        val duml = DumlSession(channel, probe, scope, tag = "duml")

        probe.stage("ble-pair") {
            duml.pair() ?: throw IOException(
                "the camera did not answer the pairing request - if it is showing a prompt, approve it and retry",
            )
        }

        val credentials = probe.stage("wifi-provision") {
            val response = duml.provisionWifi(PROPOSED_SSID, PROPOSED_PASSPHRASE)
                ?: throw IOException("the camera did not answer the Wi-Fi provisioning request")

            // The open question this milestone settles: does 0x07/0x47 tell the camera which
            // access point to raise, or report the one it chose? Log every string in the reply
            // and prefer them if they are there, so one run answers it either way.
            val strings = PayloadStrings.find(response.payload)
            probe.note(
                "wifi",
                "strings in provisioning response",
                mapOf("count" to strings.size.toString(), "found" to strings.joinToString(" ")),
            )

            val ssid = strings.getOrNull(0)?.value ?: PROPOSED_SSID
            val passphrase = strings.getOrNull(1)?.value ?: PROPOSED_PASSPHRASE
            probe.note(
                "wifi",
                "credentials",
                mapOf("source" to if (strings.size >= 2) "camera" else "proposed by us"),
            )
            Credentials(ssid, passphrase)
        }

        val lease = probe.stage("wifi-join") {
            WifiJoiner(context, probe).join(credentials.ssid, credentials.passphrase)
        }

        probe.stage("reachability") {
            lease.poke(CAMERA_IP, POKE_PORT)
        }

        return DjiLink(gatt, duml, lease)
    }

    private data class Credentials(val ssid: String, val passphrase: String)

    companion object {
        /**
         * A guess. The scanner logs every device it sees, so a wrong prefix still produces the
         * real advertised name rather than an empty result that could mean anything.
         */
        const val NAME_PREFIX = "Osmo"

        const val CAMERA_IP = "192.168.2.1"

        /** The poke the DUML transport needs before UDP works; doubles as a reachability check. */
        const val POKE_PORT = 7001

        const val MTU = 500

        val NOTIFY_CHARACTERISTIC: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
        val WRITE_CHARACTERISTIC: UUID = UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb")

        /**
         * Constants rather than per-install random values, because an *exact* SSID is what lets
         * Android remember the user's approval - a value that changed per run would re-prompt
         * every time. Worth revisiting once it is known whether the camera even uses what we
         * propose here; if it reports its own credentials instead, these never reach the air.
         */
        const val PROPOSED_SSID = "camDL"
        const val PROPOSED_PASSPHRASE = "camDL-transfer"
    }
}

/** An open connection to a camera: BLE still up, Wi-Fi joined, sockets bindable. */
class DjiLink(
    private val gatt: GattClient,
    val duml: DumlSession,
    val wifi: WifiLease,
) : AutoCloseable {
    override fun close() {
        duml.close()
        wifi.close()
        gatt.close()
    }
}
