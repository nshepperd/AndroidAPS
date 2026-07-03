package app.aaps.pump.rileylink2.rfspy

import app.aaps.pump.rileylink2.ble.BleConnection
import app.aaps.pump.rileylink2.ble.BleEvent
import app.aaps.pump.rileylink2.ble.CharId
import app.aaps.pump.rileylink2.fake.FakeOrangeLink
import app.aaps.pump.rileylink2.fake.RadioReply
import app.aaps.pump.rileylink2.minimed.Minimed4b6b
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Protocol v2 conformance (plan Phase B, PROTOCOL-V2.md) against the FakeOrangeLink -ol1
 * personality. Stock-personality behavior is covered by [RfspySessionTest]; the contract
 * here is: opt-in at bring-up, envelope exchange, immediate NACKs, RF byte identity.
 */
class RfspySessionV2Test {

    private fun config() = SessionConfig(frequencyMHz = 916.55)

    private fun v2Fake(scope: CoroutineScope) =
        FakeOrangeLink(scope, versionString = "subg_rfspy 2.2-ol1", queueDepth = 8)

    private suspend fun TestScope.openV2(fake: FakeOrangeLink): Pair<RfspySession, BleConnection> {
        val conn = fake.connect(fake.address, 5.seconds)
        return RfspySession.open(conn, config(), epoch = 1, scope = backgroundScope) to conn
    }

    private fun sendAndListen(packet: ByteArray, timeoutMs: Int = 1250) = RfspyCommand.SendAndListen(
        sendChannel = 0, repeatCount = 0, delayBetweenPacketsMs = 0,
        listenChannel = 0, timeoutMs = timeoutMs, retryCount = 0,
        preambleExtensionMs = 0, packet = packet,
    )

    // -- opt-in ---------------------------------------------------------------------------------

    @Test
    fun `bring-up activates v2 - radio config and statistics run through the envelope`() = runTest {
        val fake = v2Fake(backgroundScope)
        val (session, conn) = openV2(fake)

        assertThat(session.protocolVariant).isEqualTo(ProtocolVariant.V2_OL1)
        assertThat(session.v2Active).isTrue()
        assertThat((conn as FakeOrangeLink.FakeConnection).subscribedV2).isTrue()
        // Only the version probe ran on the stock path; encoding/preamble/FREQx/statistics
        // were enveloped and must not bump the stock response count (spec §Completion).
        assertThat(fake.responseCount).isEqualTo(1)
        val (f2, f1, f0) = FrequencyRegisters.forMhz(916.55)
        assertThat(fake.registers[RadioRegister.FREQ2]).isEqualTo(f2)
        assertThat(fake.registers[RadioRegister.FREQ1]).isEqualTo(f1)
        assertThat(fake.registers[RadioRegister.FREQ0]).isEqualTo(f0)
        assertThat(session.bringUpStatisticsHex).isEqualTo("00".repeat(18))
    }

    @Test
    fun `MTU below 131 falls back to stock for the whole session`() = runTest {
        val fake = v2Fake(backgroundScope)
        fake.negotiatedMtu = 23

        val (session, _) = openV2(fake)

        assertThat(session.v2Active).isFalse()
        assertThat(session.v2FallbackReason).contains("mtu=23")
        assertThat(session.bringUpStatisticsHex).isNull()
        // The session is fully functional on the stock path.
        assertThat(session.execute(RfspyCommand.GetState, 5.seconds)).isInstanceOf(RfspyResult.Ok::class.java)
    }

    // -- envelope exchange ----------------------------------------------------------------------

