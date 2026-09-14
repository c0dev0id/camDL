package de.codevoid.camdl.probe

import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ProbeLogTest {

    /** Advances by a fixed millisecond on every read, so timestamps are predictable. */
    private class TickClock(private val stepNanos: Long = 1_000_000) : Clock {
        private var now = 0L
        override fun nanos(): Long {
            now += stepNanos
            return now
        }
    }

    @Test
    fun `timestamps are relative to the start of the log`() {
        val log = ProbeLog(clock = TickClock())

        log.note("app", "first")
        log.note("app", "second")

        val events = log.snapshot().events
        assertEquals(2, events.size)
        // First read is consumed by the constructor, so the first event lands at one tick.
        assertEquals(1_000_000, events[0].at)
        assertEquals(2_000_000, events[1].at)
    }

    @Test
    fun `the ring buffer drops the oldest and counts what it dropped`() {
        val log = ProbeLog(capacity = 3, clock = TickClock())

        repeat(5) { log.note("app", "event $it") }

        val snapshot = log.snapshot()
        assertEquals(2, snapshot.dropped)
        assertEquals(
            listOf("event 2", "event 3", "event 4"),
            snapshot.events.map { (it as ProbeEvent.Note).message },
        )
    }

    @Test
    fun `a payload is copied so a reused receive buffer cannot rewrite history`() {
        val log = ProbeLog(clock = TickClock())
        val buffer = byteArrayOf(0x01, 0x02, 0x03)

        log.wire("udp", Direction.RX, "udp/9004", buffer)
        buffer[0] = 0x7F

        val recorded = log.snapshot().events.single() as ProbeEvent.Wire
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03), recorded.bytes)
    }

    @Test
    fun `a successful stage is bracketed with its elapsed time`() = runTest {
        val log = ProbeLog(clock = TickClock())

        val result = log.stage("ble-pair") { "paired" }

        assertEquals("paired", result)
        val events = log.snapshot().events
        assertTrue(events[0] is ProbeEvent.StageBegin)
        val end = events[1] as ProbeEvent.StageEnd
        assertEquals("ble-pair", end.tag)
        assertNull(end.failure)
        assertTrue(end.elapsedNanos > 0)
    }

    @Test
    fun `a failing stage records the failure and rethrows it`() = runTest {
        val log = ProbeLog(clock = TickClock())

        assertFailsWith<IOException> {
            log.stage("wifi-join") { throw IOException("timed out waiting for onAvailable") }
        }

        val end = log.snapshot().events.last() as ProbeEvent.StageEnd
        assertEquals("IOException: timed out waiting for onAvailable", end.failure)
    }

    @Test
    fun `a failure with no message still names its type`() = runTest {
        val log = ProbeLog(clock = TickClock())

        assertFailsWith<IllegalStateException> { log.stage("duml") { throw IllegalStateException() } }

        assertEquals("IllegalStateException", (log.snapshot().events.last() as ProbeEvent.StageEnd).failure)
    }

    @Test
    fun `cancellation is recorded rather than swallowed`() = runTest {
        val log = ProbeLog(clock = TickClock())

        assertFailsWith<CancellationException> {
            log.stage("ble-pair") { throw CancellationException("user backed out") }
        }

        // Without this, "the user gave up" and "it hung forever" are indistinguishable.
        val end = log.snapshot().events.last() as ProbeEvent.StageEnd
        assertNotNull(end.failure)
        assertTrue(end.failure.contains("user backed out"), "got ${end.failure}")
    }

    @Test
    fun `nested stages nest in the log`() = runTest {
        val log = ProbeLog(clock = TickClock())

        log.stage("connect") {
            log.stage("ble-pair") { }
        }

        assertEquals(
            listOf("connect", "ble-pair", "ble-pair", "connect"),
            log.snapshot().events.map { it.tag },
        )
    }

    @Test
    fun `notes carry structured fields`() {
        val log = ProbeLog(clock = TickClock())

        log.note("wifi", "joining", mapOf("ssid" to "cam-1", "bound" to "true"))

        val note = log.snapshot().events.single() as ProbeEvent.Note
        assertEquals(mapOf("ssid" to "cam-1", "bound" to "true"), note.fields)
    }
}
