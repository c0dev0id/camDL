package de.codevoid.camdl.duml

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class DumlCommandsTest {

    @Test
    fun `type packs flags command set and command id little endian`() {
        assertEquals(0x450740, DumlCommands.type(DumlCommands.FLAG_REQUEST, 0x07, 0x45))
        assertEquals(0x470740, DumlCommands.type(DumlCommands.FLAG_REQUEST, 0x07, 0x47))
    }

    @Test
    fun `packString writes a u8 length then utf8`() {
        assertContentEquals(byteArrayOf(4, 0x6F, 0x73, 0x6D, 0x6F), DumlCommands.packString("osmo"))
        assertContentEquals(byteArrayOf(0), DumlCommands.packString(""))
    }

    @Test
    fun `packString counts utf8 bytes and not characters`() {
        // "ü" is two bytes; a length prefix counting characters would desynchronise the frame.
        assertContentEquals(byteArrayOf(2, 0xC3.toByte(), 0xBC.toByte()), DumlCommands.packString("ü"))
    }

    @Test
    fun `packString refuses a string that cannot be described by its prefix`() {
        assertFailsWith<IllegalArgumentException> { DumlCommands.packString("a".repeat(256)) }
    }

    @Test
    fun `the pairing frame carries the identifier before the pin`() {
        val frame = DumlCommands.setPairingPin(pin = "osmo", identifier = "abc")

        assertEquals(DumlCommands.TARGET_APP_TO_WIFI, frame.target)
        assertEquals(DumlCommands.MESSAGE_ID_PAIR, frame.messageId)
        assertEquals(0x07, frame.commandSet)
        assertEquals(0x45, frame.commandId)
        assertEquals(DumlCommands.FLAG_REQUEST, frame.flags)
        // Order matters and is not guessable from the command name.
        assertContentEquals(
            byteArrayOf(3, 0x61, 0x62, 0x63) + byteArrayOf(4, 0x6F, 0x73, 0x6D, 0x6F),
            frame.payload,
        )
    }

    @Test
    fun `the pairing frame defaults to the camera pin`() {
        val payload = DumlCommands.setPairingPin().payload
        val identifier = DumlCommands.packString(DumlCommands.DEFAULT_IDENTIFIER)

        assertContentEquals(identifier + DumlCommands.packString("osmo"), payload)
    }

    @Test
    fun `the wifi frame carries ssid then password`() {
        val frame = DumlCommands.connectToWifi("cam", "secret")

        assertEquals(DumlCommands.MESSAGE_ID_WIFI, frame.messageId)
        assertEquals(0x47, frame.commandId)
        assertContentEquals(
            DumlCommands.packString("cam") + DumlCommands.packString("secret"),
            frame.payload,
        )
    }

    @Test
    fun `command frames survive an encode and decode round trip`() {
        for (frame in listOf(DumlCommands.setPairingPin(), DumlCommands.connectToWifi("cam", "secret"))) {
            assertEquals(frame, DumlFrame.decodeOrNull(frame.encode()))
        }
    }
}
