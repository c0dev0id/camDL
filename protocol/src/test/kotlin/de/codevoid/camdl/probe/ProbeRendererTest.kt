package de.codevoid.camdl.probe

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ProbeRendererTest {

    private fun snapshot(vararg events: ProbeEvent, dropped: Int = 0, duration: Long = 0) =
        ProbeLog.Snapshot(events.toList(), dropped, duration)

    @Test
    fun `seconds are formatted without depending on the default locale`() {
        // "%.3f".format(1.39) emits "1,390" under a German locale, which would be ambiguous
        // next to the key=value fields on the same line.
        assertEquals("1.390", ProbeRenderer.seconds(1_390_000_000))
        assertEquals("0.000", ProbeRenderer.seconds(0))
        assertEquals("0.007", ProbeRenderer.seconds(7_000_000))
        assertEquals("12.000", ProbeRenderer.seconds(12_000_000_000))
        assertEquals("-0.500", ProbeRenderer.seconds(-500_000_000))
    }

    @Test
    fun `sub millisecond timings are floored rather than rounded away`() {
        assertEquals("0.000", ProbeRenderer.seconds(999_999))
        assertEquals("0.001", ProbeRenderer.seconds(1_000_000))
    }

    @Test
    fun `the header summarises the session`() {
        val text = ProbeRenderer.render(
            snapshot(ProbeEvent.Note(0, "app", "hello"), dropped = 3, duration = 2_500_000_000),
            Redactor(),
        )

        assertTrue(text.startsWith("camDL probe log\n"), "got $text")
        assertTrue(text.contains("1 events, 3 dropped, 2.500s"), "got $text")
    }

    @Test
    fun `a clean header omits the dropped and redacted counts`() {
        val text = ProbeRenderer.render(snapshot(ProbeEvent.Note(0, "app", "hello")), Redactor())

        assertFalse(text.contains("dropped"))
        assertFalse(text.contains("redacted"))
    }

    @Test
    fun `stage lines report outcome and elapsed time`() {
        val text = ProbeRenderer.render(
            snapshot(
                ProbeEvent.StageBegin(0, "ble-pair"),
                ProbeEvent.StageEnd(1_390_000_000, "ble-pair", 1_390_000_000, failure = null),
                ProbeEvent.StageEnd(2_000_000_000, "wifi-join", 610_000_000, failure = "IOException: no route"),
            ),
            Redactor(),
        )

        assertTrue(text.contains("begin"), "got $text")
        assertTrue(text.contains("end   ok +1.390s"), "got $text")
        assertTrue(text.contains("end   FAIL IOException: no route +0.610s"), "got $text")
    }

    @Test
    fun `note fields are rendered as key equals value`() {
        val text = ProbeRenderer.render(
            snapshot(ProbeEvent.Note(0, "wifi", "joining", mapOf("ssid" to "cam-1", "bound" to "true"))),
            Redactor(),
        )

        assertTrue(text.contains("joining ssid=cam-1 bound=true"), "got $text")
    }

    @Test
    fun `a wire event is followed by its hexdump`() {
        val text = ProbeRenderer.render(
            snapshot(ProbeEvent.Wire(0, "ble", Direction.TX, "gatt/fff5", "Hi".toByteArray())),
            Redactor(),
        )

        assertTrue(text.contains("tx gatt/fff5 2B"), "got $text")
        assertTrue(text.contains("0000  48 69"), "got $text")
        assertTrue(text.contains("|Hi|"), "got $text")
    }

    @Test
    fun `payload dumps line up under the message column`() {
        val text = ProbeRenderer.render(
            snapshot(ProbeEvent.Wire(0, "ble", Direction.RX, "gatt/fff4", ByteArray(4))),
            Redactor(),
        )

        val lines = text.lines()
        val header = lines.first { it.contains("rx gatt/fff4") }
        val dump = lines.first { it.contains("0000  00 00") }

        assertEquals(header.indexOf("rx gatt"), dump.indexOf("0000"))
    }

    @Test
    fun `registered secrets are scrubbed from messages and payloads alike`() {
        val redactor = Redactor()
        redactor.register("OsmoAction5-A1B2", "SSID")
        redactor.register("hunter2", "PSK")

        val text = ProbeRenderer.render(
            snapshot(
                ProbeEvent.Note(0, "wifi", "joining", mapOf("ssid" to "OsmoAction5-A1B2")),
                ProbeEvent.Wire(0, "ble", Direction.TX, "gatt/fff5", "psk=hunter2".toByteArray()),
            ),
            redactor,
        )

        assertTrue(text.contains("ssid=SSID#1"), "got $text")
        assertFalse(text.contains("OsmoAction5-A1B2"), "SSID leaked: $text")
        assertFalse(text.contains("hunter2"), "PSK leaked in ascii gutter: $text")
        assertTrue(text.contains("redacted span(s) overwritten"), "got $text")
        assertTrue(text.contains("2 values redacted"), "got $text")
    }

    @Test
    fun `a failure message is scrubbed too`() {
        val redactor = Redactor()
        redactor.register("OsmoAction5-A1B2", "SSID")

        val text = ProbeRenderer.render(
            snapshot(ProbeEvent.StageEnd(0, "wifi-join", 0, "could not join OsmoAction5-A1B2")),
            redactor,
        )

        assertTrue(text.contains("could not join SSID#1"), "got $text")
    }

    @Test
    fun `large payloads are truncated in the dump`() {
        val text = ProbeRenderer.render(
            snapshot(ProbeEvent.Wire(0, "http", Direction.RX, "GET /v2", ByteArray(4096))),
            Redactor(),
            dumpLimit = 64,
        )

        assertTrue(text.contains("... 4032 more bytes"), "got $text")
    }

    @Test
    fun `a rendered capture can be read back as a fixture`() {
        val frame = byteArrayOf(0x55, 0x10, 0x04, 0x33, 0x0A, 0x07, 0x45)
        val text = ProbeRenderer.render(
            snapshot(ProbeEvent.Wire(0, "ble", Direction.TX, "gatt/fff5", frame)),
            Redactor(),
        )

        // This round trip is the whole workflow: capture on a phone, paste the log back,
        // turn the bytes into a test.
        assertContentEquals(frame, Hexdump.parse(text))
    }
}
