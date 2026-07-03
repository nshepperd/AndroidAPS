package app.aaps.pump.rileylink2.rfspy

import java.nio.ByteBuffer

/**
 * subg_rfspy 2.x command set (firmware ground truth: design doc §2.2).
 *
 * [body] is the unframed wire form `[opcode][params…]`; the session prepends the length
 * byte (`total − 1`). Encodings must stay byte-identical to the legacy stack — they are
 * covered by golden-frame tests.
 *
 * [mutating] marks commands whose RF transmission may change pump state; for these a lost
 * response yields [RfspyResult.Uncertain], never a silent retry (design invariant I9).
 */
sealed class RfspyCommand(val opcode: Byte, val mutating: Boolean) {

    abstract val params: ByteArray

    val body: ByteArray get() = byteArrayOf(opcode) + params

    data object GetState : RfspyCommand(1, mutating = false) {
        override val params: ByteArray = ByteArray(0)
    }

    data object GetVersion : RfspyCommand(2, mutating = false) {
        override val params: ByteArray = ByteArray(0)
    }

    data class GetPacket(val listenChannel: Byte, val timeoutMs: Int) : RfspyCommand(3, mutating = false) {
        override val params: ByteArray
            get() = ByteBuffer.allocate(5).put(listenChannel).putInt(timeoutMs).array()
    }

    data class SendPacket(
        val sendChannel: Byte,
        val repeatCount: Byte,
        val delayBetweenPacketsMs: Int,
        val preambleExtensionMs: Int,
        val packet: ByteArray,
    ) : RfspyCommand(4, mutating = true) {

        override val params: ByteArray
            get() = ByteBuffer.allocate(6 + packet.size)
                .put(sendChannel).put(repeatCount)
                .putShort(delayBetweenPacketsMs.toShort())
                .putShort(preambleExtensionMs.toShort())
                .put(packet).array()

        override fun equals(other: Any?): Boolean = other is SendPacket && other.body.contentEquals(body)
        override fun hashCode(): Int = body.contentHashCode()
    }

    data class SendAndListen(
        val sendChannel: Byte,
        val repeatCount: Byte,
        val delayBetweenPacketsMs: Int,
        val listenChannel: Byte,
        val timeoutMs: Int,
        val retryCount: Byte,
        val preambleExtensionMs: Int,
        val packet: ByteArray,
    ) : RfspyCommand(5, mutating = true) {

        override val params: ByteArray
            get() = ByteBuffer.allocate(12 + packet.size)
                .put(sendChannel).put(repeatCount)
                .putShort(delayBetweenPacketsMs.toShort())
                .put(listenChannel)
                .putInt(timeoutMs)
                .put(retryCount)
                .putShort(preambleExtensionMs.toShort())
                .put(packet).array()

        /** Firmware-side worst-case duration; the session adds BLE latency margin. */
        val expectedDurationMs: Long
            get() = repeatCount.toUByte().toLong() * delayBetweenPacketsMs + timeoutMs.toLong() * (retryCount + 1)

        override fun equals(other: Any?): Boolean = other is SendAndListen && other.body.contentEquals(body)
        override fun hashCode(): Int = body.contentHashCode()
    }

    data class UpdateRegister(val register: Int, val value: Int) : RfspyCommand(6, mutating = false) {
        override val params: ByteArray get() = byteArrayOf(register.toByte(), value.toByte())
    }

    data object Reset : RfspyCommand(7, mutating = false) {
        override val params: ByteArray = ByteArray(0)
    }

    data class SetSwEncoding(val encoding: SwEncoding) : RfspyCommand(11, mutating = false) {
        override val params: ByteArray get() = byteArrayOf(encoding.wire)
    }

    data class SetPreamble(val preamble: Int) : RfspyCommand(12, mutating = false) {
        override val params: ByteArray get() = ByteBuffer.allocate(2).putShort(preamble.toShort()).array()
    }

    data object ResetRadioConfig : RfspyCommand(13, mutating = false) {
        override val params: ByteArray = ByteArray(0)
    }

    data object GetStatistics : RfspyCommand(14, mutating = false) {
        override val params: ByteArray = ByteArray(0)
    }
}

/** Firmware-side software encoding. Medtronic uses NONE (host-side 4b6b, "Local"). */
enum class SwEncoding(val wire: Byte) {
    NONE(0),
    MANCHESTER(1),
    FOUR_B_SIX_B(2),
}

/** CC111x-compatible register addresses understood by the firmware. */
object RadioRegister {
    const val FREQ2 = 0x09
    const val FREQ1 = 0x0A
    const val FREQ0 = 0x0B
}

/**
 * Frequency → FREQ2/FREQ1/FREQ0 register values. Same math as the legacy stack
 * (24 MHz crystal, 16-bit fractional): value = freqMHz * 1e6 / (24e6 / 2^16).
 */
object FrequencyRegisters {

    private const val FXTAL_HZ = 24_000_000.0

    fun forMhz(freqMHz: Double): Triple<Int, Int, Int> {
        val value = (freqMHz * 1_000_000 / (FXTAL_HZ / (1 shl 16))).toInt()
        return Triple((value shr 16) and 0xFF, (value shr 8) and 0xFF, value and 0xFF)
    }
}
