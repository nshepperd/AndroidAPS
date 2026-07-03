package app.aaps.pump.rileylink2.fake

import app.aaps.pump.rileylink2.ble.BleConnection
import app.aaps.pump.rileylink2.ble.BleEvent
import app.aaps.pump.rileylink2.ble.BleException
import app.aaps.pump.rileylink2.ble.BleLink
import app.aaps.pump.rileylink2.ble.CharId
import app.aaps.pump.rileylink2.ble.CloseCause
import app.aaps.pump.rileylink2.ble.ScanHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
 * - command queue depth 1+processing; overflow drops silently;
 * - ~10 ms command loop latency; SendAndListen consumes its listen timeout on radio silence;
 * - trailing 0x00 stripped from transmitted Minimed radio packets;
 * - reboot(): volatile state loss (count→0, encoding→NONE, preamble→0, frequency→default),
 *   BLE drop with GATT status 8, immediate re-advertising.
 */
class FakeOrangeLink(
    private val scope: CoroutineScope,
    var versionString: String = "subg_rfspy 2.2",
) : BleLink {

    val address = "F1:47:96:C3:E3:C0"

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

        // capacity 1 + the one being processed = firmware queue depth 2 ("effectively 1")
        private val commands = Channel<ByteArray>(capacity = 1)
        private val worker = scope.launch {
            for (body in commands) {
                delay(CMD_LOOP_MS)                     // firmware 10 ms command loop
                val response = executeOnDevice(body)
                if (!open) break
                lastDataValue = response               // response is READ back, never pushed
                // firmware ble_ips.c:457-460 — counter is zeroed on REACHING 0xff, so the
                // observable sequence is 0…254 → 0 (mod 255; the value 255 never appears)
                responseCount = (responseCount + 1) % 255
                _events.emit(BleEvent.NotificationReceived(CharId.RADIO_RESPONSE_COUNT, byteArrayOf(responseCount.toByte())))
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
            // queue overflow: silent drop
            commands.trySend(bytes.copyOfRange(1, bytes.size))
        }

        override suspend fun read(char: CharId, timeout: Duration): ByteArray {
            if (!open) throw BleException.LinkClosed(CloseCause.LinkLost(8))
            return when (char) {
                CharId.RADIO_RESPONSE_COUNT -> byteArrayOf(responseCount.toByte())
                CharId.RADIO_DATA           -> lastDataValue      // stale-read semantics
                CharId.RADIO_VERSION        -> "ble_rfspy 2.0".toByteArray(Charsets.US_ASCII)
                CharId.BATTERY_LEVEL        -> byteArrayOf(80)
                CharId.ORANGE_NOTIFICATION  -> ByteArray(0)
            }
        }

        override suspend fun subscribe(char: CharId, timeout: Duration) {
            if (!open) throw BleException.LinkClosed(CloseCause.LinkLost(8))
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

        private suspend fun executeOnDevice(body: ByteArray): ByteArray {
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
                    // Minimed mode strips a trailing 0x00 before TX (app_aps.c:381)
                    if (packet.isNotEmpty() && packet.last() == 0.toByte())
                        packet = packet.copyOfRange(0, packet.size - 1)
                    val repeatCount = params[1].toInt() and 0xFF
                    val tx = RadioTx(packet, repeatCount, delayMs, listenTimeoutMs, retryCount)
                    txLog += tx
                    when (val reply = radioBehavior(tx)) {
                        is RadioReply.Silence -> {
                            delay((repeatCount.toLong() * delayMs) + listenTimeoutMs.toLong() * (retryCount + 1))
                            byteArrayOf(0xAA.toByte())
                        }

                        is RadioReply.Packet  -> {
                            delay(reply.afterMs)
                            ok(byteArrayOf(reply.rssiRaw.toByte(), reply.packetCount.toByte()) + reply.bytes)
                        }

                        is RadioReply.Hang    -> {
                            delay(Long.MAX_VALUE)     // firmware wedged; only reboot recovers
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
                14   -> ok(ByteArray(18))
                else -> byteArrayOf(0x22)
            }
        }

        private fun ok(payload: ByteArray): ByteArray = byteArrayOf(0xDD.toByte()) + payload
    }

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
