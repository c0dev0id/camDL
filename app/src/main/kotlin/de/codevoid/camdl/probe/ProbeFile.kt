package de.codevoid.camdl.probe

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter

/**
 * The probe log on disk, appended to and flushed on every single entry.
 *
 * Slow and safe, on purpose. The alternative - buffering, or rendering only when asked - loses
 * exactly the runs worth having, because the interesting ones are the ones that end in a crash.
 * Volumes here are a few hundred lines per connection attempt, so the cost is irrelevant next
 * to never losing a capture again.
 *
 * Entries are written already redacted, so the file is safe to share as it stands, even from a
 * run that happened before the current one.
 */
class ProbeFile(context: Context) {

    private val file = File(File(context.filesDir, "probe").apply { mkdirs() }, "probe.log")
    private val rotated = File(file.parentFile, "probe.log.1")
    private val lock = Any()

    private var writer: PrintWriter? = null

    fun append(lines: List<String>) = synchronized(lock) {
        val out = writer ?: open().also { writer = it }
        lines.forEach(out::println)
        // The flush is the entire point.
        out.flush()
        rotateIfHuge()
    }

    fun append(line: String) = append(listOf(line))

    /**
     * The whole log, oldest first, capped so the on-screen view stays usable. The share export
     * uses [file] directly and is not capped.
     */
    fun read(limit: Int = READ_LIMIT): String = synchronized(lock) {
        writer?.flush()
        val text = (rotated.takeIf { it.isFile }?.readText().orEmpty()) + file.takeIf { it.isFile }?.readText().orEmpty()
        if (text.length <= limit) text else "... earlier entries truncated ...\n" + text.takeLast(limit)
    }

    /** The file to attach when sharing. Flushed first so it is current. */
    fun forSharing(): File = synchronized(lock) {
        writer?.flush()
        file
    }

    fun clear() = synchronized(lock) {
        writer?.close()
        writer = null
        file.delete()
        rotated.delete()
    }

    fun sizeBytes(): Long = synchronized(lock) { file.length() + rotated.length() }

    private fun open() = PrintWriter(FileOutputStream(file, true).bufferedWriter())

    /**
     * Keeps at most two generations. "Nothing gets lost" cannot mean literally forever on a
     * phone, and one rotation guarantees at least the last [MAX_BYTES] are always there.
     */
    private fun rotateIfHuge() {
        if (file.length() < MAX_BYTES) return
        writer?.close()
        writer = null
        rotated.delete()
        file.renameTo(rotated)
    }

    companion object {
        const val MAX_BYTES = 4L * 1024 * 1024
        const val READ_LIMIT = 256 * 1024
    }
}
