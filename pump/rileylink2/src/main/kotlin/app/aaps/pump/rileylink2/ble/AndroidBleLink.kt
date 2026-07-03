package app.aaps.pump.rileylink2.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * L0 production implementation over Android GATT.
 *
 * ⚠ Requires on-device validation — GATT behavior differs across Android versions and
 * stacks. The logic below follows the design contract (§5): atomic bring-up, one
 * outstanding GATT operation, deadline-expiry closes the connection, directed connects
 * (autoConnect=false — reconnection policy belongs to the supervisor, not Android).
 *
 * Uses the pre-API-33 GATT write/notification calls (suppressed deprecation) for
 * compatibility with the minSdk range AAPS supports.
 */
@SuppressLint("MissingPermission")
class AndroidBleLink(
    private val context: Context,
    private val log: (String) -> Unit = {},
) : BleLink {

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter

    override fun scan(filterAddress: String?, timeout: Duration): Flow<ScanHit> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
            ?: run { close(BleException.ConnectFailed(null, "no BLE scanner (adapter off?)")); return@callbackFlow }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(ScanHit(result.device.address, result.device.name, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleException.ConnectFailed(null, "scan failed: $errorCode"))
            }
        }
        val filters = filterAddress?.let { listOf(ScanFilter.Builder().setDeviceAddress(it).build()) }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, callback)
        launch { delay(timeout); close() }
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    override suspend fun connect(address: String, timeout: Duration): BleConnection {
        val device = adapter?.getRemoteDevice(address)
            ?: throw BleException.ConnectFailed(null, "bluetooth adapter unavailable")
        val connection = AndroidBleConnection(log)
        try {
            withTimeout(timeout) {
                // Some Android stacks require connectGatt on the main thread.
                val gatt = withContext(Dispatchers.Main.immediate) {
                    device.connectGatt(context, /* autoConnect = */ false, connection.gattCallback)
                } ?: throw BleException.ConnectFailed(null, "connectGatt returned null")
                connection.bringUp(gatt)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            connection.close()
            throw BleException.ConnectFailed(null, "bring-up timed out")
        } catch (e: Exception) {
            connection.close()
            throw e
        }
        return connection
    }
}

@SuppressLint("MissingPermission")
private class AndroidBleConnection(private val log: (String) -> Unit) : BleConnection {

    private val _events = MutableSharedFlow<BleEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<BleEvent> = _events.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    @Volatile private var closed = false
    @Volatile private var negotiatedMtu = DEFAULT_MTU

    override val mtu: Int get() = negotiatedMtu

    /** One outstanding GATT operation (Android requirement); ops carry deadlines. */
    private val opMutex = Mutex()
    @Volatile private var pendingOp: CompletableDeferred<OpResult>? = null

    /** MTU negotiation is tracked separately from [pendingOp]: onMtuChanged is the one GATT
     *  callback that can fire UNSOLICITED (peer/stack-initiated exchange, co-resident GATT
     *  client), so it must never be allowed to complete an unrelated in-flight operation. */
    @Volatile private var pendingMtu: CompletableDeferred<Unit>? = null

    private val connected = CompletableDeferred<Unit>()
    private val servicesDiscovered = CompletableDeferred<Unit>()

    private data class OpResult(val status: Int, val value: ByteArray?, val rssi: Int = 0)

    val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("LINKV2: onConnectionStateChange status=$status newState=$newState")
            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS ->
                    connected.complete(Unit)

                newState == BluetoothProfile.STATE_DISCONNECTED                                      -> {
                    val cause = if (closed) CloseCause.LocalClose else CloseCause.LinkLost(status)
                    markClosed(cause)
                }

