package app.aaps.plugins.main.general.bgMeter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.main.general.bgMeter.GlucoseProfile.CLIENT_CHARACTERISTIC_CONFIG
import app.aaps.plugins.main.general.bgMeter.GlucoseProfile.CURRENT_TIME
import app.aaps.plugins.main.general.bgMeter.GlucoseProfile.CURRENT_TIME_SERVICE
import app.aaps.plugins.main.general.bgMeter.GlucoseProfile.DATE_TIME
import app.aaps.plugins.main.general.bgMeter.GlucoseProfile.DEVICE_INFO_SERVICE
import app.aaps.plugins.main.general.bgMeter.GlucoseProfile.GLUCOSE_MEASUREMENT
import app.aaps.plugins.main.general.bgMeter.GlucoseProfile.GLUCOSE_MEASUREMENT_CONTEXT
import app.aaps.plugins.main.general.bgMeter.GlucoseProfile.GLUCOSE_SERVICE
import app.aaps.plugins.main.general.bgMeter.GlucoseProfile.MANUFACTURER_NAME
import app.aaps.plugins.main.general.bgMeter.GlucoseProfile.MODEL_NUMBER
import app.aaps.plugins.main.general.bgMeter.GlucoseProfile.RECORD_ACCESS_CONTROL_POINT
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs

/**
 * Background connection to one bonded Bluetooth Glucose Profile meter.
 *
 * The meter only switches its radio on after a measurement (or when its transfer menu is used),
 * so we keep an autoConnect GATT request pending and let the Bluetooth stack connect whenever
 * the meter advertises. Each connection: read meter clock, subscribe, ask for records newer
 * than the last imported sequence number. The meter drops the link itself when it's done.
 *
 * All state is touched only on [handler]'s thread.
 */
