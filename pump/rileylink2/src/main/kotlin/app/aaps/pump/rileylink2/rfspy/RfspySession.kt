package app.aaps.pump.rileylink2.rfspy

import app.aaps.pump.rileylink2.ble.BleConnection
import app.aaps.pump.rileylink2.ble.BleEvent
import app.aaps.pump.rileylink2.ble.BleException
import app.aaps.pump.rileylink2.ble.CharId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * L1: one live subg_rfspy session on top of one operational [BleConnection].
 *
 * Created exclusively via [RfspySession.open], which runs the full bring-up (design rule R1):
 * count baseline, stale-data flush, version probe, encoding, preamble, frequency. A session
 * therefore never exists in a partially-initialized state.
 *
 * Correctness properties (design doc §6):
 * - single in-flight command, enforced by [commandMutex];
 * - write → await count notification → read-back, with the notification subscription
 *   established before the write (no lost-wakeup race);
 * - response-count desync (reboot evidence) closes the session (rule R2);
 * - results carry [epoch]; late results from a previous session are detectable by callers.
 */
class RfspySession private constructor(
    private val conn: BleConnection,
    private val config: SessionConfig,
    val epoch: Int,
    scope: CoroutineScope,
) {

    lateinit var firmwareVersion: String
        private set
    lateinit var protocolVariant: ProtocolVariant
        private set

    private val commandMutex = Mutex()
    private var lastCount: Int = 0

    @Volatile private var closed = false
    private val closeWatcher: Job = scope.launch {
        val event = conn.events.filterIsInstance<BleEvent.Closed>().first()
        closed = true
        onClosed(event.cause.toString())
    }

    /** Hook for the supervisor; assigned before any command can be in flight. */
    var onSessionLost: (reason: String) -> Unit = {}

    val isOpen: Boolean get() = !closed

    suspend fun execute(cmd: RfspyCommand, responseDeadline: Duration): RfspyResult =
        commandMutex.withLock {
            if (closed) throw SessionClosedException("session epoch=$epoch is closed")
            var result = executeLocked(cmd, responseDeadline)
            // Design §6.3: one in-session retry for non-mutating commands that got no doorbell.
            if (!cmd.mutating && result is RfspyResult.Uncertain &&
                result.cause == UncertainCause.NO_RESPONSE_NOTIFICATION && !closed
            ) {
                result = executeLocked(cmd, responseDeadline)
            }
            result
        }

    private suspend fun executeLocked(cmd: RfspyCommand, responseDeadline: Duration): RfspyResult {
        val frame = frame(cmd.body)
        val expectedCount = (lastCount + 1) and 0xFF

        val event: BleEvent? = try {
            withTimeoutOrNull(responseDeadline) {
                conn.events
                    .onSubscription {
                        // Subscription is live: the doorbell cannot be missed between here
                        // and the write completing.
                        conn.write(CharId.RADIO_DATA, frame, config.gattOpTimeout)
                    }
                    .first {
                        (it is BleEvent.NotificationReceived && it.char == CharId.RADIO_RESPONSE_COUNT) ||
                            it is BleEvent.Closed
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: BleException) {
            // Write failed at the BLE layer: the command never reached the device.
            closeInternal("write failed")
            return if (cmd.mutating) RfspyResult.Uncertain(UncertainCause.NO_RESPONSE_NOTIFICATION, epoch)
            else throw SessionClosedException("write failed on epoch=$epoch")
        }

        when (event) {
            null -> // deadline expired: silent drop, or the response is lost (firmware §2.2).
                return RfspyResult.Uncertain(UncertainCause.NO_RESPONSE_NOTIFICATION, epoch)

            is BleEvent.Closed -> {
                closed = true
                return if (cmd.mutating) RfspyResult.Uncertain(UncertainCause.NO_RESPONSE_NOTIFICATION, epoch)
                else throw SessionClosedException("link closed mid-command on epoch=$epoch")
            }

            is BleEvent.NotificationReceived -> {
                val observed = event.bytes.firstOrNull()?.toInt()?.and(0xFF)
                if (observed != expectedCount) {
                    // Count regression/skip = reboot or desync evidence (rule R2): never
                    // resynchronize a live session; tear down and let the supervisor re-open.
                    closeInternal("response count desync: expected=$expectedCount observed=$observed")
                    return RfspyResult.Interrupted(epoch)
                }
                lastCount = observed
            }
        }

        val payload = try {
            conn.read(CharId.RADIO_DATA, config.gattOpTimeout)
        } catch (e: CancellationException) {
            throw e
        } catch (_: BleException) {
            closeInternal("response read-back failed")
            return if (cmd.mutating) RfspyResult.Uncertain(UncertainCause.READBACK_FAILED, epoch)
            else throw SessionClosedException("read-back failed on epoch=$epoch")
        }

        return classify(payload)
    }

    private fun classify(raw: ByteArray): RfspyResult {
        if (raw.isEmpty()) return RfspyResult.Uncertain(UncertainCause.READBACK_FAILED, epoch)
        return when (raw[0].toInt() and 0xFF) {
            RfspyStatus.OK, RfspyStatus.OK_LEGACY -> RfspyResult.Ok(raw.copyOfRange(1, raw.size), epoch)
            RfspyStatus.RX_TIMEOUT, RfspyStatus.ZERO_DATA -> RfspyResult.RadioTimeout(epoch)
            RfspyStatus.INTERRUPTED -> {
                closeInternal("firmware reported interrupted (0xBB)")
                RfspyResult.Interrupted(epoch)
            }

            RfspyStatus.PARAM_ERROR, RfspyStatus.UNKNOWN_COMMAND ->
                RfspyResult.FirmwareError(raw[0].toInt() and 0xFF, epoch)

            else -> // Bare radio packet without status prefix: surface as payload; L2 validates.
                RfspyResult.Ok(raw, epoch)
        }
    }

    suspend fun close() {
        closeInternal("local close")
        conn.close()
    }

    private fun closeInternal(reason: String) {
        if (!closed) {
            closed = true
            onClosed(reason)
        }
    }

    private fun onClosed(reason: String) {
        closeWatcher.cancel()
        onSessionLost(reason)
    }

    companion object {

        /** Data characteristic capacity (firmware: 150) minus the length prefix. */
        const val MAX_BODY = 149

        fun frame(body: ByteArray): ByteArray {
            require(body.size in 1..MAX_BODY) { "rfspy frame body size ${body.size} out of range" }
            return byteArrayOf(body.size.toByte()) + body
        }

        /**
         * Open a session: full bring-up per design §6.2 / rule R1. Throws
         * [IncompatibleFirmwareException] or [BringUpException]; cancellable throughout.
         */
        suspend fun open(
            conn: BleConnection,
            config: SessionConfig,
            epoch: Int,
            scope: CoroutineScope,
        ): RfspySession {
            val session = RfspySession(conn, config, epoch, scope)

            // 1. Response-count baseline.
            session.lastCount = conn.read(CharId.RADIO_RESPONSE_COUNT, config.gattOpTimeout)
                .firstOrNull()?.toInt()?.and(0xFF) ?: 0
            // 2. Flush the stale data-characteristic value (firmware returns last value on read).
            conn.read(CharId.RADIO_DATA, config.gattOpTimeout)

            // 3. Version probe — the only gate; anything but subg_rfspy 2.x is unsupported.
            val version = session.execute(RfspyCommand.GetVersion, config.bringUpCmdDeadline)
            val versionString = (version as? RfspyResult.Ok)?.payload?.toString(Charsets.US_ASCII)
                ?: throw BringUpException("version probe failed: $version")
            if (!versionString.startsWith("subg_rfspy 2."))
                throw IncompatibleFirmwareException(versionString)
            session.firmwareVersion = versionString
            session.protocolVariant =
                if (versionString.contains("-ol")) ProtocolVariant.V2_OL1 else ProtocolVariant.STOCK

            // 4–6. Radio state is assumed lost (rule R1): encoding, preamble, frequency.
            session.bringUpStep(RfspyCommand.SetSwEncoding(config.encoding), config)
            session.bringUpStep(RfspyCommand.SetPreamble(config.preamble), config)
            val (f2, f1, f0) = FrequencyRegisters.forMhz(config.frequencyMHz)
            session.bringUpStep(RfspyCommand.UpdateRegister(RadioRegister.FREQ2, f2), config)
            session.bringUpStep(RfspyCommand.UpdateRegister(RadioRegister.FREQ1, f1), config)
            session.bringUpStep(RfspyCommand.UpdateRegister(RadioRegister.FREQ0, f0), config)

            return session
        }
    }

    private suspend fun bringUpStep(cmd: RfspyCommand, config: SessionConfig) {
        val result = execute(cmd, config.bringUpCmdDeadline)
        if (result !is RfspyResult.Ok) throw BringUpException("bring-up step $cmd failed: $result")
    }
}

enum class ProtocolVariant { STOCK, V2_OL1 }

data class SessionConfig(
    val frequencyMHz: Double,
    val encoding: SwEncoding = SwEncoding.NONE,
    val preamble: Int = 0x0000,
    val gattOpTimeout: Duration = 2.seconds,
    val bringUpCmdDeadline: Duration = 3.seconds,
)

class IncompatibleFirmwareException(val version: String) :
    Exception("unsupported rfspy firmware: $version")

class BringUpException(message: String) : Exception(message)
