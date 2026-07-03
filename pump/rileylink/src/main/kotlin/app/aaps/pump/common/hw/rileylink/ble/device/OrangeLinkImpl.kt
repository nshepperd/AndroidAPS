package app.aaps.pump.common.hw.rileylink.ble.device

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.utils.pump.ByteUtil
import app.aaps.pump.common.hw.rileylink.ble.RileyLinkBLE
import app.aaps.pump.common.hw.rileylink.ble.data.GattAttributes
import app.aaps.pump.common.hw.rileylink.ble.operations.BLECommOperationResult
import app.aaps.pump.common.hw.rileylink.service.RileyLinkServiceData
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OrangeLinkImpl @Inject constructor(
    var aapsLogger: AAPSLogger,
    var rileyLinkServiceData: RileyLinkServiceData
) {

    lateinit var rileyLinkBLE: RileyLinkBLE

    fun onCharacteristicChanged(characteristic: BluetoothGattCharacteristic, data: ByteArray) {
        if (characteristic.uuid.toString() == GattAttributes.CHARA_NOTIFICATION_ORANGE) {
            val first = 0xff and data[0].toInt()
            aapsLogger.info(LTag.PUMPBTCOMM, "OrangeLinkImpl: onCharacteristicChanged ${ByteUtil.shortHexString(data)}=====$first")
            val fv = data[3].toString() + "." + data[4]
            val hv = data[5].toString() + "." + data[6]
            rileyLinkServiceData.versionOrangeFirmware = fv
            rileyLinkServiceData.versionOrangeHardware = hv

            aapsLogger.info(LTag.PUMPBTCOMM, "OrangeLink: Firmware: ${fv}, Hardware: $hv")
        }
    }

    fun resetOrangeLinkData() {
        rileyLinkServiceData.isOrange = false
        rileyLinkServiceData.versionOrangeFirmware = null
        rileyLinkServiceData.versionOrangeHardware = null
    }

    /**
     * We are checking if this is special Orange (with ORANGE_NOTIFICATION_SERVICE)
     */
    fun checkIsOrange(uuidService: UUID) {
        if (GattAttributes.isOrange(uuidService))
            rileyLinkServiceData.isOrange = true
    }

    fun enableNotifications(): Boolean {
        aapsLogger.info(LTag.PUMPBTCOMM, "OrangeLinkImpl::enableNotifications")
        val result: BLECommOperationResult = rileyLinkBLE.setNotificationBlocking(
            UUID.fromString(GattAttributes.SERVICE_RADIO_ORANGE),  //
            UUID.fromString(GattAttributes.CHARA_NOTIFICATION_ORANGE)
        )

        if (result.resultCode != BLECommOperationResult.RESULT_SUCCESS) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Error setting response count notification")
            return false
        }
        return true
    }

    private fun buildScanFilters(): List<ScanFilter> {
        val scanFilterList: MutableList<ScanFilter> = mutableListOf() //ArrayList<*> = ArrayList<Any>()
        val scanFilterBuilder = ScanFilter.Builder()
        scanFilterBuilder.setDeviceAddress(rileyLinkServiceData.rileyLinkAddress)
        scanFilterList.add(scanFilterBuilder.build())
        return scanFilterList
    }

    private fun buildScanSettings(): ScanSettings? {
        val scanSettingBuilder = ScanSettings.Builder()
        scanSettingBuilder.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        scanSettingBuilder.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
        scanSettingBuilder.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        return scanSettingBuilder.build()
    }

    fun startScan() {
        try {
            stopScan()
            // RECONN: a scan is only started from findRileyLink (service init / reconfigure / new address) - NOT after an
            // unexpected disconnect. Seeing this line tells you an explicit (re)scan was actually triggered.
            aapsLogger.warn(LTag.PUMPBTCOMM, "RECONN: OrangeLink startScan for address={} timeoutMs={}", rileyLinkServiceData.rileyLinkAddress, TIME_OUT)
            aapsLogger.debug(LTag.PUMPBTCOMM, "startScan")
            handler.sendEmptyMessageDelayed(TIME_OUT_WHAT, TIME_OUT.toLong())
            val bluetoothLeScanner = rileyLinkBLE.bluetoothAdapter?.bluetoothLeScanner
            // if (bluetoothLeScanner == null) {
            //     bluetoothAdapter.startLeScan(mLeScanCallback)
            //     return
            // }
            bluetoothLeScanner?.startScan(buildScanFilters(), buildScanSettings(), scanCallback)
        } catch (e: Exception) {
            e.printStackTrace()
            aapsLogger.error(LTag.PUMPBTCOMM, "Start scan: ${e.message}", e)
        }
    }

    private val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            //val name = result.device.name
            val address = result.device.address
            if (rileyLinkServiceData.rileyLinkAddress.equals(address)) {
                // RECONN: target OrangeLink found by scan -> connecting. rssi shows signal at the moment of match.
                aapsLogger.warn(LTag.PUMPBTCOMM, "RECONN: scan matched target {} rssi={} -> connectGattInternal", address, result.rssi)
                stopScan()
                rileyLinkBLE.rileyLinkDevice = result.device
                rileyLinkBLE.connectGattInternal()
            }
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            super.onBatchScanResults(results)
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            // RECONN: scan could not even start (1=already started, 2=app reg failed, etc.).
            aapsLogger.warn(LTag.PUMPBTCOMM, "RECONN: OrangeLink scan FAILED errorCode={}", errorCode)
            stopScan()
        }
    }

    private val handler: Handler = object : Handler(HandlerThread(this::class.java.simpleName + "Handler").also { it.start() }.looper) {
        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            when (msg.what) {
                TIME_OUT_WHAT -> {
                    // RECONN: 90s scan window elapsed with no match -> OrangeLink was not seen advertising. If this fires
                    // repeatedly, the device is not discoverable (suspect firmware Group B / power-cycle needed).
                    aapsLogger.warn(LTag.PUMPBTCOMM, "RECONN: OrangeLink scan TIMED OUT after {}ms with no match", TIME_OUT)
                    stopScan()
                }
            }
        }
    }

    fun stopScan() {
        handler.removeMessages(TIME_OUT_WHAT)

        try {
            if (isBluetoothAvailable())
                rileyLinkBLE.bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)

        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Stop scan: ${e.message}", e)
        }
    }

    private fun isBluetoothAvailable(): Boolean =
        rileyLinkBLE.bluetoothAdapter?.isEnabled == true && rileyLinkBLE.bluetoothAdapter?.state == BluetoothAdapter.STATE_ON

    companion object {

        const val TIME_OUT = 90 * 1000
        const val TIME_OUT_WHAT = 0x12
    }
}