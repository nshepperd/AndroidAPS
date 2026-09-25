package app.aaps.pump.rileylink2.link

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Durable state owned by the integration layer (AAPS preferences in production). */
interface PumpLinkPrefs {
    var lastGoodFrequencyMHz: Double?
}

class InMemoryPumpLinkPrefs(override var lastGoodFrequencyMHz: Double? = null) : PumpLinkPrefs

data class PumpLinkConfig(
    val deviceAddress: String,
    /** Short GetModel frame for this pump serial — the wake/tune probe. */
    val probeMessage: ByteArray,
    val scanFrequenciesMHz: List<Double>,
    val prefs: PumpLinkPrefs,

    val connectTimeout: Duration = 30.seconds,
    /** From this connect attempt on, sight the device in a filtered scan before the directed
     *  connect — a cold LE cache (phone reboot) makes directed connects to a random static
     *  address fail silently forever; a scan sighting repopulates the cache (2026-07-10). */
    val scanBeforeConnectAfter: Int = 2,
    /** How long one sighting scan waits for an advertisement before counting as a failure. */
    val scanSightingTimeout: Duration = 25.seconds,
    /** Reconnect backoff (design §5): 0.5 s → 30 s. Jitter injected, deterministic default. */
    val backoffFloor: Duration = 500.milliseconds,
    val backoffCeiling: Duration = 30.seconds,
    val backoffJitter: (Duration) -> Duration = { it },
    /** Minimum spacing between tune attempts (§8.3; matches the proven tactical fix). */
    val tuneMinInterval: Duration = 3.minutes,
    /** PumpUnreachable retry cadence when demand exists (§8.3). */
    val unreachableRetryInterval: Duration = 5.minutes,
    /** Hard ceiling on one command block; MinimedRadio ops are internally bounded anyway. */
    val maxCommandDuration: Duration = 5.minutes,
    /** Journal ring-buffer size (§11). */
    val journalCapacity: Int = 500,
    /** Phone↔RileyLink BLE RSSI sampling cadence; ZERO disables (default — tests, and
     *  callers that don't consume [PumpLinkSupervisor.onBleRssi]). */
    val bleRssiPollInterval: Duration = Duration.ZERO,
    /** RileyLink battery-level poll cadence (standard BLE Battery Service); ZERO disables.
     *  First read happens immediately at session bring-up. */
    val batteryPollInterval: Duration = Duration.ZERO,
    /** Accelerated cadence once the reported level is ≤ 20% (legacy RFSpy parity). */
    val batteryPollLowInterval: Duration = batteryPollInterval,
) {

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}
