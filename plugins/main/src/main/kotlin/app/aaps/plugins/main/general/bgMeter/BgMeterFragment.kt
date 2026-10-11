package app.aaps.plugins.main.general.bgMeter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import app.aaps.core.interfaces.pump.BlePreCheck
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.plugins.main.R
import app.aaps.plugins.main.databinding.BgMeterFragmentBinding
import app.aaps.plugins.main.general.bgMeter.events.EventBgMeterStatus
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject

@SuppressLint("MissingPermission") // BlePreCheck asks for it before any scan
class BgMeterFragment : DaggerFragment() {

    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var blePreCheck: BlePreCheck
    @Inject lateinit var bgMeterPlugin: BgMeterPlugin

    companion object {

        private const val SCAN_DURATION_MS = 30_000L
    }

    private val disposable = CompositeDisposable()
    private val handler = Handler(Looper.getMainLooper())
    private var scanDialog: AlertDialog? = null

    private var _binding: BgMeterFragmentBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        BgMeterFragmentBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.pair.setOnClickListener { startScan() }
        binding.forget.setOnClickListener {
            activity?.let { OKDialog.showConfirmation(it, rh.gs(R.string.bg_meter_forget_confirm)) { bgMeterPlugin.forget() } }
        }
    }

    override fun onResume() {
        super.onResume()
        disposable += rxBus
            .toObservable(EventBgMeterStatus::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGui() }, fabricPrivacy::logException)
        updateGui()
    }

    override fun onPause() {
        super.onPause()
        disposable.clear()
        stopScan()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateGui() {
        val binding = _binding ?: return
        val address = bgMeterPlugin.meterAddress
        binding.meter.text =
            if (address.isEmpty()) rh.gs(R.string.bg_meter_none)
            else "${bgMeterPlugin.meterName.ifEmpty { rh.gs(R.string.bg_meter) }}  $address"
        binding.status.text = bgMeterPlugin.status
        binding.forget.isEnabled = address.isNotEmpty()
        binding.log.text = synchronized(bgMeterPlugin.log) { bgMeterPlugin.log.joinToString("\n") }
    }

    // ---------- scanning ----------

    private class Found(val device: BluetoothDevice, val name: String?) {

        override fun toString() = "${name ?: "?"}\n${device.address}"
    }

    private var found = mutableListOf<Found>()
    private var adapter: ArrayAdapter<Found>? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid }
            val name = result.scanRecord?.deviceName ?: result.device.name
            if (GlucoseProfile.GLUCOSE_SERVICE !in uuids && !looksLikeMeter(name)) return
            if (found.any { it.device.address == result.device.address }) return
            found.add(Found(result.device, name))
            adapter?.notifyDataSetChanged()
        }
    }

    private fun looksLikeMeter(name: String?) =
        name != null && listOf("meter", "accu", "guide", "contour").any { name.contains(it, ignoreCase = true) }

    private fun startScan() {
        val activity = activity as? AppCompatActivity ?: return
        if (!blePreCheck.prerequisitesCheck(activity)) return
        val bluetooth = (activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        val scanner = bluetooth.bluetoothLeScanner ?: return

        // Already-paired meters show up even when not advertising
        found = bluetooth.bondedDevices.filter { looksLikeMeter(it.name) }.map { Found(it, it.name) }.toMutableList()
        adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, found)
        scanDialog = MaterialAlertDialogBuilder(activity, app.aaps.core.ui.R.style.DialogTheme)
            .setTitle(R.string.bg_meter_scanning)
            .setAdapter(adapter) { _, which -> found.getOrNull(which)?.let { bgMeterPlugin.pair(it.device, it.name) } }
            .setNegativeButton(app.aaps.core.ui.R.string.cancel, null)
            .setOnDismissListener { stopScan() }
            .show()
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        handler.postDelayed({ stopScanner() }, SCAN_DURATION_MS)
    }

    private fun stopScanner() {
        handler.removeCallbacksAndMessages(null)
        runCatching {
            (context?.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
    }

    private fun stopScan() {
        stopScanner()
        adapter = null
        scanDialog?.let { scanDialog = null; it.dismiss() }
    }
}