    @Test
    fun `v2 completion carries the stock payload and leaves the stock buffer and count untouched`() = runTest {
        val fake = v2Fake(backgroundScope)
        fake.radioBehavior = { RadioReply.Packet(bytes = byteArrayOf(0x77, 0x66), rssiRaw = 0x8D, packetCount = 3) }
        val (session, _) = openV2(fake)
        val countBefore = fake.responseCount
        val bufferBefore = fake.lastDataValue

        val result = session.execute(sendAndListen(byteArrayOf(0x01)), 30.seconds)

        assertThat(result).isInstanceOf(RfspyResult.Ok::class.java)
        assertThat((result as RfspyResult.Ok).payload)
            .isEqualTo(byteArrayOf(0x8D.toByte(), 0x03, 0x77, 0x66))    // byte-identical to stock
        assertThat(fake.responseCount).isEqualTo(countBefore)
        assertThat(fake.lastDataValue).isSameInstanceAs(bufferBefore)
    }

    @Test
    fun `pump silence on v2 classifies as RadioTimeout, session stays open`() = runTest {
        val fake = v2Fake(backgroundScope)
        val (session, _) = openV2(fake)

        val result = session.execute(sendAndListen(byteArrayOf(0x01, 0x02)), 30.seconds)

        assertThat(result).isInstanceOf(RfspyResult.RadioTimeout::class.java)
        assertThat(session.isOpen).isTrue()
    }

    @Test
    fun `RF bytes are identical on the stock and v2 paths (I8 - trailing 0x00)`() = runTest {
        // The legacy encoder appends a 0x00 terminator that stock firmware strips before
        // keying; the v2 envelope strips nothing. The host-side V2Codec strip keeps the
        // on-air bytes identical — THE silent-divergence trap of this protocol (plan D6).
        val message = byteArrayOf(0xA7.toByte(), 0x12, 0x89.toByte(), 0x06, 0x0D, 0x00)
        val encoded = Minimed4b6b.encodeForTx(message)
        assertThat(encoded.last()).isEqualTo(0.toByte())                 // precondition: terminator

        suspend fun txPacketOf(fake: FakeOrangeLink): ByteArray {
            val conn = fake.connect(fake.address, 5.seconds)
            val session = RfspySession.open(conn, config(), epoch = 1, scope = backgroundScope)
            session.execute(sendAndListen(encoded), 30.seconds)
            return fake.txLog.single().packet
        }

        val stockTx = txPacketOf(FakeOrangeLink(backgroundScope))
        val v2Tx = txPacketOf(v2Fake(backgroundScope))

        assertThat(v2Tx).isEqualTo(stockTx)
    }

    @Test
    fun `envelope strips the terminator only inside the packet field`() {
        // Non-empty packet ending in the 0x00 terminator → stripped (RF identity).
        val withPacket = sendAndListen(byteArrayOf(0x0A, 0x0B, 0x00)).body
        assertThat(V2Codec.envelope(1, withPacket).size).isEqualTo(2 + withPacket.size - 1)

        // EMPTY packet: the body still ends in 0x00, but it is the preambleExtensionMs LSB
        // — a header byte the firmware's stock-path strip never touches. Must NOT strip.
        val emptyPacket = sendAndListen(ByteArray(0)).body
        assertThat(emptyPacket.last()).isEqualTo(0.toByte())
        assertThat(V2Codec.envelope(1, emptyPacket).size).isEqualTo(2 + emptyPacket.size)
    }

    @Test
    fun `-ol1 stock path silently drops params over 123 bytes (vendor kept the overflow)`() = runTest {
        suspend fun completed(fake: FakeOrangeLink): Int {
            val conn = fake.connect(fake.address, 5.seconds)
            val before = fake.responseCount
            // GetState (opcode 1) with 124 bytes of params — stock-legal frame size.
            conn.write(CharId.RADIO_DATA, RfspySession.frame(byteArrayOf(0x01) + ByteArray(124)), 2.seconds)
            advanceTimeBy(1.seconds)
            return fake.responseCount - before
        }

        assertThat(completed(FakeOrangeLink(backgroundScope, versionString = "subg_rfspy 2.2-ol1", queueDepth = 8)))
            .isEqualTo(0)                                            // -ol1: bounds check, silent drop
        assertThat(completed(FakeOrangeLink(backgroundScope))).isEqualTo(1)   // vendor: executes
    }

