package app.aaps.plugins.main.general.bgMeter.keys

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

enum class BgMeterStringKey(
    override val key: String,
    override val defaultValue: String,
    override val exportable: Boolean = true
) : StringNonPreferenceKey {

    Address("bg_meter_address", ""),
    Name("bg_meter_name", ""),
}
