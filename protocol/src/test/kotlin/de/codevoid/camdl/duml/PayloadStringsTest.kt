package de.codevoid.camdl.duml

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PayloadStringsTest {

    @Test
    fun `finds a string that packString produced`() {
        val payload = DumlCommands.packString("OsmoAction5-A1B2")

        assertEquals(listOf(PayloadStrings.Found(0, "OsmoAction5-A1B2")), PayloadStrings.find(payload))
    }

    @Test
    fun `finds a string that does not start at offset zero`() {
        // Responses usually lead with a status byte or two, which is exactly why this scans
        // rather than assuming an offset.
        val payload = byteArrayOf(0x00, 0x00) + DumlCommands.packString("cam-ap")

        assertEquals(listOf(PayloadStrings.Found(2, "cam-ap")), PayloadStrings.find(payload))
    }

    @Test
    fun `finds both strings of an ssid and password pair`() {
        val payload = byteArrayOf(0x00) +
            DumlCommands.packString("OsmoAction5-A1B2") +
            DumlCommands.packString("hunter2hunter2")

        assertEquals(
            listOf("OsmoAction5-A1B2", "hunter2hunter2"),
            PayloadStrings.find(payload).map { it.value },
        )
    }

    @Test
    fun `a length running past the end is not a string`() {
        assertEquals(emptyList(), PayloadStrings.find(byteArrayOf(0x40, 0x61, 0x62, 0x63)))
    }

    @Test
    fun `binary runs are rejected`() {
        val payload = byteArrayOf(0x06, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05)

        assertEquals(emptyList(), PayloadStrings.find(payload))
    }

    @Test
    fun `a run with one non printable byte is rejected whole`() {
        val payload = byteArrayOf(0x05, 0x61, 0x62, 0x00, 0x64, 0x65)

        assertEquals(emptyList(), PayloadStrings.find(payload))
    }

    @Test
    fun `del and high bytes count as non printable`() {
        assertEquals(emptyList(), PayloadStrings.find(byteArrayOf(0x04, 0x61, 0x62, 0x63, 0x7F)))
        assertEquals(emptyList(), PayloadStrings.find(byteArrayOf(0x04, 0x61, 0x62, 0x63, 0xC3.toByte())))
    }

    @Test
    fun `short runs are filtered by the minimum length`() {
        val payload = DumlCommands.packString("ab") + DumlCommands.packString("abcdef")

        assertEquals(listOf("abcdef"), PayloadStrings.find(payload, minLength = 4).map { it.value })
        assertTrue(PayloadStrings.find(payload, minLength = 2).map { it.value }.contains("ab"))
    }

    @Test
    fun `an empty payload finds nothing`() {
        assertEquals(emptyList(), PayloadStrings.find(ByteArray(0)))
    }

    @Test
    fun `a zero minimum length is refused rather than matching every byte`() {
        assertFailsWith<IllegalArgumentException> { PayloadStrings.find(ByteArray(4), minLength = 0) }
    }

    @Test
    fun `overlapping candidates are all reported`() {
        // "hello" also contains a plausible run starting one byte in. Reporting both and
        // letting a human read the hexdump beats silently picking one.
        val payload = byteArrayOf(0x05, 0x68, 0x65, 0x6C, 0x6C, 0x6F, 0x21, 0x21, 0x21)

        val found = PayloadStrings.find(payload, minLength = 4)

        assertTrue(found.any { it.value == "hello" }, "got $found")
    }
}
