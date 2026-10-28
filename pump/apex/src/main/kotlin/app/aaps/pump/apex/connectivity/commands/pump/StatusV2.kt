package app.aaps.pump.apex.connectivity.commands.pump

import app.aaps.pump.apex.utils.getUnsignedShort

class StatusV2(val command: PumpCommand): PumpObjectModel() {
    /** Pump-calculated absolute insulin, in 0.025U steps */
    val absoluteInsulin get() = getUnsignedShort(command.objectData, 2)

    /** Alarm length */
    val alarmLength get() = AlarmLength.entries.find { it.raw == command.objectData[4] }

    /** Pump battery voltage */
    val batteryVoltage get() = command.objectData[5].toUByte().toDouble() / 100.0

    /** Whether audio-bolus is enabled, or null if the firmware does not report it */
    val audioBolusEnabled: Boolean? get() =
        if (command.objectData.size <= 6) null else command.objectData[6].toInt() != 0

    /** Audio-bolus step (raw units), or null if the firmware does not report it */
    val audioBolusStep: Int? get() =
        if (command.objectData.size > 7) command.objectData[7].toUByte().toInt() else null

    override fun validate(): String? {
        if (alarmLength == null) return "alarmLength invalid"
        return null
    }
}
