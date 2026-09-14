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
 *
 * **Implementations own wire logging.** A channel records the bytes it actually moves, which
 * is not always what the session handed it - a frame can be split across several GATT writes.
 * [DumlSession] therefore logs decoded frames rather than bytes, and the two views sit next to
 * each other in the log instead of duplicating one another.
 */
interface DumlChannel {

    /** Short name for the probe log, e.g. `gatt/fff5` or `udp/9004`. */
    val name: String

    suspend fun write(bytes: ByteArray)

    /** Raw inbound chunks, in arrival order. */
    val inbound: Flow<ByteArray>
}
