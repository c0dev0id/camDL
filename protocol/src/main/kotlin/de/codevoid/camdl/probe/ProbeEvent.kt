package de.codevoid.camdl.probe

enum class Direction { TX, RX }

/**
 * One thing that happened while talking to a camera.
 *
 * Timestamps are relative to the start of the log, not wall clock. What matters when reading
 * a log back is the gap between steps - "the AP appeared 15s after provisioning" - and an
 * absolute timestamp is identifying without being useful.
 */
sealed interface ProbeEvent {

    /** Nanoseconds since the log started. */
    val at: Long

    /** Subsystem or stage name, used as the log's second column. */
    val tag: String

    data class StageBegin(
        override val at: Long,
        override val tag: String,
    ) : ProbeEvent

    data class StageEnd(
        override val at: Long,
        override val tag: String,
        val elapsedNanos: Long,
        /** `null` on success; otherwise the failure as it will be shown. */
        val failure: String?,
    ) : ProbeEvent

    data class Note(
        override val at: Long,
        override val tag: String,
        val message: String,
        val fields: Map<String, String> = emptyMap(),
    ) : ProbeEvent

    /**
     * Bytes that crossed a boundary. Always recorded verbatim: the whole point of this log is
     * to learn a protocol nobody has documented, so anything not yet understood must survive
     * to be read later.
     */
    class Wire(
        override val at: Long,
        override val tag: String,
        val direction: Direction,
        /** Where on the subsystem, e.g. `gatt/fff5`, `udp/9004`, `GET /v2`. */
        val channel: String,
        val bytes: ByteArray,
    ) : ProbeEvent {

        // Hand-written because ByteArray does not have value equality, and tests compare
        // captured events.
        override fun equals(other: Any?): Boolean =
            this === other || (
                other is Wire &&
                    at == other.at &&
                    tag == other.tag &&
                    direction == other.direction &&
                    channel == other.channel &&
                    bytes.contentEquals(other.bytes)
                )

        override fun hashCode(): Int {
            var result = at.hashCode()
            result = 31 * result + tag.hashCode()
            result = 31 * result + direction.hashCode()
            result = 31 * result + channel.hashCode()
            result = 31 * result + bytes.contentHashCode()
            return result
        }

        override fun toString(): String =
            "Wire(at=$at, tag=$tag, direction=$direction, channel=$channel, ${bytes.size}B)"
    }
}
