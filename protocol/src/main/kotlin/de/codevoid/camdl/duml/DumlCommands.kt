package de.codevoid.camdl.duml

/**
 * The DUML commands camDL sends, and the constants they are built from.
 *
 * Values come from the reverse engineering published in KonradIT/osmosis (MIT), which is
 * verified against the Osmo Action 5 Pro. See NOTICE.
 */
object DumlCommands {

    // Targets pack sender in the low byte and receiver in the high byte.
    const val TARGET_APP_TO_CAMERA = 0x0102
    const val TARGET_APP_TO_WIFI = 0x0702
    const val TARGET_APP_TO_SESSION = 0xF002
    const val TARGET_APP_TO_1C = 0x1C02

    const val FLAG_REQUEST = 0x40
    const val FLAG_RESPONSE = 0xC0
    const val FLAG_NOTIFY = 0x00

    const val SET_COMMON = 0x00
    const val SET_WIFI = 0x07

    const val ID_SET_PAIRING_PIN = 0x45
    const val ID_CONNECT_TO_WIFI = 0x47

    /**
     * Message ids are echoed back in the response, so they are how a reply is matched to its
     * request. These two are the values the vendor app uses; the camera does not appear to
     * care, but staying identical removes one variable during bring-up.
     */
    const val MESSAGE_ID_PAIR = 0x8092
    const val MESSAGE_ID_WIFI = 0x8C19

    /**
     * The PIN token a camera expects. Drones want "DJI FLY" instead - camDL has no reason to
     * talk to one, but the difference is the sort of thing that is invisible in a log.
     */
    const val CAMERA_PIN = "osmo"

    /**
     * Identity the camera remembers once pairing is approved.
     *
     * This is the value osmosis uses. Sending our own would be tidier, but an unknown
     * identifier is one more thing that could explain a failed pairing, and each guess here
     * costs a CI build and a sideload. It is a parameter, so switching to a camDL-specific
     * identity is a one-line change once pairing is known to work.
     */
    const val DEFAULT_IDENTIFIER = "284ae5b8d76b3375a04a6417ad71bea3"

    /** Packs flags, command set and command id into the frame's 24-bit type field. */
    fun type(flags: Int, commandSet: Int, commandId: Int): Int =
        (flags and 0xFF) or ((commandSet and 0xFF) shl 8) or ((commandId and 0xFF) shl 16)

    /** `[len:u8][utf8]`. Strings longer than 255 bytes cannot be expressed. */
    fun packString(value: String): ByteArray {
        val utf8 = value.toByteArray(Charsets.UTF_8)
        require(utf8.size <= 0xFF) { "string of ${utf8.size} bytes does not fit a u8 length prefix" }
        return ByteArray(utf8.size + 1).also {
            it[0] = utf8.size.toByte()
            utf8.copyInto(it, 1)
        }
    }

    /** 0x07/0x45. First step of the handshake; the camera shows an approval prompt. */
    fun setPairingPin(
        pin: String = CAMERA_PIN,
        identifier: String = DEFAULT_IDENTIFIER,
    ): DumlFrame = DumlFrame(
        target = TARGET_APP_TO_WIFI,
        messageId = MESSAGE_ID_PAIR,
        type = type(FLAG_REQUEST, SET_WIFI, ID_SET_PAIRING_PIN),
        payload = packString(identifier) + packString(pin),
    )

    /**
     * 0x07/0x47. The AP appears roughly fifteen seconds afterwards.
     *
     * Which direction the credentials travel is the open question M1 answers: the payload is
     * an SSID and a password, and the published notes say they are "provisioned over BLE",
     * which reads as the phone dictating what AP the camera should raise. If the probe log
     * shows the camera raising an AP under a name of its own instead, these are values to
     * read back rather than to send.
     */
    fun connectToWifi(ssid: String, password: String): DumlFrame = DumlFrame(
        target = TARGET_APP_TO_WIFI,
        messageId = MESSAGE_ID_WIFI,
        type = type(FLAG_REQUEST, SET_WIFI, ID_CONNECT_TO_WIFI),
        payload = packString(ssid) + packString(password),
    )
}
