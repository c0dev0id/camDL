package de.codevoid.camdl.duml

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DumlFramerTest {

    private fun frame(id: Int, payload: ByteArray = ByteArray(0)) = DumlFrame(
        target = DumlCommands.TARGET_APP_TO_WIFI,
        messageId = id,
        type = DumlCommands.type(DumlCommands.FLAG_RESPONSE, 0x07, 0x45),
        payload = payload,
    )

    @Test
    fun `a whole frame in one chunk comes straight back`() {
        val framer = DumlFramer()
        val sent = frame(1, byteArrayOf(0x0A, 0x0B))

        assertEquals(listOf(sent), framer.offer(sent.encode()))
        assertEquals(0, framer.buffered)
    }

    @Test
    fun `a frame split one byte at a time is reassembled`() {
        // This is the BLE case: the negotiated MTU decides where frames get cut.
        val framer = DumlFramer()
        val encoded = frame(2, ByteArray(40) { it.toByte() }).encode()

        val collected = encoded.flatMap { framer.offer(byteArrayOf(it)) }

        assertEquals(1, collected.size)
        assertEquals(frame(2, ByteArray(40) { it.toByte() }), collected.single())
    }

    @Test
    fun `several frames in one chunk all come back in order`() {
        // This is the UDP case: one datagram, several frames.
        val framer = DumlFramer()
        val chunk = frame(1).encode() + frame(2).encode() + frame(3).encode()

        assertEquals(listOf(1, 2, 3), framer.offer(chunk).map { it.messageId })
    }

    @Test
    fun `leading noise is skipped`() {
        val framer = DumlFramer()
        val sent = frame(7)

        val frames = framer.offer(byteArrayOf(0x00, 0x11, 0x22) + sent.encode())

        assertEquals(listOf(sent), frames)
        assertEquals(3, framer.discarded)
    }

    @Test
    fun `a stray start byte inside noise does not wedge the stream`() {
        // 0x55 appears in payloads all the time; the header checksum is what distinguishes a
        // real start of frame from a coincidence.
        val framer = DumlFramer()
        val sent = frame(8)

        val frames = framer.offer(byteArrayOf(0x55, 0x55, 0x55) + sent.encode())

        assertEquals(listOf(sent), frames)
    }

    @Test
    fun `a frame with a bad checksum is dropped and the next one is still found`() {
        val framer = DumlFramer()
        val corrupt = frame(9, byteArrayOf(1, 2, 3)).encode()
        corrupt[corrupt.size - 1] = (corrupt[corrupt.size - 1] + 1).toByte()
        val good = frame(10)

        val frames = framer.offer(corrupt + good.encode())

        assertEquals(listOf(good), frames)
        assertTrue(framer.discarded > 0)
    }

    @Test
    fun `a partial frame is held until the rest arrives`() {
        val framer = DumlFramer()
        val encoded = frame(11, byteArrayOf(0x01, 0x02, 0x03)).encode()

        assertEquals(emptyList(), framer.offer(encoded.copyOf(6)))
        assertTrue(framer.buffered > 0)
        assertEquals(1, framer.offer(encoded.copyOfRange(6, encoded.size)).size)
    }

    @Test
    fun `a frame straddling two chunks with a second frame behind it`() {
        val framer = DumlFramer()
        val first = frame(12, byteArrayOf(0x0F))
        val second = frame(13)
        val all = first.encode() + second.encode()
        val split = first.encode().size - 2

        val head = framer.offer(all.copyOf(split))
        val tail = framer.offer(all.copyOfRange(split, all.size))

        assertEquals(emptyList(), head)
        assertEquals(listOf(first, second), tail)
    }

    @Test
    fun `an empty chunk changes nothing`() {
        val framer = DumlFramer()

        assertEquals(emptyList(), framer.offer(ByteArray(0)))
        assertEquals(0, framer.buffered)
    }

    @Test
    fun `unparseable noise cannot grow the buffer without bound`() {
        val framer = DumlFramer(maxBuffered = 64)

        // 0x55 everywhere: every byte looks like a start of frame and none of them are, so
        // without the cap the buffer would keep every byte forever.
        repeat(10) { framer.offer(ByteArray(32) { 0x55 }) }

        assertTrue(framer.buffered <= 64, "buffered ${framer.buffered}")
    }

    @Test
    fun `reset clears buffered bytes and counters`() {
        val framer = DumlFramer()
        framer.offer(frame(14, byteArrayOf(1, 2, 3)).encode().copyOf(5))

        framer.reset()

        assertEquals(0, framer.buffered)
        assertEquals(0, framer.discarded)
    }

    @Test
    fun `payload bytes survive reassembly intact`() {
        val framer = DumlFramer()
        val payload = ByteArray(200) { (it * 3).toByte() }
        val encoded = frame(15, payload).encode()

        val frames = framer.offer(encoded.copyOf(50)) + framer.offer(encoded.copyOfRange(50, encoded.size))

        assertContentEquals(payload, frames.single().payload)
    }
}
