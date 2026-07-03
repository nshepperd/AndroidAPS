package app.aaps.pump.common.hw.rileylink.service

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.common.hw.rileylink.RileyLinkConst
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkError
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkPumpDevice
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkServiceState
import app.aaps.pump.common.hw.rileylink.keys.RileyLinkStringPreferenceKey
import app.aaps.pump.common.hw.rileylink.service.tasks.DiscoverGattServicesTask
import app.aaps.pump.common.hw.rileylink.service.tasks.InitializePumpManagerTask
import app.aaps.pump.common.hw.rileylink.service.tasks.ServiceTask
import app.aaps.pump.common.hw.rileylink.service.tasks.ServiceTaskExecutor
import app.aaps.pump.common.hw.rileylink.service.tasks.WakeAndTuneTask
import dagger.android.DaggerBroadcastReceiver
import javax.inject.Inject
import javax.inject.Provider

class RileyLinkBroadcastReceiver : DaggerBroadcastReceiver() {

    @Inject lateinit var preferences: Preferences
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var rileyLinkServiceData: RileyLinkServiceData
    @Inject lateinit var serviceTaskExecutor: ServiceTaskExecutor
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var wakeAndTuneTaskProvider: Provider<WakeAndTuneTask>
    @Inject lateinit var initializePumpManagerTaskProvider: Provider<InitializePumpManagerTask>
    @Inject lateinit var discoverGattServicesTaskProvider: Provider<DiscoverGattServicesTask>

    private val broadcastIdentifiers: MutableMap<String, List<String>> = HashMap()

    init {
        createBroadcastIdentifiers()
    }

    private val rileyLinkService: RileyLinkService?
        get() = (activePlugin.activePump as RileyLinkPumpDevice).rileyLinkService

