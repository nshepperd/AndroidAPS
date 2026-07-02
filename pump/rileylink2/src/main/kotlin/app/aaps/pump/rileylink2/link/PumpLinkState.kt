package app.aaps.pump.rileylink2.link

import kotlin.time.Duration

/**
 * L3 states (design doc §8.1). No state is terminal except [IncompatibleFirmware] and
 * [NotConfigured] — the no-dead-ends property is enforced by the supervisor's main loop
 * and verified by scenario tests.
 */
sealed interface PumpLinkState {

    /** No / invalid configuration; exits only via reconfiguration + restart. */
    data class NotConfigured(val why: String) : PumpLinkState

    data class BleConnecting(val attempt: Int) : PumpLinkState

    /** Rfspy bring-up (rule R1: full radio re-init, every time). */
    data object SessionInit : PumpLinkState

    data object Tuning : PumpLinkState

    /** Pump commands may execute. */
    data class Ready(val frequencyMHz: Double) : PumpLinkState

    /** BLE + session healthy, pump silent (F1). Retries on demand/timer, forever. */
    data object PumpUnreachable : PumpLinkState

    /** BLE lost (F2/F3/F7); reconnect loop with backoff is active. */
    data class LinkDown(val cause: String) : PumpLinkState

    /** Version probe failed — only the user can fix this (wrong/old device). */
    data class IncompatibleFirmware(val version: String) : PumpLinkState

    data object Stopped : PumpLinkState

    /** "Genuinely in progress" — the adapter's Pump.isConnecting() (design §9.1). */
    fun isConnecting(): Boolean = this is BleConnecting || this is SessionInit || this is Tuning
}

/** One entry of the transition journal (design §11). */
data class LinkTransition(
    val atElapsed: Duration,
    val from: PumpLinkState,
    val to: PumpLinkState,
    val cause: String,
)

enum class PumpCmdPriority { HIGH, NORMAL }

/** Outcome of [PumpLinkSupervisor.withPump] (design §9.2). */
sealed interface PumpCmdOutcome<out T> {

    data class Success<T>(val value: T) : PumpCmdOutcome<T>

    /** Link did not reach [PumpLinkState.Ready] within the caller's timeout. */
    data class Unavailable(val stateAtRejection: PumpLinkState) : PumpCmdOutcome<Nothing>

    /** The command block threw (including session loss mid-command). */
    data class Failed(val error: Throwable) : PumpCmdOutcome<Nothing>
}
