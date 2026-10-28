package app.aaps.pump.apex.connectivity.commands.pump

import app.aaps.pump.apex.interfaces.ApexDeviceInfo
import app.aaps.pump.apex.utils.getDateTime
import app.aaps.pump.apex.utils.getUnsignedShort
import app.aaps.pump.apex.utils.validateDateTime

class AlarmObject(
    val command: PumpCommand,
    val apexDeviceInfo: ApexDeviceInfo
): PumpObjectModel() {
    /** Alarm entry index */
    val index get() = command.objectData[1].toUByte().toInt()

    /** Alarm date */
    val dateTime get() = getDateTime(command.objectData, 2, apexDeviceInfo)

    /** Raw alarm code, as the pump stores it in history. */
    val rawType get() = getUnsignedShort(command.objectData, 8) + 0x100

    /**
     * Alarm type. A code that is not in [Alarm] decodes to [Alarm.Unknown], the same way a live alarm
     * in [StatusV1.alarms] does. The table is reverse engineered and still has Reserved entries, so
     * dropping the whole record for a code we cannot name would hide exactly the faults we know least
     * about.
     */
    val type get() = Alarm.entries.find { it.raw == rawType } ?: Alarm.Unknown

    override fun validate(): String? {
        // valueId + index + 6 date bytes + 2 code bytes. Checked first, else a truncated frame throws
        // out of the field readers instead of naming the problem.
        if (command.objectData.size < 10) return "truncated alarm entry"
        // An unused ring buffer slot: the pump writes the 'no error' code there.
        if (rawType == Alarm.NoError.raw) return EMPTY_SLOT
        if (!validateDateTime(command.objectData, 2, apexDeviceInfo)) return "invalid datetime"
        return null
    }
}