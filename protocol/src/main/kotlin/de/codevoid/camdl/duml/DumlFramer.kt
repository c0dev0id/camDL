package de.codevoid.camdl.duml

/**
 * Reassembles DUML frames from a byte stream.
 *
 * Needed because neither transport delivers whole frames: BLE notifications are capped by the
 * negotiated MTU so one frame arrives in several, while a UDP datagram can carry several
 * frames at once.
 *
 * Anything that does not parse is skipped a byte at a time until the next plausible start of
 * frame. Resynchronising rather than giving up matters during bring-up, when a wrong guess
 * about the protocol would otherwise wedge the stream permanently.
 *
 * Not thread safe; feed it from one place.
 */
class DumlFramer(private val maxBuffered: Int = DEFAULT_MAX_BUFFERED) {

    private var buffer = ByteArray(256)
    private var size = 0

    /** Bytes dropped because they could not begin a valid frame. */
    var discarded: Long = 0
        private set

    val buffered: Int get() = size

    fun offer(chunk: ByteArray): List<DumlFrame> {
        append(chunk)

        val frames = ArrayList<DumlFrame>()
        var start = 0

        while (start < size) {
            val sof = indexOfSof(start)
            if (sof < 0) {
                // Nothing that could start a frame; keep nothing.
                discarded += size - start
                start = size
                break
            }
            if (sof > start) {
                discarded += sof - start
                start = sof
            }

            val total = DumlFrame.peekLength(buffer, start)
            if (total < 0) {
                // Either the header is still incomplete, or its CRC-8 says this 0x55 was
                // payload rather than a start of frame.
                if (size - start < DumlFrame.HEADER_SIZE) break
                discarded++
                start++
                continue
            }
            if (size - start < total) break

            val frame = DumlFrame.decodeOrNull(buffer, start)
            if (frame == null) {
                discarded++
                start++
                continue
            }

            frames += frame
            start += total
        }

        consume(start)
        guardAgainstRunaway()
        return frames
    }

    fun reset() {
        size = 0
        discarded = 0
    }

    private fun append(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        if (size + chunk.size > buffer.size) {
            buffer = buffer.copyOf(maxOf(buffer.size * 2, size + chunk.size))
        }
        chunk.copyInto(buffer, size)
        size += chunk.size
    }

    private fun consume(count: Int) {
        if (count <= 0) return
        buffer.copyInto(buffer, 0, count, size)
        size -= count
    }

    private fun indexOfSof(from: Int): Int {
        for (i in from until size) {
            if (buffer[i].toInt() and 0xFF == DumlFrame.SOF) return i
        }
        return -1
    }

    /**
     * A stream of bytes that never parses would otherwise grow without bound. A frame cannot
     * exceed 1023 bytes, so anything past the cap is noise by definition.
     */
    private fun guardAgainstRunaway() {
        if (size > maxBuffered) {
            discarded += size
            size = 0
        }
    }

    companion object {
        const val DEFAULT_MAX_BUFFERED = 8 * 1024
    }
}
