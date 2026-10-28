package app.aaps.pump.apex

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.data.model.BS
import app.aaps.pump.apex.connectivity.commands.device.Bolus
import app.aaps.pump.apex.connectivity.commands.device.ForceUpdatePassword
import app.aaps.pump.apex.connectivity.commands.pump.Alarm
import app.aaps.pump.apex.connectivity.commands.pump.AlarmLength
import app.aaps.pump.apex.connectivity.commands.pump.AlarmObject
import app.aaps.pump.apex.connectivity.commands.pump.AlarmType
import app.aaps.pump.apex.connectivity.commands.pump.BasalProfile
import app.aaps.pump.apex.connectivity.commands.pump.BatteryLevel
import app.aaps.pump.apex.connectivity.commands.pump.BolusDeliverySpeed
import app.aaps.pump.apex.connectivity.commands.pump.BolusEntry
import app.aaps.pump.apex.connectivity.commands.pump.CommandResponse
import app.aaps.pump.apex.connectivity.commands.pump.PumpAlarm
import app.aaps.pump.apex.connectivity.commands.pump.PumpCommand
import app.aaps.pump.apex.connectivity.commands.pump.PumpObject
import app.aaps.pump.apex.connectivity.commands.pump.PumpObjectModel
import app.aaps.pump.apex.connectivity.commands.pump.ScreenBrightness
import app.aaps.pump.apex.connectivity.commands.pump.StatusV1
import app.aaps.pump.apex.connectivity.commands.pump.StatusV2
import app.aaps.pump.apex.connectivity.commands.pump.TDDEntry
import app.aaps.pump.apex.connectivity.commands.pump.Version
import app.aaps.pump.apex.interfaces.ApexDeviceInfo
import app.aaps.pump.apex.misc.BatteryType
import app.aaps.pump.apex.utils.ApexCrypto
import app.aaps.pump.apex.utils.keys.ApexBooleanKey
import app.aaps.pump.apex.utils.keys.ApexDoubleKey
import app.aaps.pump.apex.utils.keys.ApexStringKey
import app.aaps.shared.tests.TestBase
import org.joda.time.DateTime
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.Mockito

class CommandsTest : TestBase() {
    @Mock lateinit var preferences: Preferences

    private val info = object : ApexDeviceInfo {
        override var serialNumber = "12345678"
        override val version = Version(deserialiseHead(PumpObject.FirmwareEntry, ubyteArrayOf(0xaau, 0x10u, 0x00u, 0xa3u, 0x31u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x06u, 0x19u, 0x04u, 0x0au, 0x30u, 0xcfu).toByteArray()))
    }

    private fun deserialiseHead(expectedType: PumpObject, data: ByteArray): PumpCommand {
        val command = PumpCommand(data)
        assert(command.isCompleteCommand())
        assert(command.verify())
        assert(PumpObject.findObject(command.id!!, command.objectData) == expectedType)
        println("Processed $command")
        return command
    }

    @Test
    fun pump_commandResponse() {
        val command = deserialiseHead(PumpObject.CommandResponse, ubyteArrayOf(0xaau, 0x0au, 0x00u, 0xa1u, 0xa0u, 0xaau, 0x0cu, 0x00u, 0xdau, 0xf5u).toByteArray())
        val data = CommandResponse(command)
        assert(data.code == CommandResponse.Code.StandardBolusProgress)
        assert(data.dose == 12)

        assert(command.trailing == null)
    }

    @Test
    fun pump_bolusEntry() {
        val command = deserialiseHead(PumpObject.BolusEntry, ubyteArrayOf(0xaau, 0x16u, 0x80u, 0xa3u, 0x21u, 0x00u, 0x25u, 0x01u, 0x25u, 0x17u, 0x52u, 0x59u, 0x09u, 0x00u, 0x09u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x78u).toByteArray())
        val data = BolusEntry(command, info)
        assert(data.dateTime == DateTime(2025, 1, 25, 17, 52, 59))
        assert(data.extendedDose == 0)
        assert(data.standardDose == 9)

        assert(command.trailing == null)
    }

    @Test
    fun pump_tddEntry() {
        val command = deserialiseHead(PumpObject.TDDEntry, ubyteArrayOf(0xaau, 0x12u, 0x5cu, 0xa3u, 0x06u, 0x00u, 0x88u, 0x00u, 0x56u, 0x01u, 0x0cu, 0x00u, 0x25u, 0x01u, 0x26u, 0x00u, 0x50u, 0xa0u, 0xaau, 0x12u, 0x5cu, 0xa3u, 0x06u, 0x00u, 0x88u, 0x00u, 0x56u, 0x01u, 0x0cu, 0x00u, 0x25u, 0x01u, 0x26u, 0x00u, 0x50u, 0xa0u).toByteArray())
        val data = TDDEntry(command, info)
        assert(data.dateTime == DateTime(2025, 1, 26, 0, 0))
        assert(data.bolus == 136) { data.bolus }
        assert(data.basal == 342) { data.basal }
        assert(data.temporaryBasal == 12) { data.temporaryBasal }

        val trailing = command.trailing
        assert(trailing != null)
        assert(trailing!!.isCompleteCommand())
        assert(trailing.verify())
        assert(PumpObject.findObject(trailing.id!!, trailing.objectData) == PumpObject.TDDEntry)
    }

    @Test
    fun pump_basalPattern() {
        val command = deserialiseHead(PumpObject.BasalProfile, ubyteArrayOf(0xaau, 0x68u, 0x08u, 0xa3u, 0x08u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x27u, 0x00u, 0x27u, 0x00u, 0x27u, 0x00u, 0x27u, 0x00u, 0x28u, 0x00u, 0x28u, 0x00u, 0x28u, 0x00u, 0x28u, 0x00u, 0x26u, 0x00u, 0x26u, 0x00u, 0x26u, 0x00u, 0x26u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x23u, 0x00u, 0x23u, 0x00u, 0x22u, 0x00u, 0x22u, 0x00u, 0x22u, 0x00u, 0x22u, 0x00u, 0x22u, 0x00u, 0x22u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x24u, 0x00u, 0x40u, 0x6au).toByteArray())
        val data = BasalProfile(command)
        val rates = listOf(
            36, 36,
            39, 39,
            39, 39,
            40, 40,
            40, 40,
            38, 38,
            38, 38,
            36, 36,
            35, 35,
            34, 34,
            34, 34,
            34, 34,
            36, 36,
            36, 36,
            36, 36,
            36, 36,
            36, 36,
            36, 36,
            36, 36,
            36, 36,
            36, 36,
            36, 36,
            36, 36,
            36, 36,
        )
        assert(data.rates == rates)
        assert(data.index == 0)
    }

