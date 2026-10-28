package app.aaps.pump.apex.utils.keys

import app.aaps.core.keys.UnitType
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.ElementVisibility
import app.aaps.pump.apex.R

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey

enum class ApexDoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val min: Double,
    override val max: Double,
    override val defaultedBySM: Boolean = false,
    override val calculatedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true
): DoublePreferenceKey {
    MaxBasal("apex_max_basal", 0.0, 0.0, 35.0),
    MaxBolus("apex_max_bolus", 0.0, 0.0, 25.0),
    BatteryLowVoltage("apex_low_batt_vtg", 1.2, 1.0, 1.8),
    BatteryHighVoltage("apex_high_batt_vtg", 1.5, 1.0, 1.8);

    // These are decimal editors, including the pump's fractional insulin limits.
    // NONE rounds the voltage to whole volts and gives a 1 V slider step.
    override val unitType: UnitType = UnitType.DOUBLE_2

    override val title: TextRef
        get() = when (this) {
            MaxBasal -> TextRef.AndroidRes(R.string.setting_max_basal)
            MaxBolus -> TextRef.AndroidRes(R.string.setting_max_bolus)
            BatteryLowVoltage -> TextRef.AndroidRes(R.string.setting_calc_battery_low_vtg)
            BatteryHighVoltage -> TextRef.AndroidRes(R.string.setting_calc_battery_high_vtg)
        }

    override val visibility: ElementVisibility
        get() = when (this) {
            BatteryLowVoltage, BatteryHighVoltage -> ElementVisibility {
                it.preferences.get(ApexBooleanKey.CalculateBatteryPercentage) &&
                    it.preferences.get(ApexStringKey.CalcBatteryType) == app.aaps.pump.apex.misc.BatteryType.Custom.name
            }
            else -> ElementVisibility.ALWAYS
        }
}
