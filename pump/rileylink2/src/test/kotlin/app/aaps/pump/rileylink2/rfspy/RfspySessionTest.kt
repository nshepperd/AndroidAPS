package app.aaps.pump.rileylink2.rfspy

import app.aaps.pump.rileylink2.ble.CharId
import app.aaps.pump.rileylink2.fake.FakeOrangeLink
import app.aaps.pump.rileylink2.fake.RadioReply
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * Conformance tests for RfspySession against the FakeOrangeLink stock personality.
 * Each firmware quirk from design doc §2 is exercised here (§12.1/§12.2).
 */
class RfspySessionTest {

    private fun config() = SessionConfig(frequencyMHz = 916.55)

    private suspend fun TestScope.openSession(fake: FakeOrangeLink): RfspySession =
        RfspySession.open(
            conn = fake.connect(fake.address, 5.seconds),
            config = config(),
            epoch = 1,
            scope = backgroundScope,
        )

    private fun sendAndListen(packet: ByteArray, timeoutMs: Int = 1250) = RfspyCommand.SendAndListen(
        sendChannel = 0, repeatCount = 0, delayBetweenPacketsMs = 0,
        listenChannel = 0, timeoutMs = timeoutMs, retryCount = 0,
        preambleExtensionMs = 0, packet = packet,
    )

    // -- bring-up ----------------------------------------------------------------------------

    @Test
    fun `bring-up detects version and configures encoding, preamble and frequency`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        val session = openSession(fake)

