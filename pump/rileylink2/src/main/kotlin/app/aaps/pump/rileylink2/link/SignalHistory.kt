package app.aaps.pump.rileylink2.link

import app.aaps.pump.rileylink2.minimed.MinimedRadio

/**
 * In-memory signal-quality history for the live chart (design §11 — journal's quantitative
 * sibling). Two series plus link-state marks:
 *
 * - BLE RSSI: phone ↔ RileyLink link strength, sampled periodically while connected.
 * - Pump RSSI: pump ↔ RileyLink radio strength, one sample per exchange. Pump-silent
 *   exchanges are kept as distinct [PumpSampleKind.SILENT] points (no dBm) so the chart can
 *   show "listened, heard nothing" — that is signal information, not a gap.
 *
 * Service-lifetime, bounded, thread-safe. Not persisted: this is a diagnostic instrument,
 * not medical history.
 */
class SignalHistory(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val capacityPerSeries: Int = 4096,
) {

    enum class PumpSampleKind { REPLY, GARBLED, SILENT }

    data class BleSample(val timeMs: Long, val dbm: Int)
    data class PumpSample(val timeMs: Long, val dbm: Int?, val kind: PumpSampleKind)
    data class StateMark(val timeMs: Long, val state: String, val cause: String)

    private val bleSamples = ArrayDeque<BleSample>()
    private val pumpSamples = ArrayDeque<PumpSample>()
    private val stateMarks = ArrayDeque<StateMark>()

    fun recordBleRssi(dbm: Int) = synchronized(this) {
        append(bleSamples, BleSample(nowMs(), dbm))
    }

    fun recordRadioOutcome(outcome: MinimedRadio.RadioOutcome): Unit = synchronized(this) {
        val sample = when (outcome) {
            is MinimedRadio.RadioOutcome.Reply       -> PumpSample(nowMs(), outcome.rssiDbm, PumpSampleKind.REPLY)
            is MinimedRadio.RadioOutcome.Garbled     -> PumpSample(nowMs(), outcome.rssiDbm, PumpSampleKind.GARBLED)
            is MinimedRadio.RadioOutcome.PumpSilent  -> PumpSample(nowMs(), null, PumpSampleKind.SILENT)
            is MinimedRadio.RadioOutcome.LinkTrouble -> return // a link event, not a radio measurement
        }
        append(pumpSamples, sample)
    }

    fun recordTransition(transition: LinkTransition) = synchronized(this) {
        append(stateMarks, StateMark(nowMs(), transition.to.toString(), transition.cause))
    }

    fun bleSnapshot(): List<BleSample> = synchronized(this) { bleSamples.toList() }
    fun pumpSnapshot(): List<PumpSample> = synchronized(this) { pumpSamples.toList() }
    fun stateSnapshot(): List<StateMark> = synchronized(this) { stateMarks.toList() }

    private fun <T> append(deque: ArrayDeque<T>, item: T) {
        deque.addLast(item)
        while (deque.size > capacityPerSeries) deque.removeFirst()
    }
}
