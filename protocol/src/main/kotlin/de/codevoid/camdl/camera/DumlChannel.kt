package de.codevoid.camdl.camera

import kotlinx.coroutines.flow.Flow

/**
 * A bidirectional byte pipe carrying DUML frames.
 *
 * Deliberately ignorant of what is underneath: the same frames travel over a BLE
 * characteristic before the camera's access point exists, and over UDP afterwards. Neither
 * delivers whole frames, so [inbound] is chunks and reassembly is [DumlSession]'s problem.
 *
 * Being an interface here rather than in the Android module is what lets the session layer -
 * correlation, timeouts, reassembly - be tested without a device.
 */
interface DumlChannel {

    /** Short name for the probe log, e.g. `gatt/fff5` or `udp/9004`. */
    val name: String

    suspend fun write(bytes: ByteArray)

    /** Raw inbound chunks, in arrival order. */
    val inbound: Flow<ByteArray>
}
