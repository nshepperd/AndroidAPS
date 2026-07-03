package app.aaps.pump.rileylink2.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.time.Duration

/**
 * L0: exclusive owner of the physical BLE link to a RileyLink-compatible device.
 *
 * Implementations: AndroidBleLink (real GATT, production) and FakeOrangeLink (JVM tests).
 *
 * Contract (design doc §5):
 * - [connect] performs the ATOMIC bring-up: GATT connect, service discovery, service-UUID
 *   verification, MTU request, response-count notification subscription. A [BleConnection]
 *   that has not completed bring-up never escapes this layer.
 * - All operations carry explicit deadlines; a deadline expiry closes the connection.
 * - Exactly one GATT operation is outstanding at any time (implementation-enforced).
 */
interface BleLink {

    /** Scan for advertising devices, optionally filtered to one MAC address. */
    fun scan(filterAddress: String?, timeout: Duration): Flow<ScanHit>

    /**
     * Connect and bring up an operational link. Throws [BleException] on failure;
     * honors coroutine cancellation at every internal step.
     */
    suspend fun connect(address: String, timeout: Duration): BleConnection
}

data class ScanHit(val address: String, val name: String?, val rssi: Int)

/**
 * An operational (fully brought-up) BLE connection. Valid until [events] emits
 * [BleEvent.Closed]; all operations after that throw [BleException.LinkClosed].
 */
interface BleConnection {

    /** Hot stream of link events. Replays nothing; subscribe before first use. */
    val events: SharedFlow<BleEvent>

    suspend fun write(char: CharId, bytes: ByteArray, timeout: Duration)

    suspend fun read(char: CharId, timeout: Duration): ByteArray

    suspend fun subscribe(char: CharId, timeout: Duration)

    /** Signal strength of the BLE link itself (phone ↔ RileyLink), in dBm. */
    suspend fun readRemoteRssi(timeout: Duration): Int

    /** Idempotent. Emits [BleEvent.Closed] with [CloseCause.LocalClose] if still open. */
    suspend fun close()
}

/** Characteristics the stack uses, abstracted from raw UUIDs (mapping in [GattUuids]). */
enum class CharId {
    RADIO_DATA,
    RADIO_RESPONSE_COUNT,
    RADIO_VERSION,
    BATTERY_LEVEL,
    ORANGE_NOTIFICATION,
}

sealed interface BleEvent {

    /** A notification arrived on a subscribed characteristic. */
    data class NotificationReceived(val char: CharId, val bytes: ByteArray) : BleEvent {

        override fun equals(other: Any?): Boolean =
            other is NotificationReceived && other.char == char && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = 31 * char.hashCode() + bytes.contentHashCode()
    }

    /** The link is gone. Terminal: no further events follow. */
    data class Closed(val cause: CloseCause) : BleEvent
}

sealed interface CloseCause {
    /** Remote dropped or supervision timeout (e.g. GATT status 8) — includes firmware reboots. */
    data class LinkLost(val gattStatus: Int?) : CloseCause
    /** We closed it. */
    data object LocalClose : CloseCause
    /** Phone Bluetooth adapter went down. */
    data object AdapterOff : CloseCause
}

sealed class BleException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ConnectFailed(val gattStatus: Int?, message: String) : BleException(message)
    class ServiceMismatch(message: String) : BleException(message)
    class OperationTimeout(val op: String) : BleException("BLE op timed out: $op")
    class LinkClosed(val closeCause: CloseCause) : BleException("link closed: $closeCause")
}
