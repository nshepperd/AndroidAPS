package app.aaps.pump.rileylink2.link

import app.aaps.pump.rileylink2.ble.BleConnection
import app.aaps.pump.rileylink2.ble.BleLink
import app.aaps.pump.rileylink2.ble.CharId
import app.aaps.pump.rileylink2.minimed.MinimedRadio
import app.aaps.pump.rileylink2.rfspy.IncompatibleFirmwareException
import app.aaps.pump.rileylink2.rfspy.RfspySession
import app.aaps.pump.rileylink2.rfspy.SessionConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * L3: the single owner of the pump-link lifecycle (design doc §8).
 *
 * Structure: one sequential main-loop coroutine — the actor. All lifecycle state lives in
 * local variables of that loop; the outside world interacts only through channels
 * (commands, control signals) and reads [state]/[journalSnapshot]. This makes the
 * no-dead-end property structural: the loop physically cannot park anywhere except the two
 * designed terminal states, because every wait is on (session-lost ∨ demand ∨ timer).
 *
 * Recovery ladder (§8.3): command retries live in L2/Medtronic budgets; session re-init and
 * BLE reconnect-with-backoff live here; tune retries are demand-driven and rate-limited by
 * [PumpLinkConfig.tuneMinInterval].
 */
class PumpLinkSupervisor(
    private val bleLink: BleLink,
    private val config: PumpLinkConfig,
    private val scope: CoroutineScope,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {

    private val _state = MutableStateFlow<PumpLinkState>(PumpLinkState.Stopped)
    val state: StateFlow<PumpLinkState> = _state.asStateFlow()

    /** Observability hook (§11); called synchronously on the actor, keep it cheap. */
    var onTransition: (LinkTransition) -> Unit = {}

    /** Observability hook (§11): every pump radio exchange outcome (RSSI telemetry). */
    var onRadioOutcome: (MinimedRadio.RadioOutcome) -> Unit = {}

    /** Observability hook (§11): periodic phone↔RileyLink BLE RSSI in dBm, while linked.
     *  Enabled by [PumpLinkConfig.bleRssiPollInterval] > 0. */
    var onBleRssi: (Int) -> Unit = {}

    /** RileyLink battery percent, polled while linked (first read at session bring-up).
     *  Enabled by [PumpLinkConfig.batteryPollInterval] > 0. */
    var onBatteryLevel: (Int) -> Unit = {}

    private val journal = ArrayDeque<LinkTransition>()
    private val startMark = timeSource.markNow()

    private val highCommands = Channel<CommandRequest<*>>(Channel.UNLIMITED)
    private val normalCommands = Channel<CommandRequest<*>>(Channel.UNLIMITED)
    private val controls = Channel<Control>(Channel.UNLIMITED)
    private val demandSignals = Channel<Unit>(Channel.CONFLATED)
    private val demandCount = AtomicInteger(0)

    private var epoch = 0
    private var lastTuneMark: TimeMark? = null
    private var job: Job? = null

    /** One-shot demand from requestTune/reportPumpUnreachable — acts even with no lease held. */
    private val userAction = java.util.concurrent.atomic.AtomicBoolean(false)

    private enum class Control { TUNE_REQUESTED, PUMP_UNREACHABLE_REPORTED }

    fun start() {
        check(job?.isActive != true) { "supervisor already running" }
        job = scope.launch {
            try {
                mainLoop()
            } finally {
                setState(PumpLinkState.Stopped, "stopped")
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    /** Signal that pump communication is wanted (adapter maps Pump.connect() here). */
    fun demand() {
        demandCount.incrementAndGet()
        demandSignals.trySend(Unit)
    }

    fun releaseDemand() {
        demandCount.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    /** User-initiated tune ("Wake and Tune Up"): bypasses the tune cooldown. */
    fun requestTune() {
        lastTuneMark = null
        userAction.set(true)
        controls.trySend(Control.TUNE_REQUESTED)
        demandSignals.trySend(Unit)
    }

    /**
     * The Medtronic driver's isDeviceReachable-equivalent escalation: repeated pump-level
     * command failures on a Ready link → drop to PumpUnreachable and re-tune (§8.2).
     */
    fun reportPumpUnreachable() {
        userAction.set(true)
        controls.trySend(Control.PUMP_UNREACHABLE_REPORTED)
        demandSignals.trySend(Unit)
    }

    fun journalSnapshot(): List<LinkTransition> = synchronized(journal) { journal.toList() }

    /**
     * Execute one pump command block when the link is Ready (design §9.2). Single-flight by
     * construction: blocks run inline on the actor. Do NOT call withPump from inside a
     * block — it would deadlock the actor.
     *
     * The command implicitly carries demand, so a queued command is what drives recovery
     * retries while the pump is unreachable.
     *
     * [readyTimeout] bounds only the WAIT for the link to become Ready and pick this command
     * up. Once the block has started it always runs to its real outcome (bounded by
     * [PumpLinkConfig.maxCommandDuration]) — abandoning an in-flight block would discard the
     * result of work that may already have reached the pump (2026-07-03 soak bug B2).
     */
    suspend fun <T> withPump(
        description: String,
        priority: PumpCmdPriority = PumpCmdPriority.NORMAL,
        readyTimeout: Duration = 90.seconds,
        block: suspend (MinimedRadio) -> T,
    ): PumpCmdOutcome<T> {
        val request = CommandRequest(description, block)
        demand()
        try {
            (if (priority == PumpCmdPriority.HIGH) highCommands else normalCommands).send(request)
            withTimeoutOrNull(readyTimeout) { request.deferred.await() }
                ?.let { return it }
            // Timed out. If the actor already started the block, the wait is over but the
            // work is real — see it through. Otherwise abandon before it can start.
            return if (request.tryAbandon()) PumpCmdOutcome.Unavailable(state.value)
            else request.deferred.await()
        } finally {
            releaseDemand()
        }
    }

    // ---------------------------------------------------------------- actor ----

    private suspend fun mainLoop() {
        if (config.deviceAddress.isBlank()) {
            setState(PumpLinkState.NotConfigured("blank device address"), "configuration")
            awaitCancellation()
        }
        var connectAttempt = 0
        while (true) {
            setState(PumpLinkState.BleConnecting(connectAttempt), if (connectAttempt == 0) "connect" else "reconnect")
            val conn = try {
                bleLink.connect(config.deviceAddress, config.connectTimeout)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                connectAttempt++
                setState(PumpLinkState.LinkDown("connect failed: ${e.message}"), "connect")
                delay(config.backoffJitter(backoff(connectAttempt)))
                continue
            }
            var rssiPoller: Job? = null
            var batteryPoller: Job? = null
            try {
                setState(PumpLinkState.SessionInit, "ble operational")
                epoch++
                val session = try {
                    RfspySession.open(conn, sessionConfig(), epoch, scope)
                } catch (e: IncompatibleFirmwareException) {
                    setState(PumpLinkState.IncompatibleFirmware(e.version), "version probe")
                    awaitCancellation()          // designed terminal state: only the user can fix this
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    connectAttempt++
                    setState(PumpLinkState.LinkDown("bring-up failed: ${e.message}"), "bring-up")
                    delay(config.backoffJitter(backoff(connectAttempt)))
                    continue
                }
                connectAttempt = 0
                rssiPoller = launchBleRssiPoller(conn)
                batteryPoller = launchBatteryPoller(conn)
                val lost = CompletableDeferred<String>()
                session.onSessionLost = { reason -> lost.complete(reason) }
                val reason = serveSession(session, MinimedRadio(session, onRadioOutcome), lost)
                setState(PumpLinkState.LinkDown(reason), "session lost")
            } finally {
                rssiPoller?.cancel()
                batteryPoller?.cancel()
                withContext(NonCancellable) { runCatching { conn.close() } }
            }
        }
    }

    /** Serve one session until it is lost; returns the loss reason. */
    private suspend fun serveSession(
        session: RfspySession,
        radio: MinimedRadio,
        lost: CompletableDeferred<String>,
    ): String {
        var needTune = config.prefs.lastGoodFrequencyMHz == null
        while (true) {
            if (lost.isCompleted) return lost.await()
            if (!session.isOpen) return "session closed"

            val knownFrequency = config.prefs.lastGoodFrequencyMHz
            if (needTune || knownFrequency == null) {
                val cooldown = tuneCooldownRemaining()
                if (cooldown > Duration.ZERO) {
                    setState(PumpLinkState.PumpUnreachable, "tune cooldown ${cooldown.inWholeSeconds}s")
                    if (waitInUnreachable(lost)) return lost.await()
                    continue
                }
                setState(PumpLinkState.Tuning, "tune needed")
                lastTuneMark = timeSource.markNow()
                drainControls()          // this tune satisfies any queued tune/unreachable signal
                when (val tune = radio.tune(config.probeMessage, config.scanFrequenciesMHz, knownFrequency)) {
                    is MinimedRadio.TuneOutcome.Tuned       -> {
                        config.prefs.lastGoodFrequencyMHz = tune.frequencyMHz
                        needTune = false
                    }

                    is MinimedRadio.TuneOutcome.PumpSilent  -> {
                        setState(PumpLinkState.PumpUnreachable, "tune: pump silent")
                        if (waitInUnreachable(lost)) return lost.await()
                    }

                    is MinimedRadio.TuneOutcome.LinkTrouble -> {
                        if (!session.isOpen) return "link trouble during tune: ${tune.detail}"
                        setState(PumpLinkState.PumpUnreachable, "tune: ${tune.detail}")
                        if (waitInUnreachable(lost)) return lost.await()
                    }
                }
            } else {
                setState(PumpLinkState.Ready(knownFrequency), "frequency known")
                when (serveReady(radio, lost)) {
                    ReadyExit.SESSION_LOST -> return lost.await()
                    ReadyExit.TUNE_WANTED,
                    ReadyExit.PUMP_UNREACHABLE -> needTune = true
                }
            }
        }
    }

    private enum class ReadyExit { SESSION_LOST, TUNE_WANTED, PUMP_UNREACHABLE }

    private suspend fun serveReady(radio: MinimedRadio, lost: CompletableDeferred<String>): ReadyExit {
        while (true) {
            // select is biased to earlier clauses: loss beats work, HIGH beats NORMAL.
            val exit = select<ReadyExit?> {
                lost.onAwait { ReadyExit.SESSION_LOST }
                highCommands.onReceive { request -> runCommand(request, radio); null }
                normalCommands.onReceive { request -> runCommand(request, radio); null }
                controls.onReceive { control ->
                    when (control) {
                        Control.TUNE_REQUESTED             -> ReadyExit.TUNE_WANTED
                        Control.PUMP_UNREACHABLE_REPORTED  -> ReadyExit.PUMP_UNREACHABLE
                    }
                }
            }
            if (exit != null) return exit
        }
    }

    /**
     * Park in PumpUnreachable until there is a reason to act. Every exit is one of:
     * session lost (returns true), or retry conditions met (returns false). This wait is
     * the structural guarantee that PumpUnreachable is never a dead end.
     */
    private suspend fun waitInUnreachable(lost: CompletableDeferred<String>): Boolean {
        while (true) {
            if (lost.isCompleted) return true
            if (userAction.compareAndSet(true, false)) return false   // act now; cooldown was reset by requestTune
            val wait = if (demandCount.get() > 0) {
                val cooldown = tuneCooldownRemaining()
                if (cooldown <= Duration.ZERO) return false           // demand exists, rate limit clear → retry
                cooldown
            } else {
                // No demand: radio retries are demand-gated (§8.4); the periodic timeout
                // just re-evaluates (cheap, and keeps the loop honest).
                config.unreachableRetryInterval
            }
            val lostWhileWaiting = withTimeoutOrNull(wait) {
                select<Boolean> {
                    lost.onAwait { true }
                    demandSignals.onReceive { false }
                }
            }
            if (lostWhileWaiting == true) return true
            // demand arrived, user action, or timer fired → loop re-evaluates everything
        }
    }

    private suspend fun runCommand(request: CommandRequest<*>, radio: MinimedRadio) {
        @Suppress("UNCHECKED_CAST")
        val cmd = request as CommandRequest<Any?>
        if (!cmd.tryStart()) return   // abandoned while queued
        val outcome: PumpCmdOutcome<Any?> = try {
            withTimeout(config.maxCommandDuration) { PumpCmdOutcome.Success(cmd.block(radio)) }
        } catch (e: TimeoutCancellationException) {
            PumpCmdOutcome.Failed(e)
        } catch (e: CancellationException) {
            cmd.deferred.complete(PumpCmdOutcome.Failed(e))
            throw e
        } catch (e: Exception) {
            PumpCmdOutcome.Failed(e)
        }
        cmd.deferred.complete(outcome)
    }

    /**
     * Periodic BLE RSSI sampling on the live connection. Shares the L0 one-op-at-a-time
     * discipline, so a sample simply queues behind any in-flight radio op. Failures end the
     * poller quietly — the link teardown path already reports the loss.
     */
    private fun launchBleRssiPoller(conn: BleConnection): Job? {
        if (config.bleRssiPollInterval <= Duration.ZERO) return null
        return scope.launch {
            while (true) {
                delay(config.bleRssiPollInterval)
                val rssi = try {
                    conn.readRemoteRssi(BLE_RSSI_READ_TIMEOUT)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@launch
                }
                onBleRssi(rssi)
            }
        }
    }

    /**
     * Battery poll on the live connection: read at bring-up, then legacy RFSpy cadence
     * (interval, accelerated below 20%). Same failure posture as the RSSI poller.
     */
    private fun launchBatteryPoller(conn: BleConnection): Job? {
        if (config.batteryPollInterval <= Duration.ZERO) return null
        return scope.launch {
            while (true) {
                val level = try {
                    conn.read(CharId.BATTERY_LEVEL, BLE_RSSI_READ_TIMEOUT).firstOrNull()?.toInt()?.and(0xFF)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@launch
                }
                if (level != null) onBatteryLevel(level)
                delay(
                    if (level != null && level <= LOW_BATTERY_PERCENT) config.batteryPollLowInterval
                    else config.batteryPollInterval
                )
            }
        }
    }

    private fun sessionConfig() = SessionConfig(
        frequencyMHz = config.prefs.lastGoodFrequencyMHz ?: config.scanFrequenciesMHz.first(),
    )

    private fun drainControls() {
        while (controls.tryReceive().isSuccess) { /* consumed by the tune we are about to run */ }
    }

    private fun tuneCooldownRemaining(): Duration =
        lastTuneMark?.let { (config.tuneMinInterval - it.elapsedNow()).coerceAtLeast(Duration.ZERO) }
            ?: Duration.ZERO

    private fun backoff(attempt: Int): Duration =
        (config.backoffFloor * (1 shl (attempt - 1).coerceAtMost(10)))
            .coerceAtMost(config.backoffCeiling)

    private fun setState(new: PumpLinkState, cause: String) {
        val old = _state.value
        if (old == new) return
        _state.value = new
        val transition = LinkTransition(startMark.elapsedNow(), old, new, cause)
        synchronized(journal) {
            journal.addLast(transition)
            while (journal.size > config.journalCapacity) journal.removeFirst()
        }
        onTransition(transition)
    }

    private companion object {
        val BLE_RSSI_READ_TIMEOUT = 5.seconds
        const val LOW_BATTERY_PERCENT = 20
    }

    private class CommandRequest<T>(
        val description: String,
        val block: suspend (MinimedRadio) -> T,
    ) {
        val deferred = CompletableDeferred<PumpCmdOutcome<T>>()

        /** PENDING → STARTED (actor wins) xor PENDING → ABANDONED (caller wins) — atomic,
         *  so a block can never start after its caller has been told Unavailable. */
        private val lifecycle = AtomicInteger(PENDING)
        fun tryStart() = lifecycle.compareAndSet(PENDING, STARTED)
        fun tryAbandon() = lifecycle.compareAndSet(PENDING, ABANDONED)

        private companion object {
            const val PENDING = 0
            const val STARTED = 1
            const val ABANDONED = 2
        }
    }
}