                status != BluetoothGatt.GATT_SUCCESS                                                 ->
                    connected.completeExceptionally(BleException.ConnectFailed(status, "connect failed, status=$status"))
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) servicesDiscovered.complete(Unit)
            else servicesDiscovered.completeExceptionally(BleException.ServiceMismatch("discovery failed, status=$status"))
        }

        @Deprecated("pre-33 callback")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            pendingOp?.complete(OpResult(status, characteristic.value))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pendingOp?.complete(OpResult(status, null))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            pendingOp?.complete(OpResult(status, null))
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            pendingOp?.complete(OpResult(status, null, rssi))
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu
            pendingMtu?.complete(Unit)      // NEVER pendingOp — see pendingMtu declaration
        }

        @Deprecated("pre-33 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val bytes = characteristic.value ?: ByteArray(0)
            charIdOf(characteristic.uuid)?.let {
                _events.tryEmit(BleEvent.NotificationReceived(it, bytes))
            }
        }
    }

    /** Atomic bring-up (§5): connected → services → RileyLink verified → MTU → subscribed. */
    suspend fun bringUp(gatt: BluetoothGatt) {
        this.gatt = gatt
        connected.await()
        if (!gatt.discoverServices()) throw BleException.ServiceMismatch("discoverServices() refused")
        servicesDiscovered.await()
        gatt.getService(GattUuids.RADIO_SERVICE)
            ?: throw BleException.ServiceMismatch("radio service missing — not a RileyLink-compatible device")
        requestMtu()
        subscribeInternal(GattUuids.CHARA_RADIO_RESPONSE_COUNT)
        log("LINKV2: bring-up complete for ${gatt.device.address}, mtu=$negotiatedMtu")
    }

    /**
     * Fully non-fatal, whatever goes wrong: the stock protocol works at MTU 23 (L1 falls
     * back to it when [mtu] < 131), and some stacks accept requestMtu() but never deliver
     * onMtuChanged (e.g. MTU already negotiated on the shared ACL link) — that must not
     * kill a connection that works. Deliberately NOT routed through [executeOp]: its
     * deadline-expiry path closes the link, and the callback must not touch [pendingOp].
     */
    private suspend fun requestMtu() {
        val changed = CompletableDeferred<Unit>()
        opMutex.withLock {
            if (closed) return
            pendingMtu = changed
            try {
                if (!requireGatt().requestMtu(REQUESTED_MTU)) {
                    log("LINKV2: requestMtu refused by stack; staying at mtu=$negotiatedMtu")
                    return
                }
                if (withTimeoutOrNull(MTU_TIMEOUT) { changed.await() } == null)
                    log("LINKV2: no onMtuChanged within $MTU_TIMEOUT; staying at mtu=$negotiatedMtu")
            } finally {
                pendingMtu = null
            }
        }
    }

    override suspend fun write(char: CharId, bytes: ByteArray, timeout: Duration) {
        val characteristic = characteristic(char)
        executeOp("write($char)", timeout) {
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            requireGatt().writeCharacteristic(characteristic)
        }
    }

    override suspend fun read(char: CharId, timeout: Duration): ByteArray {
        val characteristic = characteristic(char)
        val result = executeOp("read($char)", timeout) {
            @Suppress("DEPRECATION")
            requireGatt().readCharacteristic(characteristic)
        }
        return result.value ?: ByteArray(0)
    }

    override suspend fun subscribe(char: CharId, timeout: Duration) {
        withTimeout(timeout) { subscribeInternal(GattUuids.of(char)) }
    }

    override suspend fun readRemoteRssi(timeout: Duration): Int =
        executeOp("readRemoteRssi", timeout) { requireGatt().readRemoteRssi() }.rssi

    private suspend fun subscribeInternal(uuid: UUID) {
        val characteristic = findCharacteristic(uuid)
            ?: throw BleException.ServiceMismatch("characteristic $uuid missing")
        val gatt = requireGatt()
        if (!gatt.setCharacteristicNotification(characteristic, true))
            throw BleException.ServiceMismatch("setCharacteristicNotification failed for $uuid")
        val descriptor = characteristic.getDescriptor(CCCD)
            ?: throw BleException.ServiceMismatch("CCCD missing on $uuid")
        executeOp("subscribe($uuid)", SUBSCRIBE_TIMEOUT) {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private suspend fun executeOp(name: String, timeout: Duration, start: () -> Boolean): OpResult =
        opMutex.withLock {
            if (closed) throw BleException.LinkClosed(CloseCause.LocalClose)
            val deferred = CompletableDeferred<OpResult>()
            pendingOp = deferred
            try {
                if (!start()) throw BleException.OperationTimeout("$name refused by stack")
                val result = withTimeoutOrNull(timeout) { deferred.await() }
                    ?: run {
                        // A GATT that stops answering is dead in practice (design §5).
                        markClosed(CloseCause.LinkLost(null))
                        throw BleException.OperationTimeout(name)
                    }
                if (result.status != BluetoothGatt.GATT_SUCCESS)
                    throw BleException.OperationTimeout("$name failed, status=${result.status}")
                result
            } finally {
                pendingOp = null
            }
        }

    override suspend fun close() {
        markClosed(CloseCause.LocalClose)
    }

    private fun markClosed(cause: CloseCause) {
        if (closed && cause !is CloseCause.LocalClose) return
        if (!closed) {
            closed = true
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
            gatt = null
            _events.tryEmit(BleEvent.Closed(cause))
            pendingOp?.completeExceptionally(BleException.LinkClosed(cause))
            pendingMtu?.complete(Unit)      // unblock a waiting requestMtu; it is non-fatal anyway
            if (!connected.isCompleted) connected.completeExceptionally(BleException.LinkClosed(cause))
            if (!servicesDiscovered.isCompleted) servicesDiscovered.completeExceptionally(BleException.LinkClosed(cause))
        }
    }

    private fun requireGatt(): BluetoothGatt =
        gatt ?: throw BleException.LinkClosed(CloseCause.LocalClose)

    private fun characteristic(char: CharId): BluetoothGattCharacteristic =
        findCharacteristic(GattUuids.of(char))
            ?: throw BleException.ServiceMismatch("characteristic $char missing")

    private fun findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? {
        val g = gatt ?: return null
        for (service in g.services) {
            service.getCharacteristic(uuid)?.let { return it }
        }
        return null
    }

    private fun charIdOf(uuid: UUID): CharId? = CharId.entries.firstOrNull { GattUuids.of(it) == uuid }

    companion object {

        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val SUBSCRIBE_TIMEOUT = 5.seconds
        private val MTU_TIMEOUT = 5.seconds

        private const val DEFAULT_MTU = 23

        /** Firmware SoftDevice is configured for 247 (PROTOCOL-V2.md deviation 1); v2 needs
         *  ≥ 131. Android 14+ coerces the first request to 517 — also fine. */
        private const val REQUESTED_MTU = 247
    }
}