    /** A real StatusV1 frame. Its battery object is the pump's coarse gauge reading `Full` = 100 %. */
    private fun statusV1() = StatusV1(
        deserialiseHead(PumpObject.StatusV1, ubyteArrayOf(0xaau, 0x60u, 0x01u, 0xa3u, 0x00u, 0xaau, 0x04u, 0x01u, 0x00u, 0x00u, 0x06u, 0x01u, 0x00u, 0x01u, 0x14u, 0x04u, 0x00u, 0x00u, 0x00u, 0x00u, 0x2cu, 0x01u, 0xb7u, 0x05u, 0x00u, 0x00u, 0x96u, 0x00u, 0x00u, 0x00u, 0xa0u, 0x00u, 0x0cu, 0x03u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x19u, 0x01u, 0x1cu, 0x15u, 0x0eu, 0x00u, 0x00u, 0x00u, 0x15u, 0x35u, 0x01u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x24u, 0x00u, 0x0du, 0x0eu, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0xc5u, 0xb3u).toByteArray()),
        info
    )

    @Test
    fun pump_statusV1() {
        val data = statusV1()
        assert(data.batteryLevel!!.approximatePercentage == 100)
        assert(data.alarmType == AlarmType.Vibration)
        assert(data.deliverySpeed == BolusDeliverySpeed.Standard)
        assert(data.brightness == ScreenBrightness.P10)
        assert(data.keyboardLockEnabled)
        assert(!data.autoSuspendEnabled)
        assert(data.autoSuspendDuration == 1)
        assert(data.lowReservoirThreshold == 20)
        assert(data.lowReservoirTimeLeftThreshold == 4)
        assert(!data.totalDailyDoseLimitEnabled)
        assert(data.screenTimeout == 300)
        assert(data.currentBasalRate == 36)
        // Today's running total, the figure the pump card shows. 1463 raw x 0.025 = 36.575 U - a
        // plausible part-day total, and the scale the whole frame uses for insulin. Pinned because
        // nothing else in the module CHECKS this field: validate() ignores it, and from ApexPump it
        // only reaches display paths, none of which anyone can compare against the pump: a row on the
        // Apex overview card, and - through ApexPumpPlugin.pumpSpecificShortStatus - whatever consumes
        // PumpStatusProvider.shortStatus, today the watch status dialog, the Tizen broadcast and the
        // SMS status reply.
        assert(data.totalDailyDose == 1463)
    }

    @Test
    fun pump_heartbeat() {
        deserialiseHead(PumpObject.Heartbeat, ubyteArrayOf(0xaau, 0x06u, 0x00u, 0xa5u, 0x01u, 0x00u, 0x81u, 0xa2u).toByteArray())
    }

    @Test
    fun pump_version() {
        val command = deserialiseHead(PumpObject.FirmwareEntry, ubyteArrayOf(0xaau, 0x10u, 0x00u, 0xa3u, 0x31u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u, 0x06u, 0x19u, 0x04u, 0x0au, 0x30u, 0xcfu).toByteArray())
        val data = Version(command)

        assert(data.firmwareMajor == 6)
        assert(data.firmwareMinor == 25)
        assert(data.protocolMajor == 4)
        assert(data.protocolMinor == 10)
    }

    private fun statusV2(centiVolts: Int) = ApexTestFrames.statusV2(centiVolts)

    /**
     * An [ApexPump] whose battery percentage is calculated over the alkaline window (1.20-1.47 V).
     * Alkaline is picked on purpose: only [app.aaps.pump.apex.misc.BatteryType.Custom] reads the two
     * voltage preferences, so a named type keeps the stubbing down to these two keys.
     */
    private fun pumpWithBattery(calculate: Boolean = true): ApexPump {
        Mockito.`when`(preferences.get(ApexBooleanKey.CalculateBatteryPercentage)).thenReturn(calculate)
        Mockito.`when`(preferences.get(ApexStringKey.CalcBatteryType)).thenReturn(BatteryType.Alkaline.name)
        return ApexPump(preferences, aapsLogger)
    }

    /**
     * A StatusV1 arriving after a StatusV2 must not drag the display back to the coarse gauge.
     * The two frames are 1.5-2 s apart, and a Nightscout upload landing in between published
     * 100 % while the cell was really at 66 % (32 of 1856 uploads on 2026-08-10).
     */
    @Test
    fun pump_batteryKeepsTheExactValueAcrossAStatusV1() {
        val pump = pumpWithBattery()
        pump.updateFromV1(statusV1())
        assert(pump.batteryLevel.percentage == 100) { "coarse bootstrap, got ${pump.batteryLevel.percentage}" }
        assert(pump.batteryLevel.approximate)
        assert(pump.lastStatusReceivedAt > 0) { "V1 must stamp the phone-clock arrival time" }
        assert(!pump.isInitialized) { "a decoded frame is not a finished handshake" }

        pump.updateFromV2(statusV2(centiVolts = 140))
        assert(pump.batteryLevel.percentage == 74) { "1.40 V over 1.20-1.47 V, got ${pump.batteryLevel.percentage}" }
        assert(!pump.batteryLevel.approximate)

        pump.updateFromV1(statusV1())
        assert(pump.batteryLevel.percentage == 74) { "V1 clobbered the exact value: ${pump.batteryLevel.percentage}" }
        assert(!pump.batteryLevel.approximate) { "an exact value must not be re-flagged as approximate" }
    }

    /** A pump below protocol 4.11 never sends StatusV2, so V1 has to keep writing the gauge. */
    @Test
    fun pump_batteryUsesTheCoarseGaugeWhenThereIsNoStatusV2() {
        val pump = pumpWithBattery()
        pump.updateFromV1(statusV1())
        pump.updateFromV1(statusV1())
        assert(pump.batteryLevel.percentage == 100) { "stuck at ${pump.batteryLevel.percentage} % with no V2 to fill it in" }
        assert(pump.batteryLevel.approximate)
    }

