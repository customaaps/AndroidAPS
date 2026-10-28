package app.aaps.pump.apex.utils.keys

import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.apex.R

import app.aaps.core.keys.interfaces.BooleanPreferenceKey

enum class ApexBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val calculatedDefaultValue: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true
) : BooleanPreferenceKey {
    LogInsulinChange("apex_log_insulin_change", true, defaultedBySM = true),
    LogBatteryChange("apex_log_battery_change", true, defaultedBySM = true),
    CalculateBatteryPercentage("apex_calc_battery_percentage", true, defaultedBySM = true),
    HideSerial("apex_hide_serial", true, defaultedBySM = true),

    // Diagnostic only; unknown fill-history layout must not create therapy records.
    ReadFillHistory(
        "apex_read_fill_history", false,
        engineeringModeOnly = true,
    ),

    PumpHasPassword("apex_pump_has_password", false);

    override val title: TextRef
        get() = when (this) {
            LogInsulinChange -> TextRef.AndroidRes(R.string.setting_log_insulin_change)
            LogBatteryChange -> TextRef.AndroidRes(R.string.setting_log_battery_change)
            CalculateBatteryPercentage -> TextRef.AndroidRes(R.string.setting_calc_battery_title)
            HideSerial -> TextRef.AndroidRes(R.string.setting_hide_serial)
            ReadFillHistory -> TextRef.AndroidRes(R.string.setting_read_fill_history)
            PumpHasPassword -> TextRef.AndroidRes(R.string.setting_pump_has_password)
        }

    override val summary: TextRef?
        get() = when (this) {
            CalculateBatteryPercentage -> TextRef.AndroidRes(R.string.setting_calc_battery_summary)
            ReadFillHistory -> TextRef.AndroidRes(R.string.setting_read_fill_history_summary)
            PumpHasPassword -> TextRef.AndroidRes(R.string.setting_pump_has_password_summary)
            else -> null
        }
}
