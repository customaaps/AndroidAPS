package app.aaps.pump.apex.connectivity.commands.pump

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.connectivity.commands.CommandId
import java.util.Locale

enum class PumpObject(
    val commandId: CommandId = CommandId.GetValue,
    val valueId: List<Int>? = null,
) {
    Heartbeat(commandId = CommandId.Heartbeat),
    CommandResponse(commandId = CommandId.SetValue),
    StatusV1(valueId = listOf(0x00)),
    StatusV2(valueId = listOf(0x0C)),
    WizardStatus(valueId = listOf(0x07)),
    BasalProfile(valueId = listOf(0x08)),
    AlarmEntry(valueId = listOf(0x03)),

    /**
     * Fill history. The id is matched across BOTH request groups (0x35 and 0x55) - [findObject] never
     * sees the group byte - so do not add another object on value id 0x04 assuming the groups are
     * separate namespaces.
     */
    FillEntry(valueId = listOf(0x04)),
    TDDEntry(valueId = listOf(0x06)),
    BolusEntry(valueId = listOf(0x21, 0x01)),
    FirmwareEntry(valueId = listOf(0x31));

    companion object {
        fun findObject(commandId: CommandId, objectData: ByteArray, aapsLogger: AAPSLogger? = null): PumpObject? {
            // Unsigned. Every id declared here is below 0x80, so masking changes no match that already
            // works - but a Byte sign-extends, and the pump's own "no such record" answer, 0xFF, came
            // out of here as -1 and was logged as "0x-1". That log line is now the first thing read
            // when a new value id is being mapped, so it has to name the id the pump actually sent.
            val valueId = objectData[0].toInt() and 0xFF
            for (e in entries) {
                if (commandId != e.commandId) continue
                if (e.valueId == null) return e
                if (!e.valueId.contains(valueId)) continue
                return e
            }
            aapsLogger?.debug(LTag.PUMPBTCOMM, "Object [${commandId.name}:0x${valueId.toString(16)}] not found")
            return null
        }
    }
}

abstract class PumpObjectModel {
    open fun validate(): String? = null

    companion object {

        /**
         * [validate] result for an unused history ring-buffer slot. Distinguished from a real
         * validation failure so the caller can skip it quietly instead of logging an error and
         * raising an "invalid object data" notification — an unused slot is normal on a pump that
         * has little or no history.
         */
        const val EMPTY_SLOT = "empty_slot"
    }
}

enum class BatteryLevel(val raw: Byte, val approximatePercentage: Int) {
    Dead(0, 0),
    Low(1, 25),
    Medium(2, 50),
    High(3, 75),
    Full(4, 100),
}

enum class Language(val raw: Byte) {
    Russian(0),
    English(1),
}

enum class ScreenBrightness(val raw: Byte) {
    P10(0),
    P30(1),
    P50(2),
    P60(3),
    P80(4),
    P100(5),
}

enum class AlarmType(val raw: Byte) {
    Sound(0),
    Vibration(1),
    VibrationAndSound(2),
}

enum class AlarmLength(val raw: Byte) {
    Long(0),
    Medium(1),
    Short(2),
}

enum class BolusDeliverySpeed(val raw: Byte) {
    Standard(0),
    Low(1),
}

enum class Alarm(val raw: Int) {
    Unknown(0xBADC0DE),
    NoError(0x100),
    LowBattery(0x101),
    CheckGlucose(0x102),
    ButtonError(0x103),
    LowReservoir(0x104),
    DeadBattery(0x105),
    BatteryError(0x106),
    TimeError(0x107),
    NoDelivery(0x108),
    ResetError(0x109),
    CommunicationError(0x10a),
    MotorError(0x10b),
    EncoderError(0x10c),

    /**
     * The cartridge has run out. The pump maker's own dictionary calls this one `REVEMPTY` and shows
     * "Empty Reservoir, Delivery Stopped, Change reservoir" for it, so it is a spent cartridge, not a
     * blocked line - [NoDelivery] (0x108) is the blockage. The old name `NoDosage` came from the
     * driver this port started from and had the two pooled into one "Occlusion" text, which sent the
     * user to change the infusion set while the pump was asking for a new cartridge. Seen for real on
     * 2026-08-31, when a pump stopped on this code with 5.00 U left.
     */
    ReservoirEmpty(0x10d),
    TDDLimitTriggered(0x10e),