        assertThat(session.firmwareVersion).isEqualTo("subg_rfspy 2.2")
        assertThat(session.protocolVariant).isEqualTo(ProtocolVariant.STOCK)
        assertThat(session.isOpen).isTrue()
        assertThat(fake.swEncoding).isEqualTo(0)                       // NONE, set explicitly
        assertThat(fake.preamble).isEqualTo(0)
        // 916.55 MHz → FREQ2/1/0 registers, same math as legacy stack
        val (f2, f1, f0) = FrequencyRegisters.forMhz(916.55)
        assertThat(fake.registers[RadioRegister.FREQ2]).isEqualTo(f2)
        assertThat(fake.registers[RadioRegister.FREQ1]).isEqualTo(f1)
        assertThat(fake.registers[RadioRegister.FREQ0]).isEqualTo(f0)
    }

    @Test
    fun `bring-up survives stale bytes parked in the data characteristic`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        fake.lastDataValue = byteArrayOf(0xDD.toByte()) + "stale garbage".toByteArray()

        val session = openSession(fake)
        val result = session.execute(RfspyCommand.GetState, 5.seconds)

        assertThat(result).isInstanceOf(RfspyResult.Ok::class.java)
        assertThat((result as RfspyResult.Ok).payload.toString(Charsets.US_ASCII)).isEqualTo("OK")
    }

    @Test
    fun `bring-up rejects firmware other than subg_rfspy 2x`() = runTest {
        val fake = FakeOrangeLink(backgroundScope, versionString = "subg_rfspy 1.0")

        assertFailsWith<IncompatibleFirmwareException> { openSession(fake) }
    }

    @Test
    fun `detects protocol v2 firmware from version suffix`() = runTest {
        val fake = FakeOrangeLink(backgroundScope, versionString = "subg_rfspy 2.2-ol1")

        val session = openSession(fake)

        assertThat(session.protocolVariant).isEqualTo(ProtocolVariant.V2_OL1)
    }

    // -- wire format -------------------------------------------------------------------------

    @Test
    fun `SendAndListen frame is byte-identical to the legacy stack`() {
        // Golden frame captured from the live legacy stack (AndroidAPS log 2026-07-02 16:01):
        // tune-scan GetModel at 916.x, listen timeout 1250 ms.
        val packet = byteArrayOf(
            0xA9.toByte(), 0x6C, 0xB4.toByte(), 0x9A.toByte(), 0x35, 0x65,
            0x68, 0xD5.toByte(), 0x55, 0xC6.toByte(), 0xC5.toByte(), 0x00,
        )
        val frame = RfspySession.frame(sendAndListen(packet).body)

        val expected = byteArrayOf(
            0x19, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x04, 0xE2.toByte(), 0x00, 0x00, 0x00,
        ) + packet
        assertThat(frame).isEqualTo(expected)
    }

    // -- command execution and classification -------------------------------------------------

    @Test
    fun `pump silence classifies as RadioTimeout, link stays open`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)          // default radioBehavior = Silence
        val session = openSession(fake)

        val result = session.execute(sendAndListen(byteArrayOf(0x01, 0x02)), 30.seconds)

        assertThat(result).isInstanceOf(RfspyResult.RadioTimeout::class.java)
        assertThat(session.isOpen).isTrue()
    }

    @Test
    fun `pump reply passes through with rssi and packet count`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        fake.radioBehavior = { RadioReply.Packet(bytes = byteArrayOf(0x77, 0x66), rssiRaw = 0x8D, packetCount = 3) }
        val session = openSession(fake)

        val result = session.execute(sendAndListen(byteArrayOf(0x01)), 30.seconds)

        assertThat(result).isInstanceOf(RfspyResult.Ok::class.java)
        assertThat((result as RfspyResult.Ok).payload)
            .isEqualTo(byteArrayOf(0x8D.toByte(), 0x03, 0x77, 0x66))
    }

    @Test
    fun `trailing zero is stripped from transmitted Minimed packets`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        val session = openSession(fake)

        session.execute(sendAndListen(byteArrayOf(0x0A, 0x0B, 0x00)), 30.seconds)

        assertThat(fake.txLog.single().packet).isEqualTo(byteArrayOf(0x0A, 0x0B))
    }

    @Test
    fun `response count wraps at 0xFF without desync`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        fake.responseCount = 0xFC                            // bring-up's 6 commands cross the wrap
        val session = openSession(fake)

        val result = session.execute(RfspyCommand.GetState, 5.seconds)

        assertThat(result).isInstanceOf(RfspyResult.Ok::class.java)
        assertThat(session.isOpen).isTrue()
    }

    @Test
    fun `response count wrap is mod 255 - the value 0xFF never appears (2026-07-03 soak bug)`() = runTest {
        // Firmware ble_ips.c zeroes the counter on REACHING 0xff: …253 → 254 → 0 → 1…
        // The 2026-07-02 overnight soak hit a false desync every ~255 exchanges because the
        // session modelled the wrap as mod 256 (…254 → 255 → 0…). Walk a session across two
        // full wraps and require zero desyncs.
        val fake = FakeOrangeLink(backgroundScope)
        val session = openSession(fake)

        repeat(520) { i ->
            val result = session.execute(RfspyCommand.GetState, 5.seconds)
            assertThat(result).isInstanceOf(RfspyResult.Ok::class.java)
            assertThat(session.isOpen).isTrue()
            // the wire value must never be 0xFF
            assertThat(fake.responseCount).isLessThan(0xFF)
        }
    }

    // -- fault classes (design doc §3) --------------------------------------------------------

    @Test
    fun `F3 reboot while idle - next command throws SessionClosed`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        val session = openSession(fake)

        fake.reboot()

        assertFailsWith<SessionClosedException> {
            session.execute(RfspyCommand.GetState, 5.seconds)
        }
        assertThat(session.isOpen).isFalse()
    }

    @Test
    fun `F3+F9 reboot during mutating command - result is Uncertain`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        fake.radioBehavior = { RadioReply.Hang }             // wedged until reboot
        val session = openSession(fake)

        val pending = async { session.execute(sendAndListen(byteArrayOf(0x01)), 60.seconds) }
        advanceTimeBy(2.seconds)
        fake.reboot()

        val result = pending.await()
        assertThat(result).isInstanceOf(RfspyResult.Uncertain::class.java)
        assertThat(session.isOpen).isFalse()
    }

    @Test
    fun `F9 silent drop of mutating command - Uncertain, session stays open`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        val session = openSession(fake)
        fake.dropNextCommand = true

        val result = session.execute(sendAndListen(byteArrayOf(0x01)), 2.seconds)

        assertThat(result).isInstanceOf(RfspyResult.Uncertain::class.java)
        assertThat((result as RfspyResult.Uncertain).cause).isEqualTo(UncertainCause.NO_RESPONSE_NOTIFICATION)
        assertThat(session.isOpen).isTrue()                  // link healthy; command was dropped
    }

    @Test
    fun `silent drop of non-mutating command is retried in-session`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        val session = openSession(fake)
        fake.dropNextCommand = true

        val result = session.execute(RfspyCommand.GetState, 2.seconds)

        assertThat(result).isInstanceOf(RfspyResult.Ok::class.java)   // second attempt succeeded
    }

    @Test
    fun `R2 response-count desync closes the session with Interrupted`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        val session = openSession(fake)
        var lostReason: String? = null
        session.onSessionLost = { lostReason = it }

        fake.skewResponseCount(5)
        val result = session.execute(RfspyCommand.GetState, 5.seconds)

        assertThat(result).isInstanceOf(RfspyResult.Interrupted::class.java)
        assertThat(session.isOpen).isFalse()
        assertThat(lostReason).contains("desync")
    }

    @Test
    fun `F2 link loss without reboot closes session`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        val session = openSession(fake)

        fake.dropLink()

        assertFailsWith<SessionClosedException> {
            session.execute(RfspyCommand.GetState, 5.seconds)
        }
    }

    // -- firmware queue + interrupt semantics (PROTOCOL-V2.md §stock, bench 2026-07-03) ------

    @Test
    fun `command enqueued during in-flight radio op interrupts it - 0xBB closes the session (R2)`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        val conn = fake.connect(fake.address, 5.seconds)
        val session = RfspySession.open(conn, config(), epoch = 1, scope = backgroundScope)

        val pending = async { session.execute(sendAndListen(byteArrayOf(0x01), timeoutMs = 30_000), 60.seconds) }
        advanceTimeBy(1.seconds)
        // A rogue write bypassing the session's single-flight mutex (another client, or a host
        // bug): the firmware aborts the in-flight listen, which completes 0xBB.
        conn.write(CharId.RADIO_DATA, RfspySession.frame(RfspyCommand.GetState.body), 2.seconds)

        val result = pending.await()
        assertThat(result).isInstanceOf(RfspyResult.Interrupted::class.java)
        assertThat(session.isOpen).isFalse()
    }

    @Test
    fun `RadioReply Hang models a hard wedge - NOT interruptible by another command`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        fake.radioBehavior = { RadioReply.Hang }
        val conn = fake.connect(fake.address, 5.seconds)
        val session = RfspySession.open(conn, config(), epoch = 1, scope = backgroundScope)

        val pending = async { session.execute(sendAndListen(byteArrayOf(0x01)), 20.seconds) }
        advanceTimeBy(1.seconds)
        conn.write(CharId.RADIO_DATA, RfspySession.frame(RfspyCommand.GetState.body), 2.seconds)

        // No 0xBB: the wedge holds until the session's own deadline gives up.
        val result = pending.await()
        assertThat(result).isInstanceOf(RfspyResult.Uncertain::class.java)
    }

    @Test
    fun `queue depth 1 (vendor) drops overflow silently where depth 8 (-ol1) absorbs it`() = runTest {
        suspend fun completedResponses(depth: Int): Int {
            val fake = FakeOrangeLink(backgroundScope, queueDepth = depth)
            val conn = fake.connect(fake.address, 5.seconds)
            val frame = RfspySession.frame(RfspyCommand.GetState.body)
            repeat(5) {
                conn.write(CharId.RADIO_DATA, frame, 2.seconds)
                runCurrent()          // let the 10 ms command loop pick up work between writes
            }
            advanceTimeBy(5.seconds)
            val count = fake.responseCount
            conn.close()
            return count
        }

        // Depth 1: one processing + one queued; the other three vanish without a trace.
        assertThat(completedResponses(1)).isEqualTo(2)
        // Depth 8 (-ol1, both paths — resolved Q-a): all five complete.
        assertThat(completedResponses(8)).isEqualTo(5)
    }

    @Test
    fun `interrupt lands even during the 10 ms command-loop window`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        val conn = fake.connect(fake.address, 5.seconds)
        val session = RfspySession.open(conn, config(), epoch = 1, scope = backgroundScope)

        val pending = async { session.execute(sendAndListen(byteArrayOf(0x01), timeoutMs = 30_000), 60.seconds) }
        runCurrent()   // S&L dequeued; command-loop latency running, listen not yet started
        conn.write(CharId.RADIO_DATA, RfspySession.frame(RfspyCommand.GetState.body), 2.seconds)

        assertThat(pending.await()).isInstanceOf(RfspyResult.Interrupted::class.java)
    }

    // -- Phase A groundwork: MTU + DIS build provenance ----------------------------------------

    @Test
    fun `negotiated MTU is exposed on the connection`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        fake.negotiatedMtu = 185

        val conn = fake.connect(fake.address, 5.seconds)

        assertThat(conn.mtu).isEqualTo(185)
    }

    @Test
    fun `DeviceInfo parses the DIS software revision string`() {
        val info = DeviceInfo(firmwareRevision = "gd5fa522d 2026-06-28", softwareRevision = "boot:17 rr:0xa")

        assertThat(info.bootCount).isEqualTo(17)
        assertThat(info.resetReason).isEqualTo(0xA)                  // watchdog + lockup
        assertThat(info.toString()).contains("boot=17")
        assertThat(info.toString()).contains("rr=0xa")               // hex, matching the wire format
    }

    @Test
    fun `DeviceInfo keeps the raw string visible when parsing fails`() {
        val info = DeviceInfo(firmwareRevision = null, softwareRevision = "bootloop???")

        assertThat(info.bootCount).isNull()
        assertThat(info.toString()).contains("bootloop???")          // evidence is never dropped
    }

    @Test
    fun `DeviceInfo of() collapses a fully absent DIS to null`() {
        assertThat(DeviceInfo.of(null, null)).isNull()
        assertThat(DeviceInfo.of("g123 2026-01-01", null)).isNotNull()
    }

    @Test
    fun `results carry the session epoch`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)
        val session = RfspySession.open(fake.connect(fake.address, 5.seconds), config(), epoch = 7, scope = backgroundScope)

        val result = session.execute(RfspyCommand.GetState, 5.seconds)

        assertThat((result as RfspyResult.Ok).epoch).isEqualTo(7)
    }
}
