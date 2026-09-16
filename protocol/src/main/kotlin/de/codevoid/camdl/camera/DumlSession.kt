package de.codevoid.camdl.camera

import de.codevoid.camdl.duml.DumlCommands
import de.codevoid.camdl.duml.DumlFrame
import de.codevoid.camdl.duml.DumlFramer
import de.codevoid.camdl.probe.ProbeLog
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns a byte pipe into request/response over DUML.
 *
 * Responses echo the message id and command of their request, which is the only thing tying
 * the two together - frames arrive interleaved with unsolicited notifications, so a session
 * that assumed the next frame was its answer would misread the camera constantly.
 *
 * Logs decoded frames, not bytes. The channel records what actually moved - which is not
 * always what it was handed, since a frame can be split across several writes - so the log
 * ends up with the raw hexdump and the decoded meaning next to each other rather than the
 * same bytes twice.
 */
class DumlSession(
    private val channel: DumlChannel,
    private val probe: ProbeLog,
    scope: CoroutineScope,
    /** Subsystem name for the probe log. */
    private val tag: String = "duml",
) {
    private val framer = DumlFramer()
    private val pending = HashMap<Key, CompletableDeferred<DumlFrame>>()
    private val pendingLock = Mutex()
    private val writeLock = Mutex()

    private val _notifications = MutableSharedFlow<DumlFrame>(extraBufferCapacity = 64)

    /** Frames that answered nobody: status pushes, heartbeat replies, unsolicited events. */
    val notifications: SharedFlow<DumlFrame> = _notifications

    /**
     * Drains the channel for the life of the session.
     *
     * Every failure is caught. An uncaught exception in a coroutine takes the whole process
     * down, and a transport dying is an ordinary event here - cameras drop BLE links - so
     * letting one escape would kill the app and with it the log that explains why.
     */
    private val reader: Job = scope.launch {
        try {
            channel.inbound.collect { chunk ->
                for (frame in framer.offer(chunk)) {
                    dispatch(frame)
                }
            }
            end("link closed", null)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            end("link failed", t)
        }
    }

    /**
     * Records why the link ended and fails everyone waiting on it.
     *
     * Without this, a request in flight when the link dropped would sit out its full timeout
     * before reporting something far less specific than the real cause.
     */
    private suspend fun end(what: String, cause: Throwable?) {
        probe.note(tag, what, if (cause == null) emptyMap() else mapOf("cause" to cause.toString()))

        val failure = cause ?: IOException("the link closed")
        val orphaned = pendingLock.withLock {
            pending.values.toList().also { pending.clear() }
        }
        orphaned.forEach { it.completeExceptionally(failure) }
    }

    /** Sends a frame without waiting for anything back. */
    suspend fun send(frame: DumlFrame) {
        val bytes = frame.encode()
        // Serialised because the transports underneath are not reentrant: a GATT write must
        // complete before the next one starts.
        writeLock.withLock {
            probe.note(tag, "tx", mapOf("cmd" to key(frame).toString(), "bytes" to bytes.size.toString()))
            channel.write(bytes)
        }
    }

    /**
     * Sends [frame] and waits for the matching response.
     *
     * Returns null on timeout rather than throwing, because a silent camera is an ordinary
     * outcome during bring-up and the caller usually wants to record it and move on. A link
     * that *dies* is different and does throw - it is a specific, actionable cause, and
     * waiting out the timeout to report something vaguer would be worse.
     */
    suspend fun request(frame: DumlFrame, timeout: Duration = DEFAULT_TIMEOUT): DumlFrame? {
        val key = key(frame)
        val waiter = CompletableDeferred<DumlFrame>()

        pendingLock.withLock {
            // A second request on the same key abandons the first; nothing should do that, but
            // leaving a deferred nobody will ever complete would hang that caller forever.
            pending.put(key, waiter)?.cancel(CancellationException("superseded by a later request"))
        }

        try {
            send(frame)
            val response = withTimeoutOrNull(timeout) { waiter.await() }
            if (response == null) {
                probe.note(
                    tag,
                    "no response",
                    mapOf("cmd" to key.toString(), "waited" to timeout.toString()),
                )
            }
            return response
        } finally {
            pendingLock.withLock { pending.remove(key, waiter) }
        }
    }

    fun close() {
        reader.cancel()
    }

    private suspend fun dispatch(frame: DumlFrame) {
        val key = key(frame)
        val waiter = pendingLock.withLock { pending.remove(key) }

        probe.note(
            tag,
            "rx",
            mapOf(
                "cmd" to key.toString(),
                "flags" to "0x${frame.flags.toString(16)}",
                "payload" to "${frame.payload.size}B",
                "for" to if (waiter != null) "request" else "nobody",
            ),
        )

        if (waiter != null) {
            waiter.complete(frame)
        } else {
            _notifications.emit(frame)
        }
    }

    private fun key(frame: DumlFrame) = Key(frame.messageId, frame.commandSet, frame.commandId)

    /**
     * Flags are excluded on purpose: a request goes out with 0x40 and its answer comes back
     * with 0xC0, so matching on them would never pair anything up.
     */
    private data class Key(val messageId: Int, val commandSet: Int, val commandId: Int) {
        override fun toString(): String =
            "0x${commandSet.toString(16)}/0x${commandId.toString(16)}#0x${messageId.toString(16)}"
    }

    companion object {
        /**
         * Generous: pairing waits on a person tapping approve on the camera. Callers that know
         * better should say so.
         */
        val DEFAULT_TIMEOUT = 30.seconds

        /** Convenience for the two commands that make up the BLE half of the handshake. */
        suspend fun DumlSession.pair(
            pin: String = DumlCommands.CAMERA_PIN,
            identifier: String = DumlCommands.DEFAULT_IDENTIFIER,
        ): DumlFrame? = request(DumlCommands.setPairingPin(pin, identifier))

        suspend fun DumlSession.provisionWifi(ssid: String, password: String): DumlFrame? =
            request(DumlCommands.connectToWifi(ssid, password))
    }
}
