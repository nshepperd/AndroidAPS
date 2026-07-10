package app.aaps.pump.medtronic.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.IBinder
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.defs.PumpDeviceState
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.utils.pump.ByteUtil
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkTargetFrequency
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkError
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkServiceState
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkTargetDevice
import app.aaps.pump.common.hw.rileylink.keys.RileyLinkDoubleKey
import app.aaps.pump.common.hw.rileylink.keys.RileyLinkStringKey
import app.aaps.pump.common.hw.rileylink.keys.RileyLinkStringPreferenceKey
import app.aaps.pump.common.hw.rileylink.keys.RileylinkBooleanPreferenceKey
import app.aaps.pump.common.hw.rileylink.service.RileyLinkServiceData
import app.aaps.pump.medtronic.MedtronicPumpPlugin
import app.aaps.pump.medtronic.R
import app.aaps.pump.medtronic.comm.MedtronicCommunicationManager
import app.aaps.pump.medtronic.comm.ui.MedtronicUIComm
import app.aaps.pump.medtronic.defs.BatteryType
import app.aaps.pump.medtronic.defs.MedtronicCommandType
import app.aaps.pump.medtronic.defs.MedtronicDeviceType
import app.aaps.pump.medtronic.driver.MedtronicPumpStatus
import app.aaps.pump.medtronic.keys.MedtronicIntPreferenceKey
import app.aaps.pump.medtronic.keys.MedtronicStringPreferenceKey
import app.aaps.pump.medtronic.util.MedtronicUtil
import app.aaps.pump.rileylink2.ble.AndroidBleLink
import app.aaps.pump.rileylink2.link.PumpCmdOutcome
import app.aaps.pump.rileylink2.link.PumpLinkConfig
import app.aaps.pump.rileylink2.link.PumpLinkPrefs
import app.aaps.pump.rileylink2.link.PumpLinkState
import app.aaps.pump.rileylink2.link.PumpLinkSupervisor
import app.aaps.pump.rileylink2.link.SignalHistory
import app.aaps.pump.rileylink2.minimed.MinimedRadio
import dagger.android.DaggerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * rileylink2 replacement for RileyLinkMedtronicService: owns the coroutine scope, the
 * AndroidBleLink transport and the PumpLinkSupervisor, and bridges supervisor state onto
 * the legacy RileyLinkServiceData so MedtronicFragment / RileyLinkStatusActivity keep
 * working unchanged (RileyLinkServiceData is retained as a dumb, bridge-written holder;
 * no legacy machinery runs).
 */
