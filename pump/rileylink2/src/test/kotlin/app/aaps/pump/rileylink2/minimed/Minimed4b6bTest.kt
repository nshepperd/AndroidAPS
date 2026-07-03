package app.aaps.pump.rileylink2.minimed

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class Minimed4b6bTest {

    /**
     * Golden vector captured from the live legacy stack (AndroidAPS log 2026-07-02):
     * pump message A7 24 63 05 8D 00 (GetModel probe) was transmitted as
     * A9 6C B4 9A 35 65 68 D5 55 C6 C5 00. This pins CRC-8 + 4b6b + terminator in one shot.
     */
    @Test
    fun `encodeForTx matches frame captured from legacy stack`() {
        val message = byteArrayOf(0xA7.toByte(), 0x24, 0x63, 0x05, 0x8D.toByte(), 0x00)

        val encoded = Minimed4b6b.encodeForTx(message)

        assertThat(encoded).isEqualTo(
            byteArrayOf(
                0xA9.toByte(), 0x6C, 0xB4.toByte(), 0x9A.toByte(), 0x35, 0x65,
                0x68, 0xD5.toByte(), 0x55, 0xC6.toByte(), 0xC5.toByte(), 0x00,
            )
        )
    }

    @Test
    fun `encode-decode round trip preserves message across lengths`() {
        for (len in 1..20) {
            val message = ByteArray(len) { ((it * 37 + len * 11) and 0xFF).toByte() }
            val encoded = Minimed4b6b.encode4b6b(message)
            val decoded = Minimed4b6b.decode4b6b(encoded)

            assertThat(decoded).isInstanceOf(Minimed4b6b.DecodeResult.Decoded::class.java)
            assertThat((decoded as Minimed4b6b.DecodeResult.Decoded).bytes).isEqualTo(message)
        }
    }

    @Test
    fun `decodeFromRx validates CRC and returns original message`() {
        val message = byteArrayOf(0xA7.toByte(), 0x24, 0x63, 0x05, 0x8D.toByte(), 0x09, 0x03, 0x37, 0x32, 0x32)
        val overTheAir = Minimed4b6b.encode4b6b(message + MedtronicCrc.crc8(message))

        val rx = Minimed4b6b.decodeFromRx(overTheAir)

        assertThat(rx).isEqualTo(Minimed4b6b.RxResult.Message(message))
    }

    @Test
    fun `invalid symbols classify as Garbled, not exception`() {
        // 0x00 is not a valid 6-bit symbol pair in any alignment
        val rx = Minimed4b6b.decodeFromRx(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00))

        assertThat(rx).isEqualTo(Minimed4b6b.RxResult.Garbled)
    }

    @Test
    fun `corrupted CRC classifies as Garbled`() {
        val message = byteArrayOf(0xA7.toByte(), 0x24, 0x63, 0x05, 0x06, 0x00)
        val badCrc = (MedtronicCrc.crc8(message) + 1).toByte()
        val overTheAir = Minimed4b6b.encode4b6b(message + badCrc)

        assertThat(Minimed4b6b.decodeFromRx(overTheAir)).isEqualTo(Minimed4b6b.RxResult.Garbled)
    }
}
