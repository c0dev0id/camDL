package de.codevoid.camdl.duml

/**
 * The two checksums DUML frames carry.
 *
 * Both are ordinary reflected CRCs with DJI's own seeds, computed bitwise rather than from a
 * 256-entry table: a table would be 512 magic numbers nobody could check by eye, and these
 * run over frames of a few dozen bytes.
 */
object DjiCrc {

    /** CRC-8, spec init 0xEE / poly 0x31 reflected, i.e. init 0x77 / poly 0x8C. */
    const val CRC8_INIT = 0x77
    private const val CRC8_POLY = 0x8C

    /** CRC-16, spec init 0x496C / poly 0x1021 reflected, i.e. init 0x3692 / poly 0x8408. */
    const val CRC16_INIT = 0x3692
    private const val CRC16_POLY = 0x8408

    fun crc8(data: ByteArray, from: Int = 0, until: Int = data.size): Int {
        var crc = CRC8_INIT
        for (i in from until until) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x01 != 0) (crc ushr 1) xor CRC8_POLY else crc ushr 1
            }
        }
        return crc and 0xFF
    }

    fun crc16(data: ByteArray, from: Int = 0, until: Int = data.size): Int {
        var crc = CRC16_INIT
        for (i in from until until) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x01 != 0) (crc ushr 1) xor CRC16_POLY else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }
}