@SuppressLint("MissingPermission") // checked by the plugin before start()
class BgMeterConnection(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val handler: Handler,
    private val listener: Listener
) {

    interface Listener {

        /** Human-readable connection state change */
        fun onStatus(status: String)

        /** Sequence number to request records from, or null to request everything */
        fun requestFromSequence(): Int?

        /**
         * A measurement arrived.
         * @param clockOffsetMs meter clock minus phone clock, already rounded/ignored when small
         * @param zone time zone the meter wall clock is assumed to be in
         */
        fun onMeasurement(measurement: GlucoseProfile.Measurement, clockOffsetMs: Long, zone: ZoneId)

        fun onTransferFinished(success: Boolean, detail: String)
    }

    companion object {

        private const val OP_TIMEOUT_MS = 10_000L
        private const val TRANSFER_TIMEOUT_MS = 120_000L
        private const val RECONNECT_DELAY_MS = 5_000L

        /** Meter clock differences below this are treated as zero, so timestamps stay stable across syncs */
        private const val CLOCK_OFFSET_THRESHOLD_MS = 60_000L
    }

    private var address: String? = null
    private var gatt: BluetoothGatt? = null
    private var connected = false

    private val ops = ArrayDeque<Pair<String, (BluetoothGatt) -> Boolean>>()
    private var currentOp: String? = null
    private var clockOffsetMs = 0L
    private var transferRunning = false
    private var received = 0

    private val opTimeout = Runnable {
        aapsLogger.warn(LTag.BGSOURCE, "BgMeter: GATT op timed out: $currentOp")
        restart()
    }

    private val transferTimeout = Runnable {
        aapsLogger.warn(LTag.BGSOURCE, "BgMeter: record transfer timed out after $received records")
        finishTransfer(false, "timeout")
    }

    private val reconnect = Runnable { connectGatt() }

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager?)?.adapter

    /** (Re)arm the pending connection to [address] */
    fun start(address: String) = handler.post {
        closeGatt()
        this.address = address
        connectGatt()
    }

    fun stop() = handler.post {
        address = null
        closeGatt()
        listener.onStatus("stopped")
    }

    /** Bluetooth adapter came back on, or bond just completed: (re)arm the pending connection */
    fun restart() = handler.post {
        closeGatt()
        if (address != null) connectGatt()
    }

    private fun connectGatt() {
        handler.removeCallbacks(reconnect)
        val address = address ?: return
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            listener.onStatus("bluetooth off")
            return
        }
        val device = adapter.getRemoteDevice(address)
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            listener.onStatus("not paired")
            return
        }
        aapsLogger.debug(LTag.BGSOURCE, "BgMeter: arming autoConnect to $address")
        gatt = device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
        listener.onStatus(if (gatt == null) "connect failed" else "waiting for meter")
    }

    private fun closeGatt() {
        handler.removeCallbacks(opTimeout)
        handler.removeCallbacks(transferTimeout)
        handler.removeCallbacks(reconnect)
        ops.clear()
        currentOp = null
        transferRunning = false
        connected = false
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g != gatt) return
            aapsLogger.debug(LTag.BGSOURCE, "BgMeter: connection state $newState status $status")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                listener.onStatus("connected")
                if (!g.discoverServices()) restart()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasConnected = connected
                if (transferRunning) finishTransfer(false, "meter disconnected")
                closeGatt()
                // With autoConnect a disconnect ends the pending request; arm a new one.
                handler.postDelayed(reconnect, RECONNECT_DELAY_MS)
                if (wasConnected) listener.onStatus("waiting for meter")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g != gatt) return
            val glucose = g.getService(GLUCOSE_SERVICE)
            if (status != BluetoothGatt.GATT_SUCCESS || glucose == null) {
                aapsLogger.warn(LTag.BGSOURCE, "BgMeter: service discovery status $status, glucose service ${glucose != null}")
                g.disconnect()
                return
            }
            listener.onStatus("syncing")
            clockOffsetMs = 0
            received = 0
            queueSync(g)
            next()
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (g != gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) handleRead(characteristic.uuid, value)
            else aapsLogger.warn(LTag.BGSOURCE, "BgMeter: read ${characteristic.uuid} failed: $status")
            next()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (g != gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                aapsLogger.warn(LTag.BGSOURCE, "BgMeter: CCCD write ${descriptor.characteristic.uuid} failed: $status")
                g.disconnect()
                return
            }
            next()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (g != gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                aapsLogger.warn(LTag.BGSOURCE, "BgMeter: write ${characteristic.uuid} failed: $status")
                g.disconnect()
                return
            }
            next()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (g != gatt) return
            when (characteristic.uuid) {
                GLUCOSE_MEASUREMENT         -> handleMeasurement(value)
                RECORD_ACCESS_CONTROL_POINT -> handleRacp(value)
                GLUCOSE_MEASUREMENT_CONTEXT -> aapsLogger.debug(LTag.BGSOURCE, "BgMeter: context ${value.toHex()}")
            }
        }
    }

    private fun queueSync(g: BluetoothGatt) {
        val glucose = g.getService(GLUCOSE_SERVICE)
        g.getService(DEVICE_INFO_SERVICE)?.let { info ->
            info.getCharacteristic(MANUFACTURER_NAME)?.let { c -> ops.add("read manufacturer" to { it.readCharacteristic(c) }) }
            info.getCharacteristic(MODEL_NUMBER)?.let { c -> ops.add("read model" to { it.readCharacteristic(c) }) }
        }
        // Roche puts Date Time in the glucose service; others use the Current Time Service.
        val clock = glucose.getCharacteristic(DATE_TIME) ?: g.getService(CURRENT_TIME_SERVICE)?.getCharacteristic(CURRENT_TIME)
        if (clock != null) ops.add("read meter clock" to { it.readCharacteristic(clock) })
        else aapsLogger.debug(LTag.BGSOURCE, "BgMeter: meter has no readable clock; trusting its timestamps")

        glucose.getCharacteristic(GLUCOSE_MEASUREMENT)?.let { c -> ops.add("notify measurement" to { subscribe(it, c, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }) }
        glucose.getCharacteristic(GLUCOSE_MEASUREMENT_CONTEXT)?.let { c -> ops.add("notify context" to { subscribe(it, c, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }) }
        val racp = glucose.getCharacteristic(RECORD_ACCESS_CONTROL_POINT)
        if (racp == null) {
            aapsLogger.warn(LTag.BGSOURCE, "BgMeter: no RACP; only live measurements will arrive")
            return
        }
        ops.add("indicate RACP" to { subscribe(it, racp, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) })
        ops.add("request records" to {
            val from = listener.requestFromSequence()
            val command = if (from == null) GlucoseProfile.racpReportAllRecords() else GlucoseProfile.racpReportRecordsFrom(from)
            aapsLogger.debug(LTag.BGSOURCE, "BgMeter: requesting records ${from?.let { ">= $it" } ?: "(all)"}")
            transferRunning = true
            handler.postDelayed(transferTimeout, TRANSFER_TIMEOUT_MS)
            it.writeCharacteristic(racp, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        })
    }

    private fun subscribe(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        if (!g.setCharacteristicNotification(c, true)) return false
        val cccd = c.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG) ?: return false
        return g.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
    }

    private fun next() {
        handler.removeCallbacks(opTimeout)
        currentOp = null
        val g = gatt ?: return
        val (name, op) = ops.removeFirstOrNull() ?: return
        currentOp = name
        if (op(g)) handler.postDelayed(opTimeout, OP_TIMEOUT_MS)
        else {
            aapsLogger.warn(LTag.BGSOURCE, "BgMeter: could not start '$name'")
            g.disconnect()
        }
    }

    private fun handleRead(uuid: UUID, value: ByteArray) {
        when (uuid) {
            MANUFACTURER_NAME, MODEL_NUMBER -> aapsLogger.debug(LTag.BGSOURCE, "BgMeter: $uuid = ${String(value).trim()}")

            DATE_TIME, CURRENT_TIME         -> {
                val meterNow = GlucoseProfile.parseDateTime(value)
                if (meterNow == null) {
                    aapsLogger.warn(LTag.BGSOURCE, "BgMeter: meter clock unreadable: ${value.toHex()}")
                    return
                }
                val offset = meterNow.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - System.currentTimeMillis()
                clockOffsetMs = if (abs(offset) < CLOCK_OFFSET_THRESHOLD_MS) 0 else offset
                aapsLogger.debug(LTag.BGSOURCE, "BgMeter: meter clock $meterNow, offset ${offset / 1000}s, applying ${clockOffsetMs / 1000}s")
            }
        }
    }

    private fun handleMeasurement(value: ByteArray) {
        val measurement = GlucoseProfile.parseMeasurement(value)
        if (measurement == null) {
            aapsLogger.warn(LTag.BGSOURCE, "BgMeter: unparseable measurement ${value.toHex()}")
            return
        }
        aapsLogger.debug(LTag.BGSOURCE, "BgMeter: $measurement")
        received++
        // Records keep streaming during a transfer; push the deadline out.
        if (transferRunning) {
            handler.removeCallbacks(transferTimeout)
            handler.postDelayed(transferTimeout, TRANSFER_TIMEOUT_MS)
        }
        listener.onMeasurement(measurement, clockOffsetMs, ZoneId.systemDefault())
    }

    private fun handleRacp(value: ByteArray) {
        when (val response = GlucoseProfile.parseRacp(value)) {
            is GlucoseProfile.RacpResponse.Completed ->
                finishTransfer(response.successful, "RACP response ${response.responseCode}, $received records")

            else                                     -> aapsLogger.debug(LTag.BGSOURCE, "BgMeter: RACP $response")
        }
    }

    private fun finishTransfer(success: Boolean, detail: String) {
        if (!transferRunning) return
        transferRunning = false
        handler.removeCallbacks(transferTimeout)
        aapsLogger.debug(LTag.BGSOURCE, "BgMeter: transfer finished success=$success $detail")
        listener.onTransferFinished(success, detail)
        if (connected) listener.onStatus("connected")
    }

    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
}
