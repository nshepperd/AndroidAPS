package app.aaps.plugins.main.general.bgMeter

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.bgMeter.events.EventBgMeterStatus
import app.aaps.plugins.main.general.bgMeter.keys.BgMeterIntKey
import app.aaps.plugins.main.general.bgMeter.keys.BgMeterStringKey
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Receives fingerstick readings from a Bluetooth glucose meter (standard Glucose Profile,
 * e.g. Accu-Chek Guide / Guide Me) and records them as BG Check therapy events.
 *
 * Readings are recorded only; they don't calibrate the CGM or feed the loop.
 */
@Singleton
class BgMeterPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    preferences: Preferences,
    private val context: Context,
    private val persistenceLayer: PersistenceLayer,
    private val rxBus: RxBus,
    private val dateUtil: DateUtil
) : PluginBaseWithPreferences(
    pluginDescription = PluginDescription()
        .mainType(PluginType.GENERAL)
        .fragmentClass(BgMeterFragment::class.java.name)
        .pluginIcon(app.aaps.core.objects.R.drawable.ic_cp_bgcheck)
        .pluginName(R.string.bg_meter)
        .shortName(R.string.bg_meter_short)
        .description(R.string.description_bg_meter),
    ownPreferences = listOf(BgMeterStringKey::class.java, BgMeterIntKey::class.java),
    aapsLogger, rh, preferences
) {

    companion object {

        /** Readings timestamped further than this in the future are dropped as bad meter clock data */
        private const val MAX_FUTURE_MS = 5 * 60_000L
        private const val MAX_LOG_LINES = 30
    }

    private var thread: HandlerThread? = null
    private var connection: BgMeterConnection? = null

    var status: String = ""
        private set

    /** Most recent lines first */
    val log = ArrayDeque<String>()

    val meterAddress: String get() = preferences.get(BgMeterStringKey.Address)
    val meterName: String get() = preferences.get(BgMeterStringKey.Name)

    override fun onStart() {
        super.onStart()
        val thread = HandlerThread("BgMeter").also { it.start() }
        this.thread = thread
        connection = BgMeterConnection(context, aapsLogger, Handler(thread.looper), connectionListener)
        ContextCompat.registerReceiver(
            context, bluetoothReceiver,
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            },
            ContextCompat.RECEIVER_EXPORTED // system broadcasts
        )
        startConnection()
    }

    override fun onStop() {
        runCatching { context.unregisterReceiver(bluetoothReceiver) }
        connection?.stop()
        thread?.quitSafely()
        connection = null
        thread = null
        super.onStop()
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun startConnection() {
        val address = meterAddress
        when {
            address.isEmpty() -> setStatus(rh.gs(R.string.bg_meter_status_not_paired))
            !hasPermission()  -> setStatus(rh.gs(R.string.bg_meter_status_no_permission))
            else              -> connection?.start(address)
        }
    }

    /** Remember [device] as our meter and bond with it; Android prompts for the meter's PIN. */
    @SuppressLint("MissingPermission")
    fun pair(device: BluetoothDevice, name: String?) {
        if (device.address != meterAddress) {
            preferences.put(BgMeterStringKey.Address, device.address)
            preferences.put(BgMeterStringKey.Name, name ?: "")
            preferences.put(BgMeterIntKey.LastSequence, BgMeterIntKey.LastSequence.defaultValue)
        }
        addLog("Selected ${name ?: ""} ${device.address}")
        if (device.bondState == BluetoothDevice.BOND_BONDED) startConnection()
        else {
            setStatus(rh.gs(R.string.bg_meter_status_pairing))
            if (!device.createBond()) addLog("Pairing request failed")
        }
    }

    fun forget() {
        connection?.stop()
        preferences.remove(BgMeterStringKey.Address)
        preferences.remove(BgMeterStringKey.Name)
        preferences.remove(BgMeterIntKey.LastSequence)
        addLog("Meter forgotten")
        setStatus(rh.gs(R.string.bg_meter_status_not_paired))
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED       ->
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) connection?.restart()

                BluetoothDevice.ACTION_BOND_STATE_CHANGED ->  {
                    val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                    if (device.address != meterAddress) return
                    when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                        BluetoothDevice.BOND_BONDED -> {
                            addLog("Paired")
                            startConnection()
                        }

                        BluetoothDevice.BOND_NONE   -> {
                            addLog("Not paired (pairing failed or removed)")
                            connection?.restart()
                        }
                    }
                }
            }
        }
    }

    private val connectionListener = object : BgMeterConnection.Listener {
        override fun onStatus(status: String) = setStatus(status)

        override fun requestFromSequence(): Int? {
            val last = preferences.get(BgMeterIntKey.LastSequence)
            return if (last < 0) null else (last + 1) and 0xFFFF
        }

        override fun onMeasurement(measurement: GlucoseProfile.Measurement, clockOffsetMs: Long, zone: ZoneId) {
            record(measurement, clockOffsetMs, zone)
            val last = preferences.get(BgMeterIntKey.LastSequence)
            preferences.put(BgMeterIntKey.LastSequence, max(last, measurement.sequence))
        }

        override fun onTransferFinished(success: Boolean, detail: String) {
            addLog(if (success) "Sync done ($detail)" else "Sync incomplete ($detail)")
        }
    }

    private fun record(m: GlucoseProfile.Measurement, clockOffsetMs: Long, zone: ZoneId) {
        if (m.isControlSolution) {
            addLog("#${m.sequence}: control solution, skipped")
            return
        }
        if (m.mgdl == null || m.mmol == null) {
            addLog("#${m.sequence}: no value, skipped")
            return
        }
        val timestamp = m.time.atZone(zone).toInstant().toEpochMilli() - clockOffsetMs
        if (timestamp > dateUtil.now() + MAX_FUTURE_MS) {
            addLog("#${m.sequence}: timestamp ${dateUtil.dateAndTimeString(timestamp)} is in the future, skipped")
            return
        }
        val unit = if (m.nativeMmol) GlucoseUnit.MMOL else GlucoseUnit.MGDL
        val value = if (m.nativeMmol) (m.mmol * 10).roundToInt() / 10.0 else m.mgdl.roundToInt().toDouble()
        val note = buildString {
            append(meterName.ifEmpty { rh.gs(R.string.bg_meter) })
            if (m.isAboveRange) append(" HI")
            if (m.isBelowRange) append(" LO")
        }
        val te = TE(
            timestamp = timestamp,
            type = TE.Type.FINGER_STICK_BG_VALUE,
            note = note,
            enteredBy = "AndroidAPS",
            glucose = value,
            glucoseType = TE.MeterType.FINGER,
            glucoseUnit = unit
        )
        val inserted = persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = te,
            timestamp = timestamp,
            action = Action.CAREPORTAL,
            source = Sources.BgCheck,
            note = note,
            listValues = listOf(
                ValueWithUnit.Timestamp(timestamp),
                ValueWithUnit.TEType(TE.Type.FINGER_STICK_BG_VALUE),
                if (unit == GlucoseUnit.MMOL) ValueWithUnit.Mmoll(value) else ValueWithUnit.Mgdl(value)
            )
        ).blockingGet().inserted.isNotEmpty()
        val text = "${dateUtil.dateAndTimeString(timestamp)}  $value ${unit.asText}"
        addLog("#${m.sequence}: $text${if (inserted) "" else " (already recorded)"}")
        aapsLogger.debug(LTag.BGSOURCE, "BgMeter: #${m.sequence} $text inserted=$inserted")
    }

    private fun setStatus(status: String) {
        this.status = status
        rxBus.send(EventBgMeterStatus())
    }

    private fun addLog(line: String) {
        synchronized(log) {
            log.addFirst("${dateUtil.timeString()}  $line")
            while (log.size > MAX_LOG_LINES) log.removeLast()
        }
        rxBus.send(EventBgMeterStatus())
    }
}
