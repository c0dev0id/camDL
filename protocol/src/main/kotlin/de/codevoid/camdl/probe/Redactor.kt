package de.codevoid.camdl.probe

/**
 * Replaces identifying values with stable pseudonyms so a probe log can be pasted into a
 * bug report or a chat without leaking the camera's Wi-Fi credentials.
 *
 * Redaction happens when the log is rendered, never when it is recorded: the in-memory log
 * keeps the real bytes, because the app itself needs them and because a half-scrubbed
 * capture is worthless as a protocol fixture.
 *
 * Pseudonyms are stable within one log (`SSID#1` is the same SSID everywhere it appears), so
 * a redacted log can still be reasoned about.
 */
class Redactor {

    private val lock = Any()
    private val pseudonyms = LinkedHashMap<String, String>()
    private val counters = HashMap<String, Int>()

    /**
     * Registers a value to scrub, returning the pseudonym it will appear as. Registering the
     * same value twice returns the same pseudonym. Null and blank values are ignored, so
     * callers can register optional fields without guarding.
     */
    fun register(value: String?, kind: String): String? {
        if (value.isNullOrEmpty()) return null
        synchronized(lock) {
            return pseudonyms.getOrPut(value) {
                val n = (counters[kind] ?: 0) + 1
                counters[kind] = n
                "$kind#$n"
            }
        }
    }

    /** Number of distinct values registered, for the log header. */
    fun size(): Int = synchronized(lock) { pseudonyms.size }

    /** Scrubs registered values and anything shaped like a MAC address out of [text]. */
    fun text(text: String): String {
        var out = text
        for ((secret, pseudonym) in ordered()) {
            out = out.replace(secret, pseudonym)
        }
        return out.replace(MAC) { "MAC#${anonymousIndex(it.value)}" }
    }

    /**
     * Scrubs registered values out of a byte payload, returning the scrubbed copy and how
     * many replacements were made so the renderer can say the dump was altered.
     *
     * Only UTF-8 occurrences are matched, which is what the DUML frames carrying the SSID and
     * pre-shared key actually use.
     */
    fun bytes(bytes: ByteArray): Redacted {
        var out = bytes
        var count = 0
        for ((secret, _) in ordered()) {
            val needle = secret.toByteArray(Charsets.UTF_8)
            if (needle.isEmpty()) continue
            var from = 0
            while (true) {
                val at = out.indexOf(needle, from)
                if (at < 0) break
                if (out === bytes) out = bytes.copyOf()
                needle.indices.forEach { out[at + it] = SCRUB }
                count++
                from = at + needle.size
            }
        }
        return Redacted(out, count)
    }

    class Redacted(val bytes: ByteArray, val replacements: Int)

    /** Longest first, so a value containing another is not partially replaced. */
    private fun ordered(): List<Map.Entry<String, String>> =
        synchronized(lock) { pseudonyms.entries.sortedByDescending { it.key.length } }

    /** MAC addresses are scrubbed without being registered first, so they number separately. */
    private fun anonymousIndex(mac: String): Int {
        synchronized(lock) {
            val existing = pseudonyms[mac]
            if (existing != null) return existing.substringAfter('#').toInt()
            val n = (counters["MAC"] ?: 0) + 1
            counters["MAC"] = n
            pseudonyms[mac] = "MAC#$n"
            return n
        }
    }

    private companion object {
        /** ASCII 'X'. */
        const val SCRUB: Byte = 0x58

        val MAC = Regex("\\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\\b")

        fun ByteArray.indexOf(needle: ByteArray, from: Int): Int {
            if (needle.size > size) return -1
            outer@ for (i in from..size - needle.size) {
                for (j in needle.indices) {
                    if (this[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return -1
        }
    }
}
