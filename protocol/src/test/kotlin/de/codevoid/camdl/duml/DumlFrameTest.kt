package de.codevoid.camdl.duml

import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class DumlFrameTest {

    private fun frame(payload: ByteArray = ByteArray(0)) = DumlFrame(
        target = DumlCommands.TARGET_APP_TO_WIFI,
        messageId = 0x8092,
        type = DumlCommands.type(DumlCommands.FLAG_REQUEST, 0x07, 0x45),
        payload = payload,
    )

    @Test
    fun `the header lands in the documented byte positions`() {
        val encoded = frame(byteArrayOf(0x01, 0x02)).encode()

        assertEquals(DumlFrame.OVERHEAD + 2, encoded.size)
        assertEquals(0x55, encoded[0].toInt() and 0xFF)
        assertEquals(encoded.size, encoded[1].toInt() and 0xFF)
        assertEquals(0x04, encoded[2].toInt() and 0xFF) // version 1 in the upper six bits
        assertEquals(DjiCrc.crc8(encoded, 0, 3), encoded[3].toInt() and 0xFF)
        assertEquals(0x02, encoded[4].toInt() and 0xFF) // target low: sender
        assertEquals(0x07, encoded[5].toInt() and 0xFF) // target high: receiver
        assertEquals(0x92, encoded[6].toInt() and 0xFF) // message id, little endian
        assertEquals(0x80, encoded[7].toInt() and 0xFF)
        assertEquals(0x40, encoded[8].toInt() and 0xFF) // flags
        assertEquals(0x07, encoded[9].toInt() and 0xFF) // command set
        assertEquals(0x45, encoded[10].toInt() and 0xFF) // command id
    }

    @Test
    fun `the trailing crc16 covers everything before it`() {
        val encoded = frame(byteArrayOf(0x01, 0x02)).encode()
        val expected = DjiCrc.crc16(encoded, 0, encoded.size - 2)

        assertEquals(expected and 0xFF, encoded[encoded.size - 2].toInt() and 0xFF)
        assertEquals((expected ushr 8) and 0xFF, encoded[encoded.size - 1].toInt() and 0xFF)
    }

    @Test
    fun `an empty payload still produces a well formed frame`() {
        val decoded = DumlFrame.decodeOrNull(frame().encode())

        assertNotNull(decoded)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun `round trips across the whole payload range`() {
        val random = Random(seed = 20260914)
        for (size in intArrayOf(0, 1, 2, 243, 244, 245, 255, 256, 512, DumlFrame.MAX_PAYLOAD)) {
            val original = frame(random.nextBytes(size))

            val decoded = DumlFrame.decodeOrNull(original.encode())

            assertEquals(original, decoded, "failed for payload size $size")
        }
    }

    @Test
    fun `a payload past 255 bytes sets the high length bits`() {
        // The length is 10 bits split across two bytes, so this boundary is easy to get wrong.
        val encoded = frame(ByteArray(300)).encode()

        assertEquals(313, encoded.size)
        assertEquals(313 and 0xFF, encoded[1].toInt() and 0xFF)
        assertEquals(0x04 or ((313 ushr 8) and 0x03), encoded[2].toInt() and 0xFF)
        assertEquals(313, DumlFrame.peekLength(encoded))
    }

    @Test
    fun `a payload too large for the length field is refused at construction`() {
        assertFailsWith<IllegalArgumentException> { frame(ByteArray(DumlFrame.MAX_PAYLOAD + 1)) }
    }

    @Test
    fun `the version round trips`() {
        val original = DumlFrame(target = 0x0102, messageId = 1, type = 0x40, version = 3)

        assertEquals(3, DumlFrame.decodeOrNull(original.encode())?.version)
    }

    @Test
    fun `type accessors unpack flags command set and command id`() {
        val decoded = assertNotNull(DumlFrame.decodeOrNull(frame().encode()))

        assertEquals(0x40, decoded.flags)
        assertEquals(0x07, decoded.commandSet)
        assertEquals(0x45, decoded.commandId)
    }

    @Test
    fun `a frame without the start byte is refused`() {
        val encoded = frame().encode()
        encoded[0] = 0x56

        assertNull(DumlFrame.decodeOrNull(encoded))
        assertEquals(-1, DumlFrame.peekLength(encoded))
    }

    @Test
    fun `a corrupt header checksum is refused`() {
        val encoded = frame().encode()
        encoded[3] = (encoded[3] + 1).toByte()

        assertNull(DumlFrame.decodeOrNull(encoded))
    }

    @Test
    fun `a corrupt payload is refused by the frame checksum`() {
        val encoded = frame(byteArrayOf(0x01, 0x02, 0x03)).encode()
        encoded[DumlFrame.HEADER_SIZE] = 0x7F

        // The header CRC still passes, so only the trailing CRC-16 catches this.
        assertEquals(encoded.size, DumlFrame.peekLength(encoded))
        assertNull(DumlFrame.decodeOrNull(encoded))
    }

    @Test
    fun `a truncated frame is refused rather than read past the end`() {
        val encoded = frame(byteArrayOf(0x01, 0x02, 0x03)).encode()

        for (cut in 1 until encoded.size) {
            assertNull(DumlFrame.decodeOrNull(encoded.copyOf(cut)), "accepted a frame cut to $cut bytes")
        }
    }

    @Test
    fun `a declared length below the minimum is refused`() {
        val encoded = frame().encode()
        encoded[1] = 5
        encoded[3] = DjiCrc.crc8(encoded, 0, 3).toByte() // keep the header self-consistent

        assertEquals(-1, DumlFrame.peekLength(encoded))
    }

    @Test
    fun `decoding honours the offset`() {
        val encoded = frame(byteArrayOf(0x09)).encode()
        val embedded = byteArrayOf(0x00, 0x55, 0xFF.toByte()) + encoded

        val decoded = DumlFrame.decodeOrNull(embedded, offset = 3)

        assertNotNull(decoded)
        assertContentEquals(byteArrayOf(0x09), decoded.payload)
    }

    @Test
    fun `equality is by value including the payload`() {
        assertEquals(frame(byteArrayOf(1, 2, 3)), frame(byteArrayOf(1, 2, 3)))
        assertEquals(frame(byteArrayOf(1, 2, 3)).hashCode(), frame(byteArrayOf(1, 2, 3)).hashCode())
    }
}