    @Test
    fun `garbage completion status closes the session (R2 - no stock leniency on v2)`() = runTest {
        val fake = v2Fake(backgroundScope)
        val (session, conn) = openV2(fake)
        var lostReason: String? = null
        session.onSessionLost = { lostReason = it }

        // Bring-up consumed seqs 0..5 (encoding, preamble, FREQ2/1/0, statistics), so the
        // next command uses seq 6. Silence keeps it in phase 2 while we inject garbage.
        val pending = async { session.execute(sendAndListen(byteArrayOf(0x01), timeoutMs = 30_000), 60.seconds) }
        advanceTimeBy(1.seconds)
        (conn as FakeOrangeLink.FakeConnection).injectNotification(
            CharId.RADIO_RESPONSE_V2, byteArrayOf(0x06, 0x77)
        )

        assertThat(pending.await()).isInstanceOf(RfspyResult.Interrupted::class.java)
        assertThat(session.isOpen).isFalse()
        assertThat(lostReason).contains("protocol violation")
    }

    @Test
    fun `seq wraps at 0xFF without correlation loss`() = runTest {
        val fake = v2Fake(backgroundScope)
        val (session, _) = openV2(fake)

        repeat(300) {                                                    // crosses the 0x00 wrap
            assertThat(session.execute(RfspyCommand.GetState, 5.seconds))
                .isInstanceOf(RfspyResult.Ok::class.java)
        }
        assertThat(session.isOpen).isTrue()
    }

    @Test
    fun `unknown inner opcode completes 0x22 where stock stays silent`() = runTest {
        val fake = v2Fake(backgroundScope)                               // Reset (7) is unmodeled →
        val (session, _) = openV2(fake)                                  // fake answers per v2 spec

        val result = session.execute(RfspyCommand.Reset, 5.seconds)

        assertThat(result).isInstanceOf(RfspyResult.FirmwareError::class.java)
        assertThat((result as RfspyResult.FirmwareError).code).isEqualTo(0x22)
        assertThat(session.isOpen).isTrue()
    }

    // -- immediate NACKs ------------------------------------------------------------------------

    @Test
    fun `MTU checked per command - 0xE2 maps to Rejected`() = runTest {
        val fake = v2Fake(backgroundScope)
        val (session, _) = openV2(fake)
        fake.negotiatedMtu = 23                                          // drops after bring-up

        val result = session.execute(RfspyCommand.GetState, 5.seconds)

        assertThat(result).isInstanceOf(RfspyResult.Rejected::class.java)
        assertThat((result as RfspyResult.Rejected).code).isEqualTo(V2Codec.ACK_MTU_TOO_SMALL)
        assertThat(session.isOpen).isTrue()                              // one NACK is not fatal
    }

    @Test
    fun `three consecutive BUSY acks close the session (Q-b tripwire)`() = runTest {
        val fake = FakeOrangeLink(backgroundScope, versionString = "subg_rfspy 2.2-ol1", queueDepth = 1)
        fake.radioBehavior = { RadioReply.Hang }
        val (session, conn) = openV2(fake)
        var lostReason: String? = null
        session.onSessionLost = { lostReason = it }

        // Rogue client wedges the radio and fills the depth-1 queue (foreign seqs 0xF0/0xF1;
        // their acks arrive while the session is idle and are simply not collected).
        conn.write(CharId.RADIO_DATA, RfspySession.frame(V2Codec.envelope(0xF0, sendAndListen(byteArrayOf(0x01)).body)), 2.seconds)
        runCurrent()                                                     // wedge dequeued, holds the radio
        conn.write(CharId.RADIO_DATA, RfspySession.frame(V2Codec.envelope(0xF1, RfspyCommand.GetState.body)), 2.seconds)

        repeat(RfspySession.MAX_CONSECUTIVE_BUSY) {
            val result = session.execute(RfspyCommand.GetState, 5.seconds)
            assertThat(result).isInstanceOf(RfspyResult.Rejected::class.java)
            assertThat((result as RfspyResult.Rejected).code).isEqualTo(V2Codec.ACK_BUSY)
        }

        assertThat(session.isOpen).isFalse()
        assertThat(lostReason).contains("BUSY")
    }

