package app.aaps.pump.rileylink2.fake

import app.aaps.pump.rileylink2.ble.BleConnection
import app.aaps.pump.rileylink2.ble.BleEvent
import app.aaps.pump.rileylink2.ble.BleException
import app.aaps.pump.rileylink2.ble.BleLink
import app.aaps.pump.rileylink2.ble.CharId
import app.aaps.pump.rileylink2.ble.CloseCause
import app.aaps.pump.rileylink2.ble.ScanHit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlin.time.Duration

/**
 * In-JVM model of the OrangeLink firmware (stock personality, subg_rfspy 2.2), built from
 * the firmware source analysis (design doc §2). Deliberately implements the quirks:
 *
 * - response-count doorbell + data-characteristic READ-BACK (no data notifications);
 * - stale reads: the data characteristic returns the LAST value when nothing is pending;
 * - malformed frames (length byte != total-1) are dropped silently;
 * - command queue depth [queueDepth]+processing (vendor firmware: 1; -ol1: 8, BOTH paths —
 *   confirmed by Emily 2026-07-03); overflow drops silently on the stock path;
 * - a command enqueued while a radio op is in flight INTERRUPTS it: the radio op completes
 *   0xBB through its own response path, then the new command runs (original subg_rfspy
 *   behavior, bench-verified on stock OrangeLink 2026-07-03; PROTOCOL-V2.md §stock).
 *   [RadioReply.Hang] deliberately stays non-interruptible — it models a hard firmware
 *   wedge (stuck outside the command loop), where only reboot recovers;
 * - ~10 ms command loop latency; SendAndListen consumes its listen timeout on radio silence;
 * - trailing 0x00 stripped from transmitted Minimed radio packets (STOCK path only — the v2
 *   envelope's length is authoritative and nothing is stripped, per PROTOCOL-V2.md);
 * - unknown opcodes on the stock path are dropped SILENTLY (spec: bug-for-bug);
 * - DIS Firmware/Software Revision strings when set (firmware ≥ gd5fa522d); reads of the
 *   characteristics throw ServiceMismatch when unset, like a real missing characteristic;
 * - reboot(): volatile state loss (count→0, encoding→NONE, preamble→0, frequency→default),
 *   BLE drop with GATT status 8, immediate re-advertising.
 *
 * v2 personality (versionString containing "-ol1"; PROTOCOL-V2.md, construct with
 * queueDepth = 8 to match -ol1 firmware — the deeper queue applies to BOTH paths):
 * - `0x10` envelopes are honored only while the RESPONSE_V2 CCCD is subscribed (tracked
 *   per connection — never persisted across reconnects); otherwise 0x10 is a stock unknown
 *   opcode → silent drop;
 * - immediate acks emitted synchronously from write() (BLE event context): E0 ACCEPTED /
 *   E1 BUSY (queue full) / E2 MTU < 131 (checked per command) / [seq][0x11] malformed
 *   (no inner opcode, params > 123); an envelope with no seq byte is dropped silently;
 * - completions [seq][status][payload] from the worker (thread context); they do NOT bump
 *   the stock response count and do NOT touch the data characteristic;
 * - unknown inner opcodes — including an enveloped 0x10 — queue normally (E0) and complete
 *   [seq][0x22] (v2 answers where stock stays silent; firmware-source-verified).
 *
 * Concurrency note: like the firmware's single command loop, the fake assumes a
 * single-threaded (runTest) dispatcher; ack/completion ordering is not defended against
 * a multithreaded scope.
 */
