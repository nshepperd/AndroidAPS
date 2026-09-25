package app.aaps.pump.rileylink2.rfspy

/**
 * Typed outcome of one rfspy command (design doc §6.1). There is deliberately no
 * "LinkLost" member: link loss cancels the session scope and surfaces as
 * [kotlinx.coroutines.CancellationException] / [SessionClosedException].
 */
sealed interface RfspyResult {

    /** 0xDD (or a bare radio packet on listen commands). [payload] excludes the status byte. */
    data class Ok(val payload: ByteArray, val epoch: Int) : RfspyResult {

        override fun equals(other: Any?): Boolean =
            other is Ok && other.epoch == epoch && other.payload.contentEquals(payload)

        override fun hashCode(): Int = 31 * epoch + payload.contentHashCode()
    }

    /** 0xAA — firmware listened, pump silent. The link itself is healthy. */
    data class RadioTimeout(val epoch: Int) : RfspyResult

    /**
     * 0xBB — the radio operation was cut short (BLE event firmware-side), OR the session
     * detected response-count desync. The session is closed after emitting this.
     */
    data class Interrupted(val epoch: Int) : RfspyResult

    /** 0x11 invalid param / 0x22 unknown command — host-side programming error. */
    data class FirmwareError(val code: Int, val epoch: Int) : RfspyResult

    /**
     * Command may or may not have been executed: the write was accepted (or its fate is
     * unknown) and no response arrived within the deadline. For [RfspyCommand.mutating]
     * commands the caller MUST treat pump state as unknown (design invariant I9).
     */
    data class Uncertain(val cause: UncertainCause, val epoch: Int) : RfspyResult

    /**
     * v2 immediate NACK ([V2Codec.ACK_BUSY]/[V2Codec.ACK_MTU_TOO_SMALL]/
     * [V2Codec.ACK_MALFORMED]): the command was DEFINITIVELY NOT executed (discarded before
     * execution, no completion follows) — safe to retry even for mutating commands. With
     * the session's single-flight discipline every one of these is a host or config bug;
     * first cut maps them to LinkTrouble at L2 (plan D4), the I9 tightening comes later.
     */
    data class Rejected(val code: Int, val epoch: Int) : RfspyResult
}

enum class UncertainCause {
    /** No response-count notification within the deadline (silent drop, or response lost). */
    NO_RESPONSE_NOTIFICATION,
    /** Notification arrived but the data read-back failed and the link then closed. */
    READBACK_FAILED,
}

/** Raw response status bytes (firmware `app_aps.c`). */
object RfspyStatus {
    const val OK = 0xDD
    const val OK_LEGACY = 0x01
    const val RX_TIMEOUT = 0xAA
    const val INTERRUPTED = 0xBB
    const val ZERO_DATA = 0xCC
    const val PARAM_ERROR = 0x11
    const val UNKNOWN_COMMAND = 0x22
}

/** Thrown by [RfspySession.execute] when the session is already closed. */
class SessionClosedException(message: String) : Exception(message)
