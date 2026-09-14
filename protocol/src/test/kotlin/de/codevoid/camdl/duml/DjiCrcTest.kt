package de.codevoid.camdl.duml

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * The real proof of these constants is the camera accepting a frame, which only happens on a
 * device. What can be pinned down here is that the seeds and polynomial are the intended ones
 * and have not been fat-fingered.
 */
class DjiCrcTest {

    @Test
    fun `an empty run returns the seed, which pins down the init values`() {
        assertEquals(DjiCrc.CRC8_INIT, DjiCrc.crc8(ByteArray(0)))
        assertEquals(DjiCrc.CRC16_INIT, DjiCrc.crc16(ByteArray(0)))
    }

    @Test
    fun `crc8 of a single zero byte matches the value worked out by hand`() {
        // init 0x77, poly 0x8C, reflected. Eight rounds over 0x77:
        // b7 -> d7 -> e7 -> ff -> f3 -> f5 -> f6 -> 7b
        assertEquals(0x7B, DjiCrc.crc8(byteArrayOf(0x00)))
    }

    @Test
    fun `the checksums depend on byte order`() {
        val forward = byteArrayOf(0x01, 0x02, 0x03)
        val reversed = byteArrayOf(0x03, 0x02, 0x01)

        assertEquals(false, DjiCrc.crc8(forward) == DjiCrc.crc8(reversed))
        assertEquals(false, DjiCrc.crc16(forward) == DjiCrc.crc16(reversed))
    }

    @Test
    fun `the range bounds are honoured`() {
        val data = byteArrayOf(0x7F, 0x01, 0x02, 0x03, 0x7F)

        assertEquals(DjiCrc.crc8(byteArrayOf(0x01, 0x02, 0x03)), DjiCrc.crc8(data, 1, 4))
        assertEquals(DjiCrc.crc16(byteArrayOf(0x01, 0x02, 0x03)), DjiCrc.crc16(data, 1, 4))
    }

    @Test
    fun `results stay inside their width`() {
        for (b in 0..255) {
            val data = byteArrayOf(b.toByte(), (b * 7).toByte())
            assertEquals(DjiCrc.crc8(data), DjiCrc.crc8(data) and 0xFF)
            assertEquals(DjiCrc.crc16(data), DjiCrc.crc16(data) and 0xFFFF)
        }
    }
}
