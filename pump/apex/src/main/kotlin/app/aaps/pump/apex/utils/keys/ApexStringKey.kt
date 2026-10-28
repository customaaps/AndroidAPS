package app.aaps.pump.apex.utils.keys

import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.ElementVisibility
import app.aaps.core.keys.interfaces.StringValidator
import app.aaps.pump.apex.R

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.pump.apex.connectivity.FirmwareVersion
import app.aaps.pump.apex.connectivity.commands.pump.AlarmLength
import app.aaps.pump.apex.connectivity.commands.pump.BolusDeliverySpeed
import app.aaps.pump.apex.misc.BatteryType

enum class ApexStringKey(
    override val key: String,
    override val defaultValue: String,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false,
    override val exportable: Boolean = true
) : StringPreferenceKey {
    // Empty means not configured; the UI and scan path both validate eight ASCII digits.
    SerialNumber("apex_serial_number", ""),

    // Keep readable/exportable, like the source driver: this pump has no password recovery.
    PumpPassword("apex_pump_password", "000000"),

    // Device identity and unfinished bolus state never travel to a different phone in an export.
    LastConnectedSerialNumber("apex_last_connected_serial_number", "", exportable = false),
    BluetoothAddress("apex_bt_address", "", exportable = false),

    PumpIdFormatCutover("apex_pumpid_format_cutover", "", exportable = false),

    PendingBolusSync("apex_pending_bolus_sync", "", exportable = false),

    AlarmSoundLength("apex_alarm_length", AlarmLength.Short.name),
    BolusSpeed("apex_bolus_speed", BolusDeliverySpeed.Standard.name),
    CalcBatteryType("apex_battery_type", BatteryType.Custom.name),
    FirmwareVer("apex_fw_ver", FirmwareVersion.AUTO.name);

    override val title: TextRef
        get() = when (this) {
            SerialNumber -> TextRef.AndroidRes(R.string.setting_serial_number)
            PumpPassword -> TextRef.AndroidRes(R.string.setting_pump_password)
            AlarmSoundLength -> TextRef.AndroidRes(R.string.setting_alarm_length)
            BolusSpeed -> TextRef.AndroidRes(R.string.setting_bolus_speed)
            CalcBatteryType -> TextRef.AndroidRes(R.string.setting_calc_battery_type_title)
            FirmwareVer -> TextRef.AndroidRes(R.string.firmware_version)
            else -> TextRef.Literal(key)
        }

    override val summary: TextRef?
        get() = if (this == PumpPassword) TextRef.AndroidRes(R.string.setting_pump_password_summary) else null

    override val validator: StringValidator
        get() = when (this) {
            SerialNumber -> StringValidator.regex("^[0-9]{8}$", "Must be 8 digits")
            PumpPassword -> StringValidator.regexRequired("^[0-9]{6}$", "Must be exactly 6 digits")
            else -> StringValidator.NONE
        }

    override val visibility: ElementVisibility
        get() = when (this) {
            PumpPassword -> ElementVisibility { it.preferences.get(ApexBooleanKey.PumpHasPassword) }
            CalcBatteryType -> ElementVisibility { it.preferences.get(ApexBooleanKey.CalculateBatteryPercentage) }
            else -> ElementVisibility.ALWAYS
        }

    override val entries: Map<String, TextRef>
        get() = when (this) {
            AlarmSoundLength -> linkedMapOf(
                AlarmLength.Long.name to TextRef.AndroidRes(R.string.setting_alarm_length_long),
                AlarmLength.Medium.name to TextRef.AndroidRes(R.string.setting_alarm_length_medium),
                AlarmLength.Short.name to TextRef.AndroidRes(R.string.setting_alarm_length_short)
            )
            BolusSpeed -> linkedMapOf(
                BolusDeliverySpeed.Standard.name to TextRef.AndroidRes(R.string.setting_bolus_speed_standard),
                BolusDeliverySpeed.Low.name to TextRef.AndroidRes(R.string.setting_bolus_speed_low)
            )
            CalcBatteryType -> linkedMapOf(
                BatteryType.Alkaline.name to TextRef.AndroidRes(R.string.setting_calc_battery_type_alkaline),
                BatteryType.Lithium.name to TextRef.AndroidRes(R.string.setting_calc_battery_type_lithium),
                BatteryType.NiMh.name to TextRef.AndroidRes(R.string.setting_calc_battery_type_ni_mh),
                BatteryType.NiZn.name to TextRef.AndroidRes(R.string.setting_calc_battery_type_ni_zn),
                BatteryType.Custom.name to TextRef.AndroidRes(R.string.setting_calc_battery_type_custom)
            )
            else -> emptyMap()
        }
}
