package app.aaps.pump.medtronic.dialog

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.medtronic.MedtronicPumpPlugin
import app.aaps.pump.medtronic.R
import app.aaps.pump.rileylink2.link.SignalHistory
import com.jjoe64.graphview.GraphView
import com.jjoe64.graphview.helper.DateAsXAxisLabelFormatter
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import com.jjoe64.graphview.series.PointsGraphSeries
import dagger.android.DaggerActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Live RSSI history for the rileylink2 stack: phone↔OrangeLink BLE signal and
 * OrangeLink↔pump radio signal on one dBm axis, plus pump-silent and link-event markers.
 * Reads MedtronicPumpLinkService.signalHistory; refreshes itself while visible.
 */
class MedtronicSignalHistoryActivity : DaggerActivity() {

    @Inject lateinit var medtronicPumpPlugin: MedtronicPumpPlugin
    @Inject lateinit var rh: ResourceHelper

    private lateinit var graph: GraphView
    private lateinit var statusView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshGraph()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.medtronic_signal_history)
        title = rh.gs(R.string.medtronic_signal_history)
        graph = findViewById(R.id.signal_graph)
        statusView = findViewById(R.id.signal_status)

        graph.gridLabelRenderer.labelFormatter =
            DateAsXAxisLabelFormatter(this, SimpleDateFormat("HH:mm", Locale.getDefault()))
        graph.gridLabelRenderer.numHorizontalLabels = 5
        graph.legendRenderer.isVisible = true
        graph.legendRenderer.align = com.jjoe64.graphview.LegendRenderer.LegendAlign.TOP
        graph.viewport.isYAxisBoundsManual = true
        graph.viewport.setMinY(FLOOR_DBM.toDouble())
        graph.viewport.setMaxY(CEIL_DBM.toDouble())
        graph.viewport.isXAxisBoundsManual = true
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun refreshGraph() {
        val history = medtronicPumpPlugin.medtronicService?.signalHistory
        if (history == null) {
            statusView.text = rh.gs(R.string.medtronic_signal_no_data)
            return
        }
        val now = System.currentTimeMillis()
        val windowStart = now - WINDOW_MS
        val ble = history.bleSnapshot().filter { it.timeMs >= windowStart }
        val pump = history.pumpSnapshot().filter { it.timeMs >= windowStart }
        val marks = history.stateSnapshot().filter { it.timeMs >= windowStart }

        graph.removeAllSeries()
        graph.viewport.setMinX(windowStart.toDouble())
        graph.viewport.setMaxX(now.toDouble())

        if (ble.isNotEmpty()) {
            val series = LineGraphSeries(ble.map { DataPoint(it.timeMs.toDouble(), it.dbm.toDouble()) }.toTypedArray())
            series.color = COLOR_BLE
            series.isDrawDataPoints = true
            series.dataPointsRadius = 4f
            series.title = rh.gs(R.string.medtronic_signal_series_ble)
            graph.addSeries(series)
        }

        val heard = pump.filter { it.dbm != null }
        if (heard.isNotEmpty()) {
            val series = LineGraphSeries(heard.map { DataPoint(it.timeMs.toDouble(), it.dbm!!.toDouble()) }.toTypedArray())
            series.color = COLOR_PUMP
            series.isDrawDataPoints = true
            series.dataPointsRadius = 4f
            series.title = rh.gs(R.string.medtronic_signal_series_pump)
            graph.addSeries(series)
        }

        val silent = pump.filter { it.kind == SignalHistory.PumpSampleKind.SILENT }
        if (silent.isNotEmpty()) {
            val series = PointsGraphSeries(silent.map { DataPoint(it.timeMs.toDouble(), SILENT_ROW_DBM.toDouble()) }.toTypedArray())
            series.color = COLOR_SILENT
            series.size = 6f
            series.title = rh.gs(R.string.medtronic_signal_series_silent)
            graph.addSeries(series)
        }

        val trouble = marks.filter { it.state.startsWith("LinkDown") || it.state.startsWith("PumpUnreachable") }
        if (trouble.isNotEmpty()) {
            val series = PointsGraphSeries(trouble.map { DataPoint(it.timeMs.toDouble(), EVENT_ROW_DBM.toDouble()) }.toTypedArray())
            series.color = COLOR_EVENT
            series.shape = PointsGraphSeries.Shape.TRIANGLE
            series.size = 8f
            series.title = rh.gs(R.string.medtronic_signal_series_events)
            graph.addSeries(series)
        }

        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val lastBle = ble.lastOrNull()
        val lastHeard = heard.lastOrNull()
        statusView.text = rh.gs(
            R.string.medtronic_signal_status,
            lastBle?.let { "${it.dbm} dBm @ ${timeFormat.format(Date(it.timeMs))}" } ?: "—",
            lastHeard?.let { "${it.dbm} dBm @ ${timeFormat.format(Date(it.timeMs))}" } ?: "—",
        )
    }

    companion object {

        private const val REFRESH_MS = 5_000L
        private const val WINDOW_MS = 3 * 60 * 60 * 1000L
        private const val FLOOR_DBM = -112
        private const val CEIL_DBM = -30
        private const val SILENT_ROW_DBM = -108
        private const val EVENT_ROW_DBM = -34
        private val COLOR_BLE = Color.rgb(79, 195, 247)     // light blue
        private val COLOR_PUMP = Color.rgb(255, 183, 77)    // orange
        private val COLOR_SILENT = Color.rgb(239, 83, 80)   // red
        private val COLOR_EVENT = Color.rgb(179, 157, 219)  // purple
    }
}
