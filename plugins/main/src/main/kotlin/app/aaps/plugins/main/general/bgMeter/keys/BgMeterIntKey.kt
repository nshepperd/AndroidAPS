package app.aaps.plugins.main.general.bgMeter.keys

import app.aaps.core.keys.interfaces.IntNonPreferenceKey

enum class BgMeterIntKey(
    override val key: String,
    override val defaultValue: Int,
    override val exportable: Boolean = true
) : IntNonPreferenceKey {

    /** Highest record sequence number already imported from the meter, -1 = none yet */
    LastSequence("bg_meter_last_sequence", -1),
}