    /** No cartridge fitted at all. The maker's `alarm_15` is literally "No reservoir". */
    NoReservoir(0x10f),
    ScreenError(0x201),
    FRAMError(0x202),
    TimeAnomalyError(0x203),
    ClockError(0x204),
    Reserved1(0x205),
    MotorAbnormal(0x206),
    MotorPowerAbnormal(0x207),
    BolusOrBasalDoseAbnormal(0x208),
    ConnectionAnomaly(0x209),
    Reserved2(0x20a),
    PressureAbnormal(0x20b),
}

/**
 * One alarm slot as the pump sent it: the decoded [Alarm] plus the raw code it came from.
 *
 * The number cannot live inside [Alarm]. Enum entries are single shared objects, so every
 * unrecognised code would have to write its number into the one `Unknown` entry and the next status
 * frame would overwrite it. Keeping it beside the decoded value also keeps equality right:
 * `ApexService.newAlarms` subtracts whole entries, so two different unrecognised codes are two
 * different alarms, while the same code seen twice stays one repeated alarm.
 */
data class PumpAlarm(val type: Alarm, val raw: Int = type.raw) {

    /** Raw pump code as text for the log and for the "report to developers" message, like `0x20B`. */
    val code: String get() = String.format(Locale.ENGLISH, "0x%X", raw)

    /**
     * The code the pump prints on its own screen, like `E23`, or null when the maker's texts do not
     * give this alarm one.
     *
     * SECOND-FAMILY CODES ONLY. The maker's own dispatcher settles it: for an alarm word `i` it picks
     * the text with `((0xFF00 and i) shr 8) == 1 ? alarmType(i and 255) : realtimeAlarmArr[(i and 255) - 1]`
     * - the first family, 0x1XX, gets a plain name from `alarmArr` (`LOWBATTER`, `NODELIVER`, ...)
     * and the second, 0x2XX, gets an E-numbered text from `realtimeAlarmArr` ("E13 Error Screen
     * abnormality", ...). Their alarm dialog carries the same E-numbers in the other wording,
     * "Error Code:E13". So an E-number names a second-family fault and nothing else.
     *
     * This is easy to get backwards, and was: their resource names are byte-swapped. `WarningDialog`
     * looks its string up by `byte2IntRL(highByte, lowByte)`, which turns pump word 0x0108 into the
     * name `alarms_0801` - so in `alarms_NNVV` it is VV that is the family and NN the alarm. Read the
     * other way round, `alarms_0802` ("Error Code:E20") looks like it belongs to 0x108 "Pipe
     * blockage", and every common alarm ends up labelled with another fault's live E-number.
     *
     * Only codes a maker's string confirms are listed. The rule behind them is `E = 12 + low byte`,
     * and the two codes it would predict but no text carries - E17 for 0x205 and E22 for 0x20A - are
     * exactly the two this driver knows as Reserved, which is what makes the rule believable. They
     * stay absent all the same: an E-number no pump has been seen to print is not worth putting on a
     * user's screen.
     */
    val screenCode: String? get() = CONFIRMED_SCREEN_CODES[raw]?.let { "E$it" }

    companion object {

        /** Decode one alarm slot. An unknown code keeps its number beside [Alarm.Unknown]. */
        fun of(raw: Int) = PumpAlarm(Alarm.entries.find { it.raw == raw } ?: Alarm.Unknown, raw)

        /** Full alarm code to the E-number the pump shows, exactly as the maker's texts state it. */
        private val CONFIRMED_SCREEN_CODES = mapOf(
            0x201 to 13, 0x202 to 14, 0x203 to 15, 0x204 to 16,
            0x206 to 18, 0x207 to 19, 0x208 to 20, 0x209 to 21, 0x20B to 23,
        )
    }
}
