package app.aaps.pump.rileylink2.minimed

import app.aaps.pump.rileylink2.rfspy.FrequencyRegisters
import app.aaps.pump.rileylink2.rfspy.RadioRegister
import app.aaps.pump.rileylink2.rfspy.RfspyCommand
import app.aaps.pump.rileylink2.rfspy.RfspyResult
import app.aaps.pump.rileylink2.rfspy.RfspySession
import app.aaps.pump.rileylink2.rfspy.SessionClosedException
import kotlin.time.Duration.Companion.milliseconds

/**
 * L2: Medtronic-specific radio procedures on one [RfspySession] (design doc §7).
 *
 * Pump message bytes in/out are exactly the legacy stack's: this layer only adds CRC-8 +
 * 4b6b framing on TX and strips/validates them on RX. Retry *budgets* for pump commands
 * stay in the (adapted) MedtronicCommunicationManager; this layer owns only the radio
 * procedures that were previously smeared across RFSpy/RileyLinkCommunicationManager:
 * wake, tune, and single send-and-listen exchanges.
 *
 * Fault classification (design §6.6): a garbled-but-present reply is REACHABILITY evidence
 * (wake/tune succeed on it); only command exchanges require a CRC-valid payload.
 */
class MinimedRadio(
    private val session: RfspySession,
    /** Observability hook (§11): every exchange outcome, for RSSI/health telemetry. Keep cheap. */
    private val onOutcome: (RadioOutcome) -> Unit = {},
) {

    sealed interface RadioOutcome {
        /** CRC-valid pump message. */
        data class Reply(val message: ByteArray, val rssiDbm: Int) : RadioOutcome {
            override fun equals(other: Any?): Boolean =
                other is Reply && other.rssiDbm == rssiDbm && other.message.contentEquals(message)

            override fun hashCode(): Int = 31 * rssiDbm + message.contentHashCode()
        }

        /** Pump keyed the carrier but the payload didn't decode/validate (F6). */
        data class Garbled(val rssiDbm: Int?) : RadioOutcome

        /** Firmware listened for the full window; nothing heard (F1). */
        data object PumpSilent : RadioOutcome

        /** Session/link-level problem: command fate unknown or session gone (F2/F3/F9). */
        data class LinkTrouble(val detail: String) : RadioOutcome
    }

    sealed interface TuneOutcome {
        data class Tuned(val frequencyMHz: Double, val averageRssiDbm: Double) : TuneOutcome
        data object PumpSilent : TuneOutcome
        data class LinkTrouble(val detail: String) : TuneOutcome
    }

    /**
     * One send-and-listen exchange. [message] is a raw pump message (unencoded, no CRC).
     */
    suspend fun sendAndListen(
        message: ByteArray,
        repeatCount: Int = 0,
        delayBetweenPacketsMs: Int = 0,
        listenTimeoutMs: Int = DEFAULT_LISTEN_MS,
        retryCount: Int = 0,
    ): RadioOutcome {
        val cmd = RfspyCommand.SendAndListen(
            sendChannel = 0,
            repeatCount = repeatCount.toByte(),
            delayBetweenPacketsMs = delayBetweenPacketsMs,
            listenChannel = 0,
            timeoutMs = listenTimeoutMs,
            retryCount = retryCount.toByte(),
            preambleExtensionMs = 0,
            packet = Minimed4b6b.encodeForTx(message),
        )
        val result = try {
            session.execute(cmd, (cmd.expectedDurationMs + BLE_LATENCY_MARGIN_MS).milliseconds)
        } catch (e: SessionClosedException) {
            return RadioOutcome.LinkTrouble("session closed: ${e.message}").also(onOutcome)
        }
        val outcome = when (result) {
            is RfspyResult.Ok            -> classifyRadioPayload(result.payload)
            is RfspyResult.RadioTimeout  -> RadioOutcome.PumpSilent
            is RfspyResult.Interrupted   -> RadioOutcome.LinkTrouble("interrupted (epoch=${result.epoch})")
            is RfspyResult.Uncertain     -> RadioOutcome.LinkTrouble("uncertain: ${result.cause}")
            is RfspyResult.FirmwareError -> RadioOutcome.LinkTrouble("firmware error 0x${result.code.toString(16)}")
            // v2 immediate NACK: definitively not executed (host/config bug — plan D4);
            // the I9 tightening to a safe retry is a deliberate later change.
            is RfspyResult.Rejected      -> RadioOutcome.LinkTrouble("v2 rejected 0x${result.code.toString(16)}")
        }
        onOutcome(outcome)
        return outcome
    }

    /** Payload after the 0xDD status byte: `[rssi][responseCount][encoded pump reply…]`. */
    private fun classifyRadioPayload(payload: ByteArray): RadioOutcome {
        if (payload.size < 3) return RadioOutcome.Garbled(rssiDbm = payload.firstOrNull()?.let { rssiToDbm(it) })
        val rssiDbm = rssiToDbm(payload[0])
        return when (val rx = Minimed4b6b.decodeFromRx(payload.copyOfRange(2, payload.size))) {
            is Minimed4b6b.RxResult.Message -> RadioOutcome.Reply(rx.bytes, rssiDbm)
            is Minimed4b6b.RxResult.Garbled -> RadioOutcome.Garbled(rssiDbm)
        }
    }

    /**
     * Wake the pump: bounded bursts instead of the legacy single 25 s listen (design §6.5 —
     * each attempt is a cancellation point and keeps the firmware's radio-busy window short).
     * Any reply, valid or garbled, proves the pump is awake and in range.
     */
    suspend fun wake(wakeMessage: ByteArray, attempts: Int = WAKE_ATTEMPTS): RadioOutcome {
        var last: RadioOutcome = RadioOutcome.PumpSilent
        repeat(attempts) {
            when (val outcome = sendAndListen(
                message = wakeMessage,
                repeatCount = WAKE_REPEAT_COUNT,
                listenTimeoutMs = WAKE_LISTEN_MS,
            )) {
                is RadioOutcome.Reply, is RadioOutcome.Garbled -> return outcome
                is RadioOutcome.LinkTrouble                    -> return outcome
                is RadioOutcome.PumpSilent                     -> last = outcome
            }
        }
        return last
    }

    suspend fun setFrequency(frequencyMHz: Double): Boolean {
        val (f2, f1, f0) = FrequencyRegisters.forMhz(frequencyMHz)
        for ((register, value) in listOf(RadioRegister.FREQ2 to f2, RadioRegister.FREQ1 to f1, RadioRegister.FREQ0 to f0)) {
            val result = try {
                session.execute(RfspyCommand.UpdateRegister(register, value), SET_FREQ_DEADLINE_MS.milliseconds)
            } catch (e: SessionClosedException) {
                return false
            }
            if (result !is RfspyResult.Ok) return false
        }
        return true
    }

    /**
     * Find the pump's frequency. Quick path first: if [currentFrequencyMHz] still answers,
     * keep it without a full scan. Full scan mirrors the legacy RSSI-ranked algorithm
     * (3 trials per frequency, −99 dBm penalty per miss), but aborts after
     * [MAX_CONSECUTIVE_LINK_FAILURES] session-level failures — those are link problems that
     * more frequencies cannot fix (the 2026-07-02 zombie-tune lesson).
     */
    suspend fun tune(
        probeMessage: ByteArray,
        frequenciesMHz: List<Double>,
        currentFrequencyMHz: Double? = null,
    ): TuneOutcome {
        if (currentFrequencyMHz != null) {
            if (!setFrequency(currentFrequencyMHz)) return TuneOutcome.LinkTrouble("setFrequency failed")
            val trial = runTrials(probeMessage)
            when (trial) {
                is Trial.LinkTrouble -> return TuneOutcome.LinkTrouble(trial.detail)
                is Trial.Scored      -> if (trial.successes > 0) return TuneOutcome.Tuned(currentFrequencyMHz, trial.averageRssi)
            }
        }

        var consecutiveLinkFailures = 0
        val scored = ArrayList<Pair<Double, Trial.Scored>>(frequenciesMHz.size)
        for (freq in frequenciesMHz) {
            if (!setFrequency(freq)) return TuneOutcome.LinkTrouble("setFrequency($freq) failed")
            when (val trial = runTrials(probeMessage)) {
                is Trial.LinkTrouble -> {
                    consecutiveLinkFailures++
                    if (consecutiveLinkFailures >= MAX_CONSECUTIVE_LINK_FAILURES)
                        return TuneOutcome.LinkTrouble("aborting scan: ${trial.detail}")
                }

                is Trial.Scored      -> {
                    consecutiveLinkFailures = 0
                    scored += freq to trial
                }
            }
        }

        val best = scored.filter { it.second.successes > 0 }.maxByOrNull { it.second.averageRssi }
            ?: return TuneOutcome.PumpSilent
        if (!setFrequency(best.first)) return TuneOutcome.LinkTrouble("setFrequency(best) failed")
        return TuneOutcome.Tuned(best.first, best.second.averageRssi)
    }

    private sealed interface Trial {
        data class Scored(val successes: Int, val averageRssi: Double) : Trial
        data class LinkTrouble(val detail: String) : Trial
    }

    private suspend fun runTrials(probeMessage: ByteArray): Trial {
        var successes = 0
        var rssiSum = 0
        repeat(TUNE_TRIALS) {
            when (val outcome = sendAndListen(probeMessage, listenTimeoutMs = TUNE_LISTEN_MS)) {
                is RadioOutcome.Reply       -> { successes++; rssiSum += outcome.rssiDbm }
                is RadioOutcome.Garbled     -> { successes++; rssiSum += outcome.rssiDbm ?: MISS_RSSI }
                is RadioOutcome.PumpSilent  -> rssiSum += MISS_RSSI
                is RadioOutcome.LinkTrouble -> return Trial.LinkTrouble(outcome.detail)
            }
        }
        return Trial.Scored(successes, rssiSum.toDouble() / TUNE_TRIALS)
    }

    companion object {

        /** CC1110 RSSI byte → dBm (same conversion as the legacy scan path). */
        fun rssiToDbm(raw: Byte): Int {
            val u = raw.toInt() and 0xFF
            return (if (u >= 128) (u - 256) / 2 else u / 2) - RSSI_OFFSET
        }

        private const val RSSI_OFFSET = 73
        private const val MISS_RSSI = -99

        /** Legacy-proven BLE round-trip margin on top of the firmware-side op duration. */
        const val BLE_LATENCY_MARGIN_MS = 7500L
        const val DEFAULT_LISTEN_MS = 2000
        const val SET_FREQ_DEADLINE_MS = 3000L

        /** §6.5: 5 × (200-repeat burst + 4.5 s listen) ≈ legacy 25 s listen budget, but bounded. */
        const val WAKE_ATTEMPTS = 5
        const val WAKE_REPEAT_COUNT = 200
        const val WAKE_LISTEN_MS = 4500

        const val TUNE_TRIALS = 3
        const val TUNE_LISTEN_MS = 1250
        const val MAX_CONSECUTIVE_LINK_FAILURES = 3

        /** Scan tables from the legacy RileyLinkTargetFrequency. */
        val US_FREQUENCIES = listOf(916.45, 916.50, 916.55, 916.60, 916.65, 916.70, 916.75, 916.80)
        val WW_FREQUENCIES = listOf(868.25, 868.30, 868.35, 868.40, 868.45, 868.50, 868.55, 868.60, 868.65)
    }
}
