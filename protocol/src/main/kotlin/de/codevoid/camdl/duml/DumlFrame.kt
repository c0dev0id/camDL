package de.codevoid.camdl.duml

/**
 * One DUML frame, DJI's control protocol. The same framing is used over the BLE
 * characteristic and, wrapped in a routing header, over UDP once the camera's AP is up.
 *
 * ```
 * offset  size  field
 *      0     1  0x55 start of frame
 *      1     2  length (10 bits) and version (6 bits), little endian
 *      3     1  CRC-8 over bytes 0..2
 *      4     2  target: sender and receiver, little endian
 *      6     2  message id, little endian
 *      8     3  type: flags | commandSet << 8 | commandId << 16, little endian
 *     11     n  payload
 *   11+n     2  CRC-16 over everything before it, little endian
 * ```
 */
class DumlFrame(
    /** Sender in the low byte, receiver in the high byte. See [DumlCommands] for the values. */
    val target: Int,
    val messageId: Int,
    /** Packed [flags], [commandSet] and [commandId]; build it with [DumlCommands.type]. */
    val type: Int,
    val payload: ByteArray = EMPTY,
    val version: Int = DEFAULT_VERSION,
) {
    val flags: Int get() = type and 0xFF
    val commandSet: Int get() = (type ushr 8) and 0xFF
    val commandId: Int get() = (type ushr 16) and 0xFF

    init {
        require(payload.size <= MAX_PAYLOAD) {
            "payload of ${payload.size} bytes exceeds the $MAX_PAYLOAD the 10-bit length field can describe"
        }
    }

    fun encode(): ByteArray {
        val total = OVERHEAD + payload.size
        val out = ByteArray(total)

        out[0] = SOF.toByte()
        out[1] = (total and 0xFF).toByte()
        out[2] = (((version and 0x3F) shl 2) or ((total ushr 8) and 0x03)).toByte()
        out[3] = DjiCrc.crc8(out, 0, 3).toByte()

        out[4] = (target and 0xFF).toByte()
        out[5] = ((target ushr 8) and 0xFF).toByte()
        out[6] = (messageId and 0xFF).toByte()
        out[7] = ((messageId ushr 8) and 0xFF).toByte()
        out[8] = (type and 0xFF).toByte()
        out[9] = ((type ushr 8) and 0xFF).toByte()
        out[10] = ((type ushr 16) and 0xFF).toByte()

        payload.copyInto(out, HEADER_SIZE)

        val crc = DjiCrc.crc16(out, 0, total - 2)
        out[total - 2] = (crc and 0xFF).toByte()
        out[total - 1] = ((crc ushr 8) and 0xFF).toByte()

        return out
    }

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is DumlFrame &&
                target == other.target &&
                messageId == other.messageId &&
                type == other.type &&
                version == other.version &&
                payload.contentEquals(other.payload)
            )

    override fun hashCode(): Int {
        var result = target
        result = 31 * result + messageId
        result = 31 * result + type
        result = 31 * result + version
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String = buildString {
        append("DumlFrame(target=0x").append(target.toString(16))
        append(", id=0x").append(messageId.toString(16))
        append(", cmd=0x").append(commandSet.toString(16)).append('/')
        append("0x").append(commandId.toString(16))
        append(", flags=0x").append(flags.toString(16))
        append(", ").append(payload.size).append("B)")
    }

    companion object {
        const val SOF = 0x55

        /** Bytes before the payload. */
        const val HEADER_SIZE = 11

        /** Header plus trailing CRC-16. */
        const val OVERHEAD = HEADER_SIZE + 2

        /** The length field is 10 bits, so a frame cannot exceed 1023 bytes. */
        const val MAX_FRAME = 0x3FF
        const val MAX_PAYLOAD = MAX_FRAME - OVERHEAD

        /** Encoded in the upper 6 bits of byte 2; 1 puts the familiar 0x04 there. */
        const val DEFAULT_VERSION = 1

        private val EMPTY = ByteArray(0)

        /** Total frame length declared by a header, or -1 if [bytes] is not a valid header. */
        fun peekLength(bytes: ByteArray, offset: Int = 0): Int {
            if (offset + HEADER_SIZE > bytes.size) return -1
            if (bytes[offset].toInt() and 0xFF != SOF) return -1

            val total = (bytes[offset + 1].toInt() and 0xFF) or
                ((bytes[offset + 2].toInt() and 0x03) shl 8)
            if (total < OVERHEAD || total > MAX_FRAME) return -1
            if (DjiCrc.crc8(bytes, offset, offset + 3) != (bytes[offset + 3].toInt() and 0xFF)) return -1

            return total
        }

        /**
         * Decodes exactly one frame starting at [offset], or returns null if the bytes are not
         * a complete, checksum-clean frame. Null rather than an exception because the caller is
         * usually scanning a stream for the next plausible start of frame.
         */
        fun decodeOrNull(bytes: ByteArray, offset: Int = 0): DumlFrame? {
            val total = peekLength(bytes, offset)
            if (total < 0 || offset + total > bytes.size) return null

            val end = offset + total
            val expected = DjiCrc.crc16(bytes, offset, end - 2)
            val actual = (bytes[end - 2].toInt() and 0xFF) or ((bytes[end - 1].toInt() and 0xFF) shl 8)
            if (expected != actual) return null

            return DumlFrame(
                target = (bytes[offset + 4].toInt() and 0xFF) or ((bytes[offset + 5].toInt() and 0xFF) shl 8),
                messageId = (bytes[offset + 6].toInt() and 0xFF) or ((bytes[offset + 7].toInt() and 0xFF) shl 8),
                type = (bytes[offset + 8].toInt() and 0xFF) or
                    ((bytes[offset + 9].toInt() and 0xFF) shl 8) or
                    ((bytes[offset + 10].toInt() and 0xFF) shl 16),
                payload = bytes.copyOfRange(offset + HEADER_SIZE, end - 2),
                version = (bytes[offset + 2].toInt() ushr 2) and 0x3F,
            )
        }
    }
}
