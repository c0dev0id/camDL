package de.codevoid.camdl.probe

import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class HexdumpTest {

    @Test
    fun `empty payload is called out rather than dumped as nothing`() {
        assertEquals(listOf("(empty)"), Hexdump.format(ByteArray(0)))
    }

    @Test
    fun `a full line has the group gap and an ascii gutter`() {
        val bytes = "Hello, hexdump!!".toByteArray()
        val lines = Hexdump.format(bytes)

        assertEquals(1, lines.size)
        assertEquals(
            "0000  48 65 6c 6c 6f 2c 20 68  65 78 64 75 6d 70 21 21  |Hello, hexdump!!|",
            lines.single(),
        )
    }

    @Test
    fun `non printable bytes become dots without disturbing the columns`() {
        val lines = Hexdump.format(byteArrayOf(0x00, 0x1F, 0x41, 0x7E, 0x7F, 0xFF.toByte()))

        assertEquals("0000  00 1f 41 7e 7f ff                                 |..A~..|", lines.single())
    }

    @Test
    fun `a partial second line keeps the hex column padded`() {
        val lines = Hexdump.format(ByteArray(17) { it.toByte() })

        assertEquals(2, lines.size)
        // Both lines must put the gutter in the same place, or the dump is unreadable.
        assertEquals(lines[0].indexOf('|'), lines[1].indexOf('|'))
    }

    @Test
    fun `indent is applied to every line`() {
        val lines = Hexdump.format(ByteArray(20), indent = "    ")

        assertTrue(lines.all { it.startsWith("    ") }, "got $lines")
    }

    @Test
    fun `over the limit the remainder is counted instead of dumped`() {
        val lines = Hexdump.format(ByteArray(100), limit = 32)

        assertEquals(3, lines.size)
        assertEquals("... 68 more bytes", lines.last())
    }

    @Test
    fun `a limit at or above the size adds no elision line`() {
        assertTrue(Hexdump.format(ByteArray(32), limit = 32).none { it.contains("more bytes") })
    }

    @Test
    fun `round trips arbitrary payloads`() {
        val random = Random(seed = 20260914)
        repeat(200) {
            val original = random.nextBytes(random.nextInt(0, 300))
            if (original.isEmpty()) return@repeat

            val text = Hexdump.format(original).joinToString("\n")
            assertContentEquals(original, Hexdump.parse(text), "failed for ${original.size} bytes")
        }
    }

    @Test
    fun `round trips payloads past the four digit offset boundary`() {
        val original = Random(seed = 7).nextBytes(0x10000 + 48)

        val text = Hexdump.format(original).joinToString("\n")
        assertContentEquals(original, Hexdump.parse(text))
    }

    @Test
    fun `parse picks the dump out of a surrounding log`() {
        val log = """
            |   0.012  ble             tx gatt/fff5 4B
            |                          0000  de ad be ef                                       |....|
            |   0.180  ble             rx gatt/fff4 0B
        """.trimMargin()

        assertContentEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()), Hexdump.parse(log))
    }

    @Test
    fun `parse rejects a dump with a hole in it`() {
        // A truncated capture pasted back would otherwise yield a fixture that silently lies.
        val text = """
            0000  00 01 02 03 04 05 06 07  08 09 0a 0b 0c 0d 0e 0f  |................|
            0020  20 21 22 23                                       | !"#|
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> { Hexdump.parse(text) }
    }

    @Test
    fun `parse of text with no dump yields nothing`() {
        assertContentEquals(ByteArray(0), Hexdump.parse("no payload here\njust prose"))
    }
}