class FakeOrangeLink(
    private val scope: CoroutineScope,
    var versionString: String = "subg_rfspy 2.2",
    private val queueDepth: Int = 1,
) : BleLink {

    val address = "F1:47:96:C3:E3:C0"

    private val isV2Firmware: Boolean get() = versionString.contains("-ol")

    // ---- volatile device state (lost on reboot) ----
    var responseCount: Int = 0
    var swEncoding: Int = 0            // 0 = NONE
    var preamble: Int = 0
    val registers = HashMap<Int, Int>()
    var lastDataValue: ByteArray = ByteArray(0)

    // ---- persistent-ish observability ----
    var bootCount: Int = 1
    val txLog = mutableListOf<RadioTx>()

    // ---- test hooks ----
    var radioBehavior: (RadioTx) -> RadioReply = { RadioReply.Silence }
    var dropNextCommand: Boolean = false
    var dropAllCommands: Boolean = false
    var refuseConnections: Boolean = false
    var connectAttempts: Int = 0
    var bleRssiDbm: Int = -55
    var negotiatedMtu: Int = 247
    /** DIS strings; null = characteristic absent (firmware < gd5fa522d / vendor RileyLink). */
    var disFirmwareRevision: String? = null
    var disSoftwareRevision: String? = null
    /** GET_STATISTICS payload (firmware: 18 bytes of counters). */
    var statisticsPayload: ByteArray = ByteArray(18)

    private var connection: FakeConnection? = null

    override fun scan(filterAddress: String?, timeout: Duration): Flow<ScanHit> =
        flowOf(ScanHit(address, "OrangeLink", rssi = -60))

    override suspend fun connect(address: String, timeout: Duration): BleConnection {
        connectAttempts++
        if (refuseConnections) throw BleException.ConnectFailed(gattStatus = 133, message = "refused (fake)")
        check(this.connection?.open != true) { "fake supports one connection at a time" }
        return FakeConnection().also { connection = it }
    }

    /** Firmware reboot: volatile state lost, link drops (status 8), re-advertises at once. */
    suspend fun reboot() {
        bootCount++
        responseCount = 0
        swEncoding = 0
        preamble = 0
        registers.clear()
        lastDataValue = ByteArray(0)
        connection?.dropFromDevice(CloseCause.LinkLost(gattStatus = 8))
    }

    /** Link loss without reboot (range/supervision timeout): device state survives. */
    suspend fun dropLink() {
        connection?.dropFromDevice(CloseCause.LinkLost(gattStatus = 8))
    }

    /** Corrupt the count bookkeeping without a visible disconnect (desync injection). */
    fun skewResponseCount(delta: Int) {
        responseCount = (responseCount + delta) and 0xFF
    }

    inner class FakeConnection : BleConnection {

        private val _events = MutableSharedFlow<BleEvent>(extraBufferCapacity = 64)
        override val events: SharedFlow<BleEvent> = _events.asSharedFlow()

        @Volatile var open: Boolean = true
            private set

        override val mtu: Int get() = negotiatedMtu

        /** RESPONSE_V2 CCCD state — per connection, never survives a reconnect. */
        @Volatile var subscribedV2: Boolean = false
            private set

        // [queueDepth] + the one being processed (vendor: 1+1 "effectively 1"; -ol1: 8+1)
        private val commands = Channel<QueuedCmd>(capacity = queueDepth)

        /** Completed when an enqueue must interrupt the in-flight radio op (→ 0xBB). */
        @Volatile private var radioOpInterrupt: CompletableDeferred<Unit>? = null

        private val worker = scope.launch {
            for (cmd in commands) {
                // Radio ops are interrupt-eligible from the moment they leave the queue: a
                // command enqueued during the 10 ms command-loop latency must still abort
                // them (firmware aborts from BLE event context, not from the command loop).
                if (cmd.body.firstOrNull()?.toInt() == 5) radioOpInterrupt = CompletableDeferred()
                try {
                    delay(CMD_LOOP_MS)                 // firmware 10 ms command loop
                    // v2 envelope length is authoritative — no trailing-0x00 strip (spec).
                    val response = executeOnDevice(cmd.body, stripTrailingZero = cmd.v2Seq == null)
                    if (!open) break
                    when {
                        cmd.v2Seq != null -> {
                            // v2 completion [seq][status][payload] from thread context; no
                            // count bump, no data buffer. Unknown inner opcode: v2 ANSWERS
                            // 0x22 where the stock path stays silent.
                            val completion = response ?: byteArrayOf(0x22)
                            _events.emit(
                                BleEvent.NotificationReceived(
                                    CharId.RADIO_RESPONSE_V2, byteArrayOf(cmd.v2Seq) + completion
                                )
                            )
                        }

                        response != null  -> {
                            lastDataValue = response   // response is READ back, never pushed
                            // firmware ble_ips.c:457-460 — counter is zeroed on REACHING 0xff:
                            // the observable sequence is 0…254 → 0 (the value 255 never appears)
                            responseCount = (responseCount + 1) % 255
                            _events.emit(BleEvent.NotificationReceived(CharId.RADIO_RESPONSE_COUNT, byteArrayOf(responseCount.toByte())))
                        }

                        // else: stock unknown opcode — silent, bug-for-bug (spec §stock)
                    }
                } finally {
                    radioOpInterrupt = null
                }
            }
        }

        override suspend fun readRemoteRssi(timeout: Duration): Int {
            if (!open) throw BleException.LinkClosed(CloseCause.LinkLost(8))
            return bleRssiDbm
        }

        override suspend fun write(char: CharId, bytes: ByteArray, timeout: Duration) {
            if (!open) throw BleException.LinkClosed(CloseCause.LinkLost(8))
            if (char != CharId.RADIO_DATA) return
            // firmware app_aps.c:664 — malformed frames dropped with no observable effect
            if (bytes.isEmpty() || bytes[0].toInt() and 0xFF != bytes.size - 1 || bytes.size == 1) return
            if (dropAllCommands) return
            if (dropNextCommand) {
                dropNextCommand = false
                return
            }
            val body = bytes.copyOfRange(1, bytes.size)
            if (isV2Firmware && subscribedV2 && (body[0].toInt() and 0xFF) == 0x10) {
                handleV2Envelope(body)
                return
            }
            // Stock path — including 0x10 while unsubscribed or on vendor firmware, which
            // is then just an unknown opcode (silent drop in the worker). -ol1 added a
            // bounds check (app_aps.c: req.pkt holds 123 B): stock drops oversize silently,
            // vendor firmware would have overflowed (not modeled). Queue overflow: silent
            // drop. A successful enqueue interrupts an in-flight radio op.
            if (isV2Firmware && body.size - 1 > 123) return
            if (commands.trySend(QueuedCmd(v2Seq = null, body = body)).isSuccess)
                radioOpInterrupt?.complete(Unit)
        }

        /**
         * Immediate acks, synchronously from BLE event context, modeled from the firmware
         * intake (app_aps.c Aps_CmdIn): no-seq → silent; MTU check FIRST; no inner opcode
         * or params > 123 → [seq][0x11]; queue full → [seq][0xE1]; else queued + [seq][0xE0]
         * emitted BEFORE the interrupt flag (firmware notifies ACCEPTED, then SetIntFlg).
         * An enveloped 0x10 is NOT special-cased — it queues like any inner opcode and the
         * dispatcher answers [seq][0x22] as a completion (verified against firmware source;
         * the spec's "answered [seq][0x22]" wording is ack-position-ambiguous).
         * Byte values are deliberately literals from PROTOCOL-V2.md, NOT V2Codec constants:
         * the fake must stay independent of the code under test (B1 rule).
         * NOTE: enveloped CANCEL (0x0F) is not modeled yet — Phase C, together with the
         * stock-path CANCEL and its per-path ack ordering.
         */
        private suspend fun handleV2Envelope(body: ByteArray) {
            if (body.size < 2) return                              // no seq byte → silent drop
            val seq = body[1]
            if (negotiatedMtu < 131) return ackV2(seq, 0xE2)       // checked on EVERY v2 command
            if (body.size < 3) return ackV2(seq, 0x11)             // no inner opcode
            val inner = body.copyOfRange(2, body.size)
            if (inner.size - 1 > 123) return ackV2(seq, 0x11)      // params > 123 B
            if (!commands.trySend(QueuedCmd(v2Seq = seq, body = inner)).isSuccess)
                return ackV2(seq, 0xE1)                            // queue full → BUSY
            ackV2(seq, 0xE0)                                       // ACCEPTED, before execution
            radioOpInterrupt?.complete(Unit)
        }

        private suspend fun ackV2(seq: Byte, code: Int) {
            _events.emit(BleEvent.NotificationReceived(CharId.RADIO_RESPONSE_V2, byteArrayOf(seq, code.toByte())))
        }

        override suspend fun read(char: CharId, timeout: Duration): ByteArray {
            if (!open) throw BleException.LinkClosed(CloseCause.LinkLost(8))
            return when (char) {
                CharId.RADIO_RESPONSE_COUNT  -> byteArrayOf(responseCount.toByte())
                CharId.RADIO_DATA            -> lastDataValue      // stale-read semantics
                CharId.RADIO_VERSION         -> "ble_rfspy 2.0".toByteArray(Charsets.US_ASCII)
                CharId.RADIO_RESPONSE_V2     -> // NOTIFY only: a real stack refuses the read
                    throw BleException.OperationTimeout("read(RADIO_RESPONSE_V2) refused by stack")
                CharId.BATTERY_LEVEL         -> byteArrayOf(80)
                CharId.ORANGE_NOTIFICATION   -> ByteArray(0)
                CharId.DIS_FIRMWARE_REVISION -> disFirmwareRevision?.toByteArray(Charsets.US_ASCII)
                    ?: throw BleException.ServiceMismatch("characteristic DIS_FIRMWARE_REVISION missing")
                CharId.DIS_SOFTWARE_REVISION -> disSoftwareRevision?.toByteArray(Charsets.US_ASCII)
                    ?: throw BleException.ServiceMismatch("characteristic DIS_SOFTWARE_REVISION missing")
            }
        }

        override suspend fun subscribe(char: CharId, timeout: Duration) {
            if (!open) throw BleException.LinkClosed(CloseCause.LinkLost(8))
            if (char == CharId.RADIO_RESPONSE_V2) subscribedV2 = true
        }

        /** Test hook: inject an arbitrary notification (garbage/violation scenarios). */
        suspend fun injectNotification(char: CharId, bytes: ByteArray) {
            _events.emit(BleEvent.NotificationReceived(char, bytes))
        }

        override suspend fun close() {
            if (open) {
                open = false
                worker.cancel()
                _events.emit(BleEvent.Closed(CloseCause.LocalClose))
            }
        }

        internal suspend fun dropFromDevice(cause: CloseCause) {
            if (open) {
                open = false
                worker.cancel()
                _events.emit(BleEvent.Closed(cause))
            }
        }

        /** null = no response at all (stock unknown opcode is dropped silently, spec §stock). */
        private suspend fun executeOnDevice(body: ByteArray, stripTrailingZero: Boolean): ByteArray? {
            val opcode = body[0].toInt() and 0xFF
            val params = body.copyOfRange(1, body.size)
            return when (opcode) {
                1    -> ok("OK".toByteArray(Charsets.US_ASCII))
                2    -> ok(versionString.toByteArray(Charsets.US_ASCII))

                5    -> { // SendAndListen
                    val delayMs = ByteBuffer.wrap(params, 2, 2).short.toInt() and 0xFFFF
                    val listenTimeoutMs = ByteBuffer.wrap(params, 5, 4).int
                    val retryCount = params[9].toInt() and 0xFF
                    var packet = params.copyOfRange(12, params.size)
                    // Minimed mode strips a trailing 0x00 before TX (app_aps.c:381) — stock
                    // path only; the v2 envelope's length is authoritative (no strip).
                    if (stripTrailingZero && packet.isNotEmpty() && packet.last() == 0.toByte())
                        packet = packet.copyOfRange(0, packet.size - 1)
                    val repeatCount = params[1].toInt() and 0xFF
                    val tx = RadioTx(packet, repeatCount, delayMs, listenTimeoutMs, retryCount)
                    txLog += tx
                    when (val reply = radioBehavior(tx)) {
                        is RadioReply.Silence ->
                            if (radioOpDelay((repeatCount.toLong() * delayMs) + listenTimeoutMs.toLong() * (retryCount + 1)))
                                byteArrayOf(0xAA.toByte())
                            else byteArrayOf(0xBB.toByte())

                        is RadioReply.Packet  ->
                            if (radioOpDelay(reply.afterMs))
                                ok(byteArrayOf(reply.rssiRaw.toByte(), reply.packetCount.toByte()) + reply.bytes)
                            else byteArrayOf(0xBB.toByte())

                        is RadioReply.Hang    -> {
                            delay(Long.MAX_VALUE)     // hard wedge, NOT interruptible; only reboot recovers
                            byteArrayOf(0xBB.toByte())
                        }
                    }
                }

                6    -> { // UpdateRegister
                    registers[params[0].toInt() and 0xFF] = params[1].toInt() and 0xFF
                    ok(ByteArray(0))
                }

                11   -> if (params[0] in 0..2) {
                    swEncoding = params[0].toInt(); ok(ByteArray(0))
                } else byteArrayOf(0x11)

                12   -> {
                    preamble = ByteBuffer.wrap(params).short.toInt() and 0xFFFF
                    ok(ByteArray(0))
                }

                13   -> ok(ByteArray(0))
                14   -> ok(statisticsPayload)
                else -> null   // stock: unknown opcodes dropped silently (spec, bug-for-bug)
            }
        }

        /**
         * Wait out a radio-op duration, interruptible by a newly enqueued command
         * (firmware aborts the in-flight radio operation from BLE event context). The
         * interrupt deferred was installed by the worker at dequeue time, so an enqueue
         * during the command-loop latency also lands.
         * @return true if the full duration elapsed; false if interrupted (caller answers 0xBB).
         */
        private suspend fun radioOpDelay(ms: Long): Boolean {
            val interrupt = checkNotNull(radioOpInterrupt) { "radioOpDelay outside a radio op" }
            return withTimeoutOrNull(ms) { interrupt.await() } == null
        }

        private fun ok(payload: ByteArray): ByteArray = byteArrayOf(0xDD.toByte()) + payload
    }

    /** One queued firmware command: [v2Seq] null = stock path, else the envelope's seq. */
    private class QueuedCmd(val v2Seq: Byte?, val body: ByteArray)

    companion object {
        const val CMD_LOOP_MS = 10L
    }
}

data class RadioTx(
    val packet: ByteArray,
    val repeatCount: Int,
    val delayMs: Int,
    val listenTimeoutMs: Int,
    val retryCount: Int,
) {
    override fun equals(other: Any?): Boolean = other is RadioTx && other.packet.contentEquals(packet)
    override fun hashCode(): Int = packet.contentHashCode()
}

sealed interface RadioReply {
    /** Pump silent: firmware burns the full listen budget, answers 0xAA. */
    data object Silence : RadioReply
    /** Pump answered: 0xDD + rssi + packetCount + bytes after [afterMs]. */
    data class Packet(val bytes: ByteArray, val rssiRaw: Int = 0x8D, val packetCount: Int = 1, val afterMs: Long = 300) : RadioReply
    /** Firmware wedge (WDT territory): no response until reboot. */
    data object Hang : RadioReply
}