    @Test
    fun `malformed envelopes are NACKed with the seq echoed`() = runTest {
        val fake = v2Fake(backgroundScope)
        val (_, conn) = openV2(fake)                                     // open() subscribed RESPONSE_V2
        val received = mutableListOf<ByteArray>()
        backgroundScope.launch {
            conn.events.filterIsInstance<BleEvent.NotificationReceived>()
                .collect { if (it.char == CharId.RADIO_RESPONSE_V2) received += it.bytes }
        }
        runCurrent()

        conn.write(CharId.RADIO_DATA, byteArrayOf(0x02, 0x10, 0x07), 2.seconds)          // no inner opcode
        runCurrent()
        assertThat(received.last()).isEqualTo(byteArrayOf(0x07, 0x11))

        // 0x10-in-0x10 is NOT special-cased at intake (firmware source): E0, then a 0x22
        // completion from the dispatcher like any unknown inner opcode.
        conn.write(CharId.RADIO_DATA, byteArrayOf(0x03, 0x10, 0x08, 0x10), 2.seconds)
        advanceTimeBy(1.seconds)
        assertThat(received.any { it.contentEquals(byteArrayOf(0x08, 0xE0.toByte())) }).isTrue()
        assertThat(received.last()).isEqualTo(byteArrayOf(0x08, 0x22))

        val before = received.size
        conn.write(CharId.RADIO_DATA, byteArrayOf(0x01, 0x10), 2.seconds)                // no seq byte
        runCurrent()
        assertThat(received.size).isEqualTo(before)                                      // silent drop
    }

    // -- fault behavior -------------------------------------------------------------------------

    @Test
    fun `stale notification from a timed-out command is ignored, not a session killer`() = runTest {
        val fake = v2Fake(backgroundScope)
        fake.radioBehavior = { RadioReply.Packet(bytes = byteArrayOf(0x42), afterMs = 10_000) }
        val (session, _) = openV2(fake)

        // Mutating command whose completion misses its deadline → Uncertain (I9); the
        // firmware is still executing it.
        val slow = session.execute(sendAndListen(byteArrayOf(0x01)), 2.seconds)
        assertThat(slow).isInstanceOf(RfspyResult.Uncertain::class.java)

        // The next command's enqueue interrupts the still-running one: its stale
        // [oldSeq][0xBB] arrives DURING the new exchange and must be skipped by the seq
        // filter — the plan's close-on-violation would kill the session right here.
        fake.radioBehavior = { RadioReply.Packet(bytes = byteArrayOf(0x43)) }
        val next = session.execute(sendAndListen(byteArrayOf(0x02)), 30.seconds)

        assertThat(next).isInstanceOf(RfspyResult.Ok::class.java)
        assertThat((next as RfspyResult.Ok).payload.last()).isEqualTo(0x43.toByte())
        assertThat(session.isOpen).isTrue()
    }

    @Test
    fun `reboot during a v2 mutating command yields Uncertain and closes the session`() = runTest {
        val fake = v2Fake(backgroundScope)
        fake.radioBehavior = { RadioReply.Hang }
        val (session, _) = openV2(fake)

        val pending = async { session.execute(sendAndListen(byteArrayOf(0x01)), 60.seconds) }
        advanceTimeBy(2.seconds)
        fake.reboot()

        assertThat(pending.await()).isInstanceOf(RfspyResult.Uncertain::class.java)
        assertThat(session.isOpen).isFalse()
    }

    @Test
    fun `stock personality drops unknown opcodes silently (fidelity regression guard)`() = runTest {
        val fake = FakeOrangeLink(backgroundScope)                       // vendor stock personality
        val conn = fake.connect(fake.address, 5.seconds)
        val before = fake.responseCount

        conn.write(CharId.RADIO_DATA, byteArrayOf(0x01, 0x7F), 2.seconds)   // unknown opcode 0x7F
        advanceTimeBy(1.seconds)

        assertThat(fake.responseCount).isEqualTo(before)                 // no response, no count bump
    }
}