    /**
     * A missing StatusV2 is not an error any more, so a pump that stops answering it must not leave
     * an exact-looking percentage frozen on screen while the cell drains.
     */
    @Test
    fun pump_batteryGoesBackToTheCoarseGaugeWhenStatusV2GoesStale() {
        val pump = pumpWithBattery()
        pump.updateFromV1(statusV1())
        pump.updateFromV2(statusV2(centiVolts = 140))
        assert(pump.batteryLevel.percentage == 74)

        pump.lastV2At -= ApexPump.EXACT_BATTERY_MAX_AGE_MS + 1
        pump.updateFromV1(statusV1())
        assert(pump.batteryLevel.percentage == 100) { "stale exact value survived: ${pump.batteryLevel.percentage}" }
        assert(pump.batteryLevel.approximate) { "the fallback must say it is approximate" }
    }

    /** With the calculation switched off there is no exact value to protect. */
    @Test
    fun pump_batteryUsesTheCoarseGaugeWhenTheCalculationIsOff() {
        val pump = pumpWithBattery(calculate = false)
        pump.updateFromV1(statusV1())
        pump.updateFromV2(statusV2(centiVolts = 140))
        assert(pump.batteryLevel.percentage == 100) { "V2 should copy V1 here, got ${pump.batteryLevel.percentage}" }

        pump.updateFromV1(statusV1())
        assert(pump.batteryLevel.percentage == 100) { "got ${pump.batteryLevel.percentage}" }
        // This flag is the only discriminator. Drop the preference test out of batteryLevelForV1 and
        // the V1 frame would carry the V2 level forward instead of writing its own gauge - and the
        // percentage would still read 100, because with the calculation off StatusV2 just copies it.
        assert(pump.batteryLevel.approximate) { "V1 must write its own coarse gauge when the calculation is off" }
    }

    /** The real [statusV1] frame with its coarse battery gauge overwritten. */
    private fun statusV1WithCoarseBattery(level: BatteryLevel) = StatusV1(
        pumpFrame(statusV1().command.objectData.toUByteArray().also { it[2] = level.raw.toUByte() }),
        info
    )

