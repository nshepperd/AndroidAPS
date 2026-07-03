package app.aaps.pump.rileylink2.link

import app.aaps.pump.rileylink2.fake.FakeOrangeLink
import app.aaps.pump.rileylink2.fake.RadioReply
import app.aaps.pump.rileylink2.minimed.MedtronicCrc
import app.aaps.pump.rileylink2.minimed.Minimed4b6b
import app.aaps.pump.rileylink2.minimed.MinimedRadio
import app.aaps.pump.rileylink2.rfspy.FrequencyRegisters
import app.aaps.pump.rileylink2.rfspy.RadioRegister
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * L3 scenario tests: the fault classes and incident replays from design doc §3/§12.3,
 * driven end-to-end through supervisor + session + radio against FakeOrangeLink.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PumpLinkSupervisorTest {

    private val probe = byteArrayOf(0xA7.toByte(), 0x24, 0x63, 0x05, 0x8D.toByte(), 0x00)
    private val pumpReply = byteArrayOf(0xA7.toByte(), 0x24, 0x63, 0x05, 0x8D.toByte(), 0x09, 0x03, 0x37, 0x32, 0x32)
    private val encodedReply = Minimed4b6b.encode4b6b(pumpReply + MedtronicCrc.crc8(pumpReply))

    /** Make the fake's pump answer only when the radio is tuned to [freqMHz]. */
    private fun FakeOrangeLink.pumpOn(freqMHz: Double) {
        val (f2, f1, f0) = FrequencyRegisters.forMhz(freqMHz)
        radioBehavior = {
            if (registers[RadioRegister.FREQ2] == f2 && registers[RadioRegister.FREQ1] == f1 && registers[RadioRegister.FREQ0] == f0)
                RadioReply.Packet(bytes = encodedReply, rssiRaw = 0x4A)
            else RadioReply.Silence
        }
    }

    private fun TestScope.startSupervisor(
        fake: FakeOrangeLink,
        prefs: PumpLinkPrefs = InMemoryPumpLinkPrefs(),
    ): PumpLinkSupervisor {
        val supervisor = PumpLinkSupervisor(
            bleLink = fake,
            config = PumpLinkConfig(
                deviceAddress = fake.address,
                probeMessage = probe,
                scanFrequenciesMHz = MinimedRadio.US_FREQUENCIES,
                prefs = prefs,
            ),
            scope = backgroundScope,
            timeSource = testScheduler.timeSource,
        )
        supervisor.start()
        return supervisor
    }

    // -- happy path ----------------------------------------------------------------------------

    @Test
    fun `cold start with unknown frequency tunes and reaches Ready`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        fake.pumpOn(916.60)
        val prefs = InMemoryPumpLinkPrefs()

        val supervisor = startSupervisor(fake, prefs)

        val ready = supervisor.state.first { it is PumpLinkState.Ready } as PumpLinkState.Ready
        assertThat(ready.frequencyMHz).isEqualTo(916.60)
        assertThat(prefs.lastGoodFrequencyMHz).isEqualTo(916.60)
    }

    @Test
    fun `withPump executes a command when Ready`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        fake.pumpOn(916.55)
        val supervisor = startSupervisor(fake)
        supervisor.state.first { it is PumpLinkState.Ready }

        val outcome = supervisor.withPump("read model") { radio -> radio.sendAndListen(probe) }

        assertThat(outcome).isInstanceOf(PumpCmdOutcome.Success::class.java)
        val radioOutcome = (outcome as PumpCmdOutcome.Success).value
        assertThat(radioOutcome).isInstanceOf(MinimedRadio.RadioOutcome.Reply::class.java)
        assertThat((radioOutcome as MinimedRadio.RadioOutcome.Reply).message).isEqualTo(pumpReply)
    }

    @Test
    fun `battery poller reads at bring-up and reports via onBatteryLevel`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        fake.pumpOn(916.55)
        val levels = mutableListOf<Int>()
        val supervisor = PumpLinkSupervisor(
            bleLink = fake,
            config = PumpLinkConfig(
                deviceAddress = fake.address,
                probeMessage = probe,
                scanFrequenciesMHz = MinimedRadio.US_FREQUENCIES,
                prefs = InMemoryPumpLinkPrefs(),
                batteryPollInterval = 30.minutes,
            ),
            scope = backgroundScope,
            timeSource = testScheduler.timeSource,
        )
        supervisor.onBatteryLevel = { levels += it }
        supervisor.start()
        supervisor.state.first { it is PumpLinkState.Ready }

        advanceTimeBy(1.seconds)                              // first read is immediate
        assertThat(levels).containsExactly(80)                // FakeOrangeLink reports 80%

        advanceTimeBy(31.minutes)                             // next poll after the interval
        assertThat(levels).containsExactly(80, 80)
    }

    @Test
    fun `withPump readyTimeout does not abandon a block that is already running (soak bug B2)`() = runTest {
        // 2026-07-03: a history read ground through RF retries past the 90 s readyTimeout and
        // was abandoned MID-EXECUTION, its result discarded. readyTimeout must bound only the
        // wait-for-Ready; a started block runs to its real outcome.
        val fake = FakeOrangeLink(backgroundScope)
        fake.pumpOn(916.55)
        val supervisor = startSupervisor(fake)
        supervisor.state.first { it is PumpLinkState.Ready }

        val outcome = supervisor.withPump("slow history read", readyTimeout = 5.seconds) { radio ->
            radio.sendAndListen(probe)                       // started well within the timeout…
            kotlinx.coroutines.delay(2.minutes)              // …but execution far outlives it
            "completed"
        }

        assertThat(outcome).isInstanceOf(PumpCmdOutcome.Success::class.java)
        assertThat((outcome as PumpCmdOutcome.Success).value).isEqualTo("completed")
    }

    // -- F1: pump out of range, then back (the original 2026-07-02 incident) --------------------

    @Test
    fun `F1 replay - pump absent at start, appears later, link recovers without restart`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)                    // silent: pump out of range
        val supervisor = startSupervisor(fake)
        supervisor.state.first { it is PumpLinkState.PumpUnreachable }

        // Pump comes back in range; the next command (KeepAlive-equivalent) drives recovery.
        fake.pumpOn(916.55)
        val outcome = supervisor.withPump("keepalive", readyTimeout = 10.minutes) { radio ->
            radio.sendAndListen(probe)
        }

        assertThat(outcome).isInstanceOf(PumpCmdOutcome.Success::class.java)
        assertThat(supervisor.state.value).isInstanceOf(PumpLinkState.Ready::class.java)
    }

    @Test
    fun `withPump while pump stays unreachable returns Unavailable, state not terminal`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        val supervisor = startSupervisor(fake)
        supervisor.state.first { it is PumpLinkState.PumpUnreachable }

        val outcome = supervisor.withPump("doomed", readyTimeout = 1.minutes) { }

        assertThat(outcome).isInstanceOf(PumpCmdOutcome.Unavailable::class.java)
        // still recoverable — not a dead end
        assertThat(supervisor.state.value.isConnecting() || supervisor.state.value is PumpLinkState.PumpUnreachable).isTrue()
    }

    // -- F3: firmware reboot (incident b) --------------------------------------------------------

    @Test
    fun `F3 replay - firmware reboot from Ready recovers with full re-init, no zombie phase`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        fake.pumpOn(916.55)
        val prefs = InMemoryPumpLinkPrefs(lastGoodFrequencyMHz = 916.55)
        val supervisor = startSupervisor(fake, prefs)
        supervisor.state.first { it is PumpLinkState.Ready }
        val bootsBefore = fake.bootCount
        val timeBefore = testScheduler.currentTime

        fake.reboot()                                                  // volatile radio state lost

        val recovered = supervisor.state.first { s ->
            s is PumpLinkState.Ready && supervisor.journalSnapshot().any { it.to is PumpLinkState.LinkDown }
        }
        assertThat(recovered).isEqualTo(PumpLinkState.Ready(916.55))
        assertThat(fake.bootCount).isEqualTo(bootsBefore + 1)
        // rule R1: frequency registers were re-programmed during bring-up after the reboot
        val (f2, f1, f0) = FrequencyRegisters.forMhz(916.55)
        assertThat(fake.registers[RadioRegister.FREQ2]).isEqualTo(f2)
        assertThat(fake.registers[RadioRegister.FREQ1]).isEqualTo(f1)
        assertThat(fake.registers[RadioRegister.FREQ0]).isEqualTo(f0)
        // §12.3(b): recovery bounded in seconds, not the legacy 13 minutes
        assertThat(testScheduler.currentTime - timeBefore).isLessThan(30_000)
    }

    @Test
    fun `F2 - link drop without reboot reconnects and recovers`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        fake.pumpOn(916.55)
        val prefs = InMemoryPumpLinkPrefs(lastGoodFrequencyMHz = 916.55)
        val supervisor = startSupervisor(fake, prefs)
        supervisor.state.first { it is PumpLinkState.Ready }

        fake.dropLink()

        val recovered = supervisor.state.first { s ->
            s is PumpLinkState.Ready && supervisor.journalSnapshot().any { it.to is PumpLinkState.LinkDown }
        }
        assertThat(recovered).isEqualTo(PumpLinkState.Ready(916.55))
    }

    // -- F7: connect failures back off and keep trying -------------------------------------------

    @Test
    fun `F7 - refused connections retry with backoff and succeed when the device returns`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        fake.pumpOn(916.55)
        fake.refuseConnections = true
        val supervisor = startSupervisor(fake)

        supervisor.state.first { it is PumpLinkState.LinkDown }
        advanceTimeBy(2.minutes)
        val attemptsWhileRefused = fake.connectAttempts
        assertThat(attemptsWhileRefused).isAtLeast(3)                 // retrying
        assertThat(attemptsWhileRefused).isAtMost(30)                 // but backing off, not spinning

        fake.refuseConnections = false
        val state = supervisor.state.first { it is PumpLinkState.Ready }
        assertThat(state).isInstanceOf(PumpLinkState.Ready::class.java)
    }

    // -- rate limiting (§8.3) ---------------------------------------------------------------------

    @Test
    fun `tune retries under standing demand respect the cooldown`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)                    // pump silent throughout
        val supervisor = startSupervisor(fake)
        supervisor.state.first { it is PumpLinkState.PumpUnreachable }

        val pending = async {
            supervisor.withPump("standing demand", readyTimeout = 20.minutes) { }
        }
        advanceTimeBy(10.minutes)
        pending.cancel()

        // full scan = 8 frequencies x 3 trials; with a 3-min cooldown ~4-5 scans fit in ~10 min
        val scans = fake.txLog.size / (MinimedRadio.US_FREQUENCIES.size * MinimedRadio.TUNE_TRIALS)
        assertThat(scans).isAtLeast(2)
        assertThat(scans).isAtMost(5)
    }

    @Test
    fun `requestTune bypasses the cooldown`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        val supervisor = startSupervisor(fake)
        supervisor.state.first { it is PumpLinkState.PumpUnreachable }
        val scansBefore = fake.txLog.size

        fake.pumpOn(916.60)
        supervisor.requestTune()

        supervisor.state.first { it is PumpLinkState.Ready }
        assertThat(fake.txLog.size).isGreaterThan(scansBefore)
    }

    // -- terminal states ---------------------------------------------------------------------------

    @Test
    fun `incompatible firmware is terminal and quiet`() = runTest {
        val fake = FakeOrangeLink(backgroundScope, versionString = "subg_rfspy 1.0")
        val supervisor = startSupervisor(fake)

        supervisor.state.first { it is PumpLinkState.IncompatibleFirmware }
        val attemptsAtDetection = fake.connectAttempts
        advanceTimeBy(60.minutes)

        assertThat(supervisor.state.value).isInstanceOf(PumpLinkState.IncompatibleFirmware::class.java)
        assertThat(fake.connectAttempts).isEqualTo(attemptsAtDetection)   // no reconnect churn
    }

    @Test
    fun `blank address is NotConfigured`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        val supervisor = PumpLinkSupervisor(
            bleLink = fake,
            config = PumpLinkConfig("", probe, MinimedRadio.US_FREQUENCIES, InMemoryPumpLinkPrefs()),
            scope = backgroundScope,
            timeSource = testScheduler.timeSource,
        )
        supervisor.start()

        assertThat(supervisor.state.first { it is PumpLinkState.NotConfigured })
            .isInstanceOf(PumpLinkState.NotConfigured::class.java)
    }

    // -- escalation from the Medtronic driver ------------------------------------------------------

    @Test
    fun `reportPumpUnreachable from Ready forces a re-tune`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        fake.pumpOn(916.55)
        val prefs = InMemoryPumpLinkPrefs(lastGoodFrequencyMHz = 916.55)
        val supervisor = startSupervisor(fake, prefs)
        supervisor.state.first { it is PumpLinkState.Ready }

        fake.pumpOn(916.70)                                            // pump drifted frequency
        supervisor.requestTune()

        val ready = supervisor.state.first { s ->
            s is PumpLinkState.Ready && (s as PumpLinkState.Ready).frequencyMHz == 916.70
        }
        assertThat((ready as PumpLinkState.Ready).frequencyMHz).isEqualTo(916.70)
        assertThat(prefs.lastGoodFrequencyMHz).isEqualTo(916.70)
    }
}
