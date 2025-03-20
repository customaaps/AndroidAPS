package app.aaps.pump.apex.connectivity.commands.pump

import app.aaps.pump.apex.utils.getUnsignedShort

class StatusV2(command: PumpCommand): PumpObjectModel() {
    /** Pump-calculated absolute insulin, in 0.025U steps */
    val absoluteInsulin = getUnsignedShort(command.objectData, 2)

    /** Alarm length */
    val alarmLength = AlarmLength.entries.find { it.raw == command.objectData[4] }

    /** Pump battery voltage */
    val batteryVoltage = command.objectData[5].toUByte().toDouble() / 100.0
}
