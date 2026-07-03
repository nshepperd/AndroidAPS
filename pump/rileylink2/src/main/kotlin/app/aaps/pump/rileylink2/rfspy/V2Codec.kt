package app.aaps.pump.rileylink2.rfspy

/**
 * OrangeLink protocol v2 envelope (firmware ground truth: Orangelink-Firmware
 * docs/PROTOCOL-V2.md, first shipped in `subg_rfspy 2.2-ol1`).
 *
 * Command (written to the stock data characteristic, stock outer framing):
 * `[len][0x10][seq][inner_opcode][inner_params…]`
 *
 * Notifications (RESPONSE_V2 characteristic): `[seq][code][payload…]` where the meaning of
 * `code` depends on protocol position — the first notification for a seq is the immediate
 * ack (0xE0 ACCEPTED / 0xE1 BUSY / 0xE2 MTU / 0x11 malformed envelope), a second one is the
 * completion carrying the stock status byte set. `0x11` appears in both tables and is only
 * disambiguated by position; that is why this codec returns raw (seq, code, payload) and
 * the session interprets by phase.
 */
object V2Codec {

    const val OPCODE_CMD_V2 = 0x10

    /** ACCEPTED — queued for execution, a completion will follow. */
    const val ACK_ACCEPTED = 0xE0

    /** BUSY — queue full (8 deep); discarded, no completion. Definitively not executed. */
    const val ACK_BUSY = 0xE1

    /** MTU < 131; discarded. Definitively not executed. */
    const val ACK_MTU_TOO_SMALL = 0xE2

    /** Envelope malformed (no inner opcode / params > 123 B); discarded. Not executed. */
    const val ACK_MALFORMED = 0x11

    /** ATT MTU the firmware requires before it accepts v2 commands. */
    const val MIN_MTU = 131

    /**
     * Build the (unframed) v2 envelope body for [innerBody] = `[opcode][params…]`.
     *
     * RF byte identity (design I8, corrected): the stock path strips one trailing 0x00 from
     * outgoing Minimed packets before keying (app_aps.c:381) and the legacy encoder relies
     * on it; the v2 path treats the envelope length as authoritative and does NOT strip.
     * To keep the on-air bytes identical, this codec strips that terminator itself for the
     * two packet-carrying opcodes. Locked by the cross-personality RF-identity test.
     */
    fun envelope(seq: Int, innerBody: ByteArray): ByteArray {
        require(innerBody.isNotEmpty()) { "empty inner body" }
        val opcode = innerBody[0].toInt() and 0xFF
        // Strip only when the trailing byte lies INSIDE the packet field: with an empty
        // packet, innerBody.last() is a header byte (e.g. preambleExtensionMs LSB, usually
        // 0x00) that the firmware's stock-path strip would never touch.
        val headerLen = PACKET_FIELD_OFFSET[opcode]
        val body = if (headerLen != null && innerBody.size > 1 + headerLen && innerBody.last() == ZERO_TERMINATOR)
            innerBody.copyOfRange(0, innerBody.size - 1)
        else innerBody
        return byteArrayOf(OPCODE_CMD_V2.toByte(), (seq and 0xFF).toByte()) + body
    }

    /** One RESPONSE_V2 notification, uninterpreted (see class doc). Null = too short. */
    data class Notification(val seq: Int, val code: Int, val payload: ByteArray) {

        override fun equals(other: Any?): Boolean =
            other is Notification && other.seq == seq && other.code == code && other.payload.contentEquals(payload)

        override fun hashCode(): Int = (31 * seq + code) * 31 + payload.contentHashCode()
    }

    fun parse(bytes: ByteArray): Notification? =
        if (bytes.size < 2) null
        else Notification(bytes[0].toInt() and 0xFF, bytes[1].toInt() and 0xFF, bytes.copyOfRange(2, bytes.size))

    /** Params bytes before the packet field: SEND_PKT (4) = 6, SEND_AND_LISTEN (5) = 12
     *  (see [RfspyCommand.SendPacket]/[RfspyCommand.SendAndListen] wire layouts). */
    private val PACKET_FIELD_OFFSET = mapOf(4 to 6, 5 to 12)
    private const val ZERO_TERMINATOR = 0.toByte()
}