    private fun createBroadcastIdentifiers() {

        // Bluetooth
        broadcastIdentifiers["Bluetooth"] = listOf(
            RileyLinkConst.Intents.BluetoothConnected,
            RileyLinkConst.Intents.BluetoothReconnected
        )

        // TuneUp
        broadcastIdentifiers["TuneUp"] = listOf(
            RileyLinkConst.IPC.MSG_PUMP_tunePump,
            RileyLinkConst.IPC.MSG_PUMP_quickTune
        )

        // RileyLink
        broadcastIdentifiers["RileyLink"] = listOf(
            RileyLinkConst.Intents.RileyLinkDisconnected,
            RileyLinkConst.Intents.RileyLinkReady,
            RileyLinkConst.Intents.RileyLinkDisconnected,
            RileyLinkConst.Intents.RileyLinkNewAddressSet,
            RileyLinkConst.Intents.RileyLinkDisconnect
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        Thread {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Received Broadcast: $action")
            if (!processBluetoothBroadcasts(action) && !processRileyLinkBroadcasts(action, context) && !processTuneUpBroadcasts(action))
                aapsLogger.error(LTag.PUMPBTCOMM, "Unhandled broadcast: action=$action")
        }.start()
    }

    fun registerBroadcasts(context: Context) {
        val intentFilter = IntentFilter()
        for ((_, value) in broadcastIdentifiers)
            for (intentKey in value)
                intentFilter.addAction(intentKey)
        LocalBroadcastManager.getInstance(context).registerReceiver(this, intentFilter)
    }

    fun unregisterBroadcasts(context: Context) {
        LocalBroadcastManager.getInstance(context).unregisterReceiver(this)
    }

    private fun processRileyLinkBroadcasts(action: String, context: Context): Boolean =
        when (action) {
            RileyLinkConst.Intents.RileyLinkDisconnected  -> {
                val adapterEnabled = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.isEnabled
                // RECONN: on an unexpected drop the ONLY action here is to set an error state; nothing re-scans or
                // re-connects. Recovery depends entirely on Android's autoConnect firing STATE_CONNECTED again.
                aapsLogger.warn(LTag.PUMPBTCOMM, "RECONN: RileyLinkDisconnected received -> error state only (no rescan/reconnect). btAdapterEnabled={}", adapterEnabled)
                if (adapterEnabled)
                    rileyLinkServiceData.setServiceState(RileyLinkServiceState.BluetoothError, RileyLinkError.RileyLinkUnreachable)
                else
                    rileyLinkServiceData.setServiceState(RileyLinkServiceState.BluetoothError, RileyLinkError.BluetoothDisabled)
                true
            }

            RileyLinkConst.Intents.RileyLinkReady         -> {
                aapsLogger.warn(LTag.PUMPBTCOMM, "RECONN: RileyLinkReady received -> starting reader/initialize pipeline. isOrange={}", rileyLinkServiceData.isOrange)
                aapsLogger.warn(LTag.PUMPBTCOMM, "RileyLinkConst.Intents.RileyLinkReady")
                // sendIPCNotification(RT2Const.IPC.MSG_note_WakingPump);
                rileyLinkService?.rileyLinkBLE?.enableNotifications()
                rileyLinkService?.rfSpy?.startReader() // call startReader from outside?
                rileyLinkService?.rfSpy?.initializeRileyLink()
                val bleVersion = rileyLinkService?.rfSpy?.getBLEVersionCached()
                val rlVersion = rileyLinkServiceData.firmwareVersion
                aapsLogger.debug(LTag.PUMPBTCOMM, "RfSpy version (BLE113): $bleVersion")
                rileyLinkService?.rileyLinkServiceData?.versionBLE113 = bleVersion

                aapsLogger.debug(LTag.PUMPBTCOMM, "RfSpy Radio version (CC110): ${rlVersion?.name}")
                rileyLinkServiceData.firmwareVersion = rlVersion
                val task: ServiceTask = initializePumpManagerTaskProvider.get()
                serviceTaskExecutor.startTask(task)
                aapsLogger.info(LTag.PUMPBTCOMM, "Announcing RileyLink open For business")
                true
            }

            RileyLinkConst.Intents.RileyLinkNewAddressSet -> {
                val rileylinkBLEAddress = preferences.get(RileyLinkStringPreferenceKey.MacAddress)
                if (rileylinkBLEAddress == "") aapsLogger.error("No Rileylink BLE Address saved in app")
                else rileyLinkService?.reconfigureRileyLink(rileylinkBLEAddress)
                true
            }

            RileyLinkConst.Intents.RileyLinkDisconnect    -> {
                rileyLinkService?.disconnectRileyLink()
                true
            }

            else                                          -> false
        }

    private fun processBluetoothBroadcasts(action: String): Boolean =
        when (action) {
            RileyLinkConst.Intents.BluetoothConnected   -> {
                // RECONN: arrives only when Android's autoConnect GATT re-establishes. If you never see this after a
                // RECONN: STATE_DISCONNECTED, autoConnect is not recovering (hypothesis A1/A3).
                aapsLogger.warn(LTag.PUMPBTCOMM, "RECONN: BluetoothConnected received -> discoverServices directly. ts={}", System.currentTimeMillis())
                aapsLogger.debug(LTag.PUMPBTCOMM, "Bluetooth - Connected")
                // Run discovery directly instead of via ServiceTaskExecutor: the executor is single-threaded and can be
                // busy for minutes with a radio task whose BLE writes all fail until discovery re-configures the GATT,
                // so queueing here would starve the one operation that can unblock everything else.
                rileyLinkService?.rileyLinkBLE?.discoverServices()
                true
            }

            RileyLinkConst.Intents.BluetoothReconnected -> {
                // RECONN: only fired by RileyLinkBluetoothStateReceiver when the phone's BT adapter is toggled off->on,
                // NOT when the OrangeLink reappears.
                aapsLogger.warn(LTag.PUMPBTCOMM, "RECONN: BluetoothReconnected (adapter toggled) -> bluetoothInit + discover. ts={}", System.currentTimeMillis())
                aapsLogger.debug(LTag.PUMPBTCOMM, "Bluetooth - Reconnecting")
                rileyLinkService?.bluetoothInit()
                serviceTaskExecutor.startTask(discoverGattServicesTaskProvider.get().with(true))
                true
            }

            else                                        -> false
        }

    private fun processTuneUpBroadcasts(action: String): Boolean =
        if (broadcastIdentifiers["TuneUp"]?.contains(action) == true) {
            if (rileyLinkServiceData.targetDevice.tuneUpEnabled) serviceTaskExecutor.startTask(wakeAndTuneTaskProvider.get())
            true
        } else false
}