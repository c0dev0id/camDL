package de.codevoid.camdl.ble

import android.bluetooth.BluetoothGattCharacteristic
import de.codevoid.camdl.camera.DumlChannel
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Carries DUML over a pair of GATT characteristics: one written to, one notified from.
 *
 * Wire logging lives in [GattClient], which is what the [DumlChannel] contract asks for - it
 * records the bytes that actually moved, while the session above records decoded frames.
 */
class BleDumlChannel(
    private val gatt: GattClient,
    private val writeTo: BluetoothGattCharacteristic,
    private val notifiedFrom: UUID,
) : DumlChannel {

    override val name: String = "gatt/${GattClient.short(writeTo.uuid)}"

    override suspend fun write(bytes: ByteArray) = gatt.write(writeTo, bytes)

    override val inbound: Flow<ByteArray> = gatt.notifications
        .filter { it.characteristic == notifiedFrom }
        .map { it.value }
}
