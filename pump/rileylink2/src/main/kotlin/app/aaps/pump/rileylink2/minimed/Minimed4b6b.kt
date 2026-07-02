package app.aaps.pump.rileylink2.minimed

/**
 * Host-side 4b6b codec ("Local" encoding), algorithm ported from the legacy
 * `Encoding4b6bGeoff`. Wire compatibility is pinned by a golden frame captured from the
 * live legacy stack.
 *
 * Decode failure is an expected RF condition (corruption, saturation, firmware 4b6b quirks
 * — design doc §2.3/§6.6), so it is a value, not an exception.
 */
object Minimed4b6b {

    /** 4-bit nibble → 6-bit symbol. */
    private val SYMBOLS = byteArrayOf(0x15, 0x31, 0x32, 0x23, 0x34, 0x25, 0x26, 0x16, 0x1a, 0x19, 0x2a, 0x0b, 0x2c, 0x0d, 0x0e, 0x1c)

    private val SYMBOL_TO_NIBBLE = IntArray(64) { -1 }.also { table ->
        SYMBOLS.forEachIndexed { nibble, symbol -> table[symbol.toInt()] = nibble }
    }

    sealed interface DecodeResult {
        data class Decoded(val bytes: ByteArray) : DecodeResult {
            override fun equals(other: Any?): Boolean = other is Decoded && other.bytes.contentEquals(bytes)
            override fun hashCode(): Int = bytes.contentHashCode()
        }

        data class CodingError(val errorCount: Int) : DecodeResult
    }

    fun encode4b6b(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>(data.size * 3 / 2 + 2)
        var acc = 0
        var bitcount = 0
        for (b in data) {
            acc = (acc shl 6) or SYMBOLS[(b.toInt() shr 4) and 0x0F].toInt()
            bitcount += 6
            acc = (acc shl 6) or SYMBOLS[b.toInt() and 0x0F].toInt()
            bitcount += 6
            while (bitcount >= 8) {
                out.add(((acc shr (bitcount - 8)) and 0xFF).toByte())
                bitcount -= 8
                acc = acc and (0xFFFF shr (16 - bitcount))
            }
        }
        if (bitcount > 0) {
            acc = (acc shl 6) or 0x14      // uneven packet boundary marker
            bitcount += 6
            if (bitcount >= 8) {
                out.add(((acc shr (bitcount - 8)) and 0xFF).toByte())
                bitcount -= 8
            }
            while (bitcount >= 8) {
                out.add(0)
                bitcount -= 8
            }
        }
        return out.toByteArray()
    }

    fun decode4b6b(raw: ByteArray): DecodeResult {
        val out = ArrayList<Byte>(raw.size * 2 / 3 + 1)
        var x = 0
        var availableBits = 0
        var codingErrors = 0
        for (b in raw) {
            x = (x shl 8) + (b.toInt() and 0xFF)
            availableBits += 8
            if (availableBits >= 12) {
                val highNibble = SYMBOL_TO_NIBBLE[(x shr (availableBits - 6)) and 0x3F]
                val lowNibble = SYMBOL_TO_NIBBLE[(x shr (availableBits - 12)) and 0x3F]
                if (highNibble >= 0 && lowNibble >= 0) {
                    out.add(((highNibble shl 4) + lowNibble).toByte())
                } else {
                    codingErrors++
                }
                availableBits -= 12
                x = x and (0xFFFF shr (16 - availableBits))
            }
        }
        if (availableBits != 0 && !(availableBits == 4 && x == 0x05)) {
            codingErrors++          // trailing bits that are not the end marker
        }
        return if (codingErrors > 0) DecodeResult.CodingError(codingErrors)
        else DecodeResult.Decoded(out.toByteArray())
    }

    /**
     * Full TX radio packet: message + CRC-8, 4b6b-encoded, plus the 0x00 terminator that the
     * legacy stack always appends (the firmware strips it before keying — by design, §2.3).
     */
    fun encodeForTx(message: ByteArray): ByteArray =
        encode4b6b(message + MedtronicCrc.crc8(message)) + 0x00

    sealed interface RxResult {
        data class Message(val bytes: ByteArray) : RxResult {
            override fun equals(other: Any?): Boolean = other is Message && other.bytes.contentEquals(bytes)
            override fun hashCode(): Int = bytes.contentHashCode()
        }

        /** Symbols didn't decode, message too short, or CRC mismatch: pump present, payload unusable. */
        data object Garbled : RxResult
    }

    /** Decode a received (encoded) radio payload and verify its trailing CRC-8. */
    fun decodeFromRx(encoded: ByteArray): RxResult {
        val decoded = (decode4b6b(encoded) as? DecodeResult.Decoded)?.bytes ?: return RxResult.Garbled
        if (decoded.size <= 2) return RxResult.Garbled
        val message = decoded.copyOfRange(0, decoded.size - 1)
        val receivedCrc = decoded[decoded.size - 1]
        return if (receivedCrc == MedtronicCrc.crc8(message)) RxResult.Message(message) else RxResult.Garbled
    }
}
