package de.codevoid.camdl.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import de.codevoid.camdl.probe.Direction
import de.codevoid.camdl.probe.ProbeLog
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A coroutine wrapper around [BluetoothGatt] that serialises operations.
 *
 * The serialisation is the entire point. `BluetoothGatt` accepts **one outstanding operation
 * at a time** and silently drops a second - no exception, no callback, the write simply never
 * happens. Every method here therefore holds [operations] for its whole duration, including
 * the wait for the callback that completes it.
 *
 * Callbacks arrive on a binder thread, so each one does nothing but record to the probe log
 * and complete a deferred. Nothing that can block runs there.
 *
 * None of this can be unit tested without a device. The probe log is the verification, which
 * is why every callback hop is recorded.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is held before anything constructs this
class GattClient private constructor(
    private val probe: ProbeLog,
    private val tag: String,
) {
    private lateinit var gatt: BluetoothGatt

    private val operations = Mutex()

    @Volatile
    private var pending: CompletableDeferred<Unit>? = null

    private val connected = CompletableDeferred<Unit>()

    /**
     * Unbounded on purpose. The alternative to buffering is dropping frames on a binder
     * thread, and a lost notification during protocol bring-up reads as a camera that did not
     * answer - the most expensive kind of wrong.
     */
    private val incoming = Channel<Notification>(Channel.UNLIMITED)
    val notifications: Flow<Notification> = incoming.receiveAsFlow()

    class Notification(val characteristic: UUID, val value: ByteArray)

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            probe.note(
                tag,
                "connection state",
                mapOf("state" to stateName(newState), "status" to status.toString()),
            )
            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS ->
                    connected.complete(Unit)

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    val failure = IOException("disconnected with status $status")
                    connected.completeExceptionally(failure)
                    pending?.completeExceptionally(failure)
                    incoming.close(failure)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            probe.note(tag, "services discovered", mapOf("status" to status.toString()))
            finish(status)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // Worth a note of its own: a smaller MTU than asked for is not an error, but it
            // changes where frames get cut and so what the framer has to cope with.
            probe.note(tag, "mtu", mapOf("granted" to mtu.toString(), "status" to status.toString()))
            finish(status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                probe.note(
                    tag,
                    "write failed",
                    mapOf("uuid" to short(characteristic.uuid), "status" to status.toString()),
                )
            }
            finish(status)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            probe.note(
                tag,
                "descriptor written",
                mapOf("uuid" to short(descriptor.characteristic.uuid), "status" to status.toString()),
            )
            finish(status)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            probe.wire(tag, Direction.RX, "gatt/${short(characteristic.uuid)}", value)
            incoming.trySend(Notification(characteristic.uuid, value))
        }

        /** Completes the outstanding operation. Only ever called from a callback. */
        private fun finish(status: Int) {
            val waiter = pending ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                waiter.complete(Unit)
            } else {
                waiter.completeExceptionally(IOException("GATT operation failed with status $status"))
            }
        }
    }

    /**
     * Runs one GATT operation and waits for the callback that completes it.
     *
     * The timeout matters as much as the mutex: a call the stack accepts but never answers
     * would otherwise hold the lock forever and wedge every operation after it.
     */
    private suspend fun operation(
        name: String,
        timeout: Duration = OPERATION_TIMEOUT,
        start: () -> Boolean,
    ) = operations.withLock {
        val waiter = CompletableDeferred<Unit>()
        pending = waiter
        try {
            if (!start()) throw IOException("$name was rejected before it started")
            withTimeoutOrNull(timeout) { waiter.await() }
                ?: throw IOException("$name timed out after $timeout")
        } finally {
            pending = null
        }
    }

    suspend fun discoverServices() = operation("discoverServices") { gatt.discoverServices() }

    suspend fun requestMtu(mtu: Int) = operation("requestMtu") { gatt.requestMtu(mtu) }

    /**
     * Finds a characteristic by UUID across every discovered service.
     *
     * Searching rather than taking a service UUID is deliberate: which service holds `fff4`
     * and `fff5` on this camera is a guess, and a wrong guess would fail in a way that looks
     * like the characteristic is absent. The failure message lists what was actually found,
     * so one sideload settles it.
     */
    fun characteristic(characteristic: UUID): BluetoothGattCharacteristic =
        gatt.services.firstNotNullOfOrNull { it.getCharacteristic(characteristic) }
            ?: throw IOException(
                "characteristic ${short(characteristic)} not found; discovered: ${describeServices()}",
            )

    suspend fun write(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        withResponse: Boolean = true,
    ) {
        val type = if (withResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        probe.wire(tag, Direction.TX, "gatt/${short(characteristic.uuid)}", value)
        operation("write ${short(characteristic.uuid)}") {
            gatt.writeCharacteristic(characteristic, value, type) == BluetoothGatt.GATT_SUCCESS
        }
    }

    /** Enables notifications locally, then writes the descriptor that makes the peer send them. */
    suspend fun subscribe(characteristic: BluetoothGattCharacteristic) {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            throw IOException("could not enable notifications on ${short(characteristic.uuid)}")
        }
        val cccd = characteristic.getDescriptor(CCCD)
            ?: throw IOException("${short(characteristic.uuid)} has no client configuration descriptor")

        operation("subscribe ${short(characteristic.uuid)}") {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothGatt.GATT_SUCCESS
        }
    }

    /** Everything the peer advertises, for the log - we are guessing at this camera's layout. */
    fun describeServices(): String = gatt.services.joinToString("; ") { service ->
        short(service.uuid) + service.characteristics.joinToString(",", "[", "]") { short(it.uuid) }
    }

    fun close() {
        incoming.close()
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    companion object {
        /** Client Characteristic Configuration Descriptor: the standard subscribe switch. */
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        val OPERATION_TIMEOUT = 15.seconds
        val CONNECT_TIMEOUT = 20.seconds

        /**
         * Connects and returns once the link is up.
         *
         * `autoConnect = false` on purpose: it connects promptly and fails promptly, which is
         * what someone standing in front of a camera wants. `autoConnect = true` can sit in a
         * background scan for minutes with nothing to show.
         */
        @SuppressLint("MissingPermission")
        suspend fun connect(
            context: Context,
            device: BluetoothDevice,
            probe: ProbeLog,
            tag: String = "ble",
        ): GattClient {
            val client = GattClient(probe, tag)

            // One callback for the whole life of the connection, registered here: a second
            // callback object passed to connectGatt would take over and the client's own would
            // never fire.
            client.gatt = device.connectGatt(context, false, client.callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw IOException("connectGatt returned null")

            try {
                withTimeoutOrNull(CONNECT_TIMEOUT) { client.connected.await() }
                    ?: throw IOException("connect timed out after $CONNECT_TIMEOUT")
            } catch (t: Throwable) {
                client.close()
                throw t
            }
            return client
        }

        private fun stateName(state: Int) = when (state) {
            BluetoothProfile.STATE_CONNECTED -> "connected"
            BluetoothProfile.STATE_CONNECTING -> "connecting"
            BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
            BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
            else -> "state $state"
        }

        /** `0000fff5-0000-1000-8000-00805f9b34fb` reads as `fff5` in a log. */
        internal fun short(uuid: UUID): String {
            val text = uuid.toString()
            return if (text.startsWith("0000") && text.endsWith("-0000-1000-8000-00805f9b34fb")) {
                text.substring(4, 8)
            } else {
                text
            }
        }
    }
}
