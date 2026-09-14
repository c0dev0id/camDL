package de.codevoid.camdl.probe

/**
 * `hexdump -C` style formatting, and the inverse.
 *
 * The inverse matters as much as the forward direction: a probe log captured from a real
 * camera is the only source of truth for an undocumented protocol, so [parse] is what turns
 * a pasted-back log into a byte-exact test fixture.
 */
object Hexdump {

    private const val BYTES_PER_LINE = 16
    private const val GROUP = 8

    /** Width of the hex column: 16 pairs plus their trailing spaces, plus the group gap. */
    private const val HEX_WIDTH = BYTES_PER_LINE * 3 + 1

    /**
     * Formats [bytes] as offset / hex / ASCII lines, each prefixed with [indent].
     *
     * At most [limit] bytes are dumped; when there are more, a trailing line says how many
     * were elided. Truncation only ever affects what a human reads - callers that need the
     * bytes themselves already have them.
     */
    fun format(bytes: ByteArray, indent: String = "", limit: Int = Int.MAX_VALUE): List<String> {
        if (bytes.isEmpty()) return listOf(indent + "(empty)")

        val shown = minOf(bytes.size, limit)
        val lines = ArrayList<String>((shown + BYTES_PER_LINE - 1) / BYTES_PER_LINE + 1)
        // 4 digits reads better and covers almost every frame; widen rather than wrap, so
        // parse() can still verify contiguity on a large dump.
        val offsetDigits = if (shown > 0x10000) 8 else 4

        var offset = 0
        while (offset < shown) {
            val end = minOf(offset + BYTES_PER_LINE, shown)
            val hex = StringBuilder(HEX_WIDTH)
            val ascii = StringBuilder(BYTES_PER_LINE)

            for (i in offset until offset + BYTES_PER_LINE) {
                if (i == offset + GROUP) hex.append(' ')
                if (i < end) {
                    val b = bytes[i].toInt() and 0xFF
                    hex.append(HEX[b ushr 4]).append(HEX[b and 0x0F]).append(' ')
                    ascii.append(if (b in 0x20..0x7E) b.toChar() else '.')
                } else {
                    hex.append("   ")
                }
            }

            lines += buildString {
                append(indent)
                appendHex(offset, offsetDigits)
                append("  ")
                append(hex.toString().padEnd(HEX_WIDTH))
                // Second space before the gutter, as `hexdump -C` does: one belongs to the
                // last hex pair, this one separates the columns.
                append(' ')
                append('|')
                append(ascii)
                append('|')
            }
            offset = end
        }

        if (shown < bytes.size) {
            lines += indent + "... " + (bytes.size - shown) + " more bytes"
        }
        return lines
    }

    /**
     * Reads back whatever [format] produced, ignoring indentation, the ASCII gutter and any
     * surrounding log lines. Offsets are verified rather than trusted: a dump with an
     * elision in the middle would otherwise yield a fixture that silently lies.
     */
    fun parse(text: String): ByteArray {
        val out = ArrayList<Byte>()
        var expectedOffset = 0

        for (raw in text.lineSequence()) {
            val match = LINE.matchEntire(raw.trim()) ?: continue

            val offset = match.groupValues[1].toInt(16)
            require(offset == expectedOffset) {
                "hexdump is not contiguous: expected offset ${expectedOffset.toString(16)}," +
                    " got ${match.groupValues[1]}"
            }

            for (pair in match.groupValues[2].trim().split(WHITESPACE)) {
                out += pair.toInt(16).toByte()
            }
            expectedOffset = out.size
        }
        return out.toByteArray()
    }

    /** Locale-independent, unlike `"%04x".format(v)`. */
    private fun StringBuilder.appendHex(value: Int, digits: Int) {
        for (shift in (digits - 1) * 4 downTo 0 step 4) {
            append(HEX[(value ushr shift) and 0xF])
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()
    private val WHITESPACE = Regex("\\s+")

    /** `0000  55 10 04 33  0a 07 45 6f |U..3..Eo|` - the ASCII gutter is optional. */
    private val LINE = Regex("^([0-9a-fA-F]{4,8}) {2}((?:[0-9a-fA-F]{2} +)+)(?:\\|.*\\|)?$")
}