    /**
     * "No status frame yet" and "the pump says the cell is Dead" are both 0 % on
     * [ApexPump.batteryLevel], and only the second one is a reading. What reaches AAPS-core has to
     * tell them apart: `TriggerPumpBatteryLevel` reads that value directly, so publishing 0 before
     * the first frame armed every "battery below N" automation on each app start.
     */
    @Test
    fun pump_batteryIsUnknownUntilTheFirstFrameAndZeroIsThenAReading() {
        val pump = pumpWithBattery()
        assert(pump.batteryPercentageOrNull == null) { "published ${pump.batteryPercentageOrNull} before any frame" }
        assert(pump.batteryLevel.percentage == 0) { "the in-class fallback stays 0 - the change detection needs one" }

        pump.updateFromV1(statusV1())
        assert(pump.batteryPercentageOrNull == 100) { "got ${pump.batteryPercentageOrNull}" }

        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Dead))
        assert(pump.batteryPercentageOrNull == 0) { "a Dead gauge is a reading, got ${pump.batteryPercentageOrNull}" }
    }

    /**
     * An [ApexPump] on the custom voltage window, the only [BatteryType] that reads the two voltage
     * preferences.
     *
     * Both of them are stubbed on every call, and so is the calculation flag: Mockito answers an
     * unstubbed Double with 0.0 and an unstubbed Boolean with false, so a missing stub does not fail
     * the test - it makes `high <= low` return 0 %, or turns the whole calculation off and lets
     * StatusV2 copy the coarse gauge. Either one can look exactly like a cap that works.
     */
    private fun pumpWithCustomBattery(low: Double, high: Double): ApexPump {
        Mockito.`when`(preferences.get(ApexBooleanKey.CalculateBatteryPercentage)).thenReturn(true)
        Mockito.`when`(preferences.get(ApexStringKey.CalcBatteryType)).thenReturn(BatteryType.Custom.name)
        Mockito.`when`(preferences.get(ApexDoubleKey.BatteryLowVoltage)).thenReturn(low)
        Mockito.`when`(preferences.get(ApexDoubleKey.BatteryHighVoltage)).thenReturn(high)
        return ApexPump(preferences, aapsLogger)
    }

    /**
     * The pump's own gauge caps the voltage map. On a 1.00-1.50 V window a cell at 1.23 V, which the
     * pump was already beeping LowBattery about, published 46 % - the reading this cap exists for.
     */
    @Test
    fun pump_batteryCapsOptimisticPercentWhenGaugeIsLow() {
        val pump = pumpWithCustomBattery(low = 1.00, high = 1.50)
        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Low))
        pump.updateFromV2(statusV2(centiVolts = 123))

        assert(pump.batteryLevel.percentage == 25) { "a Low gauge went uncapped: ${pump.batteryLevel.percentage}" }
        assert(!pump.batteryLevel.approximate) { "a voltage-derived value stays exact" }
    }

    /**
     * The cap is a ceiling, never a replacement. A well-configured 1.20-1.50 V window already reads
     * 10 % at that same 1.23 V, and 10 % is the honest number. This is what pins
     * [pump_batteryCapsOptimisticPercentWhenGaugeIsLow] as a voltage cap rather than a copy of the
     * coarse gauge.
     */
    @Test
    fun pump_batteryKeepsPessimisticVoltageWhenGaugeIsLow() {
        val pump = pumpWithCustomBattery(low = 1.20, high = 1.50)
        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Low))
        pump.updateFromV2(statusV2(centiVolts = 123))

        assert(pump.batteryLevel.percentage == 10) { "the cap raised a lower reading: ${pump.batteryLevel.percentage}" }
        assert(!pump.batteryLevel.approximate)
    }

    /** Only Low and Dead cap. A healthy gauge leaves the voltage map exactly where it is. */
    @Test
    fun pump_batteryDoesNotCapMediumGauge() {
        val pump = pumpWithCustomBattery(low = 1.00, high = 1.50)
        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Medium))
        pump.updateFromV2(statusV2(centiVolts = 127))

        assert(pump.batteryLevel.percentage == 54) { "Medium must not cap, got ${pump.batteryLevel.percentage}" }
        assert(!pump.batteryLevel.approximate)
    }

    /**
     * Dead publishes 0 even where the voltage map is well above it (1.18 V is 35 % on this window).
     * The window is custom on purpose: on the alkaline one 1.18 V is already 0 from the map alone,
     * so the test would keep passing with the override deleted.
     */
    @Test
    fun pump_batteryDeadWinsEvenWhenLinearMapIsHigher() {
        val pump = pumpWithCustomBattery(low = 1.00, high = 1.50)
        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Dead))
        pump.updateFromV2(statusV2(centiVolts = 118))

        assert(pump.batteryLevel.percentage == 0) { "the map published ${pump.batteryLevel.percentage} % over a Dead gauge" }
        assert(!pump.batteryLevel.approximate)
    }

    /**
     * 1.50 V is the pump's firmware ceiling, not evidence of a good cell: it reports that byte for
     * anything at or above it. A Dead gauge there is still 0 %.
     */
    @Test
    fun pump_batteryDeadWinsEvenAtCeilingVoltage() {
        val pump = pumpWithCustomBattery(low = 1.00, high = 1.50)
        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Dead))
        pump.updateFromV2(statusV2(centiVolts = 150))

        assert(pump.batteryLevel.percentage == 0) { "ceiling voltage beat a Dead gauge: ${pump.batteryLevel.percentage}" }
        assert(!pump.batteryLevel.approximate)
    }

    /** No StatusV1 yet is "unknown", not "Dead" and not "Low" - there is nothing to cap against. */
    @Test
    fun pump_batteryV2WithoutV1UsesLinearMap() {
        val pump = pumpWithCustomBattery(low = 1.00, high = 1.50)
        pump.updateFromV2(statusV2(centiVolts = 123))

        assert(pump.batteryLevel.percentage == 46) { "a missing gauge capped the map: ${pump.batteryLevel.percentage}" }
        assert(!pump.batteryLevel.approximate)
    }

    /**
     * The V1 path caps too, and it has to: `checkPump` reads StatusV1 alone before a bolus or TBR, a
     * StatusV2 that times out is only a warning, and `onStatusV1` publishes about 1.6 s before its
     * own cycle's V2. Waiting for the next V2 would keep a healthy-looking percentage on screen
     * while the pump beeps.
     */
    @Test
    fun pump_batteryV1LowCapsAHeldExactValue() {
        val pump = pumpWithCustomBattery(low = 1.00, high = 1.50)
        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Medium))
        pump.updateFromV2(statusV2(centiVolts = 127))
        assert(pump.batteryLevel.percentage == 54) { "setup must hold a value above the cap, got ${pump.batteryLevel.percentage}" }

        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Low))

        assert(pump.batteryLevel.percentage == 25) { "the held exact value survived a Low gauge: ${pump.batteryLevel.percentage}" }
        assert(!pump.batteryLevel.approximate) { "the cap must not re-flag the level as approximate - onBatteryChanged skips mixed scales" }
        assert(pump.batteryLevel.voltage == 1.27) { "the last measured voltage is still the truth, got ${pump.batteryLevel.voltage}" }
    }

    /** Same, for the gauge that means the pump has given up on the cell entirely. */
    @Test
    fun pump_batteryV1DeadCapsAHeldExactValue() {
        val pump = pumpWithCustomBattery(low = 1.00, high = 1.50)
        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Medium))
        pump.updateFromV2(statusV2(centiVolts = 127))
        assert(pump.batteryLevel.percentage == 54) { "setup, got ${pump.batteryLevel.percentage}" }

        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Dead))

        assert(pump.batteryLevel.percentage == 0) { "a Dead gauge did not reach the display: ${pump.batteryLevel.percentage}" }
        assert(!pump.batteryLevel.approximate)
        assert(pump.batteryLevel.voltage == 1.27) { "got ${pump.batteryLevel.voltage}" }
    }

    /** The other end of the scale: a Full gauge at the ceiling voltage is a plain 100 %. */
    @Test
    fun pump_batteryFullAtCeilingIsOneHundred() {
        val pump = pumpWithCustomBattery(low = 1.00, high = 1.50)
        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Full))
        pump.updateFromV2(statusV2(centiVolts = 150))

        assert(pump.batteryLevel.percentage == 100) { "got ${pump.batteryLevel.percentage}" }
        assert(!pump.batteryLevel.approximate)
    }

    /**
     * With the calculation off there is no exact value to cap: StatusV2 copies whatever StatusV1
     * wrote, and the next V1 writes its own coarse gauge and says so. `exactIsUsable` is false here
     * because of the preference, not because a StatusV2 is missing.
     */
    @Test
    fun pump_batteryCalculationOffCopiesV1AndNextV1IsApproximate() {
        val pump = pumpWithBattery(calculate = false)
        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Medium))
        pump.updateFromV2(statusV2(centiVolts = 123))
        assert(pump.batteryLevel.percentage == 50) { "V2 must copy the coarse gauge here, got ${pump.batteryLevel.percentage}" }
        assert(!pump.batteryLevel.approximate) { "updateFromV2 always writes an exact flag, even when it copies" }

        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Low))

        assert(pump.batteryLevel.percentage == 25) { "got ${pump.batteryLevel.percentage}" }
        assert(pump.batteryLevel.approximate) { "with the calculation off a V1 writes its own gauge, which is approximate" }
    }

    /**
     * Recovery after a stale Dead gauge has overwritten a good value is deliberately slow, and the
     * next StatusV2 is not enough on its own: it reads `lastV1`, which is still Dead. It takes a
     * non-Dead V1 - which keeps the stored 0 %, but moves `lastV1` - and then a V2.
     */
    @Test
    fun pump_batteryStaleDeadDoesNotRecoverOnTheNextV2Alone() {
        val pump = pumpWithCustomBattery(low = 1.00, high = 1.50)
        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Full))
        pump.updateFromV2(statusV2(centiVolts = 150))
        assert(pump.batteryLevel.percentage == 100) { "setup, got ${pump.batteryLevel.percentage}" }

        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Dead))
        assert(pump.batteryLevel.percentage == 0) { "got ${pump.batteryLevel.percentage}" }

        pump.updateFromV2(statusV2(centiVolts = 150))
        assert(pump.batteryLevel.percentage == 0) { "a V2 while lastV1 is Dead must stay 0: ${pump.batteryLevel.percentage}" }

        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Full))
        assert(pump.batteryLevel.percentage == 0) { "a coarse Full must not resurrect a held 0 %: ${pump.batteryLevel.percentage}" }
        assert(!pump.batteryLevel.approximate)

        pump.updateFromV2(statusV2(centiVolts = 150))
        assert(pump.batteryLevel.percentage == 100) { "the pump never came back: ${pump.batteryLevel.percentage}" }
        assert(!pump.batteryLevel.approximate)
    }

    /**
     * A cap lowers what is published without the cell having changed, so releasing it looks like a
     * jump. `ApexService.onBatteryChanged` calls a rise of more than 26 points a battery swap and
     * writes a PUMP_BATTERY_CHANGE therapy event, which is uploaded to Nightscout and resets the
     * battery-age display - so on a 1.00-1.50 V window a gauge dipping to Low at 1.27 V and coming
     * back (54 -> 25 -> 54 published) would log a swap nobody made.
     *
     * [ApexPump.BatteryLevel.uncappedPercentage] is what that detector compares, and it must not
     * move while only the gauge does.
     */
    @Test
    fun pump_batteryCapDoesNotMoveTheFigureTheSwapDetectorReads() {
        val pump = pumpWithCustomBattery(low = 1.00, high = 1.50)
        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Medium))
        pump.updateFromV2(statusV2(centiVolts = 127))
        assert(pump.batteryLevel.uncappedPercentage == 54) { "setup, got ${pump.batteryLevel.uncappedPercentage}" }

        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Low))
        assert(pump.batteryLevel.percentage == 25) { "the published value must be capped: ${pump.batteryLevel.percentage}" }
        assert(pump.batteryLevel.uncappedPercentage == 54) { "the cap moved the swap detector's figure: ${pump.batteryLevel.uncappedPercentage}" }

        // The gauge recovers. Published goes back up; the uncapped figure never moved, so the
        // detector sees 54 against 54 and has nothing to report.
        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Medium))
        pump.updateFromV2(statusV2(centiVolts = 127))
        assert(pump.batteryLevel.percentage == 54) { "got ${pump.batteryLevel.percentage}" }
        assert(pump.batteryLevel.uncappedPercentage == 54) { "got ${pump.batteryLevel.uncappedPercentage}" }
    }

    /** Same for Dead, which caps all the way to 0 and so has the widest false-swap margin. */
    @Test
    fun pump_deadCapDoesNotMoveTheFigureTheSwapDetectorReads() {
        val pump = pumpWithCustomBattery(low = 1.00, high = 1.50)
        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Full))
        pump.updateFromV2(statusV2(centiVolts = 150))

        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Dead))
        assert(pump.batteryLevel.percentage == 0) { "got ${pump.batteryLevel.percentage}" }
        assert(pump.batteryLevel.uncappedPercentage == 100) { "got ${pump.batteryLevel.uncappedPercentage}" }
    }

    /**
     * The swap detector still has to fire on a real swap, and a cell is usually replaced while the
     * pump is calling it Low - the exact state the cap is active in. The new cell moves the voltage,
     * which moves the uncapped figure, and `ApexService.onBatteryChanged` reads that one.
     */
    @Test
    fun pump_batterySwapOutOfACappedStateIsStillDetectable() {
        val pump = pumpWithCustomBattery(low = 1.00, high = 1.50)
        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Low))
        pump.updateFromV2(statusV2(centiVolts = 123))
        assert(pump.batteryLevel.percentage == 25) { "got ${pump.batteryLevel.percentage}" }
        assert(pump.batteryLevel.uncappedPercentage == 46) { "got ${pump.batteryLevel.uncappedPercentage}" }

        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Full))
        pump.updateFromV2(statusV2(centiVolts = 150))

        assert(pump.batteryLevel.percentage == 100) { "got ${pump.batteryLevel.percentage}" }
        // 100 - 26 > 46 is what onBatteryChanged tests, on the uncapped figures.
        assert(pump.batteryLevel.uncappedPercentage - 26 > 46) { "a real swap stopped being detectable: ${pump.batteryLevel.uncappedPercentage}" }
    }

    /**
     * The same swap, seen in the frame order where the pump's gauge lags the new cell: the V2
     * carrying the new voltage is applied while `lastV1` still reads the old Dead gauge, which
     * `calcFromVoltage` documents as reachable on the cached frame pool.
     *
     * The published percentage cannot move on that frame - the stale Dead gauge caps it to 0, and a
     * later non-Dead V1 only holds the stored 0 rather than restoring it - so anything that required
     * the published figure to rise alongside the uncapped one would log this swap never. The
     * uncapped figure does all of its rising here, on the first V2, which is where the detector
     * sees it. Before this work it saw nothing here at all: a Dead gauge zeroed the voltage map
     * itself, so both frames published 0 and the swap only surfaced a cycle later, once a non-Dead
     * V1 had moved the gauge. Carrying the uncapped map through the Dead state is what brings it
     * forward to the first post-swap V2.
     */
    @Test
    fun pump_batterySwapIsDetectableEvenWhenTheGaugeLagsAFrame() {
        val pump = pumpWithCustomBattery(low = 1.00, high = 1.50)
        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Dead))
        pump.updateFromV2(statusV2(centiVolts = 118))
        assert(pump.batteryLevel.percentage == 0) { "got ${pump.batteryLevel.percentage}" }
        assert(pump.batteryLevel.uncappedPercentage == 35) { "got ${pump.batteryLevel.uncappedPercentage}" }

        // New cell, but this cycle's V1 has not been applied yet, so the gauge is still Dead.
        pump.updateFromV2(statusV2(centiVolts = 150))

        assert(pump.batteryLevel.percentage == 0) { "a stale Dead gauge still caps: ${pump.batteryLevel.percentage}" }
        // 100 - 26 > 35: the detector has what it needs on this frame, before the gauge catches up.
        assert(pump.batteryLevel.uncappedPercentage == 100) { "the swap left no trace to detect: ${pump.batteryLevel.uncappedPercentage}" }
    }

    /**
     * The V1 cap is a ceiling, not an assignment: a stored value already below it stays where it is.
     * What this pins that no other test does is the V1 caller's plumbing - that `batteryLevelForV1`
     * feeds the stored percentage into `capByGauge` instead of writing 25 flat.
     * [pump_batteryKeepsPessimisticVoltageWhenGaugeIsLow] pins the same `minOf` from the V2 side.
     */
    @Test
    fun pump_lowGaugeDoesNotRaiseAnAlreadyLowerHeldValue() {
        val pump = pumpWithCustomBattery(low = 1.20, high = 1.50)
        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Medium))
        pump.updateFromV2(statusV2(centiVolts = 123))
        assert(pump.batteryLevel.percentage == 10) { "setup, got ${pump.batteryLevel.percentage}" }

        pump.updateFromV1(statusV1WithCoarseBattery(BatteryLevel.Low))

        assert(pump.batteryLevel.percentage == 10) { "the cap raised a lower held value: ${pump.batteryLevel.percentage}" }
        assert(!pump.batteryLevel.approximate)
    }

    private fun pumpFrame(objectData: UByteArray) = ApexTestFrames.pumpFrame(objectData)

    /** objectData layout: valueId, index, 6-byte datetime, stdDose, stdPerformed, extDose, extPerformed. */
    private fun bolusEntryData(stdDose: Int, stdPerformed: Int, extDose: Int, extPerformed: Int) = ubyteArrayOf(
        0x21u, 0x00u,
        0x25u, 0x01u, 0x25u, 0x17u, 0x52u, 0x59u,
        (stdDose and 0xFF).toUByte(), (stdDose shr 8).toUByte(),
        (stdPerformed and 0xFF).toUByte(), (stdPerformed shr 8).toUByte(),
        (extDose and 0xFF).toUByte(), (extDose shr 8).toUByte(),
        (extPerformed and 0xFF).toUByte(), (extPerformed shr 8).toUByte(),
    )

    /** An untouched ring-buffer slot must be reported as empty, not as a corrupt object. */
    @Test
    fun pump_bolusEntry_emptySlotIsRecognised() {
        val entry = BolusEntry(pumpFrame(bolusEntryData(0, 0, 0, 0)), info)
        assert(entry.validate() == PumpObjectModel.EMPTY_SLOT) { "got ${entry.validate()}" }
    }

    /** objectData layout: valueId, index, 6-byte datetime, 2-byte code the driver reads with +0x100. */
    private fun alarmEntryData(code: Int) = ubyteArrayOf(
        0x03u, 0x00u,
        0x25u, 0x01u, 0x25u, 0x17u, 0x52u, 0x59u,
        (code and 0xFF).toUByte(), (code shr 8).toUByte(),
    )

    @Test
    fun pump_alarmEntry_knownCodeIsDecoded() {
        val entry = AlarmObject(pumpFrame(alarmEntryData(0x08)), info)
        assert(entry.validate() == null) { "got ${entry.validate()}" }
        assert(entry.type == Alarm.NoDelivery) { "got ${entry.type}" }
    }

    /**
     * A code the table does not know must be kept as [Alarm.Unknown], not thrown away. The whole record
     * used to be dropped, so exactly the faults this driver knows least about vanished from history.
     */
    @Test
    fun pump_alarmEntry_unknownCodeIsKeptAsUnknown() {
        val entry = AlarmObject(pumpFrame(alarmEntryData(0x55)), info)
        assert(entry.validate() == null) { "got ${entry.validate()}" }
        assert(entry.type == Alarm.Unknown) { "got ${entry.type}" }
    }

    /** An untouched alarm slot holds the 'no error' code and must be reported as empty, not as corrupt. */
    @Test
    fun pump_alarmEntry_emptySlotIsRecognised() {
        val entry = AlarmObject(pumpFrame(alarmEntryData(0x00)), info)
        assert(entry.validate() == PumpObjectModel.EMPTY_SLOT) { "got ${entry.validate()}" }
    }

    /** A short frame must be named, not throw out of the field readers. */
    @Test
    fun pump_alarmEntry_truncatedFrameIsRejected() {
        val entry = AlarmObject(pumpFrame(ubyteArrayOf(0x03u, 0x00u, 0x25u, 0x01u)), info)
        assert(entry.validate() == "truncated alarm entry") { "got ${entry.validate()}" }
    }

    /**
     * A dual-wave entry carries both a standard and an extended part. It must pass validation so the
     * standard half still reaches the database — rejecting it hid that insulin from IOB.
     */
    @Test
    fun pump_bolusEntry_dualWaveIsAccepted() {
        val entry = BolusEntry(pumpFrame(bolusEntryData(stdDose = 40, stdPerformed = 40, extDose = 20, extPerformed = 20)), info)
        assert(entry.validate() == null) { "got ${entry.validate()}" }
        assert(entry.standardPerformed == 40)
    }

    /**
     * The pump stamps every bolus at second 59 of its minute, so two boluses in one minute share a
     * timestamp. The pump id must still tell them apart, and must not change between history reads.
     */
    @Test
    fun pumpId_separatesSameMinuteBolusesAndIsStable() {
        val stamp = DateTime(2025, 1, 25, 17, 52, 59).millis

        val smb = ApexService.pumpIdOf(stamp, 10)      // 0.25 U
        val meal = ApexService.pumpIdOf(stamp, 240)    // 6 U
        assert(smb != meal) { "same-minute boluses collided on pumpId $smb" }

        assert(ApexService.pumpIdOf(stamp, 240) == meal) { "pumpId is not stable across reads" }

        // The largest dose the pump accepts (25 U = 1000 raw) must not spill into the next time slot.
        assert(ApexService.pumpIdOf(stamp, 1000) < ApexService.pumpIdOf(stamp + ApexService.PUMP_ID_TIME_GRID_MS, 0))
    }

    /**
     * A real bolus frame from a TruCare III on protocol 4.12, captured on 2026-08-09 at 12:16.
     *
     * It is the evidence behind [ApexService.historyBolusTimestamp]: the pump keeps history at minute
     * resolution and writes second 59 itself (byte 0x3b), on the modern protocol path too, where the
     * date bytes are plain values instead of the older BCD. So the driver decodes the frame correctly
     * and the time it gets is simply up to a minute later than the delivery.
     */
    @Test
    fun pump_bolusEntry_realProto412FrameCarriesSecond59() {
        val proto412 = object : ApexDeviceInfo {
            override var serialNumber = "33310677"
            override val version = Version(pumpFrame(ubyteArrayOf(0x31u, 0x00u, 0x00u, 0x00u, 0x00u, 0x01u, 0x01u, 0x01u, 0x04u, 0x0cu)))
        }
        assert(proto412.version!!.protocolMajor == 4 && proto412.version!!.protocolMinor == 12)

        // Object data of aa1680a321071a08090c103b140014000000000054a3 as seen on the wire; the frame
        // is rebuilt here so the CRC matches the header this helper writes.
        val entry = BolusEntry(pumpFrame(ubyteArrayOf(0x21u, 0x07u, 0x1au, 0x08u, 0x09u, 0x0cu, 0x10u, 0x3bu, 0x14u, 0x00u, 0x14u, 0x00u, 0x00u, 0x00u, 0x00u, 0x00u)), proto412)
        assert(entry.validate() == null) { "got ${entry.validate()}" }
        assert(entry.dateTime == DateTime(2026, 8, 9, 12, 16, 59)) { "got ${entry.dateTime}" }
        assert(entry.standardPerformed == 20) { "got ${entry.standardPerformed}" }   // 0.5 U
    }

    /**
     * The stored time of a keypad bolus must never be in the future: while it is, the bolus is left
     * out of IOB and the loop doses as if that insulin did not exist.
     */
    @Test
    fun historyBolusTimestamp_neverInTheFuture() {
        val now = DateTime(2026, 8, 9, 12, 16, 20).millis

        // The pump stamped the minute it is still inside - 39 s ahead of the read.
        val ahead = DateTime(2026, 8, 9, 12, 16, 59).millis
        assert(ApexService.historyBolusTimestamp(ahead, now) == now) { "future stamp was not pulled back" }

        // An entry read later in the same minute, and any older entry, must be left alone.
        val past = DateTime(2026, 8, 9, 12, 15, 59).millis
        assert(ApexService.historyBolusTimestamp(past, now) == past) { "past stamp must not be moved" }
        assert(ApexService.historyBolusTimestamp(now, now) == now)
    }

    @Test
    fun device_bolus() {
        val expected = ubyteArrayOf(0x35u, 0x17u, 0x00u, 0xa1u, 0x12u, 0xaau, 0x41u, 0x50u, 0x45u, 0x58u, 0x31u, 0x32u, 0x33u, 0x34u, 0x35u, 0x36u, 0x37u, 0x38u, 0x3cu, 0x00u, 0x00u, 0x6fu, 0x65u).toByteArray()
        val command = Bolus(info, 60)
        assert(expected.contentEquals(command.serialize()))
    }

    @Test
    fun device_forceUpdatePassword_sixValueBytes() {
        // From the decompile (BSDevBlePacker case 53), NOT a wire capture: each digit is its own value
        // byte 0x00..0x09, the command is 0x35 / SetValue / valueId 53. Nothing sends this yet - the
        // layout must be byte-compared against a real pump before 2B wires a sender.
        val command = ForceUpdatePassword(info, "123456")
        assert(command.valueId == 53)
        assert(command.isWrite)
        assert(command.type == 0x35)
        assert(command.additionalData.contentEquals(byteArrayOf(1, 2, 3, 4, 5, 6)))
    }

    // --- The bolus match that has to survive a restart (see ApexStringKey.PendingBolusSync) ---

    private val now = 1786550400000L

    private fun pending(temporaryId: Long = now, rawDose: Int = 16) =
        ApexService.Companion.PendingBolusSync(temporaryId, now, rawDose, BS.Type.SMB)

    @Test
    fun pendingBolusSync_survivesTheRoundTripThroughAString() {
        val original = ApexService.Companion.PendingBolusSync(1786550392059L, 1786550392069L, 16, BS.Type.SMB)

        assert(ApexService.Companion.PendingBolusSync.parse(original.serialize()) == original)
    }

    @Test
    fun pendingBolusSync_parseRefusesAnythingMalformed() {
        // A bad stored value must simply disable the recovery, never crash or half-match.
        assert(ApexService.Companion.PendingBolusSync.parse("") == null)
        assert(ApexService.Companion.PendingBolusSync.parse("1|2|3") == null)
        assert(ApexService.Companion.PendingBolusSync.parse("x|2|3|SMB") == null)
        assert(ApexService.Companion.PendingBolusSync.parse("1|2|3|NOT_A_TYPE") == null)
    }

    @Test
    fun pendingBolusSync_matchesTheSameEntryTheLiveBolusWould() {
        // Same second, same requested dose.
        assert(pending().matches(now, 16, now))
        // The pump files every entry at second 59, so an entry a minute away is the same bolus.
        assert(pending().matches(now + 60000, 16, now))
        // A dose within one step and a half is still this bolus (0.11 U over 0.025 U steps).
        assert(pending().matches(now, 20, now))
    }

    @Test
    fun pendingBolusSync_refusesAnotherBolus() {
        assert(!pending().matches(now + 30000, 16, now))  // half a minute away: a different entry
        assert(!pending().matches(now, 24, now))          // 0.2 U apart: a different bolus
    }

    /** A record older than the history window must be dropped, or it could match an unrelated later bolus. */
    @Test
    fun pendingBolusSync_expiresWithTheHistoryWindow() {
        val old = now + ApexService.HISTORY_RECENCY_WINDOW + 1

        assert(pending().isStale(old))
        assert(!pending().matches(now, 16, old))
        assert(!pending().isStale(now + ApexService.HISTORY_RECENCY_WINDOW - 1))
    }

    // --- Reconnect backoff: bounded, and the first retry stays immediate ---

    @Test
    fun reconnectBackoff_isImmediateThenRisesAndIsCapped() {
        val sut = ApexService()

        assert(sut.reconnectBackoffMs(0) == 0L)
        assert(sut.reconnectBackoffMs(1) == 5_000L)
        assert(sut.reconnectBackoffMs(2) == 15_000L)
        assert(sut.reconnectBackoffMs(3) == 30_000L)
        assert(sut.reconnectBackoffMs(50) == 30_000L)
    }

    // --- A pump standing on a motor-stop alarm is not delivering ---

    /** The real [statusV1] frame with one raw alarm code written into its first alarm slot. */
    private fun statusV1WithAlarm(raw: Int) = StatusV1(
        pumpFrame(statusV1().command.objectData.toUByteArray().also {
            it[58] = (raw and 0xff).toUByte()
            it[59] = ((raw shr 8) and 0xff).toUByte()
        }),
        info
    )

    @Test
    fun driverLogic_anyStopsDeliveryOnlyForMotorStops() {
        assert(!ApexDriverLogic.anyStopsDelivery(listOf()))
        assert(ApexDriverLogic.anyStopsDelivery(listOf(PumpAlarm.of(Alarm.ReservoirEmpty.raw))))
        // The one alarm this pump actually raises in ordinary use. It says nothing about the motor,
        // so blocking dosing on it would stop the loop for hours at a time.
        assert(!ApexDriverLogic.anyStopsDelivery(listOf(PumpAlarm.of(Alarm.ButtonError.raw))))
        // An unnameable code is not evidence of a stop - see the KDoc on stopsDelivery.
        assert(!ApexDriverLogic.anyStopsDelivery(listOf(PumpAlarm.of(0x2FF))))
    }

    /**
     * The defect this exists for: the pump kept reporting a running basal rate while it stood on
     * `ReservoirEmpty` and delivered nothing, so `isSuspended` said false and the loop went on setting
     * temporary basals (measured 2026-08-31, five of them, up to 1.875 U/h).
     */
    @Test
    fun pump_isSuspendedWhileAStopAlarmStandsEvenThoughBasalIsRunning() {
        val pump = pumpWithBattery()

        pump.updateFromV1(statusV1())
        assert(pump.alarms.isEmpty()) { "the reference frame must start with no alarm, got ${pump.alarms}" }
        assert(pump.basal != null) { "the reference frame must report a running basal rate" }
        assert(!pump.isSuspended) { "a delivering pump is not suspended" }

        pump.updateFromV1(statusV1WithAlarm(Alarm.ReservoirEmpty.raw))
        assert(pump.basal != null) { "the pump still claims a running basal rate - that is the whole problem" }
        assert(pump.isDeliveryStoppedByAlarm)
        assert(pump.isSuspended) { "a pump standing on ReservoirEmpty is not putting insulin in" }
    }

    /** A keypad fault must not stop the loop: it is not proof the motor stopped. */
    @Test
    fun pump_isNotSuspendedByAnAlarmThatDoesNotStopTheMotor() {
        val pump = pumpWithBattery()

        pump.updateFromV1(statusV1WithAlarm(Alarm.ButtonError.raw))
        assert(pump.alarms.isNotEmpty()) { "the frame should carry the alarm" }
        assert(!pump.isDeliveryStoppedByAlarm)
        assert(!pump.isSuspended)
    }

    /**
     * The pump drops the alarm out of its slots for a single frame while the motor is still stopped -
     * seen at 20:58:57 on 2026-08-31, with the alarm back at 21:00:37 and the delivered-dose counter
     * frozen throughout. Resuming on that one frame would hand the loop a dead pump.
     */
    @Test
    fun pump_oneCleanFrameDoesNotEndADeliveryStop() {
        val pump = pumpWithBattery()

        pump.updateFromV1(statusV1WithAlarm(Alarm.ReservoirEmpty.raw))
        pump.updateFromV1(statusV1())

        assert(pump.isDeliveryStoppedByAlarm) { "one clean frame is a flicker, not a recovery" }
        assert(pump.isSuspended)
    }

    @Test
    fun pump_deliveryStopEndsAfterTwoCleanFrames() {
        // The literal count, and a check of the constant beside it. Feeding the loop from
        // DELIVERY_STOP_CLEAR_FRAMES itself made this pass for every value it could hold, so raising
        // it to 10 - five times the wait before dosing resumes - would have failed nothing.
        assert(ApexDriverLogic.DELIVERY_STOP_CLEAR_FRAMES == 2) { "this test is written for two clean frames" }
        val pump = pumpWithBattery()

        pump.updateFromV1(statusV1WithAlarm(Alarm.ReservoirEmpty.raw))
        pump.updateFromV1(statusV1())
        pump.updateFromV1(statusV1())

        assert(!pump.isDeliveryStoppedByAlarm)
        assert(!pump.isSuspended) { "the pump is delivering again once the alarm is really gone" }
    }

    /** The real [statusV1] frame with the "no basal running" sentinel in its basal rate. */
    private fun statusV1BasalPaused() = StatusV1(
        pumpFrame(statusV1().command.objectData.toUByteArray().also {
            it[78] = 0xFFu
            it[79] = 0xFFu
        }),
        info
    )

    @Test
    fun pump_isSuspendedWhenThePumpReportsNoBasalRate() {
        val pump = pumpWithBattery()

        pump.updateFromV1(statusV1BasalPaused())

        assert(pump.basal == null) { "the sentinel must decode as no basal, got ${pump.basal}" }
        assert(pump.isBasalPaused)
        assert(pump.isSuspended)
        assert(!pump.isDeliveryStoppedByAlarm) { "no alarm is standing - this is a plain pause" }
    }

    /**
     * The distinction `ApexPumpPlugin.cancelTempBasal` rests on. While the motor is stopped the pump
     * counts as suspended, so the loop stays off it - but it is not paused, so a temporary basal
     * booked before the stop can still be cancelled off the books. Widening isSuspended without this
     * split closed the cancel path too, which is the one command core allows in SUSPENDED_BY_PUMP.
     */
    @Test
    fun pump_aMotorStopSuspendsWithoutPausingTheBasal() {
        val pump = pumpWithBattery()

        pump.updateFromV1(statusV1WithAlarm(Alarm.ReservoirEmpty.raw))

        assert(pump.isSuspended)
        assert(!pump.isBasalPaused) { "the pump still reports a basal rate, so cancelling must stay open" }
    }

    /** A flicker must not bank progress towards recovery: the count restarts at every alarm frame. */
    @Test
    fun pump_cleanFrameCountRestartsWhenTheAlarmComesBack() {
        val pump = pumpWithBattery()

        pump.updateFromV1(statusV1WithAlarm(Alarm.ReservoirEmpty.raw))
        pump.updateFromV1(statusV1())
        assert(pump.cleanFramesSinceDeliveryStop == 1)

        pump.updateFromV1(statusV1WithAlarm(Alarm.ReservoirEmpty.raw))
        assert(pump.cleanFramesSinceDeliveryStop == 0)

        pump.updateFromV1(statusV1())
        assert(pump.isDeliveryStoppedByAlarm) { "the single clean frame before the alarm returned must not count" }
    }
}
