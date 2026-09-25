package app.aaps.pump.rileylink2.minimed

import app.aaps.pump.rileylink2.fake.FakeOrangeLink
import app.aaps.pump.rileylink2.fake.RadioReply
import app.aaps.pump.rileylink2.rfspy.FrequencyRegisters
import app.aaps.pump.rileylink2.rfspy.RadioRegister
import app.aaps.pump.rileylink2.rfspy.RfspySession
import app.aaps.pump.rileylink2.rfspy.SessionConfig
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class MinimedRadioTest {

    private val probe = byteArrayOf(0xA7.toByte(), 0x24, 0x63, 0x05, 0x8D.toByte(), 0x00)
    private val pumpModelReply =
        byteArrayOf(0xA7.toByte(), 0x24, 0x63, 0x05, 0x8D.toByte(), 0x09, 0x03, 0x37, 0x32, 0x32)

    private fun encodedReply(message: ByteArray): ByteArray =
        Minimed4b6b.encode4b6b(message + MedtronicCrc.crc8(message))

    private suspend fun TestScope.radio(fake: FakeOrangeLink, frequency: Double = 916.55): MinimedRadio =
        MinimedRadio(
            RfspySession.open(
                conn = fake.connect(fake.address, 5.seconds),
                config = SessionConfig(frequencyMHz = frequency),
                epoch = 1,
                scope = backgroundScope,
            )
        )

    /** True when the fake's frequency registers currently select [freqMHz]. */
    private fun FakeOrangeLink.isTunedTo(freqMHz: Double): Boolean {
        val (f2, f1, f0) = FrequencyRegisters.forMhz(freqMHz)
        return registers[RadioRegister.FREQ2] == f2 &&
            registers[RadioRegister.FREQ1] == f1 &&
            registers[RadioRegister.FREQ0] == f0
    }

    // -- send/receive ------------------------------------------------------------------------

    @Test
    fun `valid pump reply decodes with rssi in dBm`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        fake.radioBehavior = { RadioReply.Packet(bytes = encodedReply(pumpModelReply), rssiRaw = 0x4A) }
        val radio = radio(fake)

        val outcome = radio.sendAndListen(probe)

        // 0x4A = 74 → 74/2 − 73 = −36 dBm
        assertThat(outcome).isEqualTo(MinimedRadio.RadioOutcome.Reply(pumpModelReply, rssiDbm = -36))
        // TX carried the encoded probe; firmware strips the 0x00 terminator (§2.3)
        assertThat(fake.txLog.single().packet)
            .isEqualTo(Minimed4b6b.encodeForTx(probe).let { it.copyOfRange(0, it.size - 1) })
    }

    @Test
    fun `undecodable reply is Garbled with rssi, pump counts as present`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        fake.radioBehavior = { RadioReply.Packet(bytes = byteArrayOf(0x00, 0x01, 0x02, 0x03), rssiRaw = 0x30) }
        val radio = radio(fake)

        val outcome = radio.sendAndListen(probe)

        assertThat(outcome).isEqualTo(MinimedRadio.RadioOutcome.Garbled(rssiDbm = 0x30 / 2 - 73))
    }

    @Test
    fun `silence is PumpSilent`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        val radio = radio(fake)

        assertThat(radio.sendAndListen(probe)).isEqualTo(MinimedRadio.RadioOutcome.PumpSilent)
    }

    // -- wake (design §6.5: bounded bursts) ---------------------------------------------------

    @Test
    fun `F6 wake succeeds on a garbled response`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        fake.radioBehavior = { RadioReply.Packet(bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)) }
        val radio = radio(fake)

        val outcome = radio.wake(probe)

        assertThat(outcome).isInstanceOf(MinimedRadio.RadioOutcome.Garbled::class.java)
    }

    @Test
    fun `wake against silent pump makes exactly the bounded number of attempts`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        val radio = radio(fake)

        val outcome = radio.wake(probe)

        assertThat(outcome).isEqualTo(MinimedRadio.RadioOutcome.PumpSilent)
        assertThat(fake.txLog).hasSize(MinimedRadio.WAKE_ATTEMPTS)
        assertThat(fake.txLog.first().repeatCount).isEqualTo(MinimedRadio.WAKE_REPEAT_COUNT)
        assertThat(fake.txLog.first().listenTimeoutMs).isEqualTo(MinimedRadio.WAKE_LISTEN_MS)
    }

    // -- tune ---------------------------------------------------------------------------------

    @Test
    fun `full scan finds the answering frequency and leaves the radio tuned to it`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        fake.radioBehavior = {
            if (fake.isTunedTo(916.60)) RadioReply.Packet(bytes = encodedReply(pumpModelReply), rssiRaw = 0x4A)
            else RadioReply.Silence
        }
        val radio = radio(fake)

        val outcome = radio.tune(probe, MinimedRadio.US_FREQUENCIES, currentFrequencyMHz = null)

        assertThat(outcome).isInstanceOf(MinimedRadio.TuneOutcome.Tuned::class.java)
        assertThat((outcome as MinimedRadio.TuneOutcome.Tuned).frequencyMHz).isEqualTo(916.60)
        assertThat(fake.isTunedTo(916.60)).isTrue()
    }

    @Test
    fun `quick path keeps the current frequency without a full scan`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        fake.radioBehavior = {
            if (fake.isTunedTo(916.55)) RadioReply.Packet(bytes = encodedReply(pumpModelReply))
            else RadioReply.Silence
        }
        val radio = radio(fake)

        val outcome = radio.tune(probe, MinimedRadio.US_FREQUENCIES, currentFrequencyMHz = 916.55)

        assertThat(outcome).isInstanceOf(MinimedRadio.TuneOutcome.Tuned::class.java)
        assertThat((outcome as MinimedRadio.TuneOutcome.Tuned).frequencyMHz).isEqualTo(916.55)
        // one wake burst (answered first attempt) + quick-path trials — no 8-frequency sweep
        assertThat(fake.txLog.size).isAtMost(MinimedRadio.TUNE_TRIALS + 1)
    }

    @Test
    fun `tune wakes a duty-cycling pump that ignores bare probes`() = runTest {
        // 2026-07-04 live-day finding: during PumpUnreachable nothing ever sent a wake
        // burst, so a pump whose duty-cycling receiver misses bare 0-repeat probes stayed
        // unreachable for 35-45 min episodes. The pump model: only a repeat burst lands in
        // a sniff window; once awake it answers everything (for the pump's ~1 min window).
        val fake = FakeOrangeLink(backgroundScope)
        var awake = false
        fake.radioBehavior = { tx ->
            if (tx.repeatCount > 0) awake = true
            if (awake && fake.isTunedTo(916.60)) RadioReply.Packet(bytes = encodedReply(pumpModelReply), rssiRaw = 0x4A)
            else RadioReply.Silence
        }
        val radio = radio(fake)

        val outcome = radio.tune(probe, MinimedRadio.US_FREQUENCIES, currentFrequencyMHz = 916.60)

        assertThat(outcome).isInstanceOf(MinimedRadio.TuneOutcome.Tuned::class.java)
        assertThat((outcome as MinimedRadio.TuneOutcome.Tuned).frequencyMHz).isEqualTo(916.60)
    }

    @Test
    fun `tune with pump out of range is PumpSilent after full sweep`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        val radio = radio(fake)

        val outcome = radio.tune(probe, MinimedRadio.US_FREQUENCIES)

        assertThat(outcome).isEqualTo(MinimedRadio.TuneOutcome.PumpSilent)
        // the pre-scan wake attempts, then the full 8-frequency x 3-trial sweep
        assertThat(fake.txLog).hasSize(MinimedRadio.WAKE_ATTEMPTS + MinimedRadio.US_FREQUENCIES.size * MinimedRadio.TUNE_TRIALS)
    }

    @Test
    fun `F4-lesson tune aborts fast when the link stops carrying commands`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        val radio = radio(fake)
        fake.dropAllCommands = true          // BLE up, but nothing reaches the firmware

        val outcome = radio.tune(probe, MinimedRadio.US_FREQUENCIES)

        assertThat(outcome).isInstanceOf(MinimedRadio.TuneOutcome.LinkTrouble::class.java)
        // fail-fast: aborted on the first setFrequency, not after 24 doomed trials
        assertThat(fake.txLog).isEmpty()
    }

    @Test
    fun `F3 reboot mid-tune is LinkTrouble, not a zombie scan`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        fake.radioBehavior = { RadioReply.Hang }     // firmware wedges on the first probe
        val radio = radio(fake)

        val outcome = async { radio.tune(probe, MinimedRadio.US_FREQUENCIES) }
        testScheduler.advanceTimeBy(2_000)
        fake.reboot()

        assertThat(outcome.await()).isInstanceOf(MinimedRadio.TuneOutcome.LinkTrouble::class.java)
    }
}
