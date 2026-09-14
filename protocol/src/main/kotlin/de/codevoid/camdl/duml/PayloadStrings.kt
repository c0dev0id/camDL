package de.codevoid.camdl.duml

/**
 * Finds length-prefixed strings anywhere in a DUML payload.
 *
 * A discovery tool, not a parser. When the layout of a response is known, read it directly;
 * this is for the case where it is not, which right now is most of them. The provisioning
 * response is the immediate motivation: it is not established whether `0x07/0x47` tells the
 * camera which access point to raise or reports the one it chose, and an SSID turning up in
 * the reply settles it. Layouts here usually put a status byte or two before the strings, so
 * scanning beats assuming an offset.
 *
 * False positives are possible and acceptable - the raw hexdump sits directly above this in
 * the log, so anything implausible is visible for what it is.
 */
object PayloadStrings {

    const val DEFAULT_MIN_LENGTH = 4

    data class Found(val offset: Int, val value: String) {
        override fun toString(): String = "@$offset=\"$value\""
    }

    /**
     * Every offset where a `[len:u8][printable ascii]` run of at least [minLength] characters
     * fits inside [payload].
     *
     * Restricted to printable ASCII on purpose: it is what SSIDs and pre-shared keys are made
     * of, and admitting arbitrary UTF-8 would match almost any byte sequence.
     */
    fun find(payload: ByteArray, minLength: Int = DEFAULT_MIN_LENGTH): List<Found> {
        require(minLength >= 1) { "minLength must be at least 1" }

        val found = ArrayList<Found>()
        for (i in payload.indices) {
            val length = payload[i].toInt() and 0xFF
            if (length < minLength) continue
            if (i + 1 + length > payload.size) continue

            var printable = true
            for (j in i + 1 until i + 1 + length) {
                val b = payload[j].toInt() and 0xFF
                if (b !in 0x20..0x7E) {
                    printable = false
                    break
                }
            }
            if (printable) {
                found += Found(i, String(payload, i + 1, length, Charsets.US_ASCII))
            }
        }
        return found
    }
}
