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
import kotlin.time.Duration.Companion.seconds
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
        // Registered before anything is sent: entries reach the log file the instant they are
        // recorded, so a secret registered afterwards would already be on disk in the clear.
        probe.redactor.register(PROPOSED_SSID, "SSID")
        probe.redactor.register(PROPOSED_PASSPHRASE, "PSK")

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
            // Sent rather than demanded. Against an Action 5 Pro this command produces no reply
            // at all: the link simply stops answering a few seconds later and dies of a
            // supervision timeout, which is what a combined radio switching itself to Wi-Fi
            // access point mode looks like from the Bluetooth side. Treating that as a failure
            // would abandon the connection at the exact moment it started working.
            val response = runCatching {
                duml.provisionWifi(PROPOSED_SSID, PROPOSED_PASSPHRASE)
            }.onFailure {
                probe.note(
                    "wifi",
                    "link ended while provisioning",
                    mapOf("cause" to it.toString(), "expected" to "yes, the radio switches to Wi-Fi"),
                )
            }.getOrNull()

            // Still worth reading if it ever does answer: it would say whether 0x07/0x47 sets
            // the access point or reports one the camera picked itself.
            val strings = response?.payload?.let { PayloadStrings.find(it) }.orEmpty()
            probe.note(
                "wifi",
                "strings in provisioning response",
                mapOf("count" to strings.size.toString(), "found" to strings.joinToString(" ")),
            )

            val ssid = strings.getOrNull(0)?.value ?: PROPOSED_SSID
            val passphrase = strings.getOrNull(1)?.value ?: PROPOSED_PASSPHRASE
            probe.redactor.register(ssid, "SSID")
            probe.redactor.register(passphrase, "PSK")
            probe.note(
                "wifi",
                "credentials",
                mapOf("source" to if (strings.size >= 2) "camera" else "proposed by us"),
            )
            Credentials(ssid, passphrase)
        }

        val wifi = WifiJoiner(context, probe)
        val lease = probe.stage("wifi-join") {
            try {
                wifi.join(credentials.ssid, credentials.passphrase, EXACT_JOIN_TIMEOUT)
            } catch (exact: IOException) {
                // Nothing came up under the name we handed over, so the camera probably named
                // its own. A prefix match puts Android's picker in front of the user, and the
                // picker lists what is actually on the air - which is the one thing this app
                // cannot see for itself without location permission.
                probe.note(
                    "wifi",
                    "no access point under the proposed name, matching by prefix instead",
                    mapOf("cause" to exact.toString(), "prefix" to AP_PREFIX),
                )
                wifi.joinMatching(AP_PREFIX, credentials.passphrase)
            }
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

        /**
         * Fallback for the access point's name. The camera advertises itself over Bluetooth as
         * `OsmoAction5Pro<serial>`, so its access point very likely carries the same prefix.
         */
        const val AP_PREFIX = "Osmo"

        /**
         * Shorter than the joiner's own default, because a failure here is not the end of the
         * attempt - it is the cue to fall back to a prefix match. The access point is documented
         * to appear about fifteen seconds after provisioning, so this is already generous, and
         * every second spent here is a second before the user sees the picker.
         */
        val EXACT_JOIN_TIMEOUT = 40.seconds

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