open class MedtronicPumpLinkService : DaggerService() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var rileyLinkServiceData: RileyLinkServiceData
    @Inject lateinit var medtronicPumpPlugin: MedtronicPumpPlugin
    @Inject lateinit var medtronicUtil: MedtronicUtil
    @Inject lateinit var medtronicPumpStatus: MedtronicPumpStatus
    @Inject lateinit var medtronicCommunicationManager: MedtronicCommunicationManager
    @Inject lateinit var medtronicUIComm: MedtronicUIComm

    private val binder: IBinder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var supervisor: PumpLinkSupervisor? = null
        private set

    /** Signal-quality history for the live chart; survives supervisor restarts. */
    val signalHistory = SignalHistory()

    private var configuredAddress: String? = null
    private var configuredSerial: String? = null
    private var configuredFrequencyRegion: RileyLinkTargetFrequency? = null

    inner class LocalBinder : Binder() {

        val serviceInstance: MedtronicPumpLinkService
            get() = this@MedtronicPumpLinkService
    }

    override fun onBind(intent: Intent): IBinder = binder

    /** F8 hardening: tell the supervisor when the phone's BT adapter goes down/up so it
     *  drops the link with an honest cause and parks reconnects instead of burning blind
     *  30 s connect attempts against a restarting stack. */
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF,
                BluetoothAdapter.STATE_OFF -> {
                    aapsLogger.info(LTag.PUMPBTCOMM, "LINKV2: bluetooth adapter going down")
                    supervisor?.setAdapterUp(false)
                }

                BluetoothAdapter.STATE_ON  -> {
                    aapsLogger.info(LTag.PUMPBTCOMM, "LINKV2: bluetooth adapter up")
                    supervisor?.setAdapterUp(true)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        aapsLogger.debug(LTag.PUMPCOMM, "MedtronicPumpLinkService created")
        rileyLinkServiceData.targetDevice = RileyLinkTargetDevice.MedtronicPump
        medtronicUIComm.supervisorAccessor = { supervisor }
        registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    override fun onDestroy() {
        aapsLogger.debug(LTag.PUMPCOMM, "MedtronicPumpLinkService destroyed")
        runCatching { unregisterReceiver(btStateReceiver) }
        supervisor?.stop()
        supervisor = null
        scope.cancel()
        super.onDestroy()
    }

    val isInitialized: Boolean
        get() = supervisor?.state?.value is PumpLinkState.Ready

    val linkState: PumpLinkState
        get() = supervisor?.state?.value ?: PumpLinkState.Stopped

    // ---------------------------------------------------------------- lifecycle ----

    /** (Re)start the supervisor from current preferences. Safe to call repeatedly. */
    @Synchronized
    open fun restartSupervisor() {
        supervisor?.stop()
        supervisor = null
        val address = preferences.get(RileyLinkStringPreferenceKey.MacAddress)
        if (address.isBlank()) {
            aapsLogger.warn(LTag.PUMPCOMM, "restartSupervisor: no RileyLink address configured")
            return
        }
        val frequencies = when (rileyLinkServiceData.rileyLinkTargetFrequency) {
            RileyLinkTargetFrequency.MedtronicWorldWide -> MinimedRadio.WW_FREQUENCIES
            else                                        -> MinimedRadio.US_FREQUENCIES
        }
        val probeMessage = medtronicUtil.buildCommandPayload(rileyLinkServiceData, MedtronicCommandType.PumpModel, null)
        val config = PumpLinkConfig(
            deviceAddress = address,
            probeMessage = probeMessage,
            scanFrequenciesMHz = frequencies,
            prefs = preferencesBackedLinkPrefs,
            bleRssiPollInterval = 15.seconds,
            batteryPollInterval = 30.minutes,
            batteryPollLowInterval = 10.minutes,
        )
        val newSupervisor = PumpLinkSupervisor(
            bleLink = AndroidBleLink(this) { line -> aapsLogger.debug(LTag.PUMPBTCOMM, line) },
            config = config,
            scope = scope,
        )
        newSupervisor.onTransition = { transition ->
            aapsLogger.info(
                LTag.PUMPBTCOMM,
                "LINKV2: ${transition.from} -> ${transition.to} (${transition.cause}) at ${transition.atElapsed.inWholeMilliseconds}ms"
            )
            signalHistory.recordTransition(transition)
            bridgeState(transition.to)
        }
        newSupervisor.onNote = { note ->
            aapsLogger.info(LTag.PUMPBTCOMM, "LINKV2: $note")
        }
        newSupervisor.onRadioOutcome = signalHistory::recordRadioOutcome
        newSupervisor.onBleRssi = signalHistory::recordBleRssi
        newSupervisor.onBatteryLevel = { percent -> rileyLinkServiceData.batteryLevel = percent }
        supervisor = newSupervisor
        configuredAddress = address
        configuredSerial = medtronicPumpStatus.serialNumber
        configuredFrequencyRegion = rileyLinkServiceData.rileyLinkTargetFrequency
        val adapterEnabled = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter?.isEnabled == true
        newSupervisor.setAdapterUp(adapterEnabled)
        newSupervisor.start()
        aapsLogger.info(LTag.PUMPCOMM, "LINKV2: supervisor started for $address")
    }

    /** Map v2 states onto the legacy enum for the existing UI surface. */
    private fun bridgeState(state: PumpLinkState) {
        when (state) {
            is PumpLinkState.NotConfigured        -> rileyLinkServiceData.setServiceState(RileyLinkServiceState.NotStarted)
            is PumpLinkState.Stopped              -> rileyLinkServiceData.setServiceState(RileyLinkServiceState.NotStarted)
            is PumpLinkState.BleConnecting        -> rileyLinkServiceData.setServiceState(RileyLinkServiceState.RileyLinkInitializing)
            is PumpLinkState.SessionInit          -> rileyLinkServiceData.setServiceState(RileyLinkServiceState.RileyLinkReady)
            is PumpLinkState.Tuning               -> rileyLinkServiceData.setServiceState(RileyLinkServiceState.TuneUpDevice)
            is PumpLinkState.Ready                -> {
                rileyLinkServiceData.lastGoodFrequency = state.frequencyMHz
                rileyLinkServiceData.setServiceState(RileyLinkServiceState.PumpConnectorReady)
            }

            is PumpLinkState.PumpUnreachable      ->
                rileyLinkServiceData.setServiceState(RileyLinkServiceState.PumpConnectorError, RileyLinkError.NoContactWithDevice)

            is PumpLinkState.LinkDown             ->
                rileyLinkServiceData.setServiceState(RileyLinkServiceState.BluetoothError, RileyLinkError.RileyLinkUnreachable)

            is PumpLinkState.IncompatibleFirmware ->
                rileyLinkServiceData.setServiceState(RileyLinkServiceState.RileyLinkError, RileyLinkError.RileyLinkUnreachable)
        }
    }

    // ---------------------------------------------------------------- pump access ----

    /**
     * Blocking pump-reachability probe (legacy isDeviceReachable equivalent) for the
     * plugin's pre-dosing checks. Escalates to the supervisor on failure.
     */
    fun verifyPumpReachable(): Boolean {
        val currentSupervisor = supervisor ?: return false
        val outcome = runBlocking {
            currentSupervisor.withPump("verifyPumpReachable") { radio ->
                medtronicCommunicationManager.verifyPumpPresence(radio)
            }
        }
        val reachable = (outcome as? PumpCmdOutcome.Success)?.value == true
        if (!reachable) currentSupervisor.reportPumpUnreachable()
        return reachable
    }

    // ---------------------------------------------------------------- configuration ----

    fun verifyConfiguration(forceRileyLinkAddressRenewal: Boolean = false): Boolean {
        return try {
            val regexSN = "[0-9]{6}"
            val regexMac = "([\\da-fA-F]{1,2}(?::|$)){6}"
            medtronicPumpStatus.errorDescription = "-"
            val serialNr = preferences.get(MedtronicStringPreferenceKey.Serial)
            if (!serialNr.matches(regexSN.toRegex())) {
                medtronicPumpStatus.errorDescription = rh.gs(R.string.medtronic_error_serial_invalid)
                return false
            }
            if (serialNr != medtronicPumpStatus.serialNumber) {
                medtronicPumpStatus.serialNumber = serialNr
            }
            setPumpIDString(serialNr)
            val pumpTypePref = preferences.get(MedtronicStringPreferenceKey.PumpType)
            if (pumpTypePref.isEmpty()) {
                medtronicPumpStatus.errorDescription = rh.gs(R.string.medtronic_error_pump_type_not_set)
                return false
            } else {
                val pumpTypePart = pumpTypePref.substring(0, 3)
                if (!pumpTypePart.matches("[0-9]{3}".toRegex())) {
                    medtronicPumpStatus.errorDescription = rh.gs(R.string.medtronic_error_pump_type_invalid)
                    return false
                } else {
                    val pumpType = medtronicPumpStatus.medtronicPumpMap[pumpTypePart] ?: return false
                    medtronicPumpStatus.medtronicDeviceType = medtronicPumpStatus.medtronicDeviceTypeMap[pumpTypePart] ?: return false
                    medtronicPumpStatus.pumpType = pumpType
                    medtronicPumpPlugin.pumpType = pumpType
                    if (pumpTypePart.startsWith("7")) medtronicPumpStatus.reservoirFullUnits = 300 else medtronicPumpStatus.reservoirFullUnits = 176
                }
            }
            rileyLinkServiceData.rileyLinkTargetFrequency = RileyLinkTargetFrequency.getByKey(preferences.get(MedtronicStringPreferenceKey.PumpFrequency))
            val rileyLinkAddress = preferences.get(RileyLinkStringPreferenceKey.MacAddress)
            if (rileyLinkAddress.isEmpty() || !rileyLinkAddress.matches(regexMac.toRegex())) {
                medtronicPumpStatus.errorDescription = rh.gs(R.string.medtronic_error_rileylink_address_invalid)
                aapsLogger.debug(LTag.PUMP, "RileyLink address invalid: $rileyLinkAddress")
                return false
            }
            rileyLinkServiceData.rileyLinkAddress = rileyLinkAddress
            rileyLinkServiceData.rileyLinkName = preferences.get(RileyLinkStringKey.Name)

            val maxBolusLcl = preferences.get(MedtronicIntPreferenceKey.MaxBolus).toDouble()
            if (medtronicPumpStatus.maxBolus == null || medtronicPumpStatus.maxBolus != maxBolusLcl) {
                medtronicPumpStatus.maxBolus = maxBolusLcl
            }
            val maxBasalLcl = preferences.get(MedtronicIntPreferenceKey.MaxBasal).toDouble()
            if (medtronicPumpStatus.maxBasal == null || medtronicPumpStatus.maxBasal != maxBasalLcl) {
                medtronicPumpStatus.maxBasal = maxBasalLcl
            }
            medtronicPumpStatus.batteryType = BatteryType.getByKey(preferences.get(MedtronicStringPreferenceKey.BatteryType))
            rileyLinkServiceData.showBatteryLevel = preferences.get(RileylinkBooleanPreferenceKey.ShowReportedBatteryLevel)
            // NOTE: the legacy Encoding preference is inert in v2 — host-side 4b6b ("Local")
            // is the only supported mode and is configured unconditionally at bring-up (R1).

            val needsRestart = forceRileyLinkAddressRenewal ||
                supervisor == null ||
                rileyLinkAddress != configuredAddress ||
                serialNr != configuredSerial ||
                rileyLinkServiceData.rileyLinkTargetFrequency != configuredFrequencyRegion
            if (needsRestart) restartSupervisor()
            true
        } catch (ex: Exception) {
            medtronicPumpStatus.errorDescription = ex.message
            aapsLogger.error(LTag.PUMP, "Error on Verification: " + ex.message, ex)
            false
        }
    }

    private fun setPumpIDString(pumpID: String) {
        val pumpIDBytes = ByteUtil.fromHexString(pumpID)
        if (pumpIDBytes == null || pumpIDBytes.size != 3 || pumpID == "000000") {
            aapsLogger.error("Invalid pump ID: $pumpID")
            rileyLinkServiceData.setPumpID("000000", byteArrayOf(0, 0, 0))
            medtronicPumpStatus.pumpDeviceState = PumpDeviceState.InvalidConfiguration
            return
        }
        val oldId = rileyLinkServiceData.pumpID
        rileyLinkServiceData.setPumpID(pumpID, pumpIDBytes)
        if (oldId != null && oldId != pumpID) {
            medtronicUtil.medtronicPumpModel = MedtronicDeviceType.Medtronic_522 // pumpId change → model probably changed too
            medtronicUtil.isModelSet = false
        }
    }

    private val preferencesBackedLinkPrefs = object : PumpLinkPrefs {
        override var lastGoodFrequencyMHz: Double?
            get() = preferences.get(RileyLinkDoubleKey.LastGoodDeviceFrequency).takeIf { it != 0.0 }
            set(value) {
                preferences.put(RileyLinkDoubleKey.LastGoodDeviceFrequency, value ?: 0.0)
                rileyLinkServiceData.lastGoodFrequency = value
            }
    }
}
