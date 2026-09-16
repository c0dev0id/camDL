package de.codevoid.camdl.camera

import de.codevoid.camdl.duml.DumlCommands
import de.codevoid.camdl.duml.DumlFrame
import de.codevoid.camdl.probe.Direction
import de.codevoid.camdl.probe.ProbeEvent
import de.codevoid.camdl.probe.ProbeLog
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    /**
     * Channel-backed so delivered bytes are buffered whether or not the reader has started.
     * Logs its own bytes, as the [DumlChannel] contract requires of implementations.
     */
    private class FakeChannel(private val probe: ProbeLog = ProbeLog()) : DumlChannel {
        override val name = "fake"
        val written = mutableListOf<ByteArray>()
        private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        override val inbound: Flow<ByteArray> = incoming.receiveAsFlow()

        override suspend fun write(bytes: ByteArray) {
            probe.wire("fake", Direction.TX, name, bytes)
            written += bytes
        }

        fun deliver(bytes: ByteArray) {
            probe.wire("fake", Direction.RX, name, bytes)
            incoming.trySend(bytes)
        }

        /** What GattClient does when the BLE link drops. */
        fun fail(cause: Throwable) {
            incoming.close(cause)
        }

        fun closeCleanly() {
            incoming.close()
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
    fun `frames nobody asked for surface as notifications and say so in the log`() = runTest {
        val log = ProbeLog()
        val channel = FakeChannel(log)
        val session = DumlSession(channel, log, backgroundScope)
        val seen = CompletableDeferred<DumlFrame>()
        backgroundScope.launch { session.notifications.collect { seen.complete(it) } }
        runCurrent()

        channel.deliver(response(messageId = 0x4242).encode())

        assertEquals(0x4242, seen.await().messageId)
        assertTrue(
            log.snapshot().events.filterIsInstance<ProbeEvent.Note>()
                .any { it.message == "rx" && it.fields["for"] == "nobody" },
        )
    }

    @Test
    fun `the session logs decoded frames and the channel logs the bytes`() = runTest {
        val log = ProbeLog()
        val channel = FakeChannel(log)
        val session = DumlSession(channel, log, backgroundScope)

        val pending = async { session.request(request()) }
        runCurrent()
        channel.deliver(response().encode())
        pending.await()

        val events = log.snapshot().events
        // Raw bytes, once, from the channel.
        assertEquals(
            listOf(Direction.TX, Direction.RX),
            events.filterIsInstance<ProbeEvent.Wire>().map { it.direction },
        )
        // Decoded meaning, once, from the session - not the same bytes a second time.
        val notes = events.filterIsInstance<ProbeEvent.Note>()
        assertTrue(notes.any { it.message == "tx" && it.fields["cmd"] == "0x7/0x45#0x8092" }, "got $notes")
        assertTrue(notes.any { it.message == "rx" && it.fields["for"] == "request" }, "got $notes")
    }

    // The three tests below run the session on the test scope rather than backgroundScope,
    // because each one ends the channel: the reader then completes on its own, and foreground
    // work is what advanceUntilIdle actually drives.

    @Test
    fun `a link that dies does not bring the process down`() = runTest {
        val log = ProbeLog()
        val channel = FakeChannel(log)
        DumlSession(channel, log, this)
        runCurrent()

        channel.fail(IOException("disconnected with status 19"))
        advanceUntilIdle()

        // The reader is a bare coroutine: an exception escaping it would reach the scope's
        // handler and take the whole app down, losing the log that explains why. This test
        // completing at all is half the assertion - an escaped throwable fails runTest.
        assertTrue(
            log.snapshot().events.filterIsInstance<ProbeEvent.Note>()
                .any { it.message == "link failed" && it.fields["cause"]?.contains("status 19") == true },
            "the cause has to survive into the log: ${log.snapshot().events}",
        )
    }

    @Test
    fun `a request in flight when the link dies fails instead of waiting out its timeout`() = runTest {
        val log = ProbeLog()
        val channel = FakeChannel(log)
        val session = DumlSession(channel, log, this)

        // runCatching inside the async: a failing async cancels its parent, which here is the
        // test itself.
        val pending = async { runCatching { session.request(request(), timeout = 30.seconds) } }
        runCurrent()
        channel.fail(IOException("disconnected with status 19"))

        val thrown = assertNotNull(pending.await().exceptionOrNull())
        assertTrue(thrown.message?.contains("status 19") == true, "got ${thrown.message}")
        // Reporting the real cause now beats reporting a vaguer one thirty seconds from now.
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `a link closing cleanly is recorded too`() = runTest {
        val log = ProbeLog()
        val channel = FakeChannel(log)
        DumlSession(channel, log, this)
        runCurrent()

        channel.closeCleanly()
        advanceUntilIdle()

        assertTrue(
            log.snapshot().events.filterIsInstance<ProbeEvent.Note>().any { it.message == "link closed" },
        )
    }

    @Test
    fun `garbage on the wire is recorded and does not break the next response`() = runTest {
        val log = ProbeLog()
        val channel = FakeChannel(log)
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
