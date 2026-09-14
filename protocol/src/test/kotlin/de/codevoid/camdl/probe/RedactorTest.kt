package de.codevoid.camdl.probe

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RedactorTest {

    @Test
    fun `pseudonyms are numbered per kind and stable`() {
        val redactor = Redactor()

        assertEquals("SSID#1", redactor.register("OsmoAction5-A1B2", "SSID"))
        assertEquals("SSID#2", redactor.register("OsmoAction5-C3D4", "SSID"))
        assertEquals("PSK#1", redactor.register("hunter2hunter2", "PSK"))
        // Same value again must map to the same pseudonym, or a redacted log stops being
        // reasoning-friendly.
        assertEquals("SSID#1", redactor.register("OsmoAction5-A1B2", "SSID"))
    }

    @Test
    fun `null and empty values are ignored so callers need not guard`() {
        val redactor = Redactor()

        assertNull(redactor.register(null, "SSID"))
        assertNull(redactor.register("", "SSID"))
        assertEquals(0, redactor.size())
    }

    @Test
    fun `registered values are replaced in text`() {
        val redactor = Redactor()
        redactor.register("OsmoAction5-A1B2", "SSID")
        redactor.register("hunter2hunter2", "PSK")

        assertEquals(
            "joining SSID#1 with PSK#1",
            redactor.text("joining OsmoAction5-A1B2 with hunter2hunter2"),
        )
    }

    @Test
    fun `a value containing another is replaced whole`() {
        val redactor = Redactor()
        redactor.register("Osmo", "SSID")
        redactor.register("OsmoAction5", "SSID")

        // Shortest-first would leave "SSID#1Action5" behind.
        assertEquals("SSID#2", redactor.text("OsmoAction5"))
    }

    @Test
    fun `mac addresses are scrubbed without being registered first`() {
        val redactor = Redactor()

        assertEquals("device MAC#1 seen", redactor.text("device 3C:5A:B4:12:9F:0E seen"))
    }

    @Test
    fun `the same mac keeps the same pseudonym across calls`() {
        val redactor = Redactor()

        val first = redactor.text("a1:b2:c3:d4:e5:f6")
        val second = redactor.text("a1:b2:c3:d4:e5:f6")

        assertEquals(first, second)
    }

    @Test
    fun `utf8 occurrences in a payload are overwritten`() {
        val redactor = Redactor()
        redactor.register("hunter2", "PSK")
        val frame = "ssid=cam psk=hunter2 end".toByteArray()

        val result = redactor.bytes(frame)

        assertEquals(1, result.replacements)
        assertEquals("ssid=cam psk=XXXXXXX end", String(result.bytes))
    }

    @Test
    fun `every occurrence in a payload is overwritten`() {
        val redactor = Redactor()
        redactor.register("AB", "PSK")

        val result = redactor.bytes("xABxABx".toByteArray())

        assertEquals(2, result.replacements)
        assertEquals("xXXxXXx", String(result.bytes))
    }

    @Test
    fun `the caller's payload is never mutated`() {
        val redactor = Redactor()
        redactor.register("secret", "PSK")
        val original = "a secret value".toByteArray()
        val before = original.copyOf()

        redactor.bytes(original)

        assertContentEquals(before, original)
    }

    @Test
    fun `a payload with nothing to scrub is passed through untouched`() {
        val redactor = Redactor()
        redactor.register("secret", "PSK")
        val frame = byteArrayOf(0x55, 0x10, 0x04)

        val result = redactor.bytes(frame)

        assertEquals(0, result.replacements)
        assertSame(frame, result.bytes)
    }

    @Test
    fun `a needle longer than the payload does not overrun`() {
        val redactor = Redactor()
        redactor.register("a very long pre-shared key", "PSK")

        val result = redactor.bytes(byteArrayOf(0x01, 0x02))

        assertEquals(0, result.replacements)
        assertTrue(result.bytes.contentEquals(byteArrayOf(0x01, 0x02)))
    }
}
