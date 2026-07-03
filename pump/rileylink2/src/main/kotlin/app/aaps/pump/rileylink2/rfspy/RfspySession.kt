package app.aaps.pump.rileylink2.rfspy

import app.aaps.pump.rileylink2.ble.BleConnection
import app.aaps.pump.rileylink2.ble.BleEvent
import app.aaps.pump.rileylink2.ble.BleException
import app.aaps.pump.rileylink2.ble.CharId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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

    /** True when the v2 envelope is in use for every command on this session. */
    var v2Active: Boolean = false
        private set

    /** Why a [ProtocolVariant.V2_OL1] session is NOT running v2 (loud-fallback evidence). */
    var v2FallbackReason: String? = null
        private set

    /** GET_STATISTICS payload captured at bring-up on v2 sessions (plan Q-d), hex. */
    var bringUpStatisticsHex: String? = null
        private set

    private val commandMutex = Mutex()
    private var lastCount: Int = 0
    private var seqCounter = 0
    private var consecutiveBusy = 0

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

    private suspend fun executeLocked(cmd: RfspyCommand, responseDeadline: Duration): RfspyResult =
        if (v2Active) v2ExchangeLocked(cmd, responseDeadline)
        else stockExchangeLocked(cmd, responseDeadline)

    private suspend fun stockExchangeLocked(cmd: RfspyCommand, responseDeadline: Duration): RfspyResult {
        val frame = frame(cmd.body)
        // Firmware wraps the counter mod 255, not mod 256: ble_ips.c zeroes it when it
        // REACHES 0xff, so the value 255 never appears on the wire (0…254 → 0).
        val expectedCount = (lastCount + 1) % 255

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
            return linkLostOutcome(cmd, "write failed")
        }

        when (event) {
            null -> // deadline expired: silent drop, or the response is lost (firmware §2.2).
                return RfspyResult.Uncertain(UncertainCause.NO_RESPONSE_NOTIFICATION, epoch)

            is BleEvent.Closed -> {
                closed = true
                return linkLostOutcome(cmd, "link closed mid-command")
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

    /**
     * v2 exchange (PROTOCOL-V2.md): write envelope → immediate ack ([V2Codec.ACK_ACCEPTED]
     * within [SessionConfig.v2AcceptDeadline]) → completion within [responseDeadline]. All
     * responses arrive as RESPONSE_V2 notifications — no data read-back, no stock count
     * bump. One continuous subscription spans both phases (a completion can land right
     * behind its ack; two separate `first{}` collections would lose it).
     *
     * Notifications for a different seq are IGNORED — deliberate deviation from the plan's
     * close-on-violation (doc §3 D-list): a completion arriving after its command's
     * deadline expired is expected on a live session, and Phase C's CANCEL drains rely on
     * the same tolerance. Genuine reboot evidence on v2 is a link drop, not seq weirdness.
     * A completion BEFORE its ack still closes the session (guaranteed order violated).
     */
    private suspend fun v2ExchangeLocked(cmd: RfspyCommand, responseDeadline: Duration): RfspyResult {
        val seq = seqCounter.also { seqCounter = (it + 1) and 0xFF }
        val frame = frame(V2Codec.envelope(seq, cmd.body))
        lostReceive.set(null)   // no salvage carry-over from a previous exchange

        return try {
            coroutineScope {
                // onUndeliveredElement: withTimeoutOrNull can cancel a receive() that has
                // already dequeued the element (prompt cancellation) — park it here so the
                // post-deadline salvage sweep in awaitSeq can recover it. For a MUTATING
                // command that is the difference between a result and I9 pump-state-unknown.
                val notifications = Channel<BleEvent>(Channel.UNLIMITED) { lostReceive.set(it) }
                val collector = launch {
                    try {
                        conn.events
                            .onSubscription {
                                conn.write(CharId.RADIO_DATA, frame, config.gattOpTimeout)
                            }
                            .collect {
                                if (it is BleEvent.Closed ||
                                    (it is BleEvent.NotificationReceived && it.char == CharId.RADIO_RESPONSE_V2)
                                ) notifications.send(it)
                            }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: BleException) {
                        notifications.close(e)
                    }
                }
                try {
                    v2AwaitResult(cmd, seq, notifications, responseDeadline)
                } finally {
                    collector.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: BleException) {
            // Write failed at the BLE layer: the envelope never reached the device.
            closeInternal("v2 write failed")
            linkLostOutcome(cmd, "v2 write failed")
        }
    }

    private suspend fun v2AwaitResult(
        cmd: RfspyCommand,
        seq: Int,
        notifications: Channel<BleEvent>,
        responseDeadline: Duration,
    ): RfspyResult {
        // ---- phase 1: immediate ack. The deadline must also cover the GATT write itself
        // (it is issued from the collector's onSubscription and is allowed gattOpTimeout on
        // its own) — v2AcceptDeadline is the ack margin BEYOND the write.
        val ack = when (val w = awaitSeq(notifications, seq, config.gattOpTimeout + config.v2AcceptDeadline)) {
            is V2Wait.DeadlineExpired -> // no ack on a live link ⇒ per spec not executed, but
                // first cut stays conservative (plan D5) — same contract as a stock silent drop.
                return RfspyResult.Uncertain(UncertainCause.NO_RESPONSE_NOTIFICATION, epoch)

            is V2Wait.LinkClosed      -> {
                closed = true
                return linkLostOutcome(cmd, "link closed mid-command")
            }

            is V2Wait.Msg             -> w.notification
        }

        when (ack.code) {
            V2Codec.ACK_ACCEPTED                          -> consecutiveBusy = 0

            V2Codec.ACK_BUSY                              -> {
                // Single-flight makes BUSY impossible unless something else is writing to
                // the device (host bug / rogue client). Q-b: log-worthy once, fatal at 3.
                if (++consecutiveBusy >= MAX_CONSECUTIVE_BUSY)
                    closeInternal("v2 BUSY x$consecutiveBusy — single-flight discipline violated")
                return RfspyResult.Rejected(ack.code, epoch)
            }

            V2Codec.ACK_MTU_TOO_SMALL, V2Codec.ACK_MALFORMED ->
                return RfspyResult.Rejected(ack.code, epoch)

            else                                          -> {
                // A completion before its ack violates the guaranteed ACCEPTED→completion
                // order — never rationalize a session talking garbage (R2 posture).
                closeInternal("v2 protocol violation: code=0x${ack.code.toString(16)} before ACCEPTED")
                return RfspyResult.Interrupted(epoch)
            }
        }

        // ---- phase 2: completion (radio-sized deadline) ----
        return when (val w = awaitSeq(notifications, seq, responseDeadline)) {
            is V2Wait.DeadlineExpired -> // firmware still executing; CANCEL reclaim is Phase C.
                RfspyResult.Uncertain(UncertainCause.NO_RESPONSE_NOTIFICATION, epoch)

            is V2Wait.LinkClosed      -> {
                closed = true
                linkLostOutcome(cmd, "link closed mid-command")
            }

            is V2Wait.Msg             ->
                classifyV2Completion(w.notification.code, w.notification.payload)
        }
    }

    /**
     * v2 completion classification. Payloads are byte-identical to stock, but the STATUS
     * contract is stricter: the spec guarantees one of the stock codes, so the stock
     * path's bare-packet leniency (classify's else branch) must NOT leak in here — an
     * unknown status (incl. the reserved 0xE3 and stray ack codes) is a session talking
     * garbage → close (R2), never a payload handed to L2 as pump data.
     */
    private fun classifyV2Completion(code: Int, payload: ByteArray): RfspyResult = when (code) {
        RfspyStatus.OK, RfspyStatus.OK_LEGACY             -> RfspyResult.Ok(payload, epoch)
        RfspyStatus.RX_TIMEOUT, RfspyStatus.ZERO_DATA     -> RfspyResult.RadioTimeout(epoch)

        RfspyStatus.INTERRUPTED                           -> {
            // v2 semantics: interrupted / superseded / cancelled. With single-flight, an
            // UNSOLICITED 0xBB still means a foreign writer superseded us — close. Phase C
            // consumes the 0xBB of its own CANCEL before it can reach this point.
            closeInternal("v2 completion 0xBB (superseded) — single-flight violated")
            RfspyResult.Interrupted(epoch)
        }

        RfspyStatus.PARAM_ERROR, RfspyStatus.UNKNOWN_COMMAND ->
            RfspyResult.FirmwareError(code, epoch)

        else                                              -> {
            closeInternal("v2 protocol violation: completion status 0x${code.toString(16)}")
            RfspyResult.Interrupted(epoch)
        }
    }

    /** Uniform link-death outcome: mutating ⇒ Uncertain (I9); otherwise the session is
     *  unusable for the caller — throw. ONE copy of this policy, shared by both paths. */
    private fun linkLostOutcome(cmd: RfspyCommand, detail: String): RfspyResult =
        if (cmd.mutating) RfspyResult.Uncertain(UncertainCause.NO_RESPONSE_NOTIFICATION, epoch)
        else throw SessionClosedException("$detail on epoch=$epoch")

    /** Element dropped by a timeout-cancelled receive(); recovered by the salvage sweep. */
    private val lostReceive = java.util.concurrent.atomic.AtomicReference<BleEvent?>(null)

    /** Wait for the next notification matching [seq] (foreign seqs skipped), link close, or deadline. */
    private suspend fun awaitSeq(notifications: Channel<BleEvent>, seq: Int, deadline: Duration): V2Wait {
        val awaited = withTimeoutOrNull(deadline) {
            var found: V2Wait? = null
            while (found == null) {
                found = match(notifications.receive(), seq)
            }
            found
        }
        if (awaited != null) return awaited
        // Deadline expired — salvage before declaring it missed: an element may have been
        // dropped by the cancelled receive (lostReceive) or landed just after cancellation.
        while (true) {
            val event = lostReceive.getAndSet(null)
                ?: notifications.tryReceive().getOrNull()
                ?: return V2Wait.DeadlineExpired
            match(event, seq)?.let { return it }
        }
    }

    private fun match(event: BleEvent, seq: Int): V2Wait? = when (event) {
        is BleEvent.Closed               -> V2Wait.LinkClosed
        is BleEvent.NotificationReceived ->
            V2Codec.parse(event.bytes)?.takeIf { it.seq == seq }?.let { V2Wait.Msg(it) }
    }

    private sealed interface V2Wait {
        data class Msg(val notification: V2Codec.Notification) : V2Wait
        data object LinkClosed : V2Wait
        data object DeadlineExpired : V2Wait
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

        /** Q-b (resolved): consecutive v2 BUSY acks before the session is closed. */
        const val MAX_CONSECUTIVE_BUSY = 3

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

            // 3c. Protocol v2 opt-in (plan Phase B): subscribe RESPONSE_V2 (rule R1 — never
            // trust a stack-restored CCCD) and gate on MTU ≥ 131. MTU too small ⇒ the WHOLE
            // session falls back to stock, loudly (plan D3); the supervisor journals it.
            // The remaining bring-up steps then run through the envelope, so a broken v2
            // path fails the session here — not mid-bolus.
            if (session.protocolVariant == ProtocolVariant.V2_OL1) {
                if (conn.mtu >= V2Codec.MIN_MTU) {
                    conn.subscribe(CharId.RADIO_RESPONSE_V2, config.gattOpTimeout)
                    session.v2Active = true
                } else {
                    session.v2FallbackReason = "mtu=${conn.mtu} < ${V2Codec.MIN_MTU}"
                }
            }

            // 4–6. Radio state is assumed lost (rule R1): encoding, preamble, frequency.
            session.bringUpStep(RfspyCommand.SetSwEncoding(config.encoding), config)
            session.bringUpStep(RfspyCommand.SetPreamble(config.preamble), config)
            val (f2, f1, f0) = FrequencyRegisters.forMhz(config.frequencyMHz)
            session.bringUpStep(RfspyCommand.UpdateRegister(RadioRegister.FREQ2, f2), config)
            session.bringUpStep(RfspyCommand.UpdateRegister(RadioRegister.FREQ1, f1), config)
            session.bringUpStep(RfspyCommand.UpdateRegister(RadioRegister.FREQ0, f0), config)

            // 7. Q-d: firmware statistics via the envelope — a per-session v2 smoke test
            // with a non-trivial payload. Observability only; a non-Ok result never gates
            // bring-up (raw hex, parsing deferred until something needs it).
            if (session.v2Active) {
                val stats = session.execute(RfspyCommand.GetStatistics, config.bringUpCmdDeadline)
                session.bringUpStatisticsHex =
                    (stats as? RfspyResult.Ok)?.payload?.joinToString("") { "%02x".format(it) }
                // Non-Ok never gates bring-up — but a result that CLOSED the session must:
                // returning a dead session would reset the supervisor's backoff (no-backoff
                // reconnect hammering) and swallow the close reason (onSessionLost is still
                // the default no-op until the supervisor wires it after open()).
                if (!session.isOpen) throw BringUpException("session closed during statistics probe: $stats")
            }

            return session
        }
    }

    private suspend fun bringUpStep(cmd: RfspyCommand, config: SessionConfig) {
        val result = execute(cmd, config.bringUpCmdDeadline)
        if (result !is RfspyResult.Ok) throw BringUpException("bring-up step $cmd failed: $result")
    }
}

/**
 * DIS build provenance (design §2 correction), exposed by firmware ≥ gd5fa522d and read by
 * the supervisor at L3 (like battery/RSSI — it is plain GATT, not rfspy protocol).
 * [softwareRevision] format: `boot:<n> rr:0x<resetreas>` — boot counter resets on power
 * loss; rr bits: 0x1 pin, 0x2 watchdog, 0x4 soft reset incl. DFU, 0x8 lockup.
 */
data class DeviceInfo(val firmwareRevision: String?, val softwareRevision: String?) {

    val bootCount: Int?
    val resetReason: Int?

    init {
        val match = softwareRevision?.let { SW_REGEX.find(it) }
        bootCount = match?.groupValues?.get(1)?.toIntOrNull()
        resetReason = match?.groupValues?.get(2)?.toIntOrNull(16)
    }

    /** The one canonical rendering — journal and logcat must stay grep-compatible. */
    override fun toString(): String {
        val sw = if (bootCount != null) "boot=$bootCount rr=0x${(resetReason ?: 0).toString(16)}"
        else "sw='${softwareRevision ?: "?"}'"
        return "fw='${firmwareRevision ?: "?"}' $sw"
    }

    companion object {

        private val SW_REGEX = Regex("""boot:(\d+) rr:0x([0-9a-fA-F]+)""")

        /** null when the device exposes neither string (vendor RileyLink, old firmware). */
        fun of(firmwareRevision: String?, softwareRevision: String?): DeviceInfo? =
            if (firmwareRevision == null && softwareRevision == null) null
            else DeviceInfo(firmwareRevision, softwareRevision)
    }
}

enum class ProtocolVariant { STOCK, V2_OL1 }

data class SessionConfig(
    val frequencyMHz: Double,
    val encoding: SwEncoding = SwEncoding.NONE,
    val preamble: Int = 0x0000,
    val gattOpTimeout: Duration = 2.seconds,
    val bringUpCmdDeadline: Duration = 3.seconds,
    /** v2: immediate-ack margin BEYOND the GATT write (phase-1 deadline is
     *  gattOpTimeout + this — the write itself may legitimately take gattOpTimeout). */
    val v2AcceptDeadline: Duration = 3.seconds,
)

class IncompatibleFirmwareException(val version: String) :
    Exception("unsupported rfspy firmware: $version")

class BringUpException(message: String) : Exception(message)
