package de.codevoid.camdl.probe

/**
 * Turns probe events into the text that gets read and shared.
 *
 * Plain ASCII, fixed columns, `hexdump -C` payloads: it has to survive being pasted into a
 * terminal, an issue tracker or a chat window without reflowing into nonsense.
 */
object ProbeRenderer {

    /** Bytes dumped per payload. Enough for any control frame; media bodies are not dumped. */
    const val DEFAULT_DUMP_LIMIT = 512

    private const val TIME_WIDTH = 8
    private const val TAG_WIDTH = 14
    private val PAYLOAD_INDENT = " ".repeat(TIME_WIDTH + 2 + TAG_WIDTH + 2)

    fun render(
        snapshot: ProbeLog.Snapshot,
        redactor: Redactor,
        dumpLimit: Int = DEFAULT_DUMP_LIMIT,
    ): String = buildString {
        append("camDL probe log\n")
        append(snapshot.events.size).append(" events")
        if (snapshot.dropped > 0) append(", ").append(snapshot.dropped).append(" dropped")
        append(", ").append(seconds(snapshot.durationNanos)).append("s")
        val redacted = redactor.size()
        if (redacted > 0) append(", ").append(redacted).append(" values redacted")
        append("\n\n")

        for (event in snapshot.events) {
            for (line in renderEvent(event, redactor, dumpLimit)) {
                appendLine(line)
            }
        }
    }

    /**
     * One event as the lines it occupies: its own line, plus a hexdump when it carries bytes.
     *
     * Separate from [render] so the same formatting can be appended to a file the moment an
     * event happens, rather than only when someone asks for the whole log.
     */
    fun renderEvent(
        event: ProbeEvent,
        redactor: Redactor,
        dumpLimit: Int = DEFAULT_DUMP_LIMIT,
    ): List<String> {
        val lines = ArrayList<String>(4)
        lines += line(event, redactor)

        if (event is ProbeEvent.Wire) {
            val scrubbed = redactor.bytes(event.bytes)
            lines += Hexdump.format(scrubbed.bytes, PAYLOAD_INDENT, dumpLimit)
            if (scrubbed.replacements > 0) {
                lines += PAYLOAD_INDENT + "(${scrubbed.replacements} redacted span(s) overwritten with 'X')"
            }
        }
        return lines
    }

    private fun line(event: ProbeEvent, redactor: Redactor): String = buildString {
        append(seconds(event.at).padStart(TIME_WIDTH))
        append("  ")
        append(event.tag.take(TAG_WIDTH).padEnd(TAG_WIDTH))
        append("  ")

        when (event) {
            is ProbeEvent.StageBegin -> append("begin")

            is ProbeEvent.StageEnd -> {
                if (event.failure == null) {
                    append("end   ok")
                } else {
                    append("end   FAIL ").append(redactor.text(event.failure))
                }
                append(" +").append(seconds(event.elapsedNanos)).append("s")
            }

            is ProbeEvent.Note -> {
                append(redactor.text(event.message))
                for ((key, value) in event.fields) {
                    append(' ').append(key).append('=').append(redactor.text(value))
                }
            }

            is ProbeEvent.Wire -> {
                append(if (event.direction == Direction.TX) "tx " else "rx ")
                append(redactor.text(event.channel))
                append(' ').append(event.bytes.size).append('B')
            }
        }
    }

    /**
     * Seconds with millisecond precision, built by hand rather than with `"%.3f".format()` -
     * that is locale-dependent and would emit `1,390` on a German device, which is both ugly
     * and ambiguous next to comma-separated fields.
     */
    internal fun seconds(nanos: Long): String {
        val negative = nanos < 0
        val abs = if (negative) -nanos else nanos
        val whole = abs / 1_000_000_000
        val millis = (abs % 1_000_000_000) / 1_000_000
        return buildString {
            if (negative) append('-')
            append(whole)
            append('.')
            append(millis.toString().padStart(3, '0'))
        }
    }
}
