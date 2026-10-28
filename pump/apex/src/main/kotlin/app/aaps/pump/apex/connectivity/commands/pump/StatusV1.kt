package app.aaps.pump.apex.connectivity.commands.pump

import app.aaps.pump.apex.connectivity.commands.device.UpdateSettingsV1
import app.aaps.pump.apex.interfaces.ApexDeviceInfo
import app.aaps.pump.apex.utils.getDateTime
import app.aaps.pump.apex.utils.getUnsignedInt
import app.aaps.pump.apex.utils.getUnsignedShort
import app.aaps.pump.apex.utils.toBoolean
import app.aaps.pump.apex.utils.validateDateTime
import app.aaps.pump.apex.utils.validateInt

class StatusV1(
    val command: PumpCommand,
    val apexDeviceInfo: ApexDeviceInfo
): PumpObjectModel() {
    /** Pump approximate battery level */
    val batteryLevel get() = BatteryLevel.entries.find { it.raw == command.objectData[2] }

    /** Alarm type */
    val alarmType get() = AlarmType.entries.find { it.raw == command.objectData[3] }

    /** Bolus delivery speed */
    val deliverySpeed get() = BolusDeliverySpeed.entries.find { it.raw == command.objectData[4] }

    /** Screen brightness */
    val brightness get() = ScreenBrightness.entries.find { it.raw == command.objectData[5] }

    private val bolusFlags get() = command.objectData[6].toUByte().toInt()
    private enum class BolusFlags(val raw: Int) {
        AdvancedBolusEnabled(1 shl 1),
        BGReminderEnabled(1 shl 2),
    }

    /** Are dual and extended bolus types enabled? */
    val advancedBolusEnabled get() = (bolusFlags and BolusFlags.AdvancedBolusEnabled.raw) != 0

    /** Is BG reminder alarm enabled? */
    val bgReminderEnabled get() = (bolusFlags and BolusFlags.BGReminderEnabled.raw) != 0

    /** Keys lock enabled? */
    val keyboardLockEnabled get() = command.objectData[7].toBoolean()

    /** Pump auto-suspend enabled? */
    val autoSuspendEnabled get() = command.objectData[8].toBoolean()

    /**
     * Time for auto-suspend to trigger, in WHOLE HOURS (1..24).
     *
     * The maker's own app clamps this field to 1..24, offers it on a 1..24 picker and prints it as
     * hours. The "30 minute steps" this used to claim was wrong, and it mattered: the settings write
     * put a literal 1 in the same slot, which reads as one hour, not thirty minutes.
     */
    val autoSuspendDuration get() = command.objectData[9].toUByte().toInt()

    /** Low reservoir alarm threshold in 1U steps */
    val lowReservoirThreshold get() = command.objectData[10].toUByte().toInt()

    /**
     * Low reservoir alarm (triggered by time left) threshold in 30 minute steps.
     *
     * Read and written back with the other settings, and acted on by nothing. Not an oversight to
     * fix by wiring it up: the pump appears not to raise this alarm anywhere AAPS can see. On
     * 2026-08-31 this read 4 - two hours - in all 390 status frames of the day, and the pump's whole
     * stored alarm history, 66 records back to 2026-05-05, holds no low-reservoir alarm at all. A
     * time-left warning that AAPS can rely on would have to be computed here from the level and the
     * rate, not taken from the pump.
     */
    val lowReservoirTimeLeftThreshold get() = command.objectData[11].toUByte().toInt()

    /** Is using preset basal pattern? */
    val isDefaultBasal get() = command.objectData[12].toBoolean()

    /** Is pump locked? */
    val isLocked get() = command.objectData[13].toBoolean()

    /** Current basal pattern index */
    val currentBasalPattern get() = command.objectData[14].toUByte().toInt()

    /** Is TDD limit enabled? */
    val totalDailyDoseLimitEnabled get() = command.objectData[15].toBoolean()

    /** Screen disable timeout, in 0.1s steps */
    val screenTimeout get() = getUnsignedShort(command.objectData, 16)

    /** Current TDD */
    val totalDailyDose get() = getUnsignedInt(command.objectData, 18)

    /** TDD alarm threshold */
    val maxTDD get() = getUnsignedInt(command.objectData, 22)

    /** Maximum basal rate in 0.025U steps */
    val maxBasal get() = getUnsignedShort(command.objectData, 26)

    /** Maximum bolus in 0.025U steps */
    val maxBolus get() = getUnsignedShort(command.objectData, 28)

    /** Bolus preset: Breakfast A 5:00-7:00 */
    val presetBreakfastA get() = getUnsignedShort(command.objectData, 30)

    /** Bolus preset: Breakfast B 7:00-10:00 */
    val presetBreakfastB get() = getUnsignedShort(command.objectData, 32)

    /** Bolus preset: Dinner A 10:00-12:00 */
    val presetDinnerA get() = getUnsignedShort(command.objectData, 34)

    /** Bolus preset: Dinner B 12:00-15:00 */
    val presetDinnerB get() = getUnsignedShort(command.objectData, 36)

    /** Bolus preset: Supper A 15:00-18:00 */
    val presetSupperA get() = getUnsignedShort(command.objectData, 38)

    /** Bolus preset: Supper B 18:00-22:00 */
    val presetSupperB get() = getUnsignedShort(command.objectData, 40)

    /** Bolus preset: Night A 22:00-0:00 */
    val presetNightA get() = getUnsignedShort(command.objectData, 42)

    /** Bolus preset: Night B 0:00-5:00 */
    val presetNightB get() = getUnsignedShort(command.objectData, 44)

    /** System date and time */
    val dateTime get() = getDateTime(command.objectData, 46, apexDeviceInfo, alwaysNonHex = true)

    /** System language */
    val language get() = Language.entries.find { it.raw == command.objectData[52] }

    /** Is temporary basal active? Protocol 0/1. Other byte values are not mapped here. */
    val isTemporaryBasalActive get() = command.objectData[53].toBoolean()

    /** Raw StatusV1 byte 53. Only 0 and 1 are valid; `decodeStatusTbr` rejects the rest. */
    val temporaryBasalActiveRaw get() = command.objectData[53].toUByte().toInt()

    /** Reservoir level, last 3 numbers are decimals */
    val reservoir get() = getUnsignedInt(command.objectData, 54)

    /**
     * Current alarms list.
     *
     * Ten slots, bytes 58..77. The official app allocates ten and reads the same offsets, and byte 78
     * is [currentBasalRate] in both, so the block ends there. AAPS read nine until now, so an alarm
     * parked in the last slot was invisible; the two extra bytes are zero in every one of the 374
     * captured frames, like the eight slots before them.
     */
    val alarms get() = buildList {
        for (i in 0..<10) {
            val raw = getUnsignedShort(command.objectData, 58 + 2 * i)
            // NoError is skipped like an empty slot: it is the pump's own way of saying the slot holds
            // nothing, and letting it through made the driver treat "no alarm" as an alarm that stands.
            if (raw != 0 && raw != Alarm.NoError.raw) add(PumpAlarm.of(raw))
        }
    }

    /** Current basal rate in 0.025U steps */
    val currentBasalRate get() = getUnsignedShort(command.objectData, 78)

    // Bytes 80 and 81 were decoded as the end time of the current basal rate. They read 00:00 in
    // every one of 452 frames captured over 9 hours on firmware 1.1 / protocol 4.12, across five
    // different basal rates and five profile steps on the hour - exactly where a real end time
    // could not have been zero. Nothing read the value. Decoding them again needs a capture that
    // actually shows them change, not another guess at what they mean.

    /** TBR if present */
    val temporaryBasalRate get() = getUnsignedShort(command.objectData, 82)

    /** Is TBR absolute? Protocol 0/1. Other byte values are not mapped here. */
    val temporaryBasalRateIsAbsolute get() = command.objectData[84].toBoolean()

    /** Raw StatusV1 byte 84. Only 0 and 1 are valid; `decodeStatusTbr` rejects the rest. */
    val temporaryBasalRateIsAbsoluteRaw get() = command.objectData[84].toUByte().toInt()

    /** TBR duration, in 1 minute steps */
    val temporaryBasalRateDuration get() = getUnsignedShort(command.objectData, 86)

    /** TBR elapsed time, in 1 minute steps */
    val temporaryBasalRateElapsed get() = getUnsignedShort(command.objectData, 88)

    override fun validate(): String? {
        if (batteryLevel == null) return "batteryLevel invalid"
        if (alarmType == null) return "alarmType invalid"
        if (deliverySpeed == null) return "deliverySpeed invalid"
        if (brightness == null) return "brightness invalid"
        if (!validateInt(currentBasalPattern, 0, 7)) return "currentBasalPattern invalid"
        if (!validateDateTime(command.objectData, 46, apexDeviceInfo, alwaysNonHex = true)) return "dateTime invalid"
        if (language == null) return "language invalid"
        return null
    }

    fun toUpdateSettingsV1(
        info: ApexDeviceInfo,
        alarmLength: AlarmLength,
        lockKeys: Boolean? = null,
        limitTDD: Boolean? = null,
        language: Language? = null,
        bolusSpeed: BolusDeliverySpeed? = null,
        alarmType: AlarmType? = null,
        screenBrightness: ScreenBrightness? = null,
        lowReservoirThreshold: Int? = null,
        lowReservoirDurationThreshold: Int? = null,
        enableAdvancedBolus: Boolean? = null,
        screenDisableDuration: Int? = null,
        maxTDD: Int? = null,
        maxBasalRate: Int? = null,
        maxSingleBolus: Int? = null,
        enableGlucoseReminder: Boolean? = null,
        enableAutoSuspend: Boolean? = null,
        lockPump: Boolean? = null,
        autoSuspendDuration: Int? = null,
        useReferenceBasal: Boolean? = null,
    ): UpdateSettingsV1 {
        return UpdateSettingsV1(
            info,
            lockKeys ?: keyboardLockEnabled,
            limitTDD ?: totalDailyDoseLimitEnabled,
            language ?: this.language!!,
            bolusSpeed ?: deliverySpeed!!,
            alarmType ?: this.alarmType!!,
            alarmLength,
            screenBrightness ?: this.brightness!!,
            lowReservoirThreshold ?: this.lowReservoirThreshold,
            lowReservoirDurationThreshold ?: this.lowReservoirTimeLeftThreshold,
            enableAdvancedBolus ?: this.advancedBolusEnabled,
            screenDisableDuration ?: this.screenTimeout,
            maxTDD ?: this.maxTDD,
            maxBasalRate ?: this.maxBasal,
            maxSingleBolus ?: this.maxBolus,
            autoSuspendDuration ?: this.autoSuspendDuration,
            useReferenceBasal ?: this.isDefaultBasal,
            enableGlucoseReminder ?: this.bgReminderEnabled,
            enableAutoSuspend ?: this.autoSuspendEnabled,
            lockPump ?: this.isLocked,
        )
    }
}
