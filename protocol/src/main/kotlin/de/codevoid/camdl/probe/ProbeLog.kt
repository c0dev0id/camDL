package de.codevoid.camdl.probe

/** Monotonic time source. Injectable so tests get deterministic timestamps. */
fun interface Clock {
    fun nanos(): Long

    companion object {
        val SYSTEM = Clock { System.nanoTime() }
    }
}

/**
 * The session record for one attempt at talking to a camera.
 *
 * Every test of this app costs a CI build and a sideload, so a run that produces nothing but
 * "it failed" is a wasted round trip. Everything the transports do lands here, and the whole
 * thing can be exported and read back.
 *
 * Safe to call from any thread: BLE callbacks arrive on binder threads while the connect
 * chain runs on coroutine dispatchers.
 */
class ProbeLog(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: Clock = Clock.SYSTEM,
) {
    private val lock = Any()
    private val started = clock.nanos()
    private val events = ArrayDeque<ProbeEvent>()
    private var dropped = 0

    val redactor = Redactor()

    fun note(tag: String, message: String, fields: Map<String, String> = emptyMap()) {
        record(ProbeEvent.Note(now(), tag, message, fields))
    }

    fun wire(tag: String, direction: Direction, channel: String, bytes: ByteArray) {
        // Defensive copy: callers reuse receive buffers, and a log that mutates after the
        // fact is worse than no log.
        record(ProbeEvent.Wire(now(), tag, direction, channel, bytes.copyOf()))
    }

    /**
     * Runs [block] as a named stage, bracketing it with begin/end events.
     *
     * Failures are recorded and rethrown - this reports, it does not handle. Cancellation is
     * recorded too, because "the user backed out" and "it hung" look identical in a log that
     * omits it.
     */
    suspend fun <T> stage(name: String, block: suspend () -> T): T {
        val begin = now()
        record(ProbeEvent.StageBegin(begin, name))
        try {
            val result = block()
            val end = now()
            record(ProbeEvent.StageEnd(end, name, end - begin, failure = null))
            return result
        } catch (t: Throwable) {
            val end = now()
            record(ProbeEvent.StageEnd(end, name, end - begin, failure = describe(t)))
            throw t
        }
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(events.toList(), dropped, now())
    }

    class Snapshot(
        val events: List<ProbeEvent>,
        /** Events discarded because the ring buffer was full. */
        val dropped: Int,
        val durationNanos: Long,
    )

    private fun record(event: ProbeEvent) = synchronized(lock) {
        // Drop from the front: on a long download the interesting part is always the end.
        while (events.size >= capacity) {
            events.removeFirst()
            dropped++
        }
        events.addLast(event)
    }

    private fun now() = clock.nanos() - started

    private fun describe(t: Throwable): String {
        val message = t.message
        return if (message.isNullOrBlank()) t.javaClass.simpleName else "${t.javaClass.simpleName}: $message"
    }

    companion object {
        const val DEFAULT_CAPACITY = 4096
    }
}
