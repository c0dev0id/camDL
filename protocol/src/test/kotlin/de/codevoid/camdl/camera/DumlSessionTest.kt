package de.codevoid.camdl.camera

import de.codevoid.camdl.duml.DumlCommands
import de.codevoid.camdl.duml.DumlFrame
import de.codevoid.camdl.probe.Direction
import de.codevoid.camdl.probe.ProbeEvent
import de.codevoid.camdl.probe.ProbeLog
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DumlSessionTest {

    /** Channel-backed so delivered bytes are buffered whether or not the reader has started. */
    private class FakeChannel : DumlChannel {
        override val name = "fake"
        val written = mutableListOf<ByteArray>()
        private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        override val inbound: Flow<ByteArray> = incoming.receiveAsFlow()
        override suspend fun write(bytes: ByteArray) {
            written += bytes
        }

        fun deliver(bytes: ByteArray) {
            incoming.trySend(bytes)
        }
    }

    private fun request(messageId: Int = 0x8092, commandId: Int = 0x45) = DumlFrame(
        target = DumlCommands.TARGET_APP_TO_WIFI,
        messageId = messageId,
        type = DumlCommands.type(DumlCommands.FLAG_REQUEST, 0x07, commandId),
    )

    private fun response(messageId: Int = 0x8092, commandId: Int = 0x45, payload: ByteArray = byteArrayOf(0)) =
        DumlFrame(
            target = DumlCommands.TARGET_APP_TO_WIFI,
            messageId = messageId,
            type = DumlCommands.type(DumlCommands.FLAG_RESPONSE, 0x07, commandId),
            payload = payload,
        )

    @Test
    fun `a request resolves on the response that echoes its id and command`() = runTest {
        val channel = FakeChannel()
        val session = DumlSession(channel, ProbeLog(), backgroundScope)

        val pending = async { session.request(request()) }
        runCurrent()
        channel.deliver(response(payload = byteArrayOf(0x01)).encode())

        assertContentEquals(byteArrayOf(0x01), pending.await()?.payload)
    }

    @Test
    fun `the request goes out on the wire encoded`() = runTest {
        val channel = FakeChannel()
        val session = DumlSession(channel, ProbeLog(), backgroundScope)

        launch { session.send(request()) }
        advanceUntilIdle()

        assertContentEquals(request().encode(), channel.written.single())
    }

    @Test
    fun `a frame for a different message id does not satisfy the request`() = runTest {
        val channel = FakeChannel()
        val session = DumlSession(channel, ProbeLog(), backgroundScope)

        val pending = async { session.request(request(messageId = 0x8092)) }
        runCurrent()
        channel.deliver(response(messageId = 0x1234).encode())
        runCurrent()
        channel.deliver(response(messageId = 0x8092, payload = byteArrayOf(0x7F)).encode())

        // Frames arrive interleaved with notifications; taking the next one would misread the
        // camera constantly.
        assertContentEquals(byteArrayOf(0x7F), pending.await()?.payload)
    }

    @Test
    fun `a frame for a different command does not satisfy the request`() = runTest {
        val channel = FakeChannel()
        val session = DumlSession(channel, ProbeLog(), backgroundScope)

        val pending = async { session.request(request(commandId = 0x45), timeout = 5.seconds) }
        runCurrent()
        channel.deliver(response(commandId = 0x47).encode())
        advanceUntilIdle()

        assertNull(pending.await())
    }

    @Test
    fun `a silent camera times out instead of hanging`() = runTest {
        val channel = FakeChannel()
        val log = ProbeLog()
        val session = DumlSession(channel, log, backgroundScope)

        val pending = async { session.request(request(), timeout = 10.seconds) }
        advanceUntilIdle()

        assertNull(pending.await())
        assertTrue(
            log.snapshot().events.any { it is ProbeEvent.Note && it.message == "no response" },
            "the timeout has to be visible in an exported log",
        )
    }

    @Test
    fun `a response split across chunks is still matched`() = runTest {
        val channel = FakeChannel()
        val session = DumlSession(channel, ProbeLog(), backgroundScope)
        val encoded = response(payload = ByteArray(40) { it.toByte() }).encode()

        val pending = async { session.request(request()) }
        runCurrent()
        // The BLE case: the negotiated MTU decides where the frame gets cut.
        encoded.forEach { channel.deliver(byteArrayOf(it)) }

        assertEquals(40, pending.await()?.payload?.size)
    }

    @Test
    fun `frames nobody asked for surface as notifications`() = runTest {
        val channel = FakeChannel()
        val session = DumlSession(channel, ProbeLog(), backgroundScope)
        val seen = CompletableDeferred<DumlFrame>()
        backgroundScope.launch { session.notifications.collect { seen.complete(it) } }
        runCurrent()

        channel.deliver(response(messageId = 0x4242).encode())

        assertEquals(0x4242, seen.await().messageId)
    }

    @Test
    fun `both directions are recorded before they are interpreted`() = runTest {
        val channel = FakeChannel()
        val log = ProbeLog()
        val session = DumlSession(channel, log, backgroundScope)

        val pending = async { session.request(request()) }
        runCurrent()
        channel.deliver(response().encode())
        pending.await()

        val wire = log.snapshot().events.filterIsInstance<ProbeEvent.Wire>()
        assertEquals(listOf(Direction.TX, Direction.RX), wire.map { it.direction })
        assertTrue(wire.all { it.channel == "fake" })
    }

    @Test
    fun `garbage on the wire is recorded and does not break the next response`() = runTest {
        val channel = FakeChannel()
        val log = ProbeLog()
        val session = DumlSession(channel, log, backgroundScope)

        val pending = async { session.request(request()) }
        runCurrent()
        channel.deliver(byteArrayOf(0x00, 0x55, 0x55, 0x13, 0x37))
        runCurrent()
        channel.deliver(response(payload = byteArrayOf(0x2A)).encode())

        assertContentEquals(byteArrayOf(0x2A), pending.await()?.payload)
        // The bytes that made no sense still have to reach the log; during bring-up they are
        // usually the interesting part.
        assertTrue(log.snapshot().events.filterIsInstance<ProbeEvent.Wire>().any { it.bytes.size == 5 })
    }
}
